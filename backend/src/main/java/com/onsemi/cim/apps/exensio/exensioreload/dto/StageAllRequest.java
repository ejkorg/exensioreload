package com.onsemi.cim.apps.exensio.exensioreload.dto;

import java.util.List;

public record StageAllRequest(
        String site,
        String environment,
        Integer senderId,
        String senderName,
        String startDate,
        String endDate,
        List<String> lots,
        List<String> wafers,
        List<String> devices,
        List<DiscoveryPreviewPair> pairs,
        String testerType,
        String dataType,
        String dataTypeExt,
        String testPhase,
        String location,
        Long locationId,
        Integer startPage,
        Integer pageSize,
        Integer maxRows,
        boolean historicalMode,
        boolean bypassCap,
        boolean triggerDispatch,
        boolean forceDuplicates,
        String userEmail,
        String requestId,
        String discoveryToken,
        // New filter fields for dtp_*_metadata tables
        List<String> steps,
        List<String> recipes,       // maps to test_program column
        List<String> equipmentIds   // maps to tester_id column
) {
}
