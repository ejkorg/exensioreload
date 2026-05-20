package com.onsemi.cim.apps.exensio.resender.dto;
/**
 * @author fg8n8x
 */
public record LotWaferProgress(
        String lot,
        String wafer,
        long totalFiles,
        long doneFiles,
        long failedFiles,
        String status
) {
}

