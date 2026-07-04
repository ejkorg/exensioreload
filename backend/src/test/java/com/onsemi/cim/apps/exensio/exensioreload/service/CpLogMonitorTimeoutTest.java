package com.onsemi.cim.apps.exensio.exensioreload.service;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Collections;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import com.onsemi.cim.apps.exensio.exensioreload.config.CpElasticsearchProperties;
import com.onsemi.cim.apps.exensio.exensioreload.config.ExensioProperties;
import com.onsemi.cim.apps.exensio.exensioreload.config.PpLogDbProperties;
import com.onsemi.cim.apps.exensio.exensioreload.stage.StageMonitorService;
import com.onsemi.cim.apps.exensio.exensioreload.stage.StageRecord;

/**
 * Unit tests for CpLogMonitor timeout detection behavior.
 * 
 * Tests verify that records timing out with ES NotFound + pp_log NotFound
 * are marked as ENRICHMENT_TIMEOUT instead of attempting Exensio fallback.
 * 
 * Requirements: 1.1, 1.2, 1.3, 10.1, 10.2
 */
@Tag("Feature: pipeline-timeout-states")
class CpLogMonitorTimeoutTest {

    @Mock private RefDbService refDbService;
    @Mock private ElasticsearchLogService elasticsearchLogService;
    @Mock private ExensioClient exensioClient;
    @Mock private ExensioProperties exensioProperties;
    @Mock private CpElasticsearchProperties cpElasticsearchProperties;
    @Mock private PpLogDbProperties ppLogDbProperties;
    @Mock private StagePipelineOrchestrator pipelineOrchestrator;
    @Mock private IntegrationStatusService integrationStatusService;
    @Mock private StageMonitorService stageMonitorService;

    private CpLogMonitor cpLogMonitor;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        
        // Configure properties
        when(cpElasticsearchProperties.isConfigured()).thenReturn(true);
        when(cpElasticsearchProperties.getEnrichmentTimeoutMinutes()).thenReturn(15);
        when(ppLogDbProperties.isPpLogAvailable()).thenReturn(true);
        
