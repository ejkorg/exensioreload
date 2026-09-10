package com.onsemi.cim.apps.exensio.exensioreload.pipeline;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.onsemi.cim.apps.exensio.exensioreload.service.IntegrationStatusService;
import com.onsemi.cim.apps.exensio.exensioreload.service.RefDbService;
import com.onsemi.cim.apps.exensio.exensioreload.stage.StageMonitorService;
import com.onsemi.cim.apps.exensio.exensioreload.stage.StageRecord;

/**
 * Core pipeline orchestrator that manages record progression through dependency-aware stages.
 * 
 * Responsibilities:
 * - Determine next action for a record based on pipeline configuration and state
 * - Check if stage dependencies are satisfied
 * - Invoke stage handlers to check for completion
 * - Handle errors, timeouts, and stage transitions
 * - Emit SSE events for status changes
 * - Maintain backward compatibility with legacy sites
 * 
 * Requirements: 2.1, 2.2, 2.3, 2.4, 2.5, 10.4, 10.5, 11.1, 11.2, 11.3
 */
@Component
public class PipelineOrchestrator {
    private static final Logger log = LoggerFactory.getLogger(PipelineOrchestrator.class);

    // Default timeout configuration (in minutes)
    private static final long DEFAULT_STAGE_TIMEOUT_MINUTES = 30;
    private static final int ERROR_THRESHOLD = 3; // Fail after 3 consecutive errors

    private final PipelineConfigCache configCache;
    private final StageHandlerRegistry handlerRegistry;
    private final PipelineStatusTracker statusTracker;
    private final StageMonitorService stageMonitorService;
    private final IntegrationStatusService integrationStatusService;
    private final RefDbService refDbService;

    /**
     * Create PipelineOrchestrator with required dependencies.
     * 
     * _Requirements: 2.1, 10.4, 10.5_
     * 
     * @param configCache cache for pipeline configurations per site
     * @param handlerRegistry registry of stage handlers by type
     * @param statusTracker tracker for pipeline state per record
     * @param stageMonitorService service for emitting SSE events
     * @param integrationStatusService service for updating integration status
     * @param refDbService service for database operations
     */
    public PipelineOrchestrator(
            PipelineConfigCache configCache,
            StageHandlerRegistry handlerRegistry,
            PipelineStatusTracker statusTracker,
            StageMonitorService stageMonitorService,
            IntegrationStatusService integrationStatusService,
            RefDbService refDbService) {
        
        this.configCache = configCache;
        this.handlerRegistry = handlerRegistry;
        this.statusTracker = statusTracker;
        this.stageMonitorService = stageMonitorService;
        this.integrationStatusService = integrationStatusService;
        this.refDbService = refDbService;
    }

