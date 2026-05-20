package com.onsemi.cim.apps.exensio.resender.dto;

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

