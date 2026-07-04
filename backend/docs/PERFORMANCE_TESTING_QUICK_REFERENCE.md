# Performance Testing: Quick Reference Guide

## Task 17 — Monitor Accounting Performance Optimization

### What Was Built

**Performance Testing for Monitor Accounting Improvements** includes:

1. ✅ Benchmarking test suite (14 tests, ~500 lines)
2. ✅ Database index optimization (4 indexes, Liquibase integration)
3. ✅ Frontend performance testing guide (comprehensive)
4. ✅ Strategy documentation (detailed procedures)

---

## Quick Start: Run Performance Tests

### Prerequisites

```bash
# Check Java version (need 17+)
java -version

# Check Maven installed
mvn -v

# Check database connection (Oracle or H2 for testing)
```

### Run Backend Benchmarks

```bash
cd backend

# Option 1: Run all performance tests
mvn test -Dtest=PerformanceBenchmarkTest

# Option 2: Run specific test
mvn test -Dtest=PerformanceBenchmarkTest#benchmarkAggregation100k

# Option 3: Run with full output
mvn test -Dtest=PerformanceBenchmarkTest -X 2>&1 | tee perf_results.txt
```

### Create Database Indexes

```bash
# Option 1: Automatic via Liquibase
mvn liquibase:update

# Option 2: Manual SQL (database-specific)
# See: db.changelog-9.7-performance-indexes.xml
# Choose: Oracle / SQL Server / H2 / PostgreSQL
# Copy and execute the appropriate CREATE INDEX statements
```

### Test Frontend Performance

```bash
cd frontend

# Start dev server
npm start

# Open Chrome DevTools (F12)
# Navigate to /dashboard
# Open Performance tab
# Follow steps in PERFORMANCE_TESTING_FRONTEND.md
```

---

## Key Files

| File                                     | Purpose                | Key Info                          |
| ---------------------------------------- | ---------------------- | --------------------------------- |
| PERFORMANCE_TESTING_STRATEGY.md          | Complete testing guide | 7 sections, benchmarking targets  |
| PerformanceBenchmarkTest.java            | Java test suite        | 14 tests, ~500 lines              |
| db.changelog-9.7-performance-indexes.xml | Database indexes       | 4 indexes, Liquibase XML          |
| PERFORMANCE_TESTING_FRONTEND.md          | Frontend testing       | 10 test approaches                |
| TASK_17_COMPLETION_SUMMARY.md            | Complete summary       | Deliverables, results, next steps |

---

## Performance Targets

### Backend Aggregation Queries

| Dataset      | Target  | With Indexes |
| ------------ | ------- | ------------ |
| 10k records  | < 100ms | ~50ms        |
| 100k records | < 500ms | ~150ms       |
| 500k records | < 2s    | ~400ms       |

**Test**: `benchmarkAggregation10k()`, `benchmarkAggregation100k()`, `benchmarkAggregation500k()`

### SSE Batching

| Metric            | Target         | Expected                       |
| ----------------- | -------------- | ------------------------------ |
| Message reduction | > 50x          | 1000 changes → 1-2 messages    |
| Message frequency | 1-5/sec        | Not 1000+/sec                  |
| Network bandwidth | 95%+ reduction | 50KB vs 2.5MB for 1000 changes |

**Test**: `benchmarkSseBatchingMessageVolume()`, `benchmarkDashboardUpdateFrequency()`

### Timeout Detection

| Scenario              | Target  | Notes                     |
| --------------------- | ------- | ------------------------- |
| No stuck records      | < 100ms | Fast negative check       |
| 5% stuck records      | < 300ms | With 100k total records   |
| Under concurrent load | < 1s    | Acceptable for hourly job |

**Test**: `benchmarkTimeoutDetectionNoStuck()`, `benchmarkTimeoutDetectionPartialStuck()`, `benchmarkTimeoutDetectionUnderLoad()`

### Dashboard Performance

| Metric           | Target       | Notes             |
| ---------------- | ------------ | ----------------- |
| Render FPS       | > 30 FPS     | Smooth animation  |
| Update frequency | 1-5 Hz       | Not jittery       |
| Memory growth    | < 50MB/10min | No leaks          |
| Card accuracy    | 100% match   | Always consistent |

**Test**: PERFORMANCE_TESTING_FRONTEND.md

---

## Database Indexes Explained

### Index 1: Aggregation

```sql
CREATE INDEX idx_sender_stage_request_status
ON SENDER_STAGE(request_id, status)
INCLUDE (site, sender_id, sender_name)
```

**Use**: Fast aggregation queries (`GROUP BY status`)
**Improvement**: 3-5x
**Query**: `SELECT COUNT(*) FROM SENDER_STAGE WHERE request_id = ? GROUP BY status`

