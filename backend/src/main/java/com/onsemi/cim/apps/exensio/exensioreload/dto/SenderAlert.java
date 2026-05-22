package com.onsemi.cim.apps.exensio.exensioreload.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record SenderAlert(
        String alertId,
        int senderId,
        String senderName,
        String alertType,
        int threshold,
        int currentValue,
        String severity,
        @JsonProperty("triggered_at") String triggeredAt,
        boolean acknowledged,
        @JsonProperty("acknowledged_by") String acknowledgedBy,
        @JsonProperty("acknowledged_at") String acknowledgedAt
) {}
