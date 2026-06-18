package com.onsemi.cim.apps.exensio.exensioreload.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jmx.export.annotation.ManagedAttribute;
import org.springframework.jmx.export.annotation.ManagedResource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.onsemi.cim.apps.exensio.exensioreload.config.CpElasticsearchProperties;
import com.onsemi.cim.apps.exensio.exensioreload.stage.StageMonitorService;
import com.onsemi.cim.apps.exensio.exensioreload.stage.StageRecord;

/**
 * Scheduled monitor that polls Elasticsearch for CP enrichment outcomes and drives
 * accurate status transitions for records in ENRICHMENT status.
 *
 * <p>Poll cycle (Requirements 2.1, 2.2):</p>
 * <ol>
 *   <li>Load all ENRICHMENT records from SENDER_STAGE</li>
 *   <li>For each record, query Elasticsearch for a matching CP log entry</li>
 *   <li>On {@link CpLogResult.Success} → transition to EXENSIO_LOADING (Requirement 3.2)</li>
 *   <li>On {@link CpLogResult.Failure} → transition to FAILED (Requirement 4.2)</li>
 *   <li>On {@link CpLogResult.NotFound} + timeout exceeded → transition to FAILED (Requirement 2.7)</li>
 * </ol>
 *
 * <p>If Elasticsearch is not configured ({@code cp.elasticsearch.url} is blank), all polling
 * cycles are skipped without affecting any records (Requirement 6.7).</p>
 */
@Component
@ManagedResource(
        objectName = "com.onsemi.exensio:type=CpLogMonitor",
        description = "CP Elasticsearch Log Monitor metrics"
)
public class CpLogMonitor {

    private static final Logger log = LoggerFactory.getLogger(CpLogMonitor.class);

    /** Maximum characters stored in error_message column (Requirement 4.5). */
    private static final int MAX_ERROR_MESSAGE_LENGTH = 500;

    private final RefDbService refDbService;
    private final ElasticsearchLogService elasticsearchLogService;
    private final CpElasticsearchProperties props;
    private final StagePipelineOrchestrator pipelineOrchestrator;
    private final IntegrationStatusService integrationStatusService;
    private final StageMonitorService stageMonitorService;

    private final AtomicLong totalRecordsProcessed = new AtomicLong(0);
    private final AtomicLong successCount = new AtomicLong(0);
    private final AtomicLong failureCount = new AtomicLong(0);
    private final AtomicLong timeoutCount = new AtomicLong(0);

    public CpLogMonitor(RefDbService refDbService,
                        ElasticsearchLogService elasticsearchLogService,
                        CpElasticsearchProperties props,
                        StagePipelineOrchestrator pipelineOrchestrator,
                        IntegrationStatusService integrationStatusService,
                        StageMonitorService stageMonitorService) {
        this.refDbService = refDbService;
        this.elasticsearchLogService = elasticsearchLogService;
        this.props = props;
        this.pipelineOrchestrator = pipelineOrchestrator;
        this.integrationStatusService = integrationStatusService;
        this.stageMonitorService = stageMonitorService;
    }

    /**
     * Main polling loop. Runs on a fixed delay configured by {@code cp.elasticsearch.poll-interval-ms}
     * (default: 60 000 ms). Requirements: 2.1
     */
    @Scheduled(fixedDelayString = "${cp.elasticsearch.poll-interval-ms:60000}")
    public void monitorEnrichmentRecords() {
        if (!props.isConfigured()) {
            log.debug("Elasticsearch not configured — CP log polling disabled ({})",
                    "SenderQueueMonitor routes to Exensio API or DONE");
            return;
        }

        List<StageRecord> enrichmentRecords;
        try {
            // Requirement 2.2: query only ENRICHMENT records
            enrichmentRecords = refDbService.listRecords(null, null, "ENRICHMENT", Integer.MAX_VALUE);
        } catch (Exception e) {
            log.warn("Failed to load ENRICHMENT records from DB — skipping poll cycle: {}", e.getMessage());
            return;
        }

        if (enrichmentRecords.isEmpty()) {
            log.debug("No ENRICHMENT records found — nothing to poll");
            return;
        }

        log.debug("Polling Elasticsearch for {} ENRICHMENT record(s)", enrichmentRecords.size());

        for (StageRecord record : enrichmentRecords) {
            totalRecordsProcessed.incrementAndGet();
            processRecord(record);
        }
    }

