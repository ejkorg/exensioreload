package com.onsemi.cim.apps.exensio.exensioreload.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.onsemi.cim.apps.exensio.exensioreload.entity.AuditLog;
import com.onsemi.cim.apps.exensio.exensioreload.entity.EtlAuditLog;
import com.onsemi.cim.apps.exensio.exensioreload.repository.AuditLogRepository;
import com.onsemi.cim.apps.exensio.exensioreload.repository.EtlAuditLogRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.onsemi.cim.apps.exensio.exensioreload.entity.AppUser;
import com.onsemi.cim.apps.exensio.exensioreload.repository.AppUserRepository;
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
     * Get audit logs with filtering
     */
    @Transactional(readOnly = true)
    public Page<AuditLog> getAuditLogs(Long userId, String action, String resourceType,
                                       Instant startDate, Instant endDate, Pageable pageable) {
        return auditLogRepository.findWithFilters(userId, action, resourceType, startDate, endDate, pageable);
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
}
