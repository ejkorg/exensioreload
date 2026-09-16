# Fix Exensio Data-Loaded Confirmation Using Python Lib's Verification Pattern

## Problem Statement

The current Spring Boot implementation marks a record as **DONE** when `lot-wafer-lookup` (or raw-sql) returns a `wafer_key` and `pg_key`. But **finding keys ≠ data loaded**. The Python lib proves this — it uses a **multi-step verification** workflow:

```
lot-wafer-lookup → get keys → Programs API → validate program exists → Results API → confirm actual data rows exist
```

Your current flow stops at step 1:

```
lot-wafer-lookup → get keys → DONE ✅ (but data may not actually be there yet!)
```

### Root Cause

The `lot-wafer-lookup` endpoint returns metadata about the **registration** of a lot/wafer in Exensio — not whether the actual parametric test data has been fully loaded. The keys can exist before data finishes loading, which means you're confirming records as "loaded" before the data is actually queryable.

### Evidence from Python Lib

The Python lib (lines 1186–1200) calls `exAPI_Results()` **after** getting keys, and only considers it successful when the response contains actual rows of parametric data:

```python
# Python: Step 5 — Verify actual data exists using Results API
stat_keys = genStatKeys(pg_key, FabWaferKeys)
rp_results = exAPI_Results(token, pgc_key=1, rework_criteria='LATEST', 
                           test_indexes=test_indexes, stat_keys=stat_keys)
if rp_results != None:
    waferData = parse_Results(FabWaferID, rp_results)
    if not waferData.empty:  # ← THIS is the real confirmation
        # Only now is it truly "loaded"
```

---

## Proposed Changes

### Component 1: ExensioClient — Add Results API Verification

#### [MODIFY] [ExensioClient.java](file:///c:/Users/fg8n8x/Desktop/wip/exensioreload/backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/service/ExensioClient.java)

**Add two new methods** modeled directly after the Python lib's `exAPI_Results()` and `exAPI_Programs()`:

**1. `verifyDataLoaded()`** — Calls `POST /v1/result/results` with the `pg_key` + `wafer_key` from a successful `Found` result. Returns `true` only if the response contains actual data rows. This is the **key missing step**.

```java
/**
 * Verifies that actual parametric data rows exist in Exensio for the given
 * pg_key + wafer_key combination.
 *
 * Modeled after Python lib's exAPI_Results() (line 676) and parse_Results() (line 951).
 * Request body:
 *   { "pgc_key": <pgcKey>,
 *     "rework_criteria": "LATEST",
 *     "stat_keys": [{ "pg_key": <pgKey>, "wafer_key": <waferKey> }] }
 *
 * Returns true when the response contains at least one data row.
 */
public boolean verifyDataLoaded(long pgKey, long waferKey, int pgcKey, String token, String traceId)
```

**2. `validateProgram()`** — Calls `POST /v1/key/programs` with the `ppid` to verify the test program exists and has valid indexes. Based on Python lib's `exAPI_Programs()` (line 243) and `parse_Programs()` (line 776).

```java
/**
 * Validates that the parametric test program (PPID) exists and has valid indexes.
 *
 * Modeled after Python lib's exAPI_Programs() (line 243).
 * Request body:
 *   { "pgc_keys": [<pgcKey>], "ppids": ["<ppid>"] }
 *
 * Returns the number of indexes, or -1 on failure.
 */
public int validateProgram(String ppid, int pgcKey, String token, String traceId)
```

**3. Update `lotWaferLookup()` / `doLotWaferLookup()`** to optionally invoke `verifyDataLoaded()` after a `Found` result, before returning. When verification is enabled and the Results API returns empty, downgrade the result from `Found` to `NotFound` so the monitor retries on the next cycle.

---

### Component 2: ExensioLotWaferResult — Add Verified State

#### [MODIFY] [ExensioLotWaferResult.java](file:///c:/Users/fg8n8x/Desktop/wip/exensioreload/backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/service/ExensioLotWaferResult.java)

Add a `dataVerified` flag to the `Found` record so downstream consumers know whether the result has been confirmed with actual data:

```java
record Found(long lotKey, long waferKey, long pgKey, String ppid,
             String lotId, String waferId, String fileName, String schema,
             boolean dataVerified) implements ExensioLotWaferResult {
    // Backward-compatible constructor (defaults to dataVerified=false)
    Found(long lotKey, long waferKey, long pgKey, String ppid,
          String lotId, String waferId, String fileName, String schema) {
        this(lotKey, waferKey, pgKey, ppid, lotId, waferId, fileName, schema, false);
    }
}
```

---

### Component 3: ExensioLoadMonitor — Use Verified Confirmation

#### [MODIFY] [ExensioLoadMonitor.java](file:///c:/Users/fg8n8x/Desktop/wip/exensioreload/backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/service/ExensioLoadMonitor.java)

Update `processBatch()` and `retryIndividualRecords()` to call `verifyDataLoaded()` when a `Found` result is returned, before transitioning to `COMPLETED`. If verification fails, keep the record as `NOT_FOUND` for retry on the next poll cycle.

