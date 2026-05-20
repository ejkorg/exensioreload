package com.onsemi.cim.apps.exensio.resender.stage;

/**
 * @author fg8n8x
 */

public record FileUpdateEvent(
        long id,
        String metadataId,
        String dataId,
        String lot,
        String wafer,
        String filename,
        String status,
        String displayStatus,
        String message,
        String updatedAt
) {}

