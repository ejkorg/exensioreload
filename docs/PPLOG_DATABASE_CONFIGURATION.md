# pp_log Database Configuration & RefDB Connection

**Date:** July 4, 2026  
**Purpose:** Determine which RefDB instance is connected to query the `pp_log` table

## Answer: Separate Configurable pp_log RefDB

The `pp_log` table is queried via a **separate, independently-configured RefDB connection** that can point to a different database environment than the main staging connection.

---

## Architecture

### Dual DataSource Design

```
┌─────────────────────────────────────────────────────────────┐
│                   RefDbService                              │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  ┌──────────────────────┐      ┌──────────────────────┐    │
│  │  Main DataSource     │      │  PP_LOG DataSource   │    │
│  │   (refdb.*)          │      │  (refdb.pplog.*)     │    │
│  │                      │      │                      │    │
│  │ STAGING queries      │      │ pp_log queries       │    │
│  │ - SENDER_STAGE       │      │ - pp_log table       │    │
│  │ - SENDER_STAGE_LOG   │      │ (PRODUCTION env)     │    │
│  │ - Enrichment data    │      │                      │    │
│  └──────────────────────┘      └──────────────────────┘    │
│          ↓                              ↓                   │
│      QA Environment            PRODUCTION Environment      │
│      (typically)                  (typically)              │
└─────────────────────────────────────────────────────────────┘
```

### Configuration

**File:** `backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/config/PpLogDbProperties.java`

**Configuration Prefix:** `refdb.pplog.*`

```yaml
refdb:
  # Main staging RefDB connection
  host: exnqa-db.onsemi.com # QA environment
  port: 1739
  service: EXNQA.onsemi.com
  user: refdb
  password: secret
  pool:
    max-size: 10

  # Separate pp_log RefDB connection (OPTIONAL)
  pplog:
    enabled: true # Enable pp_log queries (default: true)
    host: exnprd-db.onsemi.com # PRODUCTION environment (different!)
    port: 1739
    service: EXNPRD.onsemi.com # Different service/database
    user: refdb
    password: different_secret
    pool:
      max-size: 5 # Smaller pool for pp_log
      min-idle: 1
```

---

## Fallback Behavior

**If `refdb.pplog.host` is NOT configured:**

- pp_log queries fall back to the **main datasource**
- Uses same database as staging queries
- Simplifies configuration when no separate pp_log database exists

**If `refdb.pplog.host` IS configured:**

- pp_log queries use **separate connection pool**
- Can point to entirely different environment (e.g., QA staging vs. PRODUCTION)
- Enables cross-environment queries (read enrichment logs from PRODUCTION while staging in QA)

### Code Flow

**RefDbService Constructor (lines 102-122):**

```java
// Build a separate datasource for pp_log queries (PRODUCTION) if configured.
// Falls back to the main dataSource when refdb.pplog.host is not set.
if (ppLogDbProperties != null && ppLogDbProperties.isConfigured()) {
    HikariConfig ppConfig = new HikariConfig();
    ppConfig.setJdbcUrl(ppLogDbProperties.buildJdbcUrl());
    ppConfig.setUsername(ppLogDbProperties.getUser());
    ppConfig.setPassword(ppLogDbProperties.getPassword());
    ppConfig.setDriverClassName("oracle.jdbc.OracleDriver");
    ppConfig.setConnectionInitSql("ALTER SESSION SET TIME_ZONE = 'UTC'");
    ppConfig.setMaximumPoolSize(ppLogDbProperties.getPool().getMaxSize());
    ppConfig.setMinimumIdle(ppLogDbProperties.getPool().getMinIdle());
    ppConfig.setPoolName("refdb-pplog");
    this.ppLogDataSource = new HikariDataSource(ppConfig);
    log.info("pp_log datasource configured separately: {}",
             ppLogDbProperties.buildJdbcUrl());
} else {
    // No separate pp_log config — reuse the main staging datasource
    this.ppLogDataSource = this.dataSource;
    log.info("pp_log datasource not separately configured — using main refdb datasource");
}
```

---

## Query Methods Using pp_log

### 1. Sandbox Reason Query

**Method:** `RefDbService.getSandboxReason()`  
**Line:** 2737  
**Query:**

```sql
SELECT log_message
FROM refdb.pp_log
WHERE lot = ? AND filename = ?
  AND LOWER(log_message) LIKE '%sandbox%'
ORDER BY log_time DESC
```

**Purpose:** Find why a payload was sent to sandbox environment

**Uses:** Main datasource (line 2759: `dataSource.getConnection()`)

### 2. CP Success Query

**Method:** `RefDbService.queryPpLogSuccess()`  
**Line:** 3637  
**Query:**

```sql
SELECT output_directory FROM pp_log
WHERE lot = ?
  AND (extension LIKE ? OR file_name LIKE ?)
  AND process_code = 0
FETCH FIRST 1 ROWS ONLY
```

