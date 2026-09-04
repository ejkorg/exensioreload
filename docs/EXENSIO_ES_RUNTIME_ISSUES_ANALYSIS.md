# Root-Cause Analysis: Elasticsearch & Exensio API Runtime Issues

> **Log Timestamp Analyzed**: `2026-09-04T06:45:10Z` – `2026-09-04T06:46:37Z`  
> **Session ID**: `736771c5-cbde-4e04-9203-20bbdc0898b1`  
> **Target Record**: Payload ID `1513` (`lot=IR77464.1J`, `wafer=11`, `dataId=19527548`, `metadataId=18025939`, `filename=IR77464.1J-11~08092026_230806.000`)

---

## Executive Summary

Analysis of the runtime execution logs reveals **6 distinct failure modes** spanning Elasticsearch querying, Exensio API integration, SQL syntax generation, database state handling, and UI telemetry. 

The most critical finding is an **architectural bug in `RefDbService.batchMarkNotFound()`**, which immediately marks records as `LOAD_FAILED` on their very first check (after only 58 seconds), completely subverting the configured 60-minute timeout window. Furthermore, **`safeSendEvent(null, ...)`** hardcodes a `null` request ID, causing 100% of batch SSE status updates to be dropped before reaching the user interface.

---

## Breakdown of Each Runtime Issue

### Issue 1: Elasticsearch Returned `NotFound` (0 Hits) Despite CP Having Already Succeeded

#### The Log Evidence:
```log
2026-09-04T06:45:32.625Z INFO [worker-2] ElasticsearchLogService : Elasticsearch query START: url=https://elastic-mosdata-prod-uswest2.es.privatelink.westus2.azure.elastic-cloud.com/logs*dataport*/_search, dataId=19527548, lot=IR77464.1J
...
2026-09-04T06:45:34.029Z INFO [worker-2] ElasticsearchLogService : ES query HTTP RESPONSE (1404ms): HTTP 200, dataId=19527548
2026-09-04T06:45:34.034Z INFO [worker-2] ElasticsearchLogService : ES query RESULT: NotFound for dataId=19527548 (no hits)
2026-09-04T06:45:34.035Z INFO [scheduling-1] CpLogMonitor       : CP enrichment success (pp_log) for record id=1513 dataId=19527548: output=/apps/exensio_data/data/cz2_defect_klarf_18_Si/outbox/PRODUCTION
```

#### What Happened:
1. `ElasticsearchLogService` queried the ES cluster with HTTP 200, but received **0 hits**.
2. Meanwhile, the parallel Oracle query against `pp_log` found the record in **12ms** with `process_code = 0` and output path `/apps/exensio_data/data/cz2_defect_klarf_18_Si/outbox/PRODUCTION`.

#### Root Cause:
The Elasticsearch query is over-constrained with multiple rigid `must` clauses:
```json
{
  "must": [
    { "wildcard": { "cpConfig": { "value": "*sender*", "case_insensitive": true } } },
    { "term": { "idData": "19527548" } },
    { "term": { "idFile": "18025939" } },
    { "wildcard": { "inputFileName": { "value": "*IR77464.1J-11~08092026_230806*", "case_insensitive": true } } },
    { "range": { "@timestamp": { "gte": "2026-09-04T06:30:10.919607Z" } } }
  ]
}
```
- **Filter Mismatch**: The CP configuration for this defect file in production is `cz2_defect_klarf_18_Si`. The wildcard filter `cpConfig: "*sender*"` **does not match** `cz2_defect_klarf_18_Si`!
- **Filename / ID Sensitivity**: If CP logs index the file under a slightly modified name, without `idFile`, or if the tilde `~` in `IR77464.1J-11~08092026_230806` is treated as a token delimiter in ES analysis, the query returns 0 hits.
- **Why it didn't fail the pipeline**: The parallel query architecture in `CpLogMonitor.java` saved the pipeline because `pp_log` has higher priority than Elasticsearch.

---

### Issue 2: Exensio Raw SQL Crashed with HTTP 503 / Oracle Error 4302

