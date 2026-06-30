package com.onsemi.cim.apps.exensio.exensioreload.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jmx.export.annotation.ManagedAttribute;
import org.springframework.jmx.export.annotation.ManagedResource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.onsemi.cim.apps.exensio.exensioreload.config.CpElasticsearchProperties;
import com.onsemi.cim.apps.exensio.exensioreload.config.ExensioProperties;
import com.onsemi.cim.apps.exensio.exensioreload.stage.StageMonitorService;
import com.onsemi.cim.apps.exensio.exensioreload.stage.StageRecord;

/**
 * Scheduled monitor that polls Elasticsearch and pp_log in parallel for CP enrichment
 * outcomes and drives status transitions for records in ENRICHMENT status.
 *
 * <p>Poll cycle:</p>
 * <ol>
 *   <li>Load all ENRICHMENT records from SENDER_STAGE</li>
 *   <li>For each record, query ES and pp_log simultaneously</li>
 *   <li>Consolidate results — either source's positive result wins</li>
 *   <li>On success → transition to EXENSIO_LOADING</li>
 *   <li>On failure → transition to FAILED</li>
 *   <li>On both NotFound + timeout → try Exensio API direct lookup</li>
 *   <li>If Exensio also NotFound → mark DONE with manual-verify indicator</li>
 * </ol>
 */
@Component
@ManagedResource(
        objectName = "com.onsemi.exensio:type=CpLogMonitor",
        description = "CP Elasticsearch Log Monitor metrics"
)
public class CpLogMonitor {

    private static final Logger log = LoggerFactory.getLogger(CpLogMonitor.class);

    private static final int MAX_ERROR_MESSAGE_LENGTH = 500;