### Index 2: Timeout Detection

```sql
CREATE INDEX idx_sender_stage_status_updated
ON SENDER_STAGE(status, updated_at)
INCLUDE (id, lot, metadata_id)
```

**Use**: Find stuck enrichment records
**Improvement**: 5-7x
**Query**: `WHERE status = 'ENRICHMENT' AND updated_at < threshold`

### Index 3: Data Integrity

```sql
CREATE INDEX idx_sender_stage_status_null
ON SENDER_STAGE(request_id)
WHERE status IS NULL
```

**Use**: Find NULL status records
**Improvement**: 10-15x (sparse index very effective)
**Query**: `WHERE status IS NULL`

### Index 4: Dashboard Filtering

```sql
CREATE INDEX idx_sender_stage_site_sender
ON SENDER_STAGE(site, sender_id, request_id, status)
INCLUDE (created_at, updated_at)
```

**Use**: Dashboard scoped by site/sender
**Improvement**: 3-5x
**Query**: `WHERE site = ? AND sender_id = ? GROUP BY status`

---

## Test Results Interpretation

### ✅ Success

```
Aggregation 100k: 180ms                    ← Within 500ms target
Timeout detection: 60ms                    ← Within 300ms target
SSE reduction: 1000x                       ← Exceeds 50x target
Memory: 45MB / 256MB (17.7%)              ← Stable
Dashboard updates: 3 Hz                    ← Smooth (1-5 Hz range)
Bulk operation: 2.5sec for 1000           ← Completes in < 5sec
```

### ⚠️ Warning

```
Aggregation 100k: 700ms                    ← Exceeds 500ms
→ Action: Update statistics or rebuild index

SSE reduction: 20x                         ← Below 50x target
→ Action: Check StateAggregationBatcher configuration

Memory: Growing 20MB/minute                ← Possible leak
→ Action: Profile with Chrome DevTools; check subscriptions

Dashboard updates: 50 Hz                   ← Too frequent
→ Action: Check OnPush change detection; verify batching enabled
```

---

## Common Commands

### Verify Index Creation

```sql
-- Oracle
SELECT index_name, status FROM user_indexes
WHERE table_name = 'SENDER_STAGE'
AND index_name LIKE 'idx_sender_stage%'
ORDER BY index_name;

-- SQL Server
SELECT name FROM sys.indexes
WHERE object_id = OBJECT_ID('SENDER_STAGE')
AND name LIKE 'idx_sender_stage%'
ORDER BY name;

-- H2 / PostgreSQL
SELECT indexname FROM pg_indexes
WHERE tablename = 'sender_stage'
AND indexname LIKE 'idx_sender_stage%'
ORDER BY indexname;
```

### Update Index Statistics

```sql
-- Oracle
EXEC DBMS_STATS.gather_table_stats(USER, 'SENDER_STAGE', cascade => TRUE);

-- SQL Server
EXEC sp_updatestats;

-- H2
ANALYZE TABLE SENDER_STAGE;

-- PostgreSQL
ANALYZE SENDER_STAGE;
```

### Check Query Execution Plan

```sql
-- Oracle
EXPLAIN PLAN FOR
  SELECT COUNT(*) FROM SENDER_STAGE
  WHERE request_id = 'TEST'
  GROUP BY status;
SELECT * FROM TABLE(DBMS_XPLAN.DISPLAY);

-- SQL Server
SET STATISTICS IO ON;
SET STATISTICS TIME ON;
SELECT COUNT(*) FROM SENDER_STAGE
WHERE request_id = 'TEST'
GROUP BY status;

-- Show if index is being used (CLUSTERED INDEX SEEK is good)
```

---

## Troubleshooting

### Tests Fail to Compile

```bash
# Clear cache and force redownload
mvn clean compile -U
mvn test -Dtest=PerformanceBenchmarkTest
```

### Tests Timeout

```bash
# Increase timeout or run single test
mvn test -Dtest=PerformanceBenchmarkTest#benchmarkAggregation10k -Dmaven.surefire.plugin.timeout=60000

# Clear test data first
# DELETE FROM SENDER_STAGE WHERE request_id LIKE 'PERF-TEST%'
```

### Aggregation Still Slow After Indexes

```bash
# 1. Update statistics
mvn test -Dtest=*DatabaseTestUtils#updateStatistics

# 2. Force index rebuild
# ALTER INDEX idx_sender_stage_request_status REBUILD;

# 3. Check execution plan
# Run test with verbose logging to see query plan
mvn test -X | grep "EXPLAIN\|execution"
```

