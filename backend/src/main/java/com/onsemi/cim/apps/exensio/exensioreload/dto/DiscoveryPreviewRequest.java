package com.onsemi.cim.apps.exensio.exensioreload.dto;

import java.util.List;

public record DiscoveryPreviewRequest(
        String site,
        String environment,
        String startDate,
        String endDate,
        List<String> lots,
        List<String> wafers,
        List<String> devices,
        List<com.onsemi.cim.apps.exensio.exensioreload.dto.DiscoveryPreviewPair> pairs,
        String testerType,
        String dataType,
        String dataTypeExt,
        String testPhase,
        String location,
        int page,
        int size,
        // When true, allow the server to bypass the default preview row cap (2000)
        // and return up to the requested page/size. Clients should use this
        // cautiously as unbounded previews may be slow for large datasets.
        boolean bypassCap,
        // When true (historical mode), strict filters apply: include testerType,
        // dataTypeExt, testPhase, and location when a date range is provided.
        boolean historicalMode,
        Long locationId,
        String requestId,
        // New filter fields for dtp_*_metadata tables
        List<String> steps,
        List<String> recipes,       // maps to test_program column
        List<String> equipmentIds   // maps to tester_id column
) {}
