/*
 * Copyright 2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.glowroot.central.repo;

import java.util.ArrayList;
import java.util.List;

import com.datastax.driver.core.KeyspaceMetadata;
import com.datastax.driver.core.TableMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.central.util.CassandraWriteMetrics;
import org.glowroot.central.util.Session;
import org.glowroot.common.Constants;
import org.glowroot.common2.config.CentralStorageConfig;
import org.glowroot.common2.repo.RepoAdmin;

import static com.google.common.base.Preconditions.checkNotNull;

public class RepoAdminImpl implements RepoAdmin {

    private static final Logger logger = LoggerFactory.getLogger(RepoAdminImpl.class);

    private final Session session;
    private final String keyspace;
    private final ConfigRepositoryImpl configRepository;
    private final CassandraWriteMetrics cassandraWriteMetrics;

    public RepoAdminImpl(Session session, String keyspace, ConfigRepositoryImpl configRepository,
            CassandraWriteMetrics cassandraWriteMetrics) {
        this.session = session;
        this.keyspace = keyspace;
        this.configRepository = configRepository;
        this.cassandraWriteMetrics = cassandraWriteMetrics;
    }

    @Override
    public void defragH2Data() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void compactH2Data() {
        throw new UnsupportedOperationException();
    }

    @Override
    public long getH2DataFileSize() {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<H2Table> analyzeH2DiskSpace() {
        throw new UnsupportedOperationException();
    }

    @Override
    public TraceCounts analyzeTraceCounts() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void deleteAllData() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void resizeIfNeeded() {
        throw new UnsupportedOperationException();
    }

    @Override
    public int updateCassandraTwcsWindowSizes() throws Exception {
        CentralStorageConfig storageConfig = configRepository.getCentralStorageConfig();
        KeyspaceMetadata keyspaceMetadata =
                checkNotNull(session.getCluster().getMetadata().getKeyspace(keyspace));
        List<String> tableNames = new ArrayList<>();
        for (TableMetadata table : keyspaceMetadata.getTables()) {
            String compactionClass = table.getOptions().getCompaction().get("class");
            if (compactionClass == null || !compactionClass
                    .equals("org.apache.cassandra.db.compaction.TimeWindowCompactionStrategy")) {
                continue;
            }
            String actualWindowUnit =
                    table.getOptions().getCompaction().get("compaction_window_unit");
            String actualWindowSize =
                    table.getOptions().getCompaction().get("compaction_window_size");
            int expirationHours = getExpirationHoursForTable(table.getName(), storageConfig);
            if (expirationHours == -1) {
                // warning already logged above inside getExpirationHoursForTable()
                continue;
            }
            int windowSizeHours = Session.getCompactionWindowSizeHours(expirationHours);
            if (!"HOURS".equals(actualWindowUnit)
                    || !Integer.toString(windowSizeHours).equals(actualWindowSize)) {
                tableNames.add(table.getName());
            }
        }
        int updatedTableCount = 0;
        for (String tableName : tableNames) {
            int expirationHours =
                    RepoAdminImpl.getExpirationHoursForTable(tableName, storageConfig);
            if (expirationHours == -1) {
                // warning already logged above inside getExpirationHoursForTable()
                continue;
            }
            int windowSizeHours = Session.getCompactionWindowSizeHours(expirationHours);
            session.execute("alter table " + tableName + " with compaction = { 'class'"
                    + " : 'TimeWindowCompactionStrategy', 'compaction_window_unit' : 'HOURS',"
                    + " 'compaction_window_size' : " + windowSizeHours + ","
                    + " 'unchecked_tombstone_compaction' : true }");
            updatedTableCount++;
        }
        return updatedTableCount;
    }

    @Override
    public List<CassandraWriteTotals> getCassandraWriteTotalsPerTable(int limit) {
        return cassandraWriteMetrics.getCassandraDataWrittenPerTable(limit);
    }

    @Override
    public List<CassandraWriteTotals> getCassandraWriteTotalsPerAgentRollup(String tableName,
            int limit) {
        return cassandraWriteMetrics.getCassandraDataWrittenPerAgentRollup(tableName, limit);
    }

    @Override
    public List<CassandraWriteTotals> getCassandraWriteTotalsPerTransactionType(
            String tableName, String agentRollupId, int limit) {
        return cassandraWriteMetrics.getCassandraDataWrittenPerTransactionType(tableName,
                agentRollupId, limit);
    }

    @Override
    public List<CassandraWriteTotals> getCassandraWriteTotalsPerTransactionName(
            String tableName, String agentRollupId, String transactionType, int limit) {
        return cassandraWriteMetrics.getCassandraDataWrittenPerTransactionName(tableName,
                agentRollupId, transactionType, limit);
    }

    static int getExpirationHoursForTable(String tableName,
            CentralStorageConfig storageConfig) {
        if (tableName.startsWith("trace_")) {
            return storageConfig.traceExpirationHours();
        } else if (tableName.startsWith("gauge_value_rollup_")) {
            int rollupLevel = Integer.parseInt(tableName.substring(tableName.lastIndexOf('_') + 1));
            if (rollupLevel == 0) {
                return storageConfig.rollupExpirationHours().get(rollupLevel);
            } else {
                return storageConfig.rollupExpirationHours().get(rollupLevel - 1);
            }
        } else if (tableName.startsWith("aggregate_tt_query_")
                || tableName.startsWith("aggregate_tn_query_")
                || tableName.startsWith("aggregate_tt_service_call_")
                || tableName.startsWith("aggregate_tn_service_call_")) {
            int rollupLevel = Integer.parseInt(tableName.substring(tableName.lastIndexOf('_') + 1));
            return storageConfig.queryAndServiceCallRollupExpirationHours().get(rollupLevel);
        } else if (tableName.startsWith("aggregate_tt_main_thread_profile_")
                || tableName.startsWith("aggregate_tn_main_thread_profile_")
                || tableName.startsWith("aggregate_tt_aux_thread_profile_")
                || tableName.startsWith("aggregate_tn_aux_thread_profile_")) {
            int rollupLevel = Integer.parseInt(tableName.substring(tableName.lastIndexOf('_') + 1));
            return storageConfig.profileRollupExpirationHours().get(rollupLevel);
        } else if (tableName.startsWith("aggregate_") || tableName.startsWith("synthetic_")) {
            int rollupLevel = Integer.parseInt(tableName.substring(tableName.lastIndexOf('_') + 1));
            return storageConfig.rollupExpirationHours().get(rollupLevel);
        } else if (tableName.equals("gauge_name") || tableName.equals("agent")) {
            return getMaxRollupExpirationHours(storageConfig);
        } else if (tableName.equals("heartbeat")) {
            return HeartbeatDao.EXPIRATION_HOURS;
        } else if (tableName.equals("resolved_incident")) {
            return Constants.RESOLVED_INCIDENT_EXPIRATION_HOURS;
        } else {
            logger.warn("unexpected table: {}", tableName);
            return -1;
        }
    }

    private static int getMaxRollupExpirationHours(CentralStorageConfig storageConfig) {
        int maxRollupExpirationHours = 0;
        for (int expirationHours : storageConfig.rollupExpirationHours()) {
            if (expirationHours == 0) {
                // zero value expiration/TTL means never expire
                return 0;
            }
            maxRollupExpirationHours = Math.max(maxRollupExpirationHours, expirationHours);
        }
        return maxRollupExpirationHours;
    }
}
