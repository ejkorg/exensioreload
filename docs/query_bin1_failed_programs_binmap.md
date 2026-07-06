# Programs with Bin 1 Set as Failed — Bin Map Programs Only (pgc_key = 4)

Query to retrieve all **bin map programs** (`pgc_key = 4`) where bin 1 is designated as a fail bin, including the datetime the program was first loaded. Optional filters narrow results by fab and/or test facility.

## SQL Query

```sql
SELECT 
    p.pg_key,
    p.ppid                          AS program_name,
    p.prog_desc                     AS program_description,
    f.fab_name,
    eq_fac.eq_name                  AS facility_name,
    hb.bin_name                     AS bin1_name,
    hb.pass_fail                    AS bin1_pass_fail,
    MIN(ol.insert_time)             AS first_loaded_datetime,
    MIN(ol.start_time)              AS first_start_time
FROM 
    program p
    JOIN hist_bin hb       ON hb.pg_key    = p.pg_key
                           AND hb.bin_num   = 1
                           AND hb.pass_fail = 'F'
    LEFT JOIN prog2tech p2t  ON p2t.pg_key   = p.pg_key
    LEFT JOIN tech2fab t2f   ON t2f.tech_key = p2t.tech_key
    LEFT JOIN fab f          ON f.fab_key    = t2f.fab_key
    LEFT JOIN op_log ol      ON ol.pg_key    = p.pg_key
    LEFT JOIN equipment eq_fac ON eq_fac.eq_key = ol.eqkey6
WHERE 
    p.pgc_key = 4                   -- bin map programs only
    -- ========================================
    -- OPTIONAL FILTERS — uncomment as needed
    -- ========================================
    -- AND f.fab_name = 'CZ4:TESLA FAB'
    -- AND eq_fac.eq_name = 'CZ4:TESLA FAB'
    -- AND p.accept_data = 1
GROUP BY 
    p.pg_key,
    p.ppid,
    p.prog_desc,
    f.fab_name,
    eq_fac.eq_name,
    hb.bin_name,
    hb.pass_fail
ORDER BY 
    first_loaded_datetime DESC;
```

## Filter by Fab Only

```sql
AND f.fab_name = 'CZ4:TESLA FAB'
```

## Filter by Facility Only

Facility is stored on `OP_LOG.eqkey6` (test facility equipment). Uncomment the `eq_name` predicate to restrict to programs loaded at that facility:

```sql
AND eq_fac.eq_name = 'CZ4:TESLA FAB'
```

When a facility filter is active, `first_loaded_datetime` and `first_start_time` reflect the earliest load **at that facility**, not globally.

## Filter by Fab and Facility Together

```sql
AND f.fab_name = 'CZ4:TESLA FAB'
AND eq_fac.eq_name = 'CZ4:TESLA FAB'
```

## Tables Used

| Table | Purpose |
|---|---|
| `PROGRAM` | Base table — `pg_key`, `ppid`, `prog_desc`, `pgc_key` (program class) |
| `HIST_BIN` | Bin definitions — `bin_num`, `pass_fail` (P = pass, F = fail), `bin_name` |
| `PROG2TECH` | Links programs to technologies |
| `TECH2FAB` | Links technologies to fabs |
| `FAB` | Fab locations — `fab_key`, `fab_name` |
| `OP_LOG` | Load log — `insert_time`, `start_time`, `eqkey6` (test facility) |
| `EQUIPMENT` | Equipment instantiations — `eq_key`, `eq_name` |

## Key Filter

| Filter | Purpose |
|---|---|
| `p.pgc_key = 4` | Restricts results to **bin map** program class exclusively. Applied as a hard `WHERE` predicate — not optional. |

## Output Columns

| Column | Description |
|---|---|
| `pg_key` | Unique program key |
| `program_name` | PPID — Production Program ID |
| `program_description` | Descriptive text for the program |
| `fab_name` | Fab associated with the program via `PROG2TECH` / `TECH2FAB` (NULL if no technology link) |
| `facility_name` | Test facility from `OP_LOG.eqkey6` → `EQUIPMENT.eq_name` (NULL if no load log or no facility recorded) |
| `bin1_name` | Name/description of bin 1 |
| `bin1_pass_fail` | Always `F` (fail) due to the filter |
| `first_loaded_datetime` | Earliest `insert_time` from `OP_LOG` — when data was first loaded into the database |
| `first_start_time` | Earliest `start_time` from `OP_LOG` — when the process step first started on the tester |

## Notes

- `pgc_key = 4` is the bin map program class. In `OP_LOG`, `row_cnt` for these programs represents the number of columns (die on the wafer) rather than row count.
- `insert_time` in `OP_LOG` is the time data was inserted into the database.
- `start_time` is when the process step itself started on the tester.
- The `LEFT JOIN` on `OP_LOG` ensures programs are returned even if no log entries exist yet; `fab_name`, `facility_name`, and load timestamps will be NULL in that case.
- A program linked to multiple technologies may appear on multiple rows (one per fab). Use the fab filter to narrow to a single fab.
- A program loaded at multiple facilities may appear on multiple rows (one per facility). Use the facility filter to narrow to a single facility.
- To filter only active programs, add `AND p.accept_data = 1` to the `WHERE` clause.
