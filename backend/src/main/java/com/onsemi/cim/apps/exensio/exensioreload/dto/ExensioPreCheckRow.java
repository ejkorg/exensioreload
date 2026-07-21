package com.onsemi.cim.apps.exensio.exensioreload.dto;

/**
 * A single row returned by the Exensio pre-check query.
 *
 * <p>{@code schemaName} holds the Exensio schema (e.g. {@code "PRODUCTION"},
 * {@code "SANDBOX"}) for lots found in Snowflake, or {@code "FOUND"} for lots
 * surfaced via the Exensio HTTP fallback (which does not expose a schema name).
 * The value {@code "NOT FOUND"} is used as an internal sentinel inside
 * {@link com.onsemi.cim.apps.exensio.exensioreload.service.ExensioPreCheckService}
 * but is filtered out before the response is returned to the caller.</p>
 *
 * <p>{@code waferId} is populated for wafer-level classes (1, 4, 14) when only lot 
 * is provided in the request, allowing the UI to show which wafers exist in the schema.</p>
 */
public record ExensioPreCheckRow(
        String lotId,
        String schemaName,
        String waferId  // wafer ID for wafer-level classes; null or empty for lot-level
) {}
