package com.onsemi.cim.apps.exensio.exensioreload.dto;

public record DashboardWaferBreakdown(
        String wafer,
        DashboardBucketTotals totals,
        String filename
) {}
