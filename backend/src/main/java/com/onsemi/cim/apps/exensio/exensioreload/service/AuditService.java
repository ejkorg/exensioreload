package com.onsemi.cim.apps.exensio.exensioreload.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.onsemi.cim.apps.exensio.exensioreload.entity.AuditLog;
import com.onsemi.cim.apps.exensio.exensioreload.entity.EtlAuditLog;
import com.onsemi.cim.apps.exensio.exensioreload.entity.AppUser;
import com.onsemi.cim.apps.exensio.exensioreload.repository.AuditLogRepository;
import com.onsemi.cim.apps.exensio.exensioreload.repository.EtlAuditLogRepository;
import com.onsemi.cim.apps.exensio.exensioreload.repository.AppUserRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Instant;
import java.util.Map;

@Service
@Transactional(readOnly = true)
public class AuditService {

    private static final Logger logger = LoggerFactory.getLogger(AuditService.class);

    private final AuditLogRepository auditLogRepository;
    private final EtlAuditLogRepository etlAuditLogRepository;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final ObjectProvider<AppUserRepository> appUserRepositoryProvider;

    public AuditService(AuditLogRepository auditLogRepository,
                        EtlAuditLogRepository etlAuditLogRepository,
                        ObjectMapper objectMapper,
                        PlatformTransactionManager transactionManager,
                        ObjectProvider<AppUserRepository> appUserRepositoryProvider) {
        this.auditLogRepository = auditLogRepository;
        this.etlAuditLogRepository = etlAuditLogRepository;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.appUserRepositoryProvider = appUserRepositoryProvider;
    }

    /**
     * Log an audit action with details
     */
    public void logAction(Long userId, String action, String resourceType, String resourceId, Map<String, Object> details) {
        String detailsJson = null;
        if (details != null) {
            try {
                detailsJson = objectMapper.writeValueAsString(details);
            } catch (JsonProcessingException e) {
                logger.error("Failed to serialize audit details for userId={}, action={}: {}", userId, action, e.getMessage());
            }
        }

        String ipAddress = getCurrentIpAddress();
        String userAgent = getCurrentUserAgent();

        try {
            final String dJson = detailsJson;
            transactionTemplate.execute(status -> {
                AuditLog auditLog = new AuditLog(userId, action, resourceType, resourceId, dJson, ipAddress, userAgent);
                auditLogRepository.save(auditLog);
                return null;
            });

            logger.info("Audit log created: userId={}, action={}, resourceType={}, resourceId={}",
                    userId, action, resourceType, resourceId);
        } catch (Exception e) {
            // If logging failed when userId is null (e.g. before schema migration is applied),
            // attempt fallback to a known admin/system user ID.
            if (userId == null && appUserRepositoryProvider != null) {
                try {
                    AppUserRepository userRepo = appUserRepositoryProvider.getIfAvailable();
                    if (userRepo != null) {
                        Long fallbackUserId = userRepo.findByUsername("admin")
                                .map(AppUser::getId)
                                .orElse(1L);
                        logger.warn("Retrying audit log with fallback admin userId={} after failure: {}",
                                fallbackUserId, e.getMessage());
                        final String dJson = detailsJson;
                        transactionTemplate.execute(status -> {
                            AuditLog fallbackLog = new AuditLog(fallbackUserId, action, resourceType, resourceId, dJson, ipAddress, userAgent);
                            auditLogRepository.save(fallbackLog);
                            return null;
                        });
                        logger.info("Audit log created with fallback userId={}: action={}, resourceType={}, resourceId={}",
                                fallbackUserId, action, resourceType, resourceId);
                        return;
                    }
                } catch (Exception retryEx) {
                    logger.error("Failed fallback audit log creation for action={}: {}", action, retryEx.getMessage(), retryEx);
                }
            }
            logger.error("Failed to create audit log for userId={}, action={}: {}", userId, action, e.getMessage(), e);
        }
    }

    /**
     * Log an audit action without details
     */
    public void logAction(Long userId, String action, String resourceType, String resourceId) {
        logAction(userId, action, resourceType, resourceId, null);
    }

