# Update Bin 1 Pass/Fail Status: Wafer Sort, Bin Map, and Final Summary Programs

## Purpose

Update `HIST_BIN.pass_fail` from 'F' (fail) to 'P' (pass) for bin 1 (`bin_num = 1`) across programs in three specific program classes:
- pgc_key = 1 (Wafer_Sort)
- pgc_key = 4 (Bin_Map)
- pgc_key = 12 (FinalSummary)

This operation reclassifies bin 1 from a fail bin to a pass bin for the affected programs.

## Prerequisites

Before executing the update, run the verification query to confirm the programs and current state:

```sql
SELECT 
    p.pg_key,
    p.ppid                          AS program_name,
    p.prog_desc                     AS program_description,
    pc.pgc_name                     AS program_class,
    hb.bin_num,
    hb.bin_name,
    hb.pass_fail                    AS current_pass_fail
FROM 
    program p
    JOIN hist_bin hb       ON hb.pg_key    = p.pg_key
                           AND hb.bin_num   = 1
                           AND hb.pass_fail = 'F'
    JOIN prog_class pc     ON pc.pgc_key   = p.pgc_key
WHERE 
    p.pgc_key IN (1, 4, 12)
ORDER BY 
    pc.pgc_name,
    p.ppid;
```

**This query returns all bin 1 fail bins currently set to 'F' for the three program classes. Review the results before proceeding with the update.**

## Update Query — Safe Approach (Explicit Program List)

If you have a specific list of `pg_key` values to update, use:

```sql
UPDATE hist_bin
SET pass_fail = 'P'
WHERE bin_num = 1
  AND pass_fail = 'F'
  AND pg_key IN (
    -- Replace with actual pg_key values from verification query
    -- Example:
    -- 12345,
    -- 12346,
    -- 12347
  );
```

## Update Query — Automatic (All Matching Programs)

To update all bin 1 fail bins for programs in the three classes without specifying individual pg_keys:

```sql
UPDATE hist_bin
SET pass_fail = 'P'
WHERE bin_num = 1
  AND pass_fail = 'F'
  AND pg_key IN (
    SELECT p.pg_key
    FROM program p
    WHERE p.pgc_key IN (1, 4, 12)
  );
```

## Rollback Query

If needed, revert the changes back to 'F':

```sql
UPDATE hist_bin
SET pass_fail = 'F'
WHERE bin_num = 1
  AND pass_fail = 'P'
  AND pg_key IN (
    SELECT p.pg_key
    FROM program p
    WHERE p.pgc_key IN (1, 4, 12)
  );
```

## Verification After Update

Confirm the update was successful:

```sql
SELECT 
    p.pg_key,
    p.ppid                          AS program_name,
    pc.pgc_name                     AS program_class,
    hb.bin_num,
    hb.bin_name,
    hb.pass_fail                    AS updated_pass_fail
FROM 
    program p
    JOIN hist_bin hb       ON hb.pg_key    = p.pg_key
                           AND hb.bin_num   = 1
    JOIN prog_class pc     ON pc.pgc_key   = p.pgc_key
WHERE 
    p.pgc_key IN (1, 4, 12)
    AND hb.pass_fail = 'P'
ORDER BY 
    pc.pgc_name,
    p.ppid;
```

## Impact Analysis

- **Table affected:** `HIST_BIN` only
- **Records affected:** All rows where `bin_num = 1`, `pass_fail = 'F'`, and `pg_key` belongs to programs in classes 1, 4, or 12
- **Reversible:** Yes (see Rollback Query above)
- **Related views/reports:** Any reports or dashboards based on bin 1 status will reflect this change immediately

## Important Notes

1. **Test in non-production first** — Run verification query in a test environment before updating production data
2. **Backup recommended** — Consider backing up the `HIST_BIN` table or the entire database before this operation
3. **No audit trail in HIST_BIN** — This table does not have built-in version control; changes are permanent unless manually rolled back
4. **OP_LOG not affected** — Load logs remain unchanged; this only affects the bin definition itself
5. **Program class filter** — Only updates programs in the three specified classes; all other programs remain unaffected

## Related Queries

To find bin 1 programs with specific fab or facility associations before updating:

```sql
SELECT 
    p.pg_key,
    p.ppid,
    pc.pgc_name,
    hb.bin_name,
    hb.pass_fail,
    f.fab_name,
    eq_fac.eq_name                  AS facility_name
FROM 
    program p
    JOIN hist_bin hb       ON hb.pg_key    = p.pg_key
                           AND hb.bin_num   = 1
                           AND hb.pass_fail = 'F'
    JOIN prog_class pc     ON pc.pgc_key   = p.pgc_key
    LEFT JOIN prog2tech p2t  ON p2t.pg_key   = p.pg_key
    LEFT JOIN tech2fab t2f   ON t2f.tech_key = p2t.tech_key
    LEFT JOIN fab f          ON f.fab_key    = t2f.fab_key
    LEFT JOIN op_log ol      ON ol.pg_key    = p.pg_key
    LEFT JOIN equipment eq_fac ON eq_fac.eq_key = ol.eqkey6
WHERE 
    p.pgc_key IN (1, 4, 12)
ORDER BY 
    pc.pgc_name,
    p.ppid;
```
