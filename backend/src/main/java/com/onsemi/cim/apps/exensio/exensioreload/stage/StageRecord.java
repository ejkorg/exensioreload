package com.onsemi.cim.apps.exensio.exensioreload.stage;

import java.time.Instant;

public record StageRecord(
        long id,
        String site,
        int senderId,
        String senderName,
        String metadataId,
        String dataId,
        String lot,
        String wafer,
        String device,
        String filename,
        java.time.Instant endTime,
        String status,
        String errorMessage,
        Instant createdAt,
        Instant updatedAt,
        Instant enrichmentStartedAt,
        Instant processedAt,
        String stagedBy,
        String lastRequestedBy,
        Instant lastRequestedAt,
        String requestId,
        String cpOutputPath,
        String cpOutputTarget,
        Long exensioWaferKey,
        Long exensioPgKey,
        String dataType,
        String testPhase
) {}
