/*
 *
 *  *  Copyright 2010-2016 OrientDB LTD (http://orientdb.com)
 *  *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *  Unless required by applicable law or agreed to in writing, software
 *  *  distributed under the License is distributed on an "AS IS" BASIS,
 *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *  See the License for the specific language governing permissions and
 *  *  limitations under the License.
 *  *
 *  * For more information: http://orientdb.com
 *
 */

package com.orientechnologies.orient.core.storage.cache.local.twoq;

import com.orientechnologies.common.concur.lock.OInterruptedException;
import com.orientechnologies.common.concur.lock.OPartitionedLockManager;
import com.orientechnologies.common.concur.lock.OReadersWriterSpinLock;
import com.orientechnologies.common.exception.OException;
import com.orientechnologies.common.log.OLogManager;
import com.orientechnologies.common.types.OModifiableBoolean;
import com.orientechnologies.orient.core.Orient;
import com.orientechnologies.orient.core.config.OGlobalConfiguration;
import com.orientechnologies.orient.core.exception.OAllCacheEntriesAreUsedException;
import com.orientechnologies.orient.core.exception.OStorageException;
import com.orientechnologies.orient.core.storage.cache.OAbstractWriteCache;
import com.orientechnologies.orient.core.storage.cache.OCacheEntry;
import com.orientechnologies.orient.core.storage.cache.OCachePointer;
import com.orientechnologies.orient.core.storage.cache.OReadCache;
import com.orientechnologies.orient.core.storage.cache.OWriteCache;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Set;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.Lock;

/**
 * @author Andrey Lomakin (a.lomakin-at-orientdb.com)
 * @since 7/24/13
 */
public final class O2QCache implements OReadCache {
  /**
   * Maximum amount of times when we will show message that limit of pinned pages was exhausted.
   */
  private static final int MAX_AMOUNT_OF_WARNINGS_PINNED_PAGES = 10;

  /**
   * Maximum percent of pinned pages which may be contained in this cache.
   */
  private static final int MAX_PERCENT_OF_PINED_PAGES = 50;

  /**
   * Minimum size of memory which may be allocated by cache (in pages). This parameter is used only if related flag is set in
   * constrictor of cache.
   */
  public static final int MIN_CACHE_SIZE = 256;

  /**
   * File which contains stored state of disk cache after storage close.
   */
  public static final String CACHE_STATE_FILE = "cache.stt";

  /**
   * Extension for file which contains stored state of disk cache after storage close.
   */
  public static final String CACHE_STATISTIC_FILE_EXTENSION = ".stt";

  private final LRUList am;
  private final LRUList a1out;
  private final LRUList a1in;
  private final int     pageSize;

  /**
   * Counts how much time we warned user that limit of amount of pinned pages is reached.
   */
  private final AtomicInteger pinnedPagesWarningCounter = new AtomicInteger();

  private final AtomicReference<MemoryData> memoryDataContainer = new AtomicReference<>();

  /**
   * Contains all pages in cache for given file.
   */
  private final ConcurrentMap<Long, Set<Long>> filePages;

  /**
   * Maximum percent of pinned pages which may be hold in this cache.
   *
   * @see com.orientechnologies.orient.core.config.OGlobalConfiguration#DISK_CACHE_PINNED_PAGES
   */
  private final int percentOfPinnedPages;

  private final LongAdder cacheRequests = new LongAdder();
  private final LongAdder cacheHits     = new LongAdder();

  private final OReadersWriterSpinLock                 cacheLock       = new OReadersWriterSpinLock();
  private final OPartitionedLockManager<Object>        fileLockManager = new OPartitionedLockManager<>(true);
  private final OPartitionedLockManager<PageKey>       pageLockManager = new OPartitionedLockManager<>();
  private final ConcurrentMap<PinnedPage, OCacheEntry> pinnedPages     = new ConcurrentHashMap<>();

  /**
   * @param readCacheMaxMemory   Maximum amount of direct memory which can allocated by disk cache in bytes.
   * @param pageSize             Cache page size in bytes.
   * @param checkMinSize         If this flat is set size of cache may be {@link #MIN_CACHE_SIZE} or bigger.
   * @param percentOfPinnedPages Maximum percent of pinned pages which may be hold by this cache.
   *
   * @see #MAX_PERCENT_OF_PINED_PAGES
   */
  public O2QCache(final long readCacheMaxMemory, final int pageSize, final boolean checkMinSize, final int percentOfPinnedPages,
      boolean printCacheStatistics, int cacheStatisticsInterval) {
    boolean printCacheStatistics1 = printCacheStatistics;
    int cacheStatisticsInterval1 = cacheStatisticsInterval;
    if (percentOfPinnedPages > MAX_PERCENT_OF_PINED_PAGES)
      throw new IllegalArgumentException(
          "Percent of pinned pages cannot be more than " + percentOfPinnedPages + " but passed value is " + percentOfPinnedPages);

    this.percentOfPinnedPages = percentOfPinnedPages;

    cacheLock.acquireWriteLock();
    try {
      this.pageSize = pageSize;

      this.filePages = new ConcurrentHashMap<>();

      int normalizedSize = normalizeMemory(readCacheMaxMemory, pageSize);

      if (checkMinSize && normalizedSize < MIN_CACHE_SIZE)
        normalizedSize = MIN_CACHE_SIZE;

      final MemoryData memoryData = new MemoryData(normalizedSize, 0);
      this.memoryDataContainer.set(memoryData);

      am = new ConcurrentLRUList();
      a1out = new ConcurrentLRUList();
      a1in = new ConcurrentLRUList();

      if (printCacheStatistics) {
        Orient.instance().scheduleTask(new TimerTask() {
          @Override
          public void run() {
            long cacheRequests = O2QCache.this.cacheRequests.sum();
            long cacheHits = O2QCache.this.cacheHits.sum();

            final MemoryData memoryData = memoryDataContainer.get();

            OLogManager.instance().infoNoDb(this, "Read cache stat: cache hits %d percents, cache size is %d percent",
                cacheRequests > 0 ? 100 * cacheHits / cacheRequests : -1,
                100 * (am.size() + a1in.size() + memoryData.pinnedPages) / memoryData.maxSize);

            O2QCache.this.cacheRequests.add(-cacheRequests);
            O2QCache.this.cacheHits.add(-cacheHits);
          }
        }, cacheStatisticsInterval * 1_000L, cacheStatisticsInterval * 1_000L);
      }

    } finally {
      cacheLock.releaseWriteLock();
    }
  }

