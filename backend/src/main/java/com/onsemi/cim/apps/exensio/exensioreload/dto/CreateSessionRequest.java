package com.onsemi.cim.apps.exensio.exensioreload.dto;

/**
 * @author fg8n8x
 */

public record CreateSessionRequest(
        String site,
        Integer senderId,
        String senderName,
        String environment
) {
}

