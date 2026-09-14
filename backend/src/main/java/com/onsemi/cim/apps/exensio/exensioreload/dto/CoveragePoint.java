package com.onsemi.cim.apps.exensio.exensioreload.dto;

public record CoveragePoint(
        String bucket,
        int senderId,
        String site,
        long total,
        long done,        // COMPLETED in PRODUCTION schema
        long doneSbx,     // COMPLETED in SANDBOX schema
        long donePending, // COMPLETED_MANUAL_VERIFICATION_REQUIRED — done but schema unconfirmed
        long enqueued,
        long staged,
        long failed
) {}
