# ExensioClient.java - Business Flow and Query Analysis

**Date:** July 21, 2026  
**Context:** Lot Existence Verification Feature Integration  
**Status:** ANALYSIS & RECOMMENDATIONS

---

## Overview

The `ExensioClient.java` serves as the HTTP API client for Exensio operations. It handles:

- **Single-record lot/wafer lookups** via `lotWaferLookup()`
- **Batch lot/wafer lookups** via `lotWaferLookupBatch()`
- **Raw SQL queries** for advanced filtering with identifiers (filename, metadataId, dataId)
- **Token management** with automatic 401 retry
- **Error handling** with exponential backoff for transient failures

---

## Current Business Flows

### 1. Single Record Lookup Flow

```
User inputs: lot, wafer, [targetEndTime], [pgcKey], [testPhase], [filename], [metadataId], [dataId]
         ↓
    Generate traceId (UUID)
         ↓
    Get auth token
         ↓
    Try Raw SQL lookup first (if identifiers provided)
         ↓
    If Raw SQL returns no results → Fall back to lot-wafer-lookup endpoint
         ↓
    On 401 → Refresh token and retry
         ↓
    Parse response & validate PPID suffix (if testPhase provided)
         ↓
    Return: Found(lotKey, waferKey, pgKey, ppid) | NotFound() | Error(msg)
```

### 2. Batch Lookup Flow

```
User inputs: List<StageRecord> with lot, wafer, dataType, filename, metadataId, dataId
         ↓
    Try Raw SQL lookup first (batch mode)
         ↓
    Track which records resolved via Raw SQL
         ↓
    For unresolved records → Fall back to lot-wafer-lookup endpoint
         ↓
    Merge results from both paths
         ↓
    On 401 → Refresh token and retry
         ↓
    On transient error → Exponential backoff retry
         ↓
    Return: BatchLookupResult with merged lot results
```

### 3. Raw SQL Lookup Flow

```
Build identifier tokens from: filename, metadataId, dataId
         ↓
    Filter: Only process if identifiers + lot/wafer provided
         ↓
    Build SQL WHERE clause:
      - pgc_key match
      - lot_id match (case-insensitive, trimmed)
      - wafer_id match (if wafer provided)
      - file_name contains any identifier (LIKE with escape)
         ↓
    Execute: SELECT lot_id, wafer_id, lot_key, wafer_key, pg_key, ppid, file_name, end_time
         ↓
    Select best row by:
      - Identifier match score (number of identifiers found in filename)
      - End time proximity (if targetEndTime provided)
         ↓
    Return: Found with selected row data
```

---

## Query Patterns

### Pattern 1: Single Record Raw SQL

```sql
SELECT lot_id, wafer_id, lot_key, wafer_key, pg_key, ppid, file_name, end_time FROM (
  SELECT l.lot_id, NVL(w.wf_id,'') AS wafer_id,
         ol.lot_key, NVL(w.wf_key,0) AS wafer_key,
         NVL(ol.pg_key,0) AS pg_key, NVL(p.ppid,'') AS ppid,
         NVL(de.file_name,'') AS file_name,
         NVL(TO_CHAR(ol.end_time, 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),'') AS end_time
  FROM op_log ol
  JOIN lot l ON l.lot_key = ol.lot_key
  JOIN program p ON p.pg_key = ol.pg_key
  LEFT JOIN wafer w ON w.wf_key = ol.wf_key
  LEFT JOIN df_export de ON de.lg_key = ol.lg_key
           AND (w.wf_key IS NULL OR de.wf_key = w.wf_key)
  WHERE ol.pgc_key = ?
    AND UPPER(TRIM(l.lot_id)) = UPPER(TRIM(?))
    AND (wafer blank OR UPPER(TRIM(NVL(w.wf_id,''))) = UPPER(TRIM(?)))
    AND (de.file_name LIKE '%identifier%' OR ...)
  ORDER BY ol.end_time DESC
) WHERE ROWNUM <= 10000
```

**Key Points:**

- Joins: op_log → lot, program, wafer, df_export
- Filters: pgc_key (data type), lot_id, wafer_id (optional), file_name (identifier matching)
- Ordering: by end_time DESC (most recent first)
- Limit: configurable via `props.getRawSqlRowLimit()`

### Pattern 2: Batch Raw SQL

```sql
SELECT ... FROM (
  SELECT l.lot_id, ...
  FROM op_log ol
  JOIN lot l ON ...
  JOIN program p ON ...
  LEFT JOIN wafer w ON ...
  LEFT JOIN df_export de ON ...
  WHERE (
    (ol.pgc_key = ? AND UPPER(TRIM(l.lot_id)) = UPPER(TRIM(?)) AND ... AND file_name LIKE ...)
    OR
    (ol.pgc_key = ? AND UPPER(TRIM(l.lot_id)) = UPPER(TRIM(?)) AND ... AND file_name LIKE ...)
    ...
  )
  ORDER BY ol.end_time DESC
) WHERE ROWNUM <= 10000
```

