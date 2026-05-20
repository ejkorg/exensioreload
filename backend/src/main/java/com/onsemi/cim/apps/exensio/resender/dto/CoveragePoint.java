package com.onsemi.cim.apps.exensio.resender.dto;

public record CoveragePoint(
        String bucket,
        int senderId,
        String site,
        long total,
        long done,
        long enqueued,
        long staged,
        long failed
) {}
