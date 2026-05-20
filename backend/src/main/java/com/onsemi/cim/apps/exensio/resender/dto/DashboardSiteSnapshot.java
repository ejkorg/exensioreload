package com.onsemi.cim.apps.exensio.resender.dto;

import java.util.List;

public record DashboardSiteSnapshot(
        String site,
        DashboardMetricTotals metrics,
        boolean alerts,
        List<DashboardSenderSnapshot> senders
) {}
