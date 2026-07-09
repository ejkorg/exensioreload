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

### Unified pp_log Query

**Method:** `RefDbService.queryPpLog()`  
**Query:**

```sql
SELECT output_directory, log_message, process_code FROM pp_log
WHERE lot = ?
  AND (extension LIKE ? OR file_name LIKE ?)
ORDER BY process_datetime DESC
FETCH FIRST 1 ROWS ONLY
```

**Purpose:** Single-round-trip replacement for the former `queryPpLogSuccess` + `queryPpLogError` pair. Returns all three relevant columns so the caller can inspect `process_code` directly:

| `process_code` | Meaning | Columns populated |
|---|---|---|
| 0 | Success | `output_directory` |
| non-zero | Failure | `log_message` |
| no row | NotFound | `null` (no `PpLogRow`) |

**Uses:** `ppLogDataSource` (separate PRODUCTION connection)

**Improvements over the old two-query approach:**
- 50% fewer round-trips (1 instead of 2)
- `ORDER BY process_datetime DESC` ensures deterministic latest result
- Elapsed time logged at DEBUG for performance monitoring

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
- `RefDbService.queryPpLog()` → Queries PRODUCTION database (pp_log table) via dedicated `ppLogDataSource`
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
| **Query method**                | `queryPpLog()` (merged single-round-trip)                                               |
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
| **LOT**                     | VARCHAR2(32)   | Yes      | Lot identifier                   | ✅ queryPpLog()               |
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

### Unified Query: queryPpLog()

**Method:** `RefDbService.queryPpLog()`  
**Return type:** `PpLogRow` (record with `outputDirectory`, `logMessage`, `processCode`) or `null`

**SQL:**

```sql
SELECT output_directory, log_message, process_code FROM pp_log
WHERE lot = ?
  AND (extension LIKE ? OR file_name LIKE ?)
ORDER BY process_datetime DESC
FETCH FIRST 1 ROWS ONLY
```

**Analysis:**

| Aspect | Status |
|--------|--------|
| Column names | ✅ Correct (`OUTPUT_DIRECTORY`, `LOG_MESSAGE`, `PROCESS_CODE`, `EXTENSION`, `FILE_NAME`, `PROCESS_DATETIME`) |
| Row selection | ✅ Deterministic — `ORDER BY PROCESS_DATETIME DESC` returns latest entry |
| Round-trips per record | ✅ **1** (was 2 before merge) |
| Datasource | ✅ `ppLogDataSource` (PRODUCTION) |
| LIKE wildcards | ✅ Flexible matching on extension/file_name |
| Query timing | ✅ Elapsed ms logged at DEBUG |

**Dataflow:**

```
LOT='LOT123' idFile='FILE456' → Search pp_log
  → EXTENSION LIKE '%FILE456%' OR FILE_NAME LIKE '%FILE456%'
  → Latest row by PROCESS_DATETIME DESC
  → process_code == 0   → row.outputDirectory() → Success
  → process_code != 0   → row.logMessage() → Failure
  → no row              → null → NotFound
```

**Uses:** `ppLogDataSource` ✅ (PRODUCTION connection)

### Removed Methods

| Method | Disposition | Reason |
|--------|-------------|--------|
| `getSandboxReason()` | 🗑️ **Removed** | Broken column names (`filename`, `log_time`); method no longer exists |
| `queryPpLogSuccess()` | 🔀 **Merged** | Replaced by single `queryPpLog()` |
| `queryPpLogError()` | 🔀 **Merged** | Replaced by single `queryPpLog()` |

---

## Enhancement Opportunities (Optional)

### A. Wafer-Level Filtering
```sql
AND WAFER_NUM = ?
```
Better precision when the same lot has multiple wafers.

### B. Environment Filtering
```sql
AND ENVIRONMENT = 'PRODUCTION'
```
Narrow to PRODUCTION-only entries if SANDBOX noise is an issue.

---

## Summary: PP_LOG Usage

| Query Method  | Purpose                     | Status             | Notes |
|---------------|-----------------------------|--------------------|-------|
| `queryPpLog()`| Unified enrichment lookup   | ✅ Merged/improved | Single query, deterministic ordering, timing logging |