    private final RefDbService refDbService;
    private final ElasticsearchLogService elasticsearchLogService;
    private final ExensioClient exensioClient;
    private final ExensioProperties exensioProperties;
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
                        ExensioClient exensioClient,
                        ExensioProperties exensioProperties,
                        CpElasticsearchProperties props,
                        StagePipelineOrchestrator pipelineOrchestrator,
                        IntegrationStatusService integrationStatusService,
                        StageMonitorService stageMonitorService) {
        this.refDbService = refDbService;
        this.elasticsearchLogService = elasticsearchLogService;
        this.exensioClient = exensioClient;
        this.exensioProperties = exensioProperties;
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
     * Evaluates a single ENRICHMENT record by querying ES and pp_log in parallel,
     * consolidating results, and falling through to Exensio direct lookup on timeout.
     */
    private void processRecord(StageRecord record) {
        Instant lookbackTime = record.updatedAt() != null ? record.updatedAt() : record.createdAt();
        Instant esLookbackTime = lookbackTime.minusSeconds(120);
        String requestId = record.requestId();
        long stageRecordId = record.id();

        log.debug("processRecord: createdAt={}, updatedAt={}, esLookbackTime={}", 
                record.createdAt(), record.updatedAt(), esLookbackTime);

        // --- Step 1: Query ES and pp_log in parallel ---
        CompletableFuture<CpLogResult> esFuture = CompletableFuture.supplyAsync(() -> {
            try {
                return elasticsearchLogService.findCpLog(
                    record.metadataId(), record.dataId(), record.lot(),
                    esLookbackTime, record.site(), record.filename());
            } catch (Exception e) {
                log.warn("ES query failed for record id={}: {}", record.id(), e.getMessage());
                return new CpLogResult.NotFound("es-error-" + java.util.UUID.randomUUID());
            }
        });

        CompletableFuture<PpLogResult> ppLogFuture = CompletableFuture.supplyAsync(() -> {
            try {
                String outputDir = refDbService.queryPpLogSuccess(record.lot(), record.metadataId());
                if (outputDir != null) {
                    return new PpLogResult.Success(outputDir);
                }
                String errMsg = refDbService.queryPpLogError(record.lot(), record.metadataId());
                if (errMsg != null) {
                    return new PpLogResult.Failure(errMsg);
                }
                return new PpLogResult.NotFound();
            } catch (Exception e) {
                log.warn("pp_log query failed for record id={}: {}", record.id(), e.getMessage());
                return new PpLogResult.NotFound();
            }
        });

        CpLogResult esResult;
        PpLogResult ppLogResult;
        try {
            esResult = esFuture.get();
            ppLogResult = ppLogFuture.get();
        } catch (Exception e) {
            log.warn("Parallel query interrupted for record id={}: {}", record.id(), e.getMessage());
            integrationStatusService.updateCpStatusForRecord(stageRecordId, "error", "Parallel query failed: " + e.getMessage());
            integrationStatusService.updateElasticsearch(requestId, "error", "Parallel query failed: " + e.getMessage());
            return;
        }

        // --- Step 2: Consolidate results ---
        // Priority: pp_log Success > ES Success > pp_log Failure > ES Failure > NotFound
        // pp_log is the production source of truth — if it has the record, that's final.
        if (ppLogResult instanceof PpLogResult.Success ppSuccess) {
            log.info("CP enrichment success (pp_log) for record id={} dataId={}: output={}",
                    record.id(), record.dataId(), ppSuccess.outputDirectory());
            String statusMsg = String.format("CP enrichment completed via pp_log: %s", ppSuccess.outputDirectory());
            integrationStatusService.updateCpStatusForRecord(stageRecordId, "success", statusMsg);
            integrationStatusService.updateElasticsearch(requestId, "success", statusMsg);
            successCount.incrementAndGet();
            pipelineOrchestrator.onCpEnrichmentSuccess(record, ppSuccess.outputDirectory(), "PP_LOG");
            emitRowUpdateSse(record, requestId);
            return;
        }

        if (esResult instanceof CpLogResult.Success success) {
            log.info("CP enrichment success (ES) for record id={} dataId={}: path={} target={} traceId={}",
                    record.id(), record.dataId(), success.outputPath(), success.outputTarget(), success.traceId());
            String statusMsg = String.format("CP enrichment success: %s -> %s (traceId=%s)",
                    success.outputPath(), success.outputTarget(), success.traceId());
            integrationStatusService.updateCpStatusForRecord(stageRecordId, "success", statusMsg);
            integrationStatusService.updateElasticsearch(requestId, "success", statusMsg);
            successCount.incrementAndGet();
            pipelineOrchestrator.onCpEnrichmentSuccess(record, success.outputPath(), success.outputTarget());
            emitRowUpdateSse(record, requestId);
            return;
        }

        if (ppLogResult instanceof PpLogResult.Failure ppFailure) {
            log.info("CP enrichment failure (pp_log) for record id={} dataId={}: {}",
                    record.id(), record.dataId(), ppFailure.errorMessage());
            String statusMsg = String.format(
                    "[pp_log Failure] lot=%s, idFile=%s, filename=%s, process_code!=0, log_message=\"%s\"",
                    record.lot(), record.metadataId(), record.filename(), ppFailure.errorMessage());
            integrationStatusService.updateCpStatusForRecord(stageRecordId, "failure", statusMsg);
            integrationStatusService.updateElasticsearch(requestId, "failure", statusMsg);
            failureCount.incrementAndGet();
            refDbService.markFailed(record, statusMsg);
            emitRowUpdateSse(record, requestId);
            return;
        }

        if (esResult instanceof CpLogResult.Failure failure) {
            String errorMessage = truncateErrorMessage(failure.errorMessage());
            log.info("CP enrichment failure (ES) for record id={} dataId={}: {}",
                    record.id(), record.dataId(), errorMessage);
            String statusMsg = String.format(
                    "[ES Failure] lot=%s, idFile=%s, dataId=%s, log.level=ERROR, message=\"%s\", traceId=%s",
                    record.lot(), record.metadataId(), record.dataId(),
                    errorMessage, failure.traceId());
            integrationStatusService.updateCpStatusForRecord(stageRecordId, "failure", statusMsg);
            integrationStatusService.updateElasticsearch(requestId, "failure", statusMsg);
            failureCount.incrementAndGet();
            refDbService.markFailed(record, statusMsg);
            emitRowUpdateSse(record, requestId);
            return;
        }

        // --- Step 3: Both ES and pp_log returned NotFound ---
        if (isTimedOut(record)) {
            timeoutCount.incrementAndGet();
            String timeoutMsg = "CP enrichment timeout — no log found in ES or pp_log after "
                    + props.getEnrichmentTimeoutMinutes() + " minutes";
            log.info("{} for record id={} dataId={}, trying Exensio direct lookup", timeoutMsg, record.id(), record.dataId());

            // Try Exensio direct lookup before giving up
            tryExensioDirectLookup(record, requestId, stageRecordId, timeoutMsg);
        } else {
            log.debug("No CP log yet for record id={} dataId={} — will retry next cycle",
                    record.id(), record.dataId());
            String notFoundMsg = "No ES log or pp_log entry — retrying";
            integrationStatusService.updateCpStatusForRecord(stageRecordId, "not_found", notFoundMsg);
            integrationStatusService.updateElasticsearch(requestId, "not_found", notFoundMsg);
            emitRowUpdateSse(record, requestId);
        }
    }

    /**
     * Attempts a direct Exensio single-record lookup when ES + pp_log timed out.
     * If Exensio finds the wafer, marks DONE with keys. Otherwise marks DONE
     * with a manual-verification indicator — we can't assume failure when we
     * simply have no info from the enrichment pipeline.
     */
    private void tryExensioDirectLookup(StageRecord record, String requestId, long stageRecordId, String timeoutMsg) {
        // Build a diagnostic summary of everything attempted
        String diagnosticSummary = String.format(
                "ES: idData=%s since=%s; pp_log: lot=%s idFile=%s;",
                record.dataId(), record.updatedAt() != null ? record.updatedAt() : record.createdAt(),
                record.lot(), record.metadataId());

        if (!exensioProperties.isConfigured()) {
            log.info("Exensio not configured — marking record id={} as DONE with manual verify", record.id());
            integrationStatusService.updateCpStatusForRecord(stageRecordId, "timeout", timeoutMsg);
            integrationStatusService.updateElasticsearch(requestId, "timeout", timeoutMsg);
            refDbService.markDoneManualVerify(record,
                    "[Enrichment Unresolved] " + diagnosticSummary + " Exensio not configured. Manual verification required.");
            emitRowUpdateSse(record, requestId);
            return;
        }

        try {
            boolean waferBlank = record.wafer() == null || record.wafer().isBlank();
            int pgcKey = com.onsemi.cim.apps.exensio.exensioreload.service.DataTypePgcKeyMapper.resolve(
                    record.dataType(), waferBlank);

            ExensioLotWaferResult exResult = exensioClient.lotWaferLookup(
                    record.lot(), record.wafer(), record.endTime(),
                    pgcKey, record.testPhase(),
                    record.filename(), record.metadataId(), record.dataId());

            switch (exResult) {
                case ExensioLotWaferResult.Found found -> {
                    log.info("Exensio direct lookup resolved record id={}: waferKey={}, pgKey={}",
                            record.id(), found.waferKey(), found.pgKey());
                    String statusMsg = String.format("Resolved via Exensio direct lookup: waferKey=%d, pgKey=%d",
                            found.waferKey(), found.pgKey());
                    integrationStatusService.updateCpStatusForRecord(stageRecordId, "success", statusMsg);
                    integrationStatusService.updateElasticsearch(requestId, "success", statusMsg);
                    successCount.incrementAndGet();
                    refDbService.markDoneFromExensio(record, found.waferKey(), found.pgKey());
                }
                case ExensioLotWaferResult.NotFound notFound -> {
                    log.info("Exensio direct lookup also not found for record id={} — marking DONE with manual verify",
                            record.id());
                    integrationStatusService.updateCpStatusForRecord(stageRecordId, "timeout", timeoutMsg);
                    integrationStatusService.updateElasticsearch(requestId, "timeout", timeoutMsg);
                    refDbService.markDoneManualVerify(record,
                            "[Enrichment Unresolved] " + diagnosticSummary
                            + " Exensio: not found for lot=" + record.lot() + " wafer=" + record.wafer()
                            + ". Manual verification required.");
                }
                case ExensioLotWaferResult.Error error -> {
                    log.warn("Exensio direct lookup error for record id={}: {}", record.id(), error.message());
                    integrationStatusService.updateCpStatusForRecord(stageRecordId, "timeout", timeoutMsg);
                    integrationStatusService.updateElasticsearch(requestId, "timeout", timeoutMsg);
                    refDbService.markDoneManualVerify(record,
                            "[Enrichment Unresolved] " + diagnosticSummary
                            + " Exensio: error=" + error.message()
                            + ". Manual verification required.");
                }
            }
        } catch (Exception e) {
            log.warn("Exensio direct lookup exception for record id={}: {}", record.id(), e.getMessage());
            integrationStatusService.updateCpStatusForRecord(stageRecordId, "timeout", timeoutMsg);
            integrationStatusService.updateElasticsearch(requestId, "timeout", timeoutMsg);
            refDbService.markDoneManualVerify(record,
                    timeoutMsg + " and Exensio API error: " + e.getMessage() + ". Manual verification required.");
        }

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

    // ── pp_log parallel query result ──────────────────────────────────────────

    sealed interface PpLogResult {
        record Success(String outputDirectory) implements PpLogResult {}
        record Failure(String errorMessage) implements PpLogResult {}
        record NotFound() implements PpLogResult {}
    }
}