    /**
     * Log a simple action
     */
    public void logAction(Long userId, String action, String resourceType) {
        logAction(userId, action, resourceType, null, null);
    }

    /**
     * Get audit logs with filtering by userId, action, resourceType, and date range.
     * Returns paginated results sorted by createdAt descending.
     *
     * @param userId the user ID to filter by (optional, pass null for all users)
     * @param action the action to filter by (optional, pass null for all actions)
     * @param resourceType the resource type to filter by (optional, pass null for all types)
     * @param startDate the start date for filtering (optional, pass null for no lower bound)
     * @param endDate the end date for filtering (optional, pass null for no upper bound)
     * @param pageable pagination and sorting info
     * @return a page of audit logs matching the criteria
     */
    @Transactional(readOnly = true)
    public Page<AuditLog> getAuditLogs(Long userId, String action, String resourceType,
                                       Instant startDate, Instant endDate, Pageable pageable) {
        return auditLogRepository.findWithFilters(userId, action, resourceType, startDate, endDate, pageable);
    }

    /**
     * Search and filter audit logs using criteria object.
     * Provides a more flexible interface for complex search scenarios.
     * Returns paginated results sorted by createdAt descending.
     *
     * @param userId the user ID to filter by (optional)
     * @param action the action to filter by (optional)
     * @param resourceType the resource type to filter by (optional)
     * @param startDate the start of the date range (optional)
     * @param endDate the end of the date range (optional)
     * @param pageable pagination and sorting info
     * @return a page of audit logs matching the search criteria
     */
    @Transactional(readOnly = true)
    public Page<AuditLog> searchAuditLogs(Long userId, String action, String resourceType,
                                          Instant startDate, Instant endDate, Pageable pageable) {
        return getAuditLogs(userId, action, resourceType, startDate, endDate, pageable);
    }

