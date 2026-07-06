package com.onsemi.cim.apps.exensio.exensioreload.dto;

/**
 * Dashboard metric totals sent to the frontend.
 *
 * Jackson serializes both the canonical v3.0 field names AND the legacy
 * field names simultaneously so the frontend can read whichever it expects.
 * This avoids needing to rename every frontend access across dozens of components.
 */
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

    // ── Legacy field names (Jackson serializes these alongside the record components) ──

    public long getReady() { return stagedToRefdb; }
    public long getQueued() { return queuedForCp; }
    public long getEnriching() { return elasticsearchMonitoring; }
    public long getEnrichmentTimeout() { return cpTimeout; }
    public long getExensioLoading() { return exensioMonitoring; }
    public long getExensioTimeout() { return completedManualVerification; }
    public long getFailed() { return cpFailed + loadFailed; }
    public long getEnqueued() { return queuedForCp + elasticsearchMonitoring + exensioMonitoring; }
    public long getProcessing() { return elasticsearchMonitoring + cpTimeout + exensioMonitoring + completedManualVerification; }

    /** All in-flight monitoring states (for backward-compatible display). */
    public long enqueued() { return queuedForCp + elasticsearchMonitoring + exensioMonitoring; }

    public long accountingSum() {
        return stagedToRefdb + queuedForCp + elasticsearchMonitoring
            + cpTimeout + exensioMonitoring + completedManualVerification
            + cpFailed + loadFailed + completed + cancelled;
    }
}
