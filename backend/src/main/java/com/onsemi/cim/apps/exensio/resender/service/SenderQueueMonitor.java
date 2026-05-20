package com.onsemi.cim.apps.exensio.resender.service;

import com.onsemi.cim.apps.exensio.resender.config.CpElasticsearchProperties;
import com.onsemi.cim.apps.exensio.resender.config.ExternalDbConfig;
import com.onsemi.cim.apps.exensio.resender.config.RefDbProperties;
import com.onsemi.cim.apps.exensio.resender.stage.StageMonitorService;
import com.onsemi.cim.apps.exensio.resender.stage.StageRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Periodically inspects the external sender queue and drives status transitions when
 * records disappear from {@code DTP_SENDER_QUEUE_ITEM} (i.e. CP has consumed them).
 *
 * <p><b>Two modes depending on Elasticsearch configuration:</b></p>
 * <ul>
 *   <li><b>ES configured</b> ({@code cp.elasticsearch.url} is set): transitions to
 *       {@code ENRICHMENT} and lets {@link CpLogMonitor} drive the rest of the pipeline
 *       ({@code ENRICHMENT → EXENSIO_LOADING / FAILED}) based on actual CP log data.</li>
 *   <li><b>ES not configured</b>: transitions directly to {@code DONE} (skipping the
 *       ES-dependent intermediate states) so operators still see completion without
 *       requiring an Elasticsearch deployment.</li>
 * </ul>
 */
@Service
public class SenderQueueMonitor {
    private static final Logger log = LoggerFactory.getLogger(SenderQueueMonitor.class);

    private final RefDbService refDbService;
    private final ExternalDbConfig externalDbConfig;
    private final RefDbProperties properties;
    private final StageSessionService stageSessionService;
    private final StageMonitorService monitorService;
    private final CpElasticsearchProperties esProperties;

    public SenderQueueMonitor(RefDbService refDbService,
                              ExternalDbConfig externalDbConfig,
                              RefDbProperties properties,
                              StageSessionService stageSessionService,
                              StageMonitorService monitorService,
                              CpElasticsearchProperties esProperties) {
        this.refDbService = refDbService;
        this.externalDbConfig = externalDbConfig;
        this.properties = properties;
        this.stageSessionService = stageSessionService;
        this.monitorService = monitorService;
        this.esProperties = esProperties;
    }

    @Scheduled(fixedDelayString = "${refdb.dispatch.monitor-interval-ms:10000}")
    public void monitorQueue() {
        int pageSize = Math.max(properties.getDispatch().getPerSend(), 500);

        int page = 0;
        while (true) {
            int offset = page * pageSize;
            // ENRICHMENT is the only active intermediate status — ENQUEUED is never written.
            List<StageRecord> enrichmentPage = refDbService.listRecords(null, null, "ENRICHMENT", offset, pageSize, "updated_at", "asc", null);

            if (enrichmentPage.isEmpty()) {
                break;
            }

            List<StageRecord> pending = enrichmentPage.stream()
                    .filter(r -> r.processedAt() == null)
                    .toList();

            if (!pending.isEmpty()) {
                Map<String, Map<Integer, List<StageRecord>>> bySite = partitionBySiteAndSender(pending);
                for (Map.Entry<String, Map<Integer, List<StageRecord>>> entry : bySite.entrySet()) {
                    String site = entry.getKey();
                    Map<Integer, List<StageRecord>> bySender = entry.getValue();
                    Connection connection = null;
                    try {
                        connection = externalDbConfig.getConnection(site);
                        if (connection == null) {
                            log.debug("Skipping monitor for site {} because no external connection is available", site);
                            continue;
                        }
                        for (Map.Entry<Integer, List<StageRecord>> senderEntry : bySender.entrySet()) {
                            inspectQueue(connection, site, senderEntry.getKey(), senderEntry.getValue());
                        }
                    } catch (SQLException ex) {
                        log.warn("Monitor unable to inspect queue for site {}: {}", site, ex.getMessage());
                    } finally {
                        if (connection != null) {
                            try {
                                connection.close();
                            } catch (SQLException closeEx) {
                                log.debug("Failed closing monitor connection for site {}: {}", site, closeEx.getMessage());
                            }
                        }
                    }
                }
            }

            if (enrichmentPage.size() < pageSize) {
                break;
            }
            page++;
        }
    }

