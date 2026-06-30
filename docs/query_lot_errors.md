# Lot / Wafer Error Queries

There are two main places where errors related to lots and wafers are logged in the database:
1. **UPDATE_STATS** (and **DF_UPSTAT**): Records errors during statistics generation or data summarization at the lot or wafer level.
2. **DP_LOG / ERROR_MESSAGE**: Records system and reader-level errors when loading raw data files.

Below are the queries to extract errors from both sources for specific lots.

## 1. Statistics / Summarization Errors (`UPDATE_STATS`)

This query retrieves errors that occurred when calculating lot or wafer statistics (e.g., UpStat utility errors).

```sql
SELECT 
    l.lot_id,
    w.wf_id                                    AS wafer_id,
    w.wf_num                                   AS wafer_num,
    p.ppid                                     AS program_name,
    MAX(rf.file_name)                          AS latest_source_file,
    us.error_code,
    us.err_msg,
    CASE us.flag
        WHEN 1 THEN 'Wafer-level Stats'
        WHEN 2 THEN 'Lot-level Stats'
        WHEN 3 THEN 'Source-lot-level Stats'
        WHEN 4 THEN 'Wafer-level Binning'
        WHEN 5 THEN 'Lot-level Binning'
        WHEN 6 THEN 'Source-lot-level Binning'
        ELSE 'Other/Unknown (' || us.flag || ')'
    END AS action_type
FROM 
    update_stats us
    JOIN lot l       ON l.lot_key = us.lot_key
    JOIN program p   ON p.pg_key = us.pg_key
    LEFT JOIN wafer w ON w.wf_key = us.wf_key
    
    -- Link out to get the most recent filename for the lot
    LEFT JOIN op_log ol ON ol.lot_key = us.lot_key
    LEFT JOIN dp_log dl ON dl.start_time = ol.insert_time
    LEFT JOIN raw_file rf ON rf.rawfile_key = dl.rawfile_key
WHERE 
    us.error_code != 0  -- Filter for actual errors/warnings
    AND l.lot_id IN (
        -- ========================================
        -- REPLACE WITH YOUR LOT LIST
        -- ========================================
        'LOT_001',
        'LOT_002',
        'LOT_003'
    )
GROUP BY 
    l.lot_id,
    w.wf_id,
    w.wf_num,
    p.ppid,
    us.error_code,
    us.err_msg,
    us.flag
    w.wf_id;
```

## 2. Raw Data Load Errors (`DP_LOG` & `ERROR_MESSAGE`)

This query finds file load errors by connecting the raw data load events (`OP_LOG`) to the system process logs (`DP_LOG`) and piecing together the full error message from `STRING_HOLDER`.

```sql
SELECT 
    l.lot_id,
    w.wf_id                                    AS wafer_id,
    p.ppid                                     AS program_name,
    rf.file_name,
    dl.error_code,
    COALESCE(sh1.str_value, '') || 
    COALESCE(sh2.str_value, '') || 
    COALESCE(sh3.str_value, '') || 
    COALESCE(sh4.str_value, '')                AS full_error_message,
    dl.start_time                              AS error_time
FROM 
    op_log ol
    JOIN lot l       ON l.lot_key = ol.lot_key
    JOIN program p   ON p.pg_key = ol.pg_key
    LEFT JOIN wf_log wl ON wl.lg_key = ol.lg_key
    LEFT JOIN wafer w ON w.wf_key = wl.wf_key
    
    -- Link OP_LOG to DP_LOG using start time correlation (or run_id if your schema supports it)
    JOIN dp_log dl   ON dl.start_time = ol.insert_time
    
    -- Get the filename if available
    LEFT JOIN raw_file rf ON rf.rawfile_key = dl.rawfile_key
    
    -- Link to ERROR_MESSAGE and STRING_HOLDER to construct the error text
    JOIN error_message em ON em.msg_key = dl.msg_key
    LEFT JOIN string_holder sh1 ON sh1.str_key = em.str_key1
    LEFT JOIN string_holder sh2 ON sh2.str_key = em.str_key2
    LEFT JOIN string_holder sh3 ON sh3.str_key = em.str_key3
    LEFT JOIN string_holder sh4 ON sh4.str_key = em.str_key4
WHERE 
    dl.error_code != 0  -- Filter for actual errors
    AND l.lot_id IN (
        -- ========================================
        -- REPLACE WITH YOUR LOT LIST
        -- ========================================
        'LOT_001',
        'LOT_002',
        'LOT_003'
    )
ORDER BY 
    l.lot_id,
    dl.start_time DESC;
```

## Tables Used

| Table | Purpose |
|---|---|
| `UPDATE_STATS` | Contains summarization and calculation errors. Direct links to `lot_key` and `wf_key`. |
| `DP_LOG` | System-level function and reader log. Contains `error_code` and `msg_key`. |
| `ERROR_MESSAGE` | Bridges `DP_LOG` to `STRING_HOLDER` keys. |
| `STRING_HOLDER` | Exensio splits long text (like error messages) into chunks across `str_key1-4` to bypass column length limits. |

## Notes

- **UPDATE_STATS**: If an error occurred during statistical recalculation, the exact module and failure reason will be in `err_msg`. An `error_code = 0` means Success, anything else (like `1` for warning, `3` for error, etc.) indicates an issue.
- **Message Reconstruction**: Exensio databases split long strings across multiple `STRING_HOLDER` records. The `COALESCE` logic safely stitches `str_key1` through `str_key4` back together.
- **DP_LOG Linkage**: As noted previously, the `DP_LOG` table is joined using a temporal correlation (`dl.start_time = ol.insert_time`). If your database version maps `OP_LOG.run_id` to `DP_LOG.dplg_key`, change the join for 100% accuracy (`dl.dplg_key = ol.run_id`).
