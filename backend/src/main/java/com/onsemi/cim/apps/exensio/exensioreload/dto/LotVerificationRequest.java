package com.onsemi.cim.apps.exensio.exensioreload.dto;

import java.util.List;

public record LotVerificationRequest(
        List<String> lots,
        String site,
        String environment,
        String dataType,
        List<String> wafers, // Optional: specific wafers to check (bypasses discovery)
        Boolean enableSnowflakeFallback, // Optional: enable Snowflake secondary fallback
        List<String> filenames // Optional: filenames for raw-SQL filename prefix matching (first 15 chars)
) {}
