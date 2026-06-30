# Confirm Raw Data Loaded by Lot

Query to verify whether raw-level data (OP_LOG / RES…) has been loaded for a given list of lots. Returns schema name, lot, wafer ID/number, program, program revision, filename, insert time, and end time.

## SQL Query

```sql
SELECT 
    SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA')  AS schema_name,
    l.lot_id,
    w.wf_id                                    AS wafer_id,
    w.wf_num                                   AS wafer_num,
    p.ppid                                     AS program_name,
    pr.revision                                AS program_rev,
    rf.file_name,
    rp.name1 || rp.name2 || rp.name3 || rp.name4  AS file_path,
    ol.insert_time,
    ol.end_time
FROM 
    op_log ol
    JOIN program    p   ON p.pg_key   = ol.pg_key
    JOIN lot        l   ON l.lot_key  = ol.lot_key
    LEFT JOIN wf_log   wl  ON wl.lg_key  = ol.lg_key
    LEFT JOIN wafer    w   ON w.wf_key   = wl.wf_key
    LEFT JOIN prog_rev pr  ON pr.pg_key  = p.pg_key
    LEFT JOIN dp_log   dl  ON dl.start_time = ol.insert_time
    LEFT JOIN raw_file rf  ON rf.rawfile_key = dl.rawfile_key
    LEFT JOIN raw_path rp  ON rp.path_key   = rf.path_key
WHERE 
    l.lot_id IN (
        -- ========================================
        -- REPLACE WITH YOUR LOT LIST
        -- ========================================
        'LOT_001',
        'LOT_002',
        'LOT_003'
    )
ORDER BY 
    l.lot_id,
    w.wf_id,
    ol.insert_time;
```

## Alternate: Using Metrology (wafer on OP_LOG directly)

For metrology programs, wafer is linked directly on `OP_LOG.wf_key` instead of through `WF_LOG`. Use this version if you also need metrology data:

```sql
SELECT 
    SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA')  AS schema_name,
    l.lot_id,
    COALESCE(w_direct.wf_id, w_wflog.wf_id)   AS wafer_id,
    COALESCE(w_direct.wf_num, w_wflog.wf_num)  AS wafer_num,
    p.ppid                                      AS program_name,
    pr.revision                                 AS program_rev,
    rf.file_name,
    rp.name1 || rp.name2 || rp.name3 || rp.name4  AS file_path,
    ol.insert_time,
    ol.end_time
FROM 
    op_log ol
    JOIN program     p          ON p.pg_key    = ol.pg_key
    JOIN lot         l          ON l.lot_key   = ol.lot_key
    LEFT JOIN wafer  w_direct   ON w_direct.wf_key = ol.wf_key
    LEFT JOIN wf_log wl         ON wl.lg_key   = ol.lg_key
    LEFT JOIN wafer  w_wflog    ON w_wflog.wf_key  = wl.wf_key
    LEFT JOIN prog_rev pr       ON pr.pg_key   = p.pg_key
    LEFT JOIN dp_log   dl       ON dl.start_time = ol.insert_time
    LEFT JOIN raw_file rf       ON rf.rawfile_key = dl.rawfile_key
    LEFT JOIN raw_path rp       ON rp.path_key   = rf.path_key
WHERE 
    l.lot_id IN (
        -- ========================================
        -- REPLACE WITH YOUR LOT LIST
        -- ========================================
        'LOT_001',
        'LOT_002',
        'LOT_003'
    )
ORDER BY 
    l.lot_id,
    COALESCE(w_direct.wf_id, w_wflog.wf_id),
    ol.insert_time;
```

## Tables Used

| Table | Key Columns | Purpose |
|---|---|---|
| `OP_LOG` | `lg_key`, `pg_key`, `lot_key`, `wf_key`, `insert_time`, `end_time` | Main load log — one row per raw data file loaded |
| `PROGRAM` | `pg_key`, `ppid` | Program name (PPID) |
| `LOT` | `lot_key`, `lot_id` | Lot identification |
| `WF_LOG` | `lg_key`, `wf_key` | Links OP_LOG entries to wafers (non-metrology) |
| `WAFER` | `wf_key`, `wf_id`, `wf_num` | Wafer ID and wafer number |
| `PROG_REV` | `prev_key`, `pg_key`, `revision` | Program revision info |
| `DP_LOG` | `dplg_key`, `rawfile_key`, `start_time` | Function/load usage log — links to raw file |
| `RAW_FILE` | `rawfile_key`, `file_name` | Stores the data file name |
| `RAW_PATH` | `path_key`, `name1`–`name4` | Stores the directory path components for the file |

