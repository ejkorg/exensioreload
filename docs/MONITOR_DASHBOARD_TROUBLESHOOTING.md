# Monitor Dashboard Troubleshooting Guide

## Overview

This guide provides step-by-step troubleshooting procedures for common issues encountered on the Monitor Dashboard. If you're experiencing accounting imbalances, missing records, or unexpected state transitions, start here.

---

## Quick Diagnosis Checklist

Before diving into detailed troubleshooting, verify the basics:

- [ ] Dashboard page loads without errors
- [ ] SSE connection is active (green indicator at top)
- [ ] Total Files count matches sum of all cards
- [ ] No red alert badges indicating stuck records
- [ ] No error messages in browser console (F12 → Console)
- [ ] Backend service is running and responding

---

## Issue 1: Accounting Imbalance (Cards Don't Sum to Total)

### Symptom

```
Total Files: 4544
Staged + Queued + Enriching + Exensio + Completed + Failed + Cancelled = 4200

Imbalance: 4544 - 4200 = 344 missing records
```

### Root Causes

1. **Invalid status values** — Records in states not recognized by dashboard
2. **NULL status** — Some records have blank/null status field
3. **Database corruption** — Inconsistent state in database
4. **Query filter mismatch** — Filter applied differently on dashboard vs. backend
5. **Cache stale** — Dashboard showing cached data

### Diagnostic Steps

#### Step 1: Verify with Admin Debug Endpoint

```bash
# Use admin credentials
curl -H "Authorization: Bearer $ADMIN_TOKEN" \
  "http://localhost:8080/exensioreload/api/admin/debug/state-accounting" \
  | jq '.data_integrity'
```

**Expected output (healthy):**

```json
{
  "is_valid": true,
  "warnings": [],
  "errors": []
}
```

**If imbalance found:**

```json
{
  "is_valid": false,
  "warnings": ["3 records with NULL status detected"],
  "errors": ["4 records with invalid state 'STUCK_UNKNOWN'"]
}
```

#### Step 2: Check Discrepancies

```bash
curl -H "Authorization: Bearer $ADMIN_TOKEN" \
  "http://localhost:8080/exensioreload/api/admin/debug/state-accounting" \
  | jq '.discrepancies'
```

Review each discrepancy for type and count:

| Type                   | Action                                    |
| ---------------------- | ----------------------------------------- |
| `ACCOUNTING_IMBALANCE` | Database totals don't match sum of states |
| `INVALID_STATE`        | Records in unrecognized state             |
| `NULL_STATUS`          | Records with NULL status field            |
| `STUCK_ENRICHMENT`     | Records exceeding timeout                 |

#### Step 3: Query Database Directly

Find records with invalid or NULL status:

```sql
-- SQL Server
SELECT TOP 10 id, status, created_at, request_id
FROM SENDER_STAGE
WHERE status NOT IN ('pending', 'ENQUEUED', 'ENRICHMENT', 'EXENSIO_LOADING', 'PROCESSING', 'FAILED', 'DONE', 'CANCELLED')
   OR status IS NULL
ORDER BY created_at DESC;
```

**Sample output:**

```
id     | status       | created_at          | request_id
-------|--------------|---------------------|----------
67890  | NULL         | 2026-07-03 10:00:00 | req-001
67891  | STUCK_UNKNOWN| 2026-07-03 10:05:00 | req-001
67892  | NULL         | 2026-07-03 10:10:00 | req-002
```

#### Step 4: Verify Dashboard Filters

Check if any filters are applied that might exclude records:

- Is a specific **Sender** selected?
- Is a specific **Site** selected?
- Is a date range filter active?

**Action:** Clear all filters and reload dashboard. Does accounting balance now?

### Solutions

#### Solution A: Cleanup NULL Status Records

If records have `NULL` status, determine if they should be:

