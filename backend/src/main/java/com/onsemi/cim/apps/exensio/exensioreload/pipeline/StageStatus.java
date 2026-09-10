package com.onsemi.cim.apps.exensio.exensioreload.pipeline;

/**
 * Enum of possible completion statuses returned by stage handlers.
 * Used by StageHandler implementations to indicate the result of a stage check.
 */
public enum StageStatus {
    /**
     * Stage has completed successfully.
     * The orchestrator will progress to the next stage.
     */
    COMPLETED,

    /**
     * Stage completion data was not found.
     * The orchestrator will retry on the next poll cycle.
     */
    NOT_FOUND,

    /**
     * A transient or permanent error occurred while checking stage completion.
     * The orchestrator will retry on the next poll cycle or fail after threshold.
     */
    ERROR,

    /**
     * Stage has exceeded its configured timeout duration.
     * The orchestrator will transition the record to a timeout status.
     */
    TIMEOUT
}
