# Exensio Data Loading Reference

## 1. When Is a Program Created?

The `PROGRAM` table does not have a direct `insert_time` column. The best proxy for program creation time is the **first `insert_time` in `OP_LOG`** for that program.

### Query: Program Creation Time via First OP_LOG Entry

```sql
SELECT p.pg_key,
       p.ppid,
       MIN(o.insert_time) AS program_created_time,
       MAX(o.insert_time) AS last_data_loaded_time
FROM program p
JOIN op_log o ON o.pg_key = p.pg_key
GROUP BY p.pg_key, p.ppid
ORDER BY program_created_time DESC;
```

### Query: Program Revision Insert Time (PROG_REV)

```sql
SELECT p.ppid,
       pr.revision,
       pr.release_date,
       pr.insert_time
FROM program p
JOIN prog_rev pr ON pr.pg_key = p.pg_key
ORDER BY p.ppid, pr.insert_time;
```

### Query: Specific Program Creation and Revision Time

To find when a specific program version/revision (e.g., `PPM_JND_WA007Z-FNB7-V-S-1_0FNB7.TST_E_PROBE:TP0FNB7-200N-00B`, product `WA007Z-FNB7-V-S-1`, revision `E`) was first created and loaded:

```sql
SELECT p.pg_key,
       p.ppid,
       pd.pd_name,
       pr.revision,
       TO_CHAR(pr.release_date, 'YYYY-MM-DD HH24:MI:SS') AS revision_release_date,
       TO_CHAR(pr.insert_time, 'YYYY-MM-DD HH24:MI:SS') AS revision_metadata_created_time,
       TO_CHAR(MIN(o.insert_time), 'YYYY-MM-DD HH24:MI:SS') AS program_first_data_loaded_time
FROM PRODUCTION.program p
JOIN PRODUCTION.prog_rev pr ON pr.pg_key = p.pg_key
JOIN PRODUCTION.prog2prod p2p ON p2p.pg_key = p.pg_key
JOIN PRODUCTION.product pd ON pd.pd_key = p2p.pd_key
LEFT JOIN PRODUCTION.op_log o ON o.pg_key = p.pg_key AND o.pd_key = pd.pd_key
WHERE p.ppid = 'PPM_JND_WA007Z-FNB7-V-S-1_0FNB7.TST_E_PROBE:TP0FNB7-200N-00B'
  AND pd.pd_name = 'WA007Z-FNB7-V-S-1'
  AND pr.revision = 'E'
GROUP BY p.pg_key, p.ppid, pd.pd_name, pr.revision, pr.release_date, pr.insert_time;
```

---

## 2. When Are Test Parameters (Pass/Fail Limits) Set?

### Rule: Limits Are Set at Program Creation (First Data Load)

From the Exensio Data Readers documentation:

> _"Limits that are part of the data files are inserted in the database **only when a new program is created**. Limits, in these cases, are not updated for subsequent processing of data files belonging to the same program."_

This means:

- On the **first file load** for a new program:
  - `DEF…` table is populated with test parameters
  - `LIM…` table is populated with limits (LSL, HSL, LPL, HPL, LOL, HOL, LWL, HWL)
  - `fail_bin` in `DEF…` defines which bin a device goes to if it fails that test
  - `LIM_LOG` records the limit set with a timestamp (`lim_date`, `insert_time`)
- On **subsequent file loads** for the same program, limits are **ignored/not updated**

### Limit Update Exceptions

| Method                                   | Behavior                                                              |
| ---------------------------------------- | --------------------------------------------------------------------- |
| Normal file load (new program)           | Limits inserted into `LIM…` and `DEF…`                                |
| Subsequent file loads (existing program) | Limits **NOT updated**                                                |
| `-limitsonly` command-line option        | Only limits updated, no raw data loaded                               |
| `DbUpdateLimits` built-in function       | Partial update — only tests with valid limits in the file are updated |
| `DbHistLimits` built-in function         | Insert new limit set with a specific date stamp                       |

---

## 3. Queries

### Query: When Were Limits First Set for a Program

```sql
SELECT p.ppid,
       p.pg_key,
       ll.lim_date,
       ll.insert_time,
       ll.lim_type,
       ll.def_flag       -- Y = current default limits
FROM program p
JOIN lim_log ll ON ll.pg_key = p.pg_key
ORDER BY p.ppid, ll.insert_time;
```

### Query: Test Parameters and Pass/Fail Definition (DEF table)

```sql
-- Replace <pg_key> with the actual pg_key value
SELECT d.test_index,
       d.cond0        AS test_name,
       d.limits_type, -- N=no limits, U=upper only, L=lower only, B=both
       d.fail_bin,    -- bin device is assigned to if it fails this test
       d.scale_factor,
       d.test_type
FROM def_<pg_key> d
ORDER BY d.test_index;
```

### Query: Actual Limit Values (LIM table)

```sql
-- Replace <pg_key> with the actual pg_key value
SELECT l.sbin_num,
       l.limit_name,   -- LSL, HSL, LPL, HPL, LOL, HOL, LWL, HWL
       ll.insert_time,
       ll.lim_date,
       ll.def_flag     -- Y = current default limit set
FROM lim_log ll
JOIN lim_<pg_key> l ON l.lim_key = ll.lim_key
WHERE ll.pg_key = <pg_key>
ORDER BY ll.insert_time;
```

---

## 4. Key Table References

| Table          | Purpose                              | Key Timestamp Column                   |
| -------------- | ------------------------------------ | -------------------------------------- |
| `OP_LOG`       | One entry per data file load         | `insert_time`                          |
| `PROGRAM`      | Program definition                   | _(no direct insert_time — use OP_LOG)_ |
| `PROG_REV`     | Program revision history             | `insert_time`                          |
| `LIM_LOG`      | Limit set metadata per program       | `insert_time`, `lim_date`              |
| `LIM_<pg_key>` | Actual limit values (LSL, HSL, etc.) | via `lim_key` → `LIM_LOG`              |
| `DEF_<pg_key>` | Test parameter definitions, fail_bin | _(set at program creation)_            |

---

## 5. Summary

- **Program creation time** → `MIN(insert_time)` in `OP_LOG` for that `pg_key`
- **Test parameter / pass-fail criteria** → Set on first data file load, stored in `DEF_<pg_key>` and `LIM_<pg_key>`
- **Limit set timestamp** → `LIM_LOG.insert_time` or `LIM_LOG.lim_date`
- **Limits are frozen** after program creation unless explicitly updated via `-limitsonly` or `DbUpdateLimits`