1. **Deleted** — If orphaned or incomplete:

   ```sql
   DELETE FROM SENDER_STAGE WHERE status IS NULL AND created_at < DATEADD(DAY, -7, GETDATE());
   ```

2. **Corrected** — If they belong in a specific state:

   ```sql
   UPDATE SENDER_STAGE SET status = 'pending' WHERE status IS NULL;
   COMMIT;
   ```

3. **Investigated** — If unclear:
   - Check related tables (stage payloads, session details) for context
   - Contact support with sample record IDs

#### Solution B: Fix Invalid Status Values

If records have invalid status (not in standard set), investigate and correct:

```sql
-- Example: Fix records with typo 'ENRICHMENT' → 'ENRICHMENT'
UPDATE SENDER_STAGE SET status = 'ENRICHMENT'
WHERE status = 'ENRICHMENT_PENDING';

COMMIT;
```

#### Solution C: Database Index Rebuild

If imbalance persists, rebuild indexes and recompile statistics:

```sql
-- SQL Server
ALTER INDEX ALL ON SENDER_STAGE REBUILD;
UPDATE STATISTICS SENDER_STAGE;
```

#### Solution D: Backend Cache Flush

Restart backend service to clear any cached data:

```bash
systemctl restart exensioreload
```

After restart, refresh dashboard browser and check accounting again.

### Prevention

- ✅ Enable data integrity job (should run hourly by default)
- ✅ Monitor `DataIntegrityJob` logs for warnings
- ✅ Use admin debug endpoint weekly as part of audit
- ✅ Never manually insert records without proper status validation

---

## Issue 2: Stuck Records Badge Shows High Count

### Symptom

```
[🔴 127 Stuck]  ← Alert badge with unusually high count
```

Clicking the badge shows records in ENRICHMENT for many hours or days.

### Root Causes

1. **CP system down/slow** — Records unable to complete enrichment
2. **Timeout threshold too low** — False positives from slow but normal processing
3. **Enrichment errors** — Records failing silently and not transitioning
4. **Auto-remediation not running** — Stuck records not being cleaned up

### Diagnostic Steps

#### Step 1: Check Stuck Records Detail

Click the stuck records badge to view details:

- Note the **duration in enrichment** for top records
- Check if times are realistic (e.g., 5 min vs. 48 hours)
- Look for patterns (all from same sender? same file?)

#### Step 2: Check CP System Status

Verify the CP pipeline is operational:

```bash
# Test CP connectivity
curl -I "http://<cp-host>:9200/_cluster/health"

# Check for errors in CP logs
grep ERROR /var/log/elasticsearch.log | tail -20
```

#### Step 3: Check Backend Logs for Enrichment Errors

```bash
tail -500 logs/exensioreload.log | grep -i "enrichment\|error\|failed"
```

Look for patterns:

```
[ERROR] EnrichmentService: Failed to enrich record 12345: Connection refused
[ERROR] EnrichmentService: Timeout after 30s waiting for CP response
[WARN] EnrichmentService: Enrichment slow for lot S7U180015, took 8 minutes
```

#### Step 4: Check Timeout Configuration

Verify the enrichment timeout setting:

```bash
curl -H "Authorization: Bearer $ADMIN_TOKEN" \
  "http://localhost:8080/exensioreload/api/admin/configuration" \
  | jq '.enrichmentTimeoutMinutes'
```

Compare against actual record durations:

- If timeout is 5 min, but records stay in ENRICHMENT 30+ min → False positives
- If timeout is 30 min, but records stuck 48+ hours → Genuine issue

#### Step 5: Check Auto-Remediation Logs

Verify the data integrity job ran and attempted remediation:

```bash
grep "DataIntegrityJob" logs/exensioreload.log | tail -5
```

Expected output (healthy):

```
[INFO] DataIntegrityJob: Starting data integrity verification...
[INFO] DataIntegrityJob: Detected 127 stuck enrichment records
[INFO] DataIntegrityJob: Auto-remediated 127 stuck records
[INFO] DataIntegrityJob: Data integrity check complete
```

