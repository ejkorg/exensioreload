# Monitor Page Configuration Guide

## Overview

The Monitor Page State Accounting system includes several configurable parameters that control timeout thresholds, data integrity checks, and SSE update batching. This guide explains each configuration option and how to set it for your environment.

---

## Configuration Properties

### Monitor/Enrichment Timeout Configuration

#### Property: `enrichmentTimeoutMinutes`

**Location:** `CpElasticsearchProperties` / `application.yml`

**Description:** Maximum number of minutes a record can remain in the `ENRICHMENT` state before being flagged as stuck. After exceeding this timeout, the data integrity job will attempt auto-remediation by marking the record as `DONE` with a `manual-verify` flag.

**Default Value:** `5` (minutes)

**Valid Range:** `1` to `60` (minutes)

**YAML Configuration:**

```yaml
cp:
  elasticsearch:
    # ... other ES config ...
    enrichment-timeout-minutes: 5
```

**Environment Variable Override:**

```bash
export CP_ENRICHMENT_TIMEOUT_MINUTES=5
```

**How It Works:**

1. Scheduled data integrity job queries for records in `ENRICHMENT` status
2. For each record where `(CURRENT_TIME - updated_at) > enrichmentTimeoutMinutes`:
   - Record is flagged as "stuck"
   - Admin alert is emitted via SSE
   - Record appears in stuck records badge on dashboard
3. On next integrity check cycle (default: hourly), stuck records are auto-remediated
4. Auto-remediation marks the record as `DONE` with `manual-verify` flag for audit trail

**Example Values:**

| Timeout | Use Case                                                                       |
| ------- | ------------------------------------------------------------------------------ |
| 1 min   | Very strict; catches delays immediately (may false-positive during heavy load) |
| 5 min   | Default; balanced for typical CP processing (recommended)                      |
| 10 min  | Relaxed; only flags genuinely stuck records, allows slower processing          |
| 30 min  | Very relaxed; only for high-latency environments                               |

**Important Notes:**

- ⚠️ Changing this value affects **future** records only; retroactive changes don't affect already-stuck records
- ⚠️ Setting too low → false positives, unnecessary auto-remediation
- ⚠️ Setting too high → stuck records remain visible longer before auto-remediation

**Validation:**

- Minimum value: `1` (must timeout eventually)
- Maximum value: `60` (to prevent extremely long timeouts)
- Non-numeric or negative values → default to `5`

---

### Data Integrity Job Scheduling

#### Property: `dataIntegrityCheck.cron`

**Location:** `DataIntegrityJob` / `application.yml`

**Description:** Cron expression controlling how often the scheduled data integrity verification job runs. This job:

- Checks all records for valid status values
- Detects and flags NULL status records
- Detects and auto-remediates stuck enrichment records
- Generates and logs a data integrity report

**Default Value:** `"0 0 * * * *"` (every hour, at minute 0)

**Cron Format:** Standard Spring `@Scheduled` format (quartz-style)

**YAML Configuration:**

```yaml
app:
  data-integrity-check:
    cron: '0 0 * * * *'
    enabled: true
```

**Environment Variable Override:**

```bash
export APP_DATA_INTEGRITY_CHECK_CRON="0 0 * * * *"
```

**Common Cron Expressions:**

| Expression            | Schedule                           | Use Case                        |
| --------------------- | ---------------------------------- | ------------------------------- |
| `"0 0 * * * *"`       | Every hour (0 min past)            | Default; balanced               |
| `"0 */30 * * * *"`    | Every 30 minutes                   | High-frequency checks           |
| `"0 */5 * * * *"`     | Every 5 minutes                    | Very frequent (high DB load)    |
| `"0 0 0 * * *"`       | Once daily (midnight)              | Low-frequency, batch processing |
| `"0 0 6,12,18 * * *"` | Three times daily (6am, 12pm, 6pm) | Business hours                  |
| `"0 0 * * * MON"`     | Weekly (Mondays at midnight)       | Weekly audits                   |

