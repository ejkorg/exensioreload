package com.onsemi.cim.apps.exensio.exensioreload.controller;

import com.onsemi.cim.apps.exensio.exensioreload.dto.AlertConfiguration;
import com.onsemi.cim.apps.exensio.exensioreload.dto.AlertThreshold;
import com.onsemi.cim.apps.exensio.exensioreload.entity.Alert;
import com.onsemi.cim.apps.exensio.exensioreload.repository.AlertRepository;
import com.onsemi.cim.apps.exensio.exensioreload.service.AlertMonitoringService;
import com.onsemi.cim.apps.exensio.exensioreload.service.AlertNotificationService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/alerts")
public class AlertController {

    private final AlertRepository alertRepository;
    private final AlertMonitoringService monitoringService;
    private final AlertNotificationService notificationService;

    public AlertController(AlertRepository alertRepository,
                           AlertMonitoringService monitoringService,
                           AlertNotificationService notificationService) {
        this.alertRepository = alertRepository;
        this.monitoringService = monitoringService;
        this.notificationService = notificationService;
    }

    /**
     * Get alerts with optional filtering.
     */
    @PreAuthorize("isAuthenticated()")
    @GetMapping
    public Page<Alert> getAlerts(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) String site,
            @RequestParam(required = false) Integer senderId,
            @RequestParam(required = false) Instant since,
            Pageable pageable) {
        return alertRepository.findWithFilters(status, severity, site, senderId, since, pageable);
    }

    /**
     * Get active alerts count.
     */
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/count")
    public Map<String, Long> getActiveAlertCount() {
        Map<String, Long> counts = new HashMap<>();
        counts.put("active", alertRepository.countByStatus("ACTIVE"));
        counts.put("critical", alertRepository.countByStatusAndSeverity("ACTIVE", "CRITICAL"));
        counts.put("warning", alertRepository.countByStatusAndSeverity("ACTIVE", "WARNING"));
        return counts;
    }

    /**
     * Acknowledge an alert.
     */
    @PreAuthorize("isAuthenticated()")
    @PostMapping("/{alertId}/acknowledge")
    public ResponseEntity<Alert> acknowledgeAlert(@PathVariable String alertId,
                                                   @RequestBody Map<String, String> body) {
        String acknowledgedBy = body.getOrDefault("acknowledgedBy", "Unknown");
        Optional<Alert> alert = monitoringService.acknowledgeAlert(alertId, acknowledgedBy);
        return alert.map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Resolve an alert.
     */
    @PreAuthorize("isAuthenticated()")
    @PostMapping("/{alertId}/resolve")
    public ResponseEntity<Alert> resolveAlert(@PathVariable String alertId,
                                               @RequestBody Map<String, String> body) {
        String resolvedBy = body.getOrDefault("resolvedBy", "Unknown");
        String note = body.getOrDefault("note", "");
        Optional<Alert> alert = monitoringService.resolveAlert(alertId, resolvedBy, note);
        return alert.map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Get thresholds for a sender.
     */
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/sender/{senderId}/thresholds")
    public AlertThreshold getThresholds(@PathVariable int senderId) {
        return monitoringService.getThreshold(senderId);
    }

    /**
     * Update thresholds for a sender.
     * Requires ADMIN or SUPER_ADMIN role.
     */
    @PreAuthorize("hasRole('ADMIN') or hasRole('ROLE_ADMIN') or hasRole('ROLE_SUPER_ADMIN')")
    @PutMapping("/sender/{senderId}/thresholds")
    public AlertThreshold updateThresholds(@PathVariable int senderId,
                                            @RequestBody AlertThreshold thresholds) {
        return monitoringService.updateThreshold(senderId, thresholds);
    }

    /**
     * Get global alert configuration.
     */
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/configuration")
    public AlertConfiguration getConfiguration() {
        return notificationService.getConfiguration();
    }

    /**
     * Update global alert configuration.
     * Requires ADMIN or SUPER_ADMIN role.
     */
    @PreAuthorize("hasRole('ADMIN') or hasRole('ROLE_ADMIN') or hasRole('ROLE_SUPER_ADMIN')")
    @PutMapping("/configuration")
    public AlertConfiguration updateConfiguration(@RequestBody AlertConfiguration config) {
        if (config != null) {
            notificationService.updateConfiguration(config);
        }
        return notificationService.getConfiguration();
    }
}
