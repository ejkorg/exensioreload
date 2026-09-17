package com.onsemi.cim.apps.exensio.exensioreload.controller;

import com.onsemi.cim.apps.exensio.exensioreload.dto.AuditLogDto;
import com.onsemi.cim.apps.exensio.exensioreload.entity.AuditLog;
import com.onsemi.cim.apps.exensio.exensioreload.service.AuditService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * REST API controller for audit log management.
 * Provides access to audit logs with filtering, searching, and export capabilities.
 * All endpoints require ADMIN or SUPER_ADMIN role.
 */
@Slf4j
@RestController
@RequestMapping("/api/audit-logs")
@PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
public class ConfigurationAuditLogController {

    private final AuditService auditService;

    public ConfigurationAuditLogController(AuditService auditService) {
        this.auditService = auditService;
    }

    /**
     * Get audit logs with pagination and filtering.
     * Supports filtering by userId, action, resourceType, and date range.
     *
     * GET /api/audit-logs?page=0&size=20&userId=1&action=PIPELINE_CREATED&resourceType=PIPELINE&startDate=2024-01-01T00:00:00Z&endDate=2024-12-31T23:59:59Z
     *
     * @param userId optional user ID to filter by
     * @param action optional action type to filter by
     * @param resourceType optional resource type to filter by
     * @param startDate optional start date for filtering (ISO-8601 format)
     * @param endDate optional end date for filtering (ISO-8601 format)
     * @param pageable pagination info (default: page 0, size 20, sorted by createdAt descending)
     * @return page of audit logs matching the criteria
     */
    @GetMapping
    public ResponseEntity<Page<AuditLogDto>> getAuditLogs(
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String resourceType,
            @RequestParam(required = false) 
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant startDate,
            @RequestParam(required = false) 
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant endDate,
            @PageableDefault(size = 20, page = 0, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        
        log.info("Fetching audit logs: userId={}, action={}, resourceType={}, startDate={}, endDate={}", 
                userId, action, resourceType, startDate, endDate);
        
        try {
            Page<AuditLog> auditLogs = auditService.getAuditLogs(userId, action, resourceType, startDate, endDate, pageable);
            Page<AuditLogDto> dtoPage = auditLogs.map(this::convertToDto);
            return ResponseEntity.ok(dtoPage);
        } catch (Exception e) {
            log.error("Error fetching audit logs", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Get a specific audit log entry by ID.
     *
     * GET /api/audit-logs/{id}
     *
     * @param id the audit log ID
     * @return the audit log entry
     */
    @GetMapping("/{id}")
    public ResponseEntity<AuditLogDto> getAuditLogById(@PathVariable Long id) {
        log.info("Fetching audit log: id={}", id);
        
        try {
            return auditService.getAuditLogRepository().findById(id)
                    .map(auditLog -> ResponseEntity.ok(convertToDto(auditLog)))
                    .orElseGet(() -> {
                        log.warn("Audit log not found: id={}", id);
                        return ResponseEntity.notFound().build();
                    });
        } catch (Exception e) {
            log.error("Error fetching audit log by id: {}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Get audit log statistics.
     * Returns aggregate statistics about audit log entries.
     *
     * GET /api/audit-logs/statistics
     *
     * @return statistics map with counts by action and resource type
     */
    @GetMapping("/statistics")
    public ResponseEntity<Map<String, Object>> getAuditLogStatistics() {
        log.info("Fetching audit log statistics");
        
        try {
            Map<String, Object> statistics = new HashMap<>();
            
            // Get total count
            long totalCount = auditService.getAuditLogRepository().count();
            statistics.put("totalCount", totalCount);
            
            // Count by action (for known actions)
            statistics.put("pipelineCreatedCount", 
                    auditService.getAuditLogRepository()
                            .countByResourceTypeAndAction(AuditLog.ResourceTypes.PIPELINE, AuditLog.Actions.PIPELINE_CREATED));
            statistics.put("pipelineUpdatedCount", 
                    auditService.getAuditLogRepository()
                            .countByResourceTypeAndAction(AuditLog.ResourceTypes.PIPELINE, AuditLog.Actions.PIPELINE_UPDATED));
            statistics.put("pipelineDeletedCount", 
                    auditService.getAuditLogRepository()
                            .countByResourceTypeAndAction(AuditLog.ResourceTypes.PIPELINE, AuditLog.Actions.PIPELINE_DELETED));
            statistics.put("etlServerCreatedCount", 
                    auditService.getAuditLogRepository()
                            .countByResourceTypeAndAction(AuditLog.ResourceTypes.ETL_SERVER, AuditLog.Actions.ETL_SERVER_CREATED));
            statistics.put("etlServerUpdatedCount", 
                    auditService.getAuditLogRepository()
                            .countByResourceTypeAndAction(AuditLog.ResourceTypes.ETL_SERVER, AuditLog.Actions.ETL_SERVER_UPDATED));
            statistics.put("etlServerDeletedCount", 
                    auditService.getAuditLogRepository()
                            .countByResourceTypeAndAction(AuditLog.ResourceTypes.ETL_SERVER, AuditLog.Actions.ETL_SERVER_DELETED));
            statistics.put("dbConnectionCreatedCount", 
                    auditService.getAuditLogRepository()
                            .countByResourceTypeAndAction(AuditLog.ResourceTypes.DB_CONNECTION, AuditLog.Actions.DB_CONNECTION_CREATED));
            statistics.put("dbConnectionUpdatedCount", 
                    auditService.getAuditLogRepository()
                            .countByResourceTypeAndAction(AuditLog.ResourceTypes.DB_CONNECTION, AuditLog.Actions.DB_CONNECTION_UPDATED));
            statistics.put("dbConnectionDeletedCount", 
                    auditService.getAuditLogRepository()
                            .countByResourceTypeAndAction(AuditLog.ResourceTypes.DB_CONNECTION, AuditLog.Actions.DB_CONNECTION_DELETED));
            
            return ResponseEntity.ok(statistics);
        } catch (Exception e) {
            log.error("Error fetching audit log statistics", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Export audit logs to CSV format.
     * Supports filtering by userId, action, and resourceType.
     *
     * GET /api/audit-logs/export?action=PIPELINE_CREATED&resourceType=PIPELINE
     *
     * @param userId optional user ID to filter by
     * @param action optional action type to filter by
     * @param resourceType optional resource type to filter by
     * @param startDate optional start date for filtering (ISO-8601 format)
     * @param endDate optional end date for filtering (ISO-8601 format)
     * @return CSV file content as byte array
     */
    @GetMapping(value = "/export", produces = "text/csv")
    public ResponseEntity<byte[]> exportAuditLogs(
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String resourceType,
            @RequestParam(required = false) 
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant startDate,
            @RequestParam(required = false) 
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant endDate) {
        
        log.info("Exporting audit logs: userId={}, action={}, resourceType={}, startDate={}, endDate={}", 
                userId, action, resourceType, startDate, endDate);
        
        try {
            ByteArrayOutputStream csvOutput = new ByteArrayOutputStream();
            PrintWriter writer = new PrintWriter(csvOutput);
            
            // Write CSV header
            writer.println("Timestamp,User ID,User Agent,Action,Resource Type,Resource ID,Status,IP Address,Details");
            
            // Fetch all matching records (pagination used here is just for data retrieval)
            Page<AuditLog> auditLogs = auditService.getAuditLogs(userId, action, resourceType, startDate, endDate, 
                    Pageable.ofSize(Integer.MAX_VALUE));
            
            // Write CSV rows
            for (AuditLog log : auditLogs.getContent()) {
                writer.printf("\"%s\",\"%s\",\"%s\",\"%s\",\"%s\",\"%s\",\"%s\",\"%s\",\"%s\"%n",
                        escapeForCsv(log.getCreatedAt() != null ? log.getCreatedAt().toString() : ""),
                        log.getUserId() != null ? log.getUserId() : "",
                        escapeForCsv(log.getUserAgent() != null ? log.getUserAgent() : ""),
                        escapeForCsv(log.getAction()),
                        escapeForCsv(log.getResourceType()),
                        escapeForCsv(log.getResourceId() != null ? log.getResourceId() : ""),
                        escapeForCsv(log.getStatus() != null ? log.getStatus() : ""),
                        escapeForCsv(log.getIpAddress() != null ? log.getIpAddress() : ""),
                        escapeForCsv(log.getDetails() != null ? log.getDetails() : "")
                );
            }
            
            writer.flush();
            writer.close();
            
            byte[] csvBytes = csvOutput.toByteArray();
            log.info("Exported {} audit logs to CSV", auditLogs.getContent().size());
            
            return ResponseEntity.ok()
                    .header("Content-Disposition", "attachment; filename=\"audit_logs.csv\"")
                    .body(csvBytes);
        } catch (Exception e) {
            log.error("Error exporting audit logs", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Convert an AuditLog entity to an AuditLogDto.
     *
     * @param auditLog the audit log entity
     * @return the DTO representation
     */
    private AuditLogDto convertToDto(AuditLog auditLog) {
        AuditLogDto dto = new AuditLogDto();
        dto.setId(auditLog.getId());
        dto.setUserId(auditLog.getUserId());
        dto.setAction(auditLog.getAction());
        dto.setResourceType(auditLog.getResourceType());
        dto.setResourceId(auditLog.getResourceId());
        dto.setDetails(auditLog.getDetails());
        dto.setIpAddress(auditLog.getIpAddress());
        dto.setUserAgent(auditLog.getUserAgent());
        dto.setCreatedAt(auditLog.getCreatedAt());
        return dto;
    }

    /**
     * Escape special characters for CSV format.
     * Quotes and escapes double quotes and newlines.
     *
     * @param value the value to escape
     * @return the escaped value
     */
    private String escapeForCsv(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return value.replace("\"", "\"\"");
        }
        return value;
    }
}
