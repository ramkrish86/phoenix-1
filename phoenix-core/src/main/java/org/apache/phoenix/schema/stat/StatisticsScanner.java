/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.phoenix.schema.stat;

import java.io.IOException;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.hbase.Cell;
import org.apache.hadoop.hbase.CellUtil;
import org.apache.hadoop.hbase.HRegionInfo;
import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.KeyValueUtil;
import org.apache.hadoop.hbase.regionserver.InternalScanner;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.io.MultipleIOException;
import org.apache.phoenix.hbase.index.util.ImmutableBytesPtr;
import org.apache.phoenix.util.SchemaUtil;

import com.google.common.collect.Lists;

/**
 * The scanner that does the scanning to collect the stats during major compaction.{@link StatisticsCollector} 
 */
public class StatisticsScanner implements InternalScanner {
  private static final Log LOG = LogFactory.getLog(StatisticsScanner.class);
  private InternalScanner delegate;
  private StatisticsTable stats;
  private HRegionInfo region;
  private StatisticsTracker tracker;
  private byte[] family;

  public StatisticsScanner(StatisticsTracker tracker, StatisticsTable stats, HRegionInfo region,
      InternalScanner delegate, byte[] family) {
    // should there be only one tracker?
    this.tracker = tracker;
    this.stats = stats;
    this.delegate = delegate;
    this.region = region;
    this.family = family;
    this.tracker.clear();
  }

  @Override
  public boolean next(List<Cell> result) throws IOException {
    boolean ret = delegate.next(result);
    updateStat(result);
    return ret;
  }

  @Override
  public boolean next(List<Cell> result, int limit) throws IOException {
    boolean ret = delegate.next(result, limit);
    updateStat(result);
    return ret;
  }

  /**
   * Update the current statistics based on the lastest batch of key-values from
   * the underlying scanner
   * 
   * @param results
   *          next batch of {@link KeyValue}s
   */
  protected void updateStat(final List<Cell> results) {
      byte[] prevRow = null;
        for (Cell c : results) {
            KeyValue kv = KeyValueUtil.ensureKeyValue(c);
            if (!CellUtil.isDelete(kv)) {
                byte[] row = new ImmutableBytesPtr(kv.getRowArray(), kv.getRowOffset(), kv.getRowLength())
                        .copyBytesIfNecessary();
                if (!Bytes.equals(row, prevRow)) {
                    tracker.updateStatistic(kv);
                }
                prevRow = row;
            }
        }
    }

  public void close() throws IOException {
    IOException toThrow = null;
    try {
      // update the statistics table
      // Just verify if this if fine
      String tableName = SchemaUtil.getTableNameFromFullName(region.getTable().getNameAsString());
      stats.updateStats(
          tableName, region
              .getRegionNameAsString(), this.tracker, Bytes.toString(family), null, false);
    } catch (IOException e) {
      LOG.error("Failed to update statistics table!", e);
      toThrow = e;
    }
    // close the delegate scanner
    try {
      delegate.close();
    } catch (IOException e) {
      if (toThrow == null) {
        throw e;
      }
      throw MultipleIOException.createIOException(Lists.newArrayList(toThrow, e));
    }
  }
}