package com.onsemi.cim.apps.exensio.exensioreload.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.onsemi.cim.apps.exensio.exensioreload.config.RefDbProperties;
import com.onsemi.cim.apps.exensio.exensioreload.dto.StateAccountingReport;
import com.onsemi.cim.apps.exensio.exensioreload.stage.StageStatus;

/**
 * Integration test for end-to-end accounting verification.
 * Tests accounting invariant: sum of all state counts = total records
 * Tests state transitions and cancelled record visibility.
 * Tests debug endpoint accuracy.
 *
 * Validates Requirements: 1, 2, 6, 7
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public class StateAccountingIntegrationTest {

    @Autowired
    private RefDbService refDbService;

    @Autowired
    private StateAccountingService stateAccountingService;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private RefDbProperties refDbProperties;

    private static final String TEST_SITE = "TEST_SITE_ACCT";
    private static final int TEST_SENDER_ID = 9999;
    private static final String TEST_SENDER_NAME = "TEST_SENDER_ACCT";
    private static final String TEST_REQUEST_ID = "TEST_REQ_" + System.nanoTime();

    @BeforeEach
    public void setup() throws SQLException {
        // Clean up test data before each test
        deleteTestData();
    }

    /**
     * Test 1: Stage N records, verify accounting sum = N
     * 
     * Validates:
     * - Accounting invariant holds for staged records
     * - StageStatus.accountingSum() equals total after staging
     * - Requirements 1, 2, 6
     */
    @Test
    public void testAccountingInvariantForStagedRecords() throws SQLException {
        // Stage 10 records
        int recordCount = 10;
        insertRecords(recordCount, "pending");

        // Query status
        StageStatus status = refDbService.fetchStatuses(TEST_REQUEST_ID)
                .stream()
                .filter(s -> s.site().equals(TEST_SITE))
                .findFirst()
                .orElse(null);

        assertNotNull(status, "Status should exist for test site");
        assertEquals(recordCount, status.total(), "Total should be " + recordCount);
        assertEquals(recordCount, status.ready(), "Ready should be " + recordCount + " (all pending)");
        
        // Verify accounting sum = total
        long accountingSum = status.accountingSum();
        assertEquals(status.total(), accountingSum,
                "Accounting sum (" + accountingSum + ") should equal total (" + status.total() + ")");
    }

    /**
     * Test 2: Bulk cancel M records, verify CANCELLED count increases and others unchanged
     * 
     * Validates:
     * - CANCELLED records are visible and countable
     * - Cancelling records does not affect other state counts
     * - Requirements 1, 6
     */
    @Test
    public void testBulkCancelIncreaseCancelledCount() throws SQLException {
        // Stage 20 records in ENRICHMENT
        int totalRecords = 20;
        int cancelCount = 7;
        insertRecords(totalRecords, "ENRICHMENT");

        // Get initial status
        StageStatus initialStatus = refDbService.fetchStatuses(TEST_REQUEST_ID)
                .stream()
                .filter(s -> s.site().equals(TEST_SITE))
                .findFirst()
                .orElse(null);

        assertNotNull(initialStatus);
        assertEquals(totalRecords, initialStatus.total());
        assertEquals(totalRecords, initialStatus.enriching());
        assertEquals(0, initialStatus.cancelled());

        // Cancel 7 records
        cancelRecords(cancelCount);

        // Get updated status
        StageStatus updatedStatus = refDbService.fetchStatuses(TEST_REQUEST_ID)
                .stream()
                .filter(s -> s.site().equals(TEST_SITE))
                .findFirst()
                .orElse(null);

        assertNotNull(updatedStatus);
        assertEquals(totalRecords, updatedStatus.total(), "Total should remain unchanged");
        assertEquals(totalRecords - cancelCount, updatedStatus.enriching(),
                "Enriching should decrease by " + cancelCount);
        assertEquals(cancelCount, updatedStatus.cancelled(),
                "Cancelled should increase by " + cancelCount);
        
        // Verify accounting still balances
        long accountingSum = updatedStatus.accountingSum();
        assertEquals(updatedStatus.total(), accountingSum,
                "Accounting sum should still equal total after cancellation");
    }

    /**
     * Test 3: Mark records DONE, verify transitions and totals
     * 
     * Validates:
     * - Records can transition from ENRICHMENT to DONE
     * - Accounting invariant maintained during transitions
     * - Completed count increases, enriching decreases
     * - Requirements 2, 6
     */
    @Test
    public void testTransitionEnrichmentToDone() throws SQLException {
        // Stage 15 records in ENRICHMENT
        int totalRecords = 15;
        int doneCount = 6;
        insertRecords(totalRecords, "ENRICHMENT");

        // Get initial status
        StageStatus initialStatus = refDbService.fetchStatuses(TEST_REQUEST_ID)
                .stream()
                .filter(s -> s.site().equals(TEST_SITE))
                .findFirst()
                .orElse(null);

        assertNotNull(initialStatus);
        assertEquals(totalRecords, initialStatus.enriching());
        assertEquals(0, initialStatus.completed());

        // Mark 6 records as DONE
        markRecordsDone(doneCount);

        // Get updated status
        StageStatus updatedStatus = refDbService.fetchStatuses(TEST_REQUEST_ID)
                .stream()
                .filter(s -> s.site().equals(TEST_SITE))
                .findFirst()
                .orElse(null);

        assertNotNull(updatedStatus);
        assertEquals(totalRecords, updatedStatus.total(), "Total should remain unchanged");
        assertEquals(totalRecords - doneCount, updatedStatus.enriching(),
                "Enriching should decrease by " + doneCount);
        assertEquals(doneCount, updatedStatus.completed(),
                "Completed should increase by " + doneCount);
        
        // Verify accounting balances
        long accountingSum = updatedStatus.accountingSum();
        assertEquals(updatedStatus.total(), accountingSum,
                "Accounting sum should equal total after transition to DONE");
    }

    /**
     * Test 4: Complex transitions maintaining accounting
     * 
     * Validates:
     * - Multiple simultaneous state transitions maintain accounting invariant
     * - Different state counts change appropriately
     * - Requirements 1, 2, 6, 7
     */
    @Test
    public void testComplexTransitionsMaintainAccounting() throws SQLException {
        // Stage records in different states
        insertRecords(5, "pending");     // 5 staged
        insertRecords(8, "ENQUEUED");    // 8 queued
        insertRecords(10, "ENRICHMENT"); // 10 enriching
        insertRecords(3, "FAILED");      // 3 failed

        int totalExpected = 5 + 8 + 10 + 3;

        // Get initial status
        StageStatus initialStatus = refDbService.fetchStatuses(TEST_REQUEST_ID)
                .stream()
                .filter(s -> s.site().equals(TEST_SITE))
                .findFirst()
                .orElse(null);

        assertNotNull(initialStatus);
        assertEquals(totalExpected, initialStatus.total());
        assertEquals(5, initialStatus.ready());
        assertEquals(8, initialStatus.queued());
        assertEquals(10, initialStatus.enriching());
        assertEquals(3, initialStatus.failed());
        assertEquals(totalExpected, initialStatus.accountingSum());

        // Perform multiple transitions:
        // - Move 2 pending -> ENQUEUED
        // - Move 4 ENRICHMENT -> DONE
        // - Move 3 ENQUEUED -> CANCELLED
        transitionRecords(2, "pending", "ENQUEUED");
        transitionRecords(4, "ENRICHMENT", "DONE");
        transitionRecords(3, "ENQUEUED", "CANCELLED");

        // Get updated status
        StageStatus updatedStatus = refDbService.fetchStatuses(TEST_REQUEST_ID)
                .stream()
                .filter(s -> s.site().equals(TEST_SITE))
                .findFirst()
                .orElse(null);

        assertNotNull(updatedStatus);
        assertEquals(totalExpected, updatedStatus.total(), "Total should remain unchanged");
        assertEquals(5 - 2, updatedStatus.ready(), "Staged should decrease by 2");
        assertEquals(8 - 3 + 2, updatedStatus.queued(), "Queued should be 8 - 3 + 2 = 7");
        assertEquals(10 - 4, updatedStatus.enriching(), "Enriching should decrease by 4");
        assertEquals(4, updatedStatus.completed(), "Completed should be 4");
        assertEquals(3, updatedStatus.cancelled(), "Cancelled should be 3");
        assertEquals(3, updatedStatus.failed(), "Failed should remain 3");
        
        // Verify accounting still balances
        long accountingSum = updatedStatus.accountingSum();
        assertEquals(updatedStatus.total(), accountingSum,
                "Accounting sum (" + accountingSum + ") should equal total (" + updatedStatus.total() + ")");
    }

    /**
     * Test 5: Debug endpoint matches dashboard totals
     * 
     * Validates:
     * - StateAccountingService generates accurate report
     * - Database state counts match dashboard card counts
     * - No discrepancies reported
     * - Requirements 2, 6
     */
    @Test
    public void testDebugEndpointMatchesDashboardTotals() throws SQLException {
        // Stage records in various states
        insertRecords(3, "pending");
        insertRecords(5, "ENQUEUED");
        insertRecords(7, "ENRICHMENT");
        insertRecords(2, "EXENSIO_LOADING");
        insertRecords(4, "DONE");
        insertRecords(1, "FAILED");
        insertRecords(6, "CANCELLED");

        int totalExpected = 3 + 5 + 7 + 2 + 4 + 1 + 6;

        // Get dashboard status
        List<StageStatus> statuses = refDbService.fetchStatusesFor(TEST_SITE, TEST_SENDER_ID, TEST_REQUEST_ID);
        StageStatus dashboardStatus = statuses.isEmpty() ? null : statuses.get(0);

        assertNotNull(dashboardStatus, "Dashboard status should exist");
        assertEquals(totalExpected, dashboardStatus.total());

        // Generate accounting report via debug endpoint
        StateAccountingReport report = stateAccountingService.generateReport(TEST_REQUEST_ID, TEST_SITE, TEST_SENDER_ID);

        assertNotNull(report);
        assertNotNull(report.getDatabase());
        assertNotNull(report.getDashboardCards());
        assertNotNull(report.getDataIntegrity());

        // Verify database totals
        long dbTotal = report.getDatabase().getTotalCount();
        assertEquals(totalExpected, dbTotal, "Database total should match expected");

        // Verify dashboard card totals match database
        StateAccountingReport.DashboardCardCounts cards = report.getDashboardCards();
        long cardSum = cards.getStaged() + cards.getQueued() + cards.getEnriching()
                + cards.getExensioLoading() + cards.getFailed() + cards.getCompleted()
                + cards.getCancelled();
        
        assertEquals(dbTotal, cardSum,
                "Dashboard card sum (" + cardSum + ") should equal database total (" + dbTotal + ")");

        // Verify no discrepancies
        assertTrue(report.getDatabase().getDiscrepancies().isEmpty(),
                "Should have no discrepancies");

        // Verify data integrity
        assertTrue(report.getDataIntegrity().isValid(),
                "Data integrity should be valid");
    }

    /**
     * Test 6: All recorded states count toward accounting sum
     * 
     * Validates:
     * - Every state (including EXENSIO_LOADING) is counted
     * - No state is hidden or uncounted
     * - Requirements 1, 3, 6
     */
    @Test
    public void testAllStatesCountedInAccounting() throws SQLException {
        // Insert one record in each valid state
        insertRecords(1, "pending");
        insertRecords(1, "ENQUEUED");
        insertRecords(1, "ENRICHMENT");
        insertRecords(1, "EXENSIO_LOADING");
        insertRecords(1, "PROCESSING");
        insertRecords(1, "DONE");
        insertRecords(1, "FAILED");
        insertRecords(1, "CANCELLED");

        int expectedTotal = 8;

        // Get status
        StageStatus status = refDbService.fetchStatuses(TEST_REQUEST_ID)
                .stream()
                .filter(s -> s.site().equals(TEST_SITE))
                .findFirst()
                .orElse(null);

        assertNotNull(status);
        assertEquals(expectedTotal, status.total(), "Total should be 8");

        // Verify each state is counted
        assertEquals(1, status.ready(), "Staged (pending) count");
        assertEquals(1, status.queued(), "Queued (ENQUEUED) count");
        assertEquals(1, status.enriching(), "Enriching count");
        assertEquals(1, status.exensioLoading(), "Exensio loading count");
        assertEquals(1, status.failed(), "Failed count");
        assertEquals(1, status.completed(), "Completed (DONE) count");
        assertEquals(1, status.cancelled(), "Cancelled count");

        // Verify sum
        long accountingSum = status.accountingSum();
        assertEquals(expectedTotal, accountingSum,
                "All states should be counted in accounting sum");
    }

    // Helper methods

    private void insertRecords(int count, String status) throws SQLException {
        String table = refDbProperties.getStagingTable();
        String sql = "INSERT INTO " + table +
                " (site, sender_id, sender_name, request_id, lot, data_type, status) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?)";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < count; i++) {
                ps.setString(1, TEST_SITE);
                ps.setInt(2, TEST_SENDER_ID);
                ps.setString(3, TEST_SENDER_NAME);
                ps.setString(4, TEST_REQUEST_ID);
                ps.setString(5, "LOT_" + System.nanoTime() + "_" + i);
                ps.setString(6, "TEST_DATA_TYPE");
                ps.setString(7, status);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private void cancelRecords(int count) throws SQLException {
        String table = refDbProperties.getStagingTable();
        String sql = "UPDATE " + table +
                " SET status = 'CANCELLED' " +
                "WHERE site = ? AND sender_id = ? AND request_id = ? " +
                "AND status = 'ENRICHMENT' " +
                "AND rownum <= ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, TEST_SITE);
            ps.setInt(2, TEST_SENDER_ID);
            ps.setString(3, TEST_REQUEST_ID);
            ps.setInt(4, count);
            ps.executeUpdate();
        }
    }

    private void markRecordsDone(int count) throws SQLException {
        String table = refDbProperties.getStagingTable();
        String sql = "UPDATE " + table +
                " SET status = 'DONE' " +
                "WHERE site = ? AND sender_id = ? AND request_id = ? " +
                "AND status = 'ENRICHMENT' " +
                "AND rownum <= ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, TEST_SITE);
            ps.setInt(2, TEST_SENDER_ID);
            ps.setString(3, TEST_REQUEST_ID);
            ps.setInt(4, count);
            ps.executeUpdate();
        }
    }

    private void transitionRecords(int count, String fromStatus, String toStatus) throws SQLException {
        String table = refDbProperties.getStagingTable();
        String sql = "UPDATE " + table +
                " SET status = ? " +
                "WHERE site = ? AND sender_id = ? AND request_id = ? " +
                "AND status = ? " +
                "AND rownum <= ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, toStatus);
            ps.setString(2, TEST_SITE);
            ps.setInt(3, TEST_SENDER_ID);
            ps.setString(4, TEST_REQUEST_ID);
            ps.setString(5, fromStatus);
            ps.setInt(6, count);
            ps.executeUpdate();
        }
    }

    private void deleteTestData() throws SQLException {
        String table = refDbProperties.getStagingTable();
        String sql = "DELETE FROM " + table +
                " WHERE site = ? AND sender_id = ? AND request_id = ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, TEST_SITE);
            ps.setInt(2, TEST_SENDER_ID);
            ps.setString(3, TEST_REQUEST_ID);
            ps.executeUpdate();
        }
    }
}
