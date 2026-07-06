# Update HIST_BIN.pass_fail: Change Bin 1 from Fail to Pass for Wafer Sort, Bin Map, Final Summary

## Ticket Summary

Update the `HIST_BIN` table to reclassify bin 1 from failed ('F') to passed ('P') status for all programs in three program classes: Wafer Sort (pgc_key = 1), Bin Map (pgc_key = 4), and Final Summary (pgc_key = 12).

## Objective

Change `HIST_BIN.pass_fail` column from 'F' to 'P' where:
- `HIST_BIN.bin_num = 1` (bin 1 only)
- `PROGRAM.pgc_key IN (1, 4, 12)` (Wafer Sort, Bin Map, Final Summary classes)

## Business Rationale

Bin 1 was previously marked as a fail bin across these three program classes. This update reclassifies it as a pass bin, reflecting a change in binning strategy or test configuration.

## Scope

**Program Classes Affected:**
- pgc_key = 1: Wafer_Sort
- pgc_key = 4: Bin_Map
- pgc_key = 12: FinalSummary

**Table Modified:**
- HIST_BIN

**Column Modified:**
- pass_fail (change from 'F' to 'P')

**Rows Affected:**
- All HIST_BIN records where bin_num = 1 AND the associated program belongs to one of the three classes above

## Pre-Update Verification

Before executing the update, run this query to identify affected programs and row count:

```sql
SELECT 
    p.pg_key,
    p.ppid AS program_name,
    pc.pgc_name AS program_class,
    hb.bin_num,
    hb.bin_name,
    hb.pass_fail AS current_status,
    COUNT(*) AS row_count
FROM 
    program p
    JOIN prog_class pc ON pc.pgc_key = p.pgc_key
    JOIN hist_bin hb ON hb.pg_key = p.pg_key
WHERE 
    p.pgc_key IN (1, 4, 12)
    AND hb.bin_num = 1
    AND hb.pass_fail = 'F'
GROUP BY 
    p.pg_key, p.ppid, pc.pgc_name, hb.bin_num, hb.bin_name, hb.pass_fail
ORDER BY 
    pc.pgc_name, p.ppid;
```

This will show:
- Which programs are affected
- Their program class
- Current bin 1 status
- Number of rows per program

## Update Query

```sql
UPDATE hist_bin
SET pass_fail = 'P'
WHERE 
    bin_num = 1
    AND pg_key IN (
        SELECT pg_key 
        FROM program 
        WHERE pgc_key IN (1, 4, 12)
    )
    AND pass_fail = 'F';
```

**Expected behavior:** All HIST_BIN rows matching the criteria will have `pass_fail` changed from 'F' to 'P'.

## Post-Update Verification

After the update, run this query to confirm all changes were applied:

```sql
SELECT 
    p.pg_key,
    p.ppid AS program_name,
    pc.pgc_name AS program_class,
    hb.bin_num,
    hb.bin_name,
    hb.pass_fail AS updated_status,
    COUNT(*) AS row_count
FROM 
    program p
    JOIN prog_class pc ON pc.pgc_key = p.pgc_key
    JOIN hist_bin hb ON hb.pg_key = p.pg_key
WHERE 
    p.pgc_key IN (1, 4, 12)
    AND hb.bin_num = 1
ORDER BY 
    pc.pgc_name, p.ppid;
```

All results should show `pass_fail = 'P'` for bin 1.

## Related Queries

To identify programs with bin 1 failed status before this update:

- **Wafer Sort (pgc_key = 1):** See `query_bin1_failed_programs.md`
- **Bin Map (pgc_key = 4):** See `query_bin1_failed_programs_binmap.md`
- **Final Summary (pgc_key = 12):** See `query_bin1_failed_programs_finalsummary.md`

## Tables and Columns Involved

| Table | Column | Change | Notes |
|-------|--------|--------|-------|
| HIST_BIN | pass_fail | 'F' → 'P' | Bin 1 reclassification |
| HIST_BIN | bin_num | — | Filter condition (bin_num = 1) |
| HIST_BIN | pg_key | — | Foreign key to PROGRAM |
| PROGRAM | pgc_key | — | Filter condition (IN 1, 4, 12) |
| PROG_CLASS | pgc_key | — | Reference for program class names |

## Considerations

1. **Data Dependencies:** This change may affect:
   - Yield calculations that depend on bin status
   - Reports filtering on pass/fail bins
   - Downstream analysis tools that aggregate by bin status

2. **Backup:** Recommend backing up HIST_BIN before this update, or reviewing change logs post-update.

3. **Testing:** Run the pre-update verification query first to confirm scope before executing the update.

4. **Rollback:** If needed, run the reverse query:
   ```sql
   UPDATE hist_bin
   SET pass_fail = 'F'
   WHERE 
       bin_num = 1
       AND pg_key IN (
           SELECT pg_key 
           FROM program 
           WHERE pgc_key IN (1, 4, 12)
       )
       AND pass_fail = 'P';
   ```

## Approval and Sign-Off

- **Requested By:** [Name/Team]
- **Approved By:** [DBA/Database Admin]
- **Execution Date:** [Date]
- **Executed By:** [DBA/Script Runner]