  LRUList getAm() {
    return am;
  }

  @SuppressWarnings("SameParameterValue")
  boolean inPinnedPages(final long fileId, final long pageIndex) {
    return pinnedPages.containsKey(new PinnedPage(fileId, pageIndex));
  }

  LRUList getA1out() {
    return a1out;
  }

  LRUList getA1in() {
    return a1in;
  }

  @Override
  public long addFile(final String fileName, final OWriteCache writeCache) throws IOException {
    cacheLock.acquireWriteLock();
    try {
      final long fileId = writeCache.addFile(fileName);
      final Set<Long> oldPages = filePages.put(fileId, Collections.newSetFromMap(new ConcurrentHashMap<>()));
      assert oldPages == null || oldPages.isEmpty();
      return fileId;
    } finally {
      cacheLock.releaseWriteLock();
    }
  }

  @Override
  public long addFile(final String fileName, long fileId, final OWriteCache writeCache) throws IOException {
    fileId = OAbstractWriteCache.checkFileIdCompatibility(writeCache.getId(), fileId);

    cacheLock.acquireWriteLock();
    try {
      final long fid = writeCache.addFile(fileName, fileId);
      final Set<Long> oldPages = filePages.put(fid, Collections.newSetFromMap(new ConcurrentHashMap<>()));
      assert oldPages == null || oldPages.isEmpty();

      return fid;
    } finally {
      cacheLock.releaseWriteLock();
    }
  }

  @Override
  public OCacheEntry loadForWrite(final long fileId, final long pageIndex, final boolean checkPinnedPages,
      final OWriteCache writeCache, final int pageCount, final boolean verifyChecksums) throws IOException {
    final OCacheEntry cacheEntry = doLoad(fileId, pageIndex, checkPinnedPages, writeCache, pageCount, verifyChecksums);

    if (cacheEntry != null) {
      cacheEntry.acquireExclusiveLock();
      cacheEntry.setDirty();
      writeCache.updateDirtyPagesTable(cacheEntry.getCachePointer());
    }

    return cacheEntry;
  }

  @Override
  public void releaseFromWrite(final OCacheEntry cacheEntry, final OWriteCache writeCache) {
    final OCachePointer cachePointer = cacheEntry.getCachePointer();
    assert cachePointer != null;

    final Lock fileLock;
    final Lock pageLock;
    cacheLock.acquireReadLock();
    try {
      fileLock = fileLockManager.acquireSharedLock(cacheEntry.getFileId());
      try {
        pageLock = pageLockManager.acquireExclusiveLock(new PageKey(cacheEntry.getFileId(), cacheEntry.getPageIndex()));
        try {
          cacheEntry.decrementUsages();

          assert cacheEntry.getUsagesCount() >= 0;
          writeCache.store(cacheEntry.getFileId(), cacheEntry.getPageIndex(), cacheEntry.getCachePointer());
        } finally {
          pageLock.unlock();
        }
      } finally {
        fileLock.unlock();
      }
    } finally {
      cacheLock.releaseReadLock();
    }

    //We need to release exclusive lock from cache pointer after we put it into the write cache so both "dirty pages" of write
    //cache and write cache itself will contain actual values simultaneously. But because cache entry can be cleared after we put it back to the
    //read cache we make copy of cache pointer before head.
    //
    //Following situation can happen, if we release exclusive lock before we put entry to the write cache.
    //1. Page is loaded for write, locked and related LSN is written to the "dirty pages" table.
    //2. Page lock is released.
    //3. Page is chosen to be flushed on disk and its entry removed from "dirty pages" table
    //4. Page is added to write cache as dirty
    //
    //So we have situation when page is added as dirty into the write cache but its related entry in "dirty pages" table is removed
    //it is treated as flushed during fuzzy checkpoint and portion of write ahead log which contains not flushed changes is removed.
    //This can lead to the data loss after restore and corruption of data structures
    cachePointer.releaseExclusiveLock();
  }