**Purpose:** Fallback enrichment check when Elasticsearch returns NotFound  
**Process Code:** 0 = success, non-zero = failure

**Uses:** `ppLogDataSource` (separate pp_log connection)

### 3. CP Failure Query

**Method:** `RefDbService.queryPpLogError()`  
**Line:** 3668  
**Query:**

```sql
SELECT log_message FROM pp_log
WHERE lot = ?
  AND (extension LIKE ? OR file_name LIKE ?)
  AND process_code != 0
FETCH FIRST 1 ROWS ONLY
```

**Purpose:** Get error details when CP processing failed  
**Uses:** `ppLogDataSource` (separate pp_log connection)

---

## Usage in Pipeline

### CpLogMonitor - Parallel Enrichment Check

The `CpLogMonitor` queries Elasticsearch AND pp_log **in parallel** for enrichment status:

1. **Elasticsearch query** → Check CP enrichment logs (primary source)
2. **pp_log query** → Check CP process logs (fallback source)
3. **Consolidate results:**
   - ES Success OR pp_log Success → `EXENSIO_LOADING`
   - ES Failure OR pp_log Failure → `FAILED`
   - Both NotFound + timeout → Try Exensio direct lookup

**Benefit:** Can check enrichment results from PRODUCTION even when staging in QA

---

## Connection Pool Configuration

| Property  | Main RefDB                                       | pp_log RefDB                            | Purpose                 |
| --------- | ------------------------------------------------ | --------------------------------------- | ----------------------- |
| Pool name | `refdb-staging`                                  | `refdb-pplog`                           | Identifies pool in logs |
| Max size  | `refdb.pool.max-size` (default 10)               | `refdb.pplog.pool.max-size` (default 5) | Concurrent connections  |
| Min idle  | `refdb.pool.min-idle`                            | `refdb.pplog.pool.min-idle` (default 1) | Warm connections        |
| Driver    | Oracle JDBC                                      | Oracle JDBC                             | Both query Oracle DBs   |
| Timezone  | UTC (SQL: `ALTER SESSION SET TIME_ZONE = 'UTC'`) | UTC                                     | Consistent timestamps   |

---

## Configuration Example: QA vs. PRODUCTION

### Scenario: Cross-Environment Enrichment Lookup

**Use Case:** Stage files in QA but verify enrichment happened in PRODUCTION

```yaml
# application-onsemi-oracle.yml
refdb:
  # Main staging connection → points to QA
  host: exnqa-db.onsemi.com
  port: 1739
  service: EXNQA.onsemi.com
  user: refdb_qa
  password: qa_secret
  pool:
    max-size: 10
    min-idle: 2

  # Separate pp_log connection → points to PRODUCTION
  pplog:
    enabled: true
    host: exnprd-db.onsemi.com # Different host!
    port: 1739
    service: EXNPRD.onsemi.com # Different service!
    user: refdb_prod
    password: prod_secret
    pool:
      max-size: 5
      min-idle: 1
```

**Result:**

- `RefDbService.fetchStatuses()` → Queries QA database (staging table)
- `RefDbService.queryPpLogSuccess()` → Queries PRODUCTION database (pp_log table)
- `CpLogMonitor` → Can enrich records using PRODUCTION enrichment logs while staging in QA

---

## Availability Control

### Enable/Disable pp_log Queries

```yaml
refdb:
  pplog:
    enabled: false # Disable pp_log fallback at sites without pp_log table
    host: ... # Can still be configured if needed later
```

**Logic:**

- `ppLogDbProperties.isPpLogAvailable()` returns true only if:
  1. `enabled: true` AND
  2. `host` is not blank

**Effect:** Even if host is configured, queries won't run if `enabled: false`

---

## Logging

**On startup, RefDbService logs:**

✅ **Separate pp_log configured:**

```
INFO  pp_log datasource configured separately: jdbc:oracle:thin:@//exnprd-db.onsemi.com:1739/EXNPRD.onsemi.com
```

✅ **Using main datasource for pp_log:**

```
INFO  pp_log datasource not separately configured — using main refdb datasource
```

**On query failure:**

```
WARN  pp_log success query failed for lot=LOT123 idFile=FILE456: [error details]
WARN  pp_log error query failed for lot=LOT123 idFile=FILE456: [error details]
```

---

## Summary

| Aspect                          | Answer                                                                                 |
| ------------------------------- | -------------------------------------------------------------------------------------- |
| **Which RefDB queries pp_log?** | A separate, independently-configured RefDB (configured via `refdb.pplog.*` properties) |
| **Default behavior**            | Falls back to main RefDB if `refdb.pplog.host` not configured                          |
| **Typical use case**            | PRODUCTION database (while staging connection points to QA)                            |
| **Pool name**                   | `refdb-pplog`                                                                          |
| **Fallback query methods**      | `queryPpLogSuccess()`, `queryPpLogError()`                                             |
| **Used in**                     | `CpLogMonitor` for parallel enrichment verification                                    |
| **Can be disabled**             | Yes, set `refdb.pplog.enabled: false`                                                  |

