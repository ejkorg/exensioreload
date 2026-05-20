package com.onsemi.cim.apps.exensio.resender.dto;

import java.util.List;

public record DashboardLotBreakdown(
        String lot,
        DashboardBucketTotals totals,
        List<DashboardWaferBreakdown> wafers
) {}
