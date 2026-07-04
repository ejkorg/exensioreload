package com.onsemi.cim.apps.exensio.exensioreload.dto;

import java.util.List;

public record StagePayloadRequest(
        String site,
        String environment,
        Integer senderId,
    String senderName,
        List<Payload> payloads,
    boolean triggerDispatch,
    boolean forceDuplicates,
    String userEmail,  // Optional: email for completion notifications
    String requestId,
    String dataType,   // Optional: data type from stepper (e.g. PROBE, FT, DEFECT, MAP)
    String testPhase   // Optional: test phase from stepper (e.g. FT, QA, RG, CRSS)
) {
    public record Payload(String metadataId, String dataId, String lot, String wafer, String filename, String endTime, String device) {}
}