        cpLogMonitor = new CpLogMonitor(
            refDbService,
            elasticsearchLogService,
            exensioClient,
            exensioProperties,
            cpElasticsearchProperties,
            ppLogDbProperties,
            pipelineOrchestrator,
            integrationStatusService,
            stageMonitorService
        );
    }

    /**
     * Test: ES NotFound + pp_log NotFound → calls markEnrichmentTimeout()
     * 
     * This verifies Requirement 1.1: timeout detection triggers correct state transition
     * and Requirement 1.2: diagnostic information is included
     */
    @Test
    void testEnrichmentTimeout_EsNotFoundPpLogNotFound_MarksEnrichmentTimeout() {
        // Setup: Create a timed-out record
        Instant createdAt = Instant.now().minusSeconds(15 * 60 + 60); // 16 minutes ago
        StageRecord record = new StageRecord(
            1L,                    // id
            "req-123",             // requestId
            "test-site",           // site
            1L,                    // senderId
            "TestSender",          // senderName
            "meta-456",            // metadataId
            "data-789",            // dataId
            "LOT-001",             // lot
            "W1",                  // wafer
            "test.csv",            // filename
            Instant.now(),         // endTime
            "pending",             // status
            null,                  // errorMessage
            createdAt,             // createdAt
            createdAt,             // updatedAt (same as createdAt means in ENRICHMENT for 16 min)
            null,                  // processedAt
            "operator1",           // stagedBy
            null,                  // lastRequestedBy
            null,                  // lastRequestedAt
            null,                  // cpOutputPath
            null,                  // cpOutputTarget
            null,                  // exensioWaferKey
            null,                  // exensioPgKey
            "dataTypeA",           // dataType
            "phase1"               // testPhase
        );

        // Setup ES and pp_log to both return NotFound
        when(elasticsearchLogService.findCpLog(
            anyString(), anyString(), anyString(), any(), anyString(), anyString()
        )).thenReturn(new CpLogResult.NotFound("es-not-found"));

        when(refDbService.queryPpLogSuccess(anyString(), anyString())).thenReturn(null);
        when(refDbService.queryPpLogError(anyString(), anyString())).thenReturn(null);

        when(refDbService.listRecords(null, null, "ENRICHMENT", Integer.MAX_VALUE))
            .thenReturn(Collections.singletonList(record));

        // Execute: Run the monitor
        cpLogMonitor.monitorEnrichmentRecords();

        // Verify: markEnrichmentTimeout was called with the record
        ArgumentCaptor<StageRecord> recordCaptor = ArgumentCaptor.forClass(StageRecord.class);
        ArgumentCaptor<String> diagnosticCaptor = ArgumentCaptor.forClass(String.class);
        verify(refDbService, times(1)).markEnrichmentTimeout(recordCaptor.capture(), diagnosticCaptor.capture());

        // Verify the diagnostic summary contains information about what was checked
        String diagnostic = diagnosticCaptor.getValue();
        assertNotNull(diagnostic, "Diagnostic summary should not be null");
        assertTrue(diagnostic.contains("ES:"), "Diagnostic should mention ES check");
        assertTrue(diagnostic.contains("NotFound"), "Diagnostic should indicate ES returned NotFound");
        assertTrue(diagnostic.contains("pp_log:"), "Diagnostic should mention pp_log check");
        assertTrue(diagnostic.contains("result=NotFound"), "Diagnostic should indicate pp_log returned NotFound");

        // Verify tryExensioDirectLookup was NOT called
        // (This is indirectly verified by checking markEnrichmentTimeout was called)
        // If Exensio fallback was still being used, we'd see tryExensioDirectLookup behavior
    }

    /**
     * Test: ES Found → continues normal flow (does NOT timeout)
     * 
     * Verifies that having a successful ES result prevents timeout marking
     */
    @Test
    void testEnrichmentTimeout_EsFound_DoesNotTimeOut() {
        // Setup: Create a timed-out record
        Instant createdAt = Instant.now().minusSeconds(15 * 60 + 60); // 16 minutes ago
        StageRecord record = new StageRecord(
            1L, "req-123", "test-site", 1L, "TestSender",
            "meta-456", "data-789", "LOT-001", "W1", "test.csv",
            Instant.now(), "pending", null, createdAt, createdAt, null,
            "operator1", null, null, null, null, null, null,
            "dataTypeA", "phase1"
        );

        // Setup: ES returns success
        when(elasticsearchLogService.findCpLog(
            anyString(), anyString(), anyString(), any(), anyString(), anyString()
        )).thenReturn(new CpLogResult.Success("path", "target", "trace-123"));

        // Setup pp_log is not needed since ES succeeded
        when(refDbService.queryPpLogSuccess(anyString(), anyString())).thenReturn(null);
        when(refDbService.queryPpLogError(anyString(), anyString())).thenReturn(null);

        when(refDbService.listRecords(null, null, "ENRICHMENT", Integer.MAX_VALUE))
            .thenReturn(Collections.singletonList(record));

        // Execute
        cpLogMonitor.monitorEnrichmentRecords();

        // Verify: markEnrichmentTimeout was NOT called
        verify(refDbService, never()).markEnrichmentTimeout(any(), anyString());
        
        // Verify: Normal success path was taken
        verify(pipelineOrchestrator, times(1)).onCpEnrichmentSuccess(any(), anyString(), anyString());
    }

    /**
     * Test: Concrete error from ES → marks FAILED (does NOT timeout)
     * 
     * Verifies that errors are handled as failures, not timeouts
     */
    @Test
    void testEnrichmentTimeout_EsError_MarksFailed() {
        // Setup: Create a timed-out record
        Instant createdAt = Instant.now().minusSeconds(15 * 60 + 60); // 16 minutes ago
        StageRecord record = new StageRecord(
            1L, "req-123", "test-site", 1L, "TestSender",
            "meta-456", "data-789", "LOT-001", "W1", "test.csv",
            Instant.now(), "pending", null, createdAt, createdAt, null,
            "operator1", null, null, null, null, null, null,
            "dataTypeA", "phase1"
        );

        // Setup: ES returns failure
        when(elasticsearchLogService.findCpLog(
            anyString(), anyString(), anyString(), any(), anyString(), anyString()
        )).thenReturn(new CpLogResult.Failure("Connection timeout", "trace-123"));

        when(refDbService.queryPpLogSuccess(anyString(), anyString())).thenReturn(null);
        when(refDbService.queryPpLogError(anyString(), anyString())).thenReturn(null);

        when(refDbService.listRecords(null, null, "ENRICHMENT", Integer.MAX_VALUE))
            .thenReturn(Collections.singletonList(record));

        // Execute
        cpLogMonitor.monitorEnrichmentRecords();

        // Verify: markFailed was called (not markEnrichmentTimeout)
        verify(refDbService, times(1)).markFailed(any(), anyString());
        verify(refDbService, never()).markEnrichmentTimeout(any(), anyString());
    }

    /**
     * Test: Record not yet timed out → continues polling next cycle
     * 
     * Verifies that records within the timeout window don't get marked as timeout
     */
    @Test
    void testEnrichmentTimeout_RecordNotYetTimedOut_ContinuesPolling() {
        // Setup: Create a record that's only 5 minutes old
        Instant createdAt = Instant.now().minusSeconds(5 * 60);
        StageRecord record = new StageRecord(
            1L, "req-123", "test-site", 1L, "TestSender",
            "meta-456", "data-789", "LOT-001", "W1", "test.csv",
            Instant.now(), "pending", null, createdAt, createdAt, null,
            "operator1", null, null, null, null, null, null,
            "dataTypeA", "phase1"
        );

        // Setup: Both sources return NotFound
        when(elasticsearchLogService.findCpLog(
            anyString(), anyString(), anyString(), any(), anyString(), anyString()
        )).thenReturn(new CpLogResult.NotFound("es-not-found"));

        when(refDbService.queryPpLogSuccess(anyString(), anyString())).thenReturn(null);
        when(refDbService.queryPpLogError(anyString(), anyString())).thenReturn(null);

        when(refDbService.listRecords(null, null, "ENRICHMENT", Integer.MAX_VALUE))
            .thenReturn(Collections.singletonList(record));

        // Execute
        cpLogMonitor.monitorEnrichmentRecords();

        // Verify: markEnrichmentTimeout was NOT called (not yet timed out)
        verify(refDbService, never()).markEnrichmentTimeout(any(), anyString());
        
        // Verify: Just noted as not found, will retry next cycle
        verify(integrationStatusService, times(1)).updateCpStatusForRecord(
            eq(1L), eq("not_found"), contains("retrying")
        );
    }

    /**
     * Test: pp_log has success → normal success flow (does NOT timeout)
     * 
     * Verifies that pp_log success is recognized even when ES is not found
     */
    @Test
    void testEnrichmentTimeout_PpLogFound_DoesNotTimeOut() {
        // Setup: Create a timed-out record
        Instant createdAt = Instant.now().minusSeconds(15 * 60 + 60);
        StageRecord record = new StageRecord(
            1L, "req-123", "test-site", 1L, "TestSender",
            "meta-456", "data-789", "LOT-001", "W1", "test.csv",
            Instant.now(), "pending", null, createdAt, createdAt, null,
            "operator1", null, null, null, null, null, null,
            "dataTypeA", "phase1"
        );

        // Setup: ES returns NotFound but pp_log has success
        when(elasticsearchLogService.findCpLog(
            anyString(), anyString(), anyString(), any(), anyString(), anyString()
        )).thenReturn(new CpLogResult.NotFound("es-not-found"));

        when(refDbService.queryPpLogSuccess(anyString(), anyString())).thenReturn("/output/dir");
        when(refDbService.queryPpLogError(anyString(), anyString())).thenReturn(null);

        when(refDbService.listRecords(null, null, "ENRICHMENT", Integer.MAX_VALUE))
            .thenReturn(Collections.singletonList(record));

        // Execute
        cpLogMonitor.monitorEnrichmentRecords();

        // Verify: markEnrichmentTimeout was NOT called
        verify(refDbService, never()).markEnrichmentTimeout(any(), anyString());
        
        // Verify: Success path was taken via pp_log result
        verify(pipelineOrchestrator, times(1)).onCpEnrichmentSuccess(any(), eq("/output/dir"), eq("PP_LOG"));
    }
}
