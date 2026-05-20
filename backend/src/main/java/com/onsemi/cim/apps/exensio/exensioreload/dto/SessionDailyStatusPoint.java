package com.onsemi.cim.apps.exensio.exensioreload.dto;

/**
 * @author fg8n8x
 */

public record SessionDailyStatusPoint(
        String day,
        long total,
        long done,
        long enqueued,
        long failed,
        long cancelled,
        long staged
) {
}