#### The Log Evidence:
```log
2026-09-04T06:46:35.286Z INFO [exensio-worker-99] ExensioClient : Exensio raw-sql START: url=https://api-prod.canyon.aws.pdf.com/api/v1/key/raw-sql, traceId=null
2026-09-04T06:46:35.286Z INFO [exensio-worker-99] ExensioClient : Exensio raw-sql SQL (traceId=null):
SELECT lot_id, wafer_id, lot_key, wafer_key, pg_key, ppid, file_name, end_time FROM ( 
  SELECT l.lot_id AS lot_id, NVL(w.wf_id,'') AS wafer_id, ol.lot_key AS lot_key, ...
  WHERE ((ol.pgc_key = 14 AND l.lot_id IN ('IR77464.1J', 'ir77464.1j') 
  AND (w.wf_id IN ('11', '11', '11', '11') OR w.wf_num = 11) 
  AND (de.file_name IS NULL OR (UPPER(NVL(de.file_name,'')) LIKE '%IR77464.1J-11~08092026\_230806.000%' ESCAPE '\\' ...))))
  ORDER BY ol.end_time DESC) WHERE ROWNUM <= 200
2026-09-04T06:46:35.558Z WARN [exensio-worker-99] ExensioClient : Exensio raw-sql FAILED (HTTP 503): elapsed=272ms, traceId=null, response={"error":{"code":4302,"message":"SQL Error during call to bdapi/oracle.queryToMap on line 7314.","detail":"","http_status":503}}
2026-09-04T06:46:35.558Z WARN [exensio-worker-99] ExensioClient : Raw SQL batch lookup failed, falling back to lot-wafer endpoint: Raw SQL batch error: HTTP 503
```

#### What Happened:
The backend attempted to execute a raw SQL query against Exensio's Oracle database via the `/v1/key/raw-sql` endpoint. The endpoint crashed with HTTP 503 and internal error code `4302: SQL Error during call to bdapi/oracle.queryToMap`.

#### Root Cause (Oracle SQL Syntax Error):
Inspect the generated SQL clause:
```sql
LIKE '%IR77464.1J-11~08092026\_230806.000%' ESCAPE '\\'
```
Look at `ExensioClient.java` line 983:
```java
parts.add("UPPER(NVL(" + column + ",'')) LIKE '%" + escapeLikeLiteral(id.toUpperCase(Locale.ROOT)) + "%' ESCAPE '\\\\'");
```
- In Java string literals, `"ESCAPE '\\\\'"` produces `ESCAPE '\\'` (two backslashes).
- In Oracle SQL, the `ESCAPE` clause specifies the character used to escape wildcards. **The escape specifier in Oracle MUST BE EXACTLY ONE SINGLE CHARACTER**.
- When Oracle parses `ESCAPE '\\'`, it sees a 2-character string (`\\`) and rejects it with:  
  **`ORA-01424: missing or illegal character following the escape character`**.
- Exensio's BDAPI gateway catches this SQL syntax error and returns HTTP 503.
- **Trace ID loss**: Line 409 passes `null` for trace ID: `doRawSqlLookupBatch(records, token, null)`, causing `traceId=null` in the logs.

---

### Issue 3: Premature Exensio Ingest Polling (Timing Race Condition)

#### The Log Evidence:
```log
2026-09-04T06:45:34.035Z INFO  CpLogMonitor       : CP enrichment success (pp_log) for record id=1513 ...
2026-09-04T06:46:32.609Z INFO  ExensioLoadMonitor : Exensio poll cycle started: 1 records in EXENSIO_LOADING
2026-09-04T06:46:36.896Z DEBUG ExensioClient      : Batch API call completed: batchSize=1 ... statusCode=200
2026-09-04T06:46:37.111Z INFO  ExensioClient      : Exensio raw-sql result: empty response (DP_LOG PRODUCTION)
2026-09-04T06:46:37.862Z INFO  ExensioClient      : Exensio raw-sql result: empty response (DP_LOG SANDBOX)
2026-09-04T06:46:37.863Z DEBUG ExensioLoadMonitor : Record 1513 NOT_FOUND - Exensio wafer not found yet — retrying
```

