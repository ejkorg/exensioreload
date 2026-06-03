package com.onsemi.cim.apps.exensio.exensioreload.service;

import com.onsemi.cim.apps.exensio.exensioreload.config.CpElasticsearchProperties;
import com.onsemi.cim.apps.exensio.exensioreload.stage.StageRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

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
public class CpLogMonitor {

    private static final Logger log = LoggerFactory.getLogger(CpLogMonitor.class);

    /** Maximum characters stored in error_message column (Requirement 4.5). */
    private static final int MAX_ERROR_MESSAGE_LENGTH = 500;

    private final RefDbService refDbService;
    private final ElasticsearchLogService elasticsearchLogService;
    private final CpElasticsearchProperties props;
    private final StagePipelineOrchestrator pipelineOrchestrator;
    private final IntegrationStatusService integrationStatusService;

    public CpLogMonitor(RefDbService refDbService,
                        ElasticsearchLogService elasticsearchLogService,
                        CpElasticsearchProperties props,
                        StagePipelineOrchestrator pipelineOrchestrator,
                        IntegrationStatusService integrationStatusService) {
        this.refDbService = refDbService;
        this.elasticsearchLogService = elasticsearchLogService;
        this.props = props;
        this.pipelineOrchestrator = pipelineOrchestrator;
        this.integrationStatusService = integrationStatusService;
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
            processRecord(record);
        }
    }

    /**
     * Evaluates a single ENRICHMENT record against Elasticsearch and drives the appropriate
     * status transition.
     */
    private void processRecord(StageRecord record) {
        // Use updatedAt as the lower bound for ES log timestamp matching (Requirement 2.6)
        // Subtract 1 hour buffer to catch logs that happened before enrichment status update
        Instant enrichmentStartedAt = record.updatedAt() != null ? record.updatedAt() : record.createdAt();
        Instant esLookbackTime = enrichmentStartedAt.minus(1, java.time.temporal.ChronoUnit.HOURS);
        String requestId = record.requestId();

        CpLogResult result;
        try {
            result = elasticsearchLogService.findCpLog(record.metadataId(), record.dataId(), record.lot(), esLookbackTime, record.site(), record.filename());
        } catch (ElasticsearchLogService.ElasticsearchQueryException e) {
            // Requirement 6.7: ES unreachable — log warning, skip this record, do not mark failed
            log.warn("Elasticsearch query failed for record id={} dataId={} — skipping: {}",
                    record.id(), record.dataId(), e.getMessage());
            integrationStatusService.updateElasticsearch(requestId, "error", "ES query failed: " + e.getMessage());
            return;
        }

        switch (result) {
            case CpLogResult.Success success -> {
                log.info("CP enrichment success for record id={} dataId={}: path={} target={}",
                        record.id(), record.dataId(), success.outputPath(), success.outputTarget());
                integrationStatusService.updateElasticsearch(requestId, "success", "CP log found in ES");
                pipelineOrchestrator.onCpEnrichmentSuccess(record, success.outputPath(), success.outputTarget());
            }
            case CpLogResult.Failure failure -> {
                // Requirement 4.2, 4.3, 4.5: transition to FAILED with truncated error message
                String errorMessage = truncateErrorMessage(failure.errorMessage());
                log.info("CP enrichment failure for record id={} dataId={}: {}",
                        record.id(), record.dataId(), errorMessage);
                integrationStatusService.updateElasticsearch(requestId, "failure", errorMessage);
                refDbService.markFailed(record, errorMessage);
            }
            case CpLogResult.NotFound notFound -> {
                // Requirement 2.7: check timeout
                if (isTimedOut(record)) {
                    String timeoutMessage = "CP enrichment timeout — no log found in Elasticsearch after "
                            + props.getEnrichmentTimeoutMinutes() + " minutes";
                    log.info("CP enrichment timeout for record id={} dataId={}", record.id(), record.dataId());
                    integrationStatusService.updateElasticsearch(requestId, "timeout", timeoutMessage);
                    refDbService.markFailed(record, timeoutMessage);
                } else {
                    log.debug("No CP log yet for record id={} dataId={} — will retry next cycle",
                            record.id(), record.dataId());
                    integrationStatusService.updateElasticsearch(requestId, "not_found", "No ES log yet — retrying");
                }
            }
        }
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
            return "Unknown error";
        }
        return message.length() > MAX_ERROR_MESSAGE_LENGTH
                ? message.substring(0, MAX_ERROR_MESSAGE_LENGTH)
                : message;
    }
}
