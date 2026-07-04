# Performance Testing Strategy: Monitor Accounting Improvements

## Overview

Task 17 involves performance testing for the monitor accounting system. This document provides:

1. **Benchmarking Strategy**: How to test aggregation queries at scale (100k+ records)
2. **SSE Batching Verification**: Quantifying message volume reduction
3. **Timeout Detection Performance**: Testing stuck record detection queries
4. **Index Optimization**: Database index recommendations
5. **Manual Test Execution Guide**: Step-by-step instructions for developers

---

## 1. Aggregation Query Performance Benchmarking

### Current Implementation

The `RefDbService.fetchStatuses()` method performs aggregation queries that count records by state:

```sql
SELECT site, sender_id, sender_name,
  COUNT(*) as total,
  SUM(CASE WHEN status = 'pending' THEN 1 ELSE 0 END) as ready,
  SUM(CASE WHEN status = 'ENQUEUED' THEN 1 ELSE 0 END) as queued,
  SUM(CASE WHEN status = 'ENRICHMENT' THEN 1 ELSE 0 END) as enriching,
  SUM(CASE WHEN status = 'EXENSIO_LOADING' THEN 1 ELSE 0 END) as exensio_loading,
  SUM(CASE WHEN status = 'FAILED' THEN 1 ELSE 0 END) as failed,
  SUM(CASE WHEN status = 'DONE' THEN 1 ELSE 0 END) as completed,
  SUM(CASE WHEN status = 'CANCELLED' THEN 1 ELSE 0 END) as cancelled
FROM SENDER_STAGE
WHERE request_id = ?
GROUP BY site, sender_id, sender_name
```

### Benchmark Targets

| Record Count | Target Latency | Reason                              |
| ------------ | -------------- | ----------------------------------- |
| 10,000       | < 100ms        | Small session                       |
| 100,000      | < 500ms        | Large session                       |
| 1,000,000    | < 2s           | Multi-session aggregate (edge case) |

### Benchmark Methodology

**Test 1: Single Session with N Records**

```
FOR N in [1000, 10000, 100000, 500000, 1000000]:
  1. Load N records into SENDER_STAGE with same request_id
  2. Distribute N records across 8 states (1/8 each)
  3. Time execution of fetchStatuses(request_id)
  4. Record latency and throughput (records/sec)
  5. Calculate reads per second and I/O pattern
```

**Test 2: Multiple Sessions (Realistic Load)**

```
FOR N in [100, 1000, 10000]:
  1. Create 10 concurrent sessions with N records each
  2. Execute fetchStatusesFor(site, sender_id, request_id) for each
  3. Measure total time for all 10 queries to complete
  4. Calculate query throughput (queries/sec)
  5. Identify any locking or contention issues
```

**Test 3: Aggregation at Dashboard Level**

```
1. Load 100k total records across 50 sessions
2. Time execution of fetchStatuses() to get all sessions' aggregations
3. Verify that result set has 50 rows (one per sender)
4. Compare latency to single-session baseline
5. Verify GROUP BY performance scales linearly
```

### Expected Results

Based on typical database performance:

- **10k records**: ~50-100ms (fast, simple scan + aggregation)
- **100k records**: ~200-500ms (medium, requires index on status)
- **1M records**: ~1-2s (acceptable for hourly aggregation, needs index optimization)

### Performance Analysis Steps

1. **Before Optimization**:
   - Run base query without indexes
   - Record execution plan (EXPLAIN)
   - Note any full table scans or hash aggregations

2. **After Index Creation**:
   - Create indexes (see section 4 below)
   - Re-run same tests
   - Compare execution times and plans
   - Target: 3-5x improvement for large datasets

3. **Contention Testing**:
   - Simulate concurrent updates while aggregation runs
   - Verify aggregation queries don't block or get blocked
   - Use isolation levels to ensure consistency

---

## 2. SSE Batching Verification

### Current Implementation

`StateAggregationBatcher` collects state changes over 1-second windows and broadcasts aggregated events.

**Without Batching** (per-record events):

