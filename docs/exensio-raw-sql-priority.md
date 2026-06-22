# Exensio Lookup: Raw SQL Priority

The Exensio integration uses raw SQL (`POST /v1/key/raw-sql`) as the **primary** lookup strategy, falling back to the structured `POST /v1/key/lot-wafer-lookup` API when raw SQL can't resolve a record.

## How Priority Works

Both **single-record** and **batch** lookups follow the same flow:

1. **Raw SQL first** — build and execute a `SELECT` against the Exensio `op_log`/`lot`/`wafer`/`df_export` tables via Exensio's raw-sql endpoint
2. **Return on Found** — if raw SQL returns rows with valid `wafer_key > 0`, the result is used immediately
3. **Fallback to lot-wafer-lookup API** — only records that raw SQL couldn't resolve (NotFound or Error) are sent to `POST /v1/key/lot-wafer-lookup`

The configuration property `exensio.prefer-raw-sql: true` (default) exists but the code does not gate on it — raw SQL is always attempted first unconditionally.

## SQL Queries

Both single and batch variants share the same core query structure. The only difference is the WHERE clause.

### Single-Record SQL

Built by `ExensioClient.buildSingleRawSql()`. Parameters (`pgcKey`, `lot`, `wafer`, identifier tokens extracted from `filename`/`metadataId`/`dataId`) are string-interpolated directly into the SQL.

```sql
SELECT * FROM (
  SELECT l.lot_id AS lot_id, NVL(w.wf_id,'') AS wafer_id,
         ol.lot_key AS lot_key, NVL(w.wf_key,0) AS wafer_key,
         NVL(ol.pg_key,0) AS pg_key, NVL(p.ppid,'') AS ppid,
         NVL(de.file_name,'') AS file_name,
         NVL(TO_CHAR(ol.end_time, 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),'') AS end_time
  FROM op_log ol
  JOIN lot l ON l.lot_key = ol.lot_key
  JOIN program p ON p.pg_key = ol.pg_key
  LEFT JOIN wafer w ON w.wf_key = ol.wf_key
  LEFT JOIN df_export de ON de.lg_key = ol.lg_key
    AND (w.wf_key IS NULL OR de.wf_key = w.wf_key)
  WHERE ol.pgc_key = <pgcKey>
    AND UPPER(TRIM(l.lot_id)) = UPPER(TRIM('<lot>'))
    AND UPPER(TRIM(NVL(w.wf_id,''))) = UPPER(TRIM('<wafer>'))
    AND (UPPER(NVL(de.file_name,'')) LIKE '%<token>%' ESCAPE '\'
         OR ...)
  ORDER BY ol.end_time DESC
) WHERE ROWNUM <= <rowLimit>
```

- `pgcKey` — resolved from `data_type` via `DataTypePgcKeyMapper`:
  - PROBE → 1, FT → 2, WMAP → 4, PCM → 5, DEFECT → 14
  - Wafer-blank records default to 2, wafer-present to 1
- `lot` — from the stage record
- `wafer` — may be blank/NA; clause is omitted when absent
- identifier tokens — `LIKE` clauses on `de.file_name` built from `filename`, `metadataId`, and `dataId`
- `rowLimit` — configured by `exensio.raw-sql-row-limit` (default 200)

### Batch SQL

Built by `ExensioClient.buildBatchRawSql()`. Individual WHERE clauses (one per record) are OR'd together:

```sql
SELECT * FROM (
  SELECT l.lot_id AS lot_id, NVL(w.wf_id,'') AS wafer_id,
         ol.lot_key AS lot_key, NVL(w.wf_key,0) AS wafer_key,
         NVL(ol.pg_key,0) AS pg_key, NVL(p.ppid,'') AS ppid,
         NVL(de.file_name,'') AS file_name,
         NVL(TO_CHAR(ol.end_time, 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),'') AS end_time
  FROM op_log ol
  JOIN lot l ON l.lot_key = ol.lot_key
  JOIN program p ON p.pg_key = ol.pg_key
  LEFT JOIN wafer w ON w.wf_key = ol.wf_key
  LEFT JOIN df_export de ON de.lg_key = ol.lg_key
    AND (w.wf_key IS NULL OR de.wf_key = w.wf_key)
  WHERE (<clause1> OR <clause2> OR ...)
  ORDER BY ol.end_time DESC
) WHERE ROWNUM <= <rowLimit>
```

Each clause looks like:
```sql
(ol.pgc_key = <pgcKey>
 AND UPPER(TRIM(l.lot_id)) = UPPER(TRIM('<lot>'))
 AND UPPER(TRIM(NVL(w.wf_id,''))) = UPPER(TRIM('<wafer>'))
 AND (UPPER(NVL(de.file_name,'')) LIKE '%<token>%' ESCAPE '\' OR ...))
```

## Result Selection

When raw SQL returns multiple rows, the best match is selected by:

1. **Score by identifier match** — rows whose `file_name` contains more of the identifier tokens get a higher score
2. **Tie-break by end_time** — among rows with the same score, the one closest to the target `end_time` wins

## Fallback: lot-wafer-lookup API

Records not resolved by raw SQL fall through to `POST /v1/key/lot-wafer-lookup` with `pgc_key`, `lot_ids[]`, and `wafer_ids[]`. This uses Exensio's structured lookup rather than raw SQL.

## Circuit Breaker & Retries

- **5 consecutive failures** (any lookup method) open the circuit breaker
- **60-second reset** before half-open retry
- **Exponential backoff** on transient errors (HTTP 429, 5xx, I/O errors) for both single and batch lookups
- **401 auto-recovery** — single lookup retries once with a fresh token; batch retries up to `retry-max-attempts` times
