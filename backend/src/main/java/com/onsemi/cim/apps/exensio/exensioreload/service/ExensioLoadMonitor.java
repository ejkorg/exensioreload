package com.onsemi.cim.apps.exensio.exensioreload.service;

import com.onsemi.cim.apps.exensio.exensioreload.config.ExensioProperties;
import com.onsemi.cim.apps.exensio.exensioreload.dto.BatchLookupResult;
import com.onsemi.cim.apps.exensio.exensioreload.dto.BatchResult;
import com.onsemi.cim.apps.exensio.exensioreload.stage.StageRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jmx.export.annotation.ManagedAttribute;
import org.springframework.jmx.export.annotation.ManagedResource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Scheduled monitor that polls the Exensio API for records in {@code EXENSIO_LOADING}
 * status and drives the final status transitions.
 *
 * <h3>When Exensio is not configured</h3>
 * <p>When {@code exensio.enabled=false} or no base URL is set, this monitor is a no-op.
 * Post-CP routing is handled by {@link StagePipelinePolicy}: without Exensio, records
 * go directly to {@code DONE} (or through Elasticsearch when ES is enabled).</p>
 *
 * <h3>Full pipeline (when Exensio is configured)</h3>
 * <ol>
 *   <li>Load all {@code EXENSIO_LOADING} records from {@code SENDER_STAGE}.</li>
 *   <li>Partition records into batches of configured size.</li>
 *   <li>Submit batches to thread pool for parallel processing.</li>
 *   <li>Each batch calls {@code POST /v1/key/lot-wafer-lookup} with all lot+wafer IDs.</li>
 *   <li>{@link BatchResult.UpdateType#DONE} → {@code DONE} (stores wafer_key + pg_key).</li>
 *   <li>{@link BatchResult.UpdateType#NOT_FOUND} + timeout exceeded → {@code FAILED}.</li>
 *   <li>{@link BatchResult.UpdateType#NOT_FOUND} within timeout → retry next cycle.</li>
 *   <li>{@link BatchResult.UpdateType#ERROR} → retry individual records via single lookup.</li>
 *   <li>Collect all results and batch-update the database.</li>
 * </ol>
 *
 * <h3>Circuit Breaker</h3>
 * <p>When the Exensio API fails repeatedly, the circuit breaker opens and skips API
 * calls until the reset timeout expires. This prevents cascading failures and gives
 * the Exensio server time to recover.</p>
 */
@Component
@ManagedResource(
        objectName = "com.onsemi.exensio:type=LoadMonitor",
        description = "Exensio Loading API batch/parallel processing monitor metrics"
)
public class ExensioLoadMonitor {

    private static final Logger log = LoggerFactory.getLogger(ExensioLoadMonitor.class);

    private final ExensioProperties props;
    private final ExensioClient exensioClient;
    private final RefDbService refDbService;
    private final IntegrationStatusService integrationStatusService;
    private final StageMonitorService stageMonitorService;

    // Thread pool and parallel processing
    private ExecutorService executorService;
    private final AtomicLong totalRecordsProcessed = new AtomicLong(0);
    private final AtomicLong successCount = new AtomicLong(0);
    private final AtomicLong failureCount = new AtomicLong(0);
    private final AtomicLong averageProcessingTimeMs = new AtomicLong(0);

    // Concurrency limiting
    private Semaphore concurrencyLimiter;

    // Circuit breaker
    private CircuitBreaker circuitBreaker;

    public ExensioLoadMonitor(ExensioProperties props,
                              ExensioClient exensioClient,
                              RefDbService refDbService,
                              IntegrationStatusService integrationStatusService,
                              StageMonitorService stageMonitorService) {
        this.props = props;
        this.exensioClient = exensioClient;
        this.refDbService = refDbService;
        this.integrationStatusService = integrationStatusService;
        this.stageMonitorService = stageMonitorService;
    }

    /**
     * Initialize thread pool, circuit breaker, and metrics on startup.
     */
    @jakarta.annotation.PostConstruct
    public void initialize() {
        int threadPoolSize = props.getThreadPoolSize();
        this.executorService = Executors.newFixedThreadPool(
                threadPoolSize,
                r -> {
                    Thread t = new Thread(r);
                    t.setName("exensio-worker-" + t.getId());
                    t.setDaemon(true);
                    return t;
                }
        );
        this.concurrencyLimiter = new Semaphore(props.getMaxConcurrentRequests());

        if (props.isEnableCircuitBreaker()) {
            this.circuitBreaker = new CircuitBreaker(
                    props.getCircuitBreakerThreshold(),
                    props.getCircuitBreakerResetMs()
            );
            log.info("Circuit breaker enabled: threshold={}, resetTimeout={}ms",
                    props.getCircuitBreakerThreshold(), props.getCircuitBreakerResetMs());
        } else {
            log.info("Circuit breaker disabled");
        }

        log.info("ExensioLoadMonitor initialized: threadPoolSize={}, maxConcurrentRequests={}, batchSize={}",
                threadPoolSize, props.getMaxConcurrentRequests(), props.getBatchSize());
    }

    /**
     * Graceful shutdown of thread pool on application shutdown.
     */
    @jakarta.annotation.PreDestroy
    public void shutdown() {
        log.info("Shutting down ExensioLoadMonitor thread pool...");
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(30, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
                if (!executorService.awaitTermination(30, TimeUnit.SECONDS)) {
                    log.error("Thread pool did not terminate gracefully");
                }
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("ExensioLoadMonitor shutdown complete. Total processed: {}, success: {}, failure: {}",
                totalRecordsProcessed.get(), successCount.get(), failureCount.get());
    }

    // -------------------------------------------------------------------------
    // 7.1 Refactored main poll loop
    // -------------------------------------------------------------------------

    @Scheduled(fixedDelayString = "${exensio.poll-interval-ms:60000}")
    public void monitorExensioLoading() {
        // Fallback: Exensio not configured → no-op
        if (!props.isConfigured()) {
            log.debug("Exensio not configured — ExensioLoadMonitor is a no-op");
            return;
        }

        // Check circuit breaker before processing
        if (circuitBreaker != null && !circuitBreaker.allowRequest()) {
            log.warn("Circuit breaker is OPEN — skipping poll cycle (failures: {}/{})",
                    circuitBreaker.getConsecutiveFailures(), circuitBreaker.getFailureThreshold());
            return;
        }

        Instant cycleStart = Instant.now();

        // 1. Load records (single query)
        List<StageRecord> records;
        try {
            records = refDbService.listRecords(null, null, "EXENSIO_LOADING", Integer.MAX_VALUE);
        } catch (Exception e) {
            log.warn("Failed to load EXENSIO_LOADING records — skipping poll cycle: {}", e.getMessage());
            return;
        }

        if (records.isEmpty()) {
            log.debug("No EXENSIO_LOADING records — nothing to poll");
            return;
        }

        // 6.1 Log poll cycle start with record count
        log.info("Exensio poll cycle started: {} records in EXENSIO_LOADING", records.size());

        // 2. Partition into batches
        int batchSize = props.getBatchSize();
        List<List<StageRecord>> batches = partition(records, batchSize);

        // 6.2 Log batch creation
        log.info("Created {} batches (batchSize={}, threadPoolSize={})",
                batches.size(), batchSize, props.getThreadPoolSize());

        // 3. Submit batches to thread pool and collect BatchResult futures
        List<CompletableFuture<BatchResult>> futures = batches.stream()
                .map(batch -> CompletableFuture.supplyAsync(() -> processBatch(batch), executorService))
                .collect(Collectors.toList());

        // 4. Wait for all batches to complete
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        // Collect results
        List<BatchResult> results = futures.stream()
                .map(CompletableFuture::join)
                .collect(Collectors.toList());

        // Aggregate counts for logging
        int totalSuccess = results.stream().mapToInt(BatchResult::successCount).sum();
        int totalFailed  = results.stream().mapToInt(BatchResult::failureCount).sum();
        int totalNotFound = results.stream().mapToInt(BatchResult::notFoundCount).sum();

        // 5. Batch update database
        Instant dbStart = Instant.now();
        int dbUpdated = 0;
        try {
            dbUpdated = refDbService.batchUpdateFromExensio(results);
        } catch (Exception e) {
            log.error("Batch database update failed: {}", e.getMessage(), e);
        }
        long dbElapsedMs = Duration.between(dbStart, Instant.now()).toMillis();

        // Update global metrics
        totalRecordsProcessed.addAndGet(records.size());
        successCount.addAndGet(totalSuccess);
        failureCount.addAndGet(totalFailed);

        long cycleElapsedMs = Duration.between(cycleStart, Instant.now()).toMillis();

        // Update rolling average processing time
        long currentAvg = averageProcessingTimeMs.get();
        long newAvg = currentAvg == 0 ? cycleElapsedMs : (currentAvg * 9 + cycleElapsedMs) / 10;
        averageProcessingTimeMs.set(newAvg);

        // 6.1 Log poll cycle end
        log.info("Exensio poll cycle completed in {}ms: records={}, batches={}, " +
                        "done={}, failed={}, notFound={}, dbUpdated={}, dbTimeMs={}",
                cycleElapsedMs, records.size(), batches.size(),
                totalSuccess, totalFailed, totalNotFound, dbUpdated, dbElapsedMs);

        // 6.3 Log thread pool metrics
        log.info("Thread pool metrics: activeThreads={}, queueSize={}, completedTasks={}",
                getActiveThreads(), getQueueSize(), getCompletedTaskCount());

        // 7.5 Performance warnings
        checkPerformanceWarnings(cycleElapsedMs, totalSuccess, totalFailed, records.size());
    }

    // -------------------------------------------------------------------------
    // 7.2 processBatch — acquires permit, calls batch API, returns BatchResult
    // -------------------------------------------------------------------------

    /**
     * Process a single batch: acquire concurrency permit, call batch API,
     * handle errors with individual retry fallback, release permit.
     */
    private BatchResult processBatch(List<StageRecord> batch) {
        // Filter out records with missing lot/wafer before hitting the API
        List<StageRecord> validRecords = new ArrayList<>();
        List<BatchResult.RecordUpdate> invalidUpdates = new ArrayList<>();
        for (StageRecord record : batch) {
            if (record.lot() == null || record.lot().isBlank()) {
                log.warn("Record id={} missing lot — marking FAILED", record.id());
                invalidUpdates.add(new BatchResult.RecordUpdate(
                        record.id(), BatchResult.UpdateType.FAILED, null, null,
                        "Missing lot for Exensio lookup", null, null, null));
            } else {
                validRecords.add(record);
            }
        }

        if (validRecords.isEmpty()) {
            return new BatchResult(invalidUpdates, 0, invalidUpdates.size(), 0, 0);
        }

        Instant batchStart = Instant.now();
        List<BatchResult.RecordUpdate> updates = new ArrayList<>(invalidUpdates);

        try {
            // Acquire concurrency permit
            concurrencyLimiter.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Batch processing interrupted while acquiring permit for {} records", validRecords.size());
            // Mark all as error so they retry next cycle
            for (StageRecord r : validRecords) {
                updates.add(new BatchResult.RecordUpdate(
                        r.id(), BatchResult.UpdateType.ERROR, null, null, "Interrupted", null, null, null));
            }
            return buildBatchResult(updates, batchStart);
        }

        try {
            // 6.4 Log API call start
            log.debug("Batch API call: {} records ({} unique lots, {} unique wafers)",
                    validRecords.size(),
                    validRecords.stream().map(StageRecord::lot).distinct().count(),
                    validRecords.stream().map(StageRecord::wafer).distinct().count());

            BatchLookupResult lookupResult = exensioClient.lotWaferLookupBatch(validRecords);

            long apiElapsedMs = Duration.between(batchStart, Instant.now()).toMillis();
            log.debug("Batch API response: success={}, timeMs={}", lookupResult.isSuccess(), apiElapsedMs);

            if (lookupResult.isSuccess()) {
                // Map batch response to individual record updates
                List<BatchResult.RecordUpdate> batchUpdates = lookupResult.mapToRecordUpdates(validRecords);

                // Apply timeout logic: NOT_FOUND records that have timed out become FAILED
                for (BatchResult.RecordUpdate update : batchUpdates) {
                    if (update.type() == BatchResult.UpdateType.NOT_FOUND) {
                        StageRecord record = findRecord(validRecords, update.recordId());
                        if (record != null && isTimedOut(record)) {
                            updates.add(new BatchResult.RecordUpdate(
                                    update.recordId(), BatchResult.UpdateType.FAILED, null, null,
                                    "Exensio load timeout — wafer not found after "
                                            + props.getTimeoutMinutes() + " minutes", null, null, null));
                        } else {
                            // Still within timeout — keep as NOT_FOUND (skip, retry next cycle)
                            updates.add(update);
                        }
                    } else {
                        updates.add(update);
                    }
                }

                // Record circuit breaker success
                if (circuitBreaker != null) {
                    circuitBreaker.recordSuccess();
                }

            } else {
                // Batch API call failed — check error type and retry individually
                String errorMsg = lookupResult.getErrorMessage();
                log.warn("Batch API call failed ({}), retrying {} records individually", errorMsg, validRecords.size());

                if (circuitBreaker != null) {
                    circuitBreaker.recordFailure();
                    log.warn("Circuit breaker failure recorded: {}/{}, state={}",
                            circuitBreaker.getConsecutiveFailures(),
                            circuitBreaker.getFailureThreshold(),
                            circuitBreaker.getState());
                }

                // 7.3 Individual record retry
                List<BatchResult.RecordUpdate> retryUpdates = retryIndividualRecords(validRecords);
                updates.addAll(retryUpdates);
            }

        } finally {
            concurrencyLimiter.release();
        }

        BatchResult result = buildBatchResult(updates, batchStart);

        recordBatchIntegrationStatus(batch, result);

        // 6.2 Log batch metrics
        log.debug("Batch done: records={}, done={}, failed={}, notFound={}, timeMs={}",
                batch.size(), result.successCount(), result.failureCount(),
                result.notFoundCount(), result.processingTimeMs());

        return result;
    }

    private void recordBatchIntegrationStatus(List<StageRecord> batch, BatchResult result) {
        if (batch == null || batch.isEmpty() || result == null || result.updates() == null) {
            return;
        }
        java.util.Map<Long, StageRecord> byId = new java.util.HashMap<>();
        for (StageRecord record : batch) {
            byId.put(record.id(), record);
        }

        for (BatchResult.RecordUpdate update : result.updates()) {
            StageRecord record = byId.get(update.recordId());
            if (record == null) {
                continue;
            }
            long stageRecordId = record.id();
            String requestId = record.requestId();
            switch (update.type()) {
                case DONE -> {
                    String msg = String.format("Exensio wafer confirmed: lot=%s, wafer=%s, file=%s",
                            update.lotId() != null ? update.lotId() : "N/A",
                            update.waferId() != null ? update.waferId() : "N/A",
                            update.fileName() != null ? update.fileName() : "N/A");
                    log.info("Record {} DONE - {}", record.id(), msg);
                    // Update per-record status
                    integrationStatusService.updateExensioStatusForRecord(stageRecordId, "success", msg);
                }
                case NOT_FOUND -> {
                    // Update per-record status
                    integrationStatusService.updateExensioStatusForRecord(stageRecordId, "not_found", "Exensio wafer not found yet — retrying");
                }
                case FAILED -> {
                    String errorMsg = update.errorMessage() != null ? update.errorMessage() : "Exensio lookup failed";
                    log.info("Record {} FAILED - {}", record.id(), errorMsg);
                    // Update per-record status
                    integrationStatusService.updateExensioStatusForRecord(stageRecordId, "failure", errorMsg);
                }
                case ERROR -> {
                    String errorMsg = update.errorMessage() != null ? update.errorMessage() : "Exensio lookup error";
                    log.warn("Record {} ERROR - {}", record.id(), errorMsg);
                    // Update per-record status
                    integrationStatusService.updateExensioStatusForRecord(stageRecordId, "error", errorMsg);
                }
            }
            // Emit ROW_UPDATE SSE event with per-record integration status
            emitRowUpdateSse(record, requestId);
        }
    }

    /**
     * Emits a ROW_UPDATE SSE event with per-record integration status.
     * Best-effort: silently skips if no SSE emitter exists for the record.
     * Requirements: 4.3
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

    // -------------------------------------------------------------------------
    // 7.3 Individual record retry logic
    // -------------------------------------------------------------------------

    /**
     * Retry individual records when a batch API call fails.
     * Uses the existing single-record {@link ExensioClient#lotWaferLookup} method.
     */
    private List<BatchResult.RecordUpdate> retryIndividualRecords(List<StageRecord> records) {
        List<BatchResult.RecordUpdate> updates = new ArrayList<>();

        for (StageRecord record : records) {
            try {
                boolean waferBlank = record.wafer() == null || record.wafer().isBlank();
                int pgcKey = DataTypePgcKeyMapper.resolve(record.dataType(), waferBlank);
                // Requirements: 4.1, 4.3 — derive pgcKey from dataType so the individual retry
                // uses the same program-group class as the batch path.
                // Requirements: 5.1–5.5 — pass testPhase so PPID suffix validation is applied.
                ExensioLotWaferResult result = exensioClient.lotWaferLookup(
                    record.lot(),
                    record.wafer(),
                    record.endTime(),
                    pgcKey,
                    record.testPhase(),
                    record.filename(),
                    record.metadataId(),
                    record.dataId());

                switch (result) {
                    case ExensioLotWaferResult.Found found -> {
                        log.debug("Individual retry found: id={} lot={} wafer={} waferKey={} pgKey={}",
                                record.id(), record.lot(), record.wafer(), found.waferKey(), found.pgKey());
                        updates.add(new BatchResult.RecordUpdate(
                                record.id(), BatchResult.UpdateType.DONE,
                                found.waferKey(), found.pgKey(), null, found.lotId(), found.waferId(), found.fileName()));
                    }
                    case ExensioLotWaferResult.NotFound notFound -> {
                        if (isTimedOut(record)) {
                            updates.add(new BatchResult.RecordUpdate(
                                    record.id(), BatchResult.UpdateType.FAILED, null, null,
                                    "Exensio load timeout — wafer not found after "
                                            + props.getTimeoutMinutes() + " minutes", null, null, null));
                        } else {
                            // Still within timeout — skip (retry next cycle)
                            updates.add(new BatchResult.RecordUpdate(
                                    record.id(), BatchResult.UpdateType.NOT_FOUND, null, null, null, null, null, null));
                        }
                    }
                    case ExensioLotWaferResult.Error error -> {
                        log.warn("Individual retry error for id={}: {}", record.id(), error.message());
                        // Leave as ERROR — no status change, retry next cycle
                        updates.add(new BatchResult.RecordUpdate(
                                record.id(), BatchResult.UpdateType.ERROR, null, null, error.message(), null, null, null));
                    }
                }
            } catch (Exception e) {
                log.warn("Individual retry exception for id={}: {}", record.id(), e.getMessage());
                updates.add(new BatchResult.RecordUpdate(
                        record.id(), BatchResult.UpdateType.ERROR, null, null, e.getMessage(), null, null, null));
            }
        }

        return updates;
    }

    // -------------------------------------------------------------------------
    // 7.5 Performance warnings
    // -------------------------------------------------------------------------

    /**
     * Emit warnings when performance thresholds are exceeded.
     * Requirements: 6.7
     */
    private void checkPerformanceWarnings(long cycleElapsedMs, int successCount, int failureCount, int totalRecords) {
        // Warn when processing time exceeds 80% of poll interval
        long pollIntervalMs = props.getPollIntervalMs();
        if (pollIntervalMs > 0 && cycleElapsedMs > pollIntervalMs * 0.8) {
            log.warn("PERFORMANCE WARNING: Poll cycle took {}ms which exceeds 80% of poll interval {}ms. " +
                            "Consider increasing thread pool size or batch size.",
                    cycleElapsedMs, pollIntervalMs);
        }

        // Warn when error rate exceeds 10%
        if (totalRecords > 0) {
            double errorRate = (double) failureCount / totalRecords;
            if (errorRate > 0.10) {
                log.warn("PERFORMANCE WARNING: Error rate {:.1f}% exceeds 10% threshold ({} failures / {} total). " +
                                "Check Exensio API health.",
                        errorRate * 100, failureCount, totalRecords);
            }
        }

        // Warn when memory usage is high (>80% of max heap)
        try {
            MemoryMXBean memBean = ManagementFactory.getMemoryMXBean();
            long usedHeap = memBean.getHeapMemoryUsage().getUsed();
            long maxHeap = memBean.getHeapMemoryUsage().getMax();
            if (maxHeap > 0 && usedHeap > maxHeap * 0.8) {
                log.warn("PERFORMANCE WARNING: Heap memory usage is {}MB / {}MB ({}%). " +
                                "Consider reducing batch size or thread pool size.",
                        usedHeap / (1024 * 1024), maxHeap / (1024 * 1024),
                        (int) (100.0 * usedHeap / maxHeap));
            }
        } catch (Exception e) {
            log.debug("Could not read memory metrics: {}", e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Partition a list of records into batches of specified size.
     */
    List<List<StageRecord>> partition(List<StageRecord> records, int batchSize) {
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize must be positive");
        }
        if (records.isEmpty()) {
            return List.of();
        }
        int numBatches = (records.size() + batchSize - 1) / batchSize;
        List<List<StageRecord>> batches = new ArrayList<>(numBatches);
        for (int i = 0; i < records.size(); i += batchSize) {
            int end = Math.min(i + batchSize, records.size());
            batches.add(records.subList(i, end));
        }
        return batches;
    }

    /**
     * Build a BatchResult from a list of updates, computing counts and elapsed time.
     */
    private BatchResult buildBatchResult(List<BatchResult.RecordUpdate> updates, Instant start) {
        int done = 0, failed = 0, notFound = 0;
        for (BatchResult.RecordUpdate u : updates) {
            switch (u.type()) {
                case DONE -> done++;
                case FAILED -> failed++;
                case NOT_FOUND -> notFound++;
                default -> { /* ERROR — no status change */ }
            }
        }
        long elapsedMs = Duration.between(start, Instant.now()).toMillis();
        return new BatchResult(updates, done, failed, notFound, elapsedMs);
    }

    /**
     * Find a record by ID in a list.
     */
    private StageRecord findRecord(List<StageRecord> records, long id) {
        for (StageRecord r : records) {
            if (r.id() == id) return r;
        }
        return null;
    }

    /**
     * Returns true if the record has been in EXENSIO_LOADING longer than the configured timeout.
     */
    private boolean isTimedOut(StageRecord record) {
        Instant startedAt = record.updatedAt() != null ? record.updatedAt() : record.createdAt();
        if (startedAt == null) return false;
        return startedAt.plus(Duration.ofMinutes(props.getTimeoutMinutes())).isBefore(Instant.now());
    }

    // -------------------------------------------------------------------------
    // Metrics (JMX-ready getters)
    // -------------------------------------------------------------------------

    @ManagedAttribute(description = "Total number of records processed across all poll cycles")
    public long getTotalRecordsProcessed() { return totalRecordsProcessed.get(); }

    @ManagedAttribute(description = "Total number of records successfully resolved (DONE)")
    public long getSuccessCount()          { return successCount.get(); }

    @ManagedAttribute(description = "Total number of records that failed (FAILED)")
    public long getFailureCount()          { return failureCount.get(); }

    @ManagedAttribute(description = "Success rate as a fraction (0.0 – 1.0) across all processed records")
    public double getSuccessRate() {
        long total = totalRecordsProcessed.get();
        return total > 0 ? (double) successCount.get() / total : 0.0;
    }

    @ManagedAttribute(description = "Exponentially-weighted rolling average poll-cycle duration in milliseconds")
    public long getAverageProcessingTimeMs() { return averageProcessingTimeMs.get(); }

    @ManagedAttribute(description = "Number of worker threads currently executing batches")
    public int getActiveThreads() {
        if (executorService instanceof ThreadPoolExecutor tp) return tp.getActiveCount();
        return 0;
    }

    @ManagedAttribute(description = "Number of batches waiting in the thread-pool queue")
    public int getQueueSize() {
        if (executorService instanceof ThreadPoolExecutor tp) return tp.getQueue().size();
        return 0;
    }

    @ManagedAttribute(description = "Total number of batch tasks completed by the thread pool since startup")
    public long getCompletedTaskCount() {
        if (executorService instanceof ThreadPoolExecutor tp) return tp.getCompletedTaskCount();
        return 0;
    }

    // Circuit breaker metrics
    @ManagedAttribute(description = "Current circuit breaker state (CLOSED, OPEN, HALF_OPEN)")
    public CircuitBreaker.State getCircuitBreakerState() {
        return circuitBreaker != null ? circuitBreaker.getState() : null;
    }

    @ManagedAttribute(description = "Number of consecutive failures recorded by the circuit breaker")
    public int getCircuitBreakerConsecutiveFailures() {
        return circuitBreaker != null ? circuitBreaker.getConsecutiveFailures() : 0;
    }

    @ManagedAttribute(description = "Failure threshold before circuit breaker opens")
    public int getCircuitBreakerThreshold() {
        return circuitBreaker != null ? circuitBreaker.getFailureThreshold() : 0;
    }

    @ManagedAttribute(description = "Circuit breaker reset timeout in milliseconds")
    public long getCircuitBreakerResetTimeoutMs() {
        return circuitBreaker != null ? circuitBreaker.getResetTimeoutMs() : 0;
    }

    @ManagedAttribute(description = "Time in milliseconds since the last circuit breaker failure")
    public long getCircuitBreakerTimeSinceLastFailureMs() {
        return circuitBreaker != null ? circuitBreaker.getTimeSinceLastFailureMs() : -1;
    }
}