  @Override
  public OCacheEntry loadForRead(final long fileId, final long pageIndex, final boolean checkPinnedPages,
      final OWriteCache writeCache, final int pageCount, final boolean verifyChecksums) throws IOException {
    final OCacheEntry cacheEntry = doLoad(fileId, pageIndex, checkPinnedPages, writeCache, pageCount, verifyChecksums);

    if (cacheEntry != null) {
      cacheEntry.acquireSharedLock();
    }

    return cacheEntry;
  }

  @Override
  public void releaseFromRead(final OCacheEntry cacheEntry, final OWriteCache writeCache) {
    cacheEntry.releaseSharedLock();

    doRelease(cacheEntry);
  }

  private void doRelease(final OCacheEntry cacheEntry) {
    final Lock fileLock;
    final Lock pageLock;
    cacheLock.acquireReadLock();
    try {
      fileLock = fileLockManager.acquireSharedLock(cacheEntry.getFileId());
      try {
        pageLock = pageLockManager.acquireExclusiveLock(new PageKey(cacheEntry.getFileId(), cacheEntry.getPageIndex()));
        try {
          cacheEntry.decrementUsages();

          assert cacheEntry.getUsagesCount() >= 0;
          assert cacheEntry.getUsagesCount() > 0 || !cacheEntry.isLockAcquiredByCurrentThread();
        } finally {
          pageLock.unlock();
        }
      } finally {
        fileLock.unlock();
      }
    } finally {
      cacheLock.releaseReadLock();
    }
  }

  @Override
  public void pinPage(final OCacheEntry cacheEntry, OWriteCache writeCache) {
    final Lock fileLock;
    final Lock pageLock;

    MemoryData memoryData = memoryDataContainer.get();

    if ((100 * (memoryData.pinnedPages + 1)) / memoryData.maxSize > percentOfPinnedPages) {
      if (pinnedPagesWarningCounter.get() < MAX_AMOUNT_OF_WARNINGS_PINNED_PAGES) {

        final long warnings = pinnedPagesWarningCounter.getAndIncrement();
        if (warnings < MAX_AMOUNT_OF_WARNINGS_PINNED_PAGES) {
          OLogManager.instance().warn(this, "Maximum amount of pinned pages is reached, given page " + cacheEntry
              + " will not be marked as pinned which may lead to performance degradation. You may consider to increase the percent of pinned pages "
              + "by changing the property '" + OGlobalConfiguration.DISK_CACHE_PINNED_PAGES.getKey() + "'");
        }
      }

      return;
    }

    cacheLock.acquireReadLock();
    try {
      fileLock = fileLockManager.acquireSharedLock(cacheEntry.getFileId());
      final PageKey k = new PageKey(cacheEntry.getFileId(), cacheEntry.getPageIndex());
      try {
        pageLock = pageLockManager.acquireExclusiveLock(k);
        try {
          remove(cacheEntry.getFileId(), cacheEntry.getPageIndex());
          pinnedPages.put(new PinnedPage(cacheEntry.getFileId(), cacheEntry.getPageIndex()), cacheEntry);
        } finally {
          pageLock.unlock();
        }
      } finally {
        fileLock.unlock();
      }
    } finally {
      cacheLock.releaseReadLock();
    }

    MemoryData newMemoryData = new MemoryData(memoryData.maxSize, memoryData.pinnedPages + 1);

    while (!memoryDataContainer.compareAndSet(memoryData, newMemoryData)) {
      memoryData = memoryDataContainer.get();
      newMemoryData = new MemoryData(memoryData.maxSize, memoryData.pinnedPages + 1);
    }

    removeColdestPagesIfNeeded(writeCache);
  }

  /**
   * Changes amount of memory which may be used by given cache. This method may consume many resources if amount of memory provided
   * in parameter is much less than current amount of memory.
   *
   * @param readCacheMaxMemory New maximum size of cache in bytes.
   *
   * @throws IllegalStateException In case of new size of disk cache is too small to hold existing pinned pages.
   */
  public void changeMaximumAmountOfMemory(final long readCacheMaxMemory) throws IllegalStateException {
    MemoryData memoryData;
    MemoryData newMemoryData;

    final int newMemorySize = normalizeMemory(readCacheMaxMemory, pageSize);
    do {
      memoryData = memoryDataContainer.get();

      if (memoryData.maxSize == newMemorySize)
        return;

      if ((100 * memoryData.pinnedPages / newMemorySize) > percentOfPinnedPages) {
        throw new IllegalStateException("Cannot decrease amount of memory used by disk cache "
            + "because limit of pinned pages will be more than allowed limit " + percentOfPinnedPages);
      }

      newMemoryData = new MemoryData(newMemorySize, memoryData.pinnedPages);
    } while (!memoryDataContainer.compareAndSet(memoryData, newMemoryData));

//    if (newMemorySize < memoryData.maxSize)
//      removeColdestPagesIfNeeded();

    OLogManager.instance()
        .info(this, "Disk cache size was changed from " + memoryData.maxSize + " pages to " + newMemorySize + " pages");
  }

