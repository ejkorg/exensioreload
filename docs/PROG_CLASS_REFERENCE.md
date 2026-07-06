# Program Class Reference — Reserved Classes and Query Design Notes

Reference extracted from dataPOWER Data Dictionary, Release 9.0.

## Reserved Program Classes (pgc_key 1–30)

| pgc_key | pgc_name | Purpose |
|---------|----------|---------|
| 1 | Wafer_Sort | Wafer sort test data |
| 2 | Final_Test | Final test data |
| 3 | Summary | Summary data |
| 4 | Bin_Map | Bin mapping / bin map programs |
| 5 | PCM | Parametric characterization module |
| 6 | Wafer_Sort_No_Bin | Wafer sort without binning |
| 7 | MultiBin | Multi-bin analysis |
| 8 | WaferStats | Wafer-level statistics |
| 9 | LotStats | Lot-level statistics |
| 10 | Metrology | Metrology/inline measurement data |
| 11 | DefectSum | Defect summaries |
| 12 | FinalSummary | Final summary |
| 13 | LEH | Lot Event History |
| 14 | Defect | Defect analysis data |
| 15 | LotEvent | Lot event tracking |
| 16 | EquipEvent | Equipment event tracking |
| 17 | Bit | Bit-level failure data |
| 18 | BitFinalTest | Bit-level final test |
| 19 | Reserved_19 | Reserved for future use |
| 20 | Reserved_20 | Reserved for future use |
| 21 | shmoo data-log | Shmoo characterization data |
| 22 | robust-ness data-log | Robustness characterization data |
| 23 | Reserved_23 | Reserved for future use |
| 24 | Reserved_24 | Reserved for future use |
| 25 | Reserved_25 | Reserved for future use |
| 26 | CV_DATA | Characterization & Validation data |
| 27 | SCV_PARAM | Spatial CV parameters |
| 28 | SCV_YLD_MOD | Spatial CV yield model |
| 29 | SCV_WF_FR_MOD | Spatial CV wafer failure rate model |
| 30 | SCV_LOT_FR_MOD | Spatial CV lot failure rate model |

## Custom Program Classes (pgc_key ≥ 31)

Program classes starting from `pgc_key = 31` are reserved for user-defined/custom classes. These must be explicitly created in the `PROG_CLASS` table with appropriate flag settings before use.

To determine what custom class exists in your system, query:

```sql
SELECT pgc_key, pgc_name 
FROM prog_class 
WHERE pgc_key >= 31 
ORDER BY pgc_key;
```

## Core Tables for Bin 1 Query

### Table Relationships

All bin 1 failed program queries are built on these core tables and relationships:

| Table | Primary Purpose | Key Field | Notes |
|-------|-----------------|-----------|-------|
| `PROGRAM` | Base program definitions | `pg_key` | Stores program metadata; `pgc_key` links to `PROG_CLASS` |
| `PROG_CLASS` | Program class definitions | `pgc_key` | Filters by program class type (e.g., 4 = Bin_Map, 14 = Defect) |
| `HIST_BIN` | Historical bin definitions | `pg_key`, `bin_num`, `pass_fail` | Filters on bin 1 with pass/fail status |
| `OP_LOG` | Operation log (load tracking) | `lg_key` | Tracks program loads; contains `insert_time`, `start_time` |
| `PROG2TECH` | Program → Technology link | `pg_key`, `tech_key` | Many-to-many; enables fab association |
| `TECH2FAB` | Technology → Fab link | `tech_key`, `fab_key` | Resolves fab name |
| `FAB` | Fab definitions | `fab_key`, `fab_name` | Fab location names |
| `EQUIPMENT` | Equipment definitions | `eq_key`, `eq_name` | Test facility names |

### Query Filter Hierarchy

```
PROGRAM (base)
├─ HIST_BIN (required)
│  └─ Filter: bin_num = 1 AND pass_fail = 'F'
├─ OP_LOG (optional)
│  ├─ Provides: insert_time, start_time
│  └─ Optional filter: eqkey6 → EQUIPMENT (facility name)
├─ PROG2TECH → TECH2FAB → FAB (optional)
│  └─ Optional filter: fab_name
└─ PROG_CLASS (implicit via pgc_key on PROGRAM)
   └─ Optional filter: pgc_key = 4 (or 31, etc.)
```

## Verification Steps for Your Queries

Before running any pgc-filtered query:

1. **Verify program class exists:**
   ```sql
   SELECT * FROM prog_class WHERE pgc_key = 4;  -- (or 31, etc.)
   ```

2. **Verify programs exist in that class with bin 1 failed:**
   ```sql
   SELECT p.pg_key, p.ppid, hb.bin_name 
   FROM program p
   JOIN hist_bin hb ON hb.pg_key = p.pg_key AND hb.bin_num = 1 AND hb.pass_fail = 'F'
   WHERE p.pgc_key = 4;
   ```

3. **Verify operation log has data (if no results, may indicate no loads):**
   ```sql
   SELECT COUNT(*) FROM op_log WHERE pg_key IN (
     SELECT p.pg_key 
     FROM program p
     WHERE p.pgc_key = 4
   );
   ```

## Notes

- **Row count without OP_LOG entries:** If no OP_LOG records exist for a program, `first_loaded_datetime` and `first_start_time` will be NULL, but the program will still appear in results due to LEFT JOIN.
- **Facility vs. Fab:** Fab is resolved at program configuration level (`PROG2TECH` → `TECH2FAB`). Facility is resolved at load level (`OP_LOG.eqkey6` → `EQUIPMENT`). These may differ.
- **Program class behavior:** For `pgc_key = 4` (Bin_Map), `OP_LOG.row_cnt` represents the number of columns (die on wafer), not rows. For `pgc_key = 14` (Defect), `row_cnt` is the defect count.