```
Record 1 → Status change A → Broadcast event
Record 2 → Status change B → Broadcast event
Record 3 → Status change C → Broadcast event
...
Record 1000 → Status change Z → Broadcast event

Total: ~1000 SSE messages in 1 second
```

**With Batching** (1-second window):

```
[0-1 second window]
Record 1 → Status change A → Accumulate
Record 2 → Status change B → Accumulate
Record 3 → Status change C → Accumulate
...
Record 1000 → Status change Z → Accumulate

[At 1 second boundary]
Broadcast single aggregated event with all 1000 changes
Total: ~1 SSE message in 1 second
```

### Verification Methodology

**Test 1: Measure Message Volume Reduction**

```java
@Test
public void testSseMessageVolumeReduction() {
    // Setup
    String sessionId = "test-session-123";
    AtomicInteger messageCount = new AtomicInteger(0);

    // Mock SSE broadcaster to count messages
    doAnswer(inv -> {
        messageCount.incrementAndGet();
        return null;
    }).when(monitorService).broadcastStateAggregation(any(), any());

    // Simulate bulk operation: 1000 state changes in 1 second
    long startTime = System.currentTimeMillis();
    for (int i = 0; i < 1000; i++) {
        batcher.recordStateChange(sessionId, "ENRICHMENT", i, i+1);
        if (i % 100 == 0) Thread.sleep(10);  // Simulate slight delays
    }

    // Wait for batch flush
    Thread.sleep(1500);

    long endTime = System.currentTimeMillis();
    long duration = endTime - startTime;

    // Verify
    int messagesExpected = 1;  // All batched into one event
    assertEquals(messagesExpected, messageCount.get(),
        "Should batch 1000 changes into 1 message");

    System.out.println("Messages: " + messageCount.get() +
                       " (reduction: " + (1000 / messageCount.get()) + "x)");
}
```

**Test 2: Message Volume Under High Concurrency**

```java
@Test
public void testSseMessageVolumeDuringBulkCancel() {
    // Simulate bulk cancel of 5000 records
    AtomicInteger messageCount = new AtomicInteger(0);
    doAnswer(inv -> {
        messageCount.incrementAndGet();
        return null;
    }).when(monitorService).broadcastStateAggregation(any(), any());

    // Bulk cancel creates rapid status changes
    bulkCancelBySender(TEST_SENDER_ID);  // Updates 5000 records

    // Wait for all batches to flush (5 seconds to be safe)
    Thread.sleep(5000);

    // Verify reduction
    // Without batching: ~5000 messages
    // With batching: ~5 messages (one per second)
    assertTrue(messageCount.get() <= 10,
        "Should batch into ~5-10 messages, got " + messageCount.get());

    double reductionFactor = 5000.0 / messageCount.get();
    System.out.println("Bulk cancel reduction: " + reductionFactor + "x");
}
```

**Test 3: Frontend Update Frequency**

```typescript
// Frontend test: Measure how often dashboard updates occur
it('should batch SSE updates to reduce UI redraws', (done) => {
  let updateCount = 0;
  let previousTotals = { staged: 0, queued: 0 };

  // Subscribe to state aggregation events
  monitoringService.stateAggregation$.subscribe((event) => {
    updateCount++;

    // Verify update changed actual totals (not duplicate updates)
    if (event.totals.staged !== previousTotals.staged || event.totals.queued !== previousTotals.queued) {
      previousTotals = event.totals;
    }
  });

  // Trigger 1000 status changes
  simulateBulkStatusChange(1000);

  // Wait for batches
  setTimeout(() => {
    // Expect ~1-5 updates instead of 1000
    expect(updateCount).toBeLessThan(10);
    console.log(`UI updates: ${updateCount} (reduction: ${1000 / updateCount}x)`);
    done();
  }, 5000);
});
```

### Target Metrics

| Metric                      | Target     | Reason                                  |
| --------------------------- | ---------- | --------------------------------------- |
| Message reduction factor    | > 50x      | 1000 changes → ~20 messages max         |
| Dashboard update frequency  | 1-5/second | Smooth animation, not jittery           |
| Network bandwidth reduction | 95%+       | Proportional to message count reduction |
| CPU usage (monitor service) | < 10%      | Batch processing shouldn't overload CPU |
| Memory overhead (batcher)   | < 10MB     | Accumulator shouldn't grow unbounded    |

