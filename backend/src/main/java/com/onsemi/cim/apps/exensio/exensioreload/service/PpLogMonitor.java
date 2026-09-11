package com.onsemi.cim.apps.exensio.exensioreload.service;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.onsemi.cim.apps.exensio.exensioreload.pipeline.BatchRecordProcessor;
import com.onsemi.cim.apps.exensio.exensioreload.pipeline.PipelineAction;
import com.onsemi.cim.apps.exensio.exensioreload.pipeline.PipelineOrchestrator;
import com.onsemi.cim.apps.exensio.exensioreload.stage.StageRecord;

/**
 * Scheduled monitor that polls records in PPLOG_MONITORING status for PP_LOG completion.
 * 
 * Integrates with PipelineOrchestrator to support dependency-aware pipeline execution.
 * For each record in PPLOG_MONITORING status:
 * 1. Call orchestrator.determineNextAction(record)
 * 2. If executeStage, invoke the orchestrator to run the PP_LOG handler
 * 3. If waitForDependencies, skip record and retry on next poll
 * 4. If useLegacyPath, fall back to direct processing (should not happen for pipeline-configured sites)
 * 
 * Batch Processing Optimization (Task 16.1):
 * - Groups records by site to minimize config lookups
 * - Processes records in parallel within each site batch using thread pool
 * - BatchRecordProcessor handles batch partitioning and parallel execution
 * 
 * Requirements: 4.1, 10.1, 11.1, 11.2, 16.1, 16.3
 */
@Component
public class PpLogMonitor {

    private static final Logger log = LoggerFactory.getLogger(PpLogMonitor.class);

    private final RefDbService refDbService;
    private final PipelineOrchestrator orchestrator;
    private final BatchRecordProcessor batchProcessor;

    public PpLogMonitor(RefDbService refDbService,
                        PipelineOrchestrator orchestrator,
                        BatchRecordProcessor batchProcessor) {
        this.refDbService = refDbService;
        this.orchestrator = orchestrator;
        this.batchProcessor = batchProcessor;
    }

    /**
     * Main polling loop for PP_LOG completion monitoring.
     * Runs on a fixed delay configured by {@code pplog.poll-interval-ms} (default: 60 000 ms).
     * 
     * Requirements: 5.1, 5.4, 11.2
     */
    @Scheduled(fixedDelayString = "${pplog.poll-interval-ms:60000}")
    public void monitorPpLogRecords() {
        List<StageRecord> pplogRecords;
        try {
            // Load records in PPLOG_MONITORING status
            pplogRecords = refDbService.listRecords(null, null, "PPLOG_MONITORING", Integer.MAX_VALUE);
        } catch (Exception e) {
            log.warn("Failed to load PPLOG_MONITORING records from DB — skipping poll cycle: {}", e.getMessage());
            return;
        }

        if (pplogRecords.isEmpty()) {
            log.debug("No PPLOG_MONITORING records found — nothing to poll");
            return;
        }

        log.debug("Polling PP_LOG for {} record(s)", pplogRecords.size());

        batchProcessor.processBatch(pplogRecords, record -> {
            // ===== ORCHESTRATOR INTEGRATION (Task 11.1) =====
            // Call orchestrator.determineNextAction() to get the action for this record
            PipelineAction action = orchestrator.determineNextAction(record);

            // Switch on the returned PipelineAction and handle each case
            if (action instanceof PipelineAction.UseLegacyPath) {
                // useLegacyPath() → legacy processing (should not happen for PP_LOG pipeline stage)
                log.debug("Record {} using legacy path for PP_LOG (unusual - should have pipeline config)", record.id());
                // No legacy processing for PP_LOG — this is a pipeline-only stage

            } else if (action instanceof PipelineAction.WaitForDependencies waitAction) {
                // waitForDependencies(blockedBy) → skip record and log waiting message
                log.debug("Record {} waiting for dependencies: {}", record.id(), waitAction.blockedByDependencies());
                // Skip record and continue to next — it will be retried on next poll cycle

            } else if (action instanceof PipelineAction.ExecuteStage execAction) {
                // executeStage(stage, handler) → call orchestrator.executeStage()
                log.debug("Record {} executing PP_LOG stage: {}", record.id(), execAction.stage().name());
                orchestrator.executeStage(record, execAction.stage(), execAction.handler());
                // No need to update status again — orchestrator.executeStage() handles it

            } else {
                // Defensive programming: unknown action type
                log.warn("Unknown PipelineAction type for record {}: {}", record.id(), action.getClass().getName());
            }
        });
    }
}
