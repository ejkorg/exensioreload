package com.onsemi.cim.apps.exensio.exensioreload.stage;

import java.util.List;

public record StageStatus(
        String site,
        int senderId,
        String senderName,
        long total,
        long ready,
        long queued,
        long enriching,
        long enrichmentTimeout,
        long exensioLoading,
        long exensioTimeout,
        long failed,
        long completed,
        long cancelled,
        List<StageUserStatus> users
) {
    public StageStatus {
        users = users == null ? List.of() : List.copyOf(users);
    }

    /**
     * Backward compatibility: compute enqueued as queued + enriching + exensioLoading
     * This allows existing code to continue working without modification.
     */
    public long enqueued() {
        return queued + enriching + exensioLoading;
    }

    /**
     * Calculate the sum of all state counts for accounting verification.
     * Should equal total if all records are in valid states.
     * Includes enrichment and Exensio timeout states in the accounting sum.
     */
    public long accountingSum() {
        return ready + queued + enriching + enrichmentTimeout + exensioLoading + exensioTimeout + failed + completed + cancelled;
    }

    /**
     * Calculate backlog records still in processing pipeline.
     * Includes timeout states as they are uncertain and may resolve later.
     */
    public long backlog() {
        return queued + enriching + enrichmentTimeout + exensioLoading + exensioTimeout;
    }
}