Missing output → Job didn't run (check cron configuration)

### Solutions

#### Solution A: Increase Timeout Threshold (False Positives)

If records are taking longer than expected due to system load:

1. Increase timeout threshold:

   ```yaml
   # application.yml
   cp:
     elasticsearch:
       enrichment-timeout-minutes: 10 # Was 5
   ```

2. Restart backend:

   ```bash
   systemctl restart exensioreload
   ```

3. Monitor for 1-2 hours to confirm false positives reduce

#### Solution B: Investigate CP System (Real Issue)

If CP is genuinely overloaded:

1. **Check CP performance:**

   ```bash
   # View CPU, memory, queue depth
   curl "http://<cp-host>:9200/_cluster/stats"
   ```

2. **Scale CP resources:**
   - Increase CP server instances
   - Allocate more memory/CPU to CP
   - Check for network bottlenecks

3. **Verify Exensio status** (if enabled):
   ```bash
   curl "http://<exensio-host>:8080/health"
   ```

#### Solution C: Manual Remediation

If auto-remediation isn't running, manually mark stuck records as done:

```bash
# Get list of stuck records
curl -H "Authorization: Bearer $ADMIN_TOKEN" \
  "http://localhost:8080/exensioreload/api/admin/debug/state-accounting" \
  | jq '.discrepancies[] | select(.type == "STUCK_ENRICHMENT") | .sample_records[].id'

# Mark individual records as done
curl -X POST \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  "http://localhost:8080/exensioreload/api/stage/mark-done" \
  -d '{"recordId": 12345, "reason": "manual-remediation"}'
```

#### Solution D: Run Data Integrity Job Manually

Force the data integrity job to run immediately:

```bash
curl -X POST \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  "http://localhost:8080/exensioreload/api/admin/debug/run-integrity-check-now"
```

Check logs to confirm execution:

```bash
tail -50 logs/exensioreload.log | grep DataIntegrityJob
```

### Prevention

- ✅ Monitor stuck records badge daily
- ✅ Set appropriate timeout based on typical enrichment time
- ✅ Monitor CP system performance proactively
- ✅ Ensure data integrity job is scheduled and running

---

## Issue 3: Dashboard Doesn't Update in Real-Time

### Symptom

```
• Cards show stale counts (don't update after state changes)
• SSE connection is red (disconnected)
• Manual refresh (F5) is required to see updates
```

### Root Causes

1. **SSE connection dropped** — Browser lost connection to backend
2. **Network issue** — Firewall blocking SSE, proxy timeout
3. **Browser cache** — Old data cached by browser
4. **Backend not broadcasting events** — Server-side issue

### Diagnostic Steps

#### Step 1: Check SSE Connection Status

Open browser DevTools (F12) → Network tab:

1. Look for an event-stream connection (usually named `dashboard-subscribe` or similar)
2. Check the connection status:
   - ✅ Status 200 → Connected
   - ❌ Status 0 → Failed/Dropped
   - ❌ Status 403 → Unauthorized
   - ❌ Status 504 → Backend unreachable

#### Step 2: Check Browser Console for Errors

Open DevTools → Console tab:

**Look for error messages:**

```javascript
// Healthy output:
[SSE] Connected to dashboard stream
[SSE] Received event: STATE_AGGREGATION (5 changed)

// Unhealthy output:
[SSE] Failed to connect: 503 Service Unavailable
[SSE] Connection closed unexpectedly
[SSE] Authorization failed: 401
```

#### Step 3: Verify Backend is Running

Check backend health endpoint:

```bash
curl -s "http://localhost:8080/exensioreload/api/test/hello" | jq .
```

**Expected response:**

```json
{
  "status": "OK",
  "message": "Backend is healthy"
}
```

#### Step 4: Check Network Connectivity

Test backend from your machine:

