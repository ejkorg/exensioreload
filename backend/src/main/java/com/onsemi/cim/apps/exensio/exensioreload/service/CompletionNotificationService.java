package com.onsemi.cim.apps.exensio.exensioreload.service;

import com.onsemi.cim.apps.exensio.exensioreload.config.ExternalDbConfig;
import com.onsemi.cim.apps.exensio.exensioreload.entity.AppUser;
import com.onsemi.cim.apps.exensio.exensioreload.entity.LoadSession;
import com.onsemi.cim.apps.exensio.exensioreload.entity.LoadSessionPayload;
import com.onsemi.cim.apps.exensio.exensioreload.repository.AppUserRepository;
import com.onsemi.cim.apps.exensio.exensioreload.repository.LoadSessionPayloadRepository;
import com.onsemi.cim.apps.exensio.exensioreload.repository.LoadSessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.*;

/**
 * CompletionNotificationService
 *
 * Monitors external sender queue tables to detect when items have been processed
 * (removed from the queue) by third-party applications. When all items for a user's
 * session are completed, sends an email notification with details.
 */
@Service
public class CompletionNotificationService {
    private static final Logger log = LoggerFactory.getLogger(CompletionNotificationService.class);

    @Autowired
    private LoadSessionRepository sessionRepo;

    @Autowired
    private LoadSessionPayloadRepository payloadRepo;

    @Autowired
    private AppUserRepository userRepository;

    @Autowired
    private ExternalDbConfig externalDbConfig;

    @Autowired
    private MailService mailService;

    @Autowired
    private Environment env;

    /**
     * Scheduled task to check for completed sessions.
     * Runs every 5 minutes by default (configurable via app.completion-check.cron)
     */
    @Scheduled(cron = "${app.completion-check.cron:0 */5 * * * *}")
    @Transactional
    public void checkCompletions() {
        boolean enabled = Boolean.parseBoolean(env.getProperty("app.completion-check.enabled", "true"));
        if (!enabled) {
            log.debug("Completion check is disabled");
            return;
        }

        try {
            log.debug("Starting completion check for active sessions");

            // Find sessions that:
            // 1. Have items pushed to external queue (status = PUSHED or PUSHING_REMOTE)
            // 2. Have user email configured
            // 3. Notification not yet sent
            Set<String> superAdminRecipients = loadSuperAdminRecipients();
            List<LoadSession> activeSessions = findActiveSessions(!superAdminRecipients.isEmpty());

            for (LoadSession session : activeSessions) {
                try {
                    checkSessionCompletion(session, superAdminRecipients);
                } catch (Exception ex) {
                    log.error("Error checking completion for session {}: {}", session.getId(), ex.getMessage(), ex);
                }
            }

        } catch (Exception ex) {
            log.error("Error in completion check: {}", ex.getMessage(), ex);
        }
    }

    /**
     * Find sessions that are active and awaiting completion notification
     */
    private List<LoadSession> findActiveSessions(boolean allowMissingUserEmail) {
        // Find all sessions where:
        // - status is PUSHING_REMOTE or COMPLETED (items pushed to external queue)
        // - userEmail is not null/blank
        // - notificationSent is false or null
        // - pushedRemoteCount > 0 (at least some items were pushed)

        List<LoadSession> allSessions = sessionRepo.findAll();
        List<LoadSession> active = new ArrayList<>();

        for (LoadSession session : allSessions) {
            if ((session.getUserEmail() == null || session.getUserEmail().isBlank()) && !allowMissingUserEmail) {
                continue; // No email configured and no admin recipients
            }
            if (Boolean.TRUE.equals(session.getNotificationSent())) {
                continue; // Already notified
            }
            if (session.getPushedRemoteCount() == null || session.getPushedRemoteCount() == 0) {
                continue; // No items pushed to external queue
            }
            String status = session.getStatus();
            if (status == null || (!status.equals("PUSHING_REMOTE") && !status.equals("COMPLETED"))) {
                continue; // Not in a state where items are in external queue
            }

            active.add(session);
        }

        return active;
    }

