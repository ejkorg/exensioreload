package com.onsemi.cim.apps.exensio.exensioreload.pipeline;

import java.util.Map;
import java.util.Objects;

import com.onsemi.cim.apps.exensio.exensioreload.stage.StageRecord;

/**
 * Interface for stage handlers that check completion of specific enrichment stages.
 * 
 * Handlers implement stage-specific logic to verify when a stage (e.g., CP, PP_LOG, Exensio)
 * has completed for a given record. The orchestrator uses handlers to determine when to
 * progress a record to the next pipeline stage.
 * 
 * Requirements: 8.1, 8.2, 8.3, 8.4
 */
public interface StageHandler {

    /**
     * Check if the stage has completed for the given record.
     * 
     * @param record the stage record to check (contains lot, wafer, identifiers, timestamps)
     * @param stageConfig configuration map for this stage (e.g., indexPattern, lookbackBufferSeconds)
     * @return StageResult indicating completion status, trace ID, and any error details
     */
    StageResult checkCompletion(StageRecord record, Map<String, Object> stageConfig);

    /**
     * Get the stage type this handler supports.
     * 
     * @return the StageType this handler is responsible for
     */
    StageType getStageType();

    /**
     * Result of a stage completion check with status, tracing, and metadata.
     * 
     * Immutable record containing:
     * - status: COMPLETED, NOT_FOUND, ERROR, or TIMEOUT
     * - traceId: correlation ID for logging and debugging
     * - errorMessage: optional error details for ERROR status
     * - metadata: optional additional information (e.g., CP output path, PP_LOG entry ID)
     */
    record StageResult(
        StageStatus status,
        String traceId,
        String errorMessage,
        Map<String, Object> metadata
    ) {
        public StageResult {
            Objects.requireNonNull(status, "status must not be null");
            Objects.requireNonNull(traceId, "traceId must not be null");
            Objects.requireNonNull(metadata, "metadata must not be null");
        }

        /**
         * Create a COMPLETED result with trace ID.
         * 
         * @param traceId correlation ID for tracing
         * @return completed StageResult
         */
        public static StageResult completed(String traceId) {
            return new StageResult(StageStatus.COMPLETED, traceId, null, Map.of());
        }

        /**
         * Create a COMPLETED result with trace ID and metadata.
         * 
         * @param traceId correlation ID for tracing
         * @param metadata additional completion details
         * @return completed StageResult with metadata
         */
        public static StageResult completed(String traceId, Map<String, Object> metadata) {
            return new StageResult(StageStatus.COMPLETED, traceId, null, metadata);
        }

        /**
         * Create a NOT_FOUND result with trace ID.
         * 
         * Stage completion data was not found. The orchestrator will retry on the next poll cycle.
         * 
         * @param traceId correlation ID for tracing
         * @return not found StageResult
         */
        public static StageResult notFound(String traceId) {
            return new StageResult(StageStatus.NOT_FOUND, traceId, null, Map.of());
        }

        /**
         * Create an ERROR result with trace ID and error message.
         * 
         * A transient or permanent error occurred. The orchestrator will retry on the next poll cycle
         * or fail after threshold.
         * 
         * @param traceId correlation ID for tracing
         * @param errorMessage description of the error
         * @return error StageResult
         */
        public static StageResult error(String traceId, String errorMessage) {
            return new StageResult(StageStatus.ERROR, traceId, errorMessage, Map.of());
        }

        /**
         * Create a TIMEOUT result with trace ID.
         * 
         * Stage has exceeded its configured timeout duration. The orchestrator will transition
         * the record to a timeout status.
         * 
         * @param traceId correlation ID for tracing
         * @return timeout StageResult
         */
        public static StageResult timeout(String traceId) {
            return new StageResult(StageStatus.TIMEOUT, traceId, null, Map.of());
        }

        /**
         * Get metadata value by key.
         * 
         * @param key metadata key
         * @return metadata value or null if not present
         */
        public Object getMetadata(String key) {
            return metadata.get(key);
        }

        /**
         * Get metadata value by key with default fallback.
         * 
         * @param key metadata key
         * @param defaultValue fallback value if key not present
         * @return metadata value or default
         */
        public Object getMetadata(String key, Object defaultValue) {
            return metadata.getOrDefault(key, defaultValue);
        }

        /**
         * Check if this result indicates successful completion.
         * 
         * @return true if status is COMPLETED
         */
        public boolean isCompleted() {
            return status == StageStatus.COMPLETED;
        }

        /**
         * Check if this result indicates the stage data was not found.
         * 
         * @return true if status is NOT_FOUND
         */
        public boolean isNotFound() {
            return status == StageStatus.NOT_FOUND;
        }

        /**
         * Check if this result indicates an error occurred.
         * 
         * @return true if status is ERROR
         */
        public boolean isError() {
            return status == StageStatus.ERROR;
        }

        /**
         * Check if this result indicates a timeout.
         * 
         * @return true if status is TIMEOUT
         */
        public boolean isTimeout() {
            return status == StageStatus.TIMEOUT;
        }
    }
}
