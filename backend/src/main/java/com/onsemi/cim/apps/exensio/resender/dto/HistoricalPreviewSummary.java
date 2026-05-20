package com.onsemi.cim.apps.exensio.resender.dto;

/**
 * Summary-only response for historical previews (count + oldest/newest end_time).
 */
public record HistoricalPreviewSummary(
        long total,
        String oldestEndTime,
        String latestEndTime,
        String message
) {
}