**Key Points:**

- Multiple (pgc_key, lot_id, wafer_id, file_name) tuples joined with OR
- Single query execution for entire batch
- Most recent end_time selected per record

### Pattern 3: Lot-Wafer-Lookup Endpoint

```
POST /v1/key/lot-wafer-lookup
{
  "pgc_key": 1,
  "lot_ids": ["LOT001", "LOT002"],
  "wafer_ids": ["WAFER001"]  // optional
}

Response:
{
  "lots": [{
    "lot_key": 2776623,
    "lot_id": "LOT001",
    "wafers": [{
      "wafer_id": "WAFER001",
      "wafer_key": 4633046,
      "pg_key": 12345,
      "ppid": "WS::CM8012X_FT"
    }]
  }]
}
```

---

## Integration with Lot Existence Verification

### Current Separation of Concerns

**ExensioClient.java:**

- ✅ Handles lot/wafer lookups with identifiers
- ✅ Supports test phase validation
- ✅ Manages token lifecycle
- ✅ Implements retry/backoff logic
- ❌ Does NOT verify lot existence (pre-flight check)
- ❌ Does NOT support dataType → PGC_KEY mapping directly

**ExensioPreCheckService.java:**

- ✅ Verifies lot existence before discovery
- ✅ Queries EXENSIO_PROD_OPLOG_METADATA (Snowflake)
- ✅ Supports dataType → PGC_KEY resolution
- ✅ Supports date range filtering (INSERT_TIME)
- ❌ Does NOT handle identifier matching (filename, metadataId, dataId)
- ❌ Does NOT do test phase validation

### Query Comparison

| Aspect                   | ExensioClient Raw SQL                              | ExensioPreCheckService HTTP/Snowflake                          |
| ------------------------ | -------------------------------------------------- | -------------------------------------------------------------- |
| **Data Source**          | Oracle op_log table                                | Exensio HTTP raw-sql OR Snowflake EXENSIO_PROD_OPLOG_METADATA  |
| **PGC_KEY**              | Resolved from wafer presence OR explicit           | Resolved from dataType parameter                               |
| **Filters**              | pgc_key, lot_id, wafer_id, file_name (identifiers) | pgc_key, lot_id, wafer_id (optional), INSERT_TIME (date range) |
| **Purpose**              | Find specific wafer with metadata matching         | Verify lot exists in Exensio (pre-flight)                      |
| **Best for**             | Monitoring individual records                      | Discovery/staging pre-flight check                             |
| **Supports Test Phase**  | ✅ Yes (PPID suffix validation)                    | ❌ No                                                          |
| **Supports Identifiers** | ✅ Yes (filename, metadataId, dataId)              | ❌ No                                                          |
| **Supports Date Range**  | ❌ No (only most recent)                           | ✅ Yes (INSERT_TIME filter)                                    |

---

## Recommended Updates for Lot Existence Verification

### 1. Add PGC_KEY Resolution Helper

**Current State:**

- ExensioClient uses wafer-presence fallback: `pgcKey = waferBlank ? 2 : 1`
- ExensioPreCheckService uses explicit `dataType` parameter

**Recommendation:**
Add utility method to ExensioClient for consistent PGC_KEY resolution:

```java
/**
 * Resolves PGC_KEY from dataType string.
 * Mirrors logic from ExensioPreCheckService.resolvePgcKey().
 *
 * Maps: probe→1, ft→2, pcm→5, defect→14, map/binmap/wxml/upm→4
 * Defaults to 2 (FT) if unknown
 */
public static int resolvePgcKeyFromDataType(String dataType) {
    if (dataType == null || dataType.isBlank()) {
        return 2; // Default to FT
    }

    String normalized = dataType.trim().toLowerCase();
    return switch (normalized) {
        case "probe" -> 1;
        case "ft", "final test" -> 2;
        case "pcm" -> 5;
        case "defect" -> 14;
        case "map", "binmap", "wxml", "upm" -> 4;
        default -> {
            log.debug("Unknown dataType '{}', defaulting to PGC_KEY=2 (FT)", dataType);
            yield 2;
        }
    };
}
```

**Benefit:** Eliminates duplication between ExensioClient and ExensioPreCheckService.

### 2. Add Batch Result Filtering by Status

**Current State:**

- `BatchLookupResult` returns all found lots regardless of verification status
- Caller must filter manually

**Recommendation:**
Add filtering method to support post-verification filtering:

