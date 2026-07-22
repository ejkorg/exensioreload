package com.onsemi.cim.apps.exensio.exensioreload.service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.onsemi.cim.apps.exensio.exensioreload.config.CpElasticsearchProperties;
import com.onsemi.cim.apps.exensio.exensioreload.config.ExternalDbConfig;
import com.onsemi.cim.apps.exensio.exensioreload.config.RefDbProperties;
import com.onsemi.cim.apps.exensio.exensioreload.stage.StageRecord;

/**
 * Scheduled job for verifying data integrity of SENDER_STAGE records.
 * Runs hourly by default to:
 * 1. Detect invalid status values (not in valid set)
 * 2. Find NULL status records
 * 3. Find CANCELLED records still in external queue (orphaned)
 * 4. Auto-remediate stuck ENRICHMENT/EXENSIO_LOADING records exceeding timeout
 * 5. Verify accounting balance (sum of states = total)
 *
 * Requirements: 8
 */
@Service
public class DataIntegrityJob {
    private static final Logger log = LoggerFactory.getLogger(DataIntegrityJob.class);

    private final DataSource dataSource;
    private final RefDbProperties refDbProperties;
    private final CpElasticsearchProperties elasticsearchProperties;
    private final RefDbService refDbService;
    private final AuditService auditService;
    private final ExternalDbConfig externalDbConfig;

    public DataIntegrityJob(DataSource dataSource,
                           RefDbProperties refDbProperties,
                           CpElasticsearchProperties elasticsearchProperties,
                           RefDbService refDbService,
                           AuditService auditService,
                           @Autowired(required = false) ExternalDbConfig externalDbConfig) {
        this.dataSource = dataSource;
        this.refDbProperties = refDbProperties;
        this.elasticsearchProperties = elasticsearchProperties;
        this.refDbService = refDbService;
        this.auditService = auditService;
        this.externalDbConfig = externalDbConfig;
    }

    /**
     * Run data integrity verification hourly.
     * Scheduled to run at 0 minutes of every hour by default.
     */
    @Scheduled(cron = "0 0 * * * *")  // Every hour at minute 0
    public void verifyDataIntegrity() {
        try {
            log.info("=== Data Integrity Job Started ===");
            Instant jobStart = Instant.now();

            // 1. Check for invalid status values
            List<StageRecord> invalidRecords = findInvalidStatusRecords();
            if (!invalidRecords.isEmpty()) {
                log.error("Found {} records with invalid status values", invalidRecords.size());
                logDataQualityIssue("INVALID_STATUS", invalidRecords.size(), invalidRecords);
                alertAdmin("Data Integrity: " + invalidRecords.size() + " records with invalid status values");
            }

            // 2. Check for NULL status
            List<StageRecord> nullStatusRecords = findNullStatusRecords();
            if (!nullStatusRecords.isEmpty()) {
                log.error("Found {} records with NULL status", nullStatusRecords.size());
                logDataQualityIssue("NULL_STATUS", nullStatusRecords.size(), nullStatusRecords);
                alertAdmin("Data Integrity: " + nullStatusRecords.size() + " records with NULL status");
            }

            // 3. Check for CANCELLED records in external queue (orphaned)
            List<StageRecord> orphanedCancelled = findOrphanedCancelledRecords();
            if (!orphanedCancelled.isEmpty()) {
                log.warn("Found {} CANCELLED records still in external queue", orphanedCancelled.size());
                logDataQualityIssue("ORPHANED_CANCELLED", orphanedCancelled.size(), orphanedCancelled);
            }

            // 4. Detect and auto-remediate stuck enrichment records
            int remediatedCount = detectAndRemediateStuckRecords();
            if (remediatedCount > 0) {
                log.info("Auto-remediated {} stuck enrichment records", remediatedCount);
                alertAdmin("Data Integrity: Auto-remediated " + remediatedCount + " stuck enrichment records");
            }

            // 5. Verify accounting balance
            verifyAccountingBalance();

            Instant jobEnd = Instant.now();
            long durationMs = java.time.Duration.between(jobStart, jobEnd).toMillis();
            log.info("=== Data Integrity Job Completed ({}ms) ===", durationMs);
        } catch (Exception ex) {
            log.error("Data integrity job failed", ex);
            alertAdmin("Data Integrity Job Failed: " + ex.getMessage());
        }
    }

