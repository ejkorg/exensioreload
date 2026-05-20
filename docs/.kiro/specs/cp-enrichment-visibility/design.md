# Design Document: CP Enrichment Visibility

## Overview

This feature closes the visibility gap between CP consuming a file from the sender queue and the file being fully loaded into Exensio. It introduces a new scheduled service (`CpLogMonitor`) that polls Elasticsearch for CP-written logs, uses them to drive accurate status transitions (`ENRICHMENT` → `EXENSIO_LOADING` or `FAILED`), and stores the enriched file output path and target (PRODUCTION/SANDBOX) for reporting. It also fixes the immediate bug where queue consumption incorrectly triggers `DONE`.

## Architecture

```
SenderQueueMonitor (existing, every 10s)
  └─ detects record left DTP_SENDER_QUEUE_ITEM
  └─ marks ENRICHMENT (was: DONE) ← BUG FIX

CpLogMonitor (new, every 60s)
  └─ loads all ENRICHMENT records from SENDER_STAGE
  └─ queries Elasticsearch logs*dataport* index
  └─ matches by idData → data_id, confirmed by mLot → lot
  └─ on success log (output path in message, no error.*):
       → marks EXENSIO_LOADING, stores cp_output_path, cp_output_target
  └─ on failure log (error.type or error.message present):
       → marks FAILED, stores error_message
  └─ on timeout (>30min in ENRICHMENT, no log found):
       → marks FAILED with timeout message

Future (not in scope):
  ExensioLoadMonitor → EXENSIO_LOADING → DONE/FAILED
```

## Components and Interfaces

### Backend — New Components

#### `CpLogMonitor.java`
- `@Component`, `@Scheduled(fixedDelayString = "${cp.elasticsearch.poll-interval-ms:60000}")`
- Injected: `RefDbService`, `ElasticsearchLogService`, `StageMonitorService`
- Main method: `monitorEnrichmentRecords()`
  1. Load all `ENRICHMENT` records from `SENDER_STAGE`
  2. For each record, call `ElasticsearchLogService.findCpLog(dataId, lot, enrichmentStartedAt)`
  3. Evaluate result and call appropriate `RefDbService` method

#### `ElasticsearchLogService.java`
- `@Service`
- Wraps the ES REST client
- Method: `CpLogResult findCpLog(String dataId, String lot, Instant since)`
- Returns a sealed result type:
  ```java
  sealed interface CpLogResult {
      record Success(String outputPath, String outputTarget, Instant logTimestamp) implements CpLogResult {}
      record Failure(String errorMessage, Instant logTimestamp) implements CpLogResult {}
      record NotFound() implements CpLogResult {}
  }
  ```

#### `CpLogResult.java`
- Sealed interface as above — clean discriminated union, no nulls

### Backend — Modified Components

#### `SenderQueueMonitor.java`
- Change: `markCompletedRecords()` → `markEnrichmentRecords()` when record leaves queue
- Single line change in `monitorQueue()`

#### `RefDbService.java`
- New method: `markEnrichmentRecords(List<StageRecord>)` — sets status to `ENRICHMENT`, broadcasts SSE
- New method: `markExensioLoading(StageRecord, String outputPath, String outputTarget)` — sets status to `EXENSIO_LOADING`, stores path/target, broadcasts SSE
- Existing `markFailed()` already handles failure — reuse with enrichment error message

### Database Changes

#### Liquibase changeset: `db.changelog-9.3-cp-enrichment-columns.xml`
```sql
ALTER TABLE SENDER_STAGE ADD (
    cp_output_path    VARCHAR2(1000),
    cp_output_target  VARCHAR2(20)
)
```

#### `StageRecord.java` — add two fields:
```java
String cpOutputPath,
String cpOutputTarget
```

#### `StageRecordView.java` — add two fields:
```java
String cpOutputPath,
String cpOutputTarget
```

### Elasticsearch Query Design

**Index:** `logs*dataport*`

