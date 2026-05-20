package com.onsemi.cim.apps.exensio.resender.dto;

/**
 * @author fg8n8x
 */
public record SessionLotWaferDailyPoint(
        String day,
        String lot,
        String wafer,
        long count
) {
}