```java
/**
 * Filters batch results to only include lots found in Exensio.
 * Used by discovery preview to exclude lots marked as 'not found' by lot verification.
 */
public static BatchLookupResult filterByVerificationStatus(
        BatchLookupResult batchResult,
        Map<String, Boolean> verificationMap) {
    if (verificationMap == null || verificationMap.isEmpty()) {
        return batchResult;
    }

    List<BatchLookupResult.LotResult> filteredLots = batchResult.getLots().stream()
            .filter(lot -> verificationMap.getOrDefault(lot.getLotId(), true))
            .toList();

    return new BatchLookupResult(filteredLots);
}
```

**Benefit:** Provides unified interface for filtering batch results after verification.

### 3. Add Date Range Support to Raw SQL

**Current State:**

- Single record raw SQL only searches most recent records (ORDER BY end_time DESC)
- No date range filtering

**Recommendation:**
Add optional date range parameters to `doRawSqlLookupSingle()`:

```java
private ExensioLotWaferResult doRawSqlLookupSingle(
    String lot,
    String wafer,
    Instant targetEndTime,
    int pgcKey,
    String testPhase,
    String filename,
    String metadataId,
    String dataId,
    String token,
    String traceId,
    Instant startDate,  // NEW: date range start
    Instant endDate     // NEW: date range end
) {
    // ... existing code ...

    String sql = buildSingleRawSql(
        lot, wafer, pgcKey, identifiers,
        startDate, endDate  // Pass date range to SQL builder
    );

    // ... rest of implementation ...
}

private String buildSingleRawSql(
    String lot, String wafer, int pgcKey, Set<String> identifiers,
    Instant startDate, Instant endDate) {

    StringBuilder where = new StringBuilder();
    where.append("ol.pgc_key = ").append(pgcKey)
            .append(" AND UPPER(TRIM(l.lot_id)) = UPPER(TRIM('")
            .append(escapeSqlLiteral(lot))
            .append("'))");

    // ... existing filter logic ...

    // NEW: Add date range filter if provided
    if (startDate != null && endDate != null) {
        where.append(" AND ol.end_time >= TO_DATE('")
                .append(formatDate(startDate))
                .append("', 'YYYY-MM-DD')")
                .append(" AND ol.end_time < TO_DATE('")
                .append(formatDate(endDate))
                .append("', 'YYYY-MM-DD')");
    }

    // ... return SQL string ...
}
```

**Benefit:** Aligns with ExensioPreCheckService date range filtering for consistency.

### 4. Add Verification Context Logging

**Current State:**

- TraceId used for individual lookup logging
- No verification context in logs

**Recommendation:**
Add verification flag to log context when called from pre-flight verification:

```java
/**
 * Executes a single lot/wafer lookup with verification context.
 * Used by pre-flight lot verification to ensure proper logging.
 */
public ExensioLotWaferResult verifyLotExistence(
    String lot,
    String wafer,
    String dataType,
    String traceId) {

    int pgcKey = resolvePgcKeyFromDataType(dataType);

    log.info("[PRECHECK] Starting lot existence verification: lot={}, wafer={}, " +
            "dataType={}, pgcKey={}, traceId={}",
            lot, wafer, dataType, pgcKey, traceId);

    ExensioLotWaferResult result = lotWaferLookup(lot, wafer, null, pgcKey, null);

    log.info("[PRECHECK] Lot existence verification completed: lot={}, status={}, traceId={}",
            lot, result.getClass().getSimpleName(), traceId);

    return result;
}
```

**Benefit:** Makes verification calls easily identifiable in logs for troubleshooting.

### 5. Add Batch Retry Strategy Alignment

**Current State:**

- Batch lookup has explicit retry logic with token refresh on 401
- Single lookup relies on outer retry wrapper

**Recommendation:**
Document retry behavior and add configuration options:

```java
/**
 * Batch lookup retry strategy:
 * 1. Initial attempt with current token
 * 2. On 401: Refresh token (no delay) and retry immediately
 * 3. On transient error (429, 5xx, timeout): Exponential backoff
 * 4. Max attempts: configurable via props.getRetryMaxAttempts()
 * 5. Base delay: configurable via props.getRetryBaseDelayMs()
 *
 * This ensures pre-flight lot verification retries gracefully
 * even when Exensio is temporarily unavailable.
 */
public BatchLookupResult lotWaferLookupBatch(List<StageRecord> records, String traceId) {
    // ... existing implementation with explicit retry logic ...
}
```

**Benefit:** Ensures batch verification doesn't fail on temporary outages.

---

## Query Optimization Recommendations

### 1. Index Strategy for Pre-Flight Verification

**Issue:** ExensioPreCheckService queries `EXENSIO_PROD_OPLOG_METADATA` on Snowflake with:

- PGC_KEY filter (selective)
- LOT_ID filter (high cardinality)
- INSERT_TIME filter (range query)

**Recommendation:** Ensure indexes exist:

