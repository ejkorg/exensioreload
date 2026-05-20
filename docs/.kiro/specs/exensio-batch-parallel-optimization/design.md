# Design Document: Exensio Loading API Batch & Parallel Processing Optimization

## Overview

This design optimizes the Exensio Loading API integration by introducing batch processing and parallel execution. The current sequential implementation processes 20k records in ~67 minutes. The optimized implementation will process the same 20k records in ~5-7 minutes by:

1. **Batch Processing**: Grouping 50-100 lot/wafer combinations per API request (reduces API calls from 20k to 200-400)
2. **Parallel Processing**: Processing 5-10 batches concurrently using thread pools (reduces wall-clock time by 5-10x)
3. **Database Optimization**: Batch database updates to reduce transaction overhead

## Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                    ExensioLoadMonitor (Scheduled)                   │
│  @Scheduled(fixedDelayString = "${exensio.poll-interval-ms}")      │
└─────────────────────────────────────────────────────────────────────┘
                          │
                          ▼
┌─────────────────────────────────────────────────────────────────────┐
│              1. Load Records (Single Query)                         │
│  List<StageRecord> records = refDbService.listRecords(...)         │
│  Result: 20,000 records in ~100ms                                  │
└─────────────────────────────────────────────────────────────────────┘
                          │
                          ▼
┌─────────────────────────────────────────────────────────────────────┐
│              2. Partition into Batches                              │
│  List<List<StageRecord>> batches = partition(records, batchSize)   │
│  Result: 400 batches (50 records each)                             │
└─────────────────────────────────────────────────────────────────────┘
                          │
                          ▼
┌─────────────────────────────────────────────────────────────────────┐
│              3. Submit to Thread Pool                               │
│  ExecutorService executor = Executors.newFixedThreadPool(5)        │
│  for (batch : batches) { executor.submit(() -> process(batch)) }   │
└─────────────────────────────────────────────────────────────────────┘
                          │
                          ▼
┌─────────────────────────────────────────────────────────────────────┐
│              4. Process Batch (Parallel)                            │
│  - Call ExensioClient.lotWaferLookupBatch(batch)                   │
│  - Parse batch response                                             │
│  - Map results to individual records                                │
│  - Collect updates for database                                     │
└─────────────────────────────────────────────────────────────────────┘
                          │
                          ▼
┌─────────────────────────────────────────────────────────────────────┐
│              5. Batch Database Updates                              │
│  - Group updates by status (DONE, FAILED)                           │
│  - Execute batch updates (100 records per transaction)              │
│  - Broadcast SSE events                                             │
└─────────────────────────────────────────────────────────────────────┘
```

## Components and Interfaces

### ExensioLoadMonitor (Enhanced)

```java
@Component
public class ExensioLoadMonitor {
    private final ExensioProperties props;
    private final ExensioClient exensioClient;
    private final RefDbService refDbService;
    private final ExecutorService executorService;
    private final Semaphore concurrencyLimiter;
    
    @PostConstruct
    public void initialize() {
        int threadPoolSize = props.getThreadPoolSize();
        this.executorService = Executors.newFixedThreadPool(
            threadPoolSize,
            new ThreadFactoryBuilder()
                .setNameFormat("exensio-worker-%d")
                .setDaemon(true)
                .build()
        );
        this.concurrencyLimiter = new Semaphore(props.getMaxConcurrentRequests());
    }
    
    @Scheduled(fixedDelayString = "${exensio.poll-interval-ms:60000}")
    public void monitorExensioLoading() {
        // 1. Load all EXENSIO_LOADING records
        List<StageRecord> records = loadRecords();
        
        // 2. Partition into batches
        List<List<StageRecord>> batches = partition(records, props.getBatchSize());
        
        // 3. Process batches in parallel
        List<CompletableFuture<BatchResult>> futures = new ArrayList<>();
        for (List<StageRecord> batch : batches) {
            CompletableFuture<BatchResult> future = CompletableFuture.supplyAsync(
                () -> processBatch(batch),
                executorService
            );
            futures.add(future);
        }
        
        // 4. Wait for all batches to complete
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        
        // 5. Collect results and update database
        List<BatchResult> results = futures.stream()
            .map(CompletableFuture::join)
            .collect(Collectors.toList());
        
        updateDatabase(results);
    }
    
