package com.onsemi.cim.apps.exensio.exensioreload.dto;

import com.onsemi.cim.apps.exensio.exensioreload.stage.PipelineStatus;

public record DashboardMetricTotals(
        long total,
        long stagedToRefdb,
        long queuedForCp,
        long elasticsearchMonitoring,
        long cpTimeout,
        long exensioMonitoring,
        long completedManualVerification,
        long cpFailed,
        long loadFailed,
        long completed,
        long cancelled,
        long backlog,
        long activeSenders,
        long activeUsers
) {
    public static DashboardMetricTotals empty() {
        return new DashboardMetricTotals(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    /** All failure states combined (for backward-compatible display). */
    public long failed() {
        return cpFailed + loadFailed;
    }

    /** All in-flight monitoring states (for backward-compatible display). */
    public long enqueued() {
        return queuedForCp + elasticsearchMonitoring + exensioMonitoring;
    }

    public long accountingSum() {
        return stagedToRefdb + queuedForCp + elasticsearchMonitoring
            + cpTimeout + exensioMonitoring + completedManualVerification
            + cpFailed + loadFailed + completed + cancelled;
    }
}