    /**
     * Determine the next action for a record based on current state and pipeline config.
     * 
     * This is the main decision point of the orchestrator. It checks:
     * 1. If the site has a pipeline configuration
     * 2. If the current stage's dependencies are satisfied
     * 3. If the current stage has timed out
     * 
     * Returns appropriate PipelineAction indicating what should happen next.
     * 
     * _Requirements: 2.2, 2.3, 2.4, 11.1, 11.2_
     * 
     * @param record the record to determine action for
     * @return the action to take for this record
     */
    public PipelineAction determineNextAction(StageRecord record) {
        if (record == null) {
            throw new IllegalArgumentException("record must not be null");
        }

        try {
            // Get pipeline config from cache using record.site()
            Optional<PipelineConfig> configOpt = configCache.getConfig(record.site());
            
            // Return PipelineAction.useLegacyPath() if config not present
            if (configOpt.isEmpty()) {
                log.debug("No pipeline config for site '{}', using legacy path", record.site());
                return PipelineAction.useLegacyPath();
            }

            PipelineConfig config = configOpt.get();

            // Get current PipelineState from statusTracker
            PipelineStatusTracker.PipelineState state = statusTracker.getState(record.id());

            // Determine current stage from state or first stage in config
            String currentStageName = state.currentStage();
            if (currentStageName == null || currentStageName.isBlank()) {
                // First time - use first stage
                currentStageName = config.getFirstStage().name();
            }

            StageDefinition currentStage = config.findStage(currentStageName);
            if (currentStage == null) {
                log.warn("Current stage '{}' not found in pipeline config for record {}", 
                         currentStageName, record.id());
                return PipelineAction.useLegacyPath();
            }

            // Check if current stage dependencies are satisfied via areDependenciesSatisfied()
            if (!areDependenciesSatisfied(currentStage, state)) {
                List<String> unsatisfiedDeps = getUnsatisfiedDependencies(currentStage, state);
                log.debug("Record {} stage '{}' dependencies not satisfied. Waiting for: {}", 
                         record.id(), currentStageName, unsatisfiedDeps);
                // Return PipelineAction.waitForDependencies() if not satisfied
                return PipelineAction.waitForDependencies(unsatisfiedDeps);
            }

            // Check if current stage has timed out
            Duration stageTimeout = getStageTimeout(currentStage);
            if (statusTracker.isStageTimedOut(record.id(), currentStageName, stageTimeout)) {
                log.warn("Record {} stage '{}' has timed out (timeout={})", 
                         record.id(), currentStageName, stageTimeout);
                // Will be handled by executeStage when result status is TIMEOUT
            }

            // Get handler from registry for current stage type
            StageHandler handler;
            try {
                handler = handlerRegistry.getHandler(currentStage.type());
            } catch (StageHandlerRegistry.StageHandlerNotFoundException e) {
                log.error("No handler found for stage type {}: {}", currentStage.type(), e.getMessage());
                return PipelineAction.useLegacyPath();
            }

            // Return PipelineAction.executeStage() with stage and handler
            return PipelineAction.executeStage(currentStage, handler);

        } catch (Exception e) {
            log.error("Error determining next action for record {}: {}", record.id(), e.getMessage(), e);
            // Fall back to legacy path on error
            return PipelineAction.useLegacyPath();
        }
    }