**Cron Syntax Reference:**

```
 ┌───────────── second (0-59)
 │ ┌───────────── minute (0-59)
 │ │ ┌───────────── hour (0-23)
 │ │ │ ┌───────────── day of month (1-31)
 │ │ │ │ ┌───────────── month (1-12)
 │ │ │ │ │ ┌───────────── day of week (0-7, where 0 and 7 = Sunday)
 │ │ │ │ │ │
 * * * * * *
```

**Examples:**

- `"0 0 * * * *"` → Every hour
- `"0 0 6 * * *"` → Daily at 6 AM
- `"0 */15 * * * *"` → Every 15 minutes
- `"0 0 * * * MON"` → Every Monday midnight
- `"0 0 1 * * *"` → First day of month at midnight

**Important Notes:**

- ⚠️ Very frequent checks (< 5 minutes) may impact database performance
- ⚠️ Very infrequent checks (> 24 hours) delay stuck record detection and auto-remediation
- ⚠️ Timing should avoid peak processing hours if possible
- ✅ Default (hourly) is recommended for most deployments

**Disabling the Job:**

To disable the integrity job entirely (not recommended):

```yaml
app:
  data-integrity-check:
    enabled: false
```

**Monitoring the Job:**

Check logs for job execution:

```bash
grep "DataIntegrityJob" logs/exensioreload.log
```

Expected output:

```
[INFO] DataIntegrityJob: Starting data integrity verification...
[INFO] DataIntegrityJob: Found 0 invalid states, 0 NULL statuses, 3 stuck enrichment records
[INFO] DataIntegrityJob: Auto-remediated 3 stuck records
[INFO] DataIntegrityJob: Data integrity check complete
```

---

### SSE Update Batching

#### Property: `stageMonitor.updateBatchWindowMs`

**Location:** `StateAggregationBatcher` / `application.yml`

**Description:** Time window (in milliseconds) for batching rapid state change events before broadcasting a single `STATE_AGGREGATION` event via SSE. During bulk operations (e.g., bulk cancel), many records may change state rapidly. This setting controls how they are grouped into broadcast events.

**Default Value:** `1000` (1 second)

**Valid Range:** `100` to `10000` (milliseconds)

**YAML Configuration:**

```yaml
app:
  stage-monitor:
    update-batch-window-ms: 1000
```

**Environment Variable Override:**

```bash
export APP_STAGE_MONITOR_UPDATE_BATCH_WINDOW_MS=1000
```

**How It Works:**

1. Record state changes are collected in a buffer
2. Buffer accumulates changes for `updateBatchWindowMs` duration
3. After the window expires, accumulated changes are aggregated and broadcast as a single `STATE_AGGREGATION` event
4. Dashboard receives one update instead of many individual updates
5. New buffer starts for the next batch

**Example:**

Without batching:

```
100 records change state → 100 separate SSE events → High network traffic
```

With batching (1000ms window):

```
100 records change state → Collected in buffer → 1 aggregated SSE event → Lower traffic
```

**Common Values:**

| Value  | Use Case                                |
| ------ | --------------------------------------- |
| 100ms  | Very responsive UI (high traffic)       |
| 500ms  | Balanced responsiveness                 |
| 1000ms | Default; good for most scenarios        |
| 2000ms | Lower traffic, slightly delayed updates |
| 5000ms | Batch processing; minimal traffic       |

**Performance Impact:**

- **Lower values (100-500ms):**
  - ✅ Dashboard updates more frequently
  - ✅ Users see changes immediately
  - ❌ Higher network traffic
  - ❌ Higher server load during bulk operations

- **Higher values (2000-5000ms):**
  - ✅ Reduced network traffic
  - ✅ Lower server load
  - ✅ Better for slow connections
  - ❌ Dashboard updates less frequently
  - ❌ Potential lag during bulk operations

**Recommendation:**

- **Development:** `500ms` (immediate feedback)
- **Production:** `1000ms` (balanced, default)
- **High-volume environments:** `2000-5000ms` (reduce load)