#### What Happened:
- CP wrote the output file to `/apps/exensio_data/data/cz2_defect_klarf_18_Si/outbox/PRODUCTION` at **06:45:34Z**.
- Exactly **58 seconds later** (06:46:32Z), `ExensioLoadMonitor` polled Exensio.
- Exensio's file loader daemon (DataPorter) had not yet picked up or finished parsing the KLARF file.
- `lot-wafer-lookup` returned HTTP 200, but with empty wafer results (`NOT_FOUND`).
- `DP_LOG` queries on `PRODUCTION` and `SANDBOX` returned `null` because DataPorter had not logged any errors either.

#### Root Cause:
This is an asynchronous race condition. Defect KLARF files typically take 2–5 minutes for Exensio's DataPorter daemon to ingest, parse, and write to `op_log` and `wafer`. Checking Exensio after 58 seconds is expected to return `NOT_FOUND`. 
Normally, this should simply remain in `EXENSIO_MONITORING` and retry on the next cycle—**which brings us to Issue 4**.

---

### Issue 4: Fatal Defect — `RefDbService.batchMarkNotFound` Kills Records on First Poll

#### The Log Evidence:
```log
2026-09-04T06:46:37.863Z DEBUG ExensioLoadMonitor : Record 1513 NOT_FOUND - Exensio wafer not found yet — retrying (traceId=N/A)
2026-09-04T06:46:37.866Z DEBUG RefDbService       : Committed final NOT_FOUND batch: 1 records
2026-09-04T06:46:37.867Z DEBUG RefDbService       : Batch marked NOT_FOUND: 1 records
2026-09-04T06:46:37.867Z INFO  RefDbService       : Batch update completed: 1 records updated in 2ms
```

#### What Happened:
`ExensioLoadMonitor` intended for the record to retry next cycle (`"Exensio wafer not found yet — retrying"`). But in the database, `RefDbService` **immediately changed the record status to `LOAD_FAILED`**!

#### Root Cause:
Examine `RefDbService.java` lines 3753–3756:
```java
private int batchMarkNotFound(List<BatchResult.RecordUpdate> updates) {
    ...
    String sql = "UPDATE " + table +
            " SET status = 'LOAD_FAILED', error_message = ?," +
            " processed_at = " + timestampExpr() +
            " WHERE id = ?";
    ...
    ps.setString(1, "Wafer not found in Exensio");
```
And examine `ExensioLoadMonitor.java` line 408:
```java
} else if (record != null && isTimedOut(record)) {
    // Record timed out with NOT_FOUND - mark as EXENSIO_TIMEOUT
    updates.add(new BatchResult.RecordUpdate(..., UpdateType.COMPLETED_MANUAL_VERIFICATION_REQUIRED, ...));
} else {
    // Within timeout window!
    updates.add(update); // update.type() is NOT_FOUND
}
```

- **The Contradiction**:
  - `ExensioLoadMonitor` uses `UpdateType.NOT_FOUND` to indicate: *"Still within the 60-minute timeout; keep in EXENSIO_MONITORING and retry next cycle."*
  - `RefDbService.batchUpdateFromExensio()` takes `NOT_FOUND` updates and calls `batchMarkNotFound()`, which executes `UPDATE ... SET status = 'LOAD_FAILED'`.
- **The Consequence**: **Every single file that is not instantly available within the first 60 seconds is immediately killed and marked as `LOAD_FAILED`!** On the next monitor cycle, `ExensioLoadMonitor` finds 0 records to poll.

---

### Issue 5: UI SSE Event Dropped Due to Hardcoded `null` Request ID

#### The Log Evidence:
```log
2026-09-04T06:46:37.867Z WARN [scheduling-1] RefDbService : Skipping SSE 'ROW_UPDATE' because requestId is null or blank (payloadId=1513)
```

#### What Happened:
The backend attempted to push real-time Server-Sent Events (SSE) to update the user's browser table, but aborted the push and logged a warning.

