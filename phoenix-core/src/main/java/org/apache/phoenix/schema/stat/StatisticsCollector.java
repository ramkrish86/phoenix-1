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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.hbase.Cell;
import org.apache.hadoop.hbase.Coprocessor;
import org.apache.hadoop.hbase.CoprocessorEnvironment;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.KeyValueUtil;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.Mutation;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.coprocessor.BaseRegionObserver;
import org.apache.hadoop.hbase.coprocessor.CoprocessorException;
import org.apache.hadoop.hbase.coprocessor.CoprocessorService;
import org.apache.hadoop.hbase.coprocessor.ObserverContext;
import org.apache.hadoop.hbase.coprocessor.RegionCoprocessorEnvironment;
import org.apache.hadoop.hbase.protobuf.ResponseConverter;
import org.apache.hadoop.hbase.regionserver.HRegion;
import org.apache.hadoop.hbase.regionserver.InternalScanner;
import org.apache.hadoop.hbase.regionserver.KeyValueScanner;
import org.apache.hadoop.hbase.regionserver.RegionScanner;
import org.apache.hadoop.hbase.regionserver.ScanType;
import org.apache.hadoop.hbase.regionserver.Store;
import org.apache.hadoop.hbase.regionserver.StoreScanner;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.phoenix.coprocessor.generated.StatCollectorProtos;
import org.apache.phoenix.coprocessor.generated.StatCollectorProtos.StatCollectRequest;
import org.apache.phoenix.coprocessor.generated.StatCollectorProtos.StatCollectResponse;
import org.apache.phoenix.coprocessor.generated.StatCollectorProtos.StatCollectResponse.Builder;
import org.apache.phoenix.coprocessor.generated.StatCollectorProtos.StatCollectService;
import org.apache.phoenix.hbase.index.util.ImmutableBytesPtr;
import org.apache.phoenix.jdbc.PhoenixDatabaseMetaData;
import org.apache.phoenix.schema.PDataType;
import org.apache.phoenix.schema.PhoenixArray;
import org.apache.phoenix.util.SchemaUtil;
import org.apache.phoenix.util.TimeKeeper;

import com.google.common.collect.Lists;
import com.google.protobuf.RpcCallback;
import com.google.protobuf.RpcController;
import com.google.protobuf.Service;

/**
 * An endpoint implementation that allows to collect the stats for a given region and groups the stat per family. This is also an
 * RegionObserver that collects the information on compaction also. The user would be allowed to invoke this endpoint and thus populate the
 * Phoenix stats table with the max key, min key and guide posts for the given region. The stats can be consumed by the stats associated
 * with every PTable and the same can be used to parallelize the queries
 */
