package com.onsemi.cim.apps.exensio.exensioreload.dto;

import java.util.List;

public record DashboardSenderSnapshot(
        int senderId,
        String senderLabel,
        String senderName,
        DashboardMetricTotals metrics,
        boolean alert,
        List<DashboardLink> links
) {}
