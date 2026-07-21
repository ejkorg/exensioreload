package com.onsemi.cim.apps.exensio.exensioreload.dto;

import java.util.List;

/**
 * Result for a single lot in lot verification response.
 *
 * <p>Includes whether the lot was found and which schema it was located in
 * (e.g., "PRODUCTION", "SANDBOX", "FOUND" for HTTP fallback).</p>
 *
 * <p>For wafer-level classes (1, 4, 14) when only lot is provided, includes
 * a list of wafer IDs found in the schema so the UI can display which wafers exist.</p>
 */
public record LotVerificationResult(
        boolean found,
        String schema,  // null if not found; "PRODUCTION", "SANDBOX", or "FOUND" (HTTP)
        List<String> wafers  // wafer IDs found for this lot (wafer-level classes only)
) {}