    /**
     * Evaluates a single ENRICHMENT record against Elasticsearch and drives the appropriate
     * status transition.
     */
    private void processRecord(StageRecord record) {
        // Use createdAt as the lower bound for ES log timestamp matching
        // createdAt is when the record was first staged, which is before CP processes it
        Instant esLookbackTime = record.createdAt();
        String requestId = record.requestId();
        long stageRecordId = record.id();

        CpLogResult result;
        try {
            result = elasticsearchLogService.findCpLog(record.metadataId(), record.dataId(), record.lot(), esLookbackTime, record.site(), record.filename());
        } catch (ElasticsearchLogService.ElasticsearchQueryException e) {
            // Requirement 6.7: ES unreachable — log warning, skip this record, do not mark failed
            log.warn("Elasticsearch query failed for record id={} dataId={} — skipping: {}",
                    record.id(), record.dataId(), e.getMessage());
            // Update per-record status
            String errMsg = "ES query failed: " + e.getMessage();
            integrationStatusService.updateCpStatusForRecord(stageRecordId, "error", errMsg);
            integrationStatusService.updateElasticsearch(requestId, "error", errMsg);
            return;
        }

        switch (result) {
            case CpLogResult.Success success -> {
                log.info("CP enrichment success for record id={} dataId={}: path={} target={} traceId={}",
                        record.id(), record.dataId(), success.outputPath(), success.outputTarget(), success.traceId());
                // Update per-record status with traceId
                String statusMsg = String.format("CP enrichment success: %s -> %s (traceId=%s)",
                        success.outputPath(), success.outputTarget(), success.traceId());
                integrationStatusService.updateCpStatusForRecord(stageRecordId, "success", statusMsg);
                integrationStatusService.updateElasticsearch(requestId, "success", statusMsg);
                successCount.incrementAndGet();
                pipelineOrchestrator.onCpEnrichmentSuccess(record, success.outputPath(), success.outputTarget());
            }
            case CpLogResult.Failure failure -> {
                // Requirement 4.2, 4.3, 4.5: transition to FAILED with truncated error message
                String errorMessage = truncateErrorMessage(failure.errorMessage());
                log.info("CP enrichment failure for record id={} dataId={}: {} (traceId={})",
                        record.id(), record.dataId(), errorMessage, failure.traceId());
                // Update per-record status with traceId
                String statusMsg = String.format("CP enrichment failure: %s (traceId=%s)",
                        errorMessage, failure.traceId());
                integrationStatusService.updateCpStatusForRecord(stageRecordId, "failure", statusMsg);
                integrationStatusService.updateElasticsearch(requestId, "failure", statusMsg);
                failureCount.incrementAndGet();
                // Add failure context to the message for better UI display
                String contextMessage = "[CP Failure] " + statusMsg;
                refDbService.markFailed(record, contextMessage);
            }
            case CpLogResult.NotFound notFound -> {
                // ES returned NotFound - the enrichment may have happened externally (not through CP),
                // so fall back to pp_log to check if enrichment completed externally
                log.debug("No CP log found in ES for record id={} dataId={} (traceId={}) - checking pp_log fallback",
                        record.id(), record.dataId(), notFound.traceId());
                
                // Query pp_log to check if enrichment completed externally
                String ppLogOutputDir = refDbService.queryPpLogSuccess(record.lot(), record.filename());
                if (ppLogOutputDir != null) {
                    // pp_log shows success (process_code = 0)
                    log.info("CP enrichment completed externally via pp_log for record id={} dataId={} - output={} (traceId={})",
                            record.id(), record.dataId(), ppLogOutputDir, notFound.traceId());
                    String statusMsg = String.format("CP enrichment completed via pp_log: %s (traceId=%s)",
                            ppLogOutputDir, notFound.traceId());
                    integrationStatusService.updateCpStatusForRecord(stageRecordId, "success", statusMsg);
                    integrationStatusService.updateElasticsearch(requestId, "success", statusMsg);
                    successCount.incrementAndGet();
                    pipelineOrchestrator.onCpEnrichmentSuccess(record, ppLogOutputDir, "PP_LOG");
                } else {
                    // Check pp_log for failure (process_code != 0)
                    String ppLogError = refDbService.queryPpLogError(record.lot(), record.filename());
                    if (ppLogError != null) {
                        // pp_log shows failure
                        log.info("CP enrichment failed in pp_log for record id={} dataId={}: {} (traceId={})",
                                record.id(), record.dataId(), ppLogError, notFound.traceId());
                        String statusMsg = String.format("CP enrichment failed in pp_log: %s (traceId=%s)",
                                ppLogError, notFound.traceId());
                        integrationStatusService.updateCpStatusForRecord(stageRecordId, "failure", statusMsg);
                        integrationStatusService.updateElasticsearch(requestId, "failure", statusMsg);
                        failureCount.incrementAndGet();
                        String contextMessage = "[CP pp_log Failure] " + statusMsg;
                        refDbService.markFailed(record, contextMessage);
                    } else {
                        // No pp_log entry found - still waiting, retry next cycle
                        // Check timeout
                        if (isTimedOut(record)) {
                            String timeoutMessage = "CP enrichment timeout — no log found in ES or pp_log after "
                                    + props.getEnrichmentTimeoutMinutes() + " minutes";
                            log.info("CP enrichment timeout for record id={} dataId={} (traceId={})",
                                    record.id(), record.dataId(), notFound.traceId());
                            String statusMsg = String.format("%s (traceId=%s)", timeoutMessage, notFound.traceId());
                            integrationStatusService.updateCpStatusForRecord(stageRecordId, "timeout", statusMsg);
                            integrationStatusService.updateElasticsearch(requestId, "timeout", statusMsg);
                            timeoutCount.incrementAndGet();
                            String contextMessage = "[CP Timeout] " + statusMsg;
                            refDbService.markFailed(record, contextMessage);
                        } else {
                            log.debug("No CP log yet for record id={} dataId={} (traceId={}) — will retry next cycle",
                                    record.id(), record.dataId(), notFound.traceId());
                            String notFoundMsg = String.format("No ES log or pp_log entry — retrying (traceId=%s)", notFound.traceId());
                            integrationStatusService.updateCpStatusForRecord(stageRecordId, "not_found", notFoundMsg);
                            integrationStatusService.updateElasticsearch(requestId, "not_found", notFoundMsg);
                        }
                    }
                }
            }
        }

        // Emit ROW_UPDATE SSE event with per-record integration status
        emitRowUpdateSse(record, requestId);
    }

