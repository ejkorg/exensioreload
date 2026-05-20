package com.onsemi.cim.apps.exensio.resender.dto;

import com.onsemi.cim.apps.exensio.resender.service.TriggerResult;

public record CreateSessionResponse(String sessionId, String requestId, String status, String message) {
    public CreateSessionResponse(String sessionId) {
        this(sessionId, null, null, null);
    }

    public static CreateSessionResponse fromTrigger(String sessionId, TriggerResult result) {
        return new CreateSessionResponse(
                sessionId,
                sessionId,  // requestId is the sessionId
                result != null ? result.getStatus() : null,
                result != null ? result.getMessage() : null
        );
    }
}