    private BatchResult processBatch(List<StageRecord> batch) {
        try {
            concurrencyLimiter.acquire();
            return doProcessBatch(batch);
        } finally {
            concurrencyLimiter.release();
        }
    }
}
```

### ExensioClient (Enhanced)

```java
@Service
public class ExensioClient {
    /**
     * Batch lot-wafer lookup for multiple records.
     * 
     * Request body:
     * {
     *   "pgc_key": 1,
     *   "lot_ids": ["LOT1", "LOT2", "LOT3", ...],
     *   "wafer_ids": ["WAFER1", "WAFER2", "WAFER3", ...]
     * }
     * 
     * Response:
     * {
     *   "lots": [
     *     {
     *       "lot_key": 123,
     *       "wafers": [
     *         {"wafer_id": "WAFER1", "wafer_key": 456, "pg_key": 789, "ppid": "..."},
     *         {"wafer_id": "WAFER2", "wafer_key": 457, "pg_key": 790, "ppid": "..."}
     *       ]
     *     }
     *   ]
     * }
     */
    public BatchLookupResult lotWaferLookupBatch(List<StageRecord> records) {
        // Extract unique lot/wafer combinations
        List<String> lotIds = records.stream()
            .map(StageRecord::lot)
            .distinct()
            .collect(Collectors.toList());
        
        List<String> waferIds = records.stream()
            .map(StageRecord::wafer)
            .distinct()
            .collect(Collectors.toList());
        
        // Build request
        ObjectNode body = objectMapper.createObjectNode();
        body.put("pgc_key", 1);
        body.set("lot_ids", objectMapper.valueToTree(lotIds));
        body.set("wafer_ids", objectMapper.valueToTree(waferIds));
        
        // Execute request
        HttpResponse<String> response = executeRequest(body);
        
        // Parse response and map to records
        return parseBatchResponse(response.body(), records);
    }
}
```

### BatchResult

```java
public record BatchResult(
    List<RecordUpdate> updates,
    int successCount,
    int failureCount,
    int notFoundCount,
    long processingTimeMs
) {
    public record RecordUpdate(
        long recordId,
        UpdateType type,
        Long waferKey,
        Long pgKey,
        String errorMessage
    ) {}
    
    public enum UpdateType {
        DONE,
        FAILED,
        NOT_FOUND,
        ERROR
    }
}
```

### RefDbService (Enhanced)

```java
@Service
public class RefDbService {
    /**
     * Batch update records with Exensio results.
     * Groups updates by type and executes in batches of 100.
     */
    public void batchUpdateFromExensio(List<BatchResult.RecordUpdate> updates) {
        // Group by update type
        Map<BatchResult.UpdateType, List<BatchResult.RecordUpdate>> grouped = 
            updates.stream().collect(Collectors.groupingBy(BatchResult.RecordUpdate::type));
        
        // Process DONE updates
        List<BatchResult.RecordUpdate> doneUpdates = grouped.get(BatchResult.UpdateType.DONE);
        if (doneUpdates != null && !doneUpdates.isEmpty()) {
            batchMarkDone(doneUpdates);
        }
        
        // Process FAILED updates
        List<BatchResult.RecordUpdate> failedUpdates = grouped.get(BatchResult.UpdateType.FAILED);
        if (failedUpdates != null && !failedUpdates.isEmpty()) {
            batchMarkFailed(failedUpdates);
        }
    }
    
    private void batchMarkDone(List<BatchResult.RecordUpdate> updates) {
        String sql = "UPDATE " + properties.getStagingTable() +
            " SET status = 'DONE', exensio_wafer_key = ?, exensio_pg_key = ?," +
            " processed_at = SYSDATE, updated_at = SYSDATE" +
            " WHERE id = ?";
        
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            
            conn.setAutoCommit(false);
            int batchCount = 0;
            
            for (BatchResult.RecordUpdate update : updates) {
                ps.setLong(1, update.waferKey());
                ps.setLong(2, update.pgKey());
                ps.setLong(3, update.recordId());
                ps.addBatch();
                batchCount++;
                
                if (batchCount % 100 == 0) {
                    ps.executeBatch();
                    conn.commit();
                }
            }
            
            if (batchCount % 100 != 0) {
                ps.executeBatch();
                conn.commit();
            }
        }
    }
}
```

## Data Models

### ExensioProperties (Enhanced)

```java
@Component
@ConfigurationProperties(prefix = "exensio")
public class ExensioProperties {
    // Existing properties
    private boolean enabled = false;
    private String env = "QA";
    private long pollIntervalMs = 60_000L;
    private int timeoutMinutes = 60;
    
