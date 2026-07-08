package com.onsemi.cim.apps.exensio.exensioreload.stage;

import java.util.List;

/**
 * Aggregated status counts for a sender's session records.
 * Field names match the v3.0 PipelineStatus enum values (with lowercase prefix convention).
 * The SQL column aliases in RefDbService.fetchStatusesFor() must match the lowercase field names here.
 */
public record StageStatus(
        String site,
        int senderId,
        String senderName,
        long total,
        long stagedToRefdb,                          // STAGED
        long queuedForCp,                            // QUEUED_FOR_CP
        long elasticsearchMonitoring,                // ELASTICSEARCH_MONITORING
        long cpTimeout,                              // CP_TIMEOUT
        long exensioMonitoring,                      // EXENSIO_MONITORING
        long completedManualVerification,            // COMPLETED_MANUAL_VERIFICATION_REQUIRED
        long cpFailed,                               // CP_FAILED
        long loadFailed,                             // LOAD_FAILED
        long completed,                              // COMPLETED
        long cancelled,                              // CANCELLED
        List<StageUserStatus> users
) {
    public StageStatus {
        users = users == null ? List.of() : List.copyOf(users);
    }

    /**
     * Calculate the sum of all state counts for accounting verification.
     * Should equal total if all records are in valid states.
     */
    public long accountingSum() {
        return stagedToRefdb + queuedForCp + elasticsearchMonitoring
            + cpTimeout + exensioMonitoring + completedManualVerification
            + cpFailed + loadFailed + completed + cancelled;
    }

    /**
     * Calculate backlog records still in processing pipeline.
     * Includes timeout/warning states as they are uncertain and may resolve later.
     */
    public long backlog() {
        return queuedForCp + elasticsearchMonitoring + cpTimeout
            + exensioMonitoring + completedManualVerification;
    }

    /** All failure states combined (for backward compatibility where both are shown together). */
    public long totalFailed() {
        return cpFailed + loadFailed;
    }

    // ── Backward-compatible accessors ──────────────────────────────────────

    /** @deprecated Use {@link #stagedToRefdb()} */
    @Deprecated public long ready() { return stagedToRefdb; }
    /** @deprecated Use {@link #queuedForCp()} */
    @Deprecated public long queued() { return queuedForCp; }
    /** @deprecated Use {@link #elasticsearchMonitoring()} */
    @Deprecated public long enriching() { return elasticsearchMonitoring; }
    /** @deprecated Use {@link #cpTimeout()} */
    @Deprecated public long enrichmentTimeout() { return cpTimeout; }
    /** @deprecated Use {@link #exensioMonitoring()} */
    @Deprecated public long exensioLoading() { return exensioMonitoring; }
    /** @deprecated Use {@link #completedManualVerification()} */
    @Deprecated public long exensioTimeout() { return completedManualVerification; }
    /** @deprecated Use {@link #totalFailed()} */
    @Deprecated public long failed() { return totalFailed(); }
    /** @deprecated Use {@link #completed()} but maps {@code DONE} → {@code COMPLETED} display */
    @Deprecated public long done() { return completed; }

    /**
     * Backward compatibility: compute enqueued as queuedForCp + elasticsearchMonitoring.
     * This allows existing code to continue working without modification.
     */
    public long enqueued() {
        return queuedForCp + elasticsearchMonitoring + exensioMonitoring;
    }

    /** @deprecated {@link #stagedToRefdb()} + {@link #queuedForCp()} */
    @Deprecated public long staged() { return stagedToRefdb; }
}
