package com.onsemi.cim.apps.exensio.exensioreload.dto;

public record ActiveStagingSession(
    String requestId,
    String site,
    int senderId,
    String senderName,
    String status, // READY, STAGING, COMPLETED, ERROR
    int totalFiles,
    int completedFiles,
    int failedFiles,
    double progress,
    String startTime,
    String lastActivity,
    String estimatedTimeRemaining,
    boolean historicalMode,
    String username
) {}