**Query per record:**
```json
{
  "query": {
    "bool": {
      "must": [
        { "wildcard": { "cpConfig": { "value": "*sender*", "case_insensitive": true } } },
        { "term":    { "idData": "<data_id>" } },
        { "term":    { "mLot":  "<lot>" } },
        { "range":   { "@timestamp": { "gte": "<enrichment_started_at>" } } }
      ]
    }
  },
  "sort": [{ "@timestamp": "desc" }],
  "size": 10
}
```

**Success detection:** Any hit where `message` contains `"output path"` AND `error.type` is null/absent AND `error.message` is null/absent.

**Failure detection:** Any hit where `error.type` is non-null OR `error.message` is non-null.

**Output path extraction:** Parse `message` field with regex `output path\s*=\s*(.+)` → capture group 1 is the path.

**PRODUCTION/SANDBOX detection:** 
```java
if (path.toUpperCase().contains("PRODUCTION")) return "PRODUCTION";
if (path.toUpperCase().contains("SANDBOX"))    return "SANDBOX";
return "UNKNOWN";
```

**Timeout check:** If `Instant.now().minus(30min).isAfter(record.updatedAt())` and `NotFound` → mark `FAILED`.

### Configuration (`application.yml`)

```yaml
cp:
  elasticsearch:
    url: ${CP_ES_URL:https://elasticsearch:9200}
    api-key: ${CP_ES_API_KEY:}          # preferred if set
    username: ${CP_ES_USERNAME:}         # basic auth fallback
    password: ${CP_ES_PASSWORD:}         # basic auth fallback
    index-pattern: logs*dataport*
    cp-config-filter: "*sender*"
    poll-interval-ms: 60000
    enrichment-timeout-minutes: 30
```

**Auth precedence:** If `api-key` is non-blank → use API key auth. Else if `username` + `password` are both non-blank → use basic auth. Else → unauthenticated (for local/dev ES).

### ES Client

Use the official `co.elastic.clients:elasticsearch-java` client (already a common Spring Boot dependency). Configure a `RestClient` bean from the `cp.elasticsearch` properties. If the URL is blank/unconfigured, `CpLogMonitor` skips all polling cycles with a `WARN` log — no records are affected.

## Data Models

### `SENDER_STAGE` table additions

| Column | Type | Nullable | Description |
|---|---|---|---|
| `cp_output_path` | VARCHAR2(1000) | YES | Full output folder path from CP success log |
| `cp_output_target` | VARCHAR2(20) | YES | `PRODUCTION`, `SANDBOX`, or `UNKNOWN` |

### `StageRecordView` additions

```java
String cpOutputPath,   // nullable
String cpOutputTarget  // nullable: "PRODUCTION", "SANDBOX", "UNKNOWN"
```

### SSE event payloads

**ENRICHMENT transition:**
```json
{ "id": 123, "status": "ENRICHMENT", "msg": "Consumed by CP (processing)" }
```

**EXENSIO_LOADING transition:**
```json
{
  "id": 123,
  "status": "EXENSIO_LOADING",
  "msg": "Exensio Loading",
  "cpOutputPath": "/apps/fecim/data/phd/DataPort/files/PRODUCTION/...",
  "cpOutputTarget": "PRODUCTION"
}
```

## Correctness Properties

*A property is a characteristic or behavior that should hold true across all valid executions — a formal statement about what the system should do.*

### Prework Analysis

**1.1** WHEN a record disappears from `DTP_SENDER_QUEUE_ITEM`, THE system SHALL update status to `ENRICHMENT` not `DONE`.
- Thoughts: Testable — we can mock the queue monitor, simulate a record leaving the queue, and assert the resulting status is `ENRICHMENT`.
- Testable: yes — example

**1.3** WHILE a record has status `ENRICHMENT`, THE system SHALL NOT mark it as `DONE` without a confirmed ES success signal.
- Thoughts: Invariant — for all records in `ENRICHMENT`, `DONE` is only reachable via `EXENSIO_LOADING`. We can generate random records and verify the state machine.
- Testable: yes — property

**2.6** THE `CpLogMonitor` SHALL only consider ES logs with `@timestamp >= enrichment_started_at`.
- Thoughts: Testable — generate logs with timestamps before and after the enrichment start, verify only post-start logs are matched.
- Testable: yes — property

