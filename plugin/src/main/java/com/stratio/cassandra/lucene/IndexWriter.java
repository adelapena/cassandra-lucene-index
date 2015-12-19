/*
 * Licensed to STRATIO (C) under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional information
 * regarding copyright ownership.  The STRATIO (C) licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.stratio.cassandra.lucene;

import org.apache.cassandra.db.Clustering;
import org.apache.cassandra.db.DecoratedKey;
import org.apache.cassandra.db.DeletionTime;
import org.apache.cassandra.db.RangeTombstone;
import org.apache.cassandra.db.rows.Row;
import org.apache.cassandra.index.transactions.IndexTransaction;
import org.apache.cassandra.utils.concurrent.OpOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedList;
import java.util.List;
import java.util.NavigableSet;

/**
 * @author Andres de la Pena {@literal <adelapena@stratio.com>}
 */
public class IndexWriter implements org.apache.cassandra.index.Index.Indexer {

    private static final Logger logger = LoggerFactory.getLogger(IndexWriter.class);

    private final IndexService service;
    private final DecoratedKey key;
    private final int nowInSec;
    private final OpOrder.Group opGroup;
    private final IndexTransaction.Type transactionType;

    private boolean hasPartitionDeletion;
    private List<Row> rows;

    /**
     * Builds a new IndexWriter.
     *
     * @param service The service to perform the indexing operation.
     * @param key Key of the partition being modified. This can be empty as an update might only contain partition,
     * range and row deletions, but the indexer is guaranteed to not get any cells for a column that is not part of
     * {@code columns}.
     * @param nowInSec Current time of the update operation.
     * @param opGroup Operation group spanning the update operation.
     */
    public IndexWriter(IndexService service,
                       DecoratedKey key,
                       int nowInSec,
                       OpOrder.Group opGroup,
                       IndexTransaction.Type transactionType) {
        this.service = service;
        this.key = key;
        this.nowInSec = nowInSec;
        this.opGroup = opGroup;
        this.transactionType = transactionType;
    }

    /**
     * Notification of the start of a partition update. This event always occurs before any other during the update.
     */
    public void begin() {
        hasPartitionDeletion = false;
        rows = new LinkedList<>();
    }

    /**
     * Notification of a top level partition delete.
     *
     * @param deletionTime the deletion time
     */
    public void partitionDelete(DeletionTime deletionTime) {
        logger.debug("Delete partition during {}: {}", transactionType, deletionTime);
        hasPartitionDeletion = true;
        rows.clear();
    }

    /**
     * Notification of a RangeTombstone. An update of a single partition may contain multiple RangeTombstones, and a
     * notification will be passed for each of them.
     *
     * @param tombstone the range tombstone
     */
    public void rangeTombstone(RangeTombstone tombstone) {
        logger.debug("Range tombstone during {}: {}", transactionType, tombstone);
    }

    /**
     * Notification that a new row was inserted into the Memtable holding the partition. This only implies that the
     * inserted row was not already present in the Memtable, it *does not* guarantee that the row does not exist in an
     * SSTable, potentially with additional column data.
     *
     * @param row the row being inserted into the base table's Memtable
     */
    public void insertRow(Row row) {
        logger.debug("Insert rows during {}: {}", transactionType, row);
        rows.add(row);
    }

    /**
     * Notification of a modification to a row in the base table's Memtable. This is allow an Index implementation to
     * clean up entries for base data which is never flushed to disk (and so will not be purged during compaction). It's
     * important to note that the old and new rows supplied here may not represent the totality of the data for the Row
     * with this particular Clustering. There may be additional column data in SSTables which is not present in either
     * the old or new row, so implementations should be aware of that. The supplied rows contain only column data which
     * has actually been updated. oldRowData contains only the columns which have been removed from the Row's
     * representation in the Memtable, while newRowData includes only new columns which were not previously present. Any
     * column data which is unchanged by the update is not included.
     *
     * @param oldRowData data that was present in existing row and which has been removed from the base table's
     * Memtable
     * @param newRowData data that was not present in the existing row and is being inserted into the base table's
     * Memtable
     */
    public void updateRow(Row oldRowData, Row newRowData) {
        logger.debug("Update row during {}: {} TO {}", transactionType, oldRowData, newRowData);
        rows.add(newRowData);
    }

    /**
     * Notification that a row was removed from the partition. Note that this is only called as part of either a
     * compaction or a cleanup. This context is indicated by the TransactionType supplied to the indexerFor method.
     *
     * As with updateRow, it cannot be guaranteed that all data belonging to the Clustering of the supplied Row has been
     * removed (although in the case of a cleanup, that is the ultimate intention). There may be data for the same row
     * in other SSTables, so in this case IndexWriter implementations should *not* assume that all traces of the row
     * have been removed. In particular, it is not safe to assert that all values associated with the Row's Clustering
     * have been deleted, so implementations which index primary key columns should not purge those entries from their
     * indexes.
     *
     * @param row data being removed from the base table
     */
    public void removeRow(Row row) {
        logger.debug("Remove row during {}: {}", transactionType, row);
        rows.add(row);
    }

    /**
     * Notification of the end of the partition update. This event always occurs after all others for the particular
     * update.
     */
    public void finish() {
        try {
            service.index(key, rows, hasPartitionDeletion, nowInSec, opGroup);
        } catch (Exception e) {
            throw new IndexException(logger, e, "Error while indexing %s during %s", key, transactionType);
        }
    }
}
