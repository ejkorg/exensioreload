package com.onsemi.cim.apps.exensio.exensioreload.dto;

public record DispatchRequest(
        String site,
        Integer senderId,
        Integer limit
) {}