    /**
     * Emits a ROW_UPDATE SSE event with per-record integration status.
     * Best-effort: silently skips if no SSE emitter exists for the record.
     * Requirements: 4.2, 4.3
     */
    private void emitRowUpdateSse(StageRecord record, String requestId) {
        // Get per-file integration status from IntegrationStatusService
        var cpStatus = integrationStatusService.getCpStatusForRecord(record.id());
        var exensioStatus = integrationStatusService.getExensioStatusForRecord(record.id());

        Map<String, Object> evt = new java.util.HashMap<>();
        evt.put("id", record.id());
        evt.put("site", record.site());
        evt.put("senderId", record.senderId());
        evt.put("senderName", record.senderName());
        evt.put("metadataId", record.metadataId());
        evt.put("dataId", record.dataId());
        evt.put("lot", record.lot());
        evt.put("wafer", record.wafer());
        evt.put("filename", record.filename());
        evt.put("endTime", record.endTime() != null ? record.endTime().toString() : null);
        evt.put("status", record.status());
        evt.put("errorMessage", record.errorMessage());
        evt.put("createdAt", record.createdAt() != null ? record.createdAt().toString() : null);
        evt.put("updatedAt", record.updatedAt() != null ? record.updatedAt().toString() : null);
        evt.put("processedAt", record.processedAt() != null ? record.processedAt().toString() : null);
        evt.put("stagedBy", record.stagedBy());
        evt.put("lastRequestedBy", record.lastRequestedBy());
        evt.put("lastRequestedAt", record.lastRequestedAt() != null ? record.lastRequestedAt().toString() : null);
        evt.put("requestId", record.requestId());
        evt.put("cpOutputPath", record.cpOutputPath());
        evt.put("cpOutputTarget", record.cpOutputTarget());
        evt.put("exensioWaferKey", record.exensioWaferKey());
        evt.put("exensioPgKey", record.exensioPgKey());
        evt.put("dataType", record.dataType());
        evt.put("testPhase", record.testPhase());

        // Add per-file integration status fields
        if (cpStatus != null) {
            evt.put("cpIntegrationStatus", cpStatus.status());
            evt.put("cpIntegrationMessage", cpStatus.message());
        }
        if (exensioStatus != null) {
            evt.put("exensioIntegrationStatus", exensioStatus.status());
            evt.put("exensioIntegrationMessage", exensioStatus.message());
        }

        stageMonitorService.sendEvent(requestId, "ROW_UPDATE", evt);
    }

    /**
     * Returns true if the record has been in ENRICHMENT status longer than the configured timeout.
     * Requirement 2.7: timeout check using record.updatedAt().
     */
    private boolean isTimedOut(StageRecord record) {
        Instant enrichmentStartedAt = record.updatedAt() != null ? record.updatedAt() : record.createdAt();
        if (enrichmentStartedAt == null) {
            return false;
        }
        Instant timeoutDeadline = enrichmentStartedAt.plus(Duration.ofMinutes(props.getEnrichmentTimeoutMinutes()));
        return timeoutDeadline.isBefore(Instant.now());
    }

    /**
     * Truncates an error message to {@value MAX_ERROR_MESSAGE_LENGTH} characters.
     * Requirement 4.5
     */
    private String truncateErrorMessage(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= MAX_ERROR_MESSAGE_LENGTH
                ? message
                : message.substring(0, MAX_ERROR_MESSAGE_LENGTH) + "...";
    }

    @ManagedAttribute(description = "Total number of records processed across all poll cycles")
    public long getTotalRecordsProcessed() { return totalRecordsProcessed.get(); }

    @ManagedAttribute(description = "Total number of records successfully resolved")
    public long getSuccessCount() { return successCount.get(); }

    @ManagedAttribute(description = "Total number of records that failed")
    public long getFailureCount() { return failureCount.get(); }

    @ManagedAttribute(description = "Total number of records that timed out")
    public long getTimeoutCount() { return timeoutCount.get(); }

    @ManagedAttribute(description = "Success rate as a fraction (0.0 - 1.0)")
    public double getSuccessRate() {
        long total = totalRecordsProcessed.get();
        return total > 0 ? (double) successCount.get() / total : 0.0;
    }
}
