package com.onsemi.cim.apps.exensio.exensioreload.service;

import com.onsemi.cim.apps.exensio.exensioreload.dto.AlertConfiguration;
import com.onsemi.cim.apps.exensio.exensioreload.entity.Alert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sends alert notifications via configured channels (email, webhook, Slack).
 */
@Service
public class AlertNotificationService {

    private static final Logger log = LoggerFactory.getLogger(AlertNotificationService.class);

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private volatile AlertConfiguration configuration = defaultConfiguration();
    private final ConcurrentHashMap<String, Long> lastNotificationTime = new ConcurrentHashMap<>();
    private static final long NOTIFICATION_COOLDOWN_MS = 300_000; // 5 minutes per alert type

    /**
     * Send notification for an alert via all configured channels.
     */
    public void sendNotification(Alert alert) {
        if (configuration == null) {
            return;
        }

        // Rate limiting: don't send duplicate notifications within cooldown
        String key = alert.getSenderId() + ":" + alert.getAlertType();
        long now = System.currentTimeMillis();
        Long lastTime = lastNotificationTime.get(key);
        if (lastTime != null && (now - lastTime) < NOTIFICATION_COOLDOWN_MS) {
            log.debug("Skipping notification for {} (cooldown)", key);
            return;
        }
        lastNotificationTime.put(key, now);

        // Send via configured channels
        if (configuration.emailNotifications() != null && configuration.emailNotifications().enabled()) {
            sendEmailNotification(alert);
        }

        if (configuration.webhookNotifications() != null && configuration.webhookNotifications().enabled()) {
            sendWebhookNotification(alert);
        }

        if (configuration.slackNotifications() != null && configuration.slackNotifications().enabled()) {
            sendSlackNotification(alert);
        }
    }

    private void sendEmailNotification(Alert alert) {
        // Email sending would require JavaMailSender configuration
        // For now, log the intent
        log.info("EMAIL ALERT: {} - {} (Sender: {}, Severity: {})",
                alert.getAlertType(), alert.getMessage(), alert.getSenderId(), alert.getSeverity());
    }

    private void sendWebhookNotification(Alert alert) {
        String url = configuration.webhookNotifications().url();
        if (url == null || url.isBlank()) {
            return;
        }

        try {
            String json = String.format(
                    "{\"alertId\":\"%s\",\"senderId\":%d,\"type\":\"%s\",\"severity\":\"%s\",\"message\":\"%s\",\"timestamp\":\"%s\"}",
                    alert.getAlertId(),
                    alert.getSenderId(),
                    alert.getAlertType(),
                    alert.getSeverity(),
                    alert.getMessage().replace("\"", "\\\""),
                    alert.getTriggeredAt()
            );

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .timeout(Duration.ofSeconds(5))
                    .build();

            httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenAccept(response -> {
                        if (response.statusCode() >= 200 && response.statusCode() < 300) {
                            log.debug("Webhook notification sent successfully to {}", url);
                        } else {
                            log.warn("Webhook notification failed: HTTP {}", response.statusCode());
                        }
                    })
                    .exceptionally(e -> {
                        log.error("Webhook notification error: {}", e.getMessage());
                        return null;
                    });
        } catch (Exception e) {
            log.error("Failed to send webhook notification: {}", e.getMessage());
        }
    }

    private void sendSlackNotification(Alert alert) {
        String webhookUrl = configuration.slackNotifications().webhookUrl();
        if (webhookUrl == null || webhookUrl.isBlank()) {
            return;
        }

        try {
            String emoji = "CRITICAL".equals(alert.getSeverity()) ? ":red_circle:" : ":warning:";
            String json = String.format(
                    "{\"text\":\"%s Alert\",\"blocks\":[{\"type\":\"section\",\"text\":{\"type\":\"mrkdwn\",\"text\":\"%s *%n*Sender %d*%n*%s*%n%n%s\"}}]}",
                    emoji,
                    emoji,
                    alert.getSenderId(),
                    alert.getSeverity(),
                    alert.getAlertType(),
                    alert.getMessage()
            );

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(webhookUrl))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .timeout(Duration.ofSeconds(5))
                    .build();

            httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenAccept(response -> {
                        if (response.statusCode() == 200) {
                            log.debug("Slack notification sent successfully");
                        } else {
                            log.warn("Slack notification failed: HTTP {}", response.statusCode());
                        }
                    })
                    .exceptionally(e -> {
                        log.error("Slack notification error: {}", e.getMessage());
                        return null;
                    });
        } catch (Exception e) {
            log.error("Failed to send Slack notification: {}", e.getMessage());
        }
    }

    public void updateConfiguration(AlertConfiguration config) {
        this.configuration = config;
    }

    public AlertConfiguration getConfiguration() {
        return configuration;
    }

    private static AlertConfiguration defaultConfiguration() {
        return new AlertConfiguration(
                new AlertConfiguration.EmailNotification(false, java.util.List.of()),
                new AlertConfiguration.WebhookNotification(false, ""),
                new AlertConfiguration.SlackNotification(false, ""),
                null,
                null
        );
    }
}