    /**
     * Check if a specific session's items have been completed in the external queue
     */
    private void checkSessionCompletion(LoadSession session, Set<String> superAdminRecipients) {
        log.debug("Checking completion for session {} (sender={}, site={}, user={})",
                session.getId(), session.getSenderId(), session.getSite(), session.getInitiatedBy());

        // Update last checked timestamp
        session.setLastCheckedAt(Instant.now());

        // Get all pushed payloads for this session
        List<LoadSessionPayload> pushedPayloads = payloadRepo.findBySessionIdAndStatusOrderById(
                session.getId(), "PUSHED", org.springframework.data.domain.PageRequest.of(0, 10000)
        );

        if (pushedPayloads.isEmpty()) {
            log.debug("No PUSHED payloads found for session {}", session.getId());
            sessionRepo.save(session);
            return;
        }

        try (Connection conn = externalDbConfig.getConnection(session.getSite(), session.getEnvironment())) {
            // Check each pushed payload to see if it still exists in external queue
            List<PayloadCompletionInfo> completedItems = new ArrayList<>();
            int totalChecked = 0;
            int stillInQueue = 0;

            for (LoadSessionPayload payload : pushedPayloads) {
                if (payload.getExternalId() == null || payload.getExternalId().isBlank()) {
                    log.warn("Payload {} has no externalId, skipping", payload.getId());
                    continue;
                }

                totalChecked++;
                boolean existsInQueue = checkIfExistsInExternalQueue(conn, payload.getExternalId());

                if (!existsInQueue) {
                    // Item was removed from queue = completed
                    String[] parts = payload.getPayloadId() != null ? payload.getPayloadId().split(",") : new String[0];
                    String metadataId = parts.length > 0 ? parts[0] : "";
                    String dataId = parts.length > 1 ? parts[1] : "";

                    completedItems.add(new PayloadCompletionInfo(
                            metadataId, dataId,
                            extractLotWaferFilename(payload),
                            payload.getPushedAt()
                    ));
                } else {
                    stillInQueue++;
                }
            }

            log.info("Session {} completion check: {}/{} items still in external queue, {} completed",
                    session.getId(), stillInQueue, totalChecked, completedItems.size());

            // If all items are completed, send notification
            if (stillInQueue == 0 && totalChecked > 0) {
                boolean sentAny = sendCompletionNotification(session, completedItems, superAdminRecipients);
                if (sentAny) {
                    session.setNotificationSent(true);
                    session.setNotificationSentAt(Instant.now());
                    log.info("Sent completion notification for session {}", session.getId());
                }
                session.setStatus("COMPLETED");
            }

            sessionRepo.save(session);

        } catch (Exception ex) {
            log.error("Error checking external queue for session {}: {}", session.getId(), ex.getMessage(), ex);
            sessionRepo.save(session); // Still update lastCheckedAt
        }
    }

