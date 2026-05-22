package com.onsemi.cim.apps.exensio.exensioreload.dto;

public record AlertThreshold(
        int senderId,
        int backlogThreshold,
        int failureRateThreshold,
        boolean enabled,
        String createdAt,
        String updatedAt
) {}
