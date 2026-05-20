package com.onsemi.cim.apps.exensio.resender.dto;

public record DispatchResponse(
        String site,
        Integer senderId,
        int dispatched
) {}