  private OCacheEntry doLoad(long fileId, final long pageIndex, final boolean checkPinnedPages, final OWriteCache writeCache,
      final int pageCount, final boolean verifyChecksums) throws IOException {

    fileId = OAbstractWriteCache.checkFileIdCompatibility(writeCache.getId(), fileId);

    final UpdateCacheResult cacheResult = doLoad(fileId, pageIndex, checkPinnedPages, false, writeCache, pageCount,
        verifyChecksums);
    if (cacheResult == null) {
      return null;
    }

    try {
      if (cacheResult.removeColdPages) {
        removeColdestPagesIfNeeded(writeCache);
      }
    } catch (final RuntimeException e) {
      releaseFromWrite(cacheResult.cacheEntry, writeCache);
      throw e;
    }

    cacheRequests.increment();
    if (cacheResult.cacheHit) {
      cacheHits.increment();
    }

    return cacheResult.cacheEntry;

  }

  private UpdateCacheResult doLoad(final long fileId, final long pageIndex, final boolean checkPinnedPages,
      final boolean addNewPages, final OWriteCache writeCache, final int pageCount, final boolean verifyChecksums)
      throws IOException {

    if (pageCount < 1)
      throw new IllegalArgumentException(
          "Amount of pages to load from cache should be not less than 1 but passed value is " + pageCount);

    boolean removeColdPages = false;
    OCacheEntry cacheEntry = null;
    boolean cacheHit;

    final Lock fileLock;
    final Lock[] pageLocks;

    cacheLock.acquireReadLock();
    try {
      fileLock = fileLockManager.acquireSharedLock(fileId);
      try {
        final PageKey[] pageKeys = new PageKey[pageCount];

        for (int i = 0; i < pageKeys.length; i++) {
          pageKeys[i] = new PageKey(fileId, pageIndex + i);

        }
        if (checkPinnedPages) {
          cacheEntry = pinnedPages.get(new PinnedPage(fileId, pageIndex));

          if (cacheEntry != null) {
            cacheEntry.incrementUsages();
            return new UpdateCacheResult(false, cacheEntry, true);
          }
        }

        pageLocks = pageLockManager.acquireExclusiveLocksInBatch(pageKeys);
        try {
          if (checkPinnedPages) {
            cacheEntry = pinnedPages.get(new PinnedPage(fileId, pageIndex));
          }

          if (cacheEntry == null) {
            final UpdateCacheResult cacheResult = updateCache(fileId, pageIndex, addNewPages, writeCache, pageCount,
                verifyChecksums);
            if (cacheResult == null) {
              return null;
            }

            cacheEntry = cacheResult.cacheEntry;
            removeColdPages = cacheResult.removeColdPages;
            cacheHit = cacheResult.cacheHit;
          } else {
            cacheHit = true;
          }

          cacheEntry.incrementUsages();
        } finally {
          for (final Lock pageLock : pageLocks) {
            pageLock.unlock();
          }
        }
      } finally {
        fileLock.unlock();
      }
    } finally {
      cacheLock.releaseReadLock();
    }

    return new UpdateCacheResult(removeColdPages, cacheEntry, cacheHit);
  }

  @Override
  public OCacheEntry allocateNewPage(long fileId, final OWriteCache writeCache, final boolean verifyChecksums) throws IOException {
    fileId = OAbstractWriteCache.checkFileIdCompatibility(writeCache.getId(), fileId);

    UpdateCacheResult cacheResult;

    final Lock fileLock;
    cacheLock.acquireReadLock();
    try {
      fileLock = fileLockManager.acquireExclusiveLock(fileId);
      try {
        final long filledUpTo = writeCache.getFilledUpTo(fileId);
        assert filledUpTo >= 0;
        cacheResult = doLoad(fileId, filledUpTo, false, true, writeCache, 1, verifyChecksums);
      } finally {
        fileLock.unlock();
      }
    } finally {
      cacheLock.releaseReadLock();
    }

    cacheRequests.increment();
    cacheHits.increment();

    assert cacheResult != null;

    try {
      if (cacheResult.removeColdPages)
        removeColdestPagesIfNeeded(writeCache);
    } catch (final RuntimeException e) {
      doRelease(cacheResult.cacheEntry);
      throw e;
    }

    final OCacheEntry cacheEntry = cacheResult.cacheEntry;

    if (cacheEntry != null) {
      cacheEntry.acquireExclusiveLock();
      cacheEntry.setDirty();
      writeCache.updateDirtyPagesTable(cacheEntry.getCachePointer());
    }

    return cacheResult.cacheEntry;
  }

  @Override
  public void clear() {
    cacheLock.acquireWriteLock();
    try {
      clearCacheContent();
    } finally {
      cacheLock.releaseWriteLock();
    }
  }

  @Override
  public void truncateFile(long fileId, final OWriteCache writeCache) throws IOException {
    final Lock fileLock;
    fileId = OAbstractWriteCache.checkFileIdCompatibility(writeCache.getId(), fileId);

    cacheLock.acquireReadLock();
    try {
      fileLock = fileLockManager.acquireExclusiveLock(fileId);
      try {

        writeCache.truncateFile(fileId);

        clearFile(fileId);
      } finally {
        fileLock.unlock();
      }
    } finally {
      cacheLock.releaseReadLock();
    }
  }

