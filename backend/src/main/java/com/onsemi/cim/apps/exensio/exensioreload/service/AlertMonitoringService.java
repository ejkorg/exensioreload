package com.onsemi.cim.apps.exensio.exensioreload.service;

import com.onsemi.cim.apps.exensio.exensioreload.dto.AlertThreshold;
import com.onsemi.cim.apps.exensio.exensioreload.entity.Alert;
import com.onsemi.cim.apps.exensio.exensioreload.repository.AlertRepository;
import com.onsemi.cim.apps.exensio.exensioreload.stage.StageRecord;
import com.onsemi.cim.apps.exensio.exensioreload.stage.StageStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Monitors sender metrics and generates alerts when thresholds are breached.
 *
 * Runs periodically to check backlog counts, failure rates, and timeout states
 * against configured thresholds for each sender.
 */
@Service
public class AlertMonitoringService {

    private static final Logger log = LoggerFactory.getLogger(AlertMonitoringService.class);

    private final AlertRepository alertRepository;
    private final RefDbService refDbService;
    private final AlertNotificationService notificationService;

    // In-memory threshold storage (senderId -> threshold)
    private final Map<Integer, AlertThreshold> thresholdsBySender = new ConcurrentHashMap<>();

    public AlertMonitoringService(AlertRepository alertRepository,
                                   RefDbService refDbService,
                                   AlertNotificationService notificationService) {
        this.alertRepository = alertRepository;
        this.refDbService = refDbService;
        this.notificationService = notificationService;
    }

    /**
     * Check sender metrics against thresholds every 60 seconds.
     */
    @Scheduled(fixedRate = 60_000)
    public void checkThresholds() {
        try {
            List<StageStatus> statuses = refDbService.fetchStatuses(null);
            if (statuses == null || statuses.isEmpty()) {
                return;
            }

            for (StageStatus status : statuses) {
                AlertThreshold threshold = thresholdsBySender.get(status.senderId());
                if (threshold == null || !threshold.enabled()) {
                    continue;
                }

                // Check backlog threshold
                checkBacklogThreshold(status, threshold);

                // Check failure rate threshold
                checkFailureRateThreshold(status, threshold);
            }
        } catch (Exception e) {
            log.error("Error checking alert thresholds: {}", e.getMessage(), e);
        }
    }

    private void checkBacklogThreshold(StageStatus status, AlertThreshold threshold) {
        long backlog = status.backlog();
        int thresholdValue = threshold.backlogThreshold() > 0 ? threshold.backlogThreshold() : 1000;

        if (backlog >= thresholdValue) {
            String alertType = "BACKLOG_THRESHOLD";
            // Don't create duplicate active alerts
            if (!alertRepository.existsBySenderIdAndAlertTypeAndStatus(status.senderId(), alertType, "ACTIVE")) {
                String severity = backlog >= thresholdValue * 2L ? "CRITICAL" : "WARNING";
                String message = String.format("Sender %d backlog (%d) exceeds threshold (%d)",
                        status.senderId(), backlog, thresholdValue);

                Alert alert = createAlert(status, alertType, severity, thresholdValue, (int) backlog, message);
                notificationService.sendNotification(alert);
            }
        }
    }

    private void checkFailureRateThreshold(StageStatus status, AlertThreshold threshold) {
        long total = status.total();
        long failed = status.totalFailed();

        if (total < 10) return; // Not enough data

        double failureRate = (failed * 100.0) / total;
        int thresholdValue = threshold.failureRateThreshold() > 0 ? threshold.failureRateThreshold() : 10;

        if (failureRate >= thresholdValue) {
            String alertType = "FAILURE_RATE";
            if (!alertRepository.existsBySenderIdAndAlertTypeAndStatus(status.senderId(), alertType, "ACTIVE")) {
                String severity = failureRate >= thresholdValue * 2.5 ? "CRITICAL" : "WARNING";
                String message = String.format("Sender %d failure rate (%.1f%%) exceeds threshold (%d%%)",
                        status.senderId(), failureRate, thresholdValue);

                Alert alert = createAlert(status, alertType, severity, thresholdValue, (int) failureRate, message);
                notificationService.sendNotification(alert);
            }
        }
    }

    private Alert createAlert(StageStatus status, String alertType, String severity,
                               int threshold, int currentValue, String message) {
        Alert alert = new Alert();
        alert.setAlertId(UUID.randomUUID().toString());
        alert.setSenderId(status.senderId());
        alert.setSenderName(status.senderName());
        alert.setSite(status.site());
        alert.setAlertType(alertType);
        alert.setSeverity(severity);
        alert.setStatus("ACTIVE");
        alert.setThreshold(threshold);
        alert.setCurrentValue(currentValue);
        alert.setMessage(message);
        alert.setTriggeredAt(Instant.now());

        Alert saved = alertRepository.save(alert);
        log.info("Alert created: {} - {} ({})", saved.getAlertId(), alertType, severity);
        return saved;
    }

    /**
     * Update threshold for a sender.
     */
    public AlertThreshold updateThreshold(int senderId, AlertThreshold threshold) {
        AlertThreshold resolved = new AlertThreshold(
                senderId,
                threshold.backlogThreshold() > 0 ? threshold.backlogThreshold() : 1000,
                threshold.failureRateThreshold() > 0 ? threshold.failureRateThreshold() : 10,
                threshold.enabled(),
                threshold.createdAt(),
                Instant.now().toString()
        );
        thresholdsBySender.put(senderId, resolved);
        return resolved;
    }

    /**
     * Get threshold for a sender.
     */
    public AlertThreshold getThreshold(int senderId) {
        return thresholdsBySender.getOrDefault(senderId, defaultThreshold(senderId));
    }

    /**
     * Acknowledge an alert.
     */
    public Optional<Alert> acknowledgeAlert(String alertId, String acknowledgedBy) {
        return alertRepository.findByAlertId(alertId).map(alert -> {
            alert.setStatus("ACKNOWLEDGED");
            alert.setAcknowledgedBy(acknowledgedBy);
            alert.setAcknowledgedAt(Instant.now());
            return alertRepository.save(alert);
        });
    }

    /**
     * Resolve an alert.
     */
    public Optional<Alert> resolveAlert(String alertId, String resolvedBy, String note) {
        return alertRepository.findByAlertId(alertId).map(alert -> {
            alert.setStatus("RESOLVED");
            alert.setResolvedBy(resolvedBy);
            alert.setResolvedAt(Instant.now());
            alert.setResolutionNote(note);
            return alertRepository.save(alert);
        });
    }

    private AlertThreshold defaultThreshold(int senderId) {
        return new AlertThreshold(senderId, 1000, 10, true, null, null);
    }
}
