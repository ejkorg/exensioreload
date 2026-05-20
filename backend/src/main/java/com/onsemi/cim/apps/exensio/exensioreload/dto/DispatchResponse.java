package com.onsemi.cim.apps.exensio.exensioreload.dto;

public record DispatchResponse(
        String site,
        Integer senderId,
        int dispatched
) {}
