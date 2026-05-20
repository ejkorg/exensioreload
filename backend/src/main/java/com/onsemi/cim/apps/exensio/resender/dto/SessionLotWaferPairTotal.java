package com.onsemi.cim.apps.exensio.resender.dto;

/**
 * @author fg8n8x
 */
public record SessionLotWaferPairTotal(
        String lot,
        String wafer,
        long total
) {
}