**Key Takeaway:** pp_log queries use a separate, configurable RefDB connection that typically points to PRODUCTION, enabling cross-environment enrichment verification.

---

## PP_LOG Table Schema (Updated with Actual Columns)

**Owner:** PRODUCTION RefDB  
**Purpose:** CP (Common Platform) processing logs with wafer-level enrichment details  
**Record Count:** Millions (one per CP processing event)

### Column Definitions (18 Total)

| Column                      | Type           | Nullable | Purpose                          | Used in Queries               |
| --------------------------- | -------------- | -------- | -------------------------------- | ----------------------------- |
| **PP_LOG_ID**               | RAW            | No       | Primary key (SYS_GUID())         | No                            |
| **LOT**                     | VARCHAR2(32)   | Yes      | Lot identifier                   | ✅ Yes (all 3 queries)        |
| **ENVIRONMENT**             | VARCHAR2(32)   | Yes      | PRODUCTION / SANDBOX / etc       | Analysis only                 |
| **PROCESS_DATETIME**        | DATE           | Yes      | When CP processed the file       | ✅ Sort key (implicit)        |
| **PROCESS_CODE**            | NUMBER(38,0)   | Yes      | 0 = success, non-zero = failure  | ✅ Success/error filter       |
| **FILE_NAME**               | VARCHAR2(255)  | Yes      | Original filename                | ✅ Match filter               |
| **OUTPUT_DIRECTORY**        | VARCHAR2(255)  | Yes      | Where output was written         | ✅ Success query returns this |
| **LOG_MESSAGE**             | VARCHAR2(2000) | Yes      | Detailed message (error/success) | ✅ Error query returns this   |
| **INSERT_ID**               | VARCHAR2(25)   | Yes      | User/system that inserted record | Audit                         |
| **MAP_ID**                  | VARCHAR2(25)   | Yes      | Process/mapping identifier       | Reference                     |
| **WAFER_NUM**               | VARCHAR2(255)  | Yes      | Wafer identifier                 | ✅ Could enhance queries      |
| **ERROR_CODE**              | VARCHAR2(255)  | Yes      | Specific error code if failed    | ✅ Detailed error info        |
| **PROGRAM_CLASS**           | NUMBER         | Yes      | CP program classification        | Reference                     |
| **SITE**                    | VARCHAR2(100)  | Yes      | Manufacturing site               | ✅ Could add to filter        |
| **PROCESS_DATETIME_ADJUST** | DATE           | Yes      | Adjusted process datetime (UTC)  | Alternate sort                |
| **LIMIT_FILE_NAME**         | VARCHAR2(255)  | Yes      | Limited filename version         | Reference                     |
| **PROGRAM_NAME**            | VARCHAR2(255)  | Yes      | CP program name                  | Reference                     |
| **EXTENSION**               | VARCHAR2(100)  | Yes      | File extension                   | ✅ Match filter               |
| **MD5**                     | VARCHAR2(100)  | Yes      | File MD5 hash                    | Data integrity                |
| **PATH**                    | VARCHAR2(255)  | Yes      | Full file path                   | Reference                     |
| **SCRIPT**                  | VARCHAR2(255)  | Yes      | Script/process script name       | Reference                     |

---

## Current Query Analysis

### Query 1: Sandbox Reason

**Method:** `RefDbService.getSandboxReason()` (line 2737)

**Current SQL:**

```sql
SELECT log_message
FROM refdb.pp_log
WHERE lot = ?
  AND filename = ?                          -- ⚠️ ISSUE: Column is FILE_NAME, not filename
  AND LOWER(log_message) LIKE '%sandbox%'
ORDER BY log_time DESC                     -- ⚠️ ISSUE: Column is PROCESS_DATETIME, not log_time
```

**Issues Found:**

1. Column name mismatch: `filename` should be `FILE_NAME`
2. Sort column missing: `log_time` doesn't exist, should be `PROCESS_DATETIME` or `PROCESS_DATETIME_ADJUST`
3. Uses main datasource, not ppLogDataSource

**Corrected SQL:**

```sql
SELECT log_message
FROM refdb.pp_log
WHERE lot = ?
  AND FILE_NAME = ?
  AND LOWER(log_message) LIKE '%sandbox%'
ORDER BY PROCESS_DATETIME DESC NULLS LAST
```

---

### Query 2: CP Success Query

**Method:** `RefDbService.queryPpLogSuccess()` (line 3637)

**Current SQL:**

