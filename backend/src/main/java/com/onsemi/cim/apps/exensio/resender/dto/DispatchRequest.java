package com.onsemi.cim.apps.exensio.resender.dto;

public record DispatchRequest(
        String site,
        Integer senderId,
        Integer limit
) {}
