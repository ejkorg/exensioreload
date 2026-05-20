package com.onsemi.cim.apps.exensio.resender.dto;

public record DashboardWaferBreakdown(
        String wafer,
        DashboardBucketTotals totals,
        String filename
) {}