```sql
SELECT output_directory
FROM pp_log
WHERE lot = ?
  AND (extension LIKE ? OR file_name LIKE ?)
  AND process_code = 0
FETCH FIRST 1 ROWS ONLY
```

**Analysis:**

- ✅ Uses correct column names: `EXTENSION`, `FILE_NAME`
- ✅ Correct process_code filter: 0 = success
- ✅ Returns OUTPUT_DIRECTORY (where enriched data is)
- ✅ Uses LIKE wildcards for flexible matching

**Dataflow:**

```
LOT='LOT123' → Search pp_log
  → EXTENSION LIKE '%FILE456%' OR FILE_NAME LIKE '%FILE456%'
  → PROCESS_CODE = 0 (success)
  → Returns: OUTPUT_DIRECTORY e.g., '/prod/enriched/LOT123/'
```

**Uses:** `ppLogDataSource` ✅

---

### Query 3: CP Failure Query

**Method:** `RefDbService.queryPpLogError()` (line 3668)

**Current SQL:**

```sql
SELECT log_message
FROM pp_log
WHERE lot = ?
  AND (extension LIKE ? OR file_name LIKE ?)
  AND process_code != 0
FETCH FIRST 1 ROWS ONLY
```

**Analysis:**

- ✅ Uses correct column names: `EXTENSION`, `FILE_NAME`
- ✅ Correct process_code filter: != 0 (failure)
- ✅ Returns LOG_MESSAGE (error description)
- ✅ Uses LIKE wildcards for flexible matching
- ⚠️ Could also return ERROR_CODE for structured error info

**Dataflow:**

```
LOT='LOT123' → Search pp_log
  → EXTENSION LIKE '%FILE456%' OR FILE_NAME LIKE '%FILE456%'
  → PROCESS_CODE != 0 (failure)
  → Returns: LOG_MESSAGE e.g., 'Wafer lookup failed in CP...'
```

**Uses:** `ppLogDataSource` ✅

---

## Query Issues & Recommendations

### Issue 1: Sandbox Query Has Column Name Errors

**Severity:** 🔴 HIGH — Query will fail at runtime

**Location:** `RefDbService.getSandboxReason()` (line 2756)

**Problems:**

```sql
WHERE lot = ? AND filename = ?              -- ❌ Column 'filename' doesn't exist
ORDER BY log_time DESC                      -- ❌ Column 'log_time' doesn't exist
```

**Fix:**

```sql
WHERE lot = ? AND FILE_NAME = ?             -- ✅ Correct column name
ORDER BY PROCESS_DATETIME DESC NULLS LAST   -- ✅ Correct column, handle NULLs
```

**Impact:** This query will throw `SQLException: Invalid column name` if executed

---

### Issue 2: Missing Wafer-Level Filtering

**Current Approach:**

```sql
WHERE lot = ? AND FILE_NAME LIKE ?
```

**Opportunity:** Use `WAFER_NUM` column for more precise filtering

**Enhanced Query (optional):**

```sql
SELECT LOG_MESSAGE, ERROR_CODE, WAFER_NUM, ENVIRONMENT
FROM pp_log
WHERE LOT = ?
  AND FILE_NAME LIKE ?
  AND WAFER_NUM = ?                         -- ✨ NEW: Filter by wafer
  AND PROCESS_CODE != 0
ORDER BY PROCESS_DATETIME DESC NULLS LAST
FETCH FIRST 1 ROWS ONLY
```

**Benefit:** Wafer-level precision in enrichment verification

---

### Issue 3: Enhancement Opportunities

#### A. Environment-Aware Queries

```sql
-- Query PRODUCTION pp_log for PRODUCTION environment logs
WHERE LOT = ?
  AND ENVIRONMENT = 'PRODUCTION'            -- ✨ Filter by environment
  AND FILE_NAME LIKE ?
  AND PROCESS_CODE = 0
```

#### B. Sorted by Recency

```sql
-- Most recent enrichment attempt
ORDER BY PROCESS_DATETIME_ADJUST DESC NULLS LAST
FETCH FIRST 1 ROWS ONLY
```

#### C. Structured Error Details

```sql
SELECT LOG_MESSAGE, ERROR_CODE, PROGRAM_NAME
FROM pp_log
WHERE PROCESS_CODE != 0
```

**Benefit:** Extractable error codes for automated remediation

---

## Summary: PP_LOG Usage

| Query Method          | Purpose              | Status     | Issue              |
| --------------------- | -------------------- | ---------- | ------------------ |
| `getSandboxReason()`  | Why sent to sandbox  | ❌ Broken  | Column names wrong |
| `queryPpLogSuccess()` | Get output directory | ✅ Correct | None               |
| `queryPpLogError()`   | Get error message    | ✅ Correct | None               |

**Recommendation:** Fix sandbox query column names before deployment
