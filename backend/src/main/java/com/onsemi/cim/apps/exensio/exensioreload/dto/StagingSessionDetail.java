package com.onsemi.cim.apps.exensio.exensioreload.dto;

/**
 * @author fg8n8x
 */

import java.util.Map;

public record StagingSessionDetail(
        String sessionId,
        String username,
        String site,
        int senderId,
        String senderName,
        String environment,
        String status,
        long totalFiles,
        long filesStaged,
        long filesEnqueued,
        long filesDone,
        long filesFailed,
        String createdAt,
        String updatedAt,
        String completedAt,
        String lastCheckedAt,
        double progress,
        Map<String, Object> integration
) {
}

