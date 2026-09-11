package com.onsemi.cim.apps.exensio.exensioreload.pipeline;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.onsemi.cim.apps.exensio.exensioreload.service.RefDbService;
import com.onsemi.cim.apps.exensio.exensioreload.stage.StageRecord;

/**
 * Tracks pipeline state per record by reading from and writing to the SENDER_STAGE table
 * pipeline columns: current_pipeline_stage, completed_pipeline_stages, stage_metadata,
 * pipeline_started_at, last_stage_check_at.
 *
 * Requirements: 6.1, 6.2, 10.2
 */
@Component
public class PipelineStatusTracker {

    private static final Logger log = LoggerFactory.getLogger(PipelineStatusTracker.class);

    private final RefDbService refDbService;
    private final ObjectMapper objectMapper;

    public PipelineStatusTracker(RefDbService refDbService) {
        this.refDbService = Objects.requireNonNull(refDbService, "refDbService must not be null");
        this.objectMapper = new ObjectMapper();
    }

    // -------------------------------------------------------------------------
    // Inner record: PipelineState
    // -------------------------------------------------------------------------

    /**
     * Immutable snapshot of a record's current pipeline progress.
     *
     * Requirements: 6.1
     */
    public record PipelineState(
            long recordId,
            String currentStage,
            Set<String> completedStages,
            Map<String, Object> stageMetadata,
            Instant pipelineStartedAt,
            Instant lastStageCheckAt
    ) {
        public PipelineState {
            Objects.requireNonNull(completedStages, "completedStages must not be null");
        }

        /**
         * Return true when the named stage is in the completed set.
         *
         * Requirements: 6.1
         */
        public boolean isStageComplete(String stageName) {
            if (stageName == null || stageName.isBlank()) return false;
            return completedStages.contains(stageName);
        }

        /**
         * How long ago this pipeline was started; Duration.ZERO when unknown.
         */
        public Duration getPipelineAge() {
            if (pipelineStartedAt == null) return Duration.ZERO;
            Duration age = Duration.between(pipelineStartedAt, Instant.now());
            return age.isNegative() ? Duration.ZERO : age;
        }

        /**
         * Return the completion time for a completed stage, or null when not found
         * or the stage has no recorded completion timestamp.
         *
         * @param stageName the stage to look up
         * @return ISO-8601 completion timestamp, or null
         */
        public String getStageCompletionTime(String stageName) {
            if (stageMetadata == null || stageName == null) return null;
            Object stageEntry = stageMetadata.get(stageName);
            if (stageEntry instanceof Map<?, ?> sm && sm.get("completedAt") instanceof String s) {
                return s;
            }
            return null;
        }

        /**
         * How long ago the current stage last completed a check; Duration.ZERO when unknown.
         */
        public Duration getStageAge(String stageName) {
            if (lastStageCheckAt == null) return Duration.ZERO;
            Duration age = Duration.between(lastStageCheckAt, Instant.now());
            return age.isNegative() ? Duration.ZERO : age;
        }
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Read current pipeline state for a record from the SENDER_STAGE table.
     *
     * Returns an empty PipelineState (all nulls/empty collections) when the record
     * does not exist or has no pipeline columns set yet.
     *
     * Requirements: 6.2
     *
     * @param recordId the SENDER_STAGE primary key
     * @return current pipeline state (never null)
     */
    public PipelineState getState(long recordId) {
        try {
            StageRecord record = refDbService.fetchRecordById(recordId);
            if (record == null) {
                log.warn("Record {} not found when getting pipeline state; returning empty state", recordId);
                return emptyState(recordId);
            }
            return buildState(record);
        } catch (Exception e) {
            log.error("Failed to get pipeline state for record {}: {}", recordId, e.getMessage(), e);
            return emptyState(recordId);
        }
    }

    /**
     * Mark a pipeline stage as complete and persist stage metadata.
     *
     * Adds the stage to completed_pipeline_stages JSON array and merges any metadata
     * into the stage_metadata JSON object; updates last_stage_check_at.
     *
     * Requirements: 6.1, 10.2
     *
     * @param recordId  the SENDER_STAGE primary key
     * @param stageName the stage that just finished
     * @param metadata  key/value metadata to store (e.g. cpOutputTarget, ppLogEntry)
     */
    public void markStageComplete(long recordId, String stageName, Map<String, Object> metadata) {
        if (stageName == null || stageName.isBlank()) {
            throw new IllegalArgumentException("stageName must not be blank");
        }

        try {
            // Fetch current state so we can merge it
            PipelineState current = getState(recordId);
            Set<String> completed = new HashSet<>(current.completedStages());
            completed.add(stageName);

            // Merge metadata
            Map<String, Object> mergedMeta;
            try {
                // Start from whatever is already stored
                mergedMeta = (current.stageMetadata() != null && !current.stageMetadata().isEmpty())
                        ? new java.util.LinkedHashMap<>(current.stageMetadata())
                        : new java.util.LinkedHashMap<>();
            } catch (Exception e) {
                mergedMeta = new java.util.LinkedHashMap<>();
            }

            if (metadata != null && !metadata.isEmpty()) {
                // Namespace under the stage name to avoid collisions
                mergedMeta.put(stageName, metadata);
            }
            // Record completion timestamp under the stage namespace
            Object stageEntry = mergedMeta.computeIfAbsent(stageName, k -> new java.util.LinkedHashMap<>());
            if (stageEntry instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> stageMap = (Map<String, Object>) stageEntry;
                stageMap.put("completedAt", Instant.now().toString());
            }

            String completedJson = toJson(completed);
            String metadataJson = toJson(mergedMeta);

            refDbService.updatePipelineStageComplete(recordId, stageName, completedJson, metadataJson);

            log.debug("Marked stage '{}' complete for record {}; completedStages={}", stageName, recordId, completed);
        } catch (Exception e) {
            log.error("Failed to mark stage '{}' complete for record {}: {}", stageName, recordId, e.getMessage(), e);
            throw new RuntimeException("Failed to mark stage complete: " + stageName, e);
        }
    }

    /**
     * Update the current_pipeline_stage column without marking the previous stage complete.
     *
     * Used when first entering a stage.
     *
     * @param recordId  the SENDER_STAGE primary key
     * @param stageName the new current stage name
     */
    public void updateCurrentStage(long recordId, String stageName) {
        try {
            refDbService.updateCurrentPipelineStage(recordId, stageName);
            log.debug("Updated current stage to '{}' for record {}", stageName, recordId);
        } catch (Exception e) {
            log.error("Failed to update current stage to '{}' for record {}: {}", stageName, recordId, e.getMessage(), e);
        }
    }

    /**
     * Reset pipeline state for a record, clearing all pipeline columns.
     *
     * Used by PipelineStatusController.retryRecord() to reset a timed-out record
     * back to its initial pipeline state so it can re-enter the first stage.
     *
     * @param recordId the SENDER_STAGE primary key
     */
    public void resetState(long recordId) {
        try {
            refDbService.updatePipelineReset(recordId);
            log.debug("Reset pipeline state for record {}", recordId);
        } catch (Exception e) {
            log.error("Failed to reset pipeline state for record {}: {}", recordId, e.getMessage(), e);
        }
    }

    /**
     * Refresh the last_stage_check_at timestamp for the record.
     *
     * Called every poll cycle to track how long a record has been in its current stage.
     *
     * @param recordId the SENDER_STAGE primary key
     */
    public void touchLastCheck(long recordId) {
        try {
            refDbService.updateLastStageCheckAt(recordId);
        } catch (Exception e) {
            log.debug("Failed to touch last-check timestamp for record {}: {}", recordId, e.getMessage());
        }
    }

    /**
     * Check whether the record has been in the named stage longer than the given timeout.
     *
     * Uses pipeline_started_at as the reference point; falls back to current time when
     * the column is null (i.e., never times out for brand-new records without a start time).
     *
     * Requirements: 5.3, 9.2
     *
     * @param recordId  the SENDER_STAGE primary key
     * @param stageName the stage to check
     * @param timeout   the timeout duration
     * @return true if the record has been in the stage longer than the timeout
     */
    public boolean isStageTimedOut(long recordId, String stageName, Duration timeout) {
        try {
            PipelineState state = getState(recordId);
            Instant reference = state.pipelineStartedAt();
            if (reference == null) {
                // Pipeline hasn't formally started yet; fall back to lastStageCheckAt
                reference = state.lastStageCheckAt();
            }
            if (reference == null) {
                return false;
            }
            Duration elapsed = Duration.between(reference, Instant.now());
            boolean timedOut = !elapsed.isNegative() && elapsed.compareTo(timeout) > 0;
            if (timedOut) {
                log.debug("Stage '{}' timed out for record {}: elapsed={}, timeout={}", 
                         stageName, recordId, elapsed, timeout);
            }
            return timedOut;
        } catch (Exception e) {
            log.error("Failed to check timeout for stage '{}' record {}: {}", stageName, recordId, e.getMessage(), e);
            return false;
        }
    }

    /**
     * Static utility to check if a given start time has exceeded the timeout,
     * used by property-based tests without constructing a full PipelineStatusTracker.
     *
     * @param startedAt when the stage started
     * @param timeout   the timeout duration
     * @return true if elapsed > timeout
     */
    public static boolean isTimedOut(Instant startedAt, Duration timeout) {
        if (startedAt == null || timeout == null) return false;
        Duration elapsed = Duration.between(startedAt, Instant.now());
        return !elapsed.isNegative() && elapsed.compareTo(timeout) > 0;
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private PipelineState buildState(StageRecord record) {
        Set<String> completedStages = parseCompletedStages(record.completedPipelineStages());
        Map<String, Object> metadata = parseMetadata(record.stageMetadata());
        return new PipelineState(
                record.id(),
                record.currentPipelineStage(),
                Collections.unmodifiableSet(completedStages),
                Collections.unmodifiableMap(metadata),
                record.pipelineStartedAt(),
                record.lastStageCheckAt()
        );
    }

    private PipelineState emptyState(long recordId) {
        return new PipelineState(
                recordId,
                null,
                Collections.emptySet(),
                Collections.emptyMap(),
                null,
                null
        );
    }

    /**
     * Parse the completed_pipeline_stages JSON array column into a Set<String>.
     *
     * Example stored value: ["cp","pplog"]
     */
    private Set<String> parseCompletedStages(String json) {
        if (json == null || json.isBlank()) return new HashSet<>();
        try {
            java.util.List<String> list = objectMapper.readValue(json, new TypeReference<>() {});
            return new HashSet<>(list);
        } catch (Exception e) {
            log.warn("Failed to parse completed_pipeline_stages JSON '{}': {}", json, e.getMessage());
            return new HashSet<>();
        }
    }

    /**
     * Parse the stage_metadata CLOB/VARCHAR column into a Map.
     *
     * Example stored value: {"cp":{"completedAt":"2024-01-15T10:00:00Z","cpOutputTarget":"..."},...}
     */
    private Map<String, Object> parseMetadata(String json) {
        if (json == null || json.isBlank()) return new java.util.LinkedHashMap<>();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("Failed to parse stage_metadata JSON: {}", e.getMessage());
            return new java.util.LinkedHashMap<>();
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("Failed to serialize to JSON: {}", e.getMessage());
            return "{}";
        }
    }
}
