package com.onsemi.cim.apps.exensio.exensioreload.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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
import com.onsemi.cim.apps.exensio.exensioreload.config.PpLogDbProperties;
import com.onsemi.cim.apps.exensio.exensioreload.stage.StageMonitorService;
import com.onsemi.cim.apps.exensio.exensioreload.stage.StageRecord;

import jakarta.annotation.PostConstruct;

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
    private final PpLogDbProperties ppLogDbProperties;
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
                        PpLogDbProperties ppLogDbProperties,
                        StagePipelineOrchestrator pipelineOrchestrator,
                        IntegrationStatusService integrationStatusService,
                        StageMonitorService stageMonitorService) {
        this.refDbService = refDbService;
        this.elasticsearchLogService = elasticsearchLogService;
        this.exensioClient = exensioClient;
        this.exensioProperties = exensioProperties;
        this.props = props;
        this.ppLogDbProperties = ppLogDbProperties;
        this.pipelineOrchestrator = pipelineOrchestrator;
        this.integrationStatusService = integrationStatusService;
        this.stageMonitorService = stageMonitorService;
    }

    @PostConstruct
    public void logActiveEnrichmentSources() {
        boolean hasEs = props.isConfigured();
        boolean hasPpLog = ppLogDbProperties.isPpLogAvailable();
        if (hasEs && hasPpLog) {
            log.info("CpLogMonitor: enrichment sources active — Elasticsearch + pp_log (parallel)");
        } else if (hasEs) {
            log.info("CpLogMonitor: enrichment sources active — Elasticsearch only (pp_log disabled)");
        } else if (hasPpLog) {
            log.info("CpLogMonitor: enrichment sources active — pp_log only (Elasticsearch not configured)");
        } else {
            log.info("CpLogMonitor: no enrichment sources configured — polling disabled (will use Exensio or DONE)");
        }
    }

    /**
     * Main polling loop. Runs on a fixed delay configured by {@code cp.elasticsearch.poll-interval-ms}
     * (default: 60 000 ms). Requirements: 2.1
     */
    @Scheduled(fixedDelayString = "${cp.elasticsearch.poll-interval-ms:60000}")
    public void monitorEnrichmentRecords() {
        boolean hasEs = props.isConfigured();
        boolean hasPpLog = ppLogDbProperties.isPpLogAvailable();

        if (!hasEs && !hasPpLog) {
            log.debug("Neither Elasticsearch nor pp_log available — resolving any stuck ENRICHMENT records immediately");
            resolveStuckEnrichmentRecords();
            return;
        }

        List<StageRecord> enrichmentRecords;
        try {
            // Requirement 2.2: query only ENRICHMENT records
            enrichmentRecords = refDbService.listRecords(null, null, "ELASTICSEARCH_MONITORING", Integer.MAX_VALUE);
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
     * Either source is skipped (returns NotFound immediately) when not available.
     */
    private void processRecord(StageRecord record) {
        Instant lookbackTime = getEnrichmentStartedAt(record);
        // Use configurable lookback buffer (default 900s / 15 minutes) to account for:
        // 1. Clock skew between application and ES cluster
        // 2. Processing delays between enrichment start and log indexing
        // 3. Potential timezone conversion drift (mitigated by UTC enforcement)
        int bufferSeconds = props.getLookbackBufferSeconds();
        Instant esLookbackTime = lookbackTime.minusSeconds(bufferSeconds);
        String requestId = record.requestId();
        long stageRecordId = record.id();

        log.debug("processRecord: createdAt={}, enrichmentStartedAt={}, esLookbackTime={}, bufferSeconds={}", 
            record.createdAt(), getEnrichmentStartedAt(record), esLookbackTime, bufferSeconds);

        boolean hasEs = props.isConfigured();
        boolean hasPpLog = ppLogDbProperties.isPpLogAvailable();

        // --- Step 1: Query ES and pp_log in parallel (skip sources that are not available) ---
        CompletableFuture<CpLogResult> esFuture = hasEs
            ? CompletableFuture.supplyAsync(() -> {
                try {
                    return elasticsearchLogService.findCpLog(
                        record.metadataId(), record.dataId(), record.lot(),
                        esLookbackTime, record.site(), record.filename());
                } catch (Exception e) {
                    log.warn("ES query failed for record id={}: {}", record.id(), e.getMessage());
                    return new CpLogResult.NotFound("es-error-" + java.util.UUID.randomUUID());
                }
              })
            : CompletableFuture.completedFuture(new CpLogResult.NotFound("es-not-configured"));

        if (!hasEs) {
            log.debug("ES not configured — skipping ES query for record id={}", record.id());
        }

        CompletableFuture<PpLogResult> ppLogFuture = hasPpLog
            ? CompletableFuture.supplyAsync(() -> {
                try {
                    Instant ppLogLookback = getEnrichmentStartedAt(record).minusSeconds(bufferSeconds);
                    RefDbService.PpLogRow row = refDbService.queryPpLog(record.lot(), ppLogLookback, record.filename());
                    if (row != null) {
                        if (row.processCode() == 0) {
                            return new PpLogResult.Success(row.outputDirectory());
                        }
                        return new PpLogResult.Failure(row.logMessage());
                    }
                    return new PpLogResult.NotFound();
                } catch (Exception e) {
                    log.warn("pp_log query failed for record id={}: {}", record.id(), e.getMessage());
                    return new PpLogResult.NotFound();
                }
              })
            : CompletableFuture.completedFuture(new PpLogResult.NotFound());

        CpLogResult esResult;
        PpLogResult ppLogResult;
        try {
            esResult = esFuture.get();
            ppLogResult = ppLogFuture.get();
        } catch (Exception e) {
            log.warn("Parallel query interrupted for record id={}: {}", record.id(), e.getMessage());
            integrationStatusService.updateCpStatusForRecord(stageRecordId, "error", "Parallel query failed: " + e.getMessage());
            integrationStatusService.updateElasticsearch(requestId, "error", "Parallel query failed: " + e.getMessage());
            esResult = new CpLogResult.NotFound("es-error-" + java.util.UUID.randomUUID());
            ppLogResult = new PpLogResult.NotFound();
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
            refDbService.markCpFailed(record, statusMsg);
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
            refDbService.markCpFailed(record, statusMsg);
            emitRowUpdateSse(record, requestId);
            return;
        }

        // --- Step 3: Both ES and pp_log returned NotFound ---
        if (isTimedOut(record)) {
            timeoutCount.incrementAndGet();
            
            // Build diagnostic summary of what was checked
            String diagnosticSummary = buildTimeoutDiagnosticSummary(record);
            
            if (exensioProperties.isConfigured()) {
                log.info("CP enrichment timeout for record id={} dataId={} — assuming success and verifying in Exensio", record.id(), record.dataId());
                refDbService.markExensioMonitoringPending(List.of(record));
                integrationStatusService.updateCpStatusForRecord(stageRecordId, "timeout", "CP enrichment timeout — assuming success and verifying in Exensio");
                integrationStatusService.updateElasticsearch(requestId, "timeout", diagnosticSummary);
                emitRowUpdateSse(record, requestId);
            } else {
                log.info("CP enrichment timeout for record id={} dataId={}: {}", record.id(), record.dataId(), diagnosticSummary);

                // Mark as ENRICHMENT_TIMEOUT (uncertain enrichment status)
                // This is honest accounting: we don't know if enrichment succeeded, failed, or needs retry
                refDbService.markCpTimeout(record, diagnosticSummary);
                integrationStatusService.updateCpStatusForRecord(stageRecordId, "timeout", 
                        "CP enrichment timeout — no log found in ES or pp_log after " 
                        + props.getEnrichmentTimeoutMinutes() + " minutes");
                integrationStatusService.updateElasticsearch(requestId, "timeout", diagnosticSummary);
                emitRowUpdateSse(record, requestId);
            }
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
     * Called when neither ES nor pp_log is configured. Any records already sitting in
     * ENRICHMENT status (e.g. left over from a previous configuration) must not stay
     * stuck indefinitely. Route them forward immediately using the same Exensio fallback
     * that the timeout path would eventually reach.
     */
    private void resolveStuckEnrichmentRecords() {
        List<StageRecord> stuck;
        try {
            stuck = refDbService.listRecords(null, null, "ELASTICSEARCH_MONITORING", Integer.MAX_VALUE);
        } catch (Exception e) {
            log.warn("resolveStuckEnrichmentRecords: failed to load ENRICHMENT records: {}", e.getMessage());
            return;
        }
        if (stuck.isEmpty()) {
            return;
        }
        log.info("resolveStuckEnrichmentRecords: {} record(s) found in ENRICHMENT with no enrichment sources — resolving immediately", stuck.size());
        String reason = "No enrichment sources configured (ES url blank, pp_log disabled) — bypassing enrichment wait";
        for (StageRecord record : stuck) {
            totalRecordsProcessed.incrementAndGet();
            tryExensioDirectLookup(record, record.requestId(), record.id(), reason);
        }
    }

    /**
     * Detects records stuck in ENRICHMENT status exceeding the enrichmentTimeoutMinutes threshold.
     * For each stuck record, emits an alert event and attempts auto-remediation via markDoneManualVerify.
     * 
     * <p>Requirements: 4, 8</p>
     */
    public void detectStuckEnrichmentRecords() {
        int timeoutMinutes = props.getEnrichmentTimeoutMinutes();
        
        List<StageRecord> stuckRecords;
        try {
            stuckRecords = refDbService.listRecords(null, null, "ELASTICSEARCH_MONITORING", Integer.MAX_VALUE);
        } catch (Exception e) {
            log.warn("detectStuckEnrichmentRecords: failed to load ENRICHMENT records: {}", e.getMessage());
            return;
        }

        if (stuckRecords.isEmpty()) {
            log.debug("detectStuckEnrichmentRecords: no records in ENRICHMENT status");
            return;
        }

        List<StageRecord> stuck = new ArrayList<>();
        Instant now = Instant.now();

        // Identify records that exceeded the timeout threshold
        for (StageRecord record : stuckRecords) {
            Instant enrichmentStartedAt = getEnrichmentStartedAt(record);
            if (enrichmentStartedAt == null) {
                continue;
            }
            
            Instant timeoutDeadline = enrichmentStartedAt.plus(Duration.ofMinutes(timeoutMinutes));
            if (timeoutDeadline.isBefore(now)) {
                stuck.add(record);
            }
        }

        if (stuck.isEmpty()) {
            log.debug("detectStuckEnrichmentRecords: no records exceeded timeout threshold of {} minutes", timeoutMinutes);
            return;
        }

        log.info("detectStuckEnrichmentRecords: detected {} record(s) stuck in ENRICHMENT for > {} minutes", 
                stuck.size(), timeoutMinutes);

        // Process each stuck record
        for (StageRecord record : stuck) {
            Instant enrichmentStartedAt = getEnrichmentStartedAt(record);
            long minutesStuck = Duration.between(enrichmentStartedAt, now).toMinutes();

            log.warn("Stuck record detected: id={}, lot={}, minutes_stuck={}", 
                    record.id(), record.lot(), minutesStuck);

            // Emit alert event via SSE
            emitStuckRecordAlert(record, minutesStuck);

            // Auto-remediate by marking DONE with manual-verify
            String message = String.format(
                    "[Stuck in Enrichment] lot=%s, idFile=%s, minutes_stuck=%d, timeout_threshold=%d minutes",
                    record.lot(), record.metadataId(), minutesStuck, timeoutMinutes);
            
            try {
                refDbService.markCompletedManualVerify(record, message);
                log.info("Auto-remediated stuck record: id={}, lot={}", record.id(), record.lot());
            } catch (Exception e) {
                log.error("Failed to auto-remediate stuck record id={}: {}", record.id(), e.getMessage());
            }
        }
    }

    /**
     * Emits a SSE alert event for a stuck enrichment record.
     * Requirements: 4.2
     */
    private void emitStuckRecordAlert(StageRecord record, long minutesStuck) {
        if (record.requestId() == null) {
            log.debug("emitStuckRecordAlert: record has no requestId, skipping SSE emit");
            return;
        }

        Map<String, Object> alertEvent = new java.util.HashMap<>();
        alertEvent.put("recordId", record.id());
        alertEvent.put("lot", record.lot());
        alertEvent.put("minutesStuck", minutesStuck);
        alertEvent.put("metadataId", record.metadataId());
        alertEvent.put("filename", record.filename());
        alertEvent.put("site", record.site());
        alertEvent.put("senderId", record.senderId());
        alertEvent.put("senderName", record.senderName());

        try {
            stageMonitorService.sendEvent(record.requestId(), "STUCK_RECORD_ALERT", alertEvent);
            log.debug("Emitted STUCK_RECORD_ALERT for record id={}", record.id());
        } catch (Exception e) {
            log.warn("Failed to emit STUCK_RECORD_ALERT for record id={}: {}", record.id(), e.getMessage());
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
            record.dataId(), getEnrichmentStartedAt(record),
                record.lot(), record.metadataId());

        if (!exensioProperties.isConfigured()) {
            log.info("Exensio not configured — marking record id={} as DONE with manual verify", record.id());
            integrationStatusService.updateCpStatusForRecord(stageRecordId, "timeout", timeoutMsg);
            integrationStatusService.updateElasticsearch(requestId, "timeout", timeoutMsg);
            refDbService.markCompletedManualVerify(record,
                    "[Enrichment Unresolved] " + diagnosticSummary + " Exensio not configured. Manual verification required.");
            emitRowUpdateSse(record, requestId);
            return;
        }

        try {
            boolean waferBlank = record.wafer() == null || record.wafer().isBlank();
            int pgcKey = com.onsemi.cim.apps.exensio.exensioreload.service.DataTypePgcKeyMapper.resolve(
                    record.dataType(), waferBlank);

            log.info("Auto-switch: CP enrichment timed out — attempting Exensio direct lookup via lot-wafer-lookup (raw-SQL→lot-wafer-lookup→SANDBOX fallback) for record id={} lot={}", record.id(), record.lot());

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
                    refDbService.markCompletedFromExensio(record, found.waferKey(), found.pgKey());
                }
                case ExensioLotWaferResult.NotFound notFound -> {
                    log.info("Exensio direct lookup also not found for record id={} — marking DONE with manual verify",
                            record.id());
                    integrationStatusService.updateCpStatusForRecord(stageRecordId, "timeout", timeoutMsg);
                    integrationStatusService.updateElasticsearch(requestId, "timeout", timeoutMsg);
                    refDbService.markCompletedManualVerify(record,
                            "[Enrichment Unresolved] " + diagnosticSummary
                            + " Exensio: not found for lot=" + record.lot() + " wafer=" + record.wafer()
                            + ". Manual verification required.");
                }
                case ExensioLotWaferResult.Error error -> {
                    log.warn("Exensio direct lookup error for record id={}: {}", record.id(), error.message());
                    integrationStatusService.updateCpStatusForRecord(stageRecordId, "timeout", timeoutMsg);
                    integrationStatusService.updateElasticsearch(requestId, "timeout", timeoutMsg);
                    refDbService.markCompletedManualVerify(record,
                            "[Enrichment Unresolved] " + diagnosticSummary
                            + " Exensio: error=" + error.message()
                            + ". Manual verification required.");
                }
            }
        } catch (Exception e) {
            log.warn("Exensio direct lookup exception for record id={}: {}", record.id(), e.getMessage());
            integrationStatusService.updateCpStatusForRecord(stageRecordId, "timeout", timeoutMsg);
            integrationStatusService.updateElasticsearch(requestId, "timeout", timeoutMsg);
            refDbService.markCompletedManualVerify(record,
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
     * Builds a diagnostic summary of what was checked during enrichment timeout detection.
     * Captures which data sources were queried and their responses.
     * Requirements: 10.1, 10.2
     */
    private String buildTimeoutDiagnosticSummary(StageRecord record) {
        Instant lookbackTime = getEnrichmentStartedAt(record);
        Instant esLookbackTime = lookbackTime.minusSeconds(120);
        
        StringBuilder sb = new StringBuilder();
        sb.append("ES: idData=").append(record.dataId()).append(" since=").append(esLookbackTime);
        sb.append(" result=NotFound; ");
        sb.append("pp_log: lot=").append(record.lot()).append(" idFile=").append(record.metadataId());
        sb.append(" result=NotFound; ");
        sb.append("Exensio direct lookup: not attempted (will retry via background process)");
        
        return sb.toString();
    }

    /**
     * Returns true if the record has been in ENRICHMENT status longer than the configured timeout.
     * Requirement 2.7: timeout check using enrichmentStartedAt when available.
     */
    private boolean isTimedOut(StageRecord record) {
        Instant enrichmentStartedAt = getEnrichmentStartedAt(record);
        if (enrichmentStartedAt == null) {
            return false;
        }
        Instant timeoutDeadline = enrichmentStartedAt.plus(Duration.ofMinutes(props.getEnrichmentTimeoutMinutes()));
        return timeoutDeadline.isBefore(Instant.now());
    }

    private Instant getEnrichmentStartedAt(StageRecord record) {
        if (record == null) {
            return null;
        }
        if (record.enrichmentStartedAt() != null) {
            return record.enrichmentStartedAt();
        }
        return record.createdAt();
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
