/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements.  See the NOTICE file distributed with this work for additional information regarding copyright ownership.  The ASF licenses this file to you under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the License.  You may obtain a copy of the License at      http://www.apache.org/licenses/LICENSE-2.0  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the License for the specific language governing permissions and limitations under the License.
 */

package org.apache.iotdb.cluster.log.manage;

import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import org.apache.iotdb.cluster.log.Log;
import org.apache.iotdb.cluster.log.LogApplier;
import org.apache.iotdb.cluster.log.snapshot.FileSnapshot;
import org.apache.iotdb.cluster.partition.PartitionTable;
import org.apache.iotdb.cluster.utils.PartitionUtils;
import org.apache.iotdb.db.engine.StorageEngine;
import org.apache.iotdb.db.engine.storagegroup.TsFileResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Different from PartitionedSnapshotLogManager, FilePartitionedSnapshotLogManager does not store
 * the committed in memory, it considers the logs are contained in the TsFiles so it will record
 * every TsFiles in the socket instead.
 */
public class FilePartitionedSnapshotLogManager extends PartitionedSnapshotLogManager{

  private static final Logger logger = LoggerFactory.getLogger(FilePartitionedSnapshotLogManager.class);

  public FilePartitionedSnapshotLogManager(LogApplier logApplier, PartitionTable partitionTable) {
    super(logApplier, partitionTable);
  }

  @Override
  public void takeSnapshot() {
    logger.info("Taking snapshots, flushing IoTDB");
    StorageEngine.getInstance().syncCloseAllProcessor();
    logger.info("Taking snapshots, IoTDB is flushed");
    synchronized (socketSnapshots) {
      collectTimeseriesSchemas();

      while (!logBuffer.isEmpty() && logBuffer.getFirst().getCurrLogIndex() <= commitLogIndex) {
        // remove committed logs
        Log log = logBuffer.removeFirst();
        snapshotLastLogId = log.getCurrLogIndex();
        snapshotLastLogTerm = log.getCurrLogTerm();
      }

      collectTsFiles();

      // TODO-Cluster: record closed data files in the snapshot
      logger.info("Snapshot is taken");
      // TODO-Cluster: serialize the snapshots
    }
  }

  private void collectTsFiles() {
    socketSnapshots.clear();
    Map<String, List<TsFileResource>> storageGroupFiles =
        StorageEngine.getInstance().getAllClosedStorageGroupTsFile();
    for (Entry<String, List<TsFileResource>> entry : storageGroupFiles.entrySet()) {
      String storageGroupName = entry.getKey();
      // TODO-Cluster: add time partitioning
      int socketNum = PartitionUtils.calculateStorageGroupSocket(storageGroupName, 0);
      FileSnapshot snapshot = (FileSnapshot) socketSnapshots.computeIfAbsent(socketNum,
          s -> new FileSnapshot());
      for (TsFileResource tsFileResource : entry.getValue()) {
        snapshot.addFile(tsFileResource);
      }
    }
  }
}
