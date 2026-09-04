# Elasticsearch & Exensio Enrichment Optimization Walkthrough

## Overview

During reloading, records being enriched through Elasticsearch and Exensio API could fail to match or show zero hits due to rigid filter constraints and differences in log generation lifecycles. This document outlines the root causes, architecture decisions, and code modifications applied to resolve these issues.

---

## 1. Scope & Anchor Strategy: Current Reload Cycle

> [!IMPORTANT]
> Files being reloaded may have been originally generated or archived days or weeks ago. However, **this application is strictly concerned with the current reloading/reprocessing event**.
> 
> The Elasticsearch query must evaluate log entries emitted by CP **during the current reload execution**, not historical runs from when the file was originally created or archived.

### Lookback Range Formula
* **Anchor**: `enrichmentStartedAt`
* **Lower Bound (`@timestamp gte`)**: `enrichmentStartedAt - lookbackBufferSeconds` (configured via `cp.elasticsearch.lookback-buffer-seconds`, default: 900s / 15m)
* Any queries or anchors based on historical `endTime` or creation timestamps have been reverted to ensure queries only observe events from the current reload cycle.

### Timezone Handling: Elasticsearch (UTC) vs. Kibana (Browser Local Time)

> [!TIP]
> **Elasticsearch JSON Logs & Ingestion**:
> All `@timestamp` values streamed and stored in Elasticsearch are recorded in **UTC (ISO-8601 ending with `Z`)**, for example:
> `{"@timestamp": "2026-09-04T07:11:32.967Z"}` or `["2026-08-20T23:25:30.319Z"]`.
>
> **Kibana Display**:
> Kibana automatically translates the raw UTC timestamp from Elasticsearch into the user's browser local timezone for display (e.g. `Aug 21, 2026 @ 01:25:30...` for UTC+2, or morning for Asian/European timezones). This is purely a UI presentation layer transformation.
>
> **Backend Query Alignment**:
> 1. In `ElasticsearchLogService.java`, the query uses `java.time.Instant.toString()`, which formats the lower bound as a standard UTC ISO-8601 string (e.g. `2026-09-04T07:40:10.652Z`). This directly matches Elasticsearch's native indexed `@timestamp` in UTC without timezone offset mismatch.
> 2. `CpLogMonitor.java` passes `lookbackTime` (`enrichmentStartedAt`) directly to `findCpLog`, preventing double-subtraction of `lookbackBufferSeconds`.
> 3. Database session timezone is configured to UTC (`ALTER SESSION SET TIME_ZONE = 'UTC'` and `oracle.jdbc.timezoneAsRegion=false`) so timestamps saved in Oracle/Postgres align consistently with UTC instants.
> 4. In contrast, `pp_log` (Oracle table written by local ETL) uses `ppLogDbProperties.getServerTimezone()` to convert UTC to the database server's local time before querying.

---

## 2. Root Cause Analysis & Query Relaxations

### A. Strict `must` Filter on `idFile`
* **Issue**: `idFile` (metadata ID) was originally specified in the `must` array. If CP indexed the document without `idFile`, mapped it under a different field type, or if a reprocessed file was assigned a different stage record ID, the query returned **zero hits**.
* **Fix**: Moved `idFile` from `must` to `should` with a relevance boost:
  ```json
  {
    "term": {
      "idFile": {
        "value": "...",
        "boost": 5
      }
    }
  }
  ```
  This prioritizes matching documents without eliminating valid hits when `idFile` is missing or mismatched.

### B. Strict `must` Filter on `inputFileName`
* **Issue**: Placing `inputFileName` in `must` caused zero hits when the file was renamed during staging, preprocessed, or indexed under an alternate name pattern by CP.
* **Fix**: Moved `inputFileName` from `must` to `should` with a relevance boost:
  ```json
  {
    "wildcard": {
      "inputFileName": {
        "value": "*<nameBase>*",
        "case_insensitive": true,
        "boost": 4
      }
    }
  }
  ```

### C. Core Required Filter (`idData`)
* **Retained in `must`**: `idData` (data ID) remains the definitive unique identifier for the payload across CP and Exensio pipelines.

---

## 3. Early Loader Error Recovery in Exensio

### Early Rejection Limitation
When files fail early during the raw data loader phase, Exensio does not create entries in `OP_LOG` or `LOT`. If the system only queries `OP_LOG` by data ID, it will return empty errors even though a loader failure occurred.

### Multi-Tier Error Resolution
1. **Tier 1 (OP_LOG)**: Query `OP_LOG` by `idData` for processing and rule execution errors.
2. **Tier 2 (Fallback via DP_LOG / RAW_FILE)**: If Tier 1 returns no results, query `DP_LOG` and `RAW_FILE` by filename and data ID to capture early-stage loader rejections and file parse errors.

---

## 4. Query Structure Comparison

| Query Component | Previous Behavior | Updated Behavior | Rationale |
|---|---|---|---|
| **`idData`** | `must` (exact) | `must` (exact) | Primary unique identifier across CP logs |
| **`@timestamp`** | `gte = enrichmentStartedAt - buffer` | `gte = enrichmentStartedAt - buffer` | Strictly isolates logs from the current reload cycle |
| **`idFile`** | `must` (exact) | `should` (boost: 5) | Prevents zero-hits when field is omitted/differently indexed |
| **`inputFileName`** | `must` (wildcard) | `should` (boost: 4) | Matches even if reprocessed under alternate staging name |
| **`cpConfig`** | `must` (default `*sender*`) | `must` (with retry to `*`) | Targeted filter with fallback for other sender configs |
| **`minimum_should_match`** | `1` | `1` | Ensures boost criteria contribute to relevant hit selection |

---

## 5. Modified Source Files

- [`ElasticsearchLogService.java`](file:///c:/Users/fg8n8x/Desktop/wip/exensioreload/backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/service/ElasticsearchLogService.java):
  - Moved `idFile` and `inputFileName` to `should` clauses with boosts.
  - Retained `enrichmentStartedAt` as the `@timestamp` lookback anchor.
- [`CpLogMonitor.java`](file:///c:/Users/fg8n8x/Desktop/wip/exensioreload/backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/service/CpLogMonitor.java):
  - Standardized `findCpLog` invocation passing current reload anchor time.
- [`ExensioClient.java`](file:///c:/Users/fg8n8x/Desktop/wip/exensioreload/backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/service/ExensioClient.java):
  - Added early-loader error fallback querying `DP_LOG` / `RAW_FILE`.
- [`ExensioLoadMonitor.java`](file:///c:/Users/fg8n8x/Desktop/wip/exensioreload/backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/service/ExensioLoadMonitor.java):
  - Updated calls to use the fallback-capable error lookup method.
