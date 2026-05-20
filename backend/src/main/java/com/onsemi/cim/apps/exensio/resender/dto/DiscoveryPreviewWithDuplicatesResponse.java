package com.onsemi.cim.apps.exensio.resender.dto;

import java.util.List;
import java.util.Map;
/**
 * @author fg8n8x

 * Combined response for preview that includes both preview rows and duplicate information.
 * This reduces the number of HTTP round-trips from 2 (preview + duplicates) to 1.
 */

public record DiscoveryPreviewWithDuplicatesResponse(
        List<DiscoveryPreviewRow> items,
        long total,
        // How many rows returned in this response
        int returned,
        int page,
        int size,
        String debugSql,
        boolean capped,
        // Whether the caller requested bypassing the cap
        boolean bypass,
        String message,
        /**
         * Map of duplicate payload info keyed by "metadataId|dataId".
         * Only entries that have duplicates are included.
         */
        Map<String, DuplicatePayloadView> duplicates,
        long previewDurationMs,
        long duplicateDurationMs,
        String discoveryToken
) {}
