package com.onsemi.cim.apps.exensio.exensioreload.stage;

/**
 * @author fg8n8x
 */

public record SessionStatusEvent(
        String status,
        String completedAt,
        String message
) {}

