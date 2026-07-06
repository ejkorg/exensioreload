package com.onsemi.cim.apps.exensio.exensioreload.stage;

import java.util.Arrays;
import java.util.List;

/**
 * Canonical pipeline status enum covering all states in the v3.0 state machine.
 *
 * <pre>
 * DISCOVERED → STAGED_TO_REFDB → QUEUED_FOR_CP → CP_CONSUMED → ELASTICSEARCH_MONITORING
 *                                                                  ├─ Failure → CP_FAILED
 *                                                                  ├─ Log found → EXENSIO_MONITORING
 *                                                                  └─ No log >15min → CP_TIMEOUT → EXENSIO_MONITORING
 *
 * EXENSIO_MONITORING
 *     ├─ Load completed → COMPLETED
 *     ├─ Exensio failure → LOAD_FAILED
 *     └─ Not found >timeout → COMPLETED_MANUAL_VERIFICATION_REQUIRED
 * </pre>
 */
public enum PipelineStatus {

    DISCOVERED("Discovered from DTAPORT"),
    STAGED_TO_REFDB("Loaded to REFDB"),
    QUEUED_FOR_CP("Inserted into Send Queue"),
    CP_CONSUMED("Removed from Send Queue by CP"),
    ELASTICSEARCH_MONITORING("Monitoring ES/CP"),
    CP_TIMEOUT("No ES activity within 15 min"),
    EXENSIO_MONITORING("Monitoring Exensio Load"),
    COMPLETED_MANUAL_VERIFICATION_REQUIRED("Manual Verification Required"),
    COMPLETED("Confirmed loaded in Exensio"),
    CP_FAILED("CP Failure detected in ES"),
    LOAD_FAILED("Exensio Load Failure"),
    CANCELLED("Cancelled");

    private final String displayLabel;

    PipelineStatus(String displayLabel) {
        this.displayLabel = displayLabel;
    }

    /** The exact string stored in the database status column. */
    public String dbValue() {
        return name();
    }

    /** Human-readable label for UI display. */
    public String displayLabel() {
        return displayLabel;
    }

    /** States that represent a confirmed end-of-pipeline (no further transitions expected). */
    public boolean isTerminal() {
        return this == COMPLETED
            || this == CP_FAILED
            || this == LOAD_FAILED
            || this == CANCELLED
            || this == COMPLETED_MANUAL_VERIFICATION_REQUIRED;
    }

    /** States that represent an error/failure condition (red in UI). */
    public boolean isFailure() {
        return this == CP_FAILED || this == LOAD_FAILED;
    }

    /** States that represent a warning/uncertain condition (amber in UI). */
    public boolean isWarning() {
        return this == CP_TIMEOUT || this == COMPLETED_MANUAL_VERIFICATION_REQUIRED;
    }

    /** All valid values that can appear in the SENDER_STAGE.status column. */
    public static List<String> allDbValues() {
        return Arrays.stream(values())
            .map(PipelineStatus::dbValue)
            .toList();
    }

    /** Look up a PipelineStatus from its database value (case-sensitive). */
    public static PipelineStatus fromDbValue(String dbValue) {
        if (dbValue == null) return null;
        return Arrays.stream(values())
            .filter(s -> s.dbValue().equals(dbValue))
            .findFirst()
            .orElse(null);
    }
}
