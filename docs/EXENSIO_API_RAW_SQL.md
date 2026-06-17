# Exensio API Integration & Raw SQL Endpoint Documentation

This document describes how the ExensioReload backend connects to the Exensio API, its use of the raw SQL endpoint, and the exact query templates passed during single and batch lot-wafer lookups.

---

## 1. Exensio API Connection Flow

The Exensio API integration is primarily implemented across the following components:
* [ExensioAuthService](file:///c:/Users/fg8n8x/Desktop/eta/wip/exensioreload/backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/service/ExensioAuthService.java): Manages authenticating and caching the session token.
* [ExensioClient](file:///c:/Users/fg8n8x/Desktop/eta/wip/exensioreload/backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/service/ExensioClient.java): Orchestrates and executes HTTP requests using a shared standard Java `HttpClient`.
* [ExensioProperties](file:///c:/Users/fg8n8x/Desktop/eta/wip/exensioreload/backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/config/ExensioProperties.java): Binds configuration properties under the prefix `exensio` from `application.yml`.

### Authentication & Token Management
1. **Login**: [ExensioAuthService](file:///c:/Users/fg8n8x/Desktop/eta/wip/exensioreload/backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/service/ExensioAuthService.java) sends a `POST` request to `POST /v1/session/login` with the following JSON payload:
   ```json
   {
     "username": "<username>",
     "password": "<password>",
     "dbname": "<dbname>",
     "dbschema": "<schema>"
   }
   ```
   * The database name (`dbname`) and schema (`dbschema`) are resolved dynamically based on the active environment (`QA` or `PROD`) and configuration. Primary schema defaults to `PRODUCTION`, with fallback schema retry to `SANDBOX` on failure.
2. **Bearer Token Caching**: Upon successful login, the returned token is cached. Subsequent outgoing requests from [ExensioClient](file:///c:/Users/fg8n8x/Desktop/eta/wip/exensioreload/backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/service/ExensioClient.java) include this cached token in the headers:
   ```http
   Authorization: Bearer <cached_token>
   ```
3. **HTTP 401 Retry**: If a call returns an HTTP 401 Unauthorized status, the client invalidates the cached token, requests a new one from [ExensioAuthService](file:///c:/Users/fg8n8x/Desktop/eta/wip/exensioreload/backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/service/ExensioAuthService.java), and retries the operation exactly once.
4. **Logout**: On application shutdown, a logout call is dispatched to `POST /v1/session/logout` to gracefully release resources on the server side.

---

## 2. Raw SQL Endpoint Usage

The application is designed to prefer raw SQL lookups for performance and exact matching before resorting to standard endpoints.
* **Property Toggle**: Driven by `exensio.prefer-raw-sql` (defaults to `true`).
* **Endpoint**: `POST /v1/key/raw-sql`
* **JSON Body**:
  ```json
  {
    "sql": "<SQL_Query_String>"
  }
  ```
* **Fallback Behavior**: When calling single or batch lookups, it initiates raw SQL queries. If the raw SQL endpoint doesn't return a record (or fails), the client falls back to the standard endpoint `POST /v1/key/lot-wafer-lookup`.

---

## 3. SQL Query Templates

### A. Single Record Lookup Query (`buildSingleRawSql`)
Used during single-record validations (e.g., matching a lot and wafer).

```sql
SELECT * FROM (
    SELECT l.lot_id AS lot_id, 
           NVL(w.wf_id,'') AS wafer_id,
           ol.lot_key AS lot_key, 
           NVL(w.wf_key,0) AS wafer_key,
           NVL(ol.pg_key,0) AS pg_key, 
           NVL(p.ppid,'') AS ppid,
           NVL(de.file_name,'') AS file_name,
           NVL(TO_CHAR(ol.end_time, 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),'') AS end_time
    FROM op_log ol
    JOIN lot l ON l.lot_key = ol.lot_key
    JOIN program p ON p.pg_key = ol.pg_key
    LEFT JOIN wafer w ON w.wf_key = ol.wf_key
    LEFT JOIN df_export de ON de.lg_key = ol.lg_key AND (w.wf_key IS NULL OR de.wf_key = w.wf_key)
    WHERE ol.pgc_key = <pgcKey> 
      AND UPPER(TRIM(l.lot_id)) = UPPER(TRIM('<escaped_lot>'))
      /* Wafer condition: only appended if wafer is non-blank/not 'NA' */
      AND UPPER(TRIM(NVL(w.wf_id,''))) = UPPER(TRIM('<escaped_wafer>'))
      /* Identifier LIKE conditions: matching filename / metadataId / dataId */
      AND (UPPER(NVL(de.file_name,'')) LIKE '%<escaped_id_1>%' ESCAPE '\\' OR ...)
    ORDER BY ol.end_time DESC
) WHERE ROWNUM <= <row_limit>
```

* `<row_limit>` is bound to `exensio.raw-sql-row-limit` (default: `200`).
* The identifier LIKE clauses match elements extracted from the target metadata, e.g., the filename base or the metadata ID, to pinpoint correct operation log records.

### B. Batch Record Lookup Query (`buildBatchRawSql`)
Used during batch processing to validate multiple lot/wafer records in a single query by constructing a group of `OR` clauses.

```sql
SELECT * FROM (
    SELECT l.lot_id AS lot_id, 
           NVL(w.wf_id,'') AS wafer_id,
           ol.lot_key AS lot_key, 
           NVL(w.wf_key,0) AS wafer_key,
           NVL(ol.pg_key,0) AS pg_key, 
           NVL(p.ppid,'') AS ppid,
           NVL(de.file_name,'') AS file_name,
           NVL(TO_CHAR(ol.end_time, 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),'') AS end_time
    FROM op_log ol
    JOIN lot l ON l.lot_key = ol.lot_key
    JOIN program p ON p.pg_key = ol.pg_key
    LEFT JOIN wafer w ON w.wf_key = ol.wf_key
    LEFT JOIN df_export de ON de.lg_key = ol.lg_key AND (w.wf_key IS NULL OR de.wf_key = w.wf_key)
    WHERE (
        (ol.pgc_key = <pgcKey_1> AND UPPER(TRIM(l.lot_id)) = UPPER(TRIM('<escaped_lot_1>')) [AND UPPER(TRIM(NVL(w.wf_id,''))) = UPPER(TRIM('<escaped_wafer_1>'))] AND (UPPER(NVL(de.file_name,'')) LIKE '%<escaped_id_1_1>%' ESCAPE '\\' OR ...))
        OR
        (ol.pgc_key = <pgcKey_2> AND UPPER(TRIM(l.lot_id)) = UPPER(TRIM('<escaped_lot_2>')) [AND UPPER(TRIM(NVL(w.wf_id,''))) = UPPER(TRIM('<escaped_wafer_2>'))] AND (UPPER(NVL(de.file_name,'')) LIKE '%<escaped_id_2_1>%' ESCAPE '\\' OR ...))
        /* ... OR clauses repeat for each record in the batch */
    )
    ORDER BY ol.end_time DESC
) WHERE ROWNUM <= <row_limit>
```

---

## 4. Related Properties

Key properties in `application.yml` (under prefix `exensio`):

| Property Name | Default Value | Description |
|---|---|---|
| `exensio.prefer-raw-sql` | `true` | Prefer the raw SQL endpoint over standard endpoint lookups. |
| `exensio.raw-sql-timeout-seconds` | `20` | HTTP request timeout for executing the raw SQL lookup. |
| `exensio.raw-sql-row-limit` | `200` | Limits the number of records returned by the Oracle ROWNUM clause in the queries. |
| `exensio.log-request-payloads` | `false` | If true, logs the built SQL payloads and Exensio responses. |

---

## 5. Schema Analysis & Query Improvement (from dataPOWER Data Dictionary)

### 5.1 Key Table Roles (per dataPOWER Data Dictionary v9.0)

| Table | Role in Current Query | Notes from Data Dictionary |
|---|---|---|
| `OP_LOG` | Primary log — one row per data file processed | `lg_key` = serial PK; `pgc_key` → `PROG_CLASS`; `wf_key` = wafer FK **only used for metrology data** |
| `LOT` | Lot identity | `lot_id` is the string lot identifier; `lot_key` is numeric PK |
| `PROGRAM` | Program/PPID definition | `ppid` is the test program identifier; `pg_key` is FK into `OP_LOG.pg_key` |
| `WAFER` | Wafer identity | `wf_key` PK; `wf_id` is the string wafer identifier |
| `WF_LOG` | **Per-wafer log** for Probe/FT/Esort | Joins to `OP_LOG` via `lg_key`; contains one row per wafer in the data file. **NOT used by Metrology, bitMAP, Events, Defect, or LEH readers.** |
| `DF_EXPORT` | defectMAP export records | `file_name` = exported defect map file; linked to `OP_LOG` via `lg_key` and `WAFER` via `wf_key`. **This is a defect-map-specific table.** |
| `PROG_CLASS` | Program class (type of test) | `pgc_key` values: **1=Probe**, **2=FT/Final Test**, **4=WaferMap/BinMap**, **5=PCM**, **14=Defect** |

### 5.2 Current Query Issues

#### Issue 1 — Wrong wafer join for Probe/FT programs
**Current:** `LEFT JOIN wafer w ON w.wf_key = ol.wf_key`

Per the data dictionary, `OP_LOG.wf_key` is **only populated for metrology data** (metrology reader sets it). For Probe (`pgc_key=1`) and FT (`pgc_key=2`) programs, the wafer relationship is tracked through **`WF_LOG`**, which joins on `lg_key`. Therefore, for probe/FT lookups, `ol.wf_key` will always be `NULL` and the wafer filter silently does nothing — the query returns rows regardless of the requested wafer.

**Correct approach (Probe/FT):** Join `WF_LOG wfl ON wfl.lg_key = ol.lg_key`, then join `WAFER w ON w.wf_key = wfl.wf_key`.

#### Issue 2 — DF_EXPORT is a defect-map table, not a general file-name source
**Current:** `LEFT JOIN df_export de ON de.lg_key = ol.lg_key AND (w.wf_key IS NULL OR de.wf_key = w.wf_key)`

`DF_EXPORT` stores file exports generated by the **defectMAP** module (`pgc_key=14`). For `pgc_key=1` (Probe) and `pgc_key=2` (FT), there will be no rows in `DF_EXPORT` and `de.file_name` will always be `NULL`. This means the LIKE-based identifier matching clause (`AND (UPPER(NVL(de.file_name,'')) LIKE '%...%')`) **will never match** for probe/FT programs, causing the raw SQL lookup to return 0 rows and silently falling back to the standard `lot-wafer-lookup` endpoint every time.

#### Issue 3 — PPID test-phase check is done in Java after the SQL call
Currently the PPID suffix validation (`_<testPhase>`) is applied in Java code after retrieving all candidate rows. Pushing this filter into SQL reduces data transfer and makes matching intent clearer.

---

### 5.3 Improved Single-Record Lookup Query

This version correctly handles both **Probe/FT** (wafer via `WF_LOG`) and **Defect** (file name via `DF_EXPORT`) program classes, and optionally filters PPID by test phase suffix in-database.

```sql
-- ============================================================
-- Improved single-record raw SQL lookup
-- Handles: Probe (pgc_key=1), FT (pgc_key=2), PCM (pgc_key=5)
--           via WF_LOG for wafer matching
--           and DF_EXPORT for defect file name (pgc_key=14)
-- ============================================================
SELECT * FROM (

    -- === Path A: Probe / FT / PCM (wafer via WF_LOG) ===
    SELECT
        l.lot_id           AS lot_id,
        NVL(w.wf_id, '')   AS wafer_id,
        ol.lot_key         AS lot_key,
        NVL(w.wf_key, 0)   AS wafer_key,
        NVL(ol.pg_key, 0)  AS pg_key,
        NVL(p.ppid, '')    AS ppid,
        ''                 AS file_name,       -- no DF_EXPORT for probe/FT
        NVL(TO_CHAR(ol.end_time, 'YYYY-MM-DD"T"HH24:MI:SS"Z"'), '') AS end_time
    FROM op_log ol
    JOIN lot     l  ON l.lot_key   = ol.lot_key
    JOIN program p  ON p.pg_key    = ol.pg_key
    JOIN wf_log  wfl ON wfl.lg_key = ol.lg_key          -- correct wafer join for probe/FT
    JOIN wafer   w   ON w.wf_key   = wfl.wf_key
    WHERE ol.pgc_key IN (1, 2, 5)                        -- Probe, FT, PCM
      AND UPPER(TRIM(l.lot_id)) = UPPER(TRIM('<escaped_lot>'))
      /* Wafer filter — only when wafer is provided */
      AND UPPER(TRIM(w.wf_id)) = UPPER(TRIM('<escaped_wafer>'))
      /* PPID suffix filter — only when testPhase is provided */
      AND UPPER(p.ppid) LIKE '%_<UPPER_TEST_PHASE>'
      /* No file_name match needed here — metadataId/dataId used in fallback */

    UNION ALL

    -- === Path B: Defect (wafer via DF_EXPORT.wf_key, file_name from DF_EXPORT) ===
    SELECT
        l.lot_id               AS lot_id,
        NVL(w.wf_id, '')       AS wafer_id,
        ol.lot_key             AS lot_key,
        NVL(w.wf_key, 0)       AS wafer_key,
        NVL(ol.pg_key, 0)      AS pg_key,
        NVL(p.ppid, '')        AS ppid,
        NVL(de.file_name, '')  AS file_name,
        NVL(TO_CHAR(ol.end_time, 'YYYY-MM-DD"T"HH24:MI:SS"Z"'), '') AS end_time
    FROM op_log   ol
    JOIN lot      l  ON l.lot_key  = ol.lot_key
    JOIN program  p  ON p.pg_key   = ol.pg_key
    JOIN df_export de ON de.lg_key = ol.lg_key
    LEFT JOIN wafer w ON w.wf_key  = de.wf_key
    WHERE ol.pgc_key = 14                                 -- Defect only
      AND UPPER(TRIM(l.lot_id)) = UPPER(TRIM('<escaped_lot>'))
      AND (UPPER(NVL(de.file_name,'')) LIKE '%<escaped_id_1>%' ESCAPE '\\'
           OR UPPER(NVL(de.file_name,'')) LIKE '%<escaped_id_2>%' ESCAPE '\\')

    ORDER BY end_time DESC

) WHERE ROWNUM <= <row_limit>
```

> [!NOTE]
> For Probe/FT programs (`pgc_key` 1, 2, 5), the file-name matching is **not applicable** — the identifier match (metadataId / dataId) is best deferred to the standard `lot-wafer-lookup` fallback endpoint, which resolves records by lot + wafer keys natively.

---

### 5.4 Lot-Level vs Wafer-Level Matching (Recommended Default)

The query needs to handle two distinct matching modes depending on **program class** and **whether a wafer ID was provided**:

| Condition | Matching Mode | Reasoning |
|---|---|---|
| `pgc_key = 2` (FT/Final Test) | **Lot-level** | FT data is recorded per lot, not per wafer. `WF_LOG` rows may not exist. |
| Wafer not provided (blank / "NA") | **Lot-level** | Caller has no wafer context — match the lot and return the best `end_time` candidate. |
| All other pgc_keys + wafer provided | **Wafer-level** | Filter to the exact wafer via `WF_LOG`. |

#### Strategy: LEFT JOIN + Conditional WHERE

Use `LEFT JOIN` on `WF_LOG`/`WAFER` so the query returns lot-level rows even when there is no wafer match, then gate the wafer filter with an `OR` condition:

```sql
SELECT * FROM (
    SELECT
        l.lot_id             AS lot_id,
        NVL(w.wf_id, '')     AS wafer_id,
        ol.lot_key           AS lot_key,
        NVL(wfl.wf_key, 0)  AS wafer_key,
        NVL(ol.pg_key, 0)    AS pg_key,
        NVL(p.ppid, '')      AS ppid,
        NVL(TO_CHAR(ol.end_time, 'YYYY-MM-DD"T"HH24:MI:SS"Z"'), '') AS end_time
    FROM op_log  ol
    JOIN lot     l    ON l.lot_key   = ol.lot_key
    JOIN program p    ON p.pg_key    = ol.pg_key
    LEFT JOIN wf_log  wfl ON wfl.lg_key = ol.lg_key   -- LEFT: may be absent for FT/lot-level
    LEFT JOIN wafer   w   ON w.wf_key   = wfl.wf_key
    WHERE ol.pgc_key = <pgcKey>
      AND UPPER(TRIM(l.lot_id)) = UPPER(TRIM('<escaped_lot>'))
      -- Wafer filter: bypass when pgc_key=2 (FT is lot-level) OR no wafer was provided
      AND (
            ol.pgc_key = 2                                              -- FT → lot-level always
            OR '<escaped_wafer>' IS NULL                                -- no wafer → lot-level
            OR UPPER(TRIM(NVL(w.wf_id, ''))) = UPPER(TRIM('<escaped_wafer>'))  -- wafer match
          )
      /* Optional: PPID suffix for test phase */
      -- AND UPPER(p.ppid) LIKE '%_<TEST_PHASE>'
    ORDER BY ol.end_time DESC
) WHERE ROWNUM <= <row_limit>
```

> [!NOTE]
> In practice, `'<escaped_wafer>' IS NULL` cannot be a literal SQL expression — the Java builder simply **omits the entire wafer AND clause** when `waferBlank=true`, resulting in the same effect. The `OR ol.pgc_key = 2` branch handles the FT case without needing any Java-side conditional.

#### How the Java builder should apply this logic

```
boolean lotLevel = waferBlank || pgcKey == 2;  // lot-level when FT or no wafer

if (lotLevel) {
    // Omit wafer WHERE clause entirely; use LEFT JOIN
    sql = buildLotLevelQuery(lotId, pgcKey, ...);
} else {
    // Include wafer WHERE clause; still use LEFT JOIN for safety
    sql = buildWaferLevelQuery(lotId, escapedWafer, pgcKey, ...);
}
```

**Why this is better than the current query:**
| Aspect | Current | Improved |
|---|---|---|
| Wafer join source | `OP_LOG.wf_key` (NULL for probe/FT) | `WF_LOG` via `lg_key` (correct) |
| FT lot-level support | No — wafer filter always applied | Yes — `pgc_key=2` bypasses wafer clause |
| Wafer absent support | Silent no-op (wrong table) | Explicit lot-level path |
| File-name join | `DF_EXPORT` (defect-only table) | Removed for probe/FT path |
| Data returned | All rows for the lot regardless of wafer | Correctly scoped to lot or wafer as appropriate |

---

### 5.5 `pgc_key` to Data Type Mapping Reference

From [DataTypePgcKeyMapper](file:///c:/Users/fg8n8x/Desktop/eta/wip/exensioreload/backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/service/DataTypePgcKeyMapper.java), consistent with the dataPOWER data dictionary:

| `pgc_key` | Data Type | Wafer Source | File Name Source |
|---|---|---|---|
| 1 | PROBE | `WF_LOG` → `WAFER.wf_id` | N/A |
| 2 | FT / FINAL TEST | `WF_LOG` → `WAFER.wf_id` | N/A |
| 4 | MAP / BIN MAP / WMAP | `WF_LOG` → `WAFER.wf_id` | N/A |
| 5 | PCM | `WF_LOG` → `WAFER.wf_id` | N/A |
| 14 | DEFECT | `DF_EXPORT.wf_key` → `WAFER.wf_id` | `DF_EXPORT.file_name` |