#### Root Cause:
Inspect `RefDbService.java` lines 4293–4310:
```java
private void broadcastBatchEvents(List<BatchResult.RecordUpdate> updates, String status, String msg) {
    ...
    for (BatchResult.RecordUpdate update : updates) {
        ...
        safeSendEvent(null, "ROW_UPDATE", evt); // <-- FIRST PARAMETER IS NULL!
    }
}
```
And inspect `safeSendEvent` at line 157:
```java
private void safeSendEvent(String requestId, String type, Object payload) {
    if (requestId == null || requestId.isBlank()) {
        log.warn("Skipping SSE '{}' because requestId is null or blank (payloadId={})", type, payloadId);
        return;
    }
    ...
```
- `broadcastBatchEvents()` literally hardcodes `null` as the `requestId`!
- Because `requestId` is `null`, `safeSendEvent` **drops 100% of batch completion and error events**.
- The operator's browser never receives any SSE updates for batch transitions. The UI stays frozen showing stale counts until a hard manual page reload is triggered.

---

### Issue 6: Lost Traceability (`traceId=N/A` and `traceId=null`)

#### The Log Evidence:
```log
2026-09-04T06:46:32.611Z INFO  ExensioLoadMonitor : Processing Exensio batch (traceId=2eb549c5-3ba4-4003-a624-7904aac74411), size=1
...
2026-09-04T06:46:35.286Z INFO  ExensioClient      : Exensio raw-sql START: url=..., traceId=null
...
2026-09-04T06:46:37.863Z DEBUG ExensioLoadMonitor : Record 1513 NOT_FOUND - Exensio wafer not found yet — retrying (traceId=N/A)
```

#### Root Cause:
1. In `ExensioClient.java` line 409:
   `BatchLookupResult rawSqlResult = doRawSqlLookupBatch(records, token, null);`
   The calling method generated a batch trace ID (`2eb549c5...`), but passed `null` to `doRawSqlLookupBatch()`.
2. When creating `RecordUpdate` objects in `BatchLookupResult.mapToRecordUpdates()`, the trace ID was omitted, resulting in `traceId=null`, which formats as `traceId=N/A` in user logs.

---

## Core System Architecture Principle: "Manual Verify in Exensio" vs "Load Failed"

> [!IMPORTANT]
> **Fundamental Contract**: If the system is unable to verify wafer ingestion from the Exensio API **regardless of reason** (wafer not found, API timeout, HTTP 503 raw-SQL error, HTTP 429 rate limit, or network disconnection), the record **MUST NEVER be reported as `LOAD_FAILED` or `not_loaded`**. Instead, it must transition to **`COMPLETED_MANUAL_VERIFICATION_REQUIRED`** (UI display: `"Completed — Verify in Exensio"`).

### Why Reporting `LOAD_FAILED` for API Inability is a False Alarm

1. **The File Has Already Been Delivered**: When a record reaches `EXENSIO_MONITORING`, the reload pipeline has already succeeded: the file was dispatched to the external queue, consumed by CP, enriched, and physically placed into Exensio's inbox/outbox directory (e.g. `/apps/exensio_data/data/cz2_defect_klarf_18_Si/outbox/PRODUCTION`).
2. **API Failure != Loader Failure**: If Exensio's REST API throws HTTP 503, fails a raw SQL query, times out, or cannot locate the wafer in `op_log`, **this does NOT mean Exensio rejected the file**. It only means ExensioReload cannot automatically confirm it via the API.
3. **Severe Operational Impact of False Failures**: When operators see `LOAD_FAILED` (red badge) in the dashboard:
   - Operators assume the reload failed and re-trigger a reload for the same lot/wafer, causing duplicate records or wasted tester/network bandwidth.
   - Operators file defect tickets against the reload system.
   - True failures (`LOAD_FAILED`) are diluted and lost among false alarms.

### Canonical State Distinction