    /**
     * Find records with status values NOT in the valid set.
     * Valid states: pending, ENQUEUED, ENRICHMENT, EXENSIO_LOADING, PROCESSING, FAILED, DONE, CANCELLED
     */
    private List<StageRecord> findInvalidStatusRecords() {
        String table = refDbProperties.getStagingTable();
        String sql = "SELECT id, site, sender_id, sender_name, metadata_id, data_id, lot, wafer, filename, " +
                "status, created_at, updated_at, request_id " +
                "FROM " + table + " " +
                "WHERE status NOT IN ('STAGED', 'QUEUED_FOR_CP', 'ELASTICSEARCH_MONITORING', " +
                "'CP_TIMEOUT', 'EXENSIO_MONITORING', 'COMPLETED_MANUAL_VERIFICATION_REQUIRED', " +
                "'PROCESSING', 'CP_FAILED', 'LOAD_FAILED', 'COMPLETED', 'CANCELLED') " +
                "FETCH FIRST 100 ROWS ONLY";

        List<StageRecord> records = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    records.add(mapRecordFromResultSet(rs));
                }
            }
        } catch (SQLException ex) {
            log.error("Failed querying invalid status records", ex);
        }
        return records;
    }

    /**
     * Find records with NULL status.
     */
    private List<StageRecord> findNullStatusRecords() {
        String table = refDbProperties.getStagingTable();
        String sql = "SELECT id, site, sender_id, sender_name, metadata_id, data_id, lot, wafer, filename, " +
                "status, created_at, updated_at, request_id " +
                "FROM " + table + " " +
                "WHERE status IS NULL " +
                "FETCH FIRST 100 ROWS ONLY";

        List<StageRecord> records = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    records.add(mapRecordFromResultSet(rs));
                }
            }
        } catch (SQLException ex) {
            log.error("Failed querying NULL status records", ex);
        }
        return records;
    }

    /**
     * Find CANCELLED records that are still referenced in DTP_SENDER_QUEUE_ITEM (external queue).
     * These are orphaned — they should not be in the queue if they're cancelled.
     */
    private List<StageRecord> findOrphanedCancelledRecords() {
        String table = refDbProperties.getStagingTable();
        String sql = "SELECT id, site, sender_id, sender_name, metadata_id, data_id, lot, wafer, filename, " +
                "status, created_at, updated_at, request_id " +
                "FROM " + table + " " +
                "WHERE status = 'CANCELLED' " +
                "FETCH FIRST 100 ROWS ONLY";

        List<StageRecord> cancelledRecords = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    cancelledRecords.add(mapRecordFromResultSet(rs));
                }
            }
        } catch (SQLException ex) {
            log.error("Failed querying CANCELLED status records", ex);
            return Collections.emptyList();
        }

        if (cancelledRecords.isEmpty() || externalDbConfig == null) {
            return Collections.emptyList();
        }

        List<StageRecord> orphanedRecords = new ArrayList<>();
        // Group by site to check external DTP_SENDER_QUEUE_ITEM table per site DB
        Map<String, List<StageRecord>> bySite = new HashMap<>();
        for (StageRecord rec : cancelledRecords) {
            if (rec.site() != null && !rec.site().isBlank()) {
                bySite.computeIfAbsent(rec.site(), k -> new ArrayList<>()).add(rec);
            }
        }

        for (Map.Entry<String, List<StageRecord>> entry : bySite.entrySet()) {
            String site = entry.getKey();
            List<StageRecord> recordsForSite = entry.getValue();
            try (Connection extConn = externalDbConfig.getConnection(site)) {
                if (extConn == null) {
                    continue;
                }
                String checkSql = "SELECT COUNT(*) FROM DTP_SENDER_QUEUE_ITEM WHERE id_metadata = ? AND id_data = ? AND id_sender = ?";
                try (PreparedStatement checkPs = extConn.prepareStatement(checkSql)) {
                    for (StageRecord rec : recordsForSite) {
                        checkPs.setString(1, rec.metadataId());
                        checkPs.setString(2, rec.dataId());
                        checkPs.setInt(3, rec.senderId());
                        try (ResultSet checkRs = checkPs.executeQuery()) {
                            if (checkRs.next() && checkRs.getInt(1) > 0) {
                                orphanedRecords.add(rec);
                            }
                        }
                    }
                }
            } catch (SQLException ex) {
                log.warn("Failed checking external queue for site {}: {}", site, ex.getMessage());
            }
        }

        return orphanedRecords;
    }

    /**
     * Detect records stuck in ENRICHMENT or EXENSIO_LOADING exceeding timeout threshold.
     * Auto-remediate by marking them as DONE with manual-verify flag.
     * Returns count of records auto-remediated.
     */
    private int detectAndRemediateStuckRecords() {
        int enrichmentTimeoutMinutes = elasticsearchProperties.getEnrichmentTimeoutMinutes();
        String table = refDbProperties.getStagingTable();

        String sql = "SELECT id, site, sender_id, sender_name, metadata_id, data_id, " +
            "lot, wafer, filename, status, created_at, updated_at, enrichment_started_at, request_id " +
                "FROM " + table + " " +
            "WHERE (status = 'ELASTICSEARCH_MONITORING' OR status = 'EXENSIO_MONITORING') " +
            "AND COALESCE(enrichment_started_at, created_at) < ? " +
                "FETCH FIRST 100 ROWS ONLY";

        List<StageRecord> stuckRecords = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            java.time.Instant threshold = java.time.Instant.now().minus(enrichmentTimeoutMinutes, java.time.temporal.ChronoUnit.MINUTES);
            ps.setTimestamp(1, java.sql.Timestamp.from(threshold));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    stuckRecords.add(mapRecordFromResultSet(rs));
                }
            }
        } catch (SQLException ex) {
            log.error("Failed querying stuck enrichment records", ex);
            return 0;
        }

        // Auto-remediate each stuck record
        int remediatedCount = 0;
        for (StageRecord stuck : stuckRecords) {
            try {
                long minutesStuck = calculateMinutesStuck(stuck.enrichmentStartedAt() != null ? stuck.enrichmentStartedAt() : stuck.createdAt());
                String remediationMsg = String.format(
                    "Auto-remediated by DataIntegrityJob after %d minutes in %s state",
                    minutesStuck,
                    stuck.status()
                );
                refDbService.markCompletedManualVerify(stuck, remediationMsg);
                log.info("Auto-remediated record id={} (stuck for {} minutes)", stuck.id(), minutesStuck);
                remediatedCount++;
            } catch (Exception ex) {
                log.error("Failed auto-remediating record id={}", stuck.id(), ex);
            }
        }
        return remediatedCount;
    }

    /**
     * Verify accounting balance: sum of all states should equal total record count.
     */
    private void verifyAccountingBalance() {
        String table = refDbProperties.getStagingTable();
        String sql = "SELECT COUNT(*) AS total, " +
                "SUM(CASE WHEN status = 'STAGED' THEN 1 ELSE 0 END) AS pending, " +
                "SUM(CASE WHEN status = 'QUEUED_FOR_CP' THEN 1 ELSE 0 END) AS enqueued, " +
                "SUM(CASE WHEN status = 'ELASTICSEARCH_MONITORING' THEN 1 ELSE 0 END) AS enrichment, " +
                "SUM(CASE WHEN status = 'CP_TIMEOUT' THEN 1 ELSE 0 END) AS cp_timeout, " +
                "SUM(CASE WHEN status = 'EXENSIO_MONITORING' THEN 1 ELSE 0 END) AS exensio_loading, " +
                "SUM(CASE WHEN status = 'COMPLETED_MANUAL_VERIFICATION_REQUIRED' THEN 1 ELSE 0 END) AS manual_verification, " +
                "SUM(CASE WHEN status = 'PROCESSING' THEN 1 ELSE 0 END) AS processing, " +
                "SUM(CASE WHEN status IN ('CP_FAILED','LOAD_FAILED') THEN 1 ELSE 0 END) AS failed, " +
                "SUM(CASE WHEN status = 'COMPLETED' THEN 1 ELSE 0 END) AS done, " +
                "SUM(CASE WHEN status = 'CANCELLED' THEN 1 ELSE 0 END) AS cancelled, " +
                "SUM(CASE WHEN status IS NULL THEN 1 ELSE 0 END) AS null_status " +
                "FROM " + table;

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    long total = rs.getLong("total");
                    long pending = rs.getLong("pending");
                    long enqueued = rs.getLong("enqueued");
                    long enrichment = rs.getLong("enrichment");
                    long cpTimeout = rs.getLong("cp_timeout");
                    long exensioLoading = rs.getLong("exensio_loading");
                    long manualVerification = rs.getLong("manual_verification");
                    long processing = rs.getLong("processing");
                    long failed = rs.getLong("failed");
                    long done = rs.getLong("done");
                    long cancelled = rs.getLong("cancelled");
                    long nullStatus = rs.getLong("null_status");

                    long summedStates = pending + enqueued + enrichment + cpTimeout +
                                      exensioLoading + manualVerification +
                                      processing + failed + done + cancelled + nullStatus;

                    log.info("Accounting check: total={}, summed={}, pending={}, enqueued={}, enrichment={}, " +
                             "cpTimeout={}, exensioLoading={}, manualVerification={}, processing={}, failed={}, " +
                             "done={}, cancelled={}, nullStatus={}",
                             total, summedStates, pending, enqueued, enrichment, cpTimeout,
                             exensioLoading, manualVerification, processing, failed, done, cancelled, nullStatus);

                    if (total != summedStates) {
                        log.error("ACCOUNTING IMBALANCE: total {} != summed {}", total, summedStates);
                        alertAdmin(String.format(
                            "Accounting Imbalance: total=%d, summed=%d, diff=%d",
                            total, summedStates, (total - summedStates)
                        ));
                    }
                }
            }
        } catch (SQLException ex) {
            log.error("Failed verifying accounting balance", ex);
        }
    }

    /**
     * Calculate minutes between now and the given instant.
     */
    private long calculateMinutesStuck(Instant updatedAt) {
        if (updatedAt == null) {
            return 0;
        }
        return java.time.Duration.between(updatedAt, Instant.now()).toMinutes();
    }

    /**
     * Map a ResultSet row to a minimal StageRecord for logging purposes.
     */
    private StageRecord mapRecordFromResultSet(ResultSet rs) throws SQLException {
        long id = rs.getLong("id");
        String site = rs.getString("site");
        int senderId = rs.getInt("sender_id");
        String senderName = rs.getString("sender_name");
        String metadataId = rs.getString("metadata_id");
        String dataId = rs.getString("data_id");
        String lot = rs.getString("lot");
        String wafer = rs.getString("wafer");
        String filename = rs.getString("filename");
        String status = rs.getString("status");
        Instant createdAt = null;
        Instant updatedAt = null;
        Instant enrichmentStartedAt = null;
        Timestamp createdAtTs = rs.getTimestamp("created_at");
        if (createdAtTs != null) {
            createdAt = createdAtTs.toInstant();
        }
        Timestamp updatedAtTs = rs.getTimestamp("updated_at");
        if (updatedAtTs != null) {
            updatedAt = updatedAtTs.toInstant();
        }
        Timestamp enrichmentStartedAtTs = null;
        try {
            enrichmentStartedAtTs = rs.getTimestamp("enrichment_started_at");
        } catch (SQLException ignore) {
            // Column may not exist on older schemas; leave null if absent
            enrichmentStartedAtTs = null;
        }
        if (enrichmentStartedAtTs != null) {
            enrichmentStartedAt = enrichmentStartedAtTs.toInstant();
        }
        String requestId = rs.getString("request_id");

        return new StageRecord(
            id, site, senderId, senderName, metadataId, dataId, lot, wafer, null, filename,
            null,  // end_time
            status, null,  // error_message
            createdAt, updatedAt, enrichmentStartedAt, null,  // enrichmentStartedAt, processedAt
            null, null, null,  // staged_by, last_requested_by, last_requested_at
            requestId, null,  // cp_output_path
            null, null, null, null, null  // cp_output_target, exensio_wafer_key, exensio_pg_key, data_type, test_phase
        );
    }

    /**
     * Log a data quality issue with the specified type, count, and sample records.
     */
    private void logDataQualityIssue(String issueType, int count, List<StageRecord> sampleRecords) {
        StringBuilder sb = new StringBuilder();
        sb.append("Data Integrity Issue: ").append(issueType).append(" (count=").append(count).append(")\n");
        sb.append("Sample records:\n");
        for (int i = 0; i < Math.min(5, sampleRecords.size()); i++) {
            StageRecord rec = sampleRecords.get(i);
            sb.append("  - id=").append(rec.id())
              .append(", status=").append(rec.status())
              .append(", site=").append(rec.site())
              .append(", lot=").append(rec.lot())
              .append(", wafer=").append(rec.wafer())
              .append("\n");
        }
        log.warn(sb.toString());
    }

    /**
     * Alert administrators of data integrity issues.
     * Currently logs; can be extended to send emails, Slack messages, etc.
     */
    private void alertAdmin(String message) {
        log.error("ADMIN ALERT: {}", message);
        // Future: Send email, Slack message, or other alert mechanism
        if (auditService != null) {
            try {
                // Log to audit service for admin tracking
                auditService.logAction(null, "DATA_INTEGRITY_ALERT", "SYSTEM", "data-integrity", 
                    java.util.Map.of("message", message));
            } catch (Exception ex) {
                log.error("Failed logging admin alert to audit service", ex);
            }
        }
    }
}
