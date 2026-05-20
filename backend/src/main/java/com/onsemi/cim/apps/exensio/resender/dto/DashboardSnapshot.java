package com.onsemi.cim.apps.exensio.resender.dto;

import java.time.Instant;
import java.util.List;

public record DashboardSnapshot(
        Instant generatedAt,
        DashboardMetricTotals global,
        List<DashboardSiteSnapshot> sites
) {}