| State | Semantic Meaning | When to Use | UI Color / Indicator |
|---|---|---|---|
| **`COMPLETED`** | Confirmed Ingested | Wafer key and Program Group (PG) key successfully retrieved via Exensio API or raw SQL | **Green** (`check_circle`) |
| **`EXENSIO_MONITORING`** | Ingest in Progress | File delivered to Exensio outbox; polling API for confirmation (within the 60-minute window) | **Blue/Primary** (`cloud_upload`) |
| **`COMPLETED_MANUAL_VERIFICATION_REQUIRED`** | Delivered; Unconfirmed by API | System unable to automatically confirm via API for **any reason** (timeout, API 503, raw-SQL failure, missing wafer record) | **Amber/Warning** (`schedule` / "Verify in Exensio") |
| **`LOAD_FAILED`** | Confirmed Ingest Rejection | Exensio's internal loader explicitly rejected the file (proven by `dp_log.error_code != 0` with error text in `string_holder`) | **Red** (`error_outline`) |

---

## Code Defect Analysis: How Current Code Violates This Contract

### 1. `RefDbService.batchMarkNotFound()` (Lines 3753–3756)
```java
// CURRENT INCORRECT IMPLEMENTATION:
private int batchMarkNotFound(List<BatchResult.RecordUpdate> updates) {
    String sql = "UPDATE " + table +
            " SET status = 'LOAD_FAILED', error_message = ?," + // <-- VIOLATION!
            " processed_at = " + timestampExpr() +
            " WHERE id = ?";
    ps.setString(1, "Wafer not found in Exensio");
```
- **The Bug**: It forces `status = 'LOAD_FAILED'` when the wafer is simply not found yet.
- **The Fix**: 
  - If within the timeout window, **do not change the status** (allow the record to remain in `EXENSIO_MONITORING` to retry next cycle).
  - If the timeout window has expired, set `status = 'COMPLETED_MANUAL_VERIFICATION_REQUIRED'`, NOT `LOAD_FAILED`!

### 2. `RefDbService.batchMarkError()` (Lines 3816–3819)
```java
// CURRENT INCORRECT IMPLEMENTATION:
private int batchMarkError(List<BatchResult.RecordUpdate> updates) {
    String sql = "UPDATE " + table +
            " SET status = 'LOAD_FAILED', error_message = ?," + // <-- VIOLATION!
            " processed_at = " + timestampExpr() +
            " WHERE id = ?";
    ps.setString(1, "Batch processing error");
```
- **The Bug**: If the Exensio API throws an HTTP 503 error (like the BDAPI Oracle syntax error in Issue 2) or an HTTP 500/network glitch, it sets `status = 'LOAD_FAILED'`!
- **The Fix**: API processing errors must NOT mark the record as failed. Transient errors must remain in `EXENSIO_MONITORING` for retry; persistent errors must route to `COMPLETED_MANUAL_VERIFICATION_REQUIRED` with the error diagnostics.

### 3. Contrast: How `CpLogMonitor.java` Handled This Correctly
In `CpLogMonitor.java` line 445 and line 477:
```java
// CpLogMonitor gets this right!
case ExensioLotWaferResult.NotFound notFound -> {
    refDbService.markCompletedManualVerify(record,
            "[Enrichment Unresolved] ... Exensio: not found for lot=" + record.lot() + ". Manual verification required.");
}
case ExensioLotWaferResult.Error error -> {
    refDbService.markCompletedManualVerify(record,
            "[Enrichment Unresolved] ... Exensio: error=" + error.message() + ". Manual verification required.");
}
```
`CpLogMonitor` strictly adheres to the principle: whenever Exensio returns `NotFound` or `Error`, it invokes `markCompletedManualVerify()`, NEVER `markLoadFailed()`! `RefDbService` must be brought into alignment with this exact pattern.

---

