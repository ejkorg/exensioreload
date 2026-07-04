package com.onsemi.cim.apps.exensio.exensioreload.service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.onsemi.cim.apps.exensio.exensioreload.config.CpElasticsearchProperties;
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

    public DataIntegrityJob(DataSource dataSource,
                           RefDbProperties refDbProperties,
                           CpElasticsearchProperties elasticsearchProperties,
                           RefDbService refDbService,
                           AuditService auditService) {
        this.dataSource = dataSource;
        this.refDbProperties = refDbProperties;
        this.elasticsearchProperties = elasticsearchProperties;
        this.refDbService = refDbService;
        this.auditService = auditService;
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
                "WHERE status NOT IN ('pending', 'ENQUEUED', 'ENRICHMENT', 'EXENSIO_LOADING', 'PROCESSING', 'FAILED', 'DONE', 'CANCELLED') " +
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
        String sql = "SELECT ss.id, ss.site, ss.sender_id, ss.sender_name, ss.metadata_id, ss.data_id, " +
                "ss.lot, ss.wafer, ss.filename, ss.status, ss.created_at, ss.updated_at, ss.request_id " +
                "FROM " + table + " ss " +
                "WHERE ss.status = 'CANCELLED' " +
                "AND EXISTS ( " +
                "  SELECT 1 FROM DTP_SENDER_QUEUE_ITEM q WHERE q.record_id = ss.id " +
                ") " +
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
            log.error("Failed querying orphaned cancelled records", ex);
        }
        return records;
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
                "lot, wafer, filename, status, created_at, updated_at, request_id " +
                "FROM " + table + " " +
                "WHERE (status = 'ENRICHMENT' OR status = 'EXENSIO_LOADING') " +
                "AND DATEDIFF(MINUTE, updated_at, GETDATE()) > ? " +
                "FETCH FIRST 100 ROWS ONLY";

        List<StageRecord> stuckRecords = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, enrichmentTimeoutMinutes);
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
                long minutesStuck = calculateMinutesStuck(stuck.updatedAt());
                String remediationMsg = String.format(
                    "Auto-remediated by DataIntegrityJob after %d minutes in %s state",
                    minutesStuck,
                    stuck.status()
                );
                refDbService.markDoneManualVerify(stuck, remediationMsg);
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
                "SUM(CASE WHEN status = 'pending' THEN 1 ELSE 0 END) AS pending, " +
                "SUM(CASE WHEN status = 'ENQUEUED' THEN 1 ELSE 0 END) AS enqueued, " +
                "SUM(CASE WHEN status = 'ENRICHMENT' THEN 1 ELSE 0 END) AS enrichment, " +
                "SUM(CASE WHEN status = 'EXENSIO_LOADING' THEN 1 ELSE 0 END) AS exensio_loading, " +
                "SUM(CASE WHEN status = 'PROCESSING' THEN 1 ELSE 0 END) AS processing, " +
                "SUM(CASE WHEN status = 'FAILED' THEN 1 ELSE 0 END) AS failed, " +
                "SUM(CASE WHEN status = 'DONE' THEN 1 ELSE 0 END) AS done, " +
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
                    long exensioLoading = rs.getLong("exensio_loading");
                    long processing = rs.getLong("processing");
                    long failed = rs.getLong("failed");
                    long done = rs.getLong("done");
                    long cancelled = rs.getLong("cancelled");
                    long nullStatus = rs.getLong("null_status");

                    long summedStates = pending + enqueued + enrichment + exensioLoading + 
                                      processing + failed + done + cancelled + nullStatus;

                    log.info("Accounting check: total={}, summed={}, pending={}, enqueued={}, enrichment={}, " +
                             "exensioLoading={}, processing={}, failed={}, done={}, cancelled={}, nullStatus={}",
                             total, summedStates, pending, enqueued, enrichment, exensioLoading,
                             processing, failed, done, cancelled, nullStatus);

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
        Timestamp createdAtTs = rs.getTimestamp("created_at");
        if (createdAtTs != null) {
            createdAt = createdAtTs.toInstant();
        }
        Timestamp updatedAtTs = rs.getTimestamp("updated_at");
        if (updatedAtTs != null) {
            updatedAt = updatedAtTs.toInstant();
        }
        String requestId = rs.getString("request_id");

        return new StageRecord(
            id, site, senderId, senderName, metadataId, dataId, lot, wafer, filename,
            null,  // end_time
            status, null,  // error_message
            createdAt, updatedAt, null,  // processed_at
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
                auditService.logAdminAction("DATA_INTEGRITY_ALERT", message, null);
            } catch (Exception ex) {
                log.error("Failed logging admin alert to audit service", ex);
            }
        }
    }
}
