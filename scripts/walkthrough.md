# Exensio Parametric Data Confirmation Flow — Implementation Walkthrough

## Overview

Based on the multi-step verification pattern established in `scripts/exensioAPI_PythonLib.py` (`exAPI_Results` and `exAPI_Programs`), we have enhanced the Spring Boot Exensio client and loading monitor. 

Previously, the application considered data "loaded" (`COMPLETED`) as soon as `lot-wafer-lookup` (or raw-SQL) returned internal wafer and program keys (`wafer_key`, `pg_key`). However, Exensio registers keys before the raw parametric rows are fully parsed and stored in the database. When enabled, the application now confirms actual data existence via the **Results API** (`POST /v1/result/results`) before marking records `COMPLETED`.

---

## Changes Implemented

### 1. Verification Result Representation
- **[ExensioLotWaferResult.java](file:///c:/Users/fg8n8x/Desktop/wip/exensioreload/backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/service/ExensioLotWaferResult.java)**:
  - Added `dataVerified` boolean flag to the `Found` record.
  - Added a backward-compatible constructor defaulting `dataVerified` to `false`.

### 2. Configuration Settings
- **[ExensioProperties.java](file:///c:/Users/fg8n8x/Desktop/wip/exensioreload/backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/config/ExensioProperties.java)** & **[application.yml](file:///c:/Users/fg8n8x/Desktop/wip/exensioreload/backend/src/main/resources/application.yml)**:
  - `exensio.verify-data-loaded` (`${EXENSIO_VERIFY_DATA_LOADED:false}`): Master switch to toggle Results API verification (defaults to `false` for 100% backward compatibility).
  - `exensio.verify-min-rows` (`${EXENSIO_VERIFY_MIN_ROWS:1}`): Minimum number of parametric data rows needed to confirm load completion (default: `1`).
  - `exensio.validate-program` (`${EXENSIO_VALIDATE_PROGRAM:false}`): Optional PPID index validation via Programs API (`POST /v1/key/programs`).
  - `exensio.verify-timeout-seconds` (`${EXENSIO_VERIFY_TIMEOUT_SECONDS:15}`): Timeout for verification HTTP requests.

### 3. Verification Methods in ExensioClient
- **[ExensioClient.java](file:///c:/Users/fg8n8x/Desktop/wip/exensioreload/backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/service/ExensioClient.java)**:
  - `verifyDataLoaded(long pgKey, long waferKey, int pgcKey, String token, String traceId)`:
    - Calls `POST /v1/result/results` matching the Python library's structure.
    - Employs `test_indexes: [1]` to minimize network payload by requesting only the first index for existence verification.
    - Inspects `results.result_sets[0].rows` to count returned rows.
  - `validateProgram(String ppid, int pgcKey, String token, String traceId)`:
    - Calls `POST /v1/key/programs` matching Python's `exAPI_Programs()`.
    - Checks whether the test program has valid test indexes (`> 0`).
  - `verifyAndEnrich(ExensioLotWaferResult.Found found, int pgcKey, String traceId)`:
    - Orchestrator that validates program and checks data rows.
    - If rows >= `verifyMinRows`, returns `Found(..., dataVerified=true)`.
    - If 0 rows found, returns `NotFound()` (keys exist but data is still processing).
    - If network/parse error occurs, fails safe by returning `found` unverified.
  - Hooked `verifyAndEnrich` into `lotWaferLookup()` to ensure single-record lookups and retries benefit automatically.

### 4. Batch & Single-Record Processing in ExensioLoadMonitor
- **[ExensioLoadMonitor.java](file:///c:/Users/fg8n8x/Desktop/wip/exensioreload/backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/service/ExensioLoadMonitor.java)**:
  - In `processBatch()`: For any record marked `COMPLETED` by `lookupResult.mapToRecordUpdates()`, if `verify-data-loaded` is enabled, calls `verifyAndEnrich()`. If verification returns `NotFound` (0 data rows), downgrades the record to `NOT_FOUND` so it will be retried in the next poll cycle (or timeout if deadline exceeded). Only records with confirmed rows are cached and marked completed.
  - In `retryIndividualRecords()`: Integrates with the verified `lotWaferLookup()` results, caches verified hits, and sets `ppid` and `schema`.

### 5. DTO Enhancements
- **[BatchResult.java](file:///c:/Users/fg8n8x/Desktop/wip/exensioreload/backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/dto/BatchResult.java)** & **[BatchLookupResult.java](file:///c:/Users/fg8n8x/Desktop/wip/exensioreload/backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/dto/BatchLookupResult.java)**:
  - Added `ppid` to `BatchResult.RecordUpdate` with overloaded constructors to maintain compatibility across all callers.
  - Propagated `ppid` from `WaferResult` during batch mapping.

### 6. Raw-SQL Bypass & Alignment with Python Library
- **[ExensioClient.java](file:///c:/Users/fg8n8x/Desktop/wip/exensioreload/backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/service/ExensioClient.java)**:
  - Fixed `doLotWaferLookup` and `doLotWaferLookupBatch` to strictly check `props.isPreferRawSql()`. Previously, raw-SQL was unconditionally executed on every call, ignoring the configuration.
- **[ExensioProperties.java](file:///c:/Users/fg8n8x/Desktop/wip/exensioreload/backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/config/ExensioProperties.java)** & **[application.yml](file:///c:/Users/fg8n8x/Desktop/wip/exensioreload/backend/src/main/resources/application.yml)**:
  - Changed `prefer-raw-sql` default from `true` to `false` (`${EXENSIO_PREFER_RAW_SQL:false}`).
  - Set `verify-data-loaded` to `true` by default (`${EXENSIO_VERIFY_DATA_LOADED:true}`).
  - Flow now directly uses Exensio's native REST endpoint `POST /v1/key/lot-wafer-lookup` (matching Python's `exAPI_LotWaferLookup`), completely avoiding Oracle SQL joins, timeouts, and HTTP 503 gateway errors.

---

## How to Test / Enable

When running the service in an environment where Java/Maven can execute:

1. **To enable verification**:
   Set the environment variable or configuration in `application.yml`:
   ```bash
   EXENSIO_VERIFY_DATA_LOADED=true
   ```
2. **Behavior Verification**:
   - If lot/wafer keys exist in Exensio but parametric data rows are still loading, logs will indicate:
     `Data verification: 0 rows found for record id=... — keeping NOT_FOUND for retry`
   - Once rows appear in Exensio, logs will show:
     `Data verification PASSED: X rows found for waferKey=..., pgKey=...`
     and the record will complete.
   - If disabled (`EXENSIO_VERIFY_DATA_LOADED=false`), the system retains previous behavior without any additional HTTP requests.