    /**
     * Check if an item exists in the external DTP_SENDER_QUEUE_ITEM table
     */
    private boolean checkIfExistsInExternalQueue(Connection conn, String externalId) throws Exception {
        String sql = "SELECT COUNT(*) FROM DTP_SENDER_QUEUE_ITEM WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, externalId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    int count = rs.getInt(1);
                    return count > 0;
                }
            }
        }
        return false;
    }

    /**
     * Extract lot/wafer/filename information from payload (if available in future)
     * For now, returns empty strings as this metadata isn't stored in LoadSessionPayload yet
     */
    private String extractLotWaferFilename(LoadSessionPayload payload) {
        // TODO: Once lot/wafer/filename are added to LoadSessionPayload entity, extract them here
        // For now, return empty string or basic info
        return payload.getPayloadId();
    }

    /**
     * Send completion notification email to user
     */
    private boolean sendCompletionNotification(LoadSession session, List<PayloadCompletionInfo> completedItems, Set<String> superAdminRecipients) {
        String userRecipient = session.getUserEmail();
        String subjectUser = String.format("Reloader: Processing completed for sender %s", session.getSenderId());
        String subjectAdmin = String.format("Reloader: User session completed for sender %s", session.getSenderId());

        boolean attempted = false;

        if (userRecipient != null && !userRecipient.isBlank()) {
            attempted = true;
            String body = buildCompletionEmailBody(session, completedItems, false);
            try {
                mailService.send(userRecipient, subjectUser, body);
                log.info("Completion notification sent to {} for session {}", userRecipient, session.getId());
            } catch (Exception ex) {
                log.error("Failed to send completion notification to {} for session {}: {}",
                        userRecipient, session.getId(), ex.getMessage(), ex);
            }
        }

        if (superAdminRecipients != null && !superAdminRecipients.isEmpty()) {
            String body = buildCompletionEmailBody(session, completedItems, true);
            String userEmailNormalized = userRecipient == null ? "" : userRecipient.trim().toLowerCase();
            for (String recipient : superAdminRecipients) {
                if (recipient == null || recipient.isBlank()) continue;
                if (!userEmailNormalized.isEmpty() && recipient.trim().equalsIgnoreCase(userEmailNormalized)) {
                    continue;
                }
                attempted = true;
                try {
                    mailService.send(recipient, subjectAdmin, body);
                    log.info("Completion notification sent to super admin {} for session {}", recipient, session.getId());
                } catch (Exception ex) {
                    log.error("Failed to send super admin completion notification to {} for session {}: {}",
                            recipient, session.getId(), ex.getMessage(), ex);
                }
            }
        }

        if (!attempted) {
            log.warn("No recipients available for completion notification for session {}", session.getId());
        }

        return attempted;
    }

    private String buildCompletionEmailBody(LoadSession session, List<PayloadCompletionInfo> completedItems, boolean adminCopy) {
        StringBuilder body = new StringBuilder();
        if (adminCopy) {
            body.append("A user reloader session has completed processing.\n\n");
        } else {
            body.append("Your reloader session has completed processing.\n\n");
        }
        body.append("Session Details:\n");
        body.append(String.format("  - Sender ID: %s\n", session.getSenderId()));
        body.append(String.format("  - Site: %s\n", session.getSite()));
        body.append(String.format("  - Environment: %s\n", session.getEnvironment()));
        body.append(String.format("  - Initiated by: %s\n", session.getInitiatedBy()));
        body.append(String.format("  - User email: %s\n", session.getUserEmail() != null ? session.getUserEmail() : "N/A"));
        body.append(String.format("  - Items processed: %d\n", completedItems.size()));
        body.append(String.format("  - Requested at: %s\n", session.getCreatedAt()));
        body.append(String.format("  - Completed at: %s\n\n", Instant.now()));

        body.append("Processed Items:\n");
        body.append(String.format("%-20s %-20s %-40s %s\n", "MetadataID", "DataID", "Lot/Wafer/File", "Pushed At"));
        body.append("=".repeat(120)).append("\n");

        int maxItems = 100; // Limit email size
        int count = 0;
        for (PayloadCompletionInfo item : completedItems) {
            if (count++ >= maxItems) {
                body.append(String.format("... and %d more items (truncated)\n", completedItems.size() - maxItems));
                break;
            }
            body.append(String.format("%-20s %-20s %-40s %s\n",
                    truncate(item.metadataId, 20),
                    truncate(item.dataId, 20),
                    truncate(item.lotWaferFile, 40),
                    item.pushedAt != null ? item.pushedAt.toString() : "N/A"
            ));
        }

        body.append("\n\nThis is an automated notification from the Reloader application.\n");
        return body.toString();
    }

    private Set<String> loadSuperAdminRecipients() {
        try {
            List<AppUser> users = userRepository.findAll();
            Set<String> recipients = new LinkedHashSet<>();
            for (AppUser user : users) {
                if (user == null || user.getRoles() == null) continue;
                if (!user.isEnabled() || user.getStatus() != AppUser.UserStatus.ACTIVE) continue;
                boolean isSuperAdmin = user.getRoles().contains("SUPER_ADMIN") || user.getRoles().contains("ROLE_SUPER_ADMIN");
                if (!isSuperAdmin) continue;
                String email = user.getEmail();
                if (email == null || email.isBlank()) continue;
                recipients.add(email.trim());
            }
            return recipients;
        } catch (Exception ex) {
            log.warn("Failed to load super admin recipients: {}", ex.getMessage());
            return java.util.Set.of();
        }
    }

    private String truncate(String value, int maxLength) {
        if (value == null) return "";
        if (value.length() <= maxLength) return value;
        return value.substring(0, maxLength - 3) + "...";
    }

    /**
     * Internal record to hold completion info for notification
     */
    private static class PayloadCompletionInfo {
        final String metadataId;
        final String dataId;
        final String lotWaferFile;
        final Instant pushedAt;

        PayloadCompletionInfo(String metadataId, String dataId, String lotWaferFile, Instant pushedAt) {
            this.metadataId = metadataId;
            this.dataId = dataId;
            this.lotWaferFile = lotWaferFile;
            this.pushedAt = pushedAt;
        }
    }
}