    private void inspectQueue(Connection connection, String site, int senderId, List<StageRecord> records) {
        if (connection == null) {
            log.debug("Skipping monitor for site {} sender {} due to missing connection", site, senderId);
            return;
        }
        Set<String> queueKeys = fetchQueueKeys(connection, senderId);
        if (queueKeys.isEmpty()) {
            log.debug("Queue empty for site {} sender {} when monitoring {} staged entries", site, senderId, records.size());
        }
        List<StageRecord> completed = new ArrayList<>();
        for (StageRecord record : records) {
            String key = buildKey(record.metadataId(), record.dataId());
            if (!queueKeys.contains(key)) {
                completed.add(record);
            }
        }
        if (!completed.isEmpty()) {
            if (esProperties.isConfigured()) {
                // ES is configured — transition to ENRICHMENT and let CpLogMonitor drive
                // the rest of the pipeline (ENRICHMENT → EXENSIO_LOADING / FAILED).
                refDbService.markEnrichmentRecords(completed);
                log.info("ES configured: marked {} record(s) as ENRICHMENT for site {} sender {}",
                        completed.size(), site, senderId);
            } else {
                // ES is not configured — transition directly to DONE so operators see
                // completion without requiring an Elasticsearch deployment.
                refDbService.markCompletedRecords(completed);
                log.info("ES not configured: marked {} record(s) as DONE (direct) for site {} sender {}",
                        completed.size(), site, senderId);
            }

            // Lot progress tracking for LOT_UPDATE events
            Map<String, Map<String, Integer>> lotProgressBySession = new HashMap<>();
            for (StageRecord record : completed) {
                if (record.requestId() != null && !record.requestId().isBlank()
                        && record.lot() != null && !record.lot().isBlank()) {
                    lotProgressBySession.computeIfAbsent(record.requestId(), k -> new HashMap<>())
                            .merge(record.lot(), 1, Integer::sum);
                }
            }

            // Broadcast LOT_UPDATE events for affected lots
            for (Map.Entry<String, Map<String, Integer>> sessionEntry : lotProgressBySession.entrySet()) {
                String requestId = sessionEntry.getKey();
                for (String lot : sessionEntry.getValue().keySet()) {
                    broadcastLotProgress(requestId, lot);
                }
            }

            stageSessionService.refreshSessions(completed.stream().map(StageRecord::requestId).toList());
        }
    }

    private Set<String> fetchQueueKeys(Connection connection, int senderId) {
        Set<String> keys = new HashSet<>();
        if (connection == null) {
            return keys;
        }
        String sql = "SELECT id_metadata, id_data FROM DTP_SENDER_QUEUE_ITEM WHERE id_sender = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, senderId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    keys.add(buildKey(rs.getString(1), rs.getString(2)));
                }
            }
        } catch (SQLException ex) {
            log.warn("Failed reading queue entries for sender {}: {}", senderId, ex.getMessage());
        }
        return keys;
    }

    private Map<String, Map<Integer, List<StageRecord>>> partitionBySiteAndSender(List<StageRecord> records) {
        Map<String, Map<Integer, List<StageRecord>>> result = new HashMap<>();
        for (StageRecord record : records) {
            result.computeIfAbsent(record.site(), key -> new HashMap<>())
                    .computeIfAbsent(record.senderId(), key -> new ArrayList<>())
                    .add(record);
        }
        return result;
    }

    private String buildKey(String metadataId, String dataId) {
        String meta = metadataId == null ? "" : metadataId;
        String data = dataId == null ? "" : dataId;
        return meta + "|" + data;
    }

    private void broadcastLotProgress(String requestId, String lot) {
        try {
            Map<String, Integer> lotStats = refDbService.getLotStatistics(requestId, lot);
            int total = lotStats.getOrDefault("total", 0);
            int completed = lotStats.getOrDefault("completed", 0);
            int failed = lotStats.getOrDefault("failed", 0);
            double progress = total > 0 ? (double) (completed + failed) / total * 100.0 : 0.0;

            monitorService.broadcastLotUpdate(requestId, new com.onsemi.cim.apps.exensio.resender.stage.LotUpdateEvent(
                    lot,
                    total,
                    completed,
                    failed,
                    progress
            ));
        } catch (Exception ex) {
            log.warn("Failed to broadcast lot progress for session {} lot {}: {}", requestId, lot, ex.getMessage());
        }
    }
}