---

## 3. Timeout Detection Query Performance

### Current Implementation

`CpLogMonitor.detectStuckEnrichmentRecords()` queries for records exceeding timeout:

```sql
SELECT id, lot, metadata_id, created_at, updated_at
FROM SENDER_STAGE
WHERE status = 'ENRICHMENT'
AND DATEDIFF(MINUTE, updated_at, GETDATE()) > ?  -- enrichment_timeout_minutes
AND request_id = ?
```

### Benchmark Targets

| Dataset Size | Query Latency Target | Notes                           |
| ------------ | -------------------- | ------------------------------- |
| 10k records  | < 50ms               | Hourly job, not time-critical   |
| 100k records | < 200ms              | Acceptable for scheduled job    |
| 1M records   | < 1s                 | Should complete within job slot |

### Benchmark Methodology

**Test 1: Baseline Query Performance**

```java
@Test
public void benchmarkTimeoutDetectionQuery() {
    // Setup: 100k records, 10% in ENRICHMENT, 5% stuck (5k stuck records)
    loadTestData(100000, 0.10, 0.05);

    long startTime = System.nanoTime();
    List<StageRecord> stuckRecords = cpLogMonitor.detectStuckEnrichmentRecords(
        TEST_REQUEST_ID, 5  // 5-minute timeout
    );
    long endTime = System.nanoTime();

    long durationMs = (endTime - startTime) / 1_000_000;

    assertEquals(5000, stuckRecords.size(), "Should find 5k stuck records");
    assertTrue(durationMs < 200,
        "Query should complete within 200ms, took " + durationMs + "ms");
}
```

**Test 2: Query Performance with Variable Data Age**

```java
@Test
public void benchmarkTimeoutDetectionWithVariableAge() {
    // Scenarios:
    // 1. All records < 5 minutes old (no stuck)
    // 2. All records > 5 minutes old (all stuck)
    // 3. Mixed: 50% < 5 min, 50% > 5 min

    for (String scenario : Arrays.asList("no-stuck", "all-stuck", "half-stuck")) {
        loadTestScenario(scenario, 100000);

        long startTime = System.nanoTime();
        List<StageRecord> stuckRecords = cpLogMonitor.detectStuckEnrichmentRecords(
            TEST_REQUEST_ID, 5
        );
        long endTime = System.nanoTime();

        long durationMs = (endTime - startTime) / 1_000_000;
        System.out.println(scenario + ": " + durationMs + "ms, found: " + stuckRecords.size());
    }
}
```

**Test 3: Concurrency: Timeout Detection During Active Writes**

```java
@Test
public void testTimeoutDetectionUnderConcurrentLoad() {
    // Background: continuous updates to SENDER_STAGE
    ExecutorService executor = Executors.newFixedThreadPool(5);

    for (int i = 0; i < 5; i++) {
        executor.submit(() -> {
            for (int j = 0; j < 1000; j++) {
                stageService.updateStatus(TEST_REQUEST_ID, "ENRICHMENT", "DONE");
                Thread.sleep(10);
            }
        });
    }

    // Measure timeout detection query latency during concurrent updates
    List<Long> latencies = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
        long start = System.nanoTime();
        List<StageRecord> stuck = cpLogMonitor.detectStuckEnrichmentRecords(
            TEST_REQUEST_ID, 5
        );
        long end = System.nanoTime();
        latencies.add((end - start) / 1_000_000);
        Thread.sleep(500);
    }

    executor.shutdown();
    executor.awaitTermination(30, TimeUnit.SECONDS);

    // Verify
    double avgLatency = latencies.stream().mapToLong(Long::longValue).average().orElse(0);
    double maxLatency = latencies.stream().mapToLong(Long::longValue).max().orElse(0);

    System.out.println("Avg latency: " + avgLatency + "ms, Max: " + maxLatency + "ms");
    assertTrue(maxLatency < 1000, "Query should not exceed 1s even under load");
}
```

