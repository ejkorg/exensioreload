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
    String requestId
) {
    public record Payload(String metadataId, String dataId, String lot, String wafer, String filename, String endTime) {}
}
