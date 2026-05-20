package com.onsemi.cim.apps.exensio.exensioreload.stage;

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

