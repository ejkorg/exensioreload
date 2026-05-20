package com.onsemi.cim.apps.exensio.resender.controller;

import com.onsemi.cim.apps.exensio.resender.entity.EtlAuditLog;
import com.onsemi.cim.apps.exensio.resender.service.AuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST API controller for ETL trigger audit logs.
 * <p>
 * This controller provides admin-only access to ETL trigger audit logs.
 * Only users with ADMIN, ROLE_ADMIN, or ROLE_SUPER_ADMIN roles can access
 * these endpoints.
 */
@RestController
@RequestMapping("/api/etl-trigger/audit")
public class AuditController {

    private static final Logger logger = LoggerFactory.getLogger(AuditController.class);

    private final AuditService auditService;

    public AuditController(AuditService auditService) {
        this.auditService = auditService;
    }

    /**
     * Get all ETL trigger audit logs.
     * <p>
     * This endpoint is restricted to users with ADMIN role.
     * Returns all audit log entries ordered by timestamp (most recent first).
     *
     * @return List of all ETL audit logs
     */
    @PreAuthorize("hasRole('ADMIN') or hasRole('ROLE_ADMIN') or hasRole('ROLE_SUPER_ADMIN')")
    @GetMapping
    public List<EtlAuditLog> getAllAuditLogs() {
        logger.info("Fetching all ETL trigger audit logs");

        try {
            return auditService.findAllForAdmin();
        } catch (Exception e) {
            logger.error("Failed to fetch ETL audit logs: {}", e.getMessage(), e);
            // Return empty list on error
            return List.of();
        }
    }

    /**
     * Get audit logs by request ID.
     * <p>
     * This endpoint is restricted to users with ADMIN role.
     *
     * @param requestId The request ID to search for
     * @return List of audit logs matching the request ID
     */
    @PreAuthorize("hasRole('ADMIN') or hasRole('ROLE_ADMIN') or hasRole('ROLE_SUPER_ADMIN')")
    @GetMapping("/request-id/{requestId}")
    public List<EtlAuditLog> getAuditLogsByRequestId(@PathVariable String requestId) {
        logger.info("Fetching ETL audit logs for requestId: {}", requestId);

        try {
            List<EtlAuditLog> logs = auditService.findAllForAdmin();
            return logs.stream()
                    .filter(log -> log.getRequestId().equals(requestId))
                    .toList();
        } catch (Exception e) {
            logger.error("Failed to fetch ETL audit logs for requestId {}: {}",
                    requestId, e.getMessage(), e);
            return List.of();
        }
    }

    /**
     * Get audit logs by user ID.
     * <p>
     * This endpoint is restricted to users with ADMIN role.
     *
     * @param userId The user ID to search for
     * @return List of audit logs matching the user ID
     */
    @PreAuthorize("hasRole('ADMIN') or hasRole('ROLE_ADMIN') or hasRole('ROLE_SUPER_ADMIN')")
    @GetMapping("/user-id/{userId}")
    public List<EtlAuditLog> getAuditLogsByUserId(@PathVariable String userId) {
        logger.info("Fetching ETL audit logs for userId: {}", userId);

        try {
            List<EtlAuditLog> logs = auditService.findAllForAdmin();
            return logs.stream()
                    .filter(log -> log.getUserId().equals(userId))
                    .toList();
        } catch (Exception e) {
            logger.error("Failed to fetch ETL audit logs for userId {}: {}",
                    userId, e.getMessage(), e);
            return List.of();
        }
    }

    /**
     * Get audit logs by status.
     * <p>
     * This endpoint is restricted to users with ADMIN role.
     *
     * @param status The status to search for (success, failure, not_configured)
     * @return List of audit logs matching the status
     */
    @PreAuthorize("hasRole('ADMIN') or hasRole('ROLE_ADMIN') or hasRole('ROLE_SUPER_ADMIN')")
    @GetMapping("/status/{status}")
    public List<EtlAuditLog> getAuditLogsByStatus(@PathVariable String status) {
        logger.info("Fetching ETL audit logs for status: {}", status);

        try {
            List<EtlAuditLog> logs = auditService.findAllForAdmin();
            return logs.stream()
                    .filter(log -> log.getStatus().equals(status))
                    .toList();
        } catch (Exception e) {
            logger.error("Failed to fetch ETL audit logs for status {}: {}",
                    status, e.getMessage(), e);
            return List.of();
        }
    }

    /**
     * Get audit logs by site.
     * <p>
     * This endpoint is restricted to users with ADMIN role.
     *
     * @param site The site name to search for
     * @return List of audit logs matching the site
     */
    @PreAuthorize("hasRole('ADMIN') or hasRole('ROLE_ADMIN') or hasRole('ROLE_SUPER_ADMIN')")
    @GetMapping("/site/{site}")
    public List<EtlAuditLog> getAuditLogsBySite(@PathVariable String site) {
        logger.info("Fetching ETL audit logs for site: {}", site);

        try {
            List<EtlAuditLog> logs = auditService.findAllForAdmin();
            return logs.stream()
                    .filter(log -> log.getSite().equals(site))
                    .toList();
        } catch (Exception e) {
            logger.error("Failed to fetch ETL audit logs for site {}: {}",
                    site, e.getMessage(), e);
            return List.of();
        }
    }

    /**
     * Get audit logs by ETL server name.
     * <p>
     * This endpoint is restricted to users with ADMIN role.
     *
     * @param etlServerName The ETL server name to search for
     * @return List of audit logs matching the server name
     */
    @PreAuthorize("hasRole('ADMIN') or hasRole('ROLE_ADMIN') or hasRole('ROLE_SUPER_ADMIN')")
    @GetMapping("/etl-server/{etlServerName}")
    public List<EtlAuditLog> getAuditLogsByEtlServerName(@PathVariable String etlServerName) {
        logger.info("Fetching ETL audit logs for etlServerName: {}", etlServerName);

        try {
            List<EtlAuditLog> logs = auditService.findAllForAdmin();
            return logs.stream()
                    .filter(log -> log.getEtlServerName().equals(etlServerName))
                    .toList();
        } catch (Exception e) {
            logger.error("Failed to fetch ETL audit logs for etlServerName {}: {}",
                    etlServerName, e.getMessage(), e);
            return List.of();
        }
    }
}
