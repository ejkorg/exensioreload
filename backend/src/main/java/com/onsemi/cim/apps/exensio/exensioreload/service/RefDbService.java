package com.onsemi.cim.apps.exensio.exensioreload.service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.onsemi.cim.apps.exensio.exensioreload.config.PpLogDbProperties;
import com.onsemi.cim.apps.exensio.exensioreload.config.RefDbProperties;
import com.onsemi.cim.apps.exensio.exensioreload.dto.BatchResult;
import com.onsemi.cim.apps.exensio.exensioreload.stage.DuplicatePayload;
import com.onsemi.cim.apps.exensio.exensioreload.stage.PayloadCandidate;
import com.onsemi.cim.apps.exensio.exensioreload.stage.StageRecord;
import com.onsemi.cim.apps.exensio.exensioreload.stage.StageResult;
import com.onsemi.cim.apps.exensio.exensioreload.stage.StageStatus;
import com.onsemi.cim.apps.exensio.exensioreload.stage.StageUserStatus;
import com.onsemi.cim.apps.exensio.exensioreload.stage.StateAggregationBatcher;
import com.onsemi.cim.apps.exensio.exensioreload.stage.StatusMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

@Service
public class RefDbService {
    private static final Logger log = LoggerFactory.getLogger(RefDbService.class);
    private static final String DEFAULT_USER = "system";
    private static final Duration SAME_USER_DUPLICATE_RETRY_COOLDOWN = Duration.ZERO;
    private static final String UNKNOWN_USER = "unknown";
    private static final int USER_MAX_LENGTH = 120;

    private final RefDbProperties properties;
    private final HikariDataSource dataSource;
    /** Separate datasource for pp_log queries — points to PRODUCTION when configured. */
    private final HikariDataSource ppLogDataSource;
    private final boolean isOracle;
    private final com.onsemi.cim.apps.exensio.exensioreload.stage.StageMonitorService monitorService;
    private final com.onsemi.cim.apps.exensio.exensioreload.config.CpElasticsearchProperties elasticsearchProperties;
    private final com.onsemi.cim.apps.exensio.exensioreload.config.ExensioProperties exensioProperties;
    private final IntegrationStatusService integrationStatusService;
    private final StateAggregationBatcher stateAggregationBatcher;
    @Value("${refdb.auth-bootstrap-enabled:false}")
    private boolean authBootstrapEnabled = false; // Disabled - using modern JPA authentication

    public RefDbService(RefDbProperties properties,
                        PpLogDbProperties ppLogDbProperties,
                        com.onsemi.cim.apps.exensio.exensioreload.stage.StageMonitorService monitorService,
                        com.onsemi.cim.apps.exensio.exensioreload.config.CpElasticsearchProperties elasticsearchProperties,
                        com.onsemi.cim.apps.exensio.exensioreload.config.ExensioProperties exensioProperties,
                        IntegrationStatusService integrationStatusService,
                        StateAggregationBatcher stateAggregationBatcher) {
        this.properties = properties;
        this.monitorService = monitorService;
        this.elasticsearchProperties = elasticsearchProperties;
        this.exensioProperties = exensioProperties;
        this.integrationStatusService = integrationStatusService;
        this.stateAggregationBatcher = stateAggregationBatcher;
        this.isOracle = properties.getHost() != null && !properties.getHost().isBlank();
        HikariConfig config = new HikariConfig();
        if (isOracle) {
            config.setJdbcUrl(properties.buildJdbcUrl());
            config.setUsername(properties.getUser());
            config.setPassword(properties.getPassword());
            config.setDriverClassName("oracle.jdbc.OracleDriver");
            // Use UTC for all timestamp operations to ensure portability across sites.
            // This allows the application to work correctly regardless of the DB server's
            // physical timezone or location.
            config.addDataSourceProperty("oracle.jdbc.timezoneAsRegion", "false");
            config.setConnectionInitSql("ALTER SESSION SET TIME_ZONE = 'UTC'");
        } else {
            // Test environment fallback: use an embedded H2 datasource so tests don't try to contact Oracle
            config.setJdbcUrl("jdbc:h2:mem:refdb;DB_CLOSE_DELAY=-1");
            config.setUsername("sa");
            config.setPassword("");
            config.setDriverClassName("org.h2.Driver");
        }
        config.setMaximumPoolSize(properties.getPool().getMaxSize());
        config.setMinimumIdle(properties.getPool().getMinIdle());
        config.setPoolName("refdb-staging");
        this.dataSource = new HikariDataSource(config);
        log.info("Main refdb datasource (QA): {}", isOracle ? properties.buildJdbcUrl() : "H2/embedded");
        
        // Build a separate datasource for pp_log queries (PRODUCTION) if configured.
        // Falls back to the main dataSource when refdb.pplog.host is not set.
        if (ppLogDbProperties != null && ppLogDbProperties.isConfigured()) {
            HikariConfig ppConfig = new HikariConfig();
            ppConfig.setJdbcUrl(ppLogDbProperties.buildJdbcUrl());
            ppConfig.setUsername(ppLogDbProperties.getUser());
            ppConfig.setPassword(ppLogDbProperties.getPassword());
            ppConfig.setDriverClassName("oracle.jdbc.OracleDriver");
            // Use UTC for all timestamp operations to ensure portability across sites
            ppConfig.addDataSourceProperty("oracle.jdbc.timezoneAsRegion", "false");
            ppConfig.setConnectionInitSql("ALTER SESSION SET TIME_ZONE = 'UTC'");
            ppConfig.setMaximumPoolSize(ppLogDbProperties.getPool().getMaxSize());
            ppConfig.setMinimumIdle(ppLogDbProperties.getPool().getMinIdle());
            ppConfig.setPoolName("refdb-pplog");
            this.ppLogDataSource = new HikariDataSource(ppConfig);
            log.info("pp_log datasource configured separately: {}", ppLogDbProperties.buildJdbcUrl());
        } else {
            // No separate pp_log config — reuse the main staging datasource
            this.ppLogDataSource = this.dataSource;
            log.warn("pp_log datasource not separately configured — using main refdb datasource (QA instead of PRODUCTION)");
        }
    }

    @PostConstruct
    public void initialize() {
        try (Connection connection = dataSource.getConnection()) {
            ensureStageTable(connection);
            // Legacy auth bootstrap removed - using modern JPA-based authentication
            log.info("RefDB initialized. Modern JPA authentication system is active.");
        } catch (SQLException ex) {
            throw new IllegalStateException("Unable to initialize staging schema", ex);
        }
    }

    @PreDestroy
    public void shutdown() {
        if (ppLogDataSource != null && ppLogDataSource != dataSource) {
            ppLogDataSource.close();
        }
        if (dataSource != null) {
            dataSource.close();
        }
    }

    public StageResult stagePayloads(String site, int senderId, List<PayloadCandidate> payloads) {
        return stagePayloads(site, senderId, null, DEFAULT_USER, payloads, true);
    }

    public StageResult stagePayloads(String site, int senderId, String senderName, List<PayloadCandidate> payloads) {
        return stagePayloads(site, senderId, senderName, DEFAULT_USER, payloads, true);
    }

    public StageResult stagePayloads(String site,
                                     int senderId,
                                     String requestedBy,
                                     List<PayloadCandidate> payloads,
                                     boolean forceDuplicates) {
        return stagePayloads(site, senderId, null, requestedBy, payloads, forceDuplicates, null);
    }

    public StageResult stagePayloads(String site,
                                     int senderId,
                                     String senderName,
                                     String requestedBy,
                                     List<PayloadCandidate> payloads,
                                     boolean forceDuplicates) {
        return stagePayloads(site, senderId, senderName, requestedBy, payloads, forceDuplicates, null);
    }