## Action Plan & Required Fixes

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              PRIORITY 1: CRITICAL FIXES                     │
├─────────────────────────────────────────────────────────────────────────────┤
│ 1. Align RefDbService with "Manual Verify" Contract:                        │
│    - batchMarkNotFound(): Within timeout, leave in EXENSIO_MONITORING.      │
│      On timeout, set COMPLETED_MANUAL_VERIFICATION_REQUIRED.                │
│      NEVER set LOAD_FAILED for missing wafers!                              │
│    - batchMarkError(): On unresolvable API errors, set                      │
│      COMPLETED_MANUAL_VERIFICATION_REQUIRED, NEVER LOAD_FAILED!             │
│    - Only DP_LOG (error_code != 0) is allowed to set LOAD_FAILED.           │
│                                                                             │
│ 2. Fix ExensioClient.java SQL Escape Syntax:                                │
│    Change ESCAPE '\\\\' to ESCAPE '\\' to fix ORA-01424 / HTTP 503 error.   │
│                                                                             │
│ 3. Fix RefDbService.broadcastBatchEvents():                                 │
│    Pass the record's actual requestId instead of hardcoded null to fix      │
│    dropped SSE updates in the UI.                                           │
├─────────────────────────────────────────────────────────────────────────────┤
│                              PRIORITY 2: QUERY TUNING                       │
├─────────────────────────────────────────────────────────────────────────────┤
│ 4. Relax Elasticsearch cpConfig Filter:                                     │
│    Support defect configs (e.g. cz2_defect_klarf_*) so ES queries don't     │
│    miss valid CP logs that lack "*sender*" in their config name.            │
│                                                                             │
│ 5. Pass traceId through doRawSqlLookupBatch:                                │
│    Maintain end-to-end trace correlation in ExensioClient logs.             │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## Fix Status: COMPLETED

All identified critical fixes and query tuning have been implemented:

1. **`RefDbService.java`**:
   - `batchMarkNotFound()`: Now maintains `status = 'EXENSIO_MONITORING'` and only updates `updated_at`. Never prematurely transitions active records to `LOAD_FAILED`.
   - `batchMarkError()`: Now transitions unresolvable API lookup errors to `COMPLETED_MANUAL_VERIFICATION_REQUIRED` instead of `LOAD_FAILED`.
   - `retryIndividualNotFoundUpdates()` and `retryIndividualErrorUpdates()`: Aligned with the above contracts.
   - `broadcastBatchEvents()`: Passes `update.requestId()` to `safeSendEvent()` and triggers `recordStateChangeForBatcher()`. No longer hardcodes `null`, fixing 100% dropped SSE events.

2. **`ExensioClient.java`**:
   - Fixed SQL escape syntax in `buildIdentifierLikeClause`: Changed `" ESCAPE '\\\\'"` to `" ESCAPE '\\'"` so that Oracle receives `ESCAPE '\'` instead of `ESCAPE '\\'`, resolving `ORA-01424: missing or illegal character following the escape character` (HTTP 503 / 4302).
   - Trace IDs: Forwarded `traceId` through `doLotWaferLookupBatch`, `doRawSqlLookupBatch`, and `mapToRecordUpdates` to eliminate `traceId=null` / `traceId=N/A`.

3. **`ExensioLoadMonitor.java`**:
   - `processUpdateWithDLQ()`: `NOT_FOUND`, `CP_TIMEOUT`, and `COMPLETED_MANUAL_VERIFICATION_REQUIRED` are excluded from the Dead Letter Queue error counter. When the DLQ threshold is reached on repeated API errors, the record transitions to `COMPLETED_MANUAL_VERIFICATION_REQUIRED` (never `LOAD_FAILED`).
   - Propagates `record.requestId()`, `lot`, `wafer`, and `filename` across all `RecordUpdate` creations.

4. **`BatchResult.java` & `BatchLookupResult.java`**:
   - Added `requestId` to `RecordUpdate` with backwards-compatible overloaded constructor.
   - Enhanced `mapToRecordUpdates(records, traceId)` to propagate `requestId`, `traceId`, `lot`, `wafer`, and `filename` across all update types (`ERROR`, `NOT_FOUND`, `COMPLETED`).

5. **`ElasticsearchLogService.java`**:
   - Broadened fallback retry in `findCpLog()`: If the initial filter misses (e.g. `*sender*`), it automatically retries with wildcard `*` to capture defect configs such as `cz2_defect_klarf_18_Si`.