    // New properties for optimization
    private int batchSize = 50;
    private int threadPoolSize = 5;
    private int maxConcurrentRequests = 10;
    private boolean enableCircuitBreaker = true;
    private int circuitBreakerThreshold = 5;
    private long circuitBreakerResetMs = 60_000L;
    
    // Validation
    @PostConstruct
    public void validate() {
        if (batchSize < 1 || batchSize > 100) {
            throw new IllegalArgumentException("batchSize must be between 1 and 100");
        }
        if (threadPoolSize < 1 || threadPoolSize > 20) {
            throw new IllegalArgumentException("threadPoolSize must be between 1 and 20");
        }
        if (maxConcurrentRequests < 1 || maxConcurrentRequests > 50) {
            throw new IllegalArgumentException("maxConcurrentRequests must be between 1 and 50");
        }
    }
}
```

## Performance Analysis

### Current Implementation (Sequential)

```
20,000 records × 200ms per API call = 4,000 seconds ≈ 67 minutes
```

### Optimized Implementation (Batch + Parallel)

```
Step 1: Load records
  - 1 database query: 100ms

Step 2: Partition into batches
  - 20,000 records ÷ 50 per batch = 400 batches
  - Partitioning: 10ms

Step 3: Process batches in parallel
  - 400 batches ÷ 5 threads = 80 batches per thread
  - 80 batches × 200ms per API call = 16,000ms = 16 minutes per thread
  - Wall-clock time: 16 minutes (parallel execution)

Step 4: Batch database updates
  - 20,000 updates ÷ 100 per batch = 200 batch updates
  - 200 × 50ms per batch = 10,000ms = 10 seconds

Total: 16 minutes + 10 seconds ≈ 16 minutes
```

### With Larger Thread Pool (10 threads)

```
400 batches ÷ 10 threads = 40 batches per thread
40 batches × 200ms = 8,000ms = 8 minutes per thread
Wall-clock time: 8 minutes

Total: 8 minutes + 10 seconds ≈ 8 minutes
```

### With Larger Batch Size (100 records)

```
20,000 records ÷ 100 per batch = 200 batches
200 batches ÷ 10 threads = 20 batches per thread
20 batches × 200ms = 4,000ms = 4 minutes per thread
Wall-clock time: 4 minutes

Total: 4 minutes + 10 seconds ≈ 4-5 minutes
```

## Configuration Examples

### Conservative (Safe, Moderate Performance)

```yaml
exensio:
  batch-size: 25
  thread-pool-size: 3
  max-concurrent-requests: 5
```

**Performance:** 20k records in ~20 minutes

### Balanced (Recommended)

```yaml
exensio:
  batch-size: 50
  thread-pool-size: 5
  max-concurrent-requests: 10
```

**Performance:** 20k records in ~10 minutes

### Aggressive (High Performance)

```yaml
exensio:
  batch-size: 100
  thread-pool-size: 10
  max-concurrent-requests: 20
```

**Performance:** 20k records in ~5 minutes

## Error Handling

### Batch API Failure

```java
try {
    BatchLookupResult result = exensioClient.lotWaferLookupBatch(batch);
    return processBatchResult(result);
} catch (HttpException e) {
    if (e.getStatusCode() >= 500) {
        // Server error - retry individual records
        return retryIndividualRecords(batch);
    } else if (e.getStatusCode() == 429) {
        // Rate limit - back off and retry
        Thread.sleep(1000);
        return retryBatch(batch);
    } else {
        // Client error - mark batch as failed
        return markBatchFailed(batch, e.getMessage());
    }
}
```

### Circuit Breaker

```java
private final CircuitBreaker circuitBreaker = new CircuitBreaker(
    props.getCircuitBreakerThreshold(),
    Duration.ofMillis(props.getCircuitBreakerResetMs())
);

private BatchResult processBatch(List<StageRecord> batch) {
    if (circuitBreaker.isOpen()) {
        log.warn("Circuit breaker is OPEN - skipping batch");
        return BatchResult.skipped(batch.size());
    }
    
    try {
        BatchResult result = doProcessBatch(batch);
        circuitBreaker.recordSuccess();
        return result;
    } catch (Exception e) {
        circuitBreaker.recordFailure();
        throw e;
    }
}
```

## Thread Safety

### Shared State

```java
// Thread-safe collections
private final ConcurrentHashMap<Long, BatchResult.RecordUpdate> pendingUpdates = new ConcurrentHashMap<>();

