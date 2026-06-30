# Programs with Bin 1 Set as Failed

Query to retrieve all programs where bin 1 is designated as a fail bin, including the datetime the program was first loaded.

## SQL Query

```sql
SELECT 
    p.pg_key,
    p.ppid                          AS program_name,
    p.prog_desc                     AS program_description,
    hb.bin_name                     AS bin1_name,
    hb.pass_fail                    AS bin1_pass_fail,
    MIN(ol.insert_time)             AS first_loaded_datetime,
    MIN(ol.start_time)              AS first_start_time
FROM 
    program p
    JOIN hist_bin hb  ON hb.pg_key    = p.pg_key
                     AND hb.bin_num   = 1
                     AND hb.pass_fail = 'F'
    LEFT JOIN op_log ol ON ol.pg_key  = p.pg_key
GROUP BY 
    p.pg_key,
    p.ppid,
    p.prog_desc,
    hb.bin_name,
    hb.pass_fail
ORDER BY 
    first_loaded_datetime DESC;
```

## Tables Used

| Table | Purpose |
|---|---|
| `PROGRAM` | Base table — `pg_key`, `ppid` (program name), `prog_desc` |
| `HIST_BIN` | Bin definitions — `bin_num`, `pass_fail` (P = pass, F = fail), `bin_name` |
| `OP_LOG` | Load log — `insert_time` (when data was loaded), `start_time` (when test started) |

## Join Logic

| Join / Filter | Purpose |
|---|---|
| `PROGRAM p` | Base table for all programs |
| `HIST_BIN hb ON hb.pg_key = p.pg_key AND hb.bin_num = 1 AND hb.pass_fail = 'F'` | Only programs where **bin 1** is defined as a **fail** bin |
| `LEFT JOIN OP_LOG ol ON ol.pg_key = p.pg_key` | Brings in load log entries to find the earliest load time |

## Output Columns

| Column | Description |
|---|---|
| `pg_key` | Unique program key |
| `program_name` | PPID — Production Program ID |
| `program_description` | Descriptive text for the program |
| `bin1_name` | Name/description of bin 1 |
| `bin1_pass_fail` | Always `F` (fail) due to the filter |
| `first_loaded_datetime` | Earliest `insert_time` from `OP_LOG` — when data was first loaded into the database |
| `first_start_time` | Earliest `start_time` from `OP_LOG` — when the process step first started on the tester |

## Notes

- `insert_time` in `OP_LOG` is the time data was inserted into the database (i.e., when the program was first loaded).
- `start_time` is when the process step itself started on the tester.
- The `LEFT JOIN` on `OP_LOG` ensures programs are returned even if no log entries exist yet.
- To filter only active programs, add `AND p.accept_data = 1` to the WHERE clause.