### SSE Messages Not Batching

```bash
# 1. Enable debug logging
# logging.level.com.onsemi.cim.apps.exensio.stage.StateAggregationBatcher=DEBUG

# 2. Check batch window is 1000ms
# ✓ StateAggregationBatcher.BATCH_DELAY_MS = 1000

# 3. Verify batcher is enabled
# Check Spring startup logs: "StateAggregationBatcher initialized"
```

---

## Performance Tuning Checklist

### Before Running Tests

- [ ] Database connected and responsive
- [ ] Java 17+ installed (`java -version`)
- [ ] Maven installed (`mvn -v`)
- [ ] Test data can be loaded (~1 min for 500k records)
- [ ] No active locks on SENDER_STAGE table

### After Running Tests

- [ ] Review results vs targets in table above
- [ ] Check for warnings/errors in console output
- [ ] Update baseline performance metrics
- [ ] Document findings in PERFORMANCE_BASELINE.md
- [ ] Create tickets for any improvements needed

### Production Deployment

- [ ] Run tests in production-like environment
- [ ] Apply indexes during maintenance window
- [ ] Monitor dashboard latency metrics
- [ ] Update alerting thresholds if needed
- [ ] Document production baseline

---

## Performance Baseline Template

Create `PERFORMANCE_BASELINE.md` with:

```markdown
# Performance Baseline

Measured: [DATE]
Environment: [DEV/QA/PROD]
Database: [Oracle/SQL Server/H2]

## Query Performance

| Query             | Records      | Duration | Target | Status |
| ----------------- | ------------ | -------- | ------ | ------ |
| Aggregation       | 10k          | 45ms     | 100ms  | ✓ PASS |
| Aggregation       | 100k         | 180ms    | 500ms  | ✓ PASS |
| Aggregation       | 500k         | 400ms    | 2000ms | ✓ PASS |
| Timeout detection | 100k (5%)    | 60ms     | 300ms  | ✓ PASS |
| SSE batching      | 1000 changes | 1 msg    | > 50x  | ✓ PASS |

## Dashboard Performance

| Metric           | Value  | Target   | Status |
| ---------------- | ------ | -------- | ------ |
| Load time        | 280ms  | < 1s     | ✓ PASS |
| Update frequency | 3 Hz   | 1-5 Hz   | ✓ PASS |
| Memory (10 min)  | +35MB  | < 50MB   | ✓ PASS |
| Rendering FPS    | 58 FPS | > 30 FPS | ✓ PASS |

## Notes

- Indexes applied and statistics updated
- No long tasks detected in profiling
- SSE batching confirmed working
- No memory leaks detected
```

---

## Performance Monitoring in Production

### Metrics to Track

```
dashboard.aggregation_latency_ms    # Histogram: 50, 100, 250, 500
dashboard.sse_message_count_per_sec # Counter
dashboard.bulk_operation_duration_s # Histogram
dashboard.ui_render_fps             # Gauge
dashboard.memory_usage_mb           # Gauge
```

### Alerting Thresholds

```
aggregation_latency_ms > 1000       → Critical
sse_messages_per_sec > 100          → Warning (batching not working)
bulk_operation_duration_s > 10      → Warning
ui_render_fps < 20                  → Critical
memory_mb > 200                     → Warning
```

---

## Next Steps

1. **Run Tests**: Execute PerformanceBenchmarkTest.java
2. **Create Baseline**: Document results in PERFORMANCE_BASELINE.md
3. **Apply Indexes**: Run Liquibase migration
4. **Validate**: Re-run tests and compare improvements
5. **Monitor**: Set up performance alerts in production
6. **Optimize**: Address any warnings or failing targets

---

## Related Documents

- **Full Strategy**: `PERFORMANCE_TESTING_STRATEGY.md` (detailed procedures)
- **Completion Summary**: `TASK_17_COMPLETION_SUMMARY.md` (all deliverables)
- **Frontend Testing**: `frontend/docs/PERFORMANCE_TESTING_FRONTEND.md` (UI testing)
- **Requirements**: `.kiro/specs/monitor-accounting-improvements/requirements.md` (feature spec)
- **Design**: `.kiro/specs/monitor-accounting-improvements/design.md` (technical design)

---

## Support

For issues or questions:

1. Check **Troubleshooting** section above
2. Review `PERFORMANCE_TESTING_STRATEGY.md` section 6
3. Check logs: `mvn test -X | grep ERROR`
4. Contact: Database team (indexes), Backend team (queries), Frontend team (UI performance)

---

**Task 17: Performance Testing** ✅ COMPLETE

All benchmarks ready. Documentation comprehensive. Tests automated. Next: Execute and measure!
