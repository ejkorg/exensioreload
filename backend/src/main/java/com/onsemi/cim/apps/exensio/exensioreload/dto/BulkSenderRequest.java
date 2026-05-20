package com.onsemi.cim.apps.exensio.exensioreload.dto;

import java.util.List;

/**
 * Request body for bulk sender operations (resume / pause / export / delete).
 * All fields are optional so that the same record can be shared across all four endpoints,
 * with each controller method only reading what it needs.
 */

/**
 * @author fg8n8x
 */

public record BulkSenderRequest(
        /** Sender IDs to operate on — required for every bulk action. */
        List<Integer> senderIds,
        /** Export format: "csv" (default) or "excel". Used only by the export endpoint. */
        String format
) {}