```bash
# Test connection
nc -zv <backend-host> 8080

# Expected: Connection to <backend-host> port 8080 [tcp/*] succeeded!
```

#### Step 5: Check Backend Logs for Broadcast Errors

```bash
grep "STATE_AGGREGATION\|SSE\|broadcast" logs/exensioreload.log | tail -20
```

**Healthy output:**

```
[DEBUG] StageMonitorService: Broadcasting STATE_AGGREGATION to 5 subscribers
[DEBUG] StateAggregationBatcher: Batched 23 changes, sending aggregation event
```

**Unhealthy output:**

```
[ERROR] StageMonitorService: Failed to broadcast: No active subscribers
[ERROR] StageMonitorService: IOException during broadcast
```

### Solutions

#### Solution A: Reconnect SSE Stream

1. **Manual Refresh:**

   ```
   Press F5 or Ctrl+Shift+R (hard refresh)
   ```

2. **Check SSE Connection in DevTools:**
   - Look for event-stream in Network tab
   - If not present, check browser console for error
   - If error says 401/403 → Verify auth token is current

#### Solution B: Check Network / Proxy

If SSE fails to connect:

1. **Verify firewall allows SSE:**

   ```bash
   # From client, test port connectivity
   telnet <backend-host> 8080
   ```

2. **Check proxy settings:**
   - If behind nginx/Apache, verify SSE is not buffered
   - Example nginx config issue: `proxy_buffering on;` blocks SSE
   - Fix: Add `proxy_buffering off;` to nginx.conf

