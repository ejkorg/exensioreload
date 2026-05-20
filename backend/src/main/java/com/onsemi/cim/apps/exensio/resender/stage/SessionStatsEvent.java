package com.onsemi.cim.apps.exensio.resender.stage;

/**
 * @author fg8n8x
 */

public record SessionStatsEvent(
        int total,
        int staged,
        int enqueued,
        int done,
        int failed,
        double progress,
        double throughput,
        int eta,
        double successRate
) {}

