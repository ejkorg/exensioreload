# Fix Exensio Raw SQL Queries for Robust Lot/Wafer/File Resolution

## Background

The raw SQL queries in `ExensioClient.java` had multiple issues causing empty/null responses and inefficient lookups:
- Queries returning `null` body (HTTP 200 but empty results)
- 16+ second response times due to fake UNION ALL querying the same schema twice
- The lot ID from the "discovery" phase (stepper) may differ from the lot ID assigned during actual Exensio loading (renamed lots, split sub-lots, or delimiter transformations)
- Inadvertent conversion of `LEFT JOIN df_export` to INNER JOIN via WHERE clause conditions
- `SUBSTR(NVL(de.file_name,''), 1, 15)` truncation in SELECT breaking Java-side file identifier matching

## Root Cause Analysis from Data Dictionary & Logs

### Issue 1: Discovery lot may differ from loaded lot — Delimiters (`.`, `-`, `_`) are NOT guaranteed and load inconsistently
We **cannot assume that lots always have `.`, `-`, or `_`** (many lots are purely alphanumeric without any punctuation).
Furthermore, when lots **do** have delimiters:
- **Sometimes they are loaded with delimiters intact** (e.g. `ABC.12` loaded as `ABC.12`).
- **Sometimes they are loaded without delimiters** (e.g. delimiters stripped: `ABC12`, or suffix omitted: `ABC`).
- **Sometimes the lot is remapped or renamed entirely** upon loading into Exensio (e.g. split into child sub-lots, or parented under a source lot).

**Data Dictionary & Architecture Solution:**
1. **Zero-Assumption Primary Query:**
   - Always queries the exact discovery lot (case-insensitive) first.
   - If delimiters (`.`, `-`, `_`) are present, also includes the stripped variant (`cleanLot.replaceAll("[.\\-_]", "")`) and base prefix.
   - Joins `LEFT JOIN lot sl ON sl.lot_key = ol.src_lot` per data dictionary §3.1 and §6.15 to match parent/source lots.
2. **Lot-Free Fallback Query as the Ultimate Safety Net:**
   - When the lot in Exensio does not match the discovery lot by any variation, the **fallback query drops the `lot_id` filter completely**.
   - It identifies the record using:
     - `ol.pgc_key = :pgcKey` (program group category)
     - `ol.insert_time >= TO_TIMESTAMP(...)` (time window)
     - `buildWaferMatchClause(wafer)` (numeric `w.wf_num` or string `w.wf_id`)
     - File identifier matching in `df_export`
   - It selects `NVL(l.lot_id, NVL(sl.lot_id,'')) AS lot_id`, retrieving the **actual loaded lot ID** directly from the Exensio database.

### Issue 2: `buildFallbackRawSql` non-matching defect file condition dropped candidate rows
The fallback query relies on `pgc_key`, time window, and wafer. Previously, placing `(de.file_name IS NULL OR ...)` in `WHERE` caused Oracle to drop rows that had any defect export record with a non-matching name.

### Issue 3: `DF_EXPORT.file_name` LIKE matching is unreliable in WHERE
Per data dictionary §10.25, `DF_EXPORT.file_name` stores the defect export filename, **not the original raw data filename**. Moving identifier matching into the `LEFT JOIN ... ON` clause ensures non-matching rows are never eliminated.

### Issue 4: Fake UNION ALL queries
The raw-sql API endpoint selects the schema based on the auth token. Both halves of the `UNION ALL` hit the **same schema** — simply hardcoding `'PRODUCTION'` in one half and `'SANDBOX'` in the other. Removing the UNION ALL and passing `schemaLabel` directly halves query execution time.

### Issue 5: Truncated `file_name`
`SUBSTR(NVL(de.file_name,''), 1, 15)` in the SELECT clause truncated filenames to 15 characters, breaking Java-side identifier scoring (`selectBestRawRow`) when filenames exceeded 15 characters.

### Issue 6: Missing `insert_time` in primary `buildSingleRawSql`
The primary query had no time window at all, scanning full historical data.

---

## Implemented Changes

### 1. [ExensioClient.java](file:///c:/Users/fg8n8x/Desktop/wip/exensioreload/backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/service/ExensioClient.java)

- **Lot Candidates & Source Lot Resolution:**
  - `getLotCandidates(lot)` makes **no assumption** that delimiters exist: always adds the exact lot (both uppercase and lowercase).
  - If delimiters exist, it adds both the delimiter-stripped version (`replaceAll("[.\\-_]", "")`) and the base prefix before the delimiter.
  - Joins `LEFT JOIN lot sl ON sl.lot_key = ol.src_lot` to match either current lot or parent source lot.
  - Filter: `(l.lot_id IN (...) OR sl.lot_id IN (...))`.
  - SELECT: `NVL(l.lot_id, NVL(sl.lot_id, '')) AS lot_id`.
- **Lot-Free Fallback Query:**
  - `buildFallbackRawSql` operates completely free of lot constraints, guaranteeing resolution when discovery lot differs from Exensio database lot.
- **Single SELECT (Removed UNION ALL):**
  - All SQL builder methods (`buildSingleRawSql`, `buildFallbackRawSql`, `buildBatchRawSql`) emit a single SELECT query parameterized by `:schemaLabel`.
- **Time Window:**
  - Added `ol.insert_time >= TO_TIMESTAMP(...)` to `buildSingleRawSql` using `props.getFallbackQueryTimeWindowHours()`.
- **DF_EXPORT Safe Join:**
  - Moved file identifier matching into the `LEFT JOIN df_export` `ON` condition so non-matching rows are never eliminated.
  - Removed `SUBSTR(..., 1, 15)` truncation; returns full `NVL(de.file_name, '') AS file_name`.
- **Wafer Matching (Dropped `wafer_id` in filters):**
  - Dropped `w.wf_id` completely from the SQL filters since the discovery `wafer_id` is not in final loaded form.
  - Added `extractWaferNum(rawWafer)` to reliably extract the wafer number regardless of format or prefixes.
  - Matches on `w.wf_num`: when `< 10`, matches both single digit and double digit (e.g. `w.wf_num = 1 OR w.wf_num = '1' OR w.wf_num = '01'`).
  - When `>= 10`, matches `w.wf_num = 11 OR w.wf_num = '11'`.
- **Batch Processing:**
  - `doRawSqlLookupBatch` uses `getLotCandidates` for both primary and fallback matching, indexing base lots and stripped lots in `lotToRecord` and `resolvedLots`.

### 2. [BatchLookupResult.java](file:///c:/Users/fg8n8x/Desktop/wip/exensioreload/backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/dto/BatchLookupResult.java)

- Enhanced `mapToRecordUpdates`:
  - Indexes exact lot, delimiter-stripped lot, and base lot in `lotLookup` and `waferLookup`.
  - Added `fileLookup` for fallback matching by filename when lot/wafer do not match.
  - Tracks the actual resolved lot ID from Exensio (`waferToLot`) so that updates receive the true database lot even when discovery lot differed.

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

## SQL Structure (Fallback Query — No Lot Constraint)

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
    AND ol.insert_time >= TO_TIMESTAMP(:windowStart, 'YYYY-MM-DD HH24:MI:SS.FF')
    AND (:waferClause)
  ORDER BY ol.insert_time DESC, ol.end_time DESC
) WHERE ROWNUM <= :limit
```