  private void clearFile(final long fileId) {
    final Set<Long> pageEntries = filePages.get(fileId);
    if (pageEntries == null || pageEntries.isEmpty()) {
      assert get(fileId, 0) == null;
      return;
    }

    for (final Long pageIndex : pageEntries) {
      OCacheEntry cacheEntry = get(fileId, pageIndex);

      if (cacheEntry == null)
        cacheEntry = pinnedPages.get(new PinnedPage(fileId, pageIndex));

      if (cacheEntry != null) {
        if (cacheEntry.getUsagesCount() == 0) {
          cacheEntry = remove(fileId, pageIndex);

          if (cacheEntry == null) {
            MemoryData memoryData = memoryDataContainer.get();
            cacheEntry = pinnedPages.remove(new PinnedPage(fileId, pageIndex));

            MemoryData newMemoryData = new MemoryData(memoryData.maxSize, memoryData.pinnedPages - 1);

            while (!memoryDataContainer.compareAndSet(memoryData, newMemoryData)) {
              memoryData = memoryDataContainer.get();
              newMemoryData = new MemoryData(memoryData.maxSize, memoryData.pinnedPages - 1);
            }
          }

          final OCachePointer cachePointer = cacheEntry.getCachePointer();
          if (cachePointer != null) {
            cachePointer.decrementReadersReferrer();
            cacheEntry.clearCachePointer();
          }

        } else
          throw new OStorageException(
              "Page with index " + pageIndex + " for file with id " + fileId + " cannot be freed because it is used.");
      } else
        throw new OStorageException("Page with index " + pageIndex + " was  not found in cache for file with id " + fileId);
    }

    assert get(fileId, 0) == null;

    pageEntries.clear();
  }

  @Override
  public void closeFile(long fileId, final boolean flush, final OWriteCache writeCache) {
    fileId = OAbstractWriteCache.checkFileIdCompatibility(writeCache.getId(), fileId);

    final Lock fileLock;
    cacheLock.acquireReadLock();
    try {
      fileLock = fileLockManager.acquireExclusiveLock(fileId);
      try {
        writeCache.close(fileId, flush);

        clearFile(fileId);

      } finally {
        fileLock.unlock();
      }

    } finally {
      cacheLock.releaseReadLock();
    }
  }

  @Override
  public void deleteFile(long fileId, final OWriteCache writeCache) throws IOException {
    fileId = OAbstractWriteCache.checkFileIdCompatibility(writeCache.getId(), fileId);

    final Lock fileLock;

    cacheLock.acquireReadLock();
    try {
      fileLock = fileLockManager.acquireExclusiveLock(fileId);
      try {
        clearFile(fileId);
        filePages.remove(fileId);
        writeCache.deleteFile(fileId);
      } finally {
        fileLock.unlock();
      }
    } finally {
      cacheLock.releaseReadLock();
    }
  }

  /**
   * Performs following steps:
   * <ol>
   * <li>If flag {@link OGlobalConfiguration#STORAGE_KEEP_DISK_CACHE_STATE} is set to <code>true</code> saves state of all queues of
   * 2Q cache into file {@link #CACHE_STATE_FILE}.The only exception is pinned pages they need to pinned again.</li>
   * <li>Closes all files and flushes all data associated to them.</li>
   * </ol>
   *
   * @param writeCache Write cache all files of which should be closed. In terms of cache write cache = storage.
   */
  @Override
  public void closeStorage(final OWriteCache writeCache) throws IOException {
    if (writeCache == null) {
      return;
    }

    cacheLock.acquireWriteLock();
    try {
      final long[] filesToClear = writeCache.close();

      for (final long fileId : filesToClear) {
        clearFile(fileId);
      }

    } finally {
      cacheLock.releaseWriteLock();
    }
  }

  /**
   * Loads state of 2Q cache queues stored during storage close {@link #closeStorage(OWriteCache)} back into memory if flag
   * {@link OGlobalConfiguration#STORAGE_KEEP_DISK_CACHE_STATE} is set to <code>true</code>.
   * If maximum size of cache was decreased cache state will not be restored.
   *
   * @param writeCache Write cache is used to load pages back into cache if needed.
   *
   * @see #closeStorage(OWriteCache)
   */
  @Override
  public void loadCacheState(final OWriteCache writeCache) {
  }

  /**
   * Stores state of queues of 2Q cache inside of {@link #CACHE_STATE_FILE} file if flag
   * {@link OGlobalConfiguration#STORAGE_KEEP_DISK_CACHE_STATE} is set to <code>true</code>.
   * Following format is used to store queue state:
   * <ol>
   * <li>Max cache size, single item (long)</li>
   * <li>File id or -1 if end of queue is reached (int)</li>
   * <li>Page index (long), is absent if end of the queue is reached</li>
   * </ol>
   *
   * @param writeCache Write cache which manages files cache state of which is going to be stored.
   */
  @Override
  public void storeCacheState(final OWriteCache writeCache) {
  }

  @Override
  public void deleteStorage(final OWriteCache writeCache) throws IOException {
    cacheLock.acquireWriteLock();
    try {
      final long[] filesToClear = writeCache.delete();
      for (final long fileId : filesToClear)
        clearFile(fileId);

      final Path rootDirectory = writeCache.getRootDirectory();
      final Path stateFile = rootDirectory.resolve(CACHE_STATE_FILE);

      if (Files.exists(stateFile)) {
        Files.delete(stateFile);
      }

    } finally {
      cacheLock.releaseWriteLock();
    }
  }

