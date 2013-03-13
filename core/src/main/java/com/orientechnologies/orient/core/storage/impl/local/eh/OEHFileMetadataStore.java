/*
 * Copyright 2010-2012 Luca Garulli (l.garulli--at--orientechnologies.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.orientechnologies.orient.core.storage.impl.local.eh;

import java.io.IOException;

import com.orientechnologies.common.serialization.types.OIntegerSerializer;
import com.orientechnologies.common.serialization.types.OLongSerializer;
import com.orientechnologies.common.serialization.types.OStringSerializer;
import com.orientechnologies.orient.core.config.OStorageFileConfiguration;
import com.orientechnologies.orient.core.config.OStorageSegmentConfiguration;
import com.orientechnologies.orient.core.storage.impl.local.OMultiFileSegment;
import com.orientechnologies.orient.core.storage.impl.local.OSingleFileSegment;
import com.orientechnologies.orient.core.storage.impl.local.OStorageLocal;

/**
 * @author Andrey Lomakin
 * @since 06.02.13
 */
public class OEHFileMetadataStore extends OSingleFileSegment {
  public static final String DEF_EXTENSION = ".oem";

  public OEHFileMetadataStore(String iPath, String iType) throws IOException {
    super(iPath, iType);
  }

  public OEHFileMetadataStore(OStorageLocal iStorage, OStorageFileConfiguration iConfig) throws IOException {
    super(iStorage, iConfig);
  }

  public OEHFileMetadataStore(OStorageLocal iStorage, OStorageFileConfiguration iConfig, String iType) throws IOException {
    super(iStorage, iConfig, iType);
  }

  public void setRecordsCount(final long recordsCount) throws IOException {
    file.writeHeaderLong(0, recordsCount);
  }

  public long getRecordsCount() throws IOException {
    return file.readHeaderLong(0);
  }

  public void setTombstonesCount(long tombstonesCount) throws IOException {
    file.writeHeaderLong(OLongSerializer.LONG_SIZE, tombstonesCount);
  }

  public long getTombstonesCount() throws IOException {
    return file.readHeaderLong(OLongSerializer.LONG_SIZE);
  }

  public void storeMetadata(OEHFileMetadata[] filesMetadata) throws IOException {
    int bufferSize = 0;

    for (OEHFileMetadata bucketFile : filesMetadata) {
      if (bucketFile == null)
        break;

      final OStorageSegmentConfiguration fileConfiguration = bucketFile.getFile().getConfig();

      bufferSize += OStringSerializer.INSTANCE.getObjectSize(fileConfiguration.name);
      bufferSize += OIntegerSerializer.INT_SIZE;

      bufferSize += 2 * OLongSerializer.LONG_SIZE;
    }

    final int totalSize = bufferSize + 3 * OIntegerSerializer.INT_SIZE;
    if (file.getFilledUpTo() < totalSize)
      file.allocateSpace(totalSize - file.getFilledUpTo());

    byte[] buffer = new byte[bufferSize];
    int offset = 0;

    for (OEHFileMetadata bucketFile : filesMetadata) {
      if (bucketFile == null)
        break;

      final OStorageSegmentConfiguration fileConfiguration = bucketFile.getFile().getConfig();

      OStringSerializer.INSTANCE.serializeNative(fileConfiguration.name, buffer, offset);
      offset += OStringSerializer.INSTANCE.getObjectSizeNative(buffer, offset);

      OIntegerSerializer.INSTANCE.serializeNative(fileConfiguration.id, buffer, offset);
      offset += OStringSerializer.INSTANCE.getObjectSizeNative(buffer, offset);

      OLongSerializer.INSTANCE.serializeNative(bucketFile.geBucketsCount(), buffer, offset);
      offset += OLongSerializer.LONG_SIZE;

      OLongSerializer.INSTANCE.serializeNative(bucketFile.getTombstonePosition(), buffer, offset);
      offset += OLongSerializer.LONG_SIZE;
    }

    file.writeInt(0, filesMetadata.length);
    file.writeInt(OIntegerSerializer.INT_SIZE, buffer.length);
    file.write(2 * OIntegerSerializer.INT_SIZE, buffer);
  }

  public OEHFileMetadata[] loadMetadata(OStorageLocal storageLocal, int bucketBufferSize) throws IOException {
    final int len = file.readInt(0);
    final OEHFileMetadata[] metadata = new OEHFileMetadata[len];

    final int bufferSize = file.readInt(OIntegerSerializer.INT_SIZE);
    final byte[] buffer = new byte[bufferSize];
    file.read(2 * OIntegerSerializer.INT_SIZE, buffer, buffer.length);

    int offset = 0;
    int i = 0;
    while (offset < bufferSize) {
      final String name = OStringSerializer.INSTANCE.deserializeNative(buffer, offset);
      offset += OStringSerializer.INSTANCE.getObjectSize(name);

      final int id = OIntegerSerializer.INSTANCE.deserializeNative(buffer, offset);
      offset += OIntegerSerializer.INT_SIZE;

      final long bucketsCount = OLongSerializer.INSTANCE.deserializeNative(buffer, offset);
      offset += OLongSerializer.LONG_SIZE;

      final long tombstone = OLongSerializer.INSTANCE.deserializeNative(buffer, offset);
      offset += OLongSerializer.LONG_SIZE;

      final OStorageSegmentConfiguration fileConfiguration = new OStorageSegmentConfiguration(storage.getConfiguration(), name, id);

      final OMultiFileSegment multiFileSegment = new OMultiFileSegment(storage, fileConfiguration, OEHFileMetadata.DEF_EXTENSION,
          bucketBufferSize);
      final OEHFileMetadata bucketFile = new OEHFileMetadata();
      bucketFile.setFile(multiFileSegment);
      bucketFile.setBucketsCount(bucketsCount);
      bucketFile.setTombstonePosition(tombstone);

      metadata[i] = bucketFile;
      i++;
    }

    return metadata;
  }
}
