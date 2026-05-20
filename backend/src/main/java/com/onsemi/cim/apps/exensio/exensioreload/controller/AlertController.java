package com.onsemi.cim.apps.exensio.exensioreload.controller;

import com.onsemi.cim.apps.exensio.exensioreload.dto.AlertConfiguration;
import com.onsemi.cim.apps.exensio.exensioreload.dto.AlertThreshold;
import com.onsemi.cim.apps.exensio.exensioreload.dto.SenderAlert;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api/alerts")
public class AlertController {
    private final Map<Integer, AlertThreshold> thresholdsBySender = new ConcurrentHashMap<>();
    private volatile AlertConfiguration configuration = defaultConfiguration();

    @GetMapping("/sender/{senderId}/thresholds")
    public AlertThreshold getThresholds(@PathVariable int senderId) {
        return thresholdsBySender.getOrDefault(senderId, defaultThreshold(senderId));
    }

    @PutMapping("/sender/{senderId}/thresholds")
    public AlertThreshold updateThresholds(@PathVariable int senderId, @RequestBody AlertThreshold thresholds) {
        AlertThreshold resolved = thresholds == null
                ? defaultThreshold(senderId)
                : new AlertThreshold(
                        senderId,
                        thresholds.backlogThreshold(),
                        thresholds.failureRateThreshold(),
                        thresholds.enabled(),
                        thresholds.createdAt(),
                        thresholds.updatedAt()
                );
        thresholdsBySender.put(senderId, resolved);
        return resolved;
    }

    @GetMapping("/sender/{senderId}")
    public List<SenderAlert> getSenderAlerts(@PathVariable int senderId) {
        return Collections.emptyList();
    }

    @GetMapping("/configuration")
    public AlertConfiguration getConfiguration() {
        return configuration;
    }

    @PutMapping("/configuration")
    public AlertConfiguration updateConfiguration(@RequestBody AlertConfiguration config) {
        if (config != null) {
            configuration = config;
        }
        return configuration;
    }

    private static AlertThreshold defaultThreshold(int senderId) {
        return new AlertThreshold(senderId, 1000, 10, true, null, null);
    }

    private static AlertConfiguration defaultConfiguration() {
        return new AlertConfiguration(
                new AlertConfiguration.EmailNotification(false, List.of()),
                new AlertConfiguration.WebhookNotification(false, ""),
                new AlertConfiguration.SlackNotification(false, ""),
                null,
                null
        );
    }
}