  private OCacheEntry get(final long fileId, final long pageIndex) {
    OCacheEntry cacheEntry = am.get(fileId, pageIndex);

    if (cacheEntry != null) {
      assert filePages.get(fileId) != null;
      assert filePages.get(fileId).contains(pageIndex);

      return cacheEntry;
    }

    cacheEntry = a1out.get(fileId, pageIndex);
    if (cacheEntry != null) {
      assert filePages.get(fileId) != null;
      assert filePages.get(fileId).contains(pageIndex);

      return cacheEntry;
    }

    cacheEntry = a1in.get(fileId, pageIndex);
    return cacheEntry;
  }

  private void clearCacheContent() {
    for (final OCacheEntry cacheEntry : am)
      if (cacheEntry.getUsagesCount() == 0) {
        final OCachePointer cachePointer = cacheEntry.getCachePointer();
        cachePointer.decrementReadersReferrer();
        cacheEntry.clearCachePointer();
      } else
        throw new OStorageException("Page with index " + cacheEntry.getPageIndex() + " for file id " + cacheEntry.getFileId()
            + " is used and cannot be removed");

    for (final OCacheEntry cacheEntry : a1in)
      if (cacheEntry.getUsagesCount() == 0) {
        final OCachePointer cachePointer = cacheEntry.getCachePointer();
        cachePointer.decrementReadersReferrer();
        cacheEntry.clearCachePointer();
      } else
        throw new OStorageException("Page with index " + cacheEntry.getPageIndex() + " for file id " + cacheEntry.getFileId()
            + " is used and cannot be removed");

    a1out.clear();
    am.clear();
    a1in.clear();

    for (final Set<Long> pages : filePages.values())
      pages.clear();

    clearPinnedPages();
  }

  private void clearPinnedPages() {
    for (final OCacheEntry pinnedEntry : pinnedPages.values()) {
      if (pinnedEntry.getUsagesCount() == 0) {
        final OCachePointer cachePointer = pinnedEntry.getCachePointer();
        cachePointer.decrementReadersReferrer();
        pinnedEntry.clearCachePointer();

        MemoryData memoryData = memoryDataContainer.get();
        MemoryData newMemoryData = new MemoryData(memoryData.maxSize, memoryData.pinnedPages - 1);

        while (!memoryDataContainer.compareAndSet(memoryData, newMemoryData)) {
          memoryData = memoryDataContainer.get();
          newMemoryData = new MemoryData(memoryData.maxSize, memoryData.pinnedPages - 1);
        }
      } else
        throw new OStorageException("Page with index " + pinnedEntry.getPageIndex() + " for file with id " + pinnedEntry.getFileId()
            + "cannot be freed because it is used.");
    }

    pinnedPages.clear();
  }

  @SuppressWarnings("SameReturnValue")
  private boolean entryIsInAmQueue(final long fileId, final long pageIndex, final OCacheEntry cacheEntry) {
    assert filePages.get(fileId) != null;
    assert filePages.get(fileId).contains(pageIndex);

    am.putToMRU(cacheEntry);

    return false;
  }

  @SuppressWarnings("SameReturnValue")
  private boolean entryWasInA1OutQueue(final long fileId, final long pageIndex, final OCachePointer dataPointer,
      final OCacheEntry cacheEntry) {
    assert filePages.get(fileId) != null;
    assert filePages.get(fileId).contains(pageIndex);

    assert dataPointer != null;
    assert cacheEntry.getCachePointer() == null;

    cacheEntry.setCachePointer(dataPointer);

    am.putToMRU(cacheEntry);

    return true;
  }

  @SuppressWarnings("SameReturnValue")
  private boolean entryIsInA1InQueue(final long fileId, final long pageIndex) {
    assert filePages.get(fileId) != null;
    assert filePages.get(fileId).contains(pageIndex);

    return false;
  }

  private UpdateCacheResult entryIsAbsentInQueues(final long fileId, final long pageIndex, final OCachePointer dataPointer) {
    final OCacheEntry cacheEntry;
    cacheEntry = new OCacheEntry(fileId, pageIndex, dataPointer);
    a1in.putToMRU(cacheEntry);

    Set<Long> pages = filePages.get(fileId);
    if (pages == null) {
      pages = Collections.newSetFromMap(new ConcurrentHashMap<>());
      final Set<Long> oldPages = filePages.putIfAbsent(fileId, pages);

      if (oldPages != null)
        pages = oldPages;
    }

    pages.add(pageIndex);

    return new UpdateCacheResult(true, cacheEntry, true);
  }

