package com.onsemi.cim.apps.exensio.exensioreload.dto;

import java.util.List;

/**
 * Request DTO for Exensio pre-flight lot existence verification.
 *
 * <p>{@code enableSnowflakeFallback} field allows runtime control of Snowflake fallback behavior:
 * <ul>
 *   <li>null (default): Use configuration default (exensio.enable-snowflake-secondary)</li>
 *   <li>true: Force Snowflake fallback enabled for this request</li>
 *   <li>false: Force Snowflake fallback disabled for this request</li>
 * </ul>
 *
 * <p>Requirements: 5.1, 5.2</p>
 */
public record ExensioPreCheckRequest(
        String environment,
        List<String> lotIds,
        List<String> waferIds,  // optional wafer IDs for wafer-level checking (Class 1, 4, 5, 14)
        List<PreCheckBlock> blocks,
        String dataType,  // for PGC_KEY resolution
        Boolean enableSnowflakeFallback  // NEW: runtime override for Snowflake fallback flag; null = use config default
) {
    /**
     * Determines whether Snowflake secondary fallback should be used for this request.
     * If enableSnowflakeFallback is null, delegates to the provided configuration default.
     *
     * @param configDefault the default from configuration (exensio.enable-snowflake-secondary)
     * @return true if Snowflake should be attempted as fallback
     */
    public boolean shouldEnableSnowflakeFallback(boolean configDefault) {
        if (enableSnowflakeFallback == null) {
            return configDefault;
        }
        return enableSnowflakeFallback;
    }
}
