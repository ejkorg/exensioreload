package com.onsemi.cim.apps.exensio.exensioreload.pipeline;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.onsemi.cim.apps.exensio.exensioreload.service.IntegrationStatusService;
import com.onsemi.cim.apps.exensio.exensioreload.service.RefDbService;
import com.onsemi.cim.apps.exensio.exensioreload.stage.StageMonitorService;
import com.onsemi.cim.apps.exensio.exensioreload.stage.StageRecord;

import org.springframework.jmx.export.annotation.ManagedAttribute;
import org.springframework.jmx.export.annotation.ManagedResource;
import java.util.concurrent.atomic.AtomicLong;

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

    /** Default timeout per stage when not specified in config (minutes). */
    private static final long DEFAULT_STAGE_TIMEOUT_MINUTES = 30;

    /**
     * Maximum number of consecutive handler errors before a record is marked FAILED.
     * Per-record error counts are tracked in-memory (reset on restart which is acceptable
     * for transient error protection).
     */
    private static final int ERROR_THRESHOLD = 3;

    // In-memory error counters keyed by "recordId:stageName"
    // These are intentionally reset on restart â€” transient protection only.
    private final ConcurrentHashMap<String, AtomicInteger> errorCounters = new ConcurrentHashMap<>();

    private final PipelineConfigCache configCache;
    private final StageHandlerRegistry handlerRegistry;
    private final PipelineStatusTracker statusTracker;
    private final StageMonitorService stageMonitorService;
    private final IntegrationStatusService integrationStatusService;
    private final RefDbService refDbService;

    /**
     * Create PipelineOrchestrator with required dependencies.
     *
     * Requirements: 2.1, 10.4, 10.5
     *
     * @param configCache               cache for pipeline configurations per site
     * @param handlerRegistry           registry of stage handlers by type
     * @param statusTracker             tracker for pipeline state per record
     * @param stageMonitorService       service for emitting SSE events
     * @param integrationStatusService  service for updating integration status
     * @param refDbService              service for database operations
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

    // =========================================================================
    // 9.2 determineNextAction()
    // =========================================================================

    /**
     * Determine the next action for a record based on current state and pipeline config.
     *
     * Decision flow:
     * 1. Get pipeline config for the site; if absent â†’ useLegacyPath()
     * 2. Get current PipelineState from statusTracker
     * 3. Identify current stage (from state or first stage in config)
     * 4. If dependencies are not satisfied â†’ waitForDependencies()
     * 5. Obtain handler from registry â†’ executeStage()
     *
     * Requirements: 2.2, 2.3, 2.4, 11.1, 11.2
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
                log.debug("No pipeline config for site '{}', using legacy path (record {})",
                         record.site(), record.id());
                return PipelineAction.useLegacyPath();
            }

            PipelineConfig config = configOpt.get();

            // Get current PipelineState from statusTracker
            PipelineStatusTracker.PipelineState state = statusTracker.getState(record.id());

            // Determine current stage: use state.currentStage() if set, else first stage in config
            String currentStageName = state.currentStage();
            if (currentStageName == null || currentStageName.isBlank()) {
                StageDefinition first = config.getFirstStage();
                if (first == null) {
                    log.warn("Pipeline config for site '{}' has no stages; falling back to legacy path", record.site());
                    return PipelineAction.useLegacyPath();
                }
                currentStageName = first.name();
            }

            StageDefinition currentStage = config.findStage(currentStageName);
            if (currentStage == null) {
                log.warn("Current stage '{}' not found in pipeline config for site '{}' (record {}); falling back to legacy path",
                         currentStageName, record.site(), record.id());
                return PipelineAction.useLegacyPath();
            }

            // Check if current stage dependencies are satisfied via areDependenciesSatisfied()
            if (!areDependenciesSatisfied(currentStage, state)) {
                List<String> unsatisfiedDeps = getUnsatisfiedDependencies(currentStage, state);
                log.debug("Record {} stage '{}' waiting for dependencies: {}", record.id(), currentStageName, unsatisfiedDeps);
                // Return PipelineAction.waitForDependencies() if not satisfied
                return PipelineAction.waitForDependencies(unsatisfiedDeps);
            }

            // Get handler from registry for current stage type
            StageHandler handler;
            try {
                handler = handlerRegistry.getHandler(currentStage.type());
            } catch (StageHandlerRegistry.StageHandlerNotFoundException e) {
                log.error("No handler found for stage type {} (record {}): {}",
                         currentStage.type(), record.id(), e.getMessage());
                // Fall back to legacy path so the record doesn't get stuck
                return PipelineAction.useLegacyPath();
            }

            // Return PipelineAction.executeStage() with stage and handler
            return PipelineAction.executeStage(currentStage, handler);

        } catch (Exception e) {
            log.error("Error determining next action for record {}: {}", record.id(), e.getMessage(), e);
            return PipelineAction.useLegacyPath();
        }
    }

    // =========================================================================
    // 9.3 areDependenciesSatisfied() (private)
    // =========================================================================

    /**
     * Return true when every dependency in stage.dependsOn() is present in state.completedStages.
     *
     * Requirements: 2.3
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
     * Return the list of dependency names that are not yet complete.
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

    // =========================================================================
    // 9.4 executeStage()
    // =========================================================================

    /**
     * Execute a stage and dispatch to the appropriate handler based on result status.
     *
     * Calls handler.checkCompletion(record, stage.config()) then switches:
     * - COMPLETED â†’ progressToNextStage()
     * - NOT_FOUND â†’ keepInCurrentStage()
     * - ERROR     â†’ handleStageError()
     * - TIMEOUT   â†’ handleStageTimeout()
     *
     * Requirements: 2.3, 2.4, 2.5
     *
     * @param record  the record to execute stage for
     * @param stage   the stage definition
     * @param handler the stage handler
     */
    public void executeStage(StageRecord record, StageDefinition stage, StageHandler handler) {
        if (record == null || stage == null || handler == null) {
            throw new IllegalArgumentException("record, stage, and handler must not be null");
        }

        try {
            // Call handler.checkCompletion(record, stage.config())
            StageHandler.StageResult result = handler.checkCompletion(record, stage.config());

            log.debug("Stage '{}' check for record {}: status={}, traceId={}",
                     stage.name(), record.id(), result.status(), result.traceId());

            // Switch on result.status()
            switch (result.status()) {
                case COMPLETED -> progressToNextStage(record, stage, result);
                case NOT_FOUND -> keepInCurrentStage(record, stage, result);
                case ERROR     -> handleStageError(record, stage, result);
                case TIMEOUT   -> handleStageTimeout(record, stage, result);
            }

        } catch (Exception e) {
            log.error("Exception executing stage '{}' for record {}: {}",
                     stage.name(), record.id(), e.getMessage(), e);
            // Treat unexpected exception as an ERROR result so error-count logic applies
            handleStageError(record, stage, null);
        }
    }

    // =========================================================================
    // 9.5 progressToNextStage() (private)
    // =========================================================================

    /**
     * Advance the record to the next pipeline stage after the current stage completes.
     *
     * Actions:
     * 1. Mark stage complete in PipelineStatusTracker (persists to DB)
     * 2. Determine next stage from pipeline config
     * 3. Update SENDER_STAGE.status to next monitoring status, or DONE if no next stage
     * 4. Emit SSE event via stageMonitorService
     * 5. Update per-record integration status via integrationStatusService
     *
     * Requirements: 2.5, 6.1, 10.4, 10.5
     */
    private void progressToNextStage(StageRecord record, StageDefinition stage,
                                     StageHandler.StageResult result) {
        try {
            // 1. Call statusTracker.markStageComplete() with stage name and result metadata
            statusTracker.markStageComplete(record.id(), stage.name(), result.metadata());

            log.info("Stage '{}' completed for record {} (traceId={})",
                    stage.name(), record.id(), result.traceId());

            // 2. Determine next stage from pipeline config
            Optional<PipelineConfig> configOpt = configCache.getConfig(record.site());
            if (configOpt.isEmpty()) {
                log.warn("Pipeline config lost for site '{}' after stage '{}' completion (record {})",
                         record.site(), stage.name(), record.id());
                return;
            }

            PipelineConfig config = configOpt.get();
            StageDefinition nextStage = config.getNextStage(stage.name());

            // 3. Update record status
            final String nextStatus;
            final String progressMsg;
            if (nextStage != null) {
                // Advance to next stage monitoring status (e.g., CP_MONITORING â†’ PPLOG_MONITORING)
                nextStatus = stageNameToMonitoringStatus(nextStage.name());
                progressMsg = "Pipeline stage '" + stage.name() + "' completed; advancing to '" + nextStage.name() + "'";
                log.info("Progressing record {} from stage '{}' â†’ '{}' (new status={})",
                         record.id(), stage.name(), nextStage.name(), nextStatus);
            } else {
                // No next stage â†’ pipeline is complete
                nextStatus = "DONE";
                progressMsg = "All pipeline stages completed";
                log.info("Record {} completed all pipeline stages; marking DONE", record.id());
            }
            refDbService.updatePipelineStatus(record.id(), nextStatus, null);

            // 4. Emit SSE event via stageMonitorService.sendEvent()
            // StageMonitorService.sendEvent uses String requestId (not Long)
            if (record.requestId() != null) {
                stageMonitorService.sendEvent(record.requestId(), "PIPELINE_STAGE_PROGRESS",
                    Map.of(
                        "recordId",      record.id(),
                        "completedStage", stage.name(),
                        "nextStage",     nextStage != null ? nextStage.name() : "NONE",
                        "newStatus",     nextStatus,
                        "completedAt",   Instant.now().toString(),
                        "traceId",       result.traceId()
                    ));
            }

            // 5. Update integration status via integrationStatusService
            // updateCpStatusForRecord is the closest generic method for per-record status tracking
            integrationStatusService.updateCpStatusForRecord(record.id(), "completed", progressMsg);

        } catch (Exception e) {
            log.error("Error progressing record {} to next stage after '{}' completed: {}",
                     record.id(), stage.name(), e.getMessage(), e);
        }
    }

    // =========================================================================
    // 9.6 keepInCurrentStage() (private)
    // =========================================================================

    /**
     * Keep the record in its current stage for the next poll cycle.
     *
     * Actions:
     * 1. Update last_stage_check_at timestamp
     * 2. Check if stage has timed out via statusTracker.isStageTimedOut()
     * 3. If timed out â†’ handleStageTimeout()
     * 4. Otherwise â†’ log debug and exit (retry next poll)
     *
     * Requirements: 5.3, 9.1
     */
    private void keepInCurrentStage(StageRecord record, StageDefinition stage,
                                    StageHandler.StageResult result) {
        try {
            // 1. Update last_stage_check_at timestamp
            statusTracker.touchLastCheck(record.id());

            log.debug("Stage '{}' not found for record {} (traceId={}); will retry next poll",
                     stage.name(), record.id(), result.traceId());

            // 2. Check if stage has timed out via statusTracker.isStageTimedOut()
            Duration stageTimeout = getStageTimeout(stage);
            if (statusTracker.isStageTimedOut(record.id(), stage.name(), stageTimeout)) {
                // 3. If timed out â†’ handleStageTimeout()
                log.warn("Stage '{}' for record {} has timed out after not finding completion", stage.name(), record.id());
                handleStageTimeout(record, stage, result);
            } else {
                // 4. Otherwise log debug and exit (retry next poll)
                log.debug("Stage '{}' check will retry on next poll for record {} (timeout in {})",
                         stage.name(), record.id(), stageTimeout);
            }

        } catch (Exception e) {
            log.error("Error in keepInCurrentStage for record {} stage '{}': {}",
                     record.id(), stage.name(), e.getMessage(), e);
        }
    }

    // =========================================================================
    // 9.7 handleStageError() (private)
    // =========================================================================

    /**
     * Handle a stage error result and manage retry/failure logic.
     *
     * Actions:
     * 1. Log error with record ID, stage name, error message
     * 2. Update integration status to "error"
     * 3. Keep record in current monitoring status for retry
     * 4. If error count exceeds threshold â†’ mark record FAILED
     *
     * Requirements: 9.1, 9.3
     *
     * @param result may be null when called from the exception catch block in executeStage()
     */
    private void handleStageError(StageRecord record, StageDefinition stage,
                                  StageHandler.StageResult result) {
        try {
            String errorMessage = (result != null && result.errorMessage() != null)
                    ? result.errorMessage() : "Unknown error in stage handler";

            // 1. Log error with record ID, stage name, error message
            log.error("Stage '{}' error for record {}: {}", stage.name(), record.id(), errorMessage);

            // Increment in-memory error counter for this record+stage combination
            String key = record.id() + ":" + stage.name();
            int errorCount = errorCounters
                    .computeIfAbsent(key, k -> new AtomicInteger(0))
                    .incrementAndGet();

            // 4. Check error count - if exceeds threshold, mark FAILED
            if (errorCount >= ERROR_THRESHOLD) {
                log.warn("Stage '{}' error threshold ({}) exceeded for record {} (errors={}); marking FAILED",
                         stage.name(), ERROR_THRESHOLD, record.id(), errorCount);

                String failMsg = "Stage '" + stage.name() + "' failed after " + errorCount
                        + " consecutive errors. Last error: " + errorMessage;

                // Update status to FAILED
                refDbService.updatePipelineStatus(record.id(), "FAILED", failMsg);

                // Emit SSE notification
                if (record.requestId() != null) {
                    stageMonitorService.sendEvent(record.requestId(), "PIPELINE_STAGE_FAILED",
                        Map.of(
                            "recordId",    record.id(),
                            "stage",       stage.name(),
                            "errorCount",  errorCount,
                            "message",     failMsg,
                            "timestamp",   Instant.now().toString()
                        ));
                }

                // 2. Update integration status to reflect final failure
                integrationStatusService.updateCpStatusForRecord(record.id(), "failed", failMsg);

                // Clear the error counter after marking failed
                errorCounters.remove(key);

            } else {
                // 3. Keep record in current monitoring status for retry
                log.debug("Stage '{}' error for record {} (error_count={}/{}); keeping in current status for retry",
                         stage.name(), record.id(), errorCount, ERROR_THRESHOLD);

                // 2. Update integration status to "error" with retry indication
                String retryMsg = "Stage '" + stage.name() + "' error (" + errorCount + "/"
                        + ERROR_THRESHOLD + "): " + errorMessage;
                integrationStatusService.updateCpStatusForRecord(record.id(), "error", retryMsg);
            }

        } catch (Exception e) {
            log.error("Exception in handleStageError for record {} stage '{}': {}",
                     record.id(), stage.name(), e.getMessage(), e);
        }
    }

    // =========================================================================
    // 9.8 handleStageTimeout() (private)
    // =========================================================================

    /**
     * Handle a stage timeout and transition the record to a timeout status.
     *
     * Actions:
     * 1. Build diagnostic message (stage name, dependencies, duration)
     * 2. Update SENDER_STAGE.status to stage timeout status (e.g., CP_TIMEOUT, PPLOG_TIMEOUT)
     * 3. Update integration status to "timeout"
     * 4. Emit SSE event with timeout diagnostic
     * 5. Log warning with full diagnostic
     *
     * Requirements: 5.3, 9.2
     *
     * Diagnostic log format (from design doc):
     * Stage '{stageName}' timed out for record {recordId} (lot={lot}, wafer={wafer}, site={site}):
     *   - Time in stage: X minutes (timeout threshold: Y minutes)
     *   - Last check result: {result}
     *   - Dependencies: {deps or "none"}
     */
    private void handleStageTimeout(StageRecord record, StageDefinition stage,
                                    StageHandler.StageResult result) {
        try {
            PipelineStatusTracker.PipelineState state = statusTracker.getState(record.id());
            Duration stageTimeout = getStageTimeout(stage);
            Duration elapsed = state.getPipelineAge();

            // 1. Build diagnostic message
            StringBuilder diagnostic = new StringBuilder();
            diagnostic.append("Stage '").append(stage.name())
                      .append("' timed out for record ").append(record.id())
                      .append(" (lot=").append(record.lot())
                      .append(", wafer=").append(record.wafer())
                      .append(", site=").append(record.site()).append("):\n");
            diagnostic.append("  - Time in stage: ").append(elapsed.toMinutes())
                      .append(" minutes (timeout threshold: ").append(stageTimeout.toMinutes()).append(" minutes)\n");

            String lastCheckResult = (result != null) ? result.status().toString() : "UNKNOWN";
            diagnostic.append("  - Last check result: ").append(lastCheckResult).append("\n");

            if (stage.dependsOn().isEmpty()) {
                diagnostic.append("  - Dependencies: none (first stage)\n");
            } else {
                diagnostic.append("  - Dependencies: ");
                List<String> depDetails = new ArrayList<>();
                for (String dep : stage.dependsOn()) {
                    depDetails.add(dep + "=" + (state.isStageComplete(dep) ? "COMPLETE" : "PENDING"));
                }
                diagnostic.append(String.join(", ", depDetails)).append("\n");
            }

            String diagnosticMsg = diagnostic.toString().trim();

            // 2. Update record status to stage timeout status
            String timeoutStatus = stageNameToTimeoutStatus(stage.name());
            refDbService.updatePipelineStatus(record.id(), timeoutStatus, diagnosticMsg);

            // 3. Update integration status to "timeout"
            integrationStatusService.updateCpStatusForRecord(record.id(), "timeout", diagnosticMsg);

            // 4. Emit SSE event with timeout diagnostic
            if (record.requestId() != null) {
                stageMonitorService.sendEvent(record.requestId(), "PIPELINE_STAGE_TIMEOUT",
                    Map.of(
                        "recordId",       record.id(),
                        "stage",          stage.name(),
                        "timeoutStatus",  timeoutStatus,
                        "elapsedMinutes", elapsed.toMinutes(),
                        "timeoutMinutes", stageTimeout.toMinutes(),
                        "diagnostic",     diagnosticMsg,
                        "timestamp",      Instant.now().toString()
                    ));
            }

            // 5. Log warning with full diagnostic information
            log.warn(diagnosticMsg);

        } catch (Exception e) {
            log.error("Error handling stage timeout for record {} stage '{}': {}",
                     record.id(), stage.name(), e.getMessage(), e);
        }
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    /**
     * Convert stage name to monitoring status string (e.g., "cp" â†’ "CP_MONITORING").
     */
    private String stageNameToMonitoringStatus(String stageName) {
        return stageName.toUpperCase() + "_MONITORING";
    }

    /**
     * Convert stage name to timeout status string (e.g., "cp" â†’ "CP_TIMEOUT").
     */
    private String stageNameToTimeoutStatus(String stageName) {
        return stageName.toUpperCase() + "_TIMEOUT";
    }

    /**
     * Resolve the timeout duration for a stage.
     *
     * Checks stage config for "timeoutMinutes"; falls back to DEFAULT_STAGE_TIMEOUT_MINUTES.
     */
    private Duration getStageTimeout(StageDefinition stage) {
        if (stage.config() == null || stage.config().isEmpty()) {
            return Duration.ofMinutes(DEFAULT_STAGE_TIMEOUT_MINUTES);
        }
        Object timeoutObj = stage.config().get("timeoutMinutes");
        if (timeoutObj instanceof Number n) {
            long minutes = n.longValue();
            return Duration.ofMinutes(Math.max(1, minutes));
        }
        return Duration.ofMinutes(DEFAULT_STAGE_TIMEOUT_MINUTES);
    }
}