public class StatisticsCollector extends BaseRegionObserver implements CoprocessorService, Coprocessor,
        StatisticsTracker, StatCollectService.Interface {

    public static void addToTable(HTableDescriptor desc) throws IOException {
        desc.addCoprocessor(StatisticsCollector.class.getName());
    }

    private Map<String, byte[]> minMap = new TreeMap<String, byte[]>();
    private Map<String, byte[]> maxMap = new TreeMap<String, byte[]>();
    private long guidepostDepth;
    private long byteCount = 0;
    private TreeMap<String, List<byte[]>> guidePostsMap = new TreeMap<String, List<byte[]>>();
    private Set<byte[]> familyMap = new TreeSet<byte[]>(Bytes.BYTES_COMPARATOR);
    private RegionCoprocessorEnvironment env;
    protected StatisticsTable stats;
    private static final Log LOG = LogFactory.getLog(StatCollectService.class);

    @Override
    public void collectStat(RpcController controller, StatCollectRequest request, RpcCallback<StatCollectResponse> done) {
        HRegion region = env.getRegion();
        // Clear all old stats
        clear();
        Scan scan = createScan();
        if (request.hasStartRow()) {
            scan.setStartRow(request.getStartRow().toByteArray());
        }
        if (request.hasStopRow()) {
            scan.setStopRow(request.getStopRow().toByteArray());
        }
        RegionScanner scanner = null;
        int count = 0;
        try {
            scanner = region.getScanner(scan);
            count = scanRegion(scanner, count);
        } catch (IOException e) {
            LOG.error(e);
            ResponseConverter.setControllerException(controller, e);
        } finally {
            if (scanner != null) {
                try {
                    writeStatsToStatsTable(region, scanner, false, new ArrayList<Mutation>(),
                            TimeKeeper.SYSTEM.getCurrentTime());
                } catch (IOException e) {
                    LOG.error(e);
                    ResponseConverter.setControllerException(controller, e);
                }
            }
        }
        Builder newBuilder = StatCollectResponse.newBuilder();
        newBuilder.setRowsScanned(count);
        StatCollectResponse result = newBuilder.build();
        done.run(result);
    }

    private void writeStatsToStatsTable(final HRegion region,
            final RegionScanner scanner, boolean split, List<Mutation> mutations, long currentTime) throws IOException {
        scanner.close();
        try {
            // update the statistics table
            for (byte[] fam : familyMap) {
                String tableName = region.getRegionInfo().getTable().getNameAsString();
                stats.updateStats(tableName, (region.getRegionInfo().getRegionNameAsString()), this,
                        Bytes.toString(fam), split, mutations, currentTime);
            }
        } catch (IOException e) {
            LOG.error("Failed to update statistics table!", e);
            throw e;
        }
    }

    private void deleteStatsFromStatsTable(final HRegion region, List<Mutation> mutations, long currentTime) throws IOException {
        try {
            // update the statistics table
            for (byte[] fam : familyMap) {
                String tableName = null;
                tableName = SchemaUtil.getTableNameFromFullName(region.getRegionInfo().getTable().getNameAsString());
                mutations.add(stats.deleteStats(tableName, (region.getRegionInfo().getRegionNameAsString()), this,
                        Bytes.toString(fam), currentTime));
            }
        } catch (IOException e) {
            LOG.error("Failed to update statistics table!", e);
            throw e;
        }
    }

    private int scanRegion(RegionScanner scanner, int count) throws IOException {
        List<Cell> results = new ArrayList<Cell>();
        boolean hasMore = true;
        while (hasMore) {
            // Am getting duplicates here. Need to avoid that
            hasMore = scanner.next(results);
            updateStat(results);
            count += results.size();
            results.clear();
            while (!hasMore) {
                break;
            }
        }
        return count;
    }

    /**
     * Update the current statistics based on the lastest batch of key-values from the underlying scanner
     * 
     * @param results
     *            next batch of {@link KeyValue}s
     */
    protected void updateStat(final List<Cell> results) {
        for (Cell c : results) {
            KeyValue kv = KeyValueUtil.ensureKeyValue(c);
            updateStatistic(kv);
        }
    }

    @Override
    public Service getService() {
        return StatCollectorProtos.StatCollectService.newReflectiveService(this);
    }

    @Override
    public void start(CoprocessorEnvironment env) throws IOException {
        if (env instanceof RegionCoprocessorEnvironment) {
            this.env = (RegionCoprocessorEnvironment)env;
        } else {
            throw new CoprocessorException("Must be loaded on a table region!");
        }
        HTableDescriptor desc = ((RegionCoprocessorEnvironment)env).getRegion().getTableDesc();
        // Get the stats table associated with the current table on which the CP is
        // triggered
        stats = StatisticsTable.getStatisticsTableForCoprocessor(env, desc.getName());
        guidepostDepth = env.getConfiguration().getLong(StatisticsConstants.HISTOGRAM_BYTE_DEPTH_CONF_KEY,
                StatisticsConstants.HISTOGRAM_DEFAULT_BYTE_DEPTH);
    }

    @Override
    public void stop(CoprocessorEnvironment env) throws IOException {
        if (env instanceof RegionCoprocessorEnvironment) {
            TableName table = ((RegionCoprocessorEnvironment)env).getRegion().getRegionInfo().getTable();
            // Close only if the table is system table
            if(table.getNameAsString().equals(PhoenixDatabaseMetaData.SYSTEM_STATS_NAME)) {
                stats.close();
            }
        }
    }

    @Override
    public InternalScanner preCompactScannerOpen(ObserverContext<RegionCoprocessorEnvironment> c, Store store,
            List<? extends KeyValueScanner> scanners, ScanType scanType, long earliestPutTs, InternalScanner s)
            throws IOException {
        InternalScanner internalScan = s;
        TableName table = c.getEnvironment().getRegion().getRegionInfo().getTable();
        if (!table.getNameAsString().equals(PhoenixDatabaseMetaData.SYSTEM_STATS_NAME)) {
            // See if this is for Major compaction
            if (scanType.equals(ScanType.COMPACT_DROP_DELETES)) {
                // this is the first CP accessed, so we need to just create a major
                // compaction scanner, just
                // like in the compactor
                if (s == null) {
                    Scan scan = new Scan();
                    scan.setMaxVersions(store.getFamily().getMaxVersions());
                    long smallestReadPoint = store.getSmallestReadPoint();
                    internalScan = new StoreScanner(store, store.getScanInfo(), scan, scanners, scanType,
                            smallestReadPoint, earliestPutTs);
                }
                InternalScanner scanner = getInternalScanner(c, store, internalScan, store.getColumnFamilyName());
                if (scanner != null) {
                    internalScan = scanner;
                }
            }
        }
        return internalScan;
    }
    

    @Override
    public void postSplit(ObserverContext<RegionCoprocessorEnvironment> ctx, HRegion l, HRegion r) throws IOException {
        // Invoke collectStat here
        HRegion region = ctx.getEnvironment().getRegion();
        TableName table = region.getRegionInfo().getTable();
        if (!table.getNameAsString().equals(PhoenixDatabaseMetaData.SYSTEM_STATS_NAME)) {
            if (familyMap != null) {
                familyMap.clear();
            }
            // Create a delete operation on the parent region
            // Then write the new guide posts for individual regions
            // TODO : Try making this atomic
            List<Mutation> mutations = Lists.newArrayListWithExpectedSize(3);
            long currentTime = TimeKeeper.SYSTEM.getCurrentTime();
            collectStatsForSplitRegions(l, region, true, mutations, currentTime);
            clear();
            collectStatsForSplitRegions(r, region, false, mutations, currentTime);
        }
    }

    private void collectStatsForSplitRegions(HRegion daughter, HRegion region, boolean delete,
            List<Mutation> mutations, long currentTime) throws IOException {
        Scan scan = createScan();
        RegionScanner scanner = null;
        int count = 0;
        try {
            scanner = daughter.getScanner(scan);
            count = scanRegion(scanner, count);
        } catch (IOException e) {
            LOG.error(e);
            throw e;
        } finally {
            if (scanner != null) {
                try {
                    if (delete) {
                        deleteStatsFromStatsTable(region, mutations, currentTime);
                    }
                    writeStatsToStatsTable(daughter, scanner, true, mutations, currentTime);
                } catch (IOException e) {
                    LOG.error(e);
                    throw e;
                }
            }
        }
    }

    private Scan createScan() {
        Scan scan = new Scan();
        // We should have a mechanism to set the caching here.
        // TODO : Should the scan object be coming from the endpoint?
        scan.setCaching(1000);
        return scan;
    }

    protected InternalScanner getInternalScanner(ObserverContext<RegionCoprocessorEnvironment> c, Store store,
            InternalScanner internalScan, String family) {
        return new StatisticsScanner(this, stats, c.getEnvironment().getRegion().getRegionInfo(), internalScan,
                Bytes.toBytes(family));
    }

    @Override
    public void clear() {
        this.maxMap.clear();
        this.minMap.clear();
        this.guidePostsMap.clear();
        this.familyMap.clear();
    }

    @Override
    public void updateStatistic(KeyValue kv) {
        // Should we onlyl check for Puts?
        byte[] cf = kv.getFamily();
        familyMap.add(cf);
        
        String fam = Bytes.toString(cf);
        byte[] row = new ImmutableBytesPtr(kv.getRowArray(), kv.getRowOffset(), kv.getRowLength())
                .copyBytesIfNecessary();
        if (!minMap.containsKey(fam) && !maxMap.containsKey(fam)) {
            minMap.put(fam, row);
            // Ideally the max key also should be added in this case
            maxMap.put(fam, row);
        } else {
            if (Bytes.compareTo(kv.getRowArray(), kv.getRowOffset(), kv.getRowLength(), minMap.get(fam), 0,
                    minMap.get(fam).length) < 0) {
                minMap.put(fam, row);
            }
            if (Bytes.compareTo(kv.getRowArray(), kv.getRowOffset(), kv.getRowLength(), maxMap.get(fam), 0,
                    maxMap.get(fam).length) > 0) {
                maxMap.put(fam, row);
            }
        }
        byteCount += kv.getLength();
        // TODO : This can be moved to an interface so that we could collect guide posts in different ways
        if (byteCount >= guidepostDepth) {
            if (guidePostsMap.get(fam) != null) {
                guidePostsMap.get(fam).add(
                        row);
            } else {
                List<byte[]> guidePosts = new ArrayList<byte[]>();
                guidePosts.add(row);
                guidePostsMap.put(fam, guidePosts);
            }
            // reset the count for the next key
            byteCount = 0;
        }
    }

    @Override
    public byte[] getMaxKey(String fam) {
        if (maxMap.get(fam) != null) { return maxMap.get(fam); }
        return null;
    }

    @Override
    public byte[] getMinKey(String fam) {
        if (minMap.get(fam) != null) { return minMap.get(fam); }
        return null;
    }

    @Override
    public byte[] getGuidePosts(String fam) {
        if (!guidePostsMap.isEmpty()) {
            List<byte[]> guidePosts = guidePostsMap.get(fam);
            if (guidePosts != null) {
                byte[][] array = new byte[guidePosts.size()][];
                int i = 0;
                for (byte[] element : guidePosts) {
                    array[i] = element;
                    i++;
                }
                PhoenixArray phoenixArray = new PhoenixArray(PDataType.VARBINARY, array);
                return PDataType.VARBINARY_ARRAY.toBytes(phoenixArray);
            }
        }
        return null;
    }
}