### Expected Results

- **No stuck records**: ~30-50ms (quick negative check)
- **5% stuck records**: ~80-150ms (index on status + updated_at helps)
- **50% stuck records**: ~150-300ms (more rows to scan but index still effective)
- **Under concurrent load**: ~1.5-2x baseline (slight contention but acceptable)

---

## 4. Database Index Optimization

### Current Index Gaps

The aggregation and timeout queries need indexes to perform well:

```sql
-- Query 1: Aggregation by request_id and status
SELECT ... FROM SENDER_STAGE WHERE request_id = ? GROUP BY status

-- Query 2: Timeout detection
SELECT ... FROM SENDER_STAGE
WHERE status = 'ENRICHMENT' AND DATEDIFF(MINUTE, updated_at, GETDATE()) > ?

-- Query 3: Data integrity checks
SELECT ... FROM SENDER_STAGE WHERE status IS NULL
SELECT ... FROM SENDER_STAGE WHERE status NOT IN (valid_states)
```

### Recommended Indexes

**Index 1: Aggregation Index**

```sql
-- For fast aggregation queries by request_id and status
CREATE INDEX idx_sender_stage_request_status ON SENDER_STAGE(request_id, status)
INCLUDE (site, sender_id, sender_name)
WHERE status IS NOT NULL;

-- This index supports:
-- - WHERE request_id = ? GROUP BY status
-- - WHERE request_id = ? AND status = ?
-- - Covers all columns needed without table lookup
```

**Index 2: Timeout Detection Index**

```sql
-- For fast stuck record detection
CREATE INDEX idx_sender_stage_status_updated ON SENDER_STAGE(status, updated_at)
INCLUDE (id, lot, metadata_id)
WHERE status IN ('ENRICHMENT', 'EXENSIO_LOADING');

-- This index supports:
-- - WHERE status = 'ENRICHMENT' AND updated_at < datetime
-- - Clustered by status (faster filtering)
-- - Includes fields needed for return set
```

**Index 3: Data Integrity Index**

```sql
-- For NULL/invalid status detection
CREATE INDEX idx_sender_stage_status_null ON SENDER_STAGE(request_id)
WHERE status IS NULL;

-- This sparse index only contains NULL status rows
-- Very fast for finding problematic records
```

**Index 4: Site/Sender Scoping Index**

```sql
-- For dashboard filtering by site or sender
CREATE INDEX idx_sender_stage_site_sender ON SENDER_STAGE(site, sender_id, request_id, status)
INCLUDE (created_at, updated_at)
WHERE status IS NOT NULL;

-- Supports all dashboard queries with site/sender filtering
```

### Index Creation Script

```sql
-- Monitor Accounting Improvements: Database Indexes
-- Created: 2026-07-04
-- Purpose: Optimize aggregation queries, timeout detection, and data integrity checks

USE refdb;  -- or appropriate database name

-- Drop existing indexes if they exist
IF EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'idx_sender_stage_request_status')
    DROP INDEX idx_sender_stage_request_status ON SENDER_STAGE;

IF EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'idx_sender_stage_status_updated')
    DROP INDEX idx_sender_stage_status_updated ON SENDER_STAGE;

IF EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'idx_sender_stage_status_null')
    DROP INDEX idx_sender_stage_status_null ON SENDER_STAGE;

IF EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'idx_sender_stage_site_sender')
    DROP INDEX idx_sender_stage_site_sender ON SENDER_STAGE;

-- Index 1: Aggregation queries (request_id, status)
CREATE INDEX idx_sender_stage_request_status
ON SENDER_STAGE(request_id, status)
INCLUDE (site, sender_id, sender_name)
WHERE status IS NOT NULL;

-- Index 2: Timeout detection (status, updated_at)
CREATE INDEX idx_sender_stage_status_updated
ON SENDER_STAGE(status, updated_at)
INCLUDE (id, lot, metadata_id)
WHERE status IN ('ENRICHMENT', 'EXENSIO_LOADING');

-- Index 3: NULL status detection (sparse index)
CREATE INDEX idx_sender_stage_status_null
ON SENDER_STAGE(request_id)
WHERE status IS NULL;

-- Index 4: Site/sender filtering
CREATE INDEX idx_sender_stage_site_sender
ON SENDER_STAGE(site, sender_id, request_id, status)
INCLUDE (created_at, updated_at)
WHERE status IS NOT NULL;

-- Verify indexes were created
SELECT
    I.name AS IndexName,
    T.name AS TableName,
    STATS_DATE(I.object_id, I.index_id) AS LastUpdated
FROM sys.indexes I
INNER JOIN sys.tables T ON I.object_id = T.object_id
WHERE T.name = 'SENDER_STAGE'
AND I.name LIKE 'idx_sender_stage%'
ORDER BY I.name;
```

