package com.onsemi.cim.apps.exensio.exensioreload.service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.onsemi.cim.apps.exensio.exensioreload.config.RefDbProperties;
import com.onsemi.cim.apps.exensio.exensioreload.dto.StateAccountingReport;
import com.onsemi.cim.apps.exensio.exensioreload.stage.StageStatus;

/**
 * Service for verifying state accounting and detecting discrepancies in SENDER_STAGE records.
 */
@Service
public class StateAccountingService {
    private static final Logger log = LoggerFactory.getLogger(StateAccountingService.class);

    private final DataSource dataSource;
    private final RefDbProperties refDbProperties;
    private final RefDbService refDbService;

    public StateAccountingService(DataSource dataSource, RefDbProperties refDbProperties, RefDbService refDbService) {
        this.dataSource = dataSource;
        this.refDbProperties = refDbProperties;
        this.refDbService = refDbService;
    }

    /**
     * Generate complete state accounting report.
     * Queries database for all 8 state counts and compares against dashboard aggregation.
     *
     * @param requestId optional request filter
     * @param site optional site filter
     * @param senderId optional sender_id filter
     * @return comprehensive accounting report
     */
    public StateAccountingReport generateReport(String requestId, String site, Integer senderId) {
        Instant timestamp = Instant.now();

        // Query database state distribution
        DatabaseStateData dbData = queryDatabaseStateCounts(requestId, site, senderId);

        // Query dashboard aggregation from RefDbService
        List<StageStatus> statuses = refDbService.fetchStatusesFor(site, senderId, requestId);

        // Build dashboard card counts
        StateAccountingReport.DashboardCardCounts dashboardCards = buildDashboardCounts(statuses);

        // Verify data integrity
        StateAccountingReport.DataIntegrity integrity = verifyDataIntegrity(dbData);

        // Build sender breakdown
        List<StateAccountingReport.SenderStateBreakdown> bySender = buildSenderBreakdown(dbData);

        return new StateAccountingReport(
                timestamp,
                dbData.getReport(),
                dashboardCards,
                integrity,
                bySender
        );
    }

