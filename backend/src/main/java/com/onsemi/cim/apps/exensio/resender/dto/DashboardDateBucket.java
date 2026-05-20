package com.onsemi.cim.apps.exensio.resender.dto;

import java.time.Instant;

/**
 * Aggregated status totals for a specific time bucket.
 */
public record DashboardDateBucket(
        Instant bucketStart,
        String label,
        DashboardBucketTotals totals
) {}
