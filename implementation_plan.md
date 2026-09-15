# Fix Exensio Raw SQL Queries for Robust Lot/Wafer/File Resolution

## Background

The raw SQL queries in `ExensioClient.java` had multiple issues causing empty/null responses and inefficient lookups:
- Queries returning `null` body (HTTP 200 but empty results)
- 16+ second response times due to fake UNION ALL querying the same schema twice
- The lot ID from the "discovery" phase (stepper) may differ from the lot ID assigned during actual Exensio loading (e.g. child sub-lots, split lots, or stripped suffixes)
- Inadvertent conversion of `LEFT JOIN df_export` to INNER JOIN via WHERE clause conditions
- `SUBSTR(NVL(de.file_name,''), 1, 15)` truncation in SELECT breaking Java-side file identifier matching

## Root Cause Analysis from Data Dictionary & Logs

### Issue 1: `buildSingleRawSql` uses `lot_id` with case-variant matching but lot may be renamed during loading
The lot ID from the file discovery (stepper) may not match the lot ID in Exensio because loaders can rename/remap lots, split lots, or assign child lots.
**Data Dictionary Insight (§3.1 & §6.15):**
- `OP_LOG.lot_key` links to the current operation's `LOT` record (`l.lot_id`).
- `OP_LOG.src_lot` links to the parent/source `LOT` record (`sl.lot_id`).
- Discovery lot IDs often include suffixes (e.g. `IR77464.1J`, `IR77464-01`) where the Exensio database registers the base lot `IR77464` or vice versa.

### Issue 2: `buildFallbackRawSql` had no lot filter and was dropped when defect files didn't match
The fallback query relies on `pgc_key`, time window, and wafer. However, non-matching defect file conditions placed in `WHERE` dropped candidate rows.

### Issue 3: `DF_EXPORT.file_name` LIKE matching is unreliable in WHERE
Per data dictionary §10.25, `DF_EXPORT.file_name` stores the defect export filename, **not the original raw data filename**. Additionally, placing `(de.file_name IS NULL OR ...)` in `WHERE` turns the LEFT JOIN into an INNER JOIN if any defect export record exists for that `lg_key` with a non-matching name.

### Issue 4: Fake UNION ALL queries
The raw-sql API endpoint selects the schema based on the auth token. Both halves of the `UNION ALL` hit the **same schema** — simply hardcoding `'PRODUCTION'` in one half and `'SANDBOX'` in the other. Removing the UNION ALL and passing `schemaLabel` directly halves query execution time.

### Issue 5: Truncated `file_name`
`SUBSTR(NVL(de.file_name,''), 1, 15)` in the SELECT clause truncated filenames to 15 characters, which broke Java-side identifier scoring (`selectBestRawRow`) when filenames exceeded 15 characters.

### Issue 6: Missing `insert_time` in primary `buildSingleRawSql`
The primary query had no time window at all, scanning full historical data.

---

## Implemented Changes

### 1. [ExensioClient.java](file:///c:/Users/fg8n8x/Desktop/wip/exensioreload/backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/service/ExensioClient.java)

- **Lot Candidates & Source Lot Resolution:**
  - Added `getLotCandidates(lot)` helper to extract case variants and base lot identifiers (stripping suffixes after `.`, `-`, `_` if length >= 3).
  - Joined `LEFT JOIN lot sl ON sl.lot_key = ol.src_lot`.
  - Filtered with `(l.lot_id IN (...) OR sl.lot_id IN (...))` across all candidates.
  - Selected `NVL(l.lot_id, NVL(sl.lot_id, '')) AS lot_id`.
- **Single SELECT (Removed UNION ALL):**
  - All SQL builder methods (`buildSingleRawSql`, `buildFallbackRawSql`, `buildBatchRawSql`) now emit a single SELECT query with `:schemaLabel` parameterized.
  - Caller manages PRODUCTION -> SANDBOX fallback cleanly.
- **Time Window:**
  - Added `ol.insert_time >= TO_TIMESTAMP(...)` to `buildSingleRawSql` using `props.getFallbackQueryTimeWindowHours()`.
- **DF_EXPORT Safe Join:**
  - Moved file identifier matching into the `LEFT JOIN df_export` `ON` condition so non-matching rows are never filtered out.
  - Removed `SUBSTR(..., 1, 15)` truncation; returns full `NVL(de.file_name, '') AS file_name`.
- **Wafer Matching:**
  - Added fallback in `buildWaferMatchClause`: if wafer cannot be parsed as an integer, matches `(UPPER(w.wf_id) LIKE '%...%' ESCAPE '\')`.
- **Batch Processing:**
  - `doRawSqlLookupBatch` uses `getLotCandidates` for both primary and fallback matching, and indexes base lots in `lotToRecord` and `resolvedLots`.

### 2. [BatchLookupResult.java](file:///c:/Users/fg8n8x/Desktop/wip/exensioreload/backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/dto/BatchLookupResult.java)

- Enhanced `mapToRecordUpdates`: indexes base lots in `lotLookup` and adds base lot lookup fallback so that differences between discovery lot (e.g. `IR77464.1J`) and database lot (e.g. `IR77464`) are seamlessly resolved.

---

## SQL Structure (Primary Query)

```sql
SELECT lot_id, wafer_id, lot_key, wafer_key, pg_key, ppid, file_name, insert_time, end_time, schema_name FROM (
  SELECT NVL(l.lot_id, NVL(sl.lot_id,'')) AS lot_id, 
         NVL(w.wf_id,'') AS wafer_id,
         ol.lot_key AS lot_key, 
         NVL(w.wf_key,0) AS wafer_key,
         NVL(ol.pg_key,0) AS pg_key, 
         NVL(p.ppid,'') AS ppid,
         NVL(de.file_name,'') AS file_name,
         NVL(TO_CHAR(ol.insert_time, 'YYYY-MM-DD"T"HH24:MI:SS.FF3"Z"'),'') AS insert_time,
         NVL(TO_CHAR(ol.end_time, 'YYYY-MM-DD"T"HH24:MI:SS.FF3"Z"'),'') AS end_time,
         :schemaLabel AS schema_name
  FROM op_log ol
  JOIN lot l ON l.lot_key = ol.lot_key
  LEFT JOIN lot sl ON sl.lot_key = ol.src_lot
  JOIN program p ON p.pg_key = ol.pg_key
  LEFT JOIN wf_log wfl ON wfl.lg_key = ol.lg_key
  LEFT JOIN wafer w ON w.wf_key = wfl.wf_key
  LEFT JOIN df_export de ON de.lg_key = ol.lg_key 
    AND (w.wf_key IS NULL OR de.wf_key = w.wf_key)
    AND (<file_identifier_conditions>)
  WHERE ol.pgc_key = :pgcKey
    AND (l.lot_id IN (:candidates) OR sl.lot_id IN (:candidates))
    AND ol.insert_time >= TO_TIMESTAMP(:windowStart, 'YYYY-MM-DD HH24:MI:SS.FF')
    AND (:waferClause)
  ORDER BY ol.insert_time DESC, ol.end_time DESC
) WHERE ROWNUM <= :limit
```

---

## Verification

1. **Static Analysis & Inspection:**
   - Syntax, types, method calls, record constructors, and import paths verified across `ExensioClient.java` and `BatchLookupResult.java`.
   - Local command execution for build tools (java, maven, git) is unavailable per environment constraints.
2. **Runtime Verification:**
   - In production / container environments, monitor `traceId` logs to confirm single-query execution per schema, absence of UNION ALL, sub-second to low-second latency, and successful lot/wafer resolution for renamed and split lots.