    /**
     * Query database for all 9 state counts (including 2 new timeout states) and verify accounting.
     */
    private DatabaseStateData queryDatabaseStateCounts(String requestId, String site, Integer senderId) {
        String table = refDbProperties.getStagingTable();
        String baseQuery = "SELECT site, sender_id, MAX(sender_name) AS sender_name, COUNT(*) AS total, " +
                "SUM(CASE WHEN status = 'STAGED' THEN 1 ELSE 0 END) AS pending, " +
                "SUM(CASE WHEN status = 'QUEUED_FOR_CP' THEN 1 ELSE 0 END) AS enqueued, " +
                "SUM(CASE WHEN status = 'ELASTICSEARCH_MONITORING' THEN 1 ELSE 0 END) AS enrichment, " +
                "SUM(CASE WHEN status = 'CP_TIMEOUT' THEN 1 ELSE 0 END) AS enrichment_timeout, " +
                "SUM(CASE WHEN status = 'EXENSIO_MONITORING' THEN 1 ELSE 0 END) AS exensio_loading, " +
                "SUM(CASE WHEN status = 'COMPLETED_MANUAL_VERIFICATION_REQUIRED' THEN 1 ELSE 0 END) AS exensio_timeout, " +
                "SUM(CASE WHEN status IN ('CP_FAILED','LOAD_FAILED') THEN 1 ELSE 0 END) AS failed, " +
                "SUM(CASE WHEN status = 'COMPLETED' THEN 1 ELSE 0 END) AS done, " +
                "SUM(CASE WHEN status = 'CANCELLED' THEN 1 ELSE 0 END) AS cancelled, " +
                "SUM(CASE WHEN status IS NULL THEN 1 ELSE 0 END) AS null_status " +
                "FROM " + table + " WHERE 1=1 ";

        List<Object> params = new ArrayList<>();
        StringBuilder whereClause = new StringBuilder(baseQuery);

        if (site != null && !site.isBlank()) {
            whereClause.append(" AND site = ?");
            params.add(site);
        }
        if (senderId != null) {
            whereClause.append(" AND sender_id = ?");
            params.add(senderId);
        }
        if (requestId != null && !requestId.isBlank()) {
            whereClause.append(" AND request_id = ?");
            params.add(requestId);
        }

        whereClause.append(" GROUP BY site, sender_id");

        Map<String, Long> globalStates = new HashMap<>();
        long totalCount = 0;
        long sumOfStates = 0;
        List<StateAccountingReport.SenderStateBreakdown> senderBreakdown = new ArrayList<>();

        try (Connection conn = dataSource.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(whereClause.toString())) {
                for (int i = 0; i < params.size(); i++) {
                    ps.setObject(i + 1, params.get(i));
                }

                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String siteVal = rs.getString("site");
                        int senderIdVal = rs.getInt("sender_id");
                        String senderName = rs.getString("sender_name");

                        long pending = rs.getLong("pending");
                        long enqueued = rs.getLong("enqueued");
                        long enrichment = rs.getLong("enrichment");
                        long enrichmentTimeout = rs.getLong("enrichment_timeout");
                        long exensioLoading = rs.getLong("exensio_loading");
                        long exensioTimeout = rs.getLong("exensio_timeout");
                        long failed = rs.getLong("failed");
                        long done = rs.getLong("done");
                        long cancelled = rs.getLong("cancelled");
                        long nullStatus = rs.getLong("null_status");
                        long recordTotal = rs.getLong("total");

                        // Accumulate global state counts
                        globalStates.put("STAGED", globalStates.getOrDefault("STAGED", 0L) + pending);
                        globalStates.put("QUEUED_FOR_CP", globalStates.getOrDefault("QUEUED_FOR_CP", 0L) + enqueued);
                        globalStates.put("ELASTICSEARCH_MONITORING", globalStates.getOrDefault("ELASTICSEARCH_MONITORING", 0L) + enrichment);
                        globalStates.put("CP_TIMEOUT", globalStates.getOrDefault("CP_TIMEOUT", 0L) + enrichmentTimeout);
                        globalStates.put("EXENSIO_MONITORING", globalStates.getOrDefault("EXENSIO_MONITORING", 0L) + exensioLoading);
                        globalStates.put("COMPLETED_MANUAL_VERIFICATION_REQUIRED", globalStates.getOrDefault("COMPLETED_MANUAL_VERIFICATION_REQUIRED", 0L) + exensioTimeout);
                        globalStates.put("CP_FAILED", globalStates.getOrDefault("CP_FAILED", 0L) + failed);
                        globalStates.put("COMPLETED", globalStates.getOrDefault("COMPLETED", 0L) + done);
                        globalStates.put("CANCELLED", globalStates.getOrDefault("CANCELLED", 0L) + cancelled);
                        globalStates.put("NULL_STATUS", globalStates.getOrDefault("NULL_STATUS", 0L) + nullStatus);

                        totalCount += recordTotal;
                        sumOfStates += (pending + enqueued + enrichment + enrichmentTimeout + exensioLoading + exensioTimeout + failed + done + cancelled + nullStatus);

                        // Build sender breakdown
                        Map<String, Long> senderStates = new HashMap<>();
                        senderStates.put("STAGED", pending);
                        senderStates.put("QUEUED_FOR_CP", enqueued);
                        senderStates.put("ELASTICSEARCH_MONITORING", enrichment);
                        senderStates.put("CP_TIMEOUT", enrichmentTimeout);
                        senderStates.put("EXENSIO_MONITORING", exensioLoading);
                        senderStates.put("COMPLETED_MANUAL_VERIFICATION_REQUIRED", exensioTimeout);
                        senderStates.put("CP_FAILED", failed);
                        senderStates.put("COMPLETED", done);
                        senderStates.put("CANCELLED", cancelled);
                        senderStates.put("NULL_STATUS", nullStatus);

                        senderBreakdown.add(new StateAccountingReport.SenderStateBreakdown(
                                siteVal, senderIdVal, senderName, recordTotal, senderStates
                        ));
                    }
                }
            }
        } catch (SQLException ex) {
            log.error("Failed querying state counts", ex);
            throw new IllegalStateException("Failed querying database state counts", ex);
        }

        // Build discrepancies
        List<StateAccountingReport.Discrepancy> discrepancies = new ArrayList<>();
        if (totalCount != sumOfStates) {
            discrepancies.add(new StateAccountingReport.Discrepancy(
                    "ACCOUNTING_IMBALANCE",
                    "Total records: " + totalCount + ", Sum of states: " + sumOfStates
            ));
        }

        StateAccountingReport.DatabaseStateCounts dbCounts = new StateAccountingReport.DatabaseStateCounts(
                totalCount,
                globalStates,
                sumOfStates,
                discrepancies
        );

        return new DatabaseStateData(dbCounts, senderBreakdown);
    }

    /**
     * Build dashboard card counts from StageStatus records.
     * Includes new timeout states in the aggregation.
     */
    private StateAccountingReport.DashboardCardCounts buildDashboardCounts(List<StageStatus> statuses) {
        long staged = 0;
        long queued = 0;
        long enriching = 0;
        long enrichmentTimeout = 0;
        long exensioLoading = 0;
        long exensioTimeout = 0;
        long failed = 0;
        long completed = 0;
        long cancelled = 0;

        for (StageStatus status : statuses) {
            staged += status.stagedToRefdb();
            queued += status.queuedForCp();
            enriching += status.elasticsearchMonitoring();
            enrichmentTimeout += status.cpTimeout();
            exensioLoading += status.exensioMonitoring();
            exensioTimeout += status.completedManualVerification();
            failed += status.totalFailed();
            completed += status.completed();
            cancelled += status.cancelled();
        }

        long sum = staged + queued + enriching + enrichmentTimeout + exensioLoading + exensioTimeout + failed + completed + cancelled;

        return new StateAccountingReport.DashboardCardCounts(
                staged, queued, enriching, enrichmentTimeout, exensioLoading, exensioTimeout, 
                failed, completed, cancelled, sum
        );
    }

    /**
     * Verify data integrity and return warnings/errors.
     * Checks for NULL statuses, accounting imbalance, and high backlog of uncertain states including timeout states.
     * 
     * Validation includes:
     * - Accounting balance: sum of all states (including ENRICHMENT_TIMEOUT and EXENSIO_TIMEOUT) equals total
     * - NULL status records are flagged as errors
     * - High counts of timeout records are flagged as warnings
     * - High counts of active processing states are flagged as warnings
     */
    private StateAccountingReport.DataIntegrity verifyDataIntegrity(DatabaseStateData dbData) {
        List<String> warnings = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        boolean valid = true;

        StateAccountingReport.DatabaseStateCounts dbCounts = dbData.getReport();

        // Check for NULL status records
        long nullStatus = dbCounts.getStates().getOrDefault("NULL_STATUS", 0L);
        if (nullStatus > 0) {
            errors.add("Found " + nullStatus + " records with NULL status");
            valid = false;
        }

        // Check for accounting imbalance
        if (!dbCounts.getDiscrepancies().isEmpty()) {
            for (StateAccountingReport.Discrepancy disc : dbCounts.getDiscrepancies()) {
                errors.add(disc.getType() + ": " + disc.getDescription());
            }
            valid = false;
        }

        // Check for orphaned cancelled records in external queue
        long cancelledCount = dbCounts.getStates().getOrDefault("CANCELLED", 0L);
        if (cancelledCount > 0) {
            warnings.add("Found " + cancelledCount + " CANCELLED records. Verify they are not in external queue.");
        }

        // Get counts for all states including timeout states
        long enrichment = dbCounts.getStates().getOrDefault("ELASTICSEARCH_MONITORING", 0L);
        long enrichmentTimeout = dbCounts.getEnrichmentTimeout();  // Use explicit field
        long exensioLoading = dbCounts.getStates().getOrDefault("EXENSIO_MONITORING", 0L);
        long exensioTimeout = dbCounts.getExensioTimeout();  // Use explicit field
        
        // Check for records stuck in active processing states
        if (enrichment > 100 || exensioLoading > 50) {
            warnings.add("High count of ENRICHMENT (" + enrichment + ") or EXENSIO_LOADING (" + exensioLoading
                    + "). Verify timeout detection is running.");
        }
        
        // Check for high counts of timeout records
        if (enrichmentTimeout > 1000 || exensioTimeout > 1000) {
            warnings.add("High count of timeout records: ENRICHMENT_TIMEOUT (" + enrichmentTimeout 
                    + "), EXENSIO_TIMEOUT (" + exensioTimeout + "). Consider reviewing timeout configuration.");
        }

        // Check for accounting balance explicitly including timeout states
        long expectedAccountingSum = enrichment + enrichmentTimeout + exensioLoading + exensioTimeout
                + dbCounts.getStates().getOrDefault("STAGED", 0L)
                + dbCounts.getStates().getOrDefault("QUEUED_FOR_CP", 0L)
                + dbCounts.getStates().getOrDefault("CP_FAILED", 0L)
                + dbCounts.getStates().getOrDefault("COMPLETED", 0L)
                + dbCounts.getStates().getOrDefault("CANCELLED", 0L);
        
        if (dbCounts.getSumOfStates() != expectedAccountingSum && dbCounts.getSumOfStates() != dbCounts.getTotalCount()) {
            // Already reported via discrepancies, but add explicit validation note
            warnings.add("Timeout states are included in accounting validation to ensure: "
                    + "STAGED + QUEUED_FOR_CP + ELASTICSEARCH_MONITORING + CP_TIMEOUT + EXENSIO_MONITORING + COMPLETED_MANUAL_VERIFICATION_REQUIRED "
                    + "+ CP_FAILED + COMPLETED + CANCELLED = Total record count");
        }

        return new StateAccountingReport.DataIntegrity(valid, warnings, errors);
    }

    /**
     * Build sender-level state breakdown.
     */
    private List<StateAccountingReport.SenderStateBreakdown> buildSenderBreakdown(DatabaseStateData dbData) {
        return dbData.getSenderBreakdown();
    }

    /**
     * Internal class for holding database query results.
     */
    private static class DatabaseStateData {
        private final StateAccountingReport.DatabaseStateCounts report;
        private final List<StateAccountingReport.SenderStateBreakdown> senderBreakdown;

        DatabaseStateData(StateAccountingReport.DatabaseStateCounts report,
                         List<StateAccountingReport.SenderStateBreakdown> senderBreakdown) {
            this.report = report;
            this.senderBreakdown = senderBreakdown;
        }

        StateAccountingReport.DatabaseStateCounts getReport() {
            return report;
        }

        List<StateAccountingReport.SenderStateBreakdown> getSenderBreakdown() {
            return senderBreakdown;
        }
    }
}