### Index Maintenance

```sql
-- Weekly: Update statistics
EXEC sp_updatestats;

-- Weekly: Rebuild fragmented indexes (fragmentation > 30%)
DBCC SHOWCONTIG (SENDER_STAGE) WITH TABLERESULTS;

EXEC sp_MSForEachTable @command1 = '
  ALTER INDEX ALL ON ? REBUILD
  WHERE INDEXPROPERTY(OBJECT_ID(''?''), index_id(''?''), ''IndexDepth'') > 0
';

-- Monthly: Check unused indexes
SELECT
    OBJECT_NAME(i.object_id) AS TableName,
    i.name AS IndexName,
    s.user_seeks,
    s.user_scans,
    s.user_lookups,
    s.user_updates
FROM sys.indexes i
LEFT JOIN sys.dm_db_index_usage_stats s
    ON i.object_id = s.object_id
    AND i.index_id = s.index_id
WHERE OBJECT_NAME(i.object_id) = 'SENDER_STAGE'
    AND s.user_seeks + s.user_scans + s.user_lookups = 0
ORDER BY s.user_updates DESC;
```

### Performance Impact Estimate

| Operation               | Before Indexes | After Indexes | Improvement |
| ----------------------- | -------------- | ------------- | ----------- |
| Aggregation (100k rows) | ~800ms         | ~150ms        | 5.3x        |
| Timeout detection (5k)  | ~400ms         | ~60ms         | 6.7x        |
| NULL status check       | ~600ms         | ~40ms         | 15x         |
| Dashboard load (all)    | ~1500ms        | ~300ms        | 5x          |

---

## 5. Manual Test Execution Guide

### Prerequisites

- Java 17+ and Maven installed
- Access to RefDB (Oracle or H2 test)
- 5-10 minutes per test suite

### Running Aggregation Benchmarks

```bash
# Compile and run the aggregation performance tests
cd backend

# Run all performance tests
mvn test -Dtest=*PerformanceTest

# Run specific performance test
mvn test -Dtest=StateAccountingPerformanceTest

# Run with verbose output
mvn test -Dtest=StateAccountingPerformanceTest -X

# Expected output:
# Test: Aggregation with 10k records
# Duration: 45ms
# Throughput: 222,222 records/sec
#
# Test: Aggregation with 100k records
# Duration: 180ms
# Throughput: 555,555 records/sec
```

### Running SSE Batching Tests

```bash
# Run SSE batching tests
mvn test -Dtest=StateAggregationBatcherTest

# Look for output like:
# Messages: 1 (reduction: 1000x)
# Bulk cancel reduction: 500x
```

### Verifying Index Performance

```bash
# 1. Connect to RefDB
# Oracle:
sqlplus refdb_user/password@refdb_host

# H2:
java -cp "h2-*.jar" org.h2.tools.Shell -url "jdbc:h2:mem:refdb"

# 2. Create indexes (see section 4 above)
# 3. Run baseline queries
SELECT * FROM SENDER_STAGE WHERE request_id = 'TEST' AND status = 'ENRICHMENT';
SELECT * FROM SENDER_STAGE WHERE status = 'ENRICHMENT' AND updated_at < CURRENT_TIMESTAMP - INTERVAL '5' MINUTE;

# 4. Check execution plan
-- Oracle
EXPLAIN PLAN FOR SELECT ...;
SELECT * FROM TABLE(DBMS_XPLAN.DISPLAY);

-- SQL Server
SET STATISTICS IO ON;
SET STATISTICS TIME ON;
SELECT ...;
```

