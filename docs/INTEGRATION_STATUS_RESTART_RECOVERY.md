# Integration Status Restart Recovery & Pipeline Stage Persistence

## 1. Executive Summary

When the backend service restarts while requests/sessions have records in active pipeline stages beyond Elasticsearch (e.g., `EXENSIO_MONITORING` or `COMPLETED`), the UI previously displayed:

```text
Integrations
Elasticsearch     ⏱ Waiting for first check
Exensio           ⏳ Exensio wafer not found yet — retrying
```

This gave the misleading impression that the application lost its stage progression and regressed to checking Elasticsearch ("Enrichment Processing"), even though the records in the database were already safely in `EXENSIO_MONITORING` and the backend `ExensioLoadMonitor` was actively polling Exensio.

This document details the root cause, architecture, and solution implemented across backend services to ensure state progression is reliably recovered and accurately reflected in both session-level and record-level integration cards post-restart.

---

## 2. Problem Symptoms & Observed Behavior

### 2.1 Observed Symptoms
- In the dashboard and file list, status counters and distributions showed:
  - **Total Files**: 1
  - **Staged**: 0
  - **Queued for Enrichment**: 0
  - **Enrichment Processing (`ELASTICSEARCH_MONITORING`)**: 0
  - **Exensio Monitoring (`EXENSIO_MONITORING`)**: 1
  - **Completed**: 0
  - **Failed**: 0
- Backend logs confirmed `ExensioLoadMonitor` and `ExensioClient` were executing SQL queries against Exensio's `raw-sql` API to detect wafer loading.
- However, in the **Integrations** panel, Elasticsearch displayed:
  - Status: `pending`
  - Message: `"Waiting for first check"` (with an active spinner / pending clock icon)
- The user interpreted this as the backend forgetting it had already advanced past Elasticsearch enrichment and erroneously restarting Elasticsearch monitoring.

---

## 3. Root Cause Analysis