  private UpdateCacheResult updateCache(final long fileId, final long pageIndex, final boolean addNewPages,
      final OWriteCache writeCache, final int pageCount, final boolean verifyChecksums) throws IOException {

    assert pageCount > 0;

    boolean cacheHit;
    OCacheEntry cacheEntry = am.get(fileId, pageIndex);

    if (cacheEntry != null) {
      return new UpdateCacheResult(entryIsInAmQueue(fileId, pageIndex, cacheEntry), cacheEntry, true);
    }

    boolean removeColdPages;
    OCachePointer[] dataPointers = null;

    cacheEntry = a1out.remove(fileId, pageIndex);

    OModifiableBoolean writeCacheHit = new OModifiableBoolean();
    if (cacheEntry != null) {
      dataPointers = writeCache.load(fileId, pageIndex, pageCount, false, writeCacheHit, verifyChecksums);
      cacheHit = writeCacheHit.getValue();

      final OCachePointer dataPointer = dataPointers[0];
      removeColdPages = entryWasInA1OutQueue(fileId, pageIndex, dataPointer, cacheEntry);
    } else {
      cacheEntry = a1in.get(fileId, pageIndex);

      if (cacheEntry != null) {
        removeColdPages = entryIsInA1InQueue(fileId, pageIndex);
        cacheHit = true;
      } else {
        dataPointers = writeCache.load(fileId, pageIndex, pageCount, addNewPages, writeCacheHit, verifyChecksums);
        cacheHit = writeCacheHit.getValue();

        if (dataPointers.length == 0) {
          return null;
        }

        final OCachePointer dataPointer = dataPointers[0];
        final UpdateCacheResult ucr = entryIsAbsentInQueues(fileId, pageIndex, dataPointer);
        cacheEntry = ucr.cacheEntry;
        removeColdPages = ucr.removeColdPages;
      }
    }

    if (dataPointers != null) {
      for (int n = 1; n < dataPointers.length; n++) {
        removeColdPages = processFetchedPage(removeColdPages, dataPointers[n]);
      }
    }

    return new UpdateCacheResult(removeColdPages, cacheEntry, cacheHit);
  }

  private boolean processFetchedPage(boolean removeColdPages, final OCachePointer dataPointer) {
    final long fileId = dataPointer.getFileId();
    final long pageIndex = dataPointer.getPageIndex();

    if (pinnedPages.containsKey(new PinnedPage(fileId, pageIndex))) {
      dataPointer.decrementReadersReferrer();

      return removeColdPages;
    }

    OCacheEntry cacheEntry = am.get(fileId, pageIndex);
    if (cacheEntry != null) {
      final boolean rcp = entryIsInAmQueue(fileId, pageIndex, cacheEntry);
      removeColdPages = removeColdPages || rcp;
      dataPointer.decrementReadersReferrer();

      return removeColdPages;
    }

    cacheEntry = a1out.remove(fileId, pageIndex);
    if (cacheEntry != null) {
      final boolean rcp = entryWasInA1OutQueue(fileId, pageIndex, dataPointer, cacheEntry);
      removeColdPages = removeColdPages || rcp;
      return removeColdPages;
    }

    cacheEntry = a1in.get(fileId, pageIndex);
    if (cacheEntry != null) {
      final boolean rcp = entryIsInA1InQueue(fileId, pageIndex);
      removeColdPages = removeColdPages || rcp;

      dataPointer.decrementReadersReferrer();

      return removeColdPages;
    }

    final boolean rcp = entryIsAbsentInQueues(fileId, pageIndex, dataPointer).removeColdPages;
    removeColdPages = removeColdPages || rcp;
    return removeColdPages;
  }

  private void removeColdestPagesIfNeeded(OWriteCache writeCache) {
    final MemoryData memoryData = this.memoryDataContainer.get();
    if (am.size() + a1in.size() > memoryData.get2QCacheSize()) {
      try {
        writeCache.checkCacheOverflow();
      } catch (InterruptedException e) {
        throw OException.wrapException(new OInterruptedException("Check of write cache overflow was interrupted"), e);
      }
    }

    cacheLock.acquireWriteLock();
    try {
      while (am.size() + a1in.size() > memoryData.get2QCacheSize()) {
        if (a1in.size() > memoryData.K_IN) {
          final OCacheEntry removedFromAInEntry = a1in.removeLRU();
          if (removedFromAInEntry == null) {
            throw new OAllCacheEntriesAreUsedException("All records in aIn queue in 2q cache are used!");
          } else {
            assert removedFromAInEntry.getUsagesCount() == 0;

            final OCachePointer cachePointer = removedFromAInEntry.getCachePointer();
            //cache pointer can be null if we load initial state of cache from disk
            //see #restoreQueueWithPageLoad for details
            if (cachePointer != null) {
              cachePointer.decrementReadersReferrer();
              removedFromAInEntry.clearCachePointer();
            }
            a1out.putToMRU(removedFromAInEntry);
          }

          while (a1out.size() > memoryData.K_OUT) {
            final OCacheEntry removedEntry = a1out.removeLRU();

            assert removedEntry.getUsagesCount() == 0;
            assert removedEntry.getCachePointer() == null;

            final Set<Long> pageEntries = filePages.get(removedEntry.getFileId());
            pageEntries.remove(removedEntry.getPageIndex());
          }
        } else {
          final OCacheEntry removedEntry = am.removeLRU();

          if (removedEntry == null) {
            throw new OAllCacheEntriesAreUsedException("All records in aIn queue in 2q cache are used!");
          } else {
            assert removedEntry.getUsagesCount() == 0;

            final OCachePointer cachePointer = removedEntry.getCachePointer();
            //cache pointer can be null if we load initial state of cache from disk
            //see #restoreQueueWithPageLoad for details
            if (cachePointer != null) {
              cachePointer.decrementReadersReferrer();
              removedEntry.clearCachePointer();
            }

            final Set<Long> pageEntries = filePages.get(removedEntry.getFileId());
            pageEntries.remove(removedEntry.getPageIndex());
          }
        }
      }
    } finally {
      cacheLock.releaseWriteLock();
    }
  }