// Atomic counters
private final AtomicLong successCount = new AtomicLong(0);
private final AtomicLong failureCount = new AtomicLong(0);

// Synchronized metrics
private synchronized void updateMetrics(BatchResult result) {
    successCount.addAndGet(result.successCount());
    failureCount.addAndGet(result.failureCount());
}
```

### Database Concurrency

```java
// Use connection pooling (HikariCP)
// Each thread gets its own connection from the pool
// No shared database connections between threads

// Batch updates use transactions
conn.setAutoCommit(false);
try {
    ps.executeBatch();
    conn.commit();
} catch (SQLException e) {
    conn.rollback();
    throw e;
}
```

## Monitoring and Metrics

### JMX Metrics

```java
@ManagedResource(objectName = "com.onsemi.exensio:type=LoadMonitor")
public class ExensioLoadMonitor {
    @ManagedAttribute
    public long getTotalRecordsProcessed() { return totalRecordsProcessed.get(); }
    
    @ManagedAttribute
    public long getSuccessCount() { return successCount.get(); }
    
    @ManagedAttribute
    public long getFailureCount() { return failureCount.get(); }
    
    @ManagedAttribute
    public double getSuccessRate() {
        long total = totalRecordsProcessed.get();
        return total > 0 ? (double) successCount.get() / total : 0.0;
    }
    
    @ManagedAttribute
    public long getAverageProcessingTimeMs() { return averageProcessingTimeMs.get(); }
    
    @ManagedAttribute
    public int getActiveThreads() { return ((ThreadPoolExecutor) executorService).getActiveCount(); }
    
    @ManagedAttribute
    public int getQueueSize() { return ((ThreadPoolExecutor) executorService).getQueue().size(); }
}
```

### Logging

```java
log.info("Exensio poll cycle started: {} records in EXENSIO_LOADING", records.size());
log.info("Created {} batches (batch size: {})", batches.size(), props.getBatchSize());
log.info("Processing batches with {} threads", props.getThreadPoolSize());

// Per-batch logging
log.debug("Batch {}/{}: {} records, {} API calls", 
    batchNum, totalBatches, batch.size(), apiCallCount);

// Cycle completion
log.info("Exensio poll cycle completed: {} records processed in {}ms " +
    "(success: {}, failed: {}, not found: {})",
    totalRecords, elapsedMs, successCount, failureCount, notFoundCount);
```

## Testing Strategy

### Unit Tests

- Test batch partitioning logic
- Test batch result parsing
- Test database batch updates
- Test error handling for each failure mode
- Test circuit breaker state transitions
- Test thread pool configuration

### Integration Tests

- Test with mock Exensio server
- Test with 1k, 10k, 20k records
- Test concurrent batch processing
- Test database transaction handling
- Test SSE event broadcasting

### Performance Tests

- Benchmark sequential vs batch vs parallel
- Measure memory usage under load
- Measure database connection pool usage
- Measure API throughput
- Measure end-to-end latency

### Thread Safety Tests

- Test with 100 concurrent threads
- Test for race conditions
- Test for deadlocks
- Test atomic counter accuracy
- Test database update consistency

## Migration Strategy

### Phase 1: Add Configuration (Backward Compatible)

```yaml
exensio:
  batch-size: 1  # Sequential mode (current behavior)
  thread-pool-size: 1  # Single-threaded (current behavior)
```

### Phase 2: Enable Batch Processing

```yaml
exensio:
  batch-size: 50  # Batch mode
  thread-pool-size: 1  # Still single-threaded
```

### Phase 3: Enable Parallel Processing

```yaml
exensio:
  batch-size: 50  # Batch mode
  thread-pool-size: 5  # Multi-threaded
```

### Phase 4: Tune for Production

```yaml
exensio:
  batch-size: 100  # Optimized batch size
  thread-pool-size: 10  # Optimized thread pool
  max-concurrent-requests: 20  # Optimized concurrency
```

## Rollback Plan

If issues arise, rollback to sequential mode:

```yaml
exensio:
  batch-size: 1
  thread-pool-size: 1
```

This reverts to the current behavior without code changes.
