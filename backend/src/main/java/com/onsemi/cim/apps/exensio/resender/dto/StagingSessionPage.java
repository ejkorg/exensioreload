package com.onsemi.cim.apps.exensio.resender.dto;

import java.util.List;

/**
 * @author fg8n8x
 */

public record StagingSessionPage(
        List<StagingSessionSummary> items,
        long total,
        int page,
        int size
) {
}