## Join Logic

| Join | Reason |
|---|---|
| `OP_LOG → PROGRAM` | Get program name (PPID) |
| `OP_LOG → LOT` | Get lot ID and filter by user-provided lot list |
| `OP_LOG → WF_LOG → WAFER` | Get wafer ID/number (LEFT JOIN — not all entries have wafer-level data) |
| `OP_LOG → WAFER` (direct) | Metrology programs link wafer directly on OP_LOG.wf_key |
| `PROGRAM → PROG_REV` | Get program revision (LEFT JOIN — not all programs have revisions) |
| `OP_LOG → DP_LOG` | Match load log to function usage log (via `start_time`) |
| `DP_LOG → RAW_FILE` | Get the source data file name |
| `RAW_FILE → RAW_PATH` | Get the directory path for the file |

## Output Columns

| Column | Source | Description |
|---|---|---|
| `schema_name` | Oracle context | Current database schema name |
| `lot_id` | `LOT.lot_id` | Lot identifier |
| `wafer_id` | `WAFER.wf_id` | Wafer ID (NULL if lot-level only) |
| `wafer_num` | `WAFER.wf_num` | Wafer number (NULL if lot-level only) |
| `program_name` | `PROGRAM.ppid` | Production Program ID |
| `program_rev` | `PROG_REV.revision` | Program revision |
| `file_name` | `RAW_FILE.file_name` | Source data file name |
| `file_path` | `RAW_PATH.name1‑4` | Full directory path of the source file |
| `insert_time` | `OP_LOG.insert_time` | When data was loaded into the database |
| `end_time` | `OP_LOG.end_time` | End time of the process step |

## Notes

- **Schema name**: Uses Oracle `SYS_CONTEXT` to return the current schema. For Informix, replace with `DBINFO('dbname')`.
- **Wafer info**: `LEFT JOIN` on `WF_LOG`/`WAFER` since not all program classes produce wafer-level data (e.g., `Final_Test`, `Summary`).
- **Program revision**: `LEFT JOIN` on `PROG_REV` since revision tracking is optional. If a program has multiple revisions, you may get multiple rows — add `AND pr.prev_key = (SELECT MAX(...))` to get only the latest.
- **Filename join**: `DP_LOG` does not have a direct FK to `OP_LOG`. The join uses `dl.start_time = ol.insert_time` as a correlation. If your schema version has `OP_LOG.run_id` mapped to `DP_LOG.dplg_key`, replace the join with `dl.dplg_key = ol.run_id` for a more reliable match.
- **File path**: Constructed by concatenating `RAW_PATH.name1` through `name4`. Some path components may be NULL — use `NVL()` or `COALESCE()` if needed: `COALESCE(rp.name1,'') || COALESCE(rp.name2,'') || COALESCE(rp.name3,'') || COALESCE(rp.name4,'')`.
- **If no rows returned** for a lot, it means no raw data has been loaded for that lot.
- **Row count**: Each OP_LOG entry represents one data file load. Multiple rows per lot are expected (one per wafer/load event).


-----

Updated 

query_confirm_raw_data_loaded.md
. Here's what was added:

New columns:

file_name — from RAW_FILE.file_name (the source data file name)
file_path — concatenated from RAW_PATH.name1 through name4 (the directory path)
New joins:

OP_LOG → DP_LOG — correlated via start_time = insert_time (see note below)
DP_LOG → RAW_FILE — via rawfile_key
RAW_FILE → RAW_PATH — via path_key
Important caveat: There's no direct FK between OP_LOG and DP_LOG in the schema. The join uses dl.start_time = ol.insert_time as a temporal correlation. If your schema version has OP_LOG.run_id mapped to DP_LOG.dplg_key, use that instead for an exact match. You may want to verify which join works in your environment before using at scale.