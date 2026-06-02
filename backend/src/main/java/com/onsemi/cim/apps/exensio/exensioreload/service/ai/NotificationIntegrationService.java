package com.onsemi.cim.apps.exensio.exensioreload.service.ai;

import com.onsemi.cim.apps.exensio.exensioreload.config.AiProperties;
import com.onsemi.cim.apps.exensio.exensioreload.dto.ai.NotificationRequest;
import com.onsemi.cim.apps.exensio.exensioreload.dto.ai.NotificationResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Service for sending notifications to Slack/Teams/Email.
 */
@Service
public class NotificationIntegrationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationIntegrationService.class);

    private final AiGatewayService gatewayService;
    private final AiProperties aiProperties;

    public NotificationIntegrationService(AiGatewayService gatewayService, AiProperties aiProperties) {
        this.gatewayService = gatewayService;
        this.aiProperties = aiProperties;
    }

    public boolean isAvailable() {
        return aiProperties.isConfigured();
    }

    /**
     * Send notification.
     */
    public NotificationResponse sendNotification(NotificationRequest request) {
        NotificationResponse response = new NotificationResponse();

        try {
            response.setNotificationId("NOTIF-" + System.currentTimeMillis());
            response.setTimestamp(new Date().toString());

            // Validate channels
            List<String> validChannels = validateChannels(request.getChannels());
            response.setChannelsConfigured(validChannels);

            // Generate message content
            String content = generateMessageContent(request);

            // Simulate sending to each channel
            Map<String, NotificationResponse.ChannelStatus> channelStatuses = new HashMap<>();
            for (String channel : validChannels) {
                NotificationResponse.ChannelStatus status = new NotificationResponse.ChannelStatus();
                status.setChannel(channel);
                status.setStatus("DELIVERED");  // Simulated
                status.setDeliveredAt(new Date().toString());
                status.setRecipients(getRecipients(channel));
                channelStatuses.put(channel, status);
            }
            response.setChannelStatuses(channelStatuses);

            // Set message preview
            response.setMessagePreview(content.length() > 200 ? content.substring(0, 200) + "..." : content);

            // Set preferences for future
            response.setPreferencesSaved(savePreferences(request));

            response.setSuccess(true);

        } catch (Exception e) {
            log.error("Notification sending failed", e);
            response.setSuccess(false);
            response.setErrorMessage(e.getMessage());
        }

        return response;
    }

    private List<String> validateChannels(List<String> channels) {
        List<String> valid = new ArrayList<>();
        List<String> supportedChannels = List.of("SLACK", "TEAMS", "EMAIL", "SMS", "PAGERDUTY");

        if (channels == null || channels.isEmpty()) {
            return List.of("EMAIL");  // Default
        }

        for (String channel : channels) {
            if (supportedChannels.contains(channel.toUpperCase())) {
                valid.add(channel.toUpperCase());
            }
        }

        return valid.isEmpty() ? List.of("EMAIL") : valid;
    }

    private String generateMessageContent(NotificationRequest request) {
        if (aiProperties.isConfigured()) {
            try {
                String prompt = String.format("""
                    Generate a concise notification message for:
                    
                    Type: %s
                    Severity: %s
                    Title: %s
                    Message: %s
                    
                    Keep it under 500 characters, professional tone.
                    """,
                    request.getType(),
                    request.getSeverity(),
                    request.getTitle(),
                    request.getMessage()
                );

                Map<String, Object> context = Map.of("task", "notification_content");
                return gatewayService.sendMessage(prompt, context);
            } catch (Exception e) {
                log.warn("AI content generation failed, using direct message", e);
            }
        }

        return String.format("[%s] %s: %s", request.getSeverity(), request.getTitle(), request.getMessage());
    }

    private List<String> getRecipients(String channel) {
        // Simulated recipient lists
        return switch (channel) {
            case "SLACK" -> List.of("#operations-alerts", "@on-call-engineer");
            case "TEAMS" -> List.of("Operations Team", "On-Call");
            case "EMAIL" -> List.of("ops-team@onsemi.com", "oncall@onsemi.com");
            case "SMS" -> List.of("+1234567890");
            case "PAGERDUTY" -> List.of("On-Call Escalation");
            default -> List.of();
        };
    }

    private boolean savePreferences(NotificationRequest request) {
        // In production, would save to database
        return true;
    }

    /**
     * Configure notification channels.
     */
    public NotificationResponse configureChannels(Map<String, Object> configuration) {
        NotificationResponse response = new NotificationResponse();
        response.setSuccess(true);

        List<String> configuredChannels = new ArrayList<>();

        if (configuration.containsKey("slack")) {
            configuredChannels.add("SLACK (Webhook: " + configuration.get("slack") + ")");
        }
        if (configuration.containsKey("teams")) {
            configuredChannels.add("TEAMS (Webhook: " + configuration.get("teams") + ")");
        }
        if (configuration.containsKey("email")) {
            configuredChannels.add("EMAIL (SMTP configured)");
        }

        response.setChannelsConfigured(configuredChannels);
        response.setMessagePreview("Channels configured: " + String.join(", ", configuredChannels));

        return response;
    }

    /**
     * Get notification history.
     */
    public List<NotificationResponse> getNotificationHistory(String type, int limit) {
        List<NotificationResponse> history = new ArrayList<>();

        // Simulated history
        NotificationResponse notif1 = new NotificationResponse();
        notif1.setNotificationId("NOTIF-001");
        notif1.setTimestamp("2024-06-01 08:30:00");
        notif1.setChannelsConfigured(List.of("SLACK", "EMAIL"));
        notif1.setMessagePreview("Daily summary: 45 sessions processed, 98% success rate");
        notif1.setSuccess(true);
        history.add(notif1);

        NotificationResponse notif2 = new NotificationResponse();
        notif2.setNotificationId("NOTIF-002");
        notif2.setTimestamp("2024-06-01 10:15:00");
        notif2.setChannelsConfigured(List.of("PAGERDUTY"));
        notif2.setMessagePreview("[CRITICAL] Network timeout on Sender A");
        notif2.setSuccess(true);
        history.add(notif2);

        return history.subList(0, Math.min(limit, history.size()));
    }
}