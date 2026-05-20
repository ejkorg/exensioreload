package com.onsemi.cim.apps.exensio.exensioreload.dto;

public record DuplicatePayloadView(
        String metadataId,
        String dataId,
        String lot,
        String wafer,
        String filename,
        String previousStatus,
        String processedAt,
        String stagedBy,
        String stagedAt,
        String lastRequestedBy,
        String lastRequestedAt,
        boolean requiresConfirmation
) {}
