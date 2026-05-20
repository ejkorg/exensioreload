package com.onsemi.cim.apps.exensio.resender.stage;

/**
 * @author fg8n8x
 */

public record SessionStatusEvent(
        String status,
        String completedAt,
        String message
) {}

