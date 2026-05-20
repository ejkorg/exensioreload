package com.onsemi.cim.apps.exensio.exensioreload.dto;

public record StageRecordsCsvRequest(
        String site,
        Integer senderId,
        String status,
        int size,
        String q
) {}
