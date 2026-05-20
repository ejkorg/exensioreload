package com.onsemi.cim.apps.exensio.exensioreload.dto;

public record DashboardMetricTotals(
        long total,
        long ready,
        long enqueued,
        long failed,
        long completed,
        long backlog,
        long activeSenders,
        long activeUsers
) {
    public static DashboardMetricTotals empty() {
        return new DashboardMetricTotals(0, 0, 0, 0, 0, 0, 0, 0);
    }
}