**Important Notes:**

- ⚠️ This only affects SSE batching, not database queries
- ⚠️ Very small values may increase server CPU usage
- ⚠️ Very large values make the dashboard feel sluggish during bulk operations

---

### Exensio Integration Configuration

#### Property: `exensio.enabled`

**Location:** `ExensioProperties` / `application.yml`

**Description:** Enables or disables the Exensio verification pipeline. When enabled, records transition through the `EXENSIO_LOADING` state. When disabled, records skip directly from `ENRICHMENT` to `DONE`.

**Default Value:** `false` (disabled)

**YAML Configuration:**

```yaml
exensio:
  enabled: true
  # ... other Exensio config ...
```

**Environment Variable Override:**

```bash
export EXENSIO_ENABLED=true
```

**Effect on Dashboard:**

- **When enabled:** EXENSIO_LOADING card is visible and tracks records in Exensio verification
- **When disabled:** EXENSIO_LOADING card shows 0 or is hidden

**Related Properties:**

```yaml
exensio:
  enabled: true
  url: ${EXENSIO_SERVICE_URL:}
  api-key: ${EXENSIO_API_KEY:}
  timeout-seconds: 30
```

---

### Query Performance and Indexes

#### Property: `database.indexes`

**Description:** The system expects certain database indexes to be present for optimal performance of aggregation queries:

**Required Indexes:**

```sql
-- Index for fast state aggregation queries
CREATE INDEX idx_sender_stage_status_request
  ON SENDER_STAGE(status, request_id);

-- Index for fast timeout detection
CREATE INDEX idx_sender_stage_status_updated
  ON SENDER_STAGE(status, updated_at);

-- Index for filtering by site and sender
CREATE INDEX idx_sender_stage_site_sender
  ON SENDER_STAGE(site, sender_id);
```

**Check Current Indexes:**

```sql
-- SQL Server
SELECT name FROM sys.indexes
WHERE object_id = OBJECT_ID('SENDER_STAGE');

-- Oracle
SELECT index_name FROM user_indexes
WHERE table_name = 'SENDER_STAGE';
```

**Performance Considerations:**

- Missing indexes → slower aggregation queries → stale dashboard data
- Large SENDER_STAGE table (> 1M records) → indexes become critical
- See `backend/src/main/resources/db/changelog/db.changelog-9.7-performance-indexes.xml` for index creation scripts

---

## Complete Configuration Example

Here's a complete configuration block for `application.yml`:

```yaml
# Monitor and Enrichment Configuration
cp:
  elasticsearch:
    url: ${CP_ES_URL:http://localhost:9200}
    api-key: ${CP_ES_API_KEY:}
    username: ${CP_ES_USERNAME:}
    password: ${CP_ES_PASSWORD:}
    enrichment-timeout-minutes: 5 # Stuck record timeout

exensio:
  enabled: ${EXENSIO_ENABLED:false}
  url: ${EXENSIO_SERVICE_URL:}
  api-key: ${EXENSIO_API_KEY:}
  timeout-seconds: 30

# Data Integrity and Monitoring
app:
  data-integrity-check:
    enabled: true
    # Every hour (recommended)
    cron: '0 0 * * * *'

  stage-monitor:
    # Batch SSE updates every 1 second
    update-batch-window-ms: 1000

# Refdb Configuration
refdb:
  staging-table: SENDER_STAGE
  pool:
    max-size: 5
    min-idle: 1
```

---

## Environment-Specific Configurations

### Development Environment

```yaml
# application-dev.yml
cp:
  elasticsearch:
    enrichment-timeout-minutes: 1 # Strict for testing

app:
  data-integrity-check:
    cron: '0 */5 * * * *' # Every 5 minutes
  stage-monitor:
    update-batch-window-ms: 500 # Responsive UI
```

### Production Environment