    /**
     * Get audit logs for a specific user
     */
    @Transactional(readOnly = true)
    public Page<AuditLog> getAuditLogsForUser(Long userId, Pageable pageable) {
        return auditLogRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable);
    }

    /**
     * Get audit logs by action
     */
    @Transactional(readOnly = true)
    public Page<AuditLog> getAuditLogsByAction(String action, Pageable pageable) {
        return auditLogRepository.findByActionOrderByCreatedAtDesc(action, pageable);
    }

    /**
     * Get audit log statistics
     */
    @Transactional(readOnly = true)
    public Map<String, Long> getActionStatistics(Instant since) {
        return auditLogRepository.getActionStatisticsSince(since)
                .stream()
                .collect(java.util.stream.Collectors.toMap(
                        row -> (String) row[0],
                        row -> (Long) row[1]
                ));
    }

    /**
     * Clean up old audit logs (for maintenance)
     */
    @Transactional
    public void cleanupOldAuditLogs(Instant cutoffDate) {
        try {
            auditLogRepository.deleteByCreatedAtBefore(cutoffDate);
            logger.info("Cleaned up audit logs older than {}", cutoffDate);
        } catch (Exception e) {
            logger.error("Failed to cleanup old audit logs: {}", e.getMessage(), e);
        }
    }

    /**
     * Get current IP address from request
     */
    private String getCurrentIpAddress() {
        try {
            ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attributes != null) {
                HttpServletRequest request = attributes.getRequest();
                String xForwardedFor = request.getHeader("X-Forwarded-For");
                if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
                    return xForwardedFor.split(",")[0].trim();
                }
                String xRealIp = request.getHeader("X-Real-IP");
                if (xRealIp != null && !xRealIp.isEmpty()) {
                    return xRealIp;
                }
                return request.getRemoteAddr();
            }
        } catch (Exception e) {
            logger.debug("Could not determine IP address: {}", e.getMessage());
        }
        return "unknown";
    }

    /**
     * Get current user agent from request
     */
    private String getCurrentUserAgent() {
        try {
            ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attributes != null) {
                HttpServletRequest request = attributes.getRequest();
                String userAgent = request.getHeader("User-Agent");
                return userAgent != null ? userAgent : "unknown";
            }
        } catch (Exception e) {
            logger.debug("Could not determine user agent: {}", e.getMessage());
        }
        return "unknown";
    }

    /**
     * Export audit logs to CSV format.
     * Generates CSV content as a byte array with columns:
     * timestamp, userId, username, action, resourceType, resourceId, status, ipAddress
     * Excludes sensitive details from the CSV export.
     *
     * @param userId the user ID to filter by (optional)
     * @param action the action to filter by (optional)
     * @param resourceType the resource type to filter by (optional)
     * @param startDate the start of the date range (optional)
     * @param endDate the end of the date range (optional)
     * @return CSV content as byte array
     */
    @Transactional(readOnly = true)
    public byte[] exportToCSV(Long userId, String action, String resourceType,
                              Instant startDate, Instant endDate) {
        // Fetch all matching records without pagination (using a large page size)
        org.springframework.data.domain.PageRequest pageRequest =
                org.springframework.data.domain.PageRequest.of(0, Integer.MAX_VALUE,
                        org.springframework.data.domain.Sort.by("createdAt").descending());

        Page<AuditLog> auditLogs = auditLogRepository.findWithFilters(
                userId, action, resourceType, startDate, endDate, pageRequest);

        // Build CSV content
        StringBuilder csv = new StringBuilder();
        csv.append("timestamp,userId,username,action,resourceType,resourceId,status,ipAddress\n");

        for (AuditLog log : auditLogs.getContent()) {
            String username = getUsernameById(log.getUserId());
            csv.append(escapeCsv(log.getCreatedAt().toString())).append(",");
            csv.append(escapeCsv(log.getUserId() != null ? log.getUserId().toString() : "")).append(",");
            csv.append(escapeCsv(username != null ? username : "")).append(",");
            csv.append(escapeCsv(log.getAction())).append(",");
            csv.append(escapeCsv(log.getResourceType())).append(",");
            csv.append(escapeCsv(log.getResourceId())).append(",");
            csv.append(escapeCsv(log.getStatus() != null ? log.getStatus() : "")).append(",");
            csv.append(escapeCsv(log.getIpAddress())).append("\n");
        }

        return csv.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * Get username by user ID from the database.
     * Returns null if user is not found.
     */
    private String getUsernameById(Long userId) {
        if (userId == null) {
            return null;
        }
        try {
            AppUserRepository userRepo = appUserRepositoryProvider.getIfAvailable();
            if (userRepo != null) {
                return userRepo.findById(userId)
                        .map(AppUser::getUsername)
                        .orElse(null);
            }
        } catch (Exception e) {
            logger.debug("Could not retrieve username for userId={}: {}", userId, e.getMessage());
        }
        return null;
    }

    /**
     * Escape special characters in CSV field values.
     * Wraps fields containing comma, quote, or newline characters in double quotes.
     * Escapes quotes by doubling them.
     */
    private String escapeCsv(String field) {
        if (field == null || field.isEmpty()) {
            return "";
        }
        if (field.contains(",") || field.contains("\"") || field.contains("\n")) {
            return "\"" + field.replace("\"", "\"\"") + "\"";
        }
        return field;
    }

    /**
     * Log an ETL trigger audit entry.
     */
    public void logEtlTrigger(String requestId, String userId, String site, String location,
                              String etlServerName, Integer senderPort, String status,
                              String message, String remoteIp) {
        try {
            transactionTemplate.execute(txStatus -> {
                EtlAuditLog etlAuditLog = new EtlAuditLog(
                        requestId, userId, site, location, etlServerName, senderPort,
                        status, message, remoteIp
                );
                etlAuditLogRepository.save(etlAuditLog);
                return null;
            });

            logger.info("ETL audit log created: requestId={}, userId={}, site={}, etlServerName={}, status={}",
                    requestId, userId, site, etlServerName, status);
        } catch (Exception e) {
            logger.error("Failed to create ETL audit log: {}", e.getMessage(), e);
        }
    }

    /**
     * Get all ETL audit logs for admin users.
     */
    @Transactional(readOnly = true)
    public java.util.List<EtlAuditLog> findAllForAdmin() {
        return etlAuditLogRepository.findAllByOrderByTimestampDesc();
    }

    /**
     * Log a configuration change with action, resource type, resource ID, and details (JSON).
     * Excludes passwords and sensitive information from audit log details.
     * Captures IP address and user agent from HttpServletRequest.
     *
     * @param action the audit action (e.g., PIPELINE_CREATED, PIPELINE_UPDATED)
     * @param resourceType the resource type (e.g., PIPELINE, ETL_SERVER, DB_CONNECTION)
     * @param resourceId the resource ID
     * @param detailsJson the details as JSON string (with passwords excluded)
     */
    public void logConfigChange(String action, String resourceType, String resourceId, String detailsJson) {
        Long userId = getCurrentUserId();
        String ipAddress = getCurrentIpAddress();
        String userAgent = getCurrentUserAgent();

        try {
            transactionTemplate.execute(status -> {
                AuditLog auditLog = new AuditLog(userId, action, resourceType, resourceId, detailsJson, ipAddress, userAgent);
                auditLog.setStatus("SUCCESS");
                auditLogRepository.save(auditLog);
                return null;
            });

            logger.info("Configuration change logged: action={}, resourceType={}, resourceId={}, userId={}",
                    action, resourceType, resourceId, userId);
        } catch (Exception e) {
            logger.error("Failed to log configuration change: action={}, resourceType={}, resourceId={}: {}",
                    action, resourceType, resourceId, e.getMessage(), e);
        }
    }

    /**
     * Log a session event with action, session ID, and session details.
     * Serializes the session details map to JSON.
     *
     * @param action the audit action (e.g., SESSION_CREATED, SESSION_COMPLETED)
     * @param sessionId the session ID or resource ID
     * @param sessionDetailsMap a map of session details to be serialized as JSON
     */
    public void logSessionEvent(String action, String sessionId, Map<String, Object> sessionDetailsMap) {
        String detailsJson = null;
        if (sessionDetailsMap != null) {
            try {
                detailsJson = objectMapper.writeValueAsString(sessionDetailsMap);
            } catch (JsonProcessingException e) {
                logger.error("Failed to serialize session details for sessionId={}: {}", sessionId, e.getMessage());
            }
        }

        logConfigChange(action, AuditLog.ResourceTypes.SESSION, sessionId, detailsJson);
    }

    /**
     * Log an ETL trigger event with request ID, site, sender ID, status, and message.
     * Creates appropriate audit log entry without exposing sensitive information.
     *
     * @param requestId the ETL request ID
     * @param site the site name
     * @param senderId the sender ID / queue port
     * @param status the trigger status (SUCCESS, FAILURE, PARTIAL)
     * @param message additional message or error details
     */
    public void logEtlTrigger(String requestId, String site, Integer senderId, String status, String message) {
        Map<String, Object> details = new java.util.HashMap<>();
        details.put("site", site);
        details.put("senderId", senderId);
        details.put("status", status);
        if (message != null) {
            details.put("message", message);
        }

        String detailsJson = null;
        try {
            detailsJson = objectMapper.writeValueAsString(details);
        } catch (JsonProcessingException e) {
            logger.error("Failed to serialize ETL trigger details for requestId={}: {}", requestId, e.getMessage());
        }

        logConfigChange(AuditLog.Actions.ETL_TRIGGERED, "ETL_TRIGGER", requestId, detailsJson);
    }

    /**
     * Get the current user ID from the security context.
     * Returns null if no user is authenticated or available.
     */
    private Long getCurrentUserId() {
        try {
            org.springframework.security.core.Authentication authentication =
                    org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
            if (authentication != null && authentication.isAuthenticated()) {
                Object principal = authentication.getPrincipal();
                if (principal instanceof org.springframework.security.core.userdetails.UserDetails) {
                    // Try to find the user in database
                    String username = ((org.springframework.security.core.userdetails.UserDetails) principal).getUsername();
                    AppUserRepository userRepo = appUserRepositoryProvider.getIfAvailable();
                    if (userRepo != null) {
                        return userRepo.findByUsername(username)
                                .map(AppUser::getId)
                                .orElse(null);
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("Could not determine current user ID: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Get the audit log repository for direct access.
     * Used by controllers and other components that need repository access.
     *
     * @return the AuditLogRepository
     */
    public AuditLogRepository getAuditLogRepository() {
        return auditLogRepository;
    }
}
