package com.onsemi.cim.apps.exensio.resender.stage;

/**
 * @author fg8n8x
 */

public record LotUpdateEvent(
        String lot,
        int totalWafers,
        int completedWafers,
        int failedWafers,
        double progress
) {}

