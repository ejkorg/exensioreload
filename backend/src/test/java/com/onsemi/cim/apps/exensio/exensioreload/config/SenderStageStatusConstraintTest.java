package com.onsemi.cim.apps.exensio.exensioreload.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.onsemi.cim.apps.exensio.exensioreload.stage.PipelineStatus;

/**
 * Validates that PipelineStatus contains all expected v3.0 pipeline states.
 */
@DisplayName("SENDER_STAGE Status Constraint Tests (v3.0)")
class SenderStageStatusConstraintTest {

    private static final List<String> ALL_STATUS_DB_VALUES = PipelineStatus.allDbValues();

    @Test
    @DisplayName("All 12 status values are defined")
    void testAllStatusesDefined() {
        assertEquals(12, ALL_STATUS_DB_VALUES.size(),
            "Must have exactly 12 status values in v3.0");
        assertFalse(ALL_STATUS_DB_VALUES.contains("pending"),
            "Deprecated status 'pending' must not be present");
        assertFalse(ALL_STATUS_DB_VALUES.contains("DONE"),
            "Deprecated status 'DONE' must not be present");
    }

    @Test
    @DisplayName("Each defined status is valid via fromDbValue")
    void testAllStatusesResolveCorrectly() {
        for (String dbValue : ALL_STATUS_DB_VALUES) {
            PipelineStatus ps = PipelineStatus.fromDbValue(dbValue);
            assertTrue(ps != null, "PipelineStatus.fromDbValue('" + dbValue + "') must succeed");
            assertEquals(dbValue, ps.dbValue(),
                "Round-trip: dbValue() must match the input");
        }
    }

    @Test
    @DisplayName("Key new and renamed states are present")
    void testKeyStatesPresent() {
        assertTrue(ALL_STATUS_DB_VALUES.contains("STAGED_TO_REFDB"),
            "Must include STAGED_TO_REFDB (replaces pending)");
        assertTrue(ALL_STATUS_DB_VALUES.contains("QUEUED_FOR_CP"),
            "Must include QUEUED_FOR_CP (replaces ENQUEUED)");
        assertTrue(ALL_STATUS_DB_VALUES.contains("ELASTICSEARCH_MONITORING"),
            "Must include ELASTICSEARCH_MONITORING (replaces ENRICHMENT)");
        assertTrue(ALL_STATUS_DB_VALUES.contains("CP_TIMEOUT"),
            "Must include CP_TIMEOUT (replaces ENRICHMENT_TIMEOUT)");
        assertTrue(ALL_STATUS_DB_VALUES.contains("EXENSIO_MONITORING"),
            "Must include EXENSIO_MONITORING (replaces EXENSIO_LOADING)");
        assertTrue(ALL_STATUS_DB_VALUES.contains("COMPLETED_MANUAL_VERIFICATION_REQUIRED"),
            "Must include COMPLETED_MANUAL_VERIFICATION_REQUIRED (replaces EXENSIO_TIMEOUT)");
        assertTrue(ALL_STATUS_DB_VALUES.contains("COMPLETED"),
            "Must include COMPLETED (replaces DONE)");
        assertTrue(ALL_STATUS_DB_VALUES.contains("CP_FAILED"),
            "Must include CP_FAILED (replaces CP-path FAILED)");
        assertTrue(ALL_STATUS_DB_VALUES.contains("LOAD_FAILED"),
            "Must include LOAD_FAILED (replaces Exensio-path FAILED)");
    }

    @Test
    @DisplayName("Terminal state classification is correct")
    void testTerminalStates() {
        for (PipelineStatus ps : Arrays.asList(
                PipelineStatus.COMPLETED,
                PipelineStatus.CP_FAILED,
                PipelineStatus.LOAD_FAILED,
                PipelineStatus.COMPLETED_MANUAL_VERIFICATION_REQUIRED,
                PipelineStatus.CANCELLED)) {
            assertTrue(ps.isTerminal(), ps.dbValue() + " must be terminal");
        }
        assertFalse(PipelineStatus.ELASTICSEARCH_MONITORING.isTerminal(),
            "ELASTICSEARCH_MONITORING must NOT be terminal");
        assertFalse(PipelineStatus.EXENSIO_MONITORING.isTerminal(),
            "EXENSIO_MONITORING must NOT be terminal");
    }

    @Test
    @DisplayName("Invalid status values return null")
    void testInvalidStatusReturnsNull() {
        for (String invalid : Arrays.asList("pending", "ENQUEUED", "DONE", "FAILED", "ENRICHMENT_TIMEOUT",
                "EXENSIO_TIMEOUT", "EXENSIO_LOADING", "ENRICHMENT", "PROCESSING", "", null)) {
            PipelineStatus ps = PipelineStatus.fromDbValue(invalid);
            assertTrue(ps == null,
                "Deprecated/invalid status '" + invalid + "' must return null from fromDbValue()");
        }
    }
}
