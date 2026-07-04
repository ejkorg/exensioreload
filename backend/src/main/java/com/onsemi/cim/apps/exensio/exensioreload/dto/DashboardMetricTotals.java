package com.onsemi.cim.apps.exensio.exensioreload.dto;

public record DashboardMetricTotals(
        long total,
        long ready,
        long queued,
        long enriching,
        long exensioLoading,
        long failed,
        long completed,
        long cancelled,
        long backlog,
        long activeSenders,
        long activeUsers
) {
    public static DashboardMetricTotals empty() {
        return new DashboardMetricTotals(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    /**
     * Backward compatibility: compute enqueued as queued + enriching + exensioLoading
     * This allows existing frontend code to continue working with the old field.
     */
    public long enqueued() {
        return queued + enriching + exensioLoading;
    }

    /**
     * Calculate the sum of all state counts for accounting verification.
     * Should equal total if all records are in valid states.
     */
    public long accountingSum() {
        return ready + queued + enriching + exensioLoading + failed + completed + cancelled;
    }
}
