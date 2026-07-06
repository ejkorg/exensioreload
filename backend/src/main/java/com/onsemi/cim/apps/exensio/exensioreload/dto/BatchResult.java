package com.onsemi.cim.apps.exensio.exensioreload.dto;

/**
 * Result of processing a batch of records against the Exensio API.
 *
 * @param updates list of individual record updates
 * @param successCount number of records successfully updated
 * @param failureCount number of records that failed
 * @param notFoundCount number of records not found in Exensio
 * @param processingTimeMs time taken to process the batch in milliseconds
 */
public record BatchResult(
        java.util.List<RecordUpdate> updates,
        int successCount,
        int failureCount,
        int notFoundCount,
        long processingTimeMs
) {
    /**
     * Represents a single record update result.
     *
     * @param recordId the ID of the stage record
     * @param type the type of update
     * @param waferKey the Exensio wafer key (if applicable)
     * @param pgKey the Exensio pg key (if applicable)
     * @param errorMessage error message if the update failed
     */
    public record RecordUpdate(
            long recordId,
            UpdateType type,
            Long waferKey,
            Long pgKey,
            String errorMessage,
            String lotId,
            String waferId,
            String fileName,
            String traceId
    ) {}

    /**
     * Type of update applied to a record (v3.0 state machine).
     */
    public enum UpdateType {
        COMPLETED,
        CP_FAILED,
        LOAD_FAILED,
        NOT_FOUND,
        ERROR,
        CP_TIMEOUT,
        COMPLETED_MANUAL_VERIFICATION_REQUIRED
    }

    public static BatchResult empty() {
        return new BatchResult(java.util.Collections.emptyList(), 0, 0, 0, 0);
    }

    public static BatchResult skipped(int recordCount) {
        return new BatchResult(java.util.Collections.emptyList(), 0, recordCount, 0, 0);
    }
}