### 3.1 Ephemeral In-Memory State vs. Persistent Database State
- Pipeline state is persisted in the database staging table (`status = 'EXENSIO_MONITORING'`, `cp_output_path`, `cp_output_target`, etc.).
- However, detailed diagnostic snapshots displayed in the **Integrations** card were managed exclusively by [`IntegrationStatusService`](file:///c:/Users/fg8n8x/Desktop/wip/exensioreload/backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/service/IntegrationStatusService.java) in memory:
  ```java
  private final ConcurrentHashMap<String, IntegrationStatus> esStatusByRequest = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, IntegrationStatus> exensioStatusByRequest = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<Long, IntegrationStatus> cpStatusByRecord = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<Long, IntegrationStatus> exensioStatusByRecord = new ConcurrentHashMap<>();
  ```
- Upon backend restart, these maps were completely empty.

### 3.2 Stage Poller Query Segregation
- Each pipeline monitor queries only records in its dedicated lifecycle state:
  - [`CpLogMonitor`](file:///c:/Users/fg8n8x/Desktop/wip/exensioreload/backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/service/CpLogMonitor.java):
    ```java
    enrichmentRecords = refDbService.listRecords(null, null, "ELASTICSEARCH_MONITORING", Integer.MAX_VALUE);
    ```
  - [`ExensioLoadMonitor`](file:///c:/Users/fg8n8x/Desktop/wip/exensioreload/backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/service/ExensioLoadMonitor.java):
    ```java
    records = refDbService.listRecords(null, null, "EXENSIO_MONITORING", Integer.MAX_VALUE);
    ```
- Because the record had already passed `ELASTICSEARCH_MONITORING` prior to restart:
  1. `CpLogMonitor` found **0 records** in `ELASTICSEARCH_MONITORING` for this request and never executed an Elasticsearch query.
  2. `integrationStatusService.updateElasticsearch(requestId, ...)` was **never called** post-restart.
  3. `ExensioLoadMonitor` found the 1 record in `EXENSIO_MONITORING`, executed Exensio queries, and populated `exensioStatusByRequest`.

### 3.3 Blind Defaulting in `snapshot()` & `StageRecordMapper`
- When the frontend polled the session endpoint or received SSE `STATS` events:
  - `esStatusByRequest.get(requestId)` was `null`.
  - `IntegrationStatusService.toMap(...)` defaulted any unconfigured or unpopulated status to:
    ```java
    if (status == null) {
        out.put("status", "pending");
        out.put("message", "Waiting for first check");
        out.put("lastAt", null);
        return out;
    }
    ```
  - Similarly, in [`StageRecordMapper`](file:///c:/Users/fg8n8x/Desktop/wip/exensioreload/backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/controller/StageRecordMapper.java), when `cpStatusByRecord.get(record.id())` returned `null`, records in `EXENSIO_MONITORING` or `COMPLETED` defaulted `cpIntegrationStatus` to `"not_configured"`.

---

## 4. Architecture & Implementation Fix

To resolve this without introducing unnecessary database tables or circular dependencies, the solution implements **stage-aware state derivation**: when in-memory integration status is absent, the system infers the integration state from persistent record stages and counts.

```
+-----------------------------------------------------------------------------------+
|                                 Backend Restart                                   |
|                          (In-memory maps initialized empty)                       |
+-----------------------------------------+-----------------------------------------+
                                          |
                   Session Request / SSE STATS / Record View
                                          |
                 +------------------------v------------------------+
                 |       Check In-Memory Status in Service         |
                 +------------------------+------------------------+
                                          |
                        +-----------------+-----------------+
                        |                                   |
                  Status Present                      Status Null
                        |                                   |
           Return live poller status            Evaluate Record Stage
                                                Counts & Fields
                                                            |
                                      +---------------------+---------------------+
                                      |                                           |
                           Records in EXENSIO_MONITORING          Records in ELASTICSEARCH_MONITORING
                           or COMPLETED (enriching == 0)                          |
                                      |                               Status: "pending"
                               Status: "success"                      Message: "Monitoring ES logs"
                               Message: "Completed"
```

### 4.1 Enhanced `IntegrationStatusService`
[`IntegrationStatusService.java`](file:///c:/Users/fg8n8x/Desktop/wip/exensioreload/backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/service/IntegrationStatusService.java) was expanded with an overloaded `snapshot(...)` method accepting stage counts:

- **Elasticsearch Mapping (`toEsMap`)**:
  - If `status != null`: returns live in-memory status.
  - If `status == null` and `enrichingCount == 0 && (exensioCount > 0 || completedCount > 0)`:
    - Returns `status = "success"`, `message = "Completed"`.
  - If `enrichingCount > 0`:
    - Returns `status = "pending"`, `message = "Monitoring Elasticsearch logs"`.
  - If `queuedCount > 0`:
    - Returns `status = "pending"`, `message = "Waiting for CP dispatch"`.
  - If `stagedCount > 0`:
    - Returns `status = "pending"`, `message = "Waiting for staging/dispatch"`.

- **Exensio Mapping (`toExensioMap`)**:
  - If `status != null`: returns live in-memory status.
  - If `status == null` and `completedCount > 0` with no active files remaining:
    - Returns `status = "success"`, `message = "Completed"`.
  - If `exensioCount > 0`:
    - Returns `status = "pending"`, `message = "Monitoring Exensio load"`.
  - If `enrichingCount > 0 || queuedCount > 0 || stagedCount > 0`:
    - Returns `status = "pending"`, `message = "Awaiting previous stages"`.

### 4.2 Hydration in `RefDbService.broadcastStats()`
[`RefDbService.java`](file:///c:/Users/fg8n8x/Desktop/wip/exensioreload/backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/service/RefDbService.java):
`broadcastStats()` computes `ready`, `enqueued`, `enriching`, `exensioLoading`, `completed`, and `failed` for SSE updates. It now passes these directly into `integrationStatusService.snapshot(...)`:
```java
if (integrationStatusService != null) {
    boolean esConfigured = elasticsearchProperties != null && elasticsearchProperties.isConfigured();
    boolean exensioConfigured = exensioProperties != null && exensioProperties.isConfigured();
    evt.put("integration", integrationStatusService.snapshot(
            requestId, esConfigured, exensioConfigured,
            ready, enqueued, enriching, exensioLoading, completed, failed
    ));
}
```

### 4.3 Hydration in `StageSessionService.buildIntegrationSnapshot()`
[`StageSessionService.java`](file:///c:/Users/fg8n8x/Desktop/wip/exensioreload/backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/service/StageSessionService.java):
When building session details (`GET /api/v1/stage/sessions/{id}`), it performs a fast aggregation on the staging table for `requestId` and passes the resulting counts into `integrationStatusService.snapshot(...)`.

### 4.4 Record-Level Fallback in `StageRecordMapper`
[`StageRecordMapper.java`](file:///c:/Users/fg8n8x/Desktop/wip/exensioreload/backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/controller/StageRecordMapper.java):
When mapping individual records (`StageRecordView`):
- **CP / Elasticsearch Status**:
  - If `cpStatus == null` and the record has `cpOutputPath != null`, `status = 'EXENSIO_MONITORING'`, or `status = 'COMPLETED'`:
    - `cpIntegrationStatus = "success"`
    - `cpIntegrationMessage = "CP logs verified"`
- **Exensio Status**:
  - If `exensioStatus == null` and the record has `status = 'COMPLETED'` or `exensioWaferKey != null`:
    - `exensioIntegrationStatus = "success"`
    - `exensioIntegrationMessage = "Loaded in Exensio"`
  - If `status = 'EXENSIO_MONITORING'`:
    - `exensioIntegrationStatus = "pending"`
    - `exensioIntegrationMessage = "Monitoring Exensio load"`

---

## 5. State Resolution Matrix (Post-Restart)

| Persistent Record / Session State | In-Memory ES Status | In-Memory Exensio Status | Resolved ES Status | Resolved Exensio Status |
| :--- | :--- | :--- | :--- | :--- |
| `STAGED` | `null` | `null` | `pending` ("Waiting for staging/dispatch") | `pending` ("Awaiting previous stages") |
| `QUEUED_FOR_CP` | `null` | `null` | `pending` ("Waiting for CP dispatch") | `pending` ("Awaiting previous stages") |
| `ELASTICSEARCH_MONITORING` | `null` | `null` | `pending` ("Monitoring Elasticsearch logs") | `pending` ("Awaiting previous stages") |
| `EXENSIO_MONITORING` | `null` | `not_found` (from Exensio poll) | **`success` ("Completed")** | `not_found` (Live poller message) |
| `EXENSIO_MONITORING` (pre-poll) | `null` | `null` | **`success` ("Completed")** | `pending` ("Monitoring Exensio load") |
| `COMPLETED` | `null` | `null` | **`success` ("Completed")** | **`success` ("Completed")** |
| `CP_FAILED` | `null` | `null` | `failure` ("CP processing failed") | `not_configured` |
| `LOAD_FAILED` | `null` | `null` | **`success` ("Completed")** | `failure` ("Exensio load failed") |

---

## 6. Files Modified

1. [`backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/service/IntegrationStatusService.java`](file:///c:/Users/fg8n8x/Desktop/wip/exensioreload/backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/service/IntegrationStatusService.java)
2. [`backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/service/RefDbService.java`](file:///c:/Users/fg8n8x/Desktop/wip/exensioreload/backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/service/RefDbService.java)
3. [`backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/service/StageSessionService.java`](file:///c:/Users/fg8n8x/Desktop/wip/exensioreload/backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/service/StageSessionService.java)
4. [`backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/controller/StageRecordMapper.java`](file:///c:/Users/fg8n8x/Desktop/wip/exensioreload/backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/controller/StageRecordMapper.java)
