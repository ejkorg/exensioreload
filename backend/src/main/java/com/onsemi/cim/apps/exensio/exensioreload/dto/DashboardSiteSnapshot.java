package com.onsemi.cim.apps.exensio.exensioreload.dto;

import java.util.List;

public record DashboardSiteSnapshot(
        String site,
        DashboardMetricTotals metrics,
        boolean alerts,
        List<DashboardSenderSnapshot> senders
) {}
