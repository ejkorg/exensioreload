package com.onsemi.cim.apps.exensio.exensioreload.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.onsemi.cim.apps.exensio.exensioreload.dto.StateAccountingReport;
import com.onsemi.cim.apps.exensio.exensioreload.service.StateAccountingService;

/**
 * Admin debug endpoints for system diagnostics and verification.
 * All endpoints require ROLE_ADMIN authorization.
 */
@RestController
@RequestMapping("/api/admin/debug")
public class AdminDebugController {
    private static final Logger logger = LoggerFactory.getLogger(AdminDebugController.class);

    private final StateAccountingService stateAccountingService;

    public AdminDebugController(StateAccountingService stateAccountingService) {
        this.stateAccountingService = stateAccountingService;
    }

    /**
     * Generate state accounting verification report.
     *
     * Queries database for all 8 record states (pending, ENQUEUED, ENRICHMENT, EXENSIO_LOADING,
     * PROCESSING, FAILED, DONE, CANCELLED) plus NULL/UNKNOWN status checks.
     * Compares against dashboard aggregation to detect discrepancies.
     *
     * @param requestId optional request_id filter
     * @param site optional site filter
     * @param senderId optional sender_id filter
     * @return comprehensive accounting report with database counts, dashboard counts, and data integrity checks
     */
    @PreAuthorize("hasRole('ADMIN') or hasRole('ROLE_ADMIN') or hasRole('ROLE_SUPER_ADMIN')")
    @GetMapping("/state-accounting")
    public ResponseEntity<StateAccountingReport> verifyStateAccounting(
            @RequestParam(required = false) String requestId,
            @RequestParam(required = false) String site,
            @RequestParam(required = false) Integer senderId) {
        try {
            logger.info("[ADMIN DEBUG] Verifying state accounting: requestId={}, site={}, senderId={}", 
                requestId, site, senderId);

            StateAccountingReport report = stateAccountingService.generateReport(requestId, site, senderId);

            logger.info("[ADMIN DEBUG] State accounting verification complete. Total: {}, Sum: {}, Valid: {}",
                report.getDatabase().getTotalCount(),
                report.getDatabase().getSumOfStates(),
                report.getDataIntegrity().isValid());

            return ResponseEntity.ok(report);
        } catch (Exception e) {
            logger.error("[ADMIN DEBUG] Failed verifying state accounting", e);
            return ResponseEntity.status(500).build();
        }
    }
}