```yaml
# application-prod.yml
cp:
  elasticsearch:
    enrichment-timeout-minutes: 5 # Default

app:
  data-integrity-check:
    cron: '0 0 * * * *' # Every hour
  stage-monitor:
    update-batch-window-ms: 1000 # Balanced
```

### High-Volume Environment

```yaml
# application-highvolume.yml
cp:
  elasticsearch:
    enrichment-timeout-minutes: 10 # Relaxed

app:
  data-integrity-check:
    cron: '0 */30 * * * *' # Every 30 minutes
  stage-monitor:
    update-batch-window-ms: 2000 # Reduced traffic
```

---

## Applying Configuration Changes

### Restart Required

Configuration changes require a backend service restart to take effect:

```bash
# Stop the service
systemctl stop exensioreload

# Update application.yml
vim backend/src/main/resources/application.yml

# Rebuild (if config is in source)
cd backend
mvn clean package

# Start the service
systemctl start exensioreload

# Verify
tail -f logs/exensioreload.log | grep "Started ExensioreloadApplication"
```

### Via Environment Variables (No Restart)

Some deployments allow environment variable injection without code changes:

```bash
# Set environment variable
export CP_ENRICHMENT_TIMEOUT_MINUTES=10
export APP_DATA_INTEGRITY_CHECK_CRON="0 */30 * * * *"

# Restart service (will pick up new env vars)
systemctl restart exensioreload
```

### Validation After Change

Check logs for confirmation:

```bash
tail -100 logs/exensioreload.log | grep -E "(enrichmentTimeout|datainteg|updateBatch)"
```

Expected output:

```
[INFO] CpElasticsearchProperties: enrichmentTimeoutMinutes=5
[INFO] DataIntegrityJob: Scheduled with cron expression: 0 0 * * * *
[INFO] StateAggregationBatcher: Update batch window: 1000ms
```

---

## Monitoring Configuration Health

### Check Current Configuration

Create a debug endpoint to view active configuration:

```bash
curl -H "Authorization: Bearer $ADMIN_TOKEN" \
  "http://localhost:8080/exensioreload/api/admin/debug/configuration"
```

### Verify Job Execution

Check that data integrity job is running:

```bash
grep "DataIntegrityJob" logs/exensioreload.log | tail -5
```

### Monitor Batching Effectiveness

Check SSE message volume:

```bash
# Monitor network traffic
tcpdump -i eth0 'tcp port 8080' -A | grep "STATE_AGGREGATION" | wc -l
```

Lower numbers indicate better batching effectiveness.

---

## Troubleshooting Configuration Issues

### Issue: Dashboard Updates Are Slow

**Possible Causes:**

- `updateBatchWindowMs` is too high
- SSE connection is unstable
- Database query performance degraded

**Solutions:**

1. Reduce `updateBatchWindowMs` to 500ms
2. Check network connectivity
3. Verify database indexes are present (see [Query Performance](#query-performance-and-indexes))

### Issue: Data Integrity Job Not Running

**Possible Causes:**

- Job is disabled
- Cron expression is invalid
- Scheduled job thread pool is exhausted

**Solutions:**

1. Verify `app.data-integrity-check.enabled = true`
2. Test cron expression with online tools
3. Check logs for scheduled task errors

### Issue: Too Many Stuck Records Detected

**Possible Causes:**

- `enrichmentTimeoutMinutes` is too low
- CP system is slow
- Network latency increased

**Solutions:**

1. Increase `enrichmentTimeoutMinutes` to 10 or 15
2. Check CP system performance
3. Monitor network latency

---

## Related Documentation

- [MONITOR_DASHBOARD_USER_GUIDE.md](./MONITOR_DASHBOARD_USER_GUIDE.md) — User guide
- [MONITOR_ADMIN_DEBUG_API.md](./MONITOR_ADMIN_DEBUG_API.md) — Debug API reference
- [MONITOR_DASHBOARD_TROUBLESHOOTING.md](./MONITOR_DASHBOARD_TROUBLESHOOTING.md) — Troubleshooting guide

</content>
</invoke>