3. **Increase connection timeout:**
   - SSE connections are long-lived (don't close)
   - If proxy/firewall closes after inactivity, configure longer timeout
   - Nginx: `proxy_read_timeout 3600s;`

#### Solution C: Clear Cache and Force Reconnect

```bash
# From browser console
localStorage.clear();
sessionStorage.clear();
location.reload(true);
```

#### Solution D: Restart Backend Service

If backend is not broadcasting events:

```bash
systemctl restart exensioreload

# Verify restart
sleep 5
curl "http://localhost:8080/exensioreload/api/test/hello"
```

### Prevention

- ✅ Monitor SSE connection indicator regularly
- ✅ Check backend logs daily for broadcast errors
- ✅ Configure network/proxy for SSE support
- ✅ Ensure backend service is monitored and auto-restarted

---

## Issue 4: Individual Card Shows Incorrect Count

### Symptom

```
Dashboard shows:
  Completed: 1000
Expected: 1200

Discrepancy: 200 records missing from Completed card
```

### Root Causes

1. **State transition bug** — Records not transitioning to expected state
2. **Query filter issue** — Completed query using wrong status value
3. **Cache stale** — Dashboard showing cached data
4. **Database replication lag** — Read replica behind primary

### Diagnostic Steps

#### Step 1: Check Database Query

Query the database directly for the state in question:

```sql
-- Example: Check DONE count
SELECT COUNT(*) FROM SENDER_STAGE WHERE status = 'DONE';

-- Compare to dashboard card (should match)
```

If database count matches dashboard → Database is correct, issue is elsewhere.

If database count differs from dashboard → Query issue or stale cache.

#### Step 2: Check Admin Debug Endpoint

```bash
curl -H "Authorization: Bearer $ADMIN_TOKEN" \
  "http://localhost:8080/exensioreload/api/admin/debug/state-accounting" \
  | jq '.dashboard_cards.completed'
```

Compare the endpoint result to the dashboard:

- **Match** → Dashboard is correct
- **Differ** → Backend query issue or caching

#### Step 3: Check for Query Filters

Verify no unintended filters are applied:

1. Check if Sender/Site filter is active
2. Check if date range filter is active
3. Clear all filters and reload

#### Step 4: Check Backend Logs for Query Errors

```bash
grep "RefDbService\|fetchStatuses\|ERROR" logs/exensioreload.log | tail -30
```

Look for SQL errors or query timeouts.

### Solutions

#### Solution A: Clear Dashboard Cache

```bash
# From browser console
localStorage.removeItem('dashboardCache');
location.reload();
```

#### Solution B: Verify Database Replication (if applicable)

If using read replicas:

```sql
-- Check replication lag
SELECT name, master_replication_offset, slave_replication_offset
FROM replication_status;

-- If lag exists, wait for sync
WAITFOR DELAY '00:00:05';
SELECT COUNT(*) FROM SENDER_STAGE WHERE status = 'DONE';
```

#### Solution C: Restart Backend to Clear Query Cache

```bash
systemctl restart exensioreload
```

#### Solution D: Investigate State Transition Logic

Check backend code for the specific status transition:

```bash
grep -r "status = 'DONE'" src/main/java --include="*.java"
```

Review code to ensure transitions are correct and comprehensive.

### Prevention

- ✅ Test state transitions with integration tests
- ✅ Monitor individual card counts daily
- ✅ Use admin debug endpoint weekly to verify accuracy

---

## Issue 5: Exensio Loading Card Not Appearing

### Symptom

```
Dashboard shows: Staged, Queued, Enriching, Completed, Failed, Cancelled
Missing: Exensio Loading card
```

### Root Causes

1. **Exensio not enabled** — Feature flag is off
2. **No records in EXENSIO_LOADING state** — Card hidden if count is 0
3. **Frontend bug** — Card component not rendering

### Diagnostic Steps

#### Step 1: Check Exensio Configuration

```bash
curl -H "Authorization: Bearer $ADMIN_TOKEN" \
  "http://localhost:8080/exensioreload/api/admin/configuration" \
  | jq '.exensio.enabled'
```

**Expected output (if Exensio is enabled):**

```json
true
```

#### Step 2: Check for EXENSIO_LOADING Records

```sql
SELECT COUNT(*) FROM SENDER_STAGE WHERE status = 'EXENSIO_LOADING';
```

If count is 0 and Exensio is enabled:

- Records may not be transitioning to EXENSIO_LOADING
- Or they're transitioning through very quickly

#### Step 3: Check Frontend Console

Open DevTools → Console and look for rendering errors:

```
[ERROR] DashboardComponent: Failed to render Exensio Loading card: TypeError...
```

### Solutions

#### Solution A: Enable Exensio

If Exensio is disabled, enable it:

```yaml
# application.yml
exensio:
  enabled: true
  url: ${EXENSIO_SERVICE_URL}
  api-key: ${EXENSIO_API_KEY}
```

Restart backend:

```bash
systemctl restart exensioreload
```

#### Solution B: Generate Test Record in EXENSIO_LOADING

To verify card displays when data is present:

```sql
-- Create test record
INSERT INTO SENDER_STAGE (lot, status, site, sender_id, request_id, created_at)
VALUES ('TEST_LOT_001', 'EXENSIO_LOADING', 'SITE_A', 1, 'req-test', GETDATE());
COMMIT;
```

Reload dashboard. Exensio Loading card should appear with count 1.

#### Solution C: Check Frontend Rendering

Inspect browser element:

1. Right-click on dashboard → Inspect
2. Search for "Exensio" in HTML
3. If not present, check frontend component code for render conditions

---

## Issue 6: "Stuck Records" Exist, But Were Recently Fixed

### Symptom

```
[🔴 47 Stuck]  ← Alert badge remains, even after auto-remediation

After manual verification, records actually show DONE status in database
```

### Root Causes

1. **Cache not refreshed** — Dashboard showing old data
2. **Auto-remediation recent** — Badge not updated yet
3. **Timestamp not synced** — Servers have clock skew

### Diagnostic Steps

#### Step 1: Verify Records Are Actually Fixed

```sql
-- Check status of records that were stuck
SELECT id, status, updated_at FROM SENDER_STAGE
WHERE status = 'DONE'
  AND updated_at > DATEADD(HOUR, -1, GETDATE())
ORDER BY updated_at DESC;
```

If records show DONE status → Database is correct, issue is cache/stale count.

#### Step 2: Check Last Integrity Job Run

```bash
grep "DataIntegrityJob.*complete" logs/exensioreload.log | tail -1
```

Output should show recent timestamp (within last hour).

### Solutions

#### Solution A: Refresh Dashboard

```
Press Ctrl+Shift+R (hard refresh, bypass cache)
```

#### Solution B: Wait for Next Aggregation Update

SSE batching collects changes for ~1 second, then broadcasts. If records were just fixed:

- Wait 1-2 seconds
- Dashboard card should update automatically

If stuck records badge remains:

- Manually refresh (F5)

#### Solution C: Verify Clock Sync

Check if backend and database server clocks are in sync:

```bash
# Backend time
curl -s "http://localhost:8080/exensioreload/api/test/time" | jq '.timestamp'

# Should be within 1-2 seconds of system time
date
```

If clocks are skewed, fix with NTP:

```bash
sudo ntpdate -s time.nist.gov
```

---

## General Troubleshooting Procedures

### Check System Health

**Quick health check (all green = good):**

```bash
#!/bin/bash
echo "=== Backend Health ==="
curl -s "http://localhost:8080/exensioreload/api/test/hello" | jq '.status'

echo "=== Database Connectivity ==="
sqlcmd -S <db-server> -U <user> -P <pass> -Q "SELECT 1" && echo "OK" || echo "FAILED"

echo "=== Accounting Balance ==="
curl -H "Authorization: Bearer $ADMIN_TOKEN" \
  "http://localhost:8080/exensioreload/api/admin/debug/state-accounting" \
  | jq '.data_integrity.is_valid'

echo "=== Recent Errors in Logs ==="
tail -50 logs/exensioreload.log | grep -i error | head -5
```

### Collect Diagnostic Info

Before contacting support, gather this information:

```bash
#!/bin/bash

echo "=== System Info ===" > diagnostic.txt
date >> diagnostic.txt
uname -a >> diagnostic.txt

echo "" >> diagnostic.txt
echo "=== Backend Version ===" >> diagnostic.txt
curl -s "http://localhost:8080/exensioreload/api/test/hello" | jq . >> diagnostic.txt

echo "" >> diagnostic.txt
echo "=== Recent Errors ===" >> diagnostic.txt
tail -100 logs/exensioreload.log | grep -i error >> diagnostic.txt

echo "" >> diagnostic.txt
echo "=== State Accounting ===" >> diagnostic.txt
curl -H "Authorization: Bearer $ADMIN_TOKEN" \
  "http://localhost:8080/exensioreload/api/admin/debug/state-accounting" \
  | jq . >> diagnostic.txt

# Share diagnostic.txt with support
```

---

## When to Contact Support

Escalate to support if:

- ❌ Accounting imbalance persists after trying all solutions
- ❌ Stuck records badge remains very high (> 1000) despite remediation
- ❌ Backend crashes or is unresponsive
- ❌ Database queries are timing out
- ❌ You see repeated error patterns in logs
- ❌ You're unsure which configuration to change

**Include with support request:**

- diagnostic.txt output (see above)
- Screenshots of dashboard issue
- Backend logs (last 100-200 lines)
- Output of admin debug endpoint

---

## Related Documentation

- [MONITOR_DASHBOARD_USER_GUIDE.md](./MONITOR_DASHBOARD_USER_GUIDE.md) — Dashboard user guide
- [MONITOR_ADMIN_DEBUG_API.md](./MONITOR_ADMIN_DEBUG_API.md) — Debug API reference
- [MONITOR_CONFIGURATION_GUIDE.md](./MONITOR_CONFIGURATION_GUIDE.md) — Configuration guide

</content>
</invoke>