  int getMaxSize() {
    return memoryDataContainer.get().maxSize;
  }

  @Override
  public long getUsedMemory() {
    return ((long) (am.size() + a1in.size())) * pageSize;
  }

  private OCacheEntry remove(final long fileId, final long pageIndex) {
    OCacheEntry cacheEntry = am.remove(fileId, pageIndex);
    if (cacheEntry != null) {
      if (cacheEntry.getUsagesCount() > 1)
        throw new IllegalStateException("Record cannot be removed because it is used!");
      return cacheEntry;
    }

    cacheEntry = a1out.remove(fileId, pageIndex);
    if (cacheEntry != null)
      return cacheEntry;

    cacheEntry = a1in.remove(fileId, pageIndex);
    if (cacheEntry != null && cacheEntry.getUsagesCount() > 1)
      throw new IllegalStateException("Record cannot be removed because it is used!");

    return cacheEntry;
  }

  private static int normalizeMemory(final long maxSize, final int pageSize) {
    final long tmpMaxSize = maxSize / pageSize;
    if (tmpMaxSize >= Integer.MAX_VALUE) {
      return Integer.MAX_VALUE;
    } else {
      return (int) tmpMaxSize;
    }
  }

  private static class PinnedPage implements Comparable<PinnedPage> {
    private final long fileId;
    private final long pageIndex;

    private PinnedPage(final long fileId, final long pageIndex) {
      this.fileId = fileId;
      this.pageIndex = pageIndex;
    }

    @Override
    public boolean equals(final Object o) {
      if (this == o)
        return true;
      if (o == null || getClass() != o.getClass())
        return false;

      final PinnedPage that = (PinnedPage) o;

      if (fileId != that.fileId)
        return false;
      if (pageIndex != that.pageIndex)
        return false;

      return true;
    }

    @Override
    public String toString() {
      return "PinnedPage{" + "fileId=" + fileId + ", pageIndex=" + pageIndex + '}';
    }

    @Override
    public int hashCode() {
      int result = (int) (fileId ^ (fileId >>> 32));
      result = 31 * result + (int) (pageIndex ^ (pageIndex >>> 32));
      return result;
    }

    @Override
    public int compareTo(final PinnedPage other) {
      if (fileId > other.fileId)
        return 1;
      if (fileId < other.fileId)
        return -1;

      return Long.compare(pageIndex, other.pageIndex);

    }
  }

  private static final class PageKey implements Comparable<PageKey> {
    private final long fileId;
    private final long pageIndex;

    private PageKey(final long fileId, final long pageIndex) {
      this.fileId = fileId;
      this.pageIndex = pageIndex;
    }

    @Override
    public boolean equals(final Object o) {
      if (this == o)
        return true;
      if (o == null || getClass() != o.getClass())
        return false;

      final PageKey pageKey = (PageKey) o;

      if (fileId != pageKey.fileId)
        return false;
      if (pageIndex != pageKey.pageIndex)
        return false;

      return true;
    }

    @Override
    public int compareTo(final PageKey other) {
      if (fileId > other.fileId)
        return 1;
      if (fileId < other.fileId)
        return -1;

      return Long.compare(pageIndex, other.pageIndex);

    }

    @Override
    public int hashCode() {
      int result = (int) (fileId ^ (fileId >>> 32));
      result = 31 * result + (int) (pageIndex ^ (pageIndex >>> 32));
      return result;
    }
  }

  private final static class UpdateCacheResult {
    private final boolean     removeColdPages;
    private final OCacheEntry cacheEntry;
    private final boolean     cacheHit;

    private UpdateCacheResult(final boolean removeColdPages, final OCacheEntry cacheEntry, boolean cacheHit) {
      this.removeColdPages = removeColdPages;
      this.cacheEntry = cacheEntry;
      this.cacheHit = cacheHit;
    }
  }

  /**
   * That is immutable class which contains information about current memory limits for 2Q cache.
   * This class is needed to change all parameters atomically when cache memory limits are changed outside of 2Q cache.
   */
  private static final class MemoryData {
    /**
     * Max size for {@link O2QCache#a1in} queue in amount of pages
     */
    private final int K_IN;

    /**
     * Max size for {@link O2QCache#a1out} queue in amount of pages
     */
    private final int K_OUT;

    /**
     * Maximum size of memory consumed by 2Q cache in amount of pages.
     */
    private final int maxSize;

    /**
     * Memory consumed by pinned pages in amount of pages.
     */
    private final int pinnedPages;

    MemoryData(final int maxSize, final int pinnedPages) {
      K_IN = (maxSize - pinnedPages) >> 2;
      K_OUT = (maxSize - pinnedPages) >> 1;

      this.maxSize = maxSize;
      this.pinnedPages = pinnedPages;
    }

    /**
     * @return Maximum size of memory which may be consumed by all 2Q queues in amount of pages.
     */
    int get2QCacheSize() {
      return maxSize - pinnedPages;
    }
  }
}