```sql
-- Snowflake indexes (if supported)
CREATE INDEX idx_exensio_pgc_lot ON ANALYTICSPRD.MFG.EXENSIO_PROD_OPLOG_METADATA(PGC_KEY, LOT_ID);
CREATE INDEX idx_exensio_insert_time ON ANALYTICSPRD.MFG.EXENSIO_PROD_OPLOG_METADATA(INSERT_TIME);
```

### 2. Query Execution Plan Monitoring

**Recommendation:** Add execution time monitoring for pre-flight queries:

```java
long startTime = System.currentTimeMillis();
ExensioPreCheckResponse result = exensioPreCheckService.check(request);
long elapsed = System.currentTimeMillis() - startTime;

if (elapsed > 5000) {
    log.warn("[PERF] Lot verification took {}ms for {} lots (threshold: 5000ms)",
            elapsed, request.lotIds().size());
}
```

**Benefit:** Identifies slow verification queries before they impact UI.

### 3. Batch Size Optimization

**Current State:**

- Batch queries join up to ~1000 lots in single query
- May hit Oracle row limit (ROWNUM <= 10000)

**Recommendation:** Add batching strategy:

```java
/**
 * Splits batch lookup into chunks to avoid hitting ROWNUM limit.
 * Each chunk processes up to 100 unique lots in single query.
 */
private static final int BATCH_CHUNK_SIZE = 100;

private BatchLookupResult doLotWaferLookupBatchChunked(List<StageRecord> records, String token) {
    List<BatchLookupResult.LotResult> allLots = new ArrayList<>();

    // Split records by unique lot into chunks
    Map<String, List<StageRecord>> byLot = records.stream()
            .collect(Collectors.groupingBy(StageRecord::lot));

    List<List<Map.Entry<String, List<StageRecord>>>> chunks =
            byLot.entrySet().stream()
                .collect(Collectors.toCollection(() -> new ArrayList<>()))
                .stream()
                .collect(BatchUtils.chunking(BATCH_CHUNK_SIZE))
                .toList();

    for (List<Map.Entry<String, List<StageRecord>>> chunk : chunks) {
        // Reconstruct records for this chunk
        List<StageRecord> chunkRecords = chunk.stream()
                .flatMap(e -> e.getValue().stream())
                .toList();

        BatchLookupResult chunkResult = doLotWaferLookupBatchEndpoint(chunkRecords, token);
        if (chunkResult.isSuccess()) {
            allLots.addAll(chunkResult.getLots());
        }
    }

    return new BatchLookupResult(allLots);
}
```

**Benefit:** Prevents hitting ROWNUM limit for large batch verification jobs.

---

## Alignment Matrix

| ExensioClient Feature      | ExensioPreCheckService Equivalent | Alignment               |
| -------------------------- | --------------------------------- | ----------------------- |
| Single lot lookup          | Verify single lot exists          | ✅ Compatible           |
| Batch lot lookup           | Verify batch lots exist           | ✅ Compatible           |
| Token refresh on 401       | Token management in auth service  | ✅ Aligned              |
| Exponential backoff retry  | Transient error handling          | ✅ Aligned              |
| PGC_KEY resolution         | Via dataType parameter            | ⚠️ Different approaches |
| Identifier matching        | Not supported in pre-check        | ⚠️ Different use cases  |
| Date range filtering       | Supported via INSERT_TIME         | ⚠️ Not in ExensioClient |
| PPID test phase validation | Not supported                     | ⚠️ Different use cases  |
| Raw SQL query building     | HTTP endpoint for simplicity      | ⚠️ Different strategies |

---

## Summary

### Current State

- **ExensioClient**: Advanced lookup with identifiers and test phase validation
- **ExensioPreCheckService**: Simple lot existence verification with date range support
- **Separation**: Clear division of concerns between detailed lookup and pre-flight check

### Recommended Improvements

1. ✅ Add `resolvePgcKeyFromDataType()` utility for consistent PGC_KEY resolution
2. ✅ Add `filterByVerificationStatus()` to support post-verification filtering
3. ✅ Add optional date range support to raw SQL for alignment
4. ✅ Add verification context logging for troubleshooting
5. ✅ Add batch chunking strategy to prevent ROWNUM limit issues

### No Breaking Changes Required

- ExensioClient can be extended without modifying existing public APIs
- ExensioPreCheckService remains independent pre-flight check service
- Both services can coexist and complement each other

---

## Implementation Priority

**High Priority (Improves Production Readiness):**

1. Add `resolvePgcKeyFromDataType()` utility - eliminates duplication
2. Add execution time monitoring - detects performance issues
3. Add batch chunking strategy - prevents edge case failures

**Medium Priority (Improves Maintainability):**

1. Add verification context logging - aids troubleshooting
2. Add `filterByVerificationStatus()` - unified filtering interface

**Low Priority (Future Enhancement):**

1. Add date range support to raw SQL - for potential future use cases