    public StageResult stagePayloads(String site,
                                     int senderId,
                                     String senderName,
                                     String requestedBy,
                                     List<PayloadCandidate> payloads,
                                     boolean forceDuplicates,
                                     String requestId) {
        if (payloads == null || payloads.isEmpty()) {
            return StageResult.empty();
        }
        String normalizedUser = normalizeUser(requestedBy);
        String normalizedSenderName = normalizeSenderName(senderName);
        if (log.isInfoEnabled()) {
            try {
                log.info("stagePayloads called site={} senderId={} requestedBy={} senderName={} payloadsCount={} forceDuplicates={}",
                        site, senderId, normalizedUser, normalizedSenderName, payloads.size(), forceDuplicates);
            } catch (Exception _e) {
                // ignore logging failures
            }
        }
        String table = properties.getStagingTable();
        String idExpr = nextIdExpr(table);
        String sql = "INSERT INTO " + table + " (id, site, sender_id, sender_name, metadata_id, data_id, lot, wafer, device, filename, end_time, status, error_message, created_at, updated_at, processed_at, staged_by, last_requested_by, last_requested_at, request_id, data_type, test_phase) " +
                "VALUES (" + idExpr + ", ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'STAGED', NULL, " + timestampExpr() + ", " + timestampExpr() + ", NULL, ?, ?, " + timestampExpr() + ", ?, ?, ?)";
        int inserted = 0;
        int requeued = 0;
        List<DuplicatePayload> duplicates = new ArrayList<>();
        log.info("Attempting to acquire database connection...");
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            log.info("Database connection acquired successfully");

            // Batch processing for performance
            int batchSize = 0;
            final int BATCH_LIMIT = 500;
            List<PayloadCandidate> currentBatch = new ArrayList<>();

            log.info("Starting to process {} payloads", payloads.size());
            for (PayloadCandidate candidate : payloads) {
                log.info("Processing payload: metadataId={} dataId={}", candidate.metadataId(), candidate.dataId());
                ps.setString(1, site);
                ps.setInt(2, senderId);
                ps.setString(3, normalizedSenderName);
                ps.setString(4, candidate.metadataId());
                ps.setString(5, candidate.dataId());
                ps.setString(6, candidate.lot());
                ps.setString(7, candidate.wafer());
                ps.setString(8, candidate.device());
                ps.setString(9, candidate.filename());
                if (candidate.endTime() != null) {
                    ps.setTimestamp(10, Timestamp.from(candidate.endTime()));
                } else {
                    ps.setNull(10, java.sql.Types.TIMESTAMP);
                }
                ps.setString(11, normalizedUser);
                ps.setString(12, normalizedUser);
                ps.setString(13, requestId);
                ps.setString(14, candidate.dataType());
                ps.setString(15, candidate.testPhase());
                log.info("About to add batch for metadataId={}", candidate.metadataId());
                ps.addBatch();
                log.info("Batch added successfully");
                currentBatch.add(candidate);
                batchSize++;

                if (batchSize >= BATCH_LIMIT) {
                    try {
                        log.info("Executing batch insert: {} records", batchSize);
                        int[] batchCounts = ps.executeBatch();
                        int batchSucceeded = 0;
                        List<Integer> failedIndices = new ArrayList<>();
                        for (int i = 0; i < batchCounts.length; i++) {
                            if (batchCounts[i] >= 0 || batchCounts[i] == java.sql.Statement.SUCCESS_NO_INFO) {
                                batchSucceeded++;
                            } else {
                                failedIndices.add(i);
                            }
                        }
                        log.info("Batch executed: succeeded={} failed={} of {} records", batchSucceeded, failedIndices.size(), batchSize);
                        inserted += batchSucceeded;
                        if (!failedIndices.isEmpty()) {
                            List<PayloadCandidate> failedCandidates = new ArrayList<>();
                            for (int idx : failedIndices) {
                                failedCandidates.add(currentBatch.get(idx));
                            }
                            log.info("Retrying {} silently-failed rows via single-row fallback", failedCandidates.size());
                            int[] fb = processBatchFallback(connection, failedCandidates,
                                    site, senderId, normalizedSenderName, normalizedUser, requestId, forceDuplicates, duplicates);
                            inserted += fb[0]; requeued += fb[1];
                        }
                        if (requestId != null && monitorService != null) {
                            log.debug("Broadcasting {} ROW_UPDATE events for requestId={}", currentBatch.size(), requestId);
                            for (PayloadCandidate c : currentBatch) {
                                Map<String, Object> evt = new HashMap<>();
                                evt.put("id", buildPayloadId(c.metadataId(), c.dataId()));
                                evt.put("metadataId", c.metadataId());
                                evt.put("dataId", c.dataId());
                                evt.put("status", "STAGED");
                                evt.put("stagedBy", normalizedUser);
                                evt.put("msg", "Staged");
                                monitorService.sendEvent(requestId, "ROW_UPDATE", evt);
                            }
                            log.debug("Broadcast completed for batch");
                        }
                    } catch (java.sql.BatchUpdateException bue) {
                        log.warn("Batch insert failed (BatchUpdateException), falling back to single-row: {}", bue.getMessage());
                        int[] fb = processBatchFallback(connection, payloads.subList(payloads.indexOf(candidate) - batchSize + 1, payloads.indexOf(candidate) + 1),
                                site, senderId, normalizedSenderName, normalizedUser, requestId, forceDuplicates, duplicates);
                        inserted += fb[0]; requeued += fb[1];
                    }
                    batchSize = 0;
                    currentBatch.clear();
                }
            }
            // Process remaining
            if (batchSize > 0) {
                try {
                    log.info("Processing remaining batch: {} records", batchSize);
                    int[] batchCounts = ps.executeBatch();
                    // Count actual successes from the batch result array.
                    // Oracle ojdbc may return SUCCESS_NO_INFO (-2) for successful rows or
                    // EXECUTE_FAILED (-3) for failed rows without throwing BatchUpdateException.
                    int batchSucceeded = 0;
                    List<Integer> failedIndices = new ArrayList<>();
                    for (int i = 0; i < batchCounts.length; i++) {
                        if (batchCounts[i] >= 0 || batchCounts[i] == java.sql.Statement.SUCCESS_NO_INFO) {
                            batchSucceeded++;
                        } else {
                            failedIndices.add(i);
                        }
                    }
                    log.info("Remaining batch executed: succeeded={} failed={} of {} records", batchSucceeded, failedIndices.size(), batchSize);
                    inserted += batchSucceeded;
                    // Re-process any rows that silently failed (EXECUTE_FAILED) via single-row fallback
                    if (!failedIndices.isEmpty()) {
                        List<PayloadCandidate> failedCandidates = new ArrayList<>();
                        for (int idx : failedIndices) {
                            failedCandidates.add(currentBatch.get(idx));
                        }
                        log.info("Retrying {} silently-failed rows via single-row fallback", failedCandidates.size());
                        int[] fb2 = processBatchFallback(connection, failedCandidates,
                                site, senderId, normalizedSenderName, normalizedUser, requestId, forceDuplicates, duplicates);
                        inserted += fb2[0]; requeued += fb2[1];
                    }
                    if (requestId != null && monitorService != null) {
                        log.info("Broadcasting {} ROW_UPDATE events for remaining batch", currentBatch.size());
                        for (PayloadCandidate c : currentBatch) {
                            Map<String, Object> evt = new HashMap<>();
                            evt.put("id", buildPayloadId(c.metadataId(), c.dataId()));
                            evt.put("metadataId", c.metadataId());
                            evt.put("dataId", c.dataId());
                            evt.put("status", "STAGED");
                            evt.put("stagedBy", normalizedUser);
                            evt.put("msg", "Staged");
                            monitorService.sendEvent(requestId, "ROW_UPDATE", evt);
                        }
                        log.info("All ROW_UPDATE events broadcast completed");
                    }
                } catch (java.sql.BatchUpdateException bue) {
                    log.warn("Remaining batch insert failed (BatchUpdateException), falling back to single-row: {}", bue.getMessage());
                    int startIdx = payloads.size() - batchSize;
                    int[] fb3 = processBatchFallback(connection, payloads.subList(startIdx, payloads.size()),
                            site, senderId, normalizedSenderName, normalizedUser, requestId, forceDuplicates, duplicates);
                    inserted += fb3[0]; requeued += fb3[1];
                }
            }
        } catch (SQLException ex) {
            log.error("SQLException during stagePayloads", ex);
            throw new IllegalStateException("Failed staging payloads", ex);
        }
        log.info("stagePayloads completed successfully: inserted={} requeued={} duplicates={}", inserted, requeued, duplicates.size());
        return new StageResult(inserted, duplicates, requeued);
    }

    /** Returns int[2]: [0]=freshInserted, [1]=requeued */
    private int[] processBatchFallback(Connection connection, List<PayloadCandidate> batch, String site, int senderId, String senderName, String user, String requestId, boolean forceDuplicates, List<DuplicatePayload> duplicates) {
        int freshInserted = 0;
        int requeuedCount = 0;
        String table = properties.getStagingTable();
        String idExpr = nextIdExpr(table);
        String sql = "INSERT INTO " + table + " (id, site, sender_id, sender_name, metadata_id, data_id, lot, wafer, device, filename, end_time, status, error_message, created_at, updated_at, processed_at, staged_by, last_requested_by, last_requested_at, request_id, data_type, test_phase) " +
                "VALUES (" + idExpr + ", ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'STAGED', NULL, " + timestampExpr() + ", " + timestampExpr() + ", NULL, ?, ?, " + timestampExpr() + ", ?, ?, ?)";

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            for (PayloadCandidate candidate : batch) {
                ps.setString(1, site);
                ps.setInt(2, senderId);
                ps.setString(3, senderName);
                ps.setString(4, candidate.metadataId());
                ps.setString(5, candidate.dataId());
                ps.setString(6, candidate.lot());
                ps.setString(7, candidate.wafer());
                ps.setString(8, candidate.device());
                ps.setString(9, candidate.filename());
                if (candidate.endTime() != null) {
                    ps.setTimestamp(10, Timestamp.from(candidate.endTime()));
                } else {
                    ps.setNull(10, java.sql.Types.TIMESTAMP);
                }
                ps.setString(11, user);
                ps.setString(12, user);
                ps.setString(13, requestId);
                ps.setString(14, candidate.dataType());
                ps.setString(15, candidate.testPhase());
                try {
                    ps.executeUpdate();
                    freshInserted++;
                } catch (SQLException ex) {
                    if (isDuplicate(ex)) {
                        ExistingPayload existing = loadExistingPayload(connection, table, site, senderId, candidate);
                        boolean sameUser = false;
                        if (existing != null) {
                            String effectiveUser = normalizeUser(existing.lastRequestedBy() != null ? existing.lastRequestedBy() : existing.stagedBy());
                            sameUser = effectiveUser.equalsIgnoreCase(user);
                        }
                        boolean autoRetrySameUser =
                                !forceDuplicates
                                        && sameUser
                                        && isAutoRetryStatus(existing)
                                        && isOutsideDuplicateRetryCooldown(existing, SAME_USER_DUPLICATE_RETRY_COOLDOWN);

                        boolean allowResubmit = forceDuplicates || autoRetrySameUser;
                        if (existing == null) {
                            allowResubmit = true;
                        }
                        if (allowResubmit && existing != null) {
                            markRetry(connection, table, site, senderId, candidate, user, senderName, requestId);
                            requeuedCount++; // re-queued, not freshly inserted
                        } else if (allowResubmit && existing == null) {
                            try {
                                ps.executeUpdate();
                                freshInserted++;
                            } catch (SQLException retryEx) {
                                log.debug("Re-insert after null-existing duplicate failed (likely race): {}", retryEx.getMessage());
                                requeuedCount++;
                            }
                        }
                        if (!allowResubmit) {
                            duplicates.add(toDuplicatePayload(candidate, existing, true));
                        }
                    }
                }
            }
        } catch (SQLException e) {
            log.error("Fallback batch processing failed", e);
        }
        return new int[]{freshInserted, requeuedCount};
    }

    /**
     * Utility: group payload candidates by file-level identity (metadataId + dataId),
     * collecting the set of wafer labels associated to each file. This supports
     * UI flows where a single file contains multiple wafers (e.g., PCM), while
     * the backend enqueues one payload per file.
     *
     * The returned map is keyed by payloadId in the format "<metadataId>,<dataId>".
     * Values are a set of wafer display labels. When wafer is null/blank, the lot
     * is used as the label; when both exist, the label is "<lot>/<wafer>".
     */
    public Map<String, java.util.Set<String>> groupPayloadsByFile(List<PayloadCandidate> payloads) {
        Map<String, java.util.Set<String>> grouped = new java.util.LinkedHashMap<>();
        if (payloads == null || payloads.isEmpty()) return grouped;
        for (PayloadCandidate c : payloads) {
            if (c == null) continue;
            String metadata = c.metadataId() != null ? c.metadataId().trim() : "";
            String data = c.dataId() != null ? c.dataId().trim() : "";
            if (metadata.isEmpty() && data.isEmpty()) continue;
            String payloadId = metadata + "," + data;
            String lot = c.lot();
            String wafer = c.wafer();
            String waferLabel;
            String lotNorm = lot != null ? lot.trim() : "";
            String waferNorm = wafer != null ? wafer.trim() : "";
            if (!waferNorm.isEmpty() && !lotNorm.isEmpty()) waferLabel = lotNorm + "/" + waferNorm;
            else if (!waferNorm.isEmpty()) waferLabel = waferNorm;
            else waferLabel = lotNorm;
            grouped.computeIfAbsent(payloadId, k -> new java.util.LinkedHashSet<>());
            if (!waferLabel.isEmpty()) grouped.get(payloadId).add(waferLabel);
        }
        return grouped;
    }

    /** Build a canonical payloadId string from metadataId + dataId */
    public static String buildPayloadId(String metadataId, String dataId) {
        String m = metadataId != null ? metadataId.trim() : "";
        String d = dataId != null ? dataId.trim() : "";
        return m + "," + d;
    }

    public List<StageRecord> fetchNextBatch(int limit) {
        String table = properties.getStagingTable();
        String sql = "SELECT id, site, sender_id, sender_name, metadata_id, data_id, lot, wafer, filename, end_time, status, " + coalesce("error_message", "''") + " AS error_message, created_at, updated_at, processed_at, staged_by, last_requested_by, last_requested_at, request_id, cp_output_path, cp_output_target, exensio_wafer_key, exensio_pg_key, data_type, test_phase " +
                "FROM " + table + " WHERE status = 'STAGED' ORDER BY created_at FETCH FIRST ? ROWS ONLY";
        List<StageRecord> records = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    records.add(mapRecord(rs));
                }
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed loading batch", ex);
        }
        return records;
    }

    /**
     * List distinct wafer labels for a given file payload (metadataId + dataId).
     * Uses COALESCE(wafer, lot) so lots without wafer still produce a label.
     */
    public List<String> listWafersForPayload(String site, int senderId, String metadataId, String dataId) {
        String table = properties.getStagingTable();
        String sql = "SELECT DISTINCT " + coalesce("wafer", "lot") + " AS wafer_label FROM " + table + " WHERE site = ? AND sender_id = ? AND metadata_id = ? AND data_id = ?";
        List<String> labels = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, site);
            ps.setInt(2, senderId);
            ps.setString(3, metadataId);
            ps.setString(4, dataId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String label = rs.getString(1);
                    if (label != null) labels.add(label);
                }
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed listing wafers for payload", ex);
        }
        return labels;
    }

    public void markEnqueued(List<Long> ids) {
        // ENQUEUED is dead code — records go directly NEW → ENRICHMENT on dispatch.
        // Kept for backwards compatibility; delegates to the real transition.
        updateStatus(ids, "ELASTICSEARCH_MONITORING", null);
    }

    /**
     * @deprecated ENQUEUED is dead code — records go directly NEW → ENRICHMENT on dispatch.
     * Delegates to {@link #markEnrichmentRecords(List)} which is the canonical method.
     */
    @Deprecated
    public void markEnqueuedRecords(List<StageRecord> records) {
        markEnrichmentRecords(records);
    }

    public void markCpFailed(long id, String message) {
        updateStatus(List.of(id), "CP_FAILED", message);
    }

    public void markCpFailed(StageRecord record, String message) {
        if (record == null) return;
        markCpFailed(record.id(), message);
        if (monitorService != null && record.requestId() != null) {
            String failureReason = determineFailureReason(message);
            String failureSource = determineFailureSource(record, message);
            
            Map<String, Object> evt = new HashMap<>();
            evt.put("id", record.id());
            evt.put("status", "CP_FAILED");
            evt.put("message", message);
            evt.put("msg", "CP Failed: " + truncate(message, 30));
            evt.put("failureReason", failureReason);
            evt.put("failureSource", failureSource);
            evt.put("updatedAt", record.updatedAt() != null ? record.updatedAt().toString() : null);
            evt.put("metadataId", record.metadataId());
            evt.put("dataId", record.dataId());
            evt.put("lot", record.lot());
            evt.put("wafer", record.wafer());
            evt.put("filename", record.filename());
            evt.put("displayStatus", StatusMapper.getDisplayStatus("CP_FAILED", false));
            monitorService.sendEvent(record.requestId(), "ROW_UPDATE", evt);
            recordStateChangeForBatcher(record.requestId(), "CP_FAILED");
            broadcastStats(record.requestId());
        }
    }

    public void markLoadFailed(long id, String message) {
        updateStatus(List.of(id), "LOAD_FAILED", message);
    }

    public void markLoadFailed(StageRecord record, String message) {
        if (record == null) return;
        markLoadFailed(record.id(), message);
        if (monitorService != null && record.requestId() != null) {
            String failureReason = determineFailureReason(message);
            String failureSource = determineFailureSource(record, message);
            
            Map<String, Object> evt = new HashMap<>();
            evt.put("id", record.id());
            evt.put("status", "LOAD_FAILED");
            evt.put("message", message);
            evt.put("msg", "Load Failed: " + truncate(message, 30));
            evt.put("failureReason", failureReason);
            evt.put("failureSource", failureSource);
            evt.put("updatedAt", record.updatedAt() != null ? record.updatedAt().toString() : null);
            evt.put("metadataId", record.metadataId());
            evt.put("dataId", record.dataId());
            evt.put("lot", record.lot());
            evt.put("wafer", record.wafer());
            evt.put("filename", record.filename());
            evt.put("displayStatus", StatusMapper.getDisplayStatus("LOAD_FAILED", false));
            monitorService.sendEvent(record.requestId(), "ROW_UPDATE", evt);
            recordStateChangeForBatcher(record.requestId(), "LOAD_FAILED");
            broadcastStats(record.requestId());
        }
    }

    /**
     * Determine the failure reason based on message content.
     */
    private String determineFailureReason(String message) {
        if (message == null || message.isBlank()) {
            return "unknown";
        }
        String msgLower = message.toLowerCase();
        
        if (msgLower.contains("timeout")) {
            return "timeout";
        }
        if (msgLower.contains("connection") || msgLower.contains("network")) {
            return "connection_error";
        }
        if (msgLower.contains("cp") && (msgLower.contains("error") || msgLower.contains("failed"))) {
            return "cp_failure";
        }
        if (msgLower.contains("exensio") || msgLower.contains("api")) {
            return "exensio_failure";
        }
        if (msgLower.contains("not found") || msgLower.contains("missing")) {
            return "not_found";
        }
        if (msgLower.contains("timeout")) {
            return "timeout";
        }
        
        return "other";
    }

    /**
     * Determine the failure source based on record status and message.
     */
    private String determineFailureSource(StageRecord record, String message) {
        if (record == null) {
            return "unknown";
        }
        
        String recordStatus = record.status() != null ? record.status().toUpperCase() : "";
        String msgLower = message != null ? message.toLowerCase() : "";
        
        // Check for CP-related failures
        if (msgLower.contains("cp") && !msgLower.contains("exensio")) {
            return "cp";
        }
        if (recordStatus.contains("ELASTICSEARCH_MONITORING") || recordStatus.contains("EXENSIO")) {
            // Check if failure is from ES query
            if (msgLower.contains("es ") || msgLower.contains("elasticsearch") || msgLower.contains("query")) {
                return "cp";
            }
            // Check if failure is from pp_log
            if (msgLower.contains("pp_log") || msgLower.contains("pp log")) {
                return "cp";
            }
        }
        
        // Check for Exensio-related failures
        if (recordStatus.contains("EXENSIO") || msgLower.contains("exensio") || msgLower.contains("api")) {
            return "exensio";
        }
        
        // Check for preprocessing failures (before enrichment)
        if (recordStatus.contains("STAGED") || recordStatus.contains("STAGED")) {
            if (msgLower.contains("push") || msgLower.contains("database") || msgLower.contains("sql")) {
                return "preprocessing";
            }
        }
        
        return "unknown";
    }

    public void markCompleted(List<Long> ids) {
        markCompleted(ids, Instant.now());
    }

    public void markCompleted(List<Long> ids, Instant processedAt) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        Instant effective = processedAt != null ? processedAt : Instant.now();
        Timestamp processedTs = Timestamp.from(effective);
        String table = properties.getStagingTable();
        String sql = "UPDATE " + table + " SET status = ?, error_message = NULL, processed_at = ? WHERE id = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            for (Long id : ids) {
                ps.setString(1, "COMPLETED");
                ps.setTimestamp(2, processedTs);
                ps.setLong(3, id);
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed marking records complete", ex);
        }
    }

    public void markCompletedRecords(List<StageRecord> records) {
        if (records == null || records.isEmpty()) return;
        List<Long> ids = records.stream().map(StageRecord::id).toList();
        markCompleted(ids);

        if (monitorService != null) {
            Map<String, List<StageRecord>> byRequest = new HashMap<>();
            for (StageRecord r : records) {
                if (r.requestId() != null) {
                    byRequest.computeIfAbsent(r.requestId(), k -> new ArrayList<>()).add(r);
                }
            }
            byRequest.forEach((reqId, group) -> {
                for (StageRecord r : group) {
                    Map<String, Object> evt = new HashMap<>();
                    evt.put("id", r.id());
                    evt.put("status", "COMPLETED");
                    evt.put("msg", "Dispatch completed");
                    monitorService.sendEvent(reqId, "ROW_UPDATE", evt);
                }
                broadcastStats(reqId);
            });
        }
    }

    /**
     * Marks records as ENRICHMENT status when they are consumed from the sender queue by CP.
     * This replaces the incorrect DONE transition — the file has only been picked up for enrichment,
     * not fully processed.
     * Broadcasts SSE ROW_UPDATE with status "ELASTICSEARCH_MONITORING" and msg "Consumed by CP (processing)".
     * Requirements: 1.1, 1.2
     */
    public void markEnrichmentRecords(List<StageRecord> records) {
        if (records == null || records.isEmpty()) return;
        List<Long> ids = records.stream().map(StageRecord::id).toList();
        updateStatus(ids, "ELASTICSEARCH_MONITORING", null);

        if (monitorService != null) {
            Map<String, List<StageRecord>> byRequest = new HashMap<>();
            for (StageRecord r : records) {
                if (r.requestId() != null) {
                    byRequest.computeIfAbsent(r.requestId(), k -> new ArrayList<>()).add(r);
                }
            }
            byRequest.forEach((reqId, group) -> {
                for (StageRecord r : group) {
                    Map<String, Object> evt = new HashMap<>();
                    evt.put("id", r.id());
                    evt.put("status", "ELASTICSEARCH_MONITORING");
                    evt.put("msg", "Consumed by CP (processing)");
                    monitorService.sendEvent(reqId, "ROW_UPDATE", evt);
                }
                // Record state change to batcher for aggregation event
                recordStateChangeForBatcher(reqId, "ELASTICSEARCH_MONITORING");
                broadcastStats(reqId);
            });
        }
    }

    /**
     * Marks a record as EXENSIO_LOADING, stores the CP output path and target,
     * and broadcasts an SSE ROW_UPDATE event.
     * Requirements: 3.2, 3.3, 3.5
     */
    public void markExensioMonitoring(StageRecord record, String outputPath, String outputTarget) {
        if (record == null) return;
        applyExensioLoading(record.id(), outputPath, outputTarget);
        broadcastExensioLoadingEvent(record, outputPath, outputTarget, "Exensio Loading");
    }

    /**
     * Transitions records to {@code EXENSIO_LOADING} after CP queue consumption when Elasticsearch
     * is disabled but the Exensio API monitor is enabled. CP output paths are not yet known.
     */
    public void markExensioMonitoringPending(List<StageRecord> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        String table = properties.getStagingTable();
        String sql = "UPDATE " + table +
                " SET status = 'EXENSIO_MONITORING', cp_output_path = NULL, cp_output_target = NULL," +
                " error_message = NULL WHERE id = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            for (StageRecord record : records) {
                ps.setLong(1, record.id());
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed marking records as EXENSIO_LOADING (pending)", ex);
        }

        if (monitorService != null) {
            Map<String, List<StageRecord>> byRequest = new HashMap<>();
            for (StageRecord r : records) {
                if (r.requestId() != null) {
                    byRequest.computeIfAbsent(r.requestId(), k -> new ArrayList<>()).add(r);
                }
            }
            byRequest.forEach((reqId, group) -> {
                for (StageRecord r : group) {
                    broadcastExensioLoadingEvent(r, null, null,
                            "Awaiting Exensio load confirmation (CP consumed)");
                }
                broadcastStats(reqId);
            });
        }
    }

    /**
     * Marks {@code DONE} after Elasticsearch confirms CP success when Exensio verification is disabled.
     */
    public void markCompletedFromCp(StageRecord record, String outputPath, String outputTarget) {
        if (record == null) {
            return;
        }
        String table = properties.getStagingTable();
        String sql = "UPDATE " + table +
                " SET status = 'COMPLETED', cp_output_path = ?, cp_output_target = ?, error_message = NULL," +
                " processed_at = " + timestampExpr() + ", updated_at = " + timestampExpr() +
                " WHERE id = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, outputPath);
            ps.setString(2, outputTarget);
            ps.setLong(3, record.id());
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed marking record DONE from CP enrichment", ex);
        }

        if (monitorService != null && record.requestId() != null) {
            Map<String, Object> evt = new HashMap<>();
            evt.put("id", record.id());
            evt.put("status", "COMPLETED");
            evt.put("msg", "CP enrichment complete");
            evt.put("cpOutputPath", outputPath);
            evt.put("cpOutputTarget", outputTarget);
            monitorService.sendEvent(record.requestId(), "ROW_UPDATE", evt);
            // Record state change to batcher for aggregation event
            recordStateChangeForBatcher(record.requestId(), "COMPLETED");
            broadcastStats(record.requestId());
        }
    }

    /**
     * Marks a record as DONE when no definitive enrichment result was found (ES timeout,
     * pp_log empty, Exensio unresolved). Sets an error_message directing manual verification
     * rather than assuming failure, since the file may have been enriched outside the CP pipeline.
     */
    public void markCompletedManualVerify(StageRecord record, String message) {
        if (record == null) return;
        String table = properties.getStagingTable();
        String sql = "UPDATE " + table +
                " SET status = 'COMPLETED', error_message = ?," +
                " processed_at = " + timestampExpr() + ", updated_at = " + timestampExpr() +
                " WHERE id = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, message);
            ps.setLong(2, record.id());
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed marking record DONE with manual verify", ex);
        }

        if (monitorService != null && record.requestId() != null) {
            Map<String, Object> evt = new HashMap<>();
            evt.put("id", record.id());
            evt.put("status", "COMPLETED");
            evt.put("msg", "Completed — manual verification needed: " + truncate(message, 60));
            evt.put("errorMessage", message);
            monitorService.sendEvent(record.requestId(), "ROW_UPDATE", evt);
            // Record state change to batcher for aggregation event
            recordStateChangeForBatcher(record.requestId(), "COMPLETED");
            broadcastStats(record.requestId());
        }
    }

    /**
     * Mark record with enrichment timeout.
     * Called when ES, pp_log, and Exensio direct lookup all return NotFound after timeout.
     * Transitions the record from ENRICHMENT to ENRICHMENT_TIMEOUT status with diagnostic information.
     * Emits SSE state change event via StateAggregationBatcher.
     *
     * @param record The stage record that timed out
     * @param diagnosticSummary Detailed diagnostic from all enrichment sources
     *
     * Requirements: 1.1, 1.2, 1.3
     */
    public void markCpTimeout(StageRecord record, String diagnosticSummary) {
        if (record == null) return;
        
        String errorMessage = "[Enrichment Timeout] " + diagnosticSummary +
                " No definitive enrichment result found after " +
                elasticsearchProperties.getEnrichmentTimeoutMinutes() + " minutes. " +
                "Needs manual verification or retry.";
        
        updateStatus(List.of(record.id()), "CP_TIMEOUT", errorMessage);
        
        if (monitorService != null && record.requestId() != null) {
            Map<String, Object> evt = new HashMap<>();
            evt.put("id", record.id());
            evt.put("status", "CP_TIMEOUT");
            evt.put("msg", "Enrichment timeout - " + truncate(diagnosticSummary, 40));
            evt.put("errorMessage", errorMessage);
            evt.put("metadataId", record.metadataId());
            evt.put("dataId", record.dataId());
            evt.put("lot", record.lot());
            evt.put("wafer", record.wafer());
            evt.put("filename", record.filename());
            monitorService.sendEvent(record.requestId(), "ROW_UPDATE", evt);
            
            // Record state change to batcher for aggregation event
            recordStateChangeForBatcher(record.requestId(), "CP_TIMEOUT");
            broadcastStats(record.requestId());
        }
        
        log.info("Marked record {} as CP_TIMEOUT: {}", record.id(), diagnosticSummary);
    }

    /** @deprecated Use {@link #markCpTimeout(StageRecord, String)} */
    @Deprecated
    public void markEnrichmentTimeout(StageRecord record, String diagnosticSummary) {
        log.warn("Deprecated method markEnrichmentTimeout called — delegating to markCpTimeout for record {}", record.id());
        markCpTimeout(record, diagnosticSummary);
    }

    /**
     * Mark record with Exensio timeout — requires manual verification.
     * Called when Exensio API returns NotFound after configured timeout period.
     * Transitions the record from EXENSIO_LOADING to EXENSIO_TIMEOUT status.
     * Emits SSE state change event via StateAggregationBatcher.
     *
     * @param record The stage record that timed out
     * @param reason Description of timeout condition
     *
     * Requirements: 2.1, 2.2, 2.3
     */
    public void markCompletedManualVerification(StageRecord record, String reason) {
        if (record == null) return;
        
        String errorMessage = "[Exensio Timeout] " + reason +
                " Wafer not found after " + exensioProperties.getTimeoutMinutes() + " minutes. " +
                "May need manual verification or retry.";
        
        updateStatus(List.of(record.id()), "COMPLETED_MANUAL_VERIFICATION_REQUIRED", errorMessage);
        
        if (monitorService != null && record.requestId() != null) {
            Map<String, Object> evt = new HashMap<>();
            evt.put("id", record.id());
            evt.put("status", "COMPLETED_MANUAL_VERIFICATION_REQUIRED");
            evt.put("msg", "Exensio timeout - " + truncate(reason, 40));
            evt.put("errorMessage", errorMessage);
            evt.put("metadataId", record.metadataId());
            evt.put("dataId", record.dataId());
            evt.put("lot", record.lot());
            evt.put("wafer", record.wafer());
            evt.put("filename", record.filename());
            monitorService.sendEvent(record.requestId(), "ROW_UPDATE", evt);
            
            // Record state change to batcher for aggregation event
            recordStateChangeForBatcher(record.requestId(), "COMPLETED_MANUAL_VERIFICATION_REQUIRED");
            broadcastStats(record.requestId());
        }
        
        log.info("Marked record {} as EXENSIO_TIMEOUT: {}", record.id(), reason);
    }

    private void applyExensioLoading(long recordId, String outputPath, String outputTarget) {
        String table = properties.getStagingTable();
        String sql = "UPDATE " + table +
                " SET status = 'EXENSIO_MONITORING', cp_output_path = ?, cp_output_target = ?, error_message = NULL WHERE id = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, outputPath);
            ps.setString(2, outputTarget);
            ps.setLong(3, recordId);
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed marking record as EXENSIO_LOADING", ex);
        }
    }

    private void broadcastExensioLoadingEvent(StageRecord record, String outputPath, String outputTarget, String msg) {
        if (monitorService == null || record.requestId() == null) {
            return;
        }
        Map<String, Object> evt = new HashMap<>();
        evt.put("id", record.id());
        evt.put("status", "EXENSIO_MONITORING");
        evt.put("msg", msg);
        if (outputPath != null) {
            evt.put("cpOutputPath", outputPath);
        }
        if (outputTarget != null) {
            evt.put("cpOutputTarget", outputTarget);
        }
        monitorService.sendEvent(record.requestId(), "ROW_UPDATE", evt);
        broadcastStats(record.requestId());
    }

    /**
     * Marks a record as DONE after Exensio confirms the wafer was loaded.
     * Stores the Exensio wafer_key and pg_key for future results queries.
     * Broadcasts SSE ROW_UPDATE with status "COMPLETED".
     */
    public void markCompletedFromExensio(StageRecord record, Long exensioWaferKey, long exensioPgKey) {
        if (record == null) return;
        String table = properties.getStagingTable();
        String sql = "UPDATE " + table +
                " SET status = 'COMPLETED', exensio_wafer_key = ?, exensio_pg_key = ?," +
                " processed_at = " + timestampExpr() + ", updated_at = " + timestampExpr() +
                " WHERE id = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            if (exensioWaferKey != null) {
                ps.setLong(1, exensioWaferKey);
            } else {
                ps.setNull(1, java.sql.Types.NUMERIC);
            }
            ps.setLong(2, exensioPgKey);
            ps.setLong(3, record.id());
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed marking record as DONE from Exensio", ex);
        }

        if (monitorService != null && record.requestId() != null) {
            Map<String, Object> evt = new HashMap<>();
            evt.put("id", record.id());
            evt.put("status", "COMPLETED");
            evt.put("msg", "Loaded into Exensio");
            evt.put("exensioWaferKey", exensioWaferKey);
            evt.put("exensioPgKey", exensioPgKey);
            monitorService.sendEvent(record.requestId(), "ROW_UPDATE", evt);
            // Record state change to batcher for aggregation event
            recordStateChangeForBatcher(record.requestId(), "COMPLETED");
            broadcastStats(record.requestId());
        }
    }

    private String truncate(String s, int len) {
        if (s == null) return "";
        return s.length() > len ? s.substring(0, len) + "..." : s;
    }

    public void broadcastStats(String requestId) {
        if (monitorService == null || requestId == null || requestId.isBlank()) return;

        List<StageStatus> statuses = fetchStatuses(requestId);

        long total = 0;
        long ready = 0;
        long enqueued = 0;
        long enriching = 0;
        long enrichmentTimeout = 0;
        long exensioLoading = 0;
        long exensioTimeout = 0;
        long failed = 0;
        long completed = 0;
        long cancelled = 0;

        for (StageStatus s : statuses) {
            total += s.total();
            ready += s.stagedToRefdb();
            enqueued += s.queuedForCp();
            enriching += s.elasticsearchMonitoring();
            enrichmentTimeout += s.cpTimeout();
            exensioLoading += s.exensioMonitoring();
            exensioTimeout += s.completedManualVerification();
            failed += s.failed();
            completed += s.completed();
            cancelled += s.cancelled();
        }

        Map<String, Object> evt = new HashMap<>();
        evt.put("total", total);
        evt.put("ready", ready);
        evt.put("enqueued", enqueued);
        evt.put("enriching", enriching);
        evt.put("enrichmentTimeout", enrichmentTimeout);
        evt.put("exensioLoading", exensioLoading);
        evt.put("exensioTimeout", exensioTimeout);
        evt.put("failed", failed);
        evt.put("completed", completed);
        evt.put("cancelled", cancelled);
        evt.put("errorCount", failed);

        double progress = total > 0 ? ((completed + failed) * 100.0 / total) : 0;
        evt.put("progress", progress);
        // Success rate counts only completed out of all terminal (completed + failed)
        long terminal = completed + failed;
        double successRate = terminal > 0 ? (completed * 100.0 / terminal) : 100.0;
        evt.put("successRate", successRate);
        evt.put("sessionStatuses", statuses);
        if (integrationStatusService != null) {
            boolean esConfigured = elasticsearchProperties != null && elasticsearchProperties.isConfigured();
            boolean exensioConfigured = exensioProperties != null && exensioProperties.isConfigured();
            evt.put("integration", integrationStatusService.snapshot(requestId, esConfigured, exensioConfigured));
        }

        monitorService.sendEvent(requestId, "STATS", evt);
    }

    public List<StageStatus> fetchStatuses(String requestId) {
        String table = properties.getStagingTable();
        List<StageStatus> statuses = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT site, sender_id, MAX(sender_name) AS sender_name, COUNT(*), " +
                "SUM(CASE WHEN status = 'STAGED' THEN 1 ELSE 0 END), " +
                "SUM(CASE WHEN status = 'QUEUED_FOR_CP' THEN 1 ELSE 0 END), " +
                "SUM(CASE WHEN status = 'ELASTICSEARCH_MONITORING' THEN 1 ELSE 0 END), " +
                "SUM(CASE WHEN status = 'CP_TIMEOUT' THEN 1 ELSE 0 END), " +
                "SUM(CASE WHEN status = 'EXENSIO_MONITORING' THEN 1 ELSE 0 END), " +
                "SUM(CASE WHEN status = 'COMPLETED_MANUAL_VERIFICATION_REQUIRED' THEN 1 ELSE 0 END), " +
                "SUM(CASE WHEN status = 'CP_FAILED' THEN 1 ELSE 0 END), " +
                "SUM(CASE WHEN status = 'LOAD_FAILED' THEN 1 ELSE 0 END), " +
                "SUM(CASE WHEN status = 'COMPLETED' THEN 1 ELSE 0 END), " +
                "SUM(CASE WHEN status = 'CANCELLED' THEN 1 ELSE 0 END) " +
                "FROM ").append(table).append(" WHERE 1=1 ");
        List<Object> params = new ArrayList<>();
        if (requestId != null && !requestId.isBlank()) {
            sql.append(" AND request_id = ?");
            params.add(requestId);
        }
        sql.append(" GROUP BY site, sender_id");

        try (Connection connection = dataSource.getConnection()) {
            Map<StageStatusKey, List<StageUserStatus>> userBreakdown = fetchUserBreakdown(connection, table, null, null, requestId);
            try (PreparedStatement ps = connection.prepareStatement(sql.toString())) {
                for (int i = 0; i < params.size(); i++) {
                    ps.setString(i + 1, params.get(i).toString());
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String site = rs.getString(1);
                        int senderId = rs.getInt(2);
                        StageStatusKey key = new StageStatusKey(site, senderId);
                        String senderName = normalizeSenderName(rs.getString(3));
                        statuses.add(new StageStatus(
                                site,
                                senderId,
                                senderName,
                                rs.getLong(4),      // total
                                rs.getLong(5),      // stagedToRefdb (STAGED)
                                rs.getLong(6),      // queuedForCp (QUEUED_FOR_CP)
                                rs.getLong(7),      // elasticsearchMonitoring (ELASTICSEARCH_MONITORING)
                                rs.getLong(8),      // cpTimeout (CP_TIMEOUT)
                                rs.getLong(9),      // exensioMonitoring (EXENSIO_MONITORING)
                                rs.getLong(10),     // completedManualVerification (COMPLETED_MANUAL_VERIFICATION_REQUIRED)
                                rs.getLong(11),     // cpFailed (CP_FAILED)
                                rs.getLong(12),     // loadFailed (LOAD_FAILED)
                                rs.getLong(13),     // completed (COMPLETED)
                                rs.getLong(14),     // cancelled (CANCELLED)
                                userBreakdown.getOrDefault(key, List.of())
                        ));
                    }
                }
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed loading stage status", ex);
        }
        return statuses;
    }

    public List<StageStatus> fetchStatusesFor(String site, Integer senderId, String requestId) {
        String table = properties.getStagingTable();
        String where = " WHERE 1=1" + (site != null ? " AND site = ?" : "") + (senderId != null ? " AND sender_id = ?" : "") +
                (requestId != null && !requestId.isBlank() ? " AND request_id = ?" : "");
        String sql = "SELECT site, sender_id, MAX(sender_name) AS sender_name, COUNT(*), " +
                "SUM(CASE WHEN status = 'STAGED' THEN 1 ELSE 0 END), " +
                "SUM(CASE WHEN status = 'QUEUED_FOR_CP' THEN 1 ELSE 0 END), " +
                "SUM(CASE WHEN status = 'ELASTICSEARCH_MONITORING' THEN 1 ELSE 0 END), " +
                "SUM(CASE WHEN status = 'CP_TIMEOUT' THEN 1 ELSE 0 END), " +
                "SUM(CASE WHEN status = 'EXENSIO_MONITORING' THEN 1 ELSE 0 END), " +
                "SUM(CASE WHEN status = 'COMPLETED_MANUAL_VERIFICATION_REQUIRED' THEN 1 ELSE 0 END), " +
                "SUM(CASE WHEN status = 'CP_FAILED' THEN 1 ELSE 0 END), " +
                "SUM(CASE WHEN status = 'LOAD_FAILED' THEN 1 ELSE 0 END), " +
                "SUM(CASE WHEN status = 'COMPLETED' THEN 1 ELSE 0 END), " +
                "SUM(CASE WHEN status = 'CANCELLED' THEN 1 ELSE 0 END) " +
                "FROM " + table + where + " GROUP BY site, sender_id";
        List<StageStatus> statuses = new ArrayList<>();
        try (Connection connection = dataSource.getConnection()) {
            Map<StageStatusKey, List<StageUserStatus>> userBreakdown = fetchUserBreakdown(connection, table, site, senderId, requestId);
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                int i = 1;
                if (site != null) ps.setString(i++, site);
                if (senderId != null) ps.setInt(i++, senderId);
                if (requestId != null && !requestId.isBlank()) ps.setString(i++, requestId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String rowSite = rs.getString(1);
                        int rowSender = rs.getInt(2);
                        StageStatusKey key = new StageStatusKey(rowSite, rowSender);
                        String senderName = normalizeSenderName(rs.getString(3));
                        statuses.add(new StageStatus(
                                rowSite,
                                rowSender,
                                senderName,
                                rs.getLong(4),      // total
                                rs.getLong(5),      // stagedToRefdb (STAGED)
                                rs.getLong(6),      // queuedForCp (QUEUED_FOR_CP)
                                rs.getLong(7),      // elasticsearchMonitoring (ELASTICSEARCH_MONITORING)
                                rs.getLong(8),      // cpTimeout (CP_TIMEOUT)
                                rs.getLong(9),      // exensioMonitoring (EXENSIO_MONITORING)
                                rs.getLong(10),     // completedManualVerification (COMPLETED_MANUAL_VERIFICATION_REQUIRED)
                                rs.getLong(11),     // cpFailed (CP_FAILED)
                                rs.getLong(12),     // loadFailed (LOAD_FAILED)
                                rs.getLong(13),     // completed (COMPLETED)
                                rs.getLong(14),     // cancelled (CANCELLED)
                                userBreakdown.getOrDefault(key, List.of())
                        ));
                    }
                }
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed loading stage status (filtered)", ex);
        }
        return statuses;
    }

    /**
     * Fetch statuses but limit the user breakdown to a specific user at the SQL level.
     * This avoids loading all user breakdown rows into memory when callers only need one user's view.
     */
    public List<StageStatus> fetchStatusesForUser(String site, Integer senderId, String userKey, String requestId) {
        String table = properties.getStagingTable();
        // If userKey is provided, include it in the aggregate SQL so totals are scoped to that user.
        String where = " WHERE 1=1" + (site != null ? " AND site = ?" : "") + (senderId != null ? " AND sender_id = ?" : "") +
                (userKey != null && !userKey.isBlank() ? " AND LOWER(COALESCE(last_requested_by, staged_by)) = ?" : "") +
                (requestId != null && !requestId.isBlank() ? " AND request_id = ?" : "");
        String sql = "SELECT site, sender_id, MAX(sender_name) AS sender_name, COUNT(*), " +
                "SUM(CASE WHEN status = 'STAGED' THEN 1 ELSE 0 END), " +
                "SUM(CASE WHEN status = 'QUEUED_FOR_CP' THEN 1 ELSE 0 END), " +
                "SUM(CASE WHEN status = 'ELASTICSEARCH_MONITORING' THEN 1 ELSE 0 END), " +
                "SUM(CASE WHEN status = 'CP_TIMEOUT' THEN 1 ELSE 0 END), " +
                "SUM(CASE WHEN status = 'EXENSIO_MONITORING' THEN 1 ELSE 0 END), " +
                "SUM(CASE WHEN status = 'COMPLETED_MANUAL_VERIFICATION_REQUIRED' THEN 1 ELSE 0 END), " +
                "SUM(CASE WHEN status = 'CP_FAILED' THEN 1 ELSE 0 END), " +
                "SUM(CASE WHEN status = 'LOAD_FAILED' THEN 1 ELSE 0 END), " +
                "SUM(CASE WHEN status = 'COMPLETED' THEN 1 ELSE 0 END), " +
                "SUM(CASE WHEN status = 'CANCELLED' THEN 1 ELSE 0 END) " +
                "FROM " + table + where + " GROUP BY site, sender_id";
        List<StageStatus> statuses = new ArrayList<>();
        try (Connection connection = dataSource.getConnection()) {
            Map<StageStatusKey, List<StageUserStatus>> userBreakdown = fetchUserBreakdown(connection, table, site, senderId, requestId);
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                int i = 1;
                if (site != null) ps.setString(i++, site);
                if (senderId != null) ps.setInt(i++, senderId);
                if (userKey != null && !userKey.isBlank()) ps.setString(i++, userKey.toLowerCase());
                if (requestId != null && !requestId.isBlank()) ps.setString(i++, requestId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String rowSite = rs.getString(1);
                        int rowSender = rs.getInt(2);
                        StageStatusKey key = new StageStatusKey(rowSite, rowSender);
                        String senderName = normalizeSenderName(rs.getString(3));
                        statuses.add(new StageStatus(
                                rowSite,
                                rowSender,
                                senderName,
                                rs.getLong(4),      // total
                                rs.getLong(5),      // stagedToRefdb (STAGED)
                                rs.getLong(6),      // queuedForCp (QUEUED_FOR_CP)
                                rs.getLong(7),      // elasticsearchMonitoring (ELASTICSEARCH_MONITORING)
                                rs.getLong(8),      // cpTimeout (CP_TIMEOUT)
                                rs.getLong(9),      // exensioMonitoring (EXENSIO_MONITORING)
                                rs.getLong(10),     // completedManualVerification (COMPLETED_MANUAL_VERIFICATION_REQUIRED)
                                rs.getLong(11),     // cpFailed (CP_FAILED)
                                rs.getLong(12),     // loadFailed (LOAD_FAILED)
                                rs.getLong(13),     // completed (COMPLETED)
                                rs.getLong(14),     // cancelled (CANCELLED)
                                userBreakdown.getOrDefault(key, List.of())
                        ));
                    }
                }
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed loading stage status (filtered by user)", ex);
        }
        return statuses;
    }

    public Set<String> findSitesWithPending() {
        String table = properties.getStagingTable();
        String sql = "SELECT DISTINCT site FROM " + table + " WHERE status = 'STAGED'";
        Set<String> sites = new HashSet<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                sites.add(rs.getString(1));
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed enumerating pending sites", ex);
        }
        return sites;
    }

    public List<StageRecord> fetchNextBatchForSite(String site, int limit) {
        String table = properties.getStagingTable();
        String sql = "SELECT id, site, sender_id, sender_name, metadata_id, data_id, lot, wafer, filename, end_time, status, " + coalesce("error_message", "''") + " AS error_message, created_at, updated_at, processed_at, staged_by, last_requested_by, last_requested_at, request_id, cp_output_path, cp_output_target, exensio_wafer_key, exensio_pg_key, data_type, test_phase " +
                "FROM " + table + " WHERE status = 'STAGED' AND site = ? ORDER BY created_at FETCH FIRST ? ROWS ONLY";
        List<StageRecord> records = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, site);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    records.add(mapRecord(rs));
                }
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed loading site batch", ex);
        }
        return records;
    }

    public List<StageRecord> fetchNextBatchForSender(String site, int senderId, int limit) {
        String table = properties.getStagingTable();
        String sql = "SELECT id, site, sender_id, sender_name, metadata_id, data_id, lot, wafer, filename, end_time, status, " + coalesce("error_message", "''") + " AS error_message, created_at, updated_at, processed_at, staged_by, last_requested_by, last_requested_at, request_id, cp_output_path, cp_output_target, exensio_wafer_key, exensio_pg_key, data_type, test_phase " +
                "FROM " + table + " WHERE status = 'STAGED' AND site = ? AND sender_id = ? ORDER BY created_at FETCH FIRST ? ROWS ONLY";
        List<StageRecord> records = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, site);
            ps.setInt(2, senderId);
            ps.setInt(3, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    records.add(mapRecord(rs));
                }
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed loading sender batch", ex);
        }
        return records;
    }

    public List<StageRecord> findEnqueuedWithoutProcessed(int limit) {
        if (limit <= 0) {
            limit = 200;
        }
        String table = properties.getStagingTable();
        String sql = "SELECT id, site, sender_id, sender_name, metadata_id, data_id, lot, wafer, filename, end_time, status, " + coalesce("error_message", "''") + " AS error_message, created_at, updated_at, processed_at, staged_by, last_requested_by, last_requested_at, request_id, cp_output_path, cp_output_target, exensio_wafer_key, exensio_pg_key, data_type, test_phase " +
                "FROM " + table + " WHERE status IN ('QUEUED_FOR_CP','ELASTICSEARCH_MONITORING','EXENSIO_MONITORING') AND processed_at IS NULL ORDER BY updated_at FETCH FIRST ? ROWS ONLY";
        List<StageRecord> records = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    records.add(mapRecord(rs));
                }
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed loading enqueued records", ex);
        }
        return records;
    }

    public List<StageRecord> listRecords(String site, Integer senderId, String status, int limit) {
        return listRecords(site, senderId, status, 0, limit);
    }

    // Backward-compatible overload (no sort) for legacy callers
    public List<StageRecord> listRecords(String site, Integer senderId, String status, int offset, int limit) {
        return listRecords(site, senderId, status, offset, limit, null, null);
    }

    private String resolveOrderBy(String sortBy, String sortDir) {
        String col;
        if (sortBy == null || sortBy.isBlank()) col = "updated_at";
        else {
            String s = sortBy.trim().toLowerCase();
            // whitelist allowed columns
            if (s.equals("updated") || s.equals("updated_at")) col = "updated_at";
            else if (s.equals("lastrequested") || s.equals("last_requested") || s.equals("last_requested_at")) col = "last_requested_at";
            else if (s.equals("created") || s.equals("created_at")) col = "created_at";
            else if (s.equals("end_time") || s.equals("endtime")) col = "end_time";
            else if (s.equals("status")) col = "status";
            else if (s.equals("lot")) col = "lot";
            else if (s.equals("wafer")) col = "wafer";
            else if (s.equals("filename")) col = "filename";
            else col = "updated_at"; // default fallback
        }
        String dir = (sortDir != null && sortDir.equalsIgnoreCase("asc")) ? "ASC" : "DESC";
        return " ORDER BY " + col + " " + dir;
    }

    public List<StageRecord> listRecords(String site, Integer senderId, String status, int offset, int limit, String sortBy, String sortDir) {
        return listRecords(site, senderId, status, offset, limit, sortBy, sortDir, null);
    }

    public List<StageRecord> listRecords(String site, Integer senderId, String status, int offset, int limit, String sortBy, String sortDir, String requestId) {
        return listRecords(site, senderId, status, offset, limit, sortBy, sortDir, requestId, null);
    }

    public List<StageRecord> listRecords(String site, Integer senderId, String status, int offset, int limit, String sortBy, String sortDir, String requestId, List<String> devices) {
        String table = properties.getStagingTable();
        StringBuilder sb = new StringBuilder("SELECT id, site, sender_id, sender_name, metadata_id, data_id, lot, wafer, device, filename, end_time, status, ")
                .append(coalesce("error_message", "''"))
                .append(" AS error_message, created_at, updated_at, processed_at, staged_by, last_requested_by, last_requested_at, request_id, cp_output_path, cp_output_target, exensio_wafer_key, exensio_pg_key, data_type, test_phase FROM ")
                .append(table)
                .append(" WHERE 1=1");
        List<Object> params = new ArrayList<>();
        if (site != null && !site.isBlank()) {
            sb.append(" AND site = ?");
            params.add(site);
        }
        if (senderId != null) {
            sb.append(" AND sender_id = ?");
            params.add(senderId);
        }
        if (status != null && !status.isBlank()) {
            String normalized = status.trim().toUpperCase();
            if (normalized.equals("QUEUED_FOR_CP") || normalized.equals("ELASTICSEARCH_MONITORING") || normalized.equals("PROCESSING")) {
                sb.append(" AND status IN ('QUEUED_FOR_CP','ELASTICSEARCH_MONITORING','EXENSIO_MONITORING')");
            } else {
                sb.append(" AND status = ?");
                params.add(status);
            }
        }
        if (requestId != null && !requestId.isBlank()) {
            sb.append(" AND request_id = ?");
            params.add(requestId);
        }
        if (devices != null && !devices.isEmpty()) {
            sb.append(" AND device IN (");
            for (int i = 0; i < devices.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append("?");
            }
            sb.append(")");
            params.addAll(devices);
        }
        sb.append(resolveOrderBy(sortBy, sortDir));
        int effectiveOffset = Math.max(offset, 0);
        if (limit > 0) {
            sb.append(" OFFSET ? ROWS FETCH NEXT ? ROWS ONLY");
            params.add(effectiveOffset);
            params.add(limit);
        }
        List<StageRecord> records = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sb.toString())) {
            int idx = 1;
            for (Object param : params) {
                if (param instanceof Integer i) ps.setInt(idx++, i);
                else if (param instanceof Long l) ps.setLong(idx++, l);
                else ps.setString(idx++, param == null ? null : param.toString());
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    records.add(mapRecord(rs));
                }
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed loading staged records", ex);
        }
        return records;
    }

    /**
     * Server-side text search variant: applies a case-insensitive LIKE filter across several columns.
     */
    // Backward-compatible overload (no sort) for legacy callers
    public List<StageRecord> listRecords(String site, Integer senderId, String status, String q, int offset, int limit) {
        return listRecords(site, senderId, status, q, offset, limit, null, null);
    }

    public List<StageRecord> listRecords(String site, Integer senderId, String status, String q, int offset, int limit, String sortBy, String sortDir) {
        return listRecords(site, senderId, status, q, offset, limit, sortBy, sortDir, null);
    }

    public List<StageRecord> listRecords(String site, Integer senderId, String status, String q, int offset, int limit, String sortBy, String sortDir, String requestId) {
        return listRecords(site, senderId, status, q, offset, limit, sortBy, sortDir, requestId, null);
    }

    public List<StageRecord> listRecords(String site, Integer senderId, String status, String q, int offset, int limit, String sortBy, String sortDir, String requestId, List<String> devices) {
        String table = properties.getStagingTable();
        StringBuilder sb = new StringBuilder("SELECT id, site, sender_id, sender_name, metadata_id, data_id, lot, wafer, device, filename, end_time, status, ")
                .append(coalesce("error_message", "''"))
                .append(" AS error_message, created_at, updated_at, processed_at, staged_by, last_requested_by, last_requested_at, request_id FROM ")
                .append(table)
                .append(" WHERE 1=1");
        List<Object> params = new ArrayList<>();
        if (site != null && !site.isBlank()) {
            sb.append(" AND site = ?");
            params.add(site);
        }
        if (senderId != null) {
            sb.append(" AND sender_id = ?");
            params.add(senderId);
        }
        if (status != null && !status.isBlank()) {
            sb.append(" AND status = ?");
            params.add(status);
        }
        if (requestId != null && !requestId.isBlank()) {
            sb.append(" AND request_id = ?");
            params.add(requestId);
        }
        if (devices != null && !devices.isEmpty()) {
            sb.append(" AND device IN (");
            for (int i = 0; i < devices.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append("?");
            }
            sb.append(")");
            params.addAll(devices);
        }
        if (q != null && !q.isBlank()) {
            // case-insensitive contains across a set of textual columns
            String likeExpr = "%" + q.toLowerCase() + "%";
            sb.append(" AND (LOWER(metadata_id) LIKE ? OR LOWER(data_id) LIKE ? OR LOWER(filename) LIKE ? OR LOWER(lot) LIKE ? OR LOWER(wafer) LIKE ? OR LOWER(status) LIKE ? OR LOWER(COALESCE(staged_by,'')) LIKE ? OR LOWER(COALESCE(last_requested_by,'')) LIKE ? OR LOWER(COALESCE(sender_name,'')) LIKE ?)");
            for (int i = 0; i < 9; i++) params.add(likeExpr);
        }
        sb.append(resolveOrderBy(sortBy, sortDir));
        int effectiveOffset = Math.max(offset, 0);
        if (limit > 0) {
            sb.append(" OFFSET ? ROWS FETCH NEXT ? ROWS ONLY");
            params.add(effectiveOffset);
            params.add(limit);
        }
        List<StageRecord> records = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sb.toString())) {
            int idx = 1;
            for (Object param : params) {
                if (param instanceof Integer i) ps.setInt(idx++, i);
                else if (param instanceof Long l) ps.setLong(idx++, l);
                else ps.setString(idx++, param == null ? null : param.toString());
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    records.add(mapRecord(rs));
                }
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed loading staged records (q)", ex);
        }
        return records;
    }

    /**
     * Like listRecords(...) but only returns rows where the effective owner (last_requested_by or staged_by)
     * matches the provided userKeyFilter (case-insensitive). This enables SQL-level scoping for non-admin users.
     */
    // Backward-compatible overload (no sort) for legacy callers
    public List<StageRecord> listRecordsForUser(String site, Integer senderId, String status, int offset, int limit, String userKeyFilter) {
        return listRecordsForUser(site, senderId, status, offset, limit, userKeyFilter, null, null);
    }

    public List<StageRecord> listRecordsForUser(String site, Integer senderId, String status, int offset, int limit, String userKeyFilter, String sortBy, String sortDir) {
        return listRecordsForUser(site, senderId, status, offset, limit, userKeyFilter, sortBy, sortDir, null);
    }

    public List<StageRecord> listRecordsForUser(String site, Integer senderId, String status, int offset, int limit, String userKeyFilter, String sortBy, String sortDir, String requestId) {
        String table = properties.getStagingTable();
        StringBuilder sb = new StringBuilder("SELECT id, site, sender_id, sender_name, metadata_id, data_id, lot, wafer, filename, end_time, status, ")
                .append(coalesce("error_message", "''"))
                .append(" AS error_message, created_at, updated_at, processed_at, staged_by, last_requested_by, last_requested_at, request_id FROM ")
                .append(table)
                .append(" WHERE 1=1");
        List<Object> params = new ArrayList<>();
        if (site != null && !site.isBlank()) {
            sb.append(" AND site = ?");
            params.add(site);
        }
        if (senderId != null) {
            sb.append(" AND sender_id = ?");
            params.add(senderId);
        }
        if (status != null && !status.isBlank()) {
            sb.append(" AND status = ?");
            params.add(status);
        }
        if (userKeyFilter != null && !userKeyFilter.isBlank()) {
            sb.append(" AND LOWER(COALESCE(last_requested_by, staged_by)) = ?");
            params.add(userKeyFilter.toLowerCase());
        }
        sb.append(resolveOrderBy(sortBy, sortDir));
        int effectiveOffset = Math.max(offset, 0);
        if (limit > 0) {
            sb.append(" OFFSET ? ROWS FETCH NEXT ? ROWS ONLY");
            params.add(effectiveOffset);
            params.add(limit);
        }

        List<StageRecord> records = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sb.toString())) {
            int idx = 1;
            for (Object param : params) {
                if (param instanceof Integer i) ps.setInt(idx++, i);
                else if (param instanceof Long l) ps.setLong(idx++, l);
                else ps.setString(idx++, param == null ? null : param.toString());
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    records.add(mapRecord(rs));
                }
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed loading staged records (user-scoped)", ex);
        }
        return records;
    }

    /**
     * User-scoped variant with server-side q filtering.
     */
    // Backward-compatible overload (no sort) for legacy callers
    public List<StageRecord> listRecordsForUser(String site, Integer senderId, String status, String q, int offset, int limit, String userKeyFilter) {
        return listRecordsForUser(site, senderId, status, q, offset, limit, userKeyFilter, null, null);
    }

    public List<StageRecord> listRecordsForUser(String site, Integer senderId, String status, String q, int offset, int limit, String userKeyFilter, String sortBy, String sortDir) {
        return listRecordsForUser(site, senderId, status, q, offset, limit, userKeyFilter, sortBy, sortDir, null);
    }

    public List<StageRecord> listRecordsForUser(String site, Integer senderId, String status, String q, int offset, int limit, String userKeyFilter, String sortBy, String sortDir, String requestId) {
        String table = properties.getStagingTable();
        StringBuilder sb = new StringBuilder("SELECT id, site, sender_id, sender_name, metadata_id, data_id, lot, wafer, filename, end_time, status, ")
                .append(coalesce("error_message", "''"))
                .append(" AS error_message, created_at, updated_at, processed_at, staged_by, last_requested_by, last_requested_at, request_id FROM ")
                .append(table)
                .append(" WHERE 1=1");
        List<Object> params = new ArrayList<>();
        if (site != null && !site.isBlank()) {
            sb.append(" AND site = ?");
            params.add(site);
        }
        if (senderId != null) {
            sb.append(" AND sender_id = ?");
            params.add(senderId);
        }
        if (status != null && !status.isBlank()) {
            sb.append(" AND status = ?");
            params.add(status);
        }
        if (userKeyFilter != null && !userKeyFilter.isBlank()) {
            sb.append(" AND LOWER(COALESCE(last_requested_by, staged_by)) = ?");
            params.add(userKeyFilter.toLowerCase());
        }
        if (requestId != null && !requestId.isBlank()) {
            sb.append(" AND request_id = ?");
            params.add(requestId);
        }
        if (q != null && !q.isBlank()) {
            String likeExpr = "%" + q.toLowerCase() + "%";
            sb.append(" AND (LOWER(metadata_id) LIKE ? OR LOWER(data_id) LIKE ? OR LOWER(filename) LIKE ? OR LOWER(lot) LIKE ? OR LOWER(wafer) LIKE ? OR LOWER(status) LIKE ? OR LOWER(COALESCE(staged_by,'')) LIKE ? OR LOWER(COALESCE(last_requested_by,'')) LIKE ? OR LOWER(COALESCE(sender_name,'')) LIKE ?)");
            for (int i = 0; i < 9; i++) params.add(likeExpr);
        }
        sb.append(resolveOrderBy(sortBy, sortDir));
        int effectiveOffset = Math.max(offset, 0);
        if (limit > 0) {
            sb.append(" OFFSET ? ROWS FETCH NEXT ? ROWS ONLY");
            params.add(effectiveOffset);
            params.add(limit);
        }
        List<StageRecord> records = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sb.toString())) {
            int idx = 1;
            for (Object param : params) {
                if (param instanceof Integer i) ps.setInt(idx++, i);
                else if (param instanceof Long l) ps.setLong(idx++, l);
                else ps.setString(idx++, param == null ? null : param.toString());
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    records.add(mapRecord(rs));
                }
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed loading staged records (user-scoped q)", ex);
        }
        return records;
    }

    public List<LotWaferAggregate> aggregateLotWafer(String site,
                                                     int senderId,
                                                     String q,
                                                     int limit,
                                                     Instant start,
                                                     Instant end,
                                                     String userKeyFilter,
                                                     String dateTimeField) {
        return aggregateLotWafer(site, senderId, q, limit, start, end, userKeyFilter, dateTimeField, null);
    }

    public List<LotWaferAggregate> aggregateLotWafer(String site,
                                                     int senderId,
                                                     String q,
                                                     int limit,
                                                     Instant start,
                                                     Instant end,
                                                     String userKeyFilter,
                                                     String dateTimeField,
                                                     String requestId) {
        if (site == null || site.isBlank()) {
            throw new IllegalArgumentException("site is required");
        }
        String table = properties.getStagingTable();
        List<Object> params = new ArrayList<>();
        params.add(site);
        params.add(senderId);

        List<LotWaferAggregate> aggregates = new ArrayList<>();

        try (Connection connection = dataSource.getConnection()) {
            String timestampExpr = resolveTimestampExpr(connection, table, dateTimeField);

            StringBuilder sb = new StringBuilder("SELECT lot, wafer, MIN(filename) AS filename, COUNT(*) AS total, ")
                    .append("SUM(CASE WHEN status = 'STAGED' THEN 1 ELSE 0 END) AS ready, ")
                    .append("SUM(CASE WHEN status IN ('QUEUED_FOR_CP','ELASTICSEARCH_MONITORING','EXENSIO_MONITORING') THEN 1 ELSE 0 END) AS enqueued, ")
                    .append("SUM(CASE WHEN status IN ('CP_FAILED','LOAD_FAILED') THEN 1 ELSE 0 END) AS failed, ")
                    .append("SUM(CASE WHEN status = 'COMPLETED' THEN 1 ELSE 0 END) AS completed ")
                    .append("FROM ")
                    .append(table)
                    .append(" WHERE site = ? AND sender_id = ?");

            if (requestId != null && !requestId.isBlank()) {
                sb.append(" AND request_id = ?");
                params.add(requestId);
            }

            if (userKeyFilter != null && !userKeyFilter.isBlank()) {
                sb.append(" AND LOWER(COALESCE(last_requested_by, staged_by)) = ?");
                params.add(userKeyFilter.toLowerCase());
            }

            if (start != null) {
                sb.append(" AND ").append(timestampExpr).append(" >= ?");
                params.add(Timestamp.from(start));
            }

            if (end != null) {
                sb.append(" AND ").append(timestampExpr).append(" < ?");
                params.add(Timestamp.from(end));
            }

            if (q != null && !q.isBlank()) {
                String likeExpr = "%" + q.toLowerCase() + "%";
                sb.append(" AND (LOWER(COALESCE(lot,'')) LIKE ? OR LOWER(COALESCE(wafer,'')) LIKE ? OR LOWER(COALESCE(filename,'')) LIKE ? OR LOWER(COALESCE(metadata_id,'')) LIKE ? OR LOWER(COALESCE(data_id,'')) LIKE ?)");
                params.add(likeExpr);
                params.add(likeExpr);
                params.add(likeExpr);
                params.add(likeExpr);
                params.add(likeExpr);
            }

            sb.append(" GROUP BY lot, wafer");
            sb.append(" ORDER BY (SUM(CASE WHEN status = 'STAGED' THEN 1 ELSE 0 END) + SUM(CASE WHEN status IN ('QUEUED_FOR_CP','ELASTICSEARCH_MONITORING','EXENSIO_MONITORING') THEN 1 ELSE 0 END)) DESC, COUNT(*) DESC");

            if (limit > 0) {
                sb.append(" FETCH FIRST ? ROWS ONLY");
                params.add(limit);
            }

            try (PreparedStatement ps = connection.prepareStatement(sb.toString())) {
                int idx = 1;
                for (Object param : params) {
                    if (param instanceof Integer i) {
                        ps.setInt(idx++, i);
                    } else if (param instanceof Long l) {
                        ps.setLong(idx++, l);
                    } else if (param instanceof Timestamp ts) {
                        ps.setTimestamp(idx++, ts);
                    } else {
                        ps.setString(idx++, param == null ? null : param.toString());
                    }
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        aggregates.add(new LotWaferAggregate(
                                rs.getString("lot"),
                                rs.getString("wafer"),
                                rs.getString("filename"),
                                rs.getLong("total"),
                                rs.getLong("ready"),
                                rs.getLong("enqueued"),
                                rs.getLong("failed"),
                                rs.getLong("completed")
                        ));
                    }
                }
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed aggregating lot/wafer breakdown", ex);
        }
        return aggregates;
    }

    public List<TimeBucketAggregate> aggregateTimeBuckets(String site,
                                                          int senderId,
                                                          Instant start,
                                                          Instant end,
                                                          int limit,
                                                          String userKeyFilter,
                                                          String dateTimeField) {
        return aggregateTimeBuckets(site, senderId, start, end, limit, userKeyFilter, dateTimeField, null);
    }

    public List<TimeBucketAggregate> aggregateTimeBuckets(String site,
                                                          int senderId,
                                                          Instant start,
                                                          Instant end,
                                                          int limit,
                                                          String userKeyFilter,
                                                          String dateTimeField,
                                                          String requestId) {
        if (site == null || site.isBlank()) {
            throw new IllegalArgumentException("site is required");
        }

        String table = properties.getStagingTable();

        List<Object> params = new ArrayList<>();
        params.add(site);
        params.add(senderId);

        List<TimeBucketAggregate> aggregates = new ArrayList<>();

        try (Connection connection = dataSource.getConnection()) {
            String timestampExpr = resolveTimestampExpr(connection, table, dateTimeField);
            String bucketExpr = isOracle ? "TRUNC(" + timestampExpr + ")" : "CAST(" + timestampExpr + " AS DATE)";

            StringBuilder sb = new StringBuilder()
                    .append("SELECT ").append(bucketExpr).append(" AS bucket_date, ")
                    .append("COUNT(*) AS total, ")
                    .append("SUM(CASE WHEN status = 'STAGED' THEN 1 ELSE 0 END) AS ready, ")
                    .append("SUM(CASE WHEN status IN ('QUEUED_FOR_CP','ELASTICSEARCH_MONITORING','EXENSIO_MONITORING') THEN 1 ELSE 0 END) AS enqueued, ")
                    .append("SUM(CASE WHEN status IN ('CP_FAILED','LOAD_FAILED') THEN 1 ELSE 0 END) AS failed, ")
                    .append("SUM(CASE WHEN status = 'COMPLETED' THEN 1 ELSE 0 END) AS completed ")
                    .append("FROM ").append(table)
                    .append(" WHERE site = ? AND sender_id = ?");

            if (requestId != null && !requestId.isBlank()) {
                sb.append(" AND request_id = ?");
                params.add(requestId);
            }

            if (userKeyFilter != null && !userKeyFilter.isBlank()) {
                sb.append(" AND LOWER(COALESCE(last_requested_by, staged_by)) = ?");
                params.add(userKeyFilter.toLowerCase());
            }

            if (start != null) {
                sb.append(" AND ").append(timestampExpr).append(" >= ?");
                params.add(Timestamp.from(start));
            }

            if (end != null) {
                sb.append(" AND ").append(timestampExpr).append(" < ?");
                params.add(Timestamp.from(end));
            }

            sb.append(" GROUP BY ").append(bucketExpr);
            sb.append(" ORDER BY bucket_date DESC");

            if (limit > 0) {
                sb.append(" FETCH FIRST ? ROWS ONLY");
                params.add(limit);
            }

            try (PreparedStatement ps = connection.prepareStatement(sb.toString())) {
                int idx = 1;
                for (Object param : params) {
                    if (param instanceof Integer i) {
                        ps.setInt(idx++, i);
                    } else if (param instanceof Long l) {
                        ps.setLong(idx++, l);
                    } else if (param instanceof Timestamp ts) {
                        ps.setTimestamp(idx++, ts);
                    } else {
                        ps.setString(idx++, param == null ? null : param.toString());
                    }
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        aggregates.add(new TimeBucketAggregate(
                                toInstant(rs.getTimestamp("bucket_date")),
                                rs.getLong("total"),
                                rs.getLong("ready"),
                                rs.getLong("enqueued"),
                                rs.getLong("failed"),
                                rs.getLong("completed")
                        ));
                    }
                }
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed aggregating date buckets", ex);
        }
        return aggregates;
    }

    /**
     * Returns true if a staged record for the given site/sender/metadataId/dataId exists and
     * the effective owner (last_requested_by or staged_by) matches the provided userKeyFilter.
     * Used to decide whether a non-admin user should be allowed to see information about
     * an existing staged payload that matches the metadata/data they are attempting to stage.
     */
    public boolean recordExistsForUser(String site, Integer senderId, String metadataId, String dataId, String userKeyFilter) {
        if (metadataId == null || dataId == null) return false;
        String table = properties.getStagingTable();
        StringBuilder sb = new StringBuilder("SELECT COUNT(1) FROM ").append(table).append(" WHERE 1=1");
        List<Object> params = new ArrayList<>();
        if (site != null && !site.isBlank()) {
            sb.append(" AND site = ?");
            params.add(site);
        }
        if (senderId != null) {
            sb.append(" AND sender_id = ?");
            params.add(senderId);
        }
        sb.append(" AND metadata_id = ? AND data_id = ?");
        params.add(metadataId);
        params.add(dataId);
        if (userKeyFilter != null && !userKeyFilter.isBlank()) {
            sb.append(" AND LOWER(COALESCE(last_requested_by, staged_by)) = ?");
            params.add(userKeyFilter.toLowerCase());
        }
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sb.toString())) {
            int idx = 1;
            for (Object param : params) {
                if (param instanceof Integer i) ps.setInt(idx++, i);
                else ps.setString(idx++, param == null ? null : param.toString());
            }
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1) > 0;
                }
            }
        } catch (SQLException ex) {
            log.warn("Failed checking recordExistsForUser: {}", ex.getMessage());
        }
        return false;
    }



    public long countRecords(String site, Integer senderId, String status) {
        return countRecords(site, senderId, status, null, null);
    }


    public List<StageRecord> listRecordsByStatus(String status, int limit) {
        return listRecords(null, null, status, limit);
    }

    /**
     * Count with optional server-side q filtering.
     */
    public long countRecords(String site, Integer senderId, String status, String q, String requestId) {
        return countRecords(site, senderId, status, q, requestId, null);
    }

    public long countRecords(String site, Integer senderId, String status, String q, String requestId, List<String> devices) {
        String table = properties.getStagingTable();
        StringBuilder sb = new StringBuilder("SELECT COUNT(1) FROM ").append(table).append(" WHERE 1=1");
        List<Object> params = new ArrayList<>();
        if (site != null && !site.isBlank()) {
            sb.append(" AND site = ?");
            params.add(site);
        }
        if (senderId != null) {
            sb.append(" AND sender_id = ?");
            params.add(senderId);
        }
        if (status != null && !status.isBlank()) {
            sb.append(" AND status = ?");
            params.add(status);
        }
        if (requestId != null && !requestId.isBlank()) {
            sb.append(" AND request_id = ?");
            params.add(requestId);
        }
        if (devices != null && !devices.isEmpty()) {
            sb.append(" AND device IN (");
            for (int i = 0; i < devices.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append("?");
            }
            sb.append(")");
            params.addAll(devices);
        }
        if (q != null && !q.isBlank()) {
            String likeExpr = "%" + q.toLowerCase() + "%";
            sb.append(" AND (LOWER(metadata_id) LIKE ? OR LOWER(data_id) LIKE ? OR LOWER(filename) LIKE ? OR LOWER(lot) LIKE ? OR LOWER(wafer) LIKE ? OR LOWER(status) LIKE ? OR LOWER(COALESCE(staged_by,'')) LIKE ? OR LOWER(COALESCE(last_requested_by,'')) LIKE ? OR LOWER(COALESCE(sender_name,'')) LIKE ?)");
            for (int i = 0; i < 9; i++) params.add(likeExpr);
        }
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sb.toString())) {
            int idx = 1;
            for (Object param : params) {
                if (param instanceof Integer i) ps.setInt(idx++, i);
                else if (param instanceof Long l) ps.setLong(idx++, l);
                else ps.setString(idx++, param == null ? null : param.toString());
            }
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getLong(1);
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed counting staged records (q)", ex);
        }
        return 0L;
    }

    /**
     * Count staged records but limited to a specific user (case-insensitive comparison of last_requested_by or staged_by).
     */
    public long countRecordsForUser(String site, Integer senderId, String status, String userKeyFilter) {
        return countRecordsForUser(site, senderId, status, userKeyFilter, null, null);
    }

    public long countRecordsForUser(String site, Integer senderId, String status, String userKeyFilter, String q, String requestId) {
        String table = properties.getStagingTable();
        StringBuilder sb = new StringBuilder("SELECT COUNT(1) FROM ").append(table).append(" WHERE 1=1");
        List<Object> params = new ArrayList<>();
        if (site != null && !site.isBlank()) {
            sb.append(" AND site = ?");
            params.add(site);
        }
        if (senderId != null) {
            sb.append(" AND sender_id = ?");
            params.add(senderId);
        }
        if (status != null && !status.isBlank()) {
            sb.append(" AND status = ?");
            params.add(status);
        }
        if (requestId != null && !requestId.isBlank()) {
            sb.append(" AND request_id = ?");
            params.add(requestId);
        }
        if (userKeyFilter != null && !userKeyFilter.isBlank()) {
            sb.append(" AND LOWER(COALESCE(last_requested_by, staged_by)) = ?");
            params.add(userKeyFilter.toLowerCase());
        }
        if (q != null && !q.isBlank()) {
            String likeExpr = "%" + q.toLowerCase() + "%";
            sb.append(" AND (LOWER(metadata_id) LIKE ? OR LOWER(data_id) LIKE ? OR LOWER(filename) LIKE ? OR LOWER(lot) LIKE ? OR LOWER(wafer) LIKE ? OR LOWER(status) LIKE ? OR LOWER(COALESCE(staged_by,'')) LIKE ? OR LOWER(COALESCE(last_requested_by,'')) LIKE ? OR LOWER(COALESCE(sender_name,'')) LIKE ?)");
            for (int i = 0; i < 9; i++) params.add(likeExpr);
        }
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sb.toString())) {
            int idx = 1;
            for (Object param : params) {
                if (param instanceof Integer i) ps.setInt(idx++, i);
                else if (param instanceof Long l) ps.setLong(idx++, l);
                else ps.setString(idx++, param == null ? null : param.toString());
            }
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getLong(1);
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed counting staged records (user-scoped)", ex);
        }
        return 0L;
    }

    public DataSource getDataSource() {
        return dataSource;
    }

    public String getStagingTable() {
        return properties.getStagingTable();
    }

    /**
     * Bulk-cancel staged records for a given sender that are in one of the specified statuses.
     * Used by the dashboard bulk-delete action (marks NEW/FAILED records as CANCELLED).
     *
     * After cancellation, emits aggregation events for all affected sessions to update dashboard counts.
     *
     * @return Number of rows updated.
     */
    public int bulkCancelBySender(int senderId, List<String> fromStatuses) {
        if (fromStatuses == null || fromStatuses.isEmpty()) {
            return 0;
        }
        String table = properties.getStagingTable();
        String inClause = String.join(",", fromStatuses.stream().map(s -> "?").toList());
        String sql = "UPDATE " + table + " SET status = 'CANCELLED', error_message = 'Bulk cancelled via dashboard', updated_at = " + timestampExpr()
                + " WHERE sender_id = ? AND status IN (" + inClause + ")";
        int rowsUpdated = 0;
        Set<String> affectedRequestIds = new HashSet<>();
        
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            int idx = 1;
            ps.setInt(idx++, senderId);
            for (String s : fromStatuses) {
                ps.setString(idx++, s);
            }
            rowsUpdated = ps.executeUpdate();
            
            // Query for affected request IDs so we can emit aggregation events
            if (rowsUpdated > 0) {
                String requestIdsQuery = "SELECT DISTINCT request_id FROM " + table 
                        + " WHERE sender_id = ? AND status = 'CANCELLED' AND request_id IS NOT NULL";
                try (PreparedStatement psReqIds = connection.prepareStatement(requestIdsQuery)) {
                    psReqIds.setInt(1, senderId);
                    try (ResultSet rs = psReqIds.executeQuery()) {
                        while (rs.next()) {
                            String requestId = rs.getString(1);
                            if (requestId != null && !requestId.isBlank()) {
                                affectedRequestIds.add(requestId);
                            }
                        }
                    }
                }
            }
        } catch (SQLException ex) {
            log.error("Failed bulk-cancelling records for sender {}: {}", senderId, ex.getMessage(), ex);
            throw new IllegalStateException("Failed bulk-cancelling records", ex);
        }
        
        // Emit aggregation events for each affected session
        // This batches the cancellations across 1-second window to reduce SSE traffic
        if (rowsUpdated > 0 && stateAggregationBatcher != null) {
            for (String requestId : affectedRequestIds) {
                recordStateChangeForBatcher(requestId, "CANCELLED");
                if (monitorService != null) {
                    broadcastStats(requestId);
                }
            }
        }
        
        return rowsUpdated;
    }

    /**
     * Count staged records for a given sender that are in one of the specified statuses.
     */
    public long bulkCountBySenderAndStatuses(int senderId, List<String> statuses) {
        if (statuses == null || statuses.isEmpty()) {
            return 0L;
        }
        String table = properties.getStagingTable();
        String inClause = String.join(",", statuses.stream().map(s -> "?").toList());
        String sql = "SELECT COUNT(1) FROM " + table + " WHERE sender_id = ? AND status IN (" + inClause + ")";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            int idx = 1;
            ps.setInt(idx++, senderId);
            for (String s : statuses) {
                ps.setString(idx++, s);
            }
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getLong(1);
            }
        } catch (SQLException ex) {
            log.warn("Failed counting records for sender {} in statuses {}: {}", senderId, statuses, ex.getMessage());
        }
        return 0L;
    }

    private void updateStatus(List<Long> ids, String status, String message) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        String table = properties.getStagingTable();
        String sql = "UPDATE " + table + " SET status = ?, error_message = ? WHERE id = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            for (Long id : ids) {
                ps.setString(1, status);
                if (message == null) {
                    ps.setNull(2, java.sql.Types.VARCHAR);
                } else {
                    ps.setString(2, truncate(message));
                }
                ps.setLong(3, id);
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed updating status", ex);
        }
    }

    private StageRecord mapRecord(ResultSet rs) throws SQLException {
        return new StageRecord(
                rs.getLong("id"),
                rs.getString("site"),
                rs.getInt("sender_id"),
                rs.getString("sender_name"),
                rs.getString("metadata_id"),
                rs.getString("data_id"),
                rs.getString("lot"),
                rs.getString("wafer"),
                safeString(rs, "device"),
                rs.getString("filename"),
                toInstant(safeTimestamp(rs, "end_time")),
                rs.getString("status"),
                rs.getString("error_message"),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("updated_at")),
                toInstant(rs.getTimestamp("processed_at")),
                rs.getString("staged_by"),
                rs.getString("last_requested_by"),
                toInstant(rs.getTimestamp("last_requested_at")),
                rs.getString("request_id"),
                safeString(rs, "cp_output_path"),
                safeString(rs, "cp_output_target"),
                safeLong(rs, "exensio_wafer_key"),
                safeLong(rs, "exensio_pg_key"),
                safeString(rs, "data_type"),
                safeString(rs, "test_phase")
        );
    }

    private void ensureStageTable(Connection connection) throws SQLException {
        String table = properties.getStagingTable();
        if (!tableExists(connection, table)) {
            createTable(connection, table);
        } else {
            // Ensure status column is large enough for all status values
            ensureStatusColumnSize(connection, table);
        }
        ensureProcessedAtColumn(connection, table);
        ensureUserColumns(connection, table);
        ensureSenderNameColumn(connection, table);
        ensureFileColumns(connection, table);
        ensureRequestIdColumn(connection, table);
        if (!sequenceExists(connection, table + "_SEQ")) {
            createSequence(connection, table + "_SEQ");
        }
        if (!indexExists(connection, table, table + "_STATUS_IDX")) {
            addStatusIndex(connection, table, table + "_STATUS_IDX");
        }
    }

    // --- Authorization schema (local app users/roles) ---
    private void ensureAuthTables(Connection connection) throws SQLException {
        // USERS(username PK), ROLES(name PK), USER_ROLES(username, role_name)
        if (!tableExists(connection, "APP_USERS")) {
            String ddl = isOracle
                    ? "CREATE TABLE APP_USERS (username VARCHAR2(128) PRIMARY KEY, active NUMBER(1) DEFAULT 1 NOT NULL)"
                    : "CREATE TABLE APP_USERS (username VARCHAR(128) PRIMARY KEY, active BOOLEAN DEFAULT TRUE NOT NULL)";
            try (Statement st = connection.createStatement()) { st.executeUpdate(ddl); }
        }
        if (!tableExists(connection, "APP_ROLES")) {
            String ddl = isOracle
                    ? "CREATE TABLE APP_ROLES (name VARCHAR2(64) PRIMARY KEY)"
                    : "CREATE TABLE APP_ROLES (name VARCHAR(64) PRIMARY KEY)";
            try (Statement st = connection.createStatement()) { st.executeUpdate(ddl); }
        }
        if (!tableExists(connection, "APP_USER_ROLES")) {
            String ddl = isOracle
                    ? "CREATE TABLE APP_USER_ROLES (username VARCHAR2(128) NOT NULL, role_name VARCHAR2(64) NOT NULL, CONSTRAINT PK_APP_USER_ROLES PRIMARY KEY (username, role_name))"
                    : "CREATE TABLE APP_USER_ROLES (username VARCHAR(128) NOT NULL, role_name VARCHAR(64) NOT NULL, CONSTRAINT PK_APP_USER_ROLES PRIMARY KEY (username, role_name))";
            try (Statement st = connection.createStatement()) { st.executeUpdate(ddl); }
        }
        // Ensure ROLE_USER and ROLE_ADMIN exist
        upsertRole(connection, "ROLE_USER");
        upsertRole(connection, "ROLE_ADMIN");
    }

    private void upsertRole(Connection connection, String roleName) throws SQLException {
        String sqlCheck = "SELECT COUNT(1) FROM APP_ROLES WHERE name = ?";
        try (PreparedStatement ps = connection.prepareStatement(sqlCheck)) {
            ps.setString(1, roleName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next() && rs.getInt(1) == 0) {
                    try (PreparedStatement ins = connection.prepareStatement("INSERT INTO APP_ROLES(name) VALUES (?)")) {
                        ins.setString(1, roleName);
                        ins.executeUpdate();
                    }
                }
            }
        }
    }

    private void bootstrapAdmins(Connection connection) throws SQLException {
        String seed = properties.getBootstrapAdmins();
        if (seed == null || seed.isBlank()) {
            return;
        }
        String[] users = seed.split(",");
        for (String u : users) {
            String username = normalizeUser(u);
            if (username.isBlank()) continue;
            ensureUser(connection, username);
            ensureUserRole(connection, username, "ROLE_USER");
            ensureUserRole(connection, username, "ROLE_ADMIN");
        }
    }

    private void ensureUser(Connection connection, String username) throws SQLException {
        String check = "SELECT COUNT(1) FROM APP_USERS WHERE username = ?";
        try (PreparedStatement ps = connection.prepareStatement(check)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next() && rs.getInt(1) == 0) {
                    try (PreparedStatement ins = connection.prepareStatement("INSERT INTO APP_USERS(username, active) VALUES (?, ?)")) {
                        psCloseableSet(ins, 1, username);
                        if (isOracle) ins.setInt(2, 1); else ins.setBoolean(2, true);
                        ins.executeUpdate();
                    }
                }
            }
        }
    }

    private void ensureUserRole(Connection connection, String username, String role) throws SQLException {
        String check = "SELECT COUNT(1) FROM APP_USER_ROLES WHERE username = ? AND role_name = ?";
        try (PreparedStatement ps = connection.prepareStatement(check)) {
            ps.setString(1, username);
            ps.setString(2, role);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next() && rs.getInt(1) == 0) {
                    try (PreparedStatement ins = connection.prepareStatement("INSERT INTO APP_USER_ROLES(username, role_name) VALUES (?, ?)")) {
                        ins.setString(1, username);
                        ins.setString(2, role);
                        ins.executeUpdate();
                    }
                }
            }
        }
    }

    private void psCloseableSet(PreparedStatement ps, int idx, String value) throws SQLException {
        if (value == null) {
            ps.setNull(idx, java.sql.Types.VARCHAR);
        } else {
            ps.setString(idx, value);
        }
    }

    // Legacy authentication methods removed - using modern JPA-based authentication
    // All user management is now handled by AppUserRepository and UserManagementService

    private boolean tableExists(Connection connection, String table) throws SQLException {
        if (isOracle) {
            try (PreparedStatement ps = connection.prepareStatement("SELECT COUNT(1) FROM user_tables WHERE table_name = ?")) {
                ps.setString(1, table.toUpperCase());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() && rs.getInt(1) > 0;
                }
            }
        } else {
            // H2: use INFORMATION_SCHEMA
            try (PreparedStatement ps = connection.prepareStatement("SELECT COUNT(1) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = ?")) {
                ps.setString(1, table.toUpperCase());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() && rs.getInt(1) > 0;
                }
            }
        }
    }

    private boolean sequenceExists(Connection connection, String sequence) throws SQLException {
        if (isOracle) {
            try (PreparedStatement ps = connection.prepareStatement("SELECT COUNT(1) FROM user_sequences WHERE sequence_name = ?")) {
                ps.setString(1, sequence.toUpperCase());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() && rs.getInt(1) > 0;
                }
            }
        } else {
            try (PreparedStatement ps = connection.prepareStatement("SELECT COUNT(1) FROM INFORMATION_SCHEMA.SEQUENCES WHERE SEQUENCE_NAME = ?")) {
                ps.setString(1, sequence.toUpperCase());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() && rs.getInt(1) > 0;
                }
            }
        }
    }

    private boolean constraintExists(Connection connection, String table, String constraint) throws SQLException {
        if (isOracle) {
            try (PreparedStatement ps = connection.prepareStatement("SELECT COUNT(1) FROM user_constraints WHERE table_name = ? AND constraint_name = ?")) {
                ps.setString(1, table.toUpperCase());
                ps.setString(2, constraint.toUpperCase());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() && rs.getInt(1) > 0;
                }
            }
        } else {
            // H2 exposes table constraints via INFORMATION_SCHEMA.TABLE_CONSTRAINTS
            try (PreparedStatement ps = connection.prepareStatement("SELECT COUNT(1) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS WHERE TABLE_NAME = ? AND CONSTRAINT_NAME = ?")) {
                ps.setString(1, table.toUpperCase());
                ps.setString(2, constraint.toUpperCase());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() && rs.getInt(1) > 0;
                }
            }
        }
    }

    private boolean indexExists(Connection connection, String table, String index) throws SQLException {
        if (isOracle) {
            try (PreparedStatement ps = connection.prepareStatement("SELECT COUNT(1) FROM user_indexes WHERE table_name = ? AND index_name = ?")) {
                ps.setString(1, table.toUpperCase());
                ps.setString(2, index.toUpperCase());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() && rs.getInt(1) > 0;
                }
            }
        } else {
            try (PreparedStatement ps = connection.prepareStatement("SELECT COUNT(1) FROM INFORMATION_SCHEMA.INDEXES WHERE TABLE_NAME = ? AND INDEX_NAME = ?")) {
                ps.setString(1, table.toUpperCase());
                ps.setString(2, index.toUpperCase());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() && rs.getInt(1) > 0;
                }
            }
        }
    }

    private void createTable(Connection connection, String table) throws SQLException {
        String ddl;
        if (isOracle) {
            ddl = "CREATE TABLE " + table + " (" +
                    "id NUMBER PRIMARY KEY, " +
                    "site VARCHAR2(64) NOT NULL, " +
                    "sender_id NUMBER NOT NULL, " +
                    "sender_name VARCHAR2(256), " +
                    "metadata_id VARCHAR2(128) NOT NULL, " +
                    "data_id VARCHAR2(128) NOT NULL, " +
                    "lot VARCHAR2(128), " +
                    "wafer VARCHAR2(128), " +
                    "filename VARCHAR2(512), " +
                    "end_time TIMESTAMP, " +
                    "status VARCHAR2(36) DEFAULT 'STAGED' NOT NULL, " +
                    "error_message VARCHAR2(4000), " +
                    "created_at TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL, " +
                    "updated_at TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL, " +
                    "processed_at TIMESTAMP, " +
                    "staged_by VARCHAR2(128), " +
                    "last_requested_by VARCHAR2(128), " +
                    "last_requested_at TIMESTAMP" +
                    ")";
        } else {
            ddl = "CREATE TABLE " + table + " (" +
                    "id BIGINT PRIMARY KEY, " +
                    "site VARCHAR(64) NOT NULL, " +
                    "sender_id INT NOT NULL, " +
                    "sender_name VARCHAR(256), " +
                    "metadata_id VARCHAR(128) NOT NULL, " +
                    "data_id VARCHAR(128) NOT NULL, " +
                    "lot VARCHAR(128), " +
                    "wafer VARCHAR(128), " +
                    "filename VARCHAR(512), " +
                    "end_time TIMESTAMP, " +
                    "status VARCHAR(36) DEFAULT 'STAGED' NOT NULL, " +
                    "error_message VARCHAR(4000), " +
                    "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL, " +
                    "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL, " +
                    "processed_at TIMESTAMP, " +
                    "staged_by VARCHAR(128), " +
                    "last_requested_by VARCHAR(128), " +
                    "last_requested_at TIMESTAMP" +
                    ")";
        }
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(ddl);
        }
    }

    private void ensureFileColumns(Connection connection, String table) throws SQLException {
        boolean lotAdded = ensureColumn(connection, table, "LOT", isOracle
                ? "ALTER TABLE " + table + " ADD (lot VARCHAR2(128))"
                : "ALTER TABLE " + table + " ADD (lot VARCHAR(128))");
        boolean waferAdded = ensureColumn(connection, table, "WAFER", isOracle
                ? "ALTER TABLE " + table + " ADD (wafer VARCHAR2(128))"
                : "ALTER TABLE " + table + " ADD (wafer VARCHAR(128))");
        boolean filenameAdded = ensureColumn(connection, table, "FILENAME", isOracle
                ? "ALTER TABLE " + table + " ADD (filename VARCHAR2(512))"
                : "ALTER TABLE " + table + " ADD (filename VARCHAR(512))");
        boolean endTimeAdded = ensureColumn(connection, table, "END_TIME",
                "ALTER TABLE " + table + " ADD (end_time TIMESTAMP)");

        if (lotAdded || waferAdded || filenameAdded || endTimeAdded) {
            log.info("File metadata columns ensured for {}", table);
        }
    }

    private void ensureSenderNameColumn(Connection connection, String table) throws SQLException {
        boolean senderNameAdded = ensureColumn(connection, table, "SENDER_NAME", isOracle
                ? "ALTER TABLE " + table + " ADD (sender_name VARCHAR2(256))"
                : "ALTER TABLE " + table + " ADD (sender_name VARCHAR(256))");
        if (senderNameAdded) {
            log.info("Sender name column ensured for {}", table);
        }
    }

    private void ensureRequestIdColumn(Connection connection, String table) throws SQLException {
        boolean requestIdAdded = ensureColumn(connection, table, "REQUEST_ID", isOracle
                ? "ALTER TABLE " + table + " ADD (request_id VARCHAR2(128))"
                : "ALTER TABLE " + table + " ADD (request_id VARCHAR(128))");
        if (requestIdAdded) {
            log.info("Request ID column ensured for {}", table);
        }
    }

    /**
     * Ensures the status column is large enough to hold all status values.
     * The longest status value is 'COMPLETED_MANUAL_VERIFICATION_REQUIRED' (33 chars).
     */
    private void ensureStatusColumnSize(Connection connection, String table) throws SQLException {
        // For Oracle, check current column size and increase if needed
        if (isOracle) {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT DATA_LENGTH FROM USER_TAB_COLUMNS WHERE TABLE_NAME = ? AND COLUMN_NAME = 'STATUS'")) {
                ps.setString(1, table.toUpperCase());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        int currentLength = rs.getInt("DATA_LENGTH");
                        if (currentLength < 36) {
                            // Need to increase the column size
                            String ddl = "ALTER TABLE " + table + " MODIFY (STATUS VARCHAR2(36))";
                            try (Statement stmt = connection.createStatement()) {
                                stmt.executeUpdate(ddl);
                                log.info("Increased STATUS column size from {} to 36 for table {}", currentLength, table);
                            }
                        }
                    }
                }
            } catch (SQLException e) {
                // If we can't check the column size, try to alter it anyway (might fail if already large enough)
                try (Statement stmt = connection.createStatement()) {
                    stmt.executeUpdate("ALTER TABLE " + table + " MODIFY (STATUS VARCHAR2(36))");
                    log.info("Ensured STATUS column size is 36 for table {}", table);
                } catch (SQLException ex) {
                    log.warn("Could not verify/update STATUS column size for table {}: {}", table, ex.getMessage());
                }
            }
        } else {
            // For H2, check and alter if needed
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT CHARACTER_MAXIMUM_LENGTH FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = ? AND COLUMN_NAME = 'STATUS'")) {
                ps.setString(1, table.toUpperCase());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        Integer currentLength = rs.getInt("CHARACTER_MAXIMUM_LENGTH");
                        if (currentLength == 0 || currentLength < 36) {
                            // Need to increase the column size
                            String ddl = "ALTER TABLE " + table + " ALTER COLUMN STATUS SET VARCHAR(36)";
                            try (Statement stmt = connection.createStatement()) {
                                stmt.executeUpdate(ddl);
                                log.info("Increased STATUS column size from {} to 36 for table {}", currentLength, table);
                            }
                        }
                    }
                }
            } catch (SQLException e) {
                // If we can't check, try to alter anyway
                try (Statement stmt = connection.createStatement()) {
                    stmt.executeUpdate("ALTER TABLE " + table + " ALTER COLUMN STATUS SET VARCHAR(36)");
                    log.info("Ensured STATUS column size is 36 for table {}", table);
                } catch (SQLException ex) {
                    log.warn("Could not verify/update STATUS column size for table {}: {}", table, ex.getMessage());
                }
            }
        }
    }

    private void createSequence(Connection connection, String name) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            if (isOracle) {
                statement.executeUpdate("CREATE SEQUENCE " + name + " START WITH 1 INCREMENT BY 1 NOCACHE");
            } else {
                statement.executeUpdate("CREATE SEQUENCE " + name + " START WITH 1 INCREMENT BY 1");
            }
        }
    }

    private void addUniqueConstraint(Connection connection, String table, String constraint) throws SQLException {
        String ddl = "ALTER TABLE " + table + " ADD CONSTRAINT " + constraint +
                " UNIQUE (site, sender_id, metadata_id, data_id)";
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(ddl);
        }
    }

    private void addStatusIndex(Connection connection, String table, String index) throws SQLException {
        String ddl = "CREATE INDEX " + index + " ON " + table + " (status, site)";
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(ddl);
        }
    }

    private void ensureProcessedAtColumn(Connection connection, String table) throws SQLException {
        if (!columnExists(connection, table, "PROCESSED_AT")) {
            addProcessedAtColumn(connection, table);
        }
    }

    private void ensureUserColumns(Connection connection, String table) throws SQLException {
        boolean stagedByAdded = ensureColumn(connection, table, "STAGED_BY", isOracle
                ? "ALTER TABLE " + table + " ADD (staged_by VARCHAR2(128))"
                : "ALTER TABLE " + table + " ADD (staged_by VARCHAR(128))");
        boolean lastRequestedByAdded = ensureColumn(connection, table, "LAST_REQUESTED_BY", isOracle
                ? "ALTER TABLE " + table + " ADD (last_requested_by VARCHAR2(128))"
                : "ALTER TABLE " + table + " ADD (last_requested_by VARCHAR(128))");
        boolean lastRequestedAtAdded = ensureColumn(connection, table, "LAST_REQUESTED_AT", "ALTER TABLE " + table + " ADD (last_requested_at TIMESTAMP)");

        if (stagedByAdded || lastRequestedByAdded || lastRequestedAtAdded) {
            log.info("User metadata columns ensured for {}", table);
        }

        backfillUserColumns(connection, table);
    }

    private boolean ensureColumn(Connection connection, String table, String column, String ddl) throws SQLException {
        if (columnExists(connection, table, column)) {
            return false;
        }
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(ddl);
        }
        return true;
    }

    private void backfillUserColumns(Connection connection, String table) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE " + table + " SET staged_by = '" + UNKNOWN_USER + "' WHERE staged_by IS NULL");
            statement.executeUpdate("UPDATE " + table + " SET last_requested_by = COALESCE(last_requested_by, staged_by) WHERE last_requested_by IS NULL");
            String timestampFallback = isOracle ? "COALESCE(last_requested_at, updated_at, created_at, SYSTIMESTAMP)"
                    : "COALESCE(last_requested_at, updated_at, created_at, CURRENT_TIMESTAMP)";
            statement.executeUpdate("UPDATE " + table + " SET last_requested_at = " + timestampFallback + " WHERE last_requested_at IS NULL");
        }
    }

    private String resolveTimestampExpr(Connection connection, String table, String dateTimeField) throws SQLException {
        boolean hasEndTime = columnExists(connection, table, "END_TIME");
        boolean hasCreatedAt = columnExists(connection, table, "CREATED_AT");

        String fallback = hasEndTime
                ? "COALESCE(end_time, processed_at, updated_at, created_at)"
                : "COALESCE(processed_at, updated_at, created_at)";

        if ("created_time".equalsIgnoreCase(dateTimeField)) {
            if (hasCreatedAt) {
                return hasEndTime
                        ? "COALESCE(created_at, end_time, processed_at, updated_at)"
                        : "COALESCE(created_at, processed_at, updated_at)";
            }
        }

        return fallback;
    }

    private boolean columnExists(Connection connection, String table, String column) throws SQLException {
        if (isOracle) {
            try (PreparedStatement ps = connection.prepareStatement("SELECT COUNT(1) FROM user_tab_cols WHERE table_name = ? AND column_name = ?")) {
                ps.setString(1, table.toUpperCase());
                ps.setString(2, column.toUpperCase());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() && rs.getInt(1) > 0;
                }
            }
        } else {
            try (PreparedStatement ps = connection.prepareStatement("SELECT COUNT(1) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = ? AND COLUMN_NAME = ?")) {
                ps.setString(1, table.toUpperCase());
                ps.setString(2, column.toUpperCase());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() && rs.getInt(1) > 0;
                }
            }
        }
    }

    private void addProcessedAtColumn(Connection connection, String table) throws SQLException {
        String ddl = "ALTER TABLE " + table + " ADD (processed_at TIMESTAMP)";
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(ddl);
        }
    }

    private Map<StageStatusKey, List<StageUserStatus>> fetchUserBreakdown(Connection connection,
                                                                          String table,
                                                                          String site,
                                                                          Integer senderId,
                                                                          String userKeyFilter) throws SQLException {
        StringBuilder sb = new StringBuilder("SELECT site, sender_id, COALESCE(last_requested_by, staged_by) AS user_key, COUNT(*), ")
                .append("SUM(CASE WHEN status = 'STAGED' THEN 1 ELSE 0 END), ")
                .append("SUM(CASE WHEN status IN ('QUEUED_FOR_CP','ELASTICSEARCH_MONITORING','EXENSIO_MONITORING') THEN 1 ELSE 0 END), ")
                .append("SUM(CASE WHEN status IN ('CP_FAILED','LOAD_FAILED') THEN 1 ELSE 0 END), ")
                .append("SUM(CASE WHEN status = 'COMPLETED' THEN 1 ELSE 0 END), ")
                .append("MAX(last_requested_at) FROM ")
                .append(table)
                .append(" WHERE 1=1");
        List<Object> params = new ArrayList<>();
        if (site != null) {
            sb.append(" AND site = ?");
            params.add(site);
        }
        if (senderId != null) {
            sb.append(" AND sender_id = ?");
            params.add(senderId);
        }
        // If a userKeyFilter is provided, limit breakdown to that user at SQL level for performance.
        // Use LOWER(...) to make the comparison case-insensitive and match previous in-memory behavior.
        if (userKeyFilter != null && !userKeyFilter.isBlank()) {
            sb.append(" AND LOWER(COALESCE(last_requested_by, staged_by)) = ?");
            params.add(userKeyFilter.toLowerCase());
        }
        sb.append(" GROUP BY site, sender_id, COALESCE(last_requested_by, staged_by)");

        Map<StageStatusKey, List<StageUserStatus>> result = new HashMap<>();
        try (PreparedStatement ps = connection.prepareStatement(sb.toString())) {
            int idx = 1;
            for (Object param : params) {
                if (param instanceof Integer i) {
                    ps.setInt(idx++, i);
                } else {
                    ps.setString(idx++, param == null ? null : param.toString());
                }
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String rowSite = rs.getString(1);
                    int rowSender = rs.getInt(2);
                    String rawUser = rs.getString(3);
                    long total = rs.getLong(4);
                    long ready = rs.getLong(5);
                    long enqueued = rs.getLong(6);
                    long failed = rs.getLong(7);
                    long completed = rs.getLong(8);
                    Instant lastRequestedAt = toInstant(rs.getTimestamp(9));
                    StageUserStatus userStatus = new StageUserStatus(displayUser(rawUser), total, ready, enqueued, failed, completed, lastRequestedAt);
                    StageStatusKey key = new StageStatusKey(rowSite, rowSender);
                    result.computeIfAbsent(key, k -> new ArrayList<>()).add(userStatus);
                }
            }
        }

        for (List<StageUserStatus> list : result.values()) {
            list.sort((a, b) -> {
                long backlogA = a.stagedToRefdb() + a.enqueued() + a.failed();
                long backlogB = b.stagedToRefdb() + b.enqueued() + b.failed();
                if (backlogA != backlogB) {
                    return Long.compare(backlogB, backlogA);
                }
                if (a.total() != b.total()) {
                    return Long.compare(b.total(), a.total());
                }
                String ua = a.username() == null ? "" : a.username();
                String ub = b.username() == null ? "" : b.username();
                return ua.compareToIgnoreCase(ub);
            });
        }

        return result;
    }

    private void markRetry(Connection connection,
                           String table,
                           String site,
                           int senderId,
                           PayloadCandidate candidate,
                           String requestedBy,
                           String senderName,
                           String requestId) {
        // Include lot and wafer in the WHERE clause to ensure we update the correct specific record
        // Also update request_id so the new session can find these records in monitoring
        // Normalize empty strings to null so IS NULL comparisons work correctly
        String candidateLot = (candidate.lot() == null || candidate.lot().isBlank()) ? null : candidate.lot().trim();
        String candidateWafer = (candidate.wafer() == null || candidate.wafer().isBlank()) ? null : candidate.wafer().trim();
        String sql = "UPDATE " + table + " SET status = 'STAGED', error_message = NULL, processed_at = NULL, updated_at = " + timestampExpr() + ", " +
                "last_requested_by = ?, last_requested_at = " + timestampExpr() + ", sender_name = COALESCE(?, sender_name)" +
                (requestId != null ? ", request_id = ?" : "") +
                " WHERE site = ? AND sender_id = ? AND metadata_id = ? AND data_id = ? " +
                "AND " + (candidateLot == null ? "lot IS NULL" : "lot = ?") +
                " AND " + (candidateWafer == null ? "wafer IS NULL" : "wafer = ?");
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, requestedBy);
            ps.setString(2, senderName);
            int paramIndex = 3;
            if (requestId != null) {
                ps.setString(paramIndex++, requestId);
            }
            ps.setString(paramIndex++, site);
            ps.setInt(paramIndex++, senderId);
            ps.setString(paramIndex++, candidate.metadataId());
            ps.setString(paramIndex++, candidate.dataId());
            if (candidateLot != null) {
                ps.setString(paramIndex++, candidateLot);
            }
            if (candidateWafer != null) {
                ps.setString(paramIndex++, candidateWafer);
            }
            int updated = ps.executeUpdate();
            if (updated == 0) {
                log.warn("markRetry updated 0 rows for metadataId={} dataId={} lot={} wafer={} site={} senderId={}",
                        candidate.metadataId(), candidate.dataId(), candidateLot, candidateWafer, site, senderId);
            }
        } catch (SQLException ex) {
            log.warn("Failed updating duplicate payload for retry: {}", candidate, ex);
        }
    }

    private ExistingPayload loadExistingPayload(Connection connection,
                                                String table,
                                                String site,
                                                int senderId,
                                                PayloadCandidate candidate) {
        // Normalize lot/wafer the same way markRetry does
        String candidateLot = (candidate.lot() == null || candidate.lot().isBlank()) ? null : candidate.lot().trim();
        String candidateWafer = (candidate.wafer() == null || candidate.wafer().isBlank()) ? null : candidate.wafer().trim();

        // Query with lot and wafer in the WHERE clause to find the exact matching row
        String sql = "SELECT id, status, processed_at, created_at, staged_by, last_requested_by, last_requested_at FROM " + table +
                " WHERE site = ? AND sender_id = ? AND metadata_id = ? AND data_id = ? " +
                "AND " + (candidateLot == null ? "lot IS NULL" : "lot = ?") +
                " AND " + (candidateWafer == null ? "wafer IS NULL" : "wafer = ?") +
                " ORDER BY id DESC FETCH FIRST 1 ROWS ONLY";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, site);
            ps.setInt(2, senderId);
            ps.setString(3, candidate.metadataId());
            ps.setString(4, candidate.dataId());
            int paramIndex = 5;
            if (candidateLot != null) {
                ps.setString(paramIndex++, candidateLot);
            }
            if (candidateWafer != null) {
                ps.setString(paramIndex++, candidateWafer);
            }
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new ExistingPayload(
                            rs.getLong("id"),
                            rs.getString("status"),
                            toInstant(rs.getTimestamp("processed_at")),
                            toInstant(rs.getTimestamp("created_at")),
                            rs.getString("staged_by"),
                            rs.getString("last_requested_by"),
                            toInstant(rs.getTimestamp("last_requested_at"))
                    );
                }
            }
        } catch (SQLException ex) {
            log.warn("Failed loading existing payload for duplicate {}: {}", candidate, ex.getMessage());
        }
        return null;
    }

    private DuplicatePayload toDuplicatePayload(PayloadCandidate candidate, ExistingPayload existing, boolean requiresConfirmation) {
        if (existing == null) {
            return new DuplicatePayload(
                    candidate.metadataId(),
                    candidate.dataId(),
                    candidate.lot(),
                    candidate.wafer(),
                    candidate.filename(),
                    null, null, null, null, null, null,
                    requiresConfirmation
            );
        }
        String stagedBy = displayUser(existing.stagedBy());
        String lastRequestedBy = displayUser(existing.lastRequestedBy() != null ? existing.lastRequestedBy() : existing.stagedBy());
        return new DuplicatePayload(
                candidate.metadataId(),
                candidate.dataId(),
                candidate.lot(),
                candidate.wafer(),
                candidate.filename(),
                existing.status(),
                existing.processedAt(),
                stagedBy,
                existing.createdAt(),
                lastRequestedBy,
                existing.lastRequestedAt(),
                requiresConfirmation
        );
    }

    /**
     * Find an existing staged payload matching the metadata/data for the given site/sender.
     * Returns a DuplicatePayload summarizing the existing row if found, otherwise null.
     * This is a read-only helper for preview flows.
     */
    public DuplicatePayload findDuplicatePayload(String site, Integer senderId, String metadataId, String dataId) {
        if (metadataId == null || dataId == null) return null;
        String table = properties.getStagingTable();
        PayloadCandidate candidate = new PayloadCandidate(metadataId, dataId, null, null, null, null);
        try (Connection connection = dataSource.getConnection()) {
            ExistingPayload existing = loadExistingPayload(connection, table, site, senderId == null ? -1 : senderId, candidate);
            if (existing == null) {
                return null;
            }
            // requiresConfirmation not relevant for preview; set false
            return toDuplicatePayload(candidate, existing, false);
        } catch (SQLException ex) {
            log.warn("Failed lookup duplicate payload for preview {}:{} - {}", metadataId, dataId, ex.getMessage());
            return null;
        }
    }

    /**
     * Bulk variant used by preview endpoints to fetch duplicate metadata in one query.
     * Accepts up to ~1000 rows and chunks into manageable statements to avoid per-row round trips.
     * Optimized to run batches in parallel.
     */
    public java.util.Map<String, DuplicatePayload> findDuplicatePayloads(String site,
                                                                         Integer senderId,
                                                                         java.util.Collection<PayloadCandidate> candidates) {
        java.util.Map<String, DuplicatePayload> result = new java.util.concurrent.ConcurrentHashMap<>();
        if (site == null || candidates == null || candidates.isEmpty()) {
            return result;
        }
        String table = properties.getStagingTable();
        int resolvedSender = senderId == null ? -1 : senderId;

        java.util.LinkedHashMap<String, PayloadCandidate> unique = new java.util.LinkedHashMap<>();
        for (PayloadCandidate candidate : candidates) {
            if (candidate == null) continue;
            String metadata = candidate.metadataId();
            String data = candidate.dataId();
            if (metadata == null || data == null) continue;
            String trimmedMetadata = metadata.trim();
            String trimmedData = data.trim();
            if (trimmedMetadata.isEmpty() || trimmedData.isEmpty()) continue;
            unique.put(trimmedMetadata + "|" + trimmedData, new PayloadCandidate(trimmedMetadata, trimmedData, null, null, null, null));
        }

        if (unique.isEmpty()) {
            return result;
        }

        java.util.List<PayloadCandidate> deduped = new java.util.ArrayList<>(unique.values());
        final int batchSize = 200; // keep SQL manageable and under driver parameter limits

        // Split into batches
        List<List<PayloadCandidate>> batches = new ArrayList<>();
        for (int start = 0; start < deduped.size(); start += batchSize) {
            int end = Math.min(deduped.size(), start + batchSize);
            batches.add(deduped.subList(start, end));
        }

        // Process batches in parallel
        batches.parallelStream().forEach(batch -> {
            try (Connection connection = dataSource.getConnection()) {
                StringBuilder sql = new StringBuilder();
                sql.append("select id, metadata_id, data_id, status, processed_at, created_at, staged_by, last_requested_by, last_requested_at from (");
                sql.append("select id, metadata_id, data_id, status, processed_at, created_at, staged_by, last_requested_by, last_requested_at, row_number() over (partition by metadata_id, data_id order by id desc) rn ");
                sql.append("from ").append(table).append(" where site = ? and sender_id = ? and (");

                java.util.List<Object> params = new java.util.ArrayList<>();
                params.add(site);
                params.add(resolvedSender);
                boolean first = true;
                for (PayloadCandidate c : batch) {
                    if (!first) {
                        sql.append(" OR ");
                    }
                    sql.append("(metadata_id = ? AND data_id = ?)");
                    params.add(c.metadataId());
                    params.add(c.dataId());
                    first = false;
                }
                sql.append(")");
                sql.append(") where rn = 1");

                try (PreparedStatement ps = connection.prepareStatement(sql.toString())) {
                    int idx = 1;
                    for (Object param : params) {
                        if (param instanceof String value) {
                            ps.setString(idx++, value);
                        } else if (param instanceof Integer value) {
                            ps.setInt(idx++, value);
                        } else {
                            ps.setObject(idx++, param);
                        }
                    }

                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            PayloadCandidate candidate = new PayloadCandidate(
                                    rs.getString("metadata_id"),
                                    rs.getString("data_id"),
                                    null,
                                    null,
                                    null,
                                    null
                            );
                            ExistingPayload existing = new ExistingPayload(
                                    rs.getLong("id"),
                                    rs.getString("status"),
                                    toInstant(rs.getTimestamp("processed_at")),
                                    toInstant(rs.getTimestamp("created_at")),
                                    rs.getString("staged_by"),
                                    rs.getString("last_requested_by"),
                                    toInstant(rs.getTimestamp("last_requested_at"))
                            );
                            String key = candidate.metadataId() + "|" + candidate.dataId();
                            result.put(key, toDuplicatePayload(candidate, existing, false));
                        }
                    }
                }
            } catch (SQLException ex) {
                log.warn("Failed batch duplicate lookup for preview ({} items): {}", batch.size(), ex.getMessage());
            }
        });

        return result;
    }

    /**
     * Get the reason why a payload was sent to sandbox from the pp_log table.
     */
    public String getSandboxReason(String site, Integer senderId, String metadataId, String dataId, String lot, String wafer, String filename) {
        // Build the lot_wafer search string
        String lotWafer;
        if (wafer == null || wafer.isEmpty() || "0".equals(wafer) || "00".equals(wafer)) {
            lotWafer = lot + "_00";
        } else {
            try {
                int w = Integer.parseInt(wafer);
                lotWafer = String.format("%s_%02d", lot, w);
            } catch (NumberFormatException e) {
                lotWafer = lot + "_" + wafer;
            }
        }

        // Filename without extension
        String nameBase = filename;
        if (nameBase != null && nameBase.contains(".")) {
            nameBase = nameBase.substring(0, nameBase.lastIndexOf("."));
        }

        String sql = "SELECT log_message FROM refdb.pp_log WHERE lot = ? AND FILE_NAME = ? AND LOWER(log_message) LIKE '%sandbox%' ORDER BY PROCESS_DATETIME DESC NULLS LAST";

        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, lotWafer);
            ps.setString(2, nameBase);

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String logMessage = rs.getString("log_message");
                    if (logMessage != null) {
                        // Context is separated by ---
                        String[] parts = logMessage.split("---");
                        for (String part : parts) {
                            if (part.toLowerCase().contains("sandbox")) {
                                return part.trim();
                            }
                        }
                        return logMessage.trim();
                    }
                }
            }
        } catch (SQLException ex) {
            log.warn("Failed retrieving sandbox reason for {}: {}", lotWafer, ex.getMessage());
        }
        return null;
    }

    private String normalizeUser(String value) {
        if (value == null) {
            return DEFAULT_USER;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return DEFAULT_USER;
        }
        if (trimmed.length() > USER_MAX_LENGTH) {
            return trimmed.substring(0, USER_MAX_LENGTH);
        }
        return trimmed;
    }

    private String normalizeSenderName(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() > 256 ? trimmed.substring(0, 256) : trimmed;
    }

    private String displayUser(String value) {
        if (value == null) {
            return UNKNOWN_USER;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? UNKNOWN_USER : trimmed;
    }

    private record ExistingPayload(Long id,
                                   String status,
                                   Instant processedAt,
                                   Instant createdAt,
                                   String stagedBy,
                                   String lastRequestedBy,
                                   Instant lastRequestedAt) {}

    public record LotWaferAggregate(String lot,
                                    String wafer,
                                    String filename,
                                    long total,
                                    long ready,
                                    long enqueued,
                                    long failed,
                                    long completed) {
        /** @deprecated Use {@link #ready()} */
        @Deprecated public long stagedToRefdb() { return ready; }
        /** @deprecated Use {@link #enqueued()} */
        @Deprecated public long queuedForCp() { return enqueued; }
        /** @deprecated Use {@link #failed()} */
        @Deprecated public long cpFailed() { return failed; }
        /** @deprecated Use {@link #completed()} */
        @Deprecated public long completedOld() { return completed; }
    }

    public record TimeBucketAggregate(Instant bucket,
                                      long total,
                                      long ready,
                                      long enqueued,
                                      long failed,
                                      long completed) {
        /** @deprecated Use {@link #ready()} */
        @Deprecated public long stagedToRefdb() { return ready; }
        /** @deprecated Use {@link #enqueued()} */
        @Deprecated public long queuedForCp() { return enqueued; }
        /** @deprecated Use {@link #failed()} */
        @Deprecated public long cpFailed() { return failed; }
        /** @deprecated Use {@link #completed()} */
        @Deprecated public long completedOld() { return completed; }
    }

    private record StageStatusKey(String site, int senderId) {}

    private boolean isDuplicate(SQLException ex) {
        return ex.getErrorCode() == 1 ||
                (ex.getMessage() != null && ex.getMessage().toUpperCase().contains("UNIQUE"));
    }

    private boolean isAutoRetryStatus(ExistingPayload existing) {
        if (existing == null || existing.status() == null) {
            return false;
        }
        String status = existing.status().trim();
        // STAGED, QUEUED_FOR_CP, ELASTICSEARCH_MONITORING, EXENSIO_MONITORING: still in-flight
        // CP_FAILED, LOAD_FAILED, CANCELLED: terminal states that are always safe to retry
        return "NEW".equalsIgnoreCase(status) || "STAGED".equalsIgnoreCase(status)
                || "QUEUED_FOR_CP".equalsIgnoreCase(status)
                || "ELASTICSEARCH_MONITORING".equalsIgnoreCase(status)
                || "EXENSIO_MONITORING".equalsIgnoreCase(status)
                || "PROCESSING".equalsIgnoreCase(status) // legacy compat
                || "CP_FAILED".equalsIgnoreCase(status)
                || "LOAD_FAILED".equalsIgnoreCase(status)
                || "CANCELLED".equalsIgnoreCase(status)
                || "ERROR".equalsIgnoreCase(status);
    }

    private boolean isOutsideDuplicateRetryCooldown(ExistingPayload existing, Duration cooldown) {
        if (existing == null) {
            return false;
        }
        // Zero cooldown means always allow retry
        if (cooldown.isZero() || cooldown.isNegative()) {
            return true;
        }
        Instant reference = existing.lastRequestedAt() != null ? existing.lastRequestedAt() : existing.createdAt();
        if (reference == null) {
            return true;
        }
        return reference.isBefore(Instant.now().minus(cooldown));
    }

    private Instant toInstant(java.sql.Timestamp timestamp) {
        if (timestamp == null) {
            return null;
        }
        // Oracle stores TIMESTAMP columns without timezone info using the DB server's local time.
        // Use Timestamp.toInstant() which correctly uses the millisecond epoch value from the JDBC driver,
        // provided the JDBC connection timezone matches the DB server timezone (configured via
        // refdb.connection-timezone in application.yml, defaulting to UTC for backward compatibility).
        return timestamp.toInstant();
    }

    private java.sql.Timestamp safeTimestamp(ResultSet rs, String column) {
        try {
            return rs.getTimestamp(column);
        } catch (SQLException ex) {
            return null;
        }
    }

    private String safeString(ResultSet rs, String column) {
        try {
            return rs.getString(column);
        } catch (SQLException ex) {
            return null;
        }
    }

    private Long safeLong(ResultSet rs, String column) {
        try {
            long val = rs.getLong(column);
            return rs.wasNull() ? null : val;
        } catch (SQLException ex) {
            return null;
        }
    }

    private String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() > 4000 ? message.substring(0, 4000) : message;
    }

    // H2 vs Oracle SQL fragments
    private String nextIdExpr(String table) {
        String sequence = table + "_SEQ";
        return isOracle ? sequence + ".NEXTVAL" : "NEXT VALUE FOR " + sequence;
    }

    private String timestampExpr() {
        return isOracle ? "SYSTIMESTAMP" : "CURRENT_TIMESTAMP";
    }

    private String coalesce(String expr, String alt) {
        // Oracle uses NVL, H2 supports COALESCE
        return isOracle ? ("NVL(" + expr + ", " + alt + ")") : ("COALESCE(" + expr + ", " + alt + ")");
    }

    /**
     * Get lot-level statistics for a specific lot in a session.
     * Returns total, completed, and failed counts for the lot.
     */
    public Map<String, Integer> getLotStatistics(String requestId, String lot) {
        Map<String, Integer> stats = new HashMap<>();
        stats.put("total", 0);
        stats.put("completed", 0);
        stats.put("failed", 0);

        if (requestId == null || requestId.isBlank() || lot == null || lot.isBlank()) {
            return stats;
        }

        String table = properties.getStagingTable();
        String sql = "SELECT " +
                "COUNT(*) AS total, " +
                "SUM(CASE WHEN status = 'COMPLETED' THEN 1 ELSE 0 END) AS completed, " +
                "SUM(CASE WHEN status IN ('CP_FAILED','LOAD_FAILED') THEN 1 ELSE 0 END) AS failed " +
                "FROM " + table + " " +
                "WHERE request_id = ? AND lot = ?";

        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, requestId);
            ps.setString(2, lot);

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    stats.put("total", rs.getInt("total"));
                    stats.put("completed", rs.getInt("completed"));
                    stats.put("failed", rs.getInt("failed"));
                }
            }
        } catch (SQLException ex) {
            log.warn("Failed to get lot statistics for session {} lot {}: {}", requestId, lot, ex.getMessage());
        }

        return stats;
    }

    /**
     * Aggregate staged records by end_time bucket (day/week/month) per sender.
     * Used by the Data Coverage report — cross-session, grouped by data end-time.
     *
     * @param site          required
     * @param senderId      optional filter
     * @param granularity   "day" | "week" | "month"  (default: day)
     * @param endTimeFrom   optional ISO date string lower bound on end_time
     * @param endTimeTo     optional ISO date string upper bound on end_time
     */
    public List<com.onsemi.cim.apps.exensio.exensioreload.dto.CoveragePoint> getCoverage(
            String site,
            Integer senderId,
            String granularity,
            String endTimeFrom,
            String endTimeTo,
            List<String> devices) {

        String table = properties.getStagingTable();

        // Build the bucket truncation expression (Oracle TRUNC vs H2 FORMATDATETIME)
        String bucketExpr;
        if (isOracle) {
            String fmt = "MONTH".equalsIgnoreCase(granularity) ? "'MONTH'"
                    : "WEEK".equalsIgnoreCase(granularity) ? "'IW'"
                    : "'DD'";
            bucketExpr = "TO_CHAR(TRUNC(end_time, " + fmt + "), 'YYYY-MM-DD')";
        } else {
            String fmt = "MONTH".equalsIgnoreCase(granularity) ? "'yyyy-MM-01'"
                    : "WEEK".equalsIgnoreCase(granularity) ? "'yyyy-ww'"
                    : "'yyyy-MM-dd'";
            bucketExpr = "FORMATDATETIME(end_time, " + fmt + ")";
        }

        StringBuilder sql = new StringBuilder(
                "SELECT " + bucketExpr + " AS bucket, sender_id, site, " +
                        "COUNT(*) AS total, " +
                        "SUM(CASE WHEN status = 'COMPLETED' THEN 1 ELSE 0 END) AS done, " +
                        "SUM(CASE WHEN status IN ('QUEUED_FOR_CP','ELASTICSEARCH_MONITORING','EXENSIO_MONITORING') THEN 1 ELSE 0 END) AS enqueued, " +
                        "SUM(CASE WHEN status IN ('STAGED','READY') THEN 1 ELSE 0 END) AS staged, " +
                        "SUM(CASE WHEN status IN ('CP_FAILED','LOAD_FAILED') THEN 1 ELSE 0 END) AS failed " +
                        "FROM " + table + " WHERE site = ? AND end_time IS NOT NULL");

        List<Object> params = new ArrayList<>();
        params.add(site);

        if (senderId != null && senderId > 0) {
            sql.append(" AND sender_id = ?");
            params.add(senderId);
        }
        if (endTimeFrom != null && !endTimeFrom.isBlank()) {
            sql.append(" AND end_time >= ?");
            params.add(java.sql.Timestamp.valueOf(endTimeFrom.trim().length() == 10
                    ? endTimeFrom.trim() + " 00:00:00"
                    : endTimeFrom.trim().replace('T', ' ').replace('Z', ' ').trim()));
        }
        if (endTimeTo != null && !endTimeTo.isBlank()) {
            sql.append(" AND end_time <= ?");
            params.add(java.sql.Timestamp.valueOf(endTimeTo.trim().length() == 10
                    ? endTimeTo.trim() + " 23:59:59"
                    : endTimeTo.trim().replace('T', ' ').replace('Z', ' ').trim()));
        }
        if (devices != null && !devices.isEmpty()) {
            sql.append(" AND device IN (");
            for (int i = 0; i < devices.size(); i++) {
                if (i > 0) sql.append(",");
                sql.append("?");
            }
            sql.append(")");
            params.addAll(devices);
        }

        sql.append(" GROUP BY " + bucketExpr + ", sender_id, site ORDER BY bucket, sender_id");

        List<com.onsemi.cim.apps.exensio.exensioreload.dto.CoveragePoint> results = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                Object v = params.get(i);
                if (v instanceof Integer iv) ps.setInt(i + 1, iv);
                else if (v instanceof java.sql.Timestamp ts) ps.setTimestamp(i + 1, ts);
                else ps.setString(i + 1, v == null ? null : v.toString());
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(new com.onsemi.cim.apps.exensio.exensioreload.dto.CoveragePoint(
                            rs.getString("bucket"),
                            rs.getInt("sender_id"),
                            rs.getString("site"),
                            rs.getLong("total"),
                            rs.getLong("done"),
                            rs.getLong("enqueued"),
                            rs.getLong("staged"),
                            rs.getLong("failed")
                    ));
                }
            }
        } catch (SQLException ex) {
            log.error("getCoverage query failed: {}", ex.getMessage(), ex);
        }
        return results;
    }

    /**
     * Batch update records with Exensio results.
     * Groups updates by type and executes in batches of 100.
     *
     * <p>This method processes a list of batch results, extracts all record updates,
     * groups them by update type (DONE, FAILED, NOT_FOUND), and executes batch
     * database updates for each type. Updates are committed in batches of 100
     * records to manage transaction size and memory usage.</p>
     *
     * <p>SSE events are broadcast for each updated record to maintain compatibility
     * with existing SSE clients.</p>
     *
     * @param batchResults list of batch results containing record updates
     * @return total number of records updated
     *
     * Requirements: 5.2, 5.3, 7.7
     */
    public int batchUpdateFromExensio(List<BatchResult> batchResults) {
        if (batchResults == null || batchResults.isEmpty()) {
            return 0;
        }

        Instant startTime = Instant.now();
        int totalUpdated = 0;

        // Collect all updates from all batch results
        List<BatchResult.RecordUpdate> allUpdates = new ArrayList<>();
        for (BatchResult result : batchResults) {
            allUpdates.addAll(result.updates());
        }

        if (allUpdates.isEmpty()) {
            return 0;
        }

        // Group updates by type
        Map<BatchResult.UpdateType, List<BatchResult.RecordUpdate>> grouped =
                allUpdates.stream().collect(Collectors.groupingBy(BatchResult.RecordUpdate::type));

        // Process COMPLETED updates
        List<BatchResult.RecordUpdate> doneUpdates = grouped.get(BatchResult.UpdateType.COMPLETED);
        if (doneUpdates != null && !doneUpdates.isEmpty()) {
            int doneCount = batchMarkCompleted(doneUpdates);
            totalUpdated += doneCount;
            log.debug("Batch marked COMPLETED: {} records", doneCount);
        }

        // Process LOAD_FAILED updates
        List<BatchResult.RecordUpdate> failedUpdates = grouped.get(BatchResult.UpdateType.LOAD_FAILED);
        if (failedUpdates != null && !failedUpdates.isEmpty()) {
            int failedCount = batchMarkLoadFailed(failedUpdates);
            totalUpdated += failedCount;
            log.debug("Batch marked LOAD_FAILED: {} records", failedCount);
        }

        // Process NOT_FOUND updates
        List<BatchResult.RecordUpdate> notFoundUpdates = grouped.get(BatchResult.UpdateType.NOT_FOUND);
        if (notFoundUpdates != null && !notFoundUpdates.isEmpty()) {
            int notFoundCount = batchMarkNotFound(notFoundUpdates);
            totalUpdated += notFoundCount;
            log.debug("Batch marked NOT_FOUND: {} records", notFoundCount);
        }

        // Process ERROR updates
        List<BatchResult.RecordUpdate> errorUpdates = grouped.get(BatchResult.UpdateType.ERROR);
        if (errorUpdates != null && !errorUpdates.isEmpty()) {
            int errorCount = batchMarkError(errorUpdates);
            totalUpdated += errorCount;
            log.debug("Batch marked ERROR: {} records", errorCount);
        }

        // Process COMPLETED_MANUAL_VERIFICATION_REQUIRED updates
        // Requirements: 2.1, 2.2, 2.3 — wafer not found in Exensio after configured timeout
        List<BatchResult.RecordUpdate> manualVerificationUpdates = grouped.get(BatchResult.UpdateType.COMPLETED_MANUAL_VERIFICATION_REQUIRED);
        if (manualVerificationUpdates != null && !manualVerificationUpdates.isEmpty()) {
            int mvCount = batchMarkCompletedManualVerification(manualVerificationUpdates);
            totalUpdated += mvCount;
            log.debug("Batch marked COMPLETED_MANUAL_VERIFICATION_REQUIRED: {} records", mvCount);
        }

        // Process CP_TIMEOUT updates
        List<BatchResult.RecordUpdate> cpTimeoutUpdates = grouped.get(BatchResult.UpdateType.CP_TIMEOUT);
        if (cpTimeoutUpdates != null && !cpTimeoutUpdates.isEmpty()) {
            int cpTimeoutCount = batchMarkCpTimeout(cpTimeoutUpdates);
            totalUpdated += cpTimeoutCount;
            log.debug("Batch marked CP_TIMEOUT: {} records", cpTimeoutCount);
        }

        long elapsedMs = Duration.between(startTime, Instant.now()).toMillis();
        log.info("Batch update completed: {} records updated in {}ms", totalUpdated, elapsedMs);

        return totalUpdated;
    }

    /**
     * Batch mark records as DONE (successfully loaded into Exensio).
     *
     * <p>Executes batch UPDATE statements with commits every 100 records
     * to manage transaction size and memory usage.</p>
     *
     * @param updates list of record updates to mark as DONE
     * @return number of records successfully updated
     *
     * Requirements: 5.2, 5.3, 7.7
     */
    private int batchMarkCompleted(List<BatchResult.RecordUpdate> updates) {
        if (updates == null || updates.isEmpty()) {
            return 0;
        }

        String table = properties.getStagingTable();
        String sql = "UPDATE " + table +
                " SET status = 'COMPLETED', exensio_wafer_key = ?, exensio_pg_key = ?," +
                " processed_at = " + timestampExpr() + ", updated_at = " + timestampExpr() +
                " WHERE id = ?";

        int totalUpdated = 0;
        int batchCount = 0;

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);

            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                for (BatchResult.RecordUpdate update : updates) {
                    if (update.waferKey() != null) {
                        ps.setLong(1, update.waferKey());
                    } else {
                        ps.setNull(1, java.sql.Types.NUMERIC);
                    }
                    ps.setLong(2, update.pgKey());
                    ps.setLong(3, update.recordId());
                    ps.addBatch();
                    batchCount++;

                    if (batchCount % 100 == 0) {
                        int[] counts = ps.executeBatch();
                        connection.commit();
                        totalUpdated += counts.length;
                        log.debug("Committed DONE batch: {} records", counts.length);
                    }
                }

                // Commit remaining records
                if (batchCount % 100 != 0) {
                    int[] counts = ps.executeBatch();
                    connection.commit();
                    totalUpdated += counts.length;
                    log.debug("Committed final DONE batch: {} records", counts.length);
                }
            }

            // Broadcast SSE events for updated records
            broadcastBatchEvents(updates, "COMPLETED", "Loaded into Exensio");

        } catch (SQLException ex) {
            log.error("Failed batch marking DONE: {}", ex.getMessage(), ex);
            // Retry failed records individually
            totalUpdated += retryIndividualDoneUpdates(updates);
        }

        return totalUpdated;
    }

    /**
     * Batch mark records as FAILED.
     *
     * <p>Executes batch UPDATE statements with commits every 100 records.</p>
     *
     * @param updates list of record updates to mark as FAILED
     * @return number of records successfully updated
     *
     * Requirements: 5.2, 5.3, 7.7
     */
    private int batchMarkLoadFailed(List<BatchResult.RecordUpdate> updates) {
        if (updates == null || updates.isEmpty()) {
            return 0;
        }

        String table = properties.getStagingTable();
        String sql = "UPDATE " + table +
                " SET status = 'LOAD_FAILED', error_message = ?," +
                " processed_at = " + timestampExpr() + ", updated_at = " + timestampExpr() +
                " WHERE id = ?";

        int totalUpdated = 0;
        int batchCount = 0;

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);

            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                for (BatchResult.RecordUpdate update : updates) {
                    String errorMsg = update.errorMessage() != null ?
                            truncate(update.errorMessage()) : "Batch processing failed";
                    ps.setString(1, errorMsg);
                    ps.setLong(2, update.recordId());
                    ps.addBatch();
                    batchCount++;

                    if (batchCount % 100 == 0) {
                        int[] counts = ps.executeBatch();
                        connection.commit();
                        totalUpdated += counts.length;
                        log.debug("Committed FAILED batch: {} records", counts.length);
                    }
                }

                // Commit remaining records
                if (batchCount % 100 != 0) {
                    int[] counts = ps.executeBatch();
                    connection.commit();
                    totalUpdated += counts.length;
                    log.debug("Committed final FAILED batch: {} records", counts.length);
                }
            }

            // Broadcast SSE events for updated records
            broadcastBatchEvents(updates, "LOAD_FAILED", "Batch processing failed");

        } catch (SQLException ex) {
            log.error("Failed batch marking FAILED: {}", ex.getMessage(), ex);
            // Retry failed records individually
            totalUpdated += retryIndividualFailedUpdates(updates);
        }

        return totalUpdated;
    }

    /**
     * Batch mark records as NOT_FOUND (not found in Exensio).
     *
     * <p>Executes batch UPDATE statements with commits every 100 records.</p>
     *
     * @param updates list of record updates to mark as NOT_FOUND
     * @return number of records successfully updated
     *
     * Requirements: 5.2, 5.3, 7.7
     */
    private int batchMarkNotFound(List<BatchResult.RecordUpdate> updates) {
        if (updates == null || updates.isEmpty()) {
            return 0;
        }

        String table = properties.getStagingTable();
        String sql = "UPDATE " + table +
                " SET status = 'LOAD_FAILED', error_message = ?," +
                " processed_at = " + timestampExpr() + ", updated_at = " + timestampExpr() +
                " WHERE id = ?";

        int totalUpdated = 0;
        int batchCount = 0;

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);

            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                for (BatchResult.RecordUpdate update : updates) {
                    ps.setString(1, "Wafer not found in Exensio");
                    ps.setLong(2, update.recordId());
                    ps.addBatch();
                    batchCount++;

                    if (batchCount % 100 == 0) {
                        int[] counts = ps.executeBatch();
                        connection.commit();
                        totalUpdated += counts.length;
                        log.debug("Committed NOT_FOUND batch: {} records", counts.length);
                    }
                }

                // Commit remaining records
                if (batchCount % 100 != 0) {
                    int[] counts = ps.executeBatch();
                    connection.commit();
                    totalUpdated += counts.length;
                    log.debug("Committed final NOT_FOUND batch: {} records", counts.length);
                }
            }

            // Broadcast SSE events for updated records
            broadcastBatchEvents(updates, "LOAD_FAILED", "Wafer not found in Exensio");

        } catch (SQLException ex) {
            log.error("Failed batch marking NOT_FOUND: {}", ex.getMessage(), ex);
            // Retry failed records individually
            totalUpdated += retryIndividualNotFoundUpdates(updates);
        }

        return totalUpdated;
    }

    /**
     * Batch mark records as ERROR (processing error).
     *
     * <p>Executes batch UPDATE statements with commits every 100 records.</p>
     *
     * @param updates list of record updates to mark as ERROR
     * @return number of records successfully updated
     *
     * Requirements: 5.2, 5.3, 7.7
     */
    private int batchMarkError(List<BatchResult.RecordUpdate> updates) {
        if (updates == null || updates.isEmpty()) {
            return 0;
        }

        String table = properties.getStagingTable();
        String sql = "UPDATE " + table +
                " SET status = 'LOAD_FAILED', error_message = ?," +
                " processed_at = " + timestampExpr() + ", updated_at = " + timestampExpr() +
                " WHERE id = ?";

        int totalUpdated = 0;
        int batchCount = 0;

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);

            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                for (BatchResult.RecordUpdate update : updates) {
                    String errorMsg = update.errorMessage() != null ?
                            truncate(update.errorMessage()) : "Batch processing error";
                    ps.setString(1, errorMsg);
                    ps.setLong(2, update.recordId());
                    ps.addBatch();
                    batchCount++;

                    if (batchCount % 100 == 0) {
                        int[] counts = ps.executeBatch();
                        connection.commit();
                        totalUpdated += counts.length;
                        log.debug("Committed ERROR batch: {} records", counts.length);
                    }
                }

                // Commit remaining records
                if (batchCount % 100 != 0) {
                    int[] counts = ps.executeBatch();
                    connection.commit();
                    totalUpdated += counts.length;
                    log.debug("Committed final ERROR batch: {} records", counts.length);
                }
            }

            // Broadcast SSE events for updated records
            broadcastBatchEvents(updates, "LOAD_FAILED", "Batch processing error");

        } catch (SQLException ex) {
            log.error("Failed batch marking ERROR: {}", ex.getMessage(), ex);
            // Retry failed records individually
            totalUpdated += retryIndividualErrorUpdates(updates);
        }

        return totalUpdated;
    }

    /**
     * Batch mark records as EXENSIO_TIMEOUT.
     * Wafer not found in Exensio after the configured monitoring timeout.
     * This is NOT a failure — the record may have loaded but was not detected.
     * Operators should manually verify in Exensio before taking corrective action.
     *
     * Requirements: 2.1, 2.2, 2.3
     */
    private int batchMarkCompletedManualVerification(List<BatchResult.RecordUpdate> updates) {
        if (updates == null || updates.isEmpty()) {
            return 0;
        }

        String table = properties.getStagingTable();
        String sql = "UPDATE " + table +
                " SET status = 'COMPLETED_MANUAL_VERIFICATION_REQUIRED', error_message = ?," +
                " processed_at = " + timestampExpr() + ", updated_at = " + timestampExpr() +
                " WHERE id = ?";

        int totalUpdated = 0;
        int batchCount = 0;

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);

            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                for (BatchResult.RecordUpdate update : updates) {
                    String errorMsg = update.errorMessage() != null ?
                            truncate(update.errorMessage()) :
                            "[Exensio Timeout] Wafer not found after " +
                            exensioProperties.getTimeoutMinutes() +
                            " minutes. May need manual verification or retry.";
                    ps.setString(1, errorMsg);
                    ps.setLong(2, update.recordId());
                    ps.addBatch();
                    batchCount++;

                    if (batchCount % 100 == 0) {
                        int[] counts = ps.executeBatch();
                        connection.commit();
                        totalUpdated += counts.length;
                        log.debug("Committed EXENSIO_TIMEOUT batch: {} records", counts.length);
                    }
                }

                if (batchCount % 100 != 0) {
                    int[] counts = ps.executeBatch();
                    connection.commit();
                    totalUpdated += counts.length;
                    log.debug("Committed final EXENSIO_TIMEOUT batch: {} records", counts.length);
                }
            }

            broadcastBatchEvents(updates, "COMPLETED_MANUAL_VERIFICATION_REQUIRED", "Exensio monitoring timeout — verify manually");

        } catch (SQLException ex) {
            log.error("Failed batch marking EXENSIO_TIMEOUT: {}", ex.getMessage(), ex);
        }

        return totalUpdated;
    }

    /**
     * Batch mark records as ENRICHMENT_TIMEOUT.
     * No enrichment log found in ES/pp_log within the monitoring window.
     * This is NOT a failure — enrichment may have occurred but was not detected.
     * Operators should manually verify before taking corrective action.
     *
     * Requirements: 1.1, 1.2, 1.3
     */
    private int batchMarkCpTimeout(List<BatchResult.RecordUpdate> updates) {
        if (updates == null || updates.isEmpty()) {
            return 0;
        }

        String table = properties.getStagingTable();
        String sql = "UPDATE " + table +
                " SET status = 'CP_TIMEOUT', error_message = ?," +
                " processed_at = " + timestampExpr() + ", updated_at = " + timestampExpr() +
                " WHERE id = ?";

        int totalUpdated = 0;
        int batchCount = 0;

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);

            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                for (BatchResult.RecordUpdate update : updates) {
                    String errorMsg = update.errorMessage() != null ?
                            truncate(update.errorMessage()) :
                            "[Enrichment Timeout] No enrichment log found after " +
                            elasticsearchProperties.getEnrichmentTimeoutMinutes() +
                            " minutes. Needs manual verification or retry.";
                    ps.setString(1, errorMsg);
                    ps.setLong(2, update.recordId());
                    ps.addBatch();
                    batchCount++;

                    if (batchCount % 100 == 0) {
                        int[] counts = ps.executeBatch();
                        connection.commit();
                        totalUpdated += counts.length;
                        log.debug("Committed ENRICHMENT_TIMEOUT batch: {} records", counts.length);
                    }
                }

                if (batchCount % 100 != 0) {
                    int[] counts = ps.executeBatch();
                    connection.commit();
                    totalUpdated += counts.length;
                    log.debug("Committed final ENRICHMENT_TIMEOUT batch: {} records", counts.length);
                }
            }

            broadcastBatchEvents(updates, "CP_TIMEOUT", "Enrichment monitoring timeout — Exensio not configured");

        } catch (SQLException ex) {
            log.error("Failed batch marking ENRICHMENT_TIMEOUT: {}", ex.getMessage(), ex);
        }

        return totalUpdated;
    }

    /**
     * Retry individual DONE updates when batch update fails.
     *
     * @param updates list of updates to retry
     * @return number of records successfully updated
     */
    private int retryIndividualDoneUpdates(List<BatchResult.RecordUpdate> updates) {
        if (updates == null || updates.isEmpty()) {
            return 0;
        }

        String table = properties.getStagingTable();
        String sql = "UPDATE " + table +
                " SET status = 'COMPLETED', exensio_wafer_key = ?, exensio_pg_key = ?," +
                " processed_at = " + timestampExpr() + ", updated_at = " + timestampExpr() +
                " WHERE id = ?";

        int successCount = 0;

        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {

            for (BatchResult.RecordUpdate update : updates) {
                try {
                    if (update.waferKey() != null) {
                        ps.setLong(1, update.waferKey());
                    } else {
                        ps.setNull(1, java.sql.Types.NUMERIC);
                    }
                    ps.setLong(2, update.pgKey());
                    ps.setLong(3, update.recordId());
                    ps.executeUpdate();
                    successCount++;

                    // Broadcast SSE event for this record
                    if (monitorService != null) {
                        Map<String, Object> evt = new HashMap<>();
                        evt.put("id", update.recordId());
                        evt.put("status", "COMPLETED");
                        evt.put("msg", "Loaded into Exensio (retry)");
                        evt.put("exensioWaferKey", update.waferKey());
                        evt.put("exensioPgKey", update.pgKey());
                        monitorService.sendEvent(null, "ROW_UPDATE", evt);
                    }
                } catch (SQLException ex) {
                    log.warn("Failed individual DONE retry for record {}: {}",
                            update.recordId(), ex.getMessage());
                }
            }

        } catch (SQLException ex) {
            log.error("Failed retrying individual DONE updates: {}", ex.getMessage(), ex);
        }

        return successCount;
    }

    /**
     * Retry individual FAILED updates when batch update fails.
     *
     * @param updates list of updates to retry
     * @return number of records successfully updated
     */
    private int retryIndividualFailedUpdates(List<BatchResult.RecordUpdate> updates) {
        if (updates == null || updates.isEmpty()) {
            return 0;
        }

        String table = properties.getStagingTable();
        String sql = "UPDATE " + table +
                " SET status = 'LOAD_FAILED', error_message = ?," +
                " processed_at = " + timestampExpr() + ", updated_at = " + timestampExpr() +
                " WHERE id = ?";

        int successCount = 0;

        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {

            for (BatchResult.RecordUpdate update : updates) {
                try {
                    String errorMsg = update.errorMessage() != null ?
                            truncate(update.errorMessage()) : "Batch processing failed";
                    ps.setString(1, errorMsg);
                    ps.setLong(2, update.recordId());
                    ps.executeUpdate();
                    successCount++;

                    // Broadcast SSE event for this record
                    if (monitorService != null) {
                        Map<String, Object> evt = new HashMap<>();
                        evt.put("id", update.recordId());
                        evt.put("status", "LOAD_FAILED");
                        evt.put("msg", "Batch processing failed (retry)");
                        monitorService.sendEvent(null, "ROW_UPDATE", evt);
                    }
                } catch (SQLException ex) {
                    log.warn("Failed individual FAILED retry for record {}: {}",
                            update.recordId(), ex.getMessage());
                }
            }

        } catch (SQLException ex) {
            log.error("Failed retrying individual FAILED updates: {}", ex.getMessage(), ex);
        }

        return successCount;
    }

    /**
     * Retry individual NOT_FOUND updates when batch update fails.
     *
     * @param updates list of updates to retry
     * @return number of records successfully updated
     */
    private int retryIndividualNotFoundUpdates(List<BatchResult.RecordUpdate> updates) {
        if (updates == null || updates.isEmpty()) {
            return 0;
        }

        String table = properties.getStagingTable();
        String sql = "UPDATE " + table +
                " SET status = 'LOAD_FAILED', error_message = ?," +
                " processed_at = " + timestampExpr() + ", updated_at = " + timestampExpr() +
                " WHERE id = ?";

        int successCount = 0;

        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {

            for (BatchResult.RecordUpdate update : updates) {
                try {
                    ps.setString(1, "Wafer not found in Exensio");
                    ps.setLong(2, update.recordId());
                    ps.executeUpdate();
                    successCount++;

                    // Broadcast SSE event for this record
                    if (monitorService != null) {
                        Map<String, Object> evt = new HashMap<>();
                        evt.put("id", update.recordId());
                        evt.put("status", "LOAD_FAILED");
                        evt.put("msg", "Wafer not found in Exensio (retry)");
                        monitorService.sendEvent(null, "ROW_UPDATE", evt);
                    }
                } catch (SQLException ex) {
                    log.warn("Failed individual NOT_FOUND retry for record {}: {}",
                            update.recordId(), ex.getMessage());
                }
            }

        } catch (SQLException ex) {
            log.error("Failed retrying individual NOT_FOUND updates: {}", ex.getMessage(), ex);
        }

        return successCount;
    }

    /**
     * Retry individual ERROR updates when batch update fails.
     *
     * @param updates list of updates to retry
     * @return number of records successfully updated
     */
    private int retryIndividualErrorUpdates(List<BatchResult.RecordUpdate> updates) {
        if (updates == null || updates.isEmpty()) {
            return 0;
        }

        String table = properties.getStagingTable();
        String sql = "UPDATE " + table +
                " SET status = 'LOAD_FAILED', error_message = ?," +
                " processed_at = " + timestampExpr() + ", updated_at = " + timestampExpr() +
                " WHERE id = ?";

        int successCount = 0;

        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {

            for (BatchResult.RecordUpdate update : updates) {
                try {
                    String errorMsg = update.errorMessage() != null ?
                            truncate(update.errorMessage()) : "Batch processing error";
                    ps.setString(1, errorMsg);
                    ps.setLong(2, update.recordId());
                    ps.executeUpdate();
                    successCount++;

                    // Broadcast SSE event for this record
                    if (monitorService != null) {
                        Map<String, Object> evt = new HashMap<>();
                        evt.put("id", update.recordId());
                        evt.put("status", "LOAD_FAILED");
                        evt.put("msg", "Batch processing error (retry)");
                        monitorService.sendEvent(null, "ROW_UPDATE", evt);
                    }
                } catch (SQLException ex) {
                    log.warn("Failed individual ERROR retry for record {}: {}",
                            update.recordId(), ex.getMessage());
                }
            }

        } catch (SQLException ex) {
            log.error("Failed retrying individual ERROR updates: {}", ex.getMessage(), ex);
        }

        return successCount;
    }

    // -------------------------------------------------------------------------
    // pp_log fallback queries (Requirements 6.1, 6.2)
    // -------------------------------------------------------------------------

    /**
     * Result row from a pp_log lookup, combining success and error paths into a
     * single query so the caller can inspect {@code process_code} directly.
     *
     * @param outputDirectory populated when process_code = 0
     * @param logMessage      populated when process_code != 0
     * @param processCode     0 = success, non-zero = error
     */
    public record PpLogRow(String outputDirectory, String logMessage, int processCode) {}

    /**
     * Looks up the most recent pp_log entry for a given lot since a point in time.
     *
     * @param lot       the lot identifier from {@link StageRecord#lot()}
     * @param updatedAt the {@link StageRecord#updatedAt()} timestamp — only rows
     *                  with {@code process_datetime >= updatedAt} are considered
     * @return a populated {@link PpLogRow} if a matching row exists, or {@code null}
     */
    public PpLogRow queryPpLog(String lot, java.time.Instant updatedAt) {
        long start = System.currentTimeMillis();
        PpLogRow result = null;
        String sql = "SELECT output_directory, log_message, process_code FROM pp_log " +
                "WHERE lot = ? AND process_datetime >= ? " +
                "ORDER BY process_datetime DESC FETCH FIRST 1 ROWS ONLY";
        log.info("pp_log query using PRD refdb ({}): lot={} since={}",
                ppLogDataSource.getJdbcUrl(), lot, updatedAt);
        try (Connection connection = ppLogDataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, lot);
            ps.setTimestamp(2, java.sql.Timestamp.from(updatedAt));
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    result = new PpLogRow(
                        rs.getString("output_directory"),
                        rs.getString("log_message"),
                        rs.getInt("process_code")
                    );
                }
            }
        } catch (SQLException ex) {
            log.warn("pp_log query failed for lot={} updatedAt={}: {}", lot, updatedAt, ex.getMessage());
        }
        long elapsed = System.currentTimeMillis() - start;
        log.debug("pp_log query for lot={} updatedAt={} completed in {}ms (found={})",
                lot, updatedAt, elapsed, result != null);
        return result;
    }

    /**
     * Broadcast SSE events for a batch of record updates.
     *
     * @param updates list of record updates
     * @param status the status to set
     * @param msg the message to broadcast
     */
    private void broadcastBatchEvents(List<BatchResult.RecordUpdate> updates, String status, String msg) {
        if (monitorService == null) {
            return;
        }

        for (BatchResult.RecordUpdate update : updates) {
            try {
                Map<String, Object> evt = new HashMap<>();
                evt.put("id", update.recordId());
                evt.put("status", status);
                evt.put("msg", msg);

                if (status.equals("COMPLETED") && update.waferKey() != null && update.pgKey() != null) {
                    evt.put("exensioWaferKey", update.waferKey());
                    evt.put("exensioPgKey", update.pgKey());
                }

                monitorService.sendEvent(null, "ROW_UPDATE", evt);
            } catch (Exception ex) {
                log.warn("Failed broadcasting SSE event for record {}: {}",
                        update.recordId(), ex.getMessage());
            }
        }
    }

    /**
     * Helper method to record a state change to the aggregation batcher.
     * Fetches current state count for the given state and records it to the batcher.
     * This enables batched SSE STATE_AGGREGATION events instead of per-record ROW_UPDATE events.
     *
     * @param requestId the session/request ID
     * @param state the state that changed (ENRICHMENT, EXENSIO_LOADING, DONE, FAILED, CANCELLED, etc.)
     */
    private void recordStateChangeForBatcher(String requestId, String state) {
        if (requestId == null || requestId.isBlank() || stateAggregationBatcher == null) {
            return;
        }

        try {
            // Query current count for this state
            String table = properties.getStagingTable();
            String sql = "SELECT COUNT(*) FROM " + table + " WHERE status = ? AND request_id = ?";
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, state);
                ps.setString(2, requestId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        long currentCount = rs.getLong(1);
                        // Record to batcher — it will handle aggregation and batching
                        stateAggregationBatcher.recordStateChange(requestId, state, -1, currentCount);
                    }
                }
            }
        } catch (SQLException ex) {
            log.warn("Failed recording state change to batcher for state {}: {}", state, ex.getMessage());
        }
    }
}