### Reviewing Performance Metrics

After running tests, check:

1. **Query Execution Time**:
   - Check console output for duration measurements
   - Compare against target latencies (Section 1 & 3)
   - Investigate outliers

2. **Message Reduction**:
   - Verify SSE batch tests show > 50x reduction
   - Check that dashboard update frequency is 1-5/second

3. **Index Effectiveness**:
   - Verify execution plans use indexes (no full table scans)
   - Check index usage statistics in database

4. **Concurrency**:
   - Verify no deadlocks in logs
   - Check contention metrics (lock timeouts)
   - Verify query latency stable under load

---

## 6. Troubleshooting Performance Issues

### Issue: Aggregation Query Still Slow (> 500ms)

**Diagnosis**:

1. Check execution plan: `EXPLAIN PLAN FOR SELECT ...`
2. Verify index `idx_sender_stage_request_status` exists
3. Check table statistics: `ANALYZE TABLE SENDER_STAGE`

**Solution**:

- Rebuild indexes: `ALTER INDEX idx_sender_stage_request_status REBUILD`
- Update statistics: `ANALYZE TABLE SENDER_STAGE`
- Consider partitioning by request_id if table > 10M rows

### Issue: Timeout Detection Missing Records

**Diagnosis**:

1. Verify timeout threshold is reasonable (default 5 min)
2. Check `updated_at` is being updated correctly
3. Verify data isn't filtered unintentionally

**Solution**:

- Adjust timeout: `UPDATE application.yml enrichment-timeout-minutes`
- Force update_at refresh: `UPDATE SENDER_STAGE SET updated_at = CURRENT_TIMESTAMP WHERE status = 'ENRICHMENT'`

### Issue: SSE Messages Not Batching

**Diagnosis**:

1. Check `StateAggregationBatcher` is enabled
2. Verify batch delay is 1000ms (not changed)
3. Check logs for batch flush messages

**Solution**:

- Verify Spring component scan includes `StateAggregationBatcher`
- Check `batcher.BATCH_DELAY_MS` is 1000
- Enable DEBUG logging: `logging.level.com.onsemi.cim.apps.exensio.stage.StateAggregationBatcher=DEBUG`

---

## 7. Performance Regression Testing

To prevent performance degradation after future changes:

### Add to CI/CD Pipeline

```yaml
# .github/workflows/performance-check.yml
name: Performance Regression Check

on: [pull_request]

jobs:
  performance-tests:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      - uses: actions/setup-java@v3
      - name: Run performance tests
        run: mvn test -Dtest=*PerformanceTest
      - name: Check results
        run: |
          if grep -q "FAILED\|Regression" target/surefire-reports/*.txt; then
            echo "Performance regression detected!"
            exit 1
          fi
```

### Baseline Metrics (After First Run)

Record these values after successful optimization:

```
Aggregation (100k records): 180ms ± 10%
SSE message reduction: > 50x
Timeout detection: 60ms ± 10%
Dashboard load: 300ms ± 15%
```

Any future test runs > 110% of baseline should trigger investigation.

---

## Summary

Task 17 Performance Testing involves:

1. ✅ **Benchmarking aggregation queries** with 100k+ records (target: < 500ms)
2. ✅ **Verifying SSE batching** reduces message volume > 50x
3. ✅ **Testing timeout detection** performance (target: < 200ms)
4. ✅ **Optimizing database indexes** for 5-10x improvement
5. ✅ **Providing manual test procedures** for developers to execute

**Recommended Execution Order**:

1. Create indexes (Section 4) - SQL script provided
2. Run aggregation benchmarks (Section 1)
3. Run SSE batching tests (Section 2)
4. Run timeout detection tests (Section 3)
5. Review results against targets
6. Document findings in project performance profile
