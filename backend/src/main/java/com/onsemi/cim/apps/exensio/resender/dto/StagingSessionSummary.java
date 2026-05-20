package com.onsemi.cim.apps.exensio.resender.dto;

/**
 * @author fg8n8x
 */

public record StagingSessionSummary(
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
        double progress
) {
}
