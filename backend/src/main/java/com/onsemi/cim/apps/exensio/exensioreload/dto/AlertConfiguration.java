package com.onsemi.cim.apps.exensio.exensioreload.dto;

import java.util.List;

public record AlertConfiguration(
        EmailNotification emailNotifications,
        WebhookNotification webhookNotifications,
        SlackNotification slackNotifications,
        String defaultSeverity,
        Integer retentionDays
) {
    public record EmailNotification(boolean enabled, List<String> recipients) {}

    public record WebhookNotification(boolean enabled, String url) {}

    public record SlackNotification(boolean enabled, String webhookUrl) {}
}