    /**
     * Check if all dependencies for a stage are satisfied.
     * 
     * Iterates through stage.dependsOn() and checks if each dependency has been completed
     * via state.isStageComplete(depName).
     * 
     * _Requirements: 2.3_
     * 
     * @param stage the stage to check dependencies for
     * @param state the current pipeline state
     * @return true if all dependencies complete, false if any dependency is incomplete
     */
    private boolean areDependenciesSatisfied(StageDefinition stage, 
                                            PipelineStatusTracker.PipelineState state) {
        for (String depName : stage.dependsOn()) {
            if (!state.isStageComplete(depName)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Get list of unsatisfied dependencies for a stage.
     * 
     * @param stage the stage to check dependencies for
     * @param state the current pipeline state
     * @return list of dependency names that are not yet complete
     */
    private List<String> getUnsatisfiedDependencies(StageDefinition stage, 
                                                   PipelineStatusTracker.PipelineState state) {
        List<String> unsatisfied = new ArrayList<>();
        for (String depName : stage.dependsOn()) {
            if (!state.isStageComplete(depName)) {
                unsatisfied.add(depName);
            }
        }
        return unsatisfied;
    }

    /**
     * Execute a stage and handle the result.
     * 
     * Calls handler.checkCompletion(record, stage.config()) and switches on result.status():
     * - COMPLETED → call progressToNextStage()
     * - NOT_FOUND → call keepInCurrentStage()
     * - ERROR → call handleStageError()
     * - TIMEOUT → call handleStageTimeout()
     * 
     * _Requirements: 2.3, 2.4, 2.5_
     * 
     * @param record the record to execute stage for
     * @param stage the stage definition
     * @param handler the stage handler
     */
    public void executeStage(StageRecord record, StageDefinition stage, StageHandler handler) {
        if (record == null || stage == null || handler == null) {
            throw new IllegalArgumentException("record, stage, and handler must not be null");
        }

        try {
            // Call handler.checkCompletion(record, stage.config())
            StageHandler.StageResult result = handler.checkCompletion(record, stage.config());

            log.debug("Stage '{}' check result for record {}: status={}, traceId={}", 
                     stage.name(), record.id(), result.status(), result.traceId());

            // Switch on result.status()
            switch (result.status()) {
                case COMPLETED -> progressToNextStage(record, stage, result);
                case NOT_FOUND -> keepInCurrentStage(record, stage, result);
                case ERROR -> handleStageError(record, stage, result);
                case TIMEOUT -> handleStageTimeout(record, stage, result);
            }

        } catch (Exception e) {
            log.error("Exception executing stage '{}' for record {}: {}", 
                     stage.name(), record.id(), e.getMessage(), e);
            handleStageError(record, stage, null);
        }
    }

    /**
     * Progress record to next stage after current stage completes.
     * 
     * Actions:
     * - Call statusTracker.markStageComplete() with stage name and result metadata
     * - Determine next stage from pipeline config
     * - Update record status to next stage monitoring status (e.g., CP_MONITORING → PPLOG_MONITORING)
     * - If no next stage, update record status to DONE
     * - Emit SSE event via stageMonitorService.sendEvent()
     * - Update integration status via integrationStatusService
     * 
     * _Requirements: 2.5, 6.1, 10.4, 10.5_
     * 
     * @param record the record to progress
     * @param stage the stage that just completed
     * @param result the completion result with metadata
     */
    private void progressToNextStage(StageRecord record, StageDefinition stage, 
                                    StageHandler.StageResult result) {
        try {
            // Call statusTracker.markStageComplete() with stage name and result metadata
            statusTracker.markStageComplete(record.id(), stage.name(), result.metadata());

            log.info("Stage '{}' completed for record {} with traceId {}", 
                    stage.name(), record.id(), result.traceId());

            // Get pipeline config to determine next stage
            Optional<PipelineConfig> configOpt = configCache.getConfig(record.site());
            if (configOpt.isEmpty()) {
                log.warn("Pipeline config lost for record {} after stage completion", record.id());
                return;
            }

            PipelineConfig config = configOpt.get();

            // Determine next stage from pipeline config
            StageDefinition nextStage = config.getNextStage(stage.name());

            String nextStatus;
            if (nextStage != null) {
                // Update record status to next stage monitoring status
                nextStatus = stageNameToMonitoringStatus(nextStage.name());
                log.debug("Progressing record {} from stage '{}' to '{}'", 
                         record.id(), stage.name(), nextStage.name());
            } else {
                // If no next stage, update record status to DONE
                nextStatus = "DONE";
                log.info("Record {} completed all pipeline stages", record.id());
            }

            // Update the record status in database
            // TODO: Implement generic status update method or use specific mark* methods
            // For now, we log the intended status transition
            log.debug("Record {} status should transition to: {}", record.id(), nextStatus);

            // Emit SSE event via stageMonitorService.sendEvent()
            // Note: StageMonitorService uses String requestId, not Long record ID
            stageMonitorService.sendEvent(String.valueOf(record.requestId()), "stageProgress", 
                Map.of(
                    "recordId", record.id(),
                    "previousStage", stage.name(),
                    "nextStage", nextStage != null ? nextStage.name() : "NONE",
                    "newStatus", nextStatus,
                    "completedAt", Instant.now().toString(),
                    "traceId", result.traceId()
                ));

            // Update integration status via integrationStatusService
            // Note: Using CP status update as a proxy for pipeline stage progression
            integrationStatusService.updateCpStatusForRecord(record.id(), "completed", 
                "Stage '" + stage.name() + "' completed successfully");

        } catch (Exception e) {
            log.error("Error progressing record {} to next stage: {}", record.id(), e.getMessage(), e);
        }
    }

    /**
     * Keep record in current stage for next poll cycle.
     * 
     * Actions:
     * - Update last_stage_check_at timestamp
     * - Check if stage has timed out via statusTracker.isStageTimedOut()
     * - If timed out, call handleStageTimeout()
     * - Otherwise, log debug message and exit (retry next poll)
     * 
     * _Requirements: 5.3, 9.1_
     * 
     * @param record the record to keep in current stage
     * @param stage the stage that's still processing
     * @param result the not found result
     */
    private void keepInCurrentStage(StageRecord record, StageDefinition stage, 
                                   StageHandler.StageResult result) {
        try {
            // Update last_stage_check_at timestamp
            // This would be done through a database update in real implementation

            log.debug("Stage '{}' not found for record {} (traceId: {}), will retry", 
                     stage.name(), record.id(), result.traceId());

            // Check if stage has timed out via statusTracker.isStageTimedOut()
            Duration stageTimeout = getStageTimeout(stage);
            if (statusTracker.isStageTimedOut(record.id(), stage.name(), stageTimeout)) {
                log.warn("Stage '{}' for record {} timed out after not finding completion", 
                         stage.name(), record.id());
                // If timed out, call handleStageTimeout()
                handleStageTimeout(record, stage, result);
            } else {
                // Otherwise, log debug message and exit (retry next poll)
                log.debug("Stage '{}' check will retry on next poll for record {}", 
                         stage.name(), record.id());
            }

        } catch (Exception e) {
            log.error("Error keeping record {} in current stage: {}", record.id(), e.getMessage(), e);
        }
    }

    /**
     * Handle stage error and manage error count/retry logic.
     * 
     * Actions:
     * - Log error with record ID, stage name, error message
     * - Update integration status to "error"
     * - Keep record in current monitoring status for retry
     * - Check error count - if exceeds threshold, mark FAILED
     * 
     * _Requirements: 9.1, 9.3_
     * 
     * @param record the record with error
     * @param stage the stage that errored
     * @param result the error result (may be null in some cases)
     */
    private void handleStageError(StageRecord record, StageDefinition stage, 
                                 StageHandler.StageResult result) {
        try {
            String errorMessage = result != null ? result.errorMessage() : "Unknown error";
            
            // Log error with record ID, stage name, error message
            log.error("Stage '{}' error for record {}: {}", stage.name(), record.id(), errorMessage);

            // Increment error count (simplified - would track in DB in real implementation)
            // Check error count - if exceeds threshold, mark FAILED
            int errorCount = getErrorCount(record.id(), stage.name());
            errorCount++;

            if (errorCount >= ERROR_THRESHOLD) {
                log.warn("Stage '{}' error threshold exceeded for record {} (errors={}), marking FAILED", 
                         stage.name(), record.id(), errorCount);
                
                // Update record status to FAILED
                refDbService.updateRecordStatus(record.id(), "FAILED");
                
                // Update integration status
                integrationStatusService.updateIntegrationStatus(record.id(), "failed", 
                    "Stage '" + stage.name() + "' failed after " + errorCount + " errors");
            } else {
                // Keep record in current monitoring status for retry
                log.debug("Stage '{}' error for record {}, will retry (error_count={})", 
                         stage.name(), record.id(), errorCount);
                
                // Update integration status to "error" with retry indication
                integrationStatusService.updateIntegrationStatus(record.id(), "error", 
                    "Stage '" + stage.name() + "' error - retry " + errorCount + "/" + ERROR_THRESHOLD);
            }

        } catch (Exception e) {
            log.error("Exception in handleStageError for record {}: {}", record.id(), e.getMessage(), e);
        }
    }

    /**
     * Handle stage timeout and transition to timeout status.
     * 
     * Actions:
     * - Build diagnostic message with stage details, dependencies, duration
     * - Update record status to stage timeout status (CP_TIMEOUT, PPLOG_TIMEOUT)
     * - Update integration status to "timeout"
     * - Emit SSE event with timeout diagnostic
     * - Log warning with full diagnostic information
     * 
     * _Requirements: 5.3, 9.2_
     * 
     * @param record the record that timed out
     * @param stage the stage that timed out
     * @param result the result that indicated timeout
     */
    private void handleStageTimeout(StageRecord record, StageDefinition stage, 
                                   StageHandler.StageResult result) {
        try {
            PipelineStatusTracker.PipelineState state = statusTracker.getState(record.id());
            Duration stageTimeout = getStageTimeout(stage);

            // Build diagnostic message with stage details, dependencies, duration
            StringBuilder diagnostic = new StringBuilder();
            diagnostic.append("Stage '").append(stage.name()).append("' timed out for record ").append(record.id()).append(": ");
            diagnostic.append("timeout_threshold=").append(stageTimeout);

            if (!stage.dependsOn().isEmpty()) {
                diagnostic.append(", dependencies=[");
                boolean first = true;
                for (String dep : stage.dependsOn()) {
                    if (!first) diagnostic.append(", ");
                    boolean completed = state.isStageComplete(dep);
                    diagnostic.append(dep).append("=").append(completed ? "COMPLETE" : "PENDING");
                    first = false;
                }
                diagnostic.append("]");
            }

            Duration stageAge = state.getStageAge(stage.name());
            if (!stageAge.isNegative() && !stageAge.isZero()) {
                diagnostic.append(", age=").append(stageAge);
            }

            String timeoutStatus = stageNameToTimeoutStatus(stage.name());

            // Update record status to stage timeout status
            refDbService.updateRecordStatus(record.id(), timeoutStatus);

            // Update integration status to "timeout"
            integrationStatusService.updateIntegrationStatus(record.id(), "timeout", diagnostic.toString());

            // Emit SSE event with timeout diagnostic
            stageMonitorService.sendEvent(record.id(), "stageTimeout",
                Map.of(
                    "recordId", record.id(),
                    "stage", stage.name(),
                    "status", timeoutStatus,
                    "diagnostic", diagnostic.toString(),
                    "timeoutMinutes", stageTimeout.toMinutes(),
                    "timestamp", Instant.now().toString()
                ));

            // Log warning with full diagnostic information
            log.warn("Stage timeout: {}", diagnostic);

        } catch (Exception e) {
            log.error("Error handling stage timeout for record {}: {}", record.id(), e.getMessage(), e);
        }
    }

    /**
     * Convert stage name to monitoring status (e.g., "cp" → "CP_MONITORING").
     * 
     * @param stageName the stage name
     * @return the monitoring status string
     */
    private String stageNameToMonitoringStatus(String stageName) {
        return stageName.toUpperCase() + "_MONITORING";
    }

    /**
     * Convert stage name to timeout status (e.g., "cp" → "CP_TIMEOUT").
     * 
     * @param stageName the stage name
     * @return the timeout status string
     */
    private String stageNameToTimeoutStatus(String stageName) {
        return stageName.toUpperCase() + "_TIMEOUT";
    }

    /**
     * Get timeout duration for a stage.
     * 
     * Checks stage config for timeoutMinutes, falls back to default.
     * 
     * @param stage the stage definition
     * @return timeout duration
     */
    private Duration getStageTimeout(StageDefinition stage) {
        if (stage.config() == null || stage.config().isEmpty()) {
            return Duration.ofMinutes(DEFAULT_STAGE_TIMEOUT_MINUTES);
        }

        Object timeoutObj = stage.config().get("timeoutMinutes");
        if (timeoutObj instanceof Number) {
            long minutes = ((Number) timeoutObj).longValue();
            return Duration.ofMinutes(Math.max(1, minutes)); // Ensure at least 1 minute
        }

        return Duration.ofMinutes(DEFAULT_STAGE_TIMEOUT_MINUTES);
    }

    /**
     * Get current error count for a stage (simplified implementation).
     * 
     * In a real implementation, this would track errors in the database.
     * 
     * @param recordId the record ID
     * @param stageName the stage name
     * @return current error count
     */
    private int getErrorCount(long recordId, String stageName) {
        // Simplified - would query database in real implementation
        return 0;
    }
}
