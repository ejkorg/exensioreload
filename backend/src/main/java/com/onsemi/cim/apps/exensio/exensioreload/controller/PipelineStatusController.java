package com.onsemi.cim.apps.exensio.exensioreload.controller;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.onsemi.cim.apps.exensio.exensioreload.pipeline.PipelineConfig;
import com.onsemi.cim.apps.exensio.exensioreload.pipeline.PipelineConfigCache;
import com.onsemi.cim.apps.exensio.exensioreload.pipeline.PipelineStatusTracker;
import com.onsemi.cim.apps.exensio.exensioreload.service.RefDbService;
import com.onsemi.cim.apps.exensio.exensioreload.stage.StageRecord;

/**
 * REST API endpoints for pipeline status and diagnostics.
 * 
 * Provides monitoring and control endpoints for pipeline-orchestrated records.
 * Requirements: 6.4, 6.5, 9.4, 9.5
 */
@RestController
@RequestMapping("/api/pipeline")
public class PipelineStatusController {

    private static final Logger log = LoggerFactory.getLogger(PipelineStatusController.class);

    private final PipelineStatusTracker statusTracker;
    private final PipelineConfigCache configCache;
    private final RefDbService refDbService;

    public PipelineStatusController(PipelineStatusTracker statusTracker,
                                   PipelineConfigCache configCache,
                                   RefDbService refDbService) {
        this.statusTracker = statusTracker;
        this.configCache = configCache;
        this.refDbService = refDbService;
    }

    /**
     * Get pipeline status for a specific record (Task 12.2).
     * 
     * Returns the current stage, completed stages, pending stages, and stage metadata.
     * Requirements: 6.4, 6.5
     * 
     * @param recordId the SENDER_STAGE record ID
     * @return pipeline status response with current/completed/pending stages, or 404 if not found
     */
    @GetMapping("/status/{recordId}")
    public ResponseEntity<?> getPipelineStatus(@PathVariable Long recordId) {
        try {
            // Get PipelineState from statusTracker
            PipelineStatusTracker.PipelineState state = statusTracker.getState(recordId);
            
            // Load record to get site for config lookup
            StageRecord record = refDbService.getRecord(recordId);
            if (record == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Record not found", "recordId", recordId));
            }

            // Get PipelineConfig from configCache
            var configOpt = configCache.getConfig(record.site());
            if (configOpt.isEmpty()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "No pipeline configured for site", "site", record.site(), "recordId", recordId));
            }

            PipelineConfig config = configOpt.get();

            // Build response with current stage, completed stages, pending stages
            Map<String, Object> response = new HashMap<>();
            response.put("recordId", recordId);
            response.put("site", record.site());
            response.put("lot", record.lot());
            response.put("wafer", record.wafer());
            response.put("status", record.status());

            // Current stage
            response.put("currentStage", state.currentStage());

            // Completed stages with times
            Map<String, Object> completedStagesInfo = new HashMap<>();
            for (String completed : state.completedStages()) {
                Instant completedAt = state.getStageCompletionTime(completed);
                completedStagesInfo.put(completed, completedAt != null ? completedAt.toString() : "unknown");
            }
            response.put("completedStages", completedStagesInfo);

            // Pending stages (in order, excluding completed and current)
            List<String> pendingStages = config.stages().stream()
                .map(s -> s.name())
                .filter(name -> !state.completedStages().contains(name) && !name.equals(state.currentStage()))
                .collect(Collectors.toList());
            response.put("pendingStages", pendingStages);

            // Stage completion times and metadata
            response.put("stageMetadata", state.metadata());

            // Pipeline age (time since started)
            if (state.pipelineStartedAt() != null) {
                Duration age = Duration.between(state.pipelineStartedAt(), Instant.now());
                response.put("pipelineAgeMinutes", age.toMinutes());
                response.put("pipelineStartedAt", state.pipelineStartedAt().toString());
            }

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Error retrieving pipeline status for record {}: {}", recordId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Failed to retrieve status: " + e.getMessage(), "recordId", recordId));
        }
    }

    /**
     * Retry a record that is stuck in a timeout status (Task 12.3).
     * 
     * Resets the pipeline state and transitions the record to the initial monitoring status.
     * Requirements: 9.4, 9.5
     * 
     * @param recordId the SENDER_STAGE record ID to retry
     * @return success/error response
     */
    @PostMapping("/retry/{recordId}")
    public ResponseEntity<?> retryRecord(@PathVariable Long recordId) {
        try {
            // Load record from RefDbService
            StageRecord record = refDbService.getRecord(recordId);
            if (record == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Record not found", "recordId", recordId));
            }

            // Check if record is in timeout status
            String status = record.status();
            if (!isTimeoutStatus(status)) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of(
                        "error", "Record is not in a timeout status (cannot retry)",
                        "recordId", recordId,
                        "currentStatus", status
                    ));
            }

            // Get pipeline config for the site
            var configOpt = configCache.getConfig(record.site());
            if (configOpt.isEmpty()) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", "No pipeline configured for site", "site", record.site()));
            }

            PipelineConfig config = configOpt.get();
            String firstStageName = config.getFirstStage().name();
            String initialMonitoringStatus = firstStageName.toUpperCase() + "_MONITORING";

            // Reset pipeline state via statusTracker
            statusTracker.resetState(recordId);
            log.info("Reset pipeline state for record {} (was in {})", recordId, status);

            // Update record status to initial monitoring status
            refDbService.updatePipelineStatus(recordId, initialMonitoringStatus, null);
            log.info("Retried record {} — reset to status {}", recordId, initialMonitoringStatus);

            return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Record retry initiated",
                "recordId", recordId,
                "newStatus", initialMonitoringStatus,
                "resetStage", firstStageName
            ));

        } catch (Exception e) {
            log.error("Error retrying record {}: {}", recordId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Failed to retry: " + e.getMessage(), "recordId", recordId));
        }
    }

    /**
     * Check if a status represents a timeout state.
     */
    private boolean isTimeoutStatus(String status) {
        return status != null && status.endsWith("_TIMEOUT");
    }
}