**3.4** THE system SHALL determine `cp_output_target` by checking for `PRODUCTION` or `SANDBOX` in the path.
- Thoughts: Pure function, easily property-tested with arbitrary path strings.
- Testable: yes — property

**4.3** WHEN enrichment fails, THE system SHALL store the error message truncated to 500 characters.
- Thoughts: Edge case — test with strings of length 0, 499, 500, 501, and very large.
- Testable: yes — property (edge case)

**2.7** IF no ES log found within 30 minutes, THE system SHALL mark as `FAILED`.
- Thoughts: Testable with a mocked clock — set `updatedAt` to 31 minutes ago, verify `FAILED`.
- Testable: yes — example

### Property Reflection

Properties 1.3 and 2.6 are independent — one is about state machine invariants, the other about timestamp filtering. No redundancy.

### Correctness Properties

Property 1: Status machine — ENRICHMENT never skips to DONE
*For any* `SENDER_STAGE` record in `ENRICHMENT` status, the only valid next statuses are `EXENSIO_LOADING` or `FAILED`. A direct transition to `DONE` must never occur.
**Validates: Requirements 1.3**

Property 2: Timestamp guard on ES log matching
*For any* set of ES log entries where some have `@timestamp` before and some after `enrichment_started_at`, only entries with `@timestamp >= enrichment_started_at` are considered for status transitions.
**Validates: Requirements 2.6**

Property 3: Output target detection is total and deterministic
*For any* non-null output path string, `detectOutputTarget(path)` returns exactly one of `PRODUCTION`, `SANDBOX`, or `UNKNOWN` — never null, never throws.
**Validates: Requirements 5.3**

Property 4: Error message truncation
*For any* error message string of length N, the stored `error_message` has length `min(N, 500)` and equals the first 500 characters of the original.
**Validates: Requirements 4.5**

## Error Handling

| Scenario | Behaviour |
|---|---|
| ES unreachable | Log `WARN`, skip cycle, no status changes |
| ES returns empty result | `NotFound` — check timeout, otherwise wait next cycle |
| ES query times out | Log `WARN`, skip cycle |
| `message` field missing output path pattern | Treat as `NotFound` for that record |
| `data_id` matches multiple ES logs | Take the most recent (`@timestamp desc`, `size: 1` after filtering) |
| Record already left `ENRICHMENT` (race) | Skip — `markExensioLoading` checks current status before updating |
| `cp_output_target` cannot be determined | Store `UNKNOWN`, do not block transition |

## Testing Strategy

### Unit Tests
- `ElasticsearchLogService`: mock ES client, test success/failure/not-found parsing
- `CpLogMonitor`: mock `ElasticsearchLogService` and `RefDbService`, test all transition paths
- Output target detection: test `PRODUCTION`, `SANDBOX`, `UNKNOWN`, mixed-case paths
- Error message truncation: boundary values 0, 499, 500, 501, 10000 chars

### Property-Based Tests (using jqwik)
- **Property 1:** Generate random `StageRecord` objects in `ENRICHMENT`, run through all possible `CpLogResult` variants, assert resulting status is never `DONE` directly
  - *Feature: cp-enrichment-visibility, Property 1: Status machine — ENRICHMENT never skips to DONE*
- **Property 2:** Generate random lists of ES log timestamps relative to `enrichment_started_at`, assert only post-start logs are selected
  - *Feature: cp-enrichment-visibility, Property 2: Timestamp guard on ES log matching*
- **Property 3:** Generate arbitrary path strings (including empty, null-safe wrappers, paths with both keywords), assert `detectOutputTarget` always returns a non-null value from the allowed set
  - *Feature: cp-enrichment-visibility, Property 3: Output target detection is total and deterministic*
- **Property 4:** Generate strings of random length, assert truncation result length and content
  - *Feature: cp-enrichment-visibility, Property 4: Error message truncation*

### Integration Tests
- `CpLogMonitor` with a real embedded ES (Testcontainers) or WireMock for the ES REST API
- Full cycle: record enters `ENRICHMENT` → ES log inserted → monitor runs → status becomes `EXENSIO_LOADING` with correct path/target
- Timeout cycle: record enters `ENRICHMENT` with `updatedAt` 31 minutes ago → no ES log → status becomes `FAILED`
