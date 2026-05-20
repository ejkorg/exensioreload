package com.onsemi.cim.apps.exensio.exensioreload.stage;

import java.time.Instant;

public record DuplicatePayload(
        String metadataId,
        String dataId,
        String lot,
        String wafer,
        String filename,
        String previousStatus,
        Instant previousProcessedAt,
        String stagedBy,
        Instant stagedAt,
        String lastRequestedBy,
        Instant lastRequestedAt,
        boolean requiresConfirmation
) {
}