```java
case ExensioLotWaferResult.Found found -> {
    // NEW: Verify actual data exists before marking DONE
    if (props.isVerifyDataLoaded()) {
        int pgcKey = DataTypePgcKeyMapper.resolve(record.dataType());
        boolean verified = exensioClient.verifyDataLoaded(
            found.pgKey(), found.waferKey(), pgcKey, token, traceId);
        if (!verified) {
            log.info("Data not yet loaded for record id={}, waferKey={} — will retry", 
                     record.id(), found.waferKey());
            updates.add(new BatchResult.RecordUpdate(
                record.id(), BatchResult.UpdateType.NOT_FOUND, ...));
            continue; // Retry next cycle
        }
    }
    // Data verified — mark DONE
    updates.add(new BatchResult.RecordUpdate(
        record.id(), BatchResult.UpdateType.COMPLETED,
        found.waferKey(), found.pgKey(), ...));
}
```

---

### Component 4: ExensioProperties — Add Configuration

#### [MODIFY] ExensioProperties

Add configuration switches:

```yaml
exensio:
  # NEW: Enable/disable data verification via Results API after lot-wafer-lookup
  verify-data-loaded: true
  # NEW: Minimum number of data rows required to consider data "loaded"
  verify-min-rows: 1
  # NEW: Enable/disable program validation via Programs API
  validate-program: false
  # NEW: Timeout for verification API calls (seconds)
  verify-timeout-seconds: 15
```

---

### Component 5: BatchLookupResult — Support Verification in Batch Path

#### [MODIFY] [BatchLookupResult.java](file:///c:/Users/fg8n8x/Desktop/wip/exensioreload/backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/dto/BatchLookupResult.java)

Update `mapToRecordUpdates()` to support verification callback: after batch lot-wafer-lookup resolves keys, verify each `COMPLETED` result against the Results API before finalizing.

---

## Implementation Detail: The Results API Call

Based on the Python lib (`exAPI_Results`, lines 676–770), here's the exact HTTP request:

```
POST /v1/result/results
Authorization: Bearer <token>
Content-Type: application/json

{
  "pgc_key": 1,
  "rework_criteria": "LATEST",
  "stat_keys": [
    { "pg_key": 12345, "wafer_key": 4633046 }
  ]
}
```

Expected response (from Python `parse_Results`, line 978):
```json
{
  "results": {
    "result_sets": [{
      "testdata": [{"test_name": "die_x"}, {"test_name": "die_y"}, ...],
      "rows": [[1, 2, ...], [3, 4, ...], ...]
    }]
  }
}
```

**Verification logic**: If `results.result_sets[0].rows` is non-empty → data is loaded. If empty or missing → data not yet loaded, retry.

> [!IMPORTANT]
> The Results API returns the **full parametric dataset** — potentially thousands of rows per wafer. For verification purposes, we only need to know if **any rows exist**. We should either:
> - Use `test_indexes: [1]` to request only the first index (minimizes response size)
> - Or check the response headers / first bytes and abort early
> 
> The Python lib fetches all rows because it wants the actual data. We only want existence confirmation.

---

## User Review Required

> [!WARNING]
> **Performance trade-off**: Adding a Results API call for every `Found` record adds ~1 extra HTTP roundtrip per wafer (estimated 200-500ms). In batch mode with 50+ records, this could add significant latency. Options:
> 1. **Always verify** — safest, slower
> 2. **Verify on first find only** — if the first wafer in a lot is verified, skip verification for the rest
> 3. **Verify after a delay** — only verify records that have been in `Found` state for >N minutes (catches the "keys exist but data still loading" window)
> 4. **Sample verification** — verify 1 out of N records randomly

> [!IMPORTANT]
> **pgc_key**: The Python lib hardcodes `pgc_key=1` (PROBE) everywhere. Your app supports pgc_keys 1, 2, 4, 5, 14 via `DataTypePgcKeyMapper`. The Results API call must pass the **correct pgc_key** for the data type, not hardcode 1. Do all your current `pgc_key` values work with the Results API, or is this only applicable to certain data types?

## Open Questions

1. **Is the Results API available in your environment?** The Python lib targets `spark-mas-01.canyon.aws.pdf.com` — is this the same Exensio instance your Spring Boot connects to?
2. **Do you want verification to be blocking (delay DONE) or a post-DONE audit?** Blocking is safer but slower. Post-DONE audit would log warnings for records where data wasn't found but wouldn't block the pipeline.
3. **Are there data types where the Results API doesn't apply?** (e.g., BINMAP/WXML/UPM with pgc_key=4 — do these have result_sets?)

---

## Verification Plan

### Automated Tests
- Unit test `verifyDataLoaded()` with mocked Exensio responses (empty, non-empty, error)
- Unit test `validateProgram()` with mocked Programs API responses
- Integration test the full flow: `lotWaferLookup` → `verifyDataLoaded` → `COMPLETED` / `NOT_FOUND`
- Existing `ExensioLoadMonitor` tests updated to cover the verification branch

### Manual Verification
1. Deploy to QA environment
2. Submit a known lot/wafer that has data loaded in Exensio → should get `COMPLETED` with `dataVerified=true`
3. Submit a lot/wafer with keys registered but data not yet loaded → should stay `NOT_FOUND` until data appears
4. Toggle `verify-data-loaded: false` → should behave as before (backward compatible)
5. Monitor API call latency impact via existing JMX metrics
