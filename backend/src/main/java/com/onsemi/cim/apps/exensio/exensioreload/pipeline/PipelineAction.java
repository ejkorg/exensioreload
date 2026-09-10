package com.onsemi.cim.apps.exensio.exensioreload.pipeline;

import java.util.List;
import java.util.Objects;

/**
 * Represents the action the orchestrator should take for a record.
 * 
 * This sealed interface defines the possible outcomes of determineNextAction():
 * - UseLegacyPath: Record has no pipeline config, use legacy behavior
 * - WaitForDependencies: Current stage dependencies not yet satisfied
 * - ExecuteStage: Current stage ready to execute
 */
public sealed interface PipelineAction {

    /**
     * Use legacy execution path (no pipeline config for this site).
     * 
     * Record should be processed using existing direct-processing logic,
     * bypassing the orchestrator entirely.
     */
    record UseLegacyPath() implements PipelineAction {}

    /**
     * Wait for dependencies to be satisfied.
     * 
     * Current stage cannot begin because one or more dependencies have not completed.
     * Record should remain in current monitoring status and be retried on next poll.
     */
    record WaitForDependencies(List<String> blockedByDependencies) implements PipelineAction {
        public WaitForDependencies {
            Objects.requireNonNull(blockedByDependencies, "blockedByDependencies must not be null");
        }
    }

    /**
     * Execute the current stage.
     * 
     * All dependencies are satisfied and the stage is ready to check for completion.
     * The handler should be called with the stage configuration.
     */
    record ExecuteStage(StageDefinition stage, StageHandler handler) implements PipelineAction {
        public ExecuteStage {
            Objects.requireNonNull(stage, "stage must not be null");
            Objects.requireNonNull(handler, "handler must not be null");
        }
    }

    /**
     * Factory method to create UseLegacyPath action.
     */
    static UseLegacyPath useLegacyPath() {
        return new UseLegacyPath();
    }

    /**
     * Factory method to create WaitForDependencies action.
     */
    static WaitForDependencies waitForDependencies(List<String> blockedBy) {
        return new WaitForDependencies(blockedBy);
    }

    /**
     * Factory method to create ExecuteStage action.
     */
    static ExecuteStage executeStage(StageDefinition stage, StageHandler handler) {
        return new ExecuteStage(stage, handler);
    }
}
