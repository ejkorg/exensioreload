# Design Document: CP Enrichment idFile + idData Matching

## Overview

The CP enrichment pipeline queries Elasticsearch to confirm that a staged file has been processed by CP. Currently the query filters only by `idData`, which can be ambiguous when multiple files share the same data ID. The metadata view exposes both `id` (`idFile`) and `id_data` (`idData`), and both are already stored on `SENDER_STAGE` as `metadata_id` and `data_id`.

This design:
1. Adds `idFile` as a second `must` term filter in the ES query alongside `idData`.
2. Replaces the `service.environment` + "output path" success detection with a `should`-clause approach that scores hits by PRODUCTION/SANDBOX keywords and error level.
3. Adds a RefDB `pp_log` fallback path for when the ES message indicates success but contains no PRODUCTION/SANDBOX keyword.
4. Routes `pp_log` queries through `RefDbService` for consistent connection pooling.

## Architecture

```
CpLogMonitor.processRecord(record)
  └─ ElasticsearchLogService.findCpLog(idFile, dataId, lot, since, site)
       └─ buildQuery(idFile, dataId, lot, since, site)
            ├─ must: cpConfig wildcard
            ├─ must: idFile term  ← NEW
            ├─ must: idData term
            ├─ must: @timestamp range
            └─ should: PRODUCTION boost / SANDBOX boost / non-ERROR boost / ERROR boost
                       minimum_should_match: 1
       └─ parseResponse(body, idFile, dataId, lot)
            ├─ log.level = ERROR  → CpLogResult.Failure(message)
            ├─ message contains PRODUCTION → CpLogResult.Success(path, "PRODUCTION")
            ├─ message contains SANDBOX   → CpLogResult.Success(path, "SANDBOX")
            ├─ message contains "executed successfully"
            │    └─ RefDbService.queryPpLogSuccess(lot, idFile)
            │         ├─ row found  → CpLogResult.Success(output_directory, target)
            │         └─ no row     → RefDbService.queryPpLogError(lot, idFile)
            │              ├─ row found  → CpLogResult.Failure(log_message)
            │              └─ no row     → CpLogResult.NotFound
            └─ no match → CpLogResult.NotFound
```

## Components and Interfaces

### Modified: `ElasticsearchLogService`

**`findCpLog` signature change**

```java
// Before
public CpLogResult findCpLog(String dataId, String lot, Instant since, String site)

// After
public CpLogResult findCpLog(String idFile, String dataId, String lot, Instant since, String site)
```

`idFile` maps to `StageRecord.metadataId()`. When null or blank, the `idFile` term filter is omitted and the query falls back to `idData`-only behavior.

**`buildQuery` changes**

The `must` array gains an `idFile` term clause (when non-blank):

```json
{ "term": { "idFile": "<metadataId>" } }
```

The `should` array replaces the old success-detection logic:

```json
"should": [
  { "wildcard": { "message": { "value": "*output path*PRODUCTION*", "case_insensitive": true, "boost": 4 } } },
  { "wildcard": { "message": { "value": "*SANDBOX*",               "case_insensitive": true, "boost": 3 } } },
  { "bool": { "must_not": [{ "term": { "log.level": "ERROR" } }], "boost": 3 } },
  { "term": { "log.level": { "value": "ERROR", "boost": 1 } } }
],
"minimum_should_match": 1
```

The `_source` field list is extended to include `idFile` and `idData`:

```json
"_source": ["@timestamp", "cpConfig", "idData", "idFile", "message", "log.level"]
```

**`parseResponse` changes**

Priority order when evaluating each hit:

1. `log.level == ERROR` → `CpLogResult.Failure(message or "CP processing error")`
2. `message` contains `PRODUCTION` (case-insensitive) → `CpLogResult.Success(message, "PRODUCTION")`
3. `message` contains `SANDBOX` (case-insensitive) → `CpLogResult.Success(message, "SANDBOX")`
4. `message` contains `executed successfully` (case-insensitive) → delegate to `RefDbService.queryPpLogSuccess` / `queryPpLogError`
5. No match → continue to next hit; if all hits exhausted → `CpLogResult.NotFound`

The old `service.environment` check and `OUTPUT_PATH_PATTERN` regex are removed from the hit-evaluation loop (the `extractOutputPath` and `detectOutputTarget` helpers are retained for use with `pp_log` `output_directory` values).

### Modified: `CpLogMonitor`

`processRecord` passes `record.metadataId()` as the new first argument:

```java
result = elasticsearchLogService.findCpLog(
    record.metadataId(), record.dataId(), record.lot(), enrichmentStartedAt, record.site());
```

### Modified: `RefDbService`

Two new public methods added for `pp_log` queries:

```java
/**
 * Queries pp_log for a successful CP run (process_code = 0).
 * Returns the output_directory of the first matching row, or null if none found.
 */
public String queryPpLogSuccess(String lot, String idFile)

/**
 * Queries pp_log for a failed CP run (process_code != 0).
 * Returns the log_message of the first matching row, or null if none found.
 */
public String queryPpLogError(String lot, String idFile)
```

SQL for success query:
```sql
SELECT output_directory FROM pp_log
WHERE lot = ?
  AND (extension LIKE ? OR file_name LIKE ?)
  AND process_code = 0
  FETCH FIRST 1 ROWS ONLY
```

SQL for error query:
```sql
SELECT log_message FROM pp_log
WHERE lot = ?
  AND (extension LIKE ? OR file_name LIKE ?)
  AND process_code != 0
  FETCH FIRST 1 ROWS ONLY
```

Both methods bind `lot` as-is and `'%' + idFile + '%'` for the LIKE parameters.

`ElasticsearchLogService` receives `RefDbService` as a constructor dependency (injected by Spring) and calls these methods when the `pp_log` fallback path is triggered.

## Data Models

No schema changes are required. `idFile` and `idData` are already stored on `SENDER_STAGE` as `metadata_id` and `data_id`. The `pp_log` table is read-only from this service's perspective.

### pp_log columns used

| Column | Type | Usage |
|---|---|---|
| `lot` | VARCHAR | Filter: `WHERE lot = ?` |
| `extension` | VARCHAR | Filter: `LIKE '%<idFile>%'` |
| `file_name` | VARCHAR | Filter: `LIKE '%<idFile>%'` |
| `process_code` | NUMBER | Filter: `= 0` (success) or `!= 0` (error) |
| `output_directory` | VARCHAR | Read: used as output path for target detection |
| `log_message` | VARCHAR | Read: used as error message on failure |

## Correctness Properties

*A property is a characteristic or behavior that should hold true across all valid executions of a system — essentially, a formal statement about what the system should do. Properties serve as the bridge between human-readable specifications and machine-verifiable correctness guarantees.*

### Property 1: Both idFile and idData appear in the must array

*For any* non-blank `idFile` and `idData` pair, the ES query built by `ElasticsearchLogService.buildQuery` SHALL contain both a `term` clause for `idFile` and a `term` clause for `idData` in the `must` array, with the correct values bound.

**Validates: Requirements 1.1, 1.2, 1.3**

### Property 2: Message-based output target detection is exhaustive

*For any* `message` string:
- When the message contains `PRODUCTION` (any case) → `detectOutputTarget` returns `"PRODUCTION"`
- When the message contains `SANDBOX` (any case) → `detectOutputTarget` returns `"SANDBOX"`
- When the message contains neither → `detectOutputTarget` returns `"UNKNOWN"`

**Validates: Requirements 2.6, 2.7**

### Property 3: ERROR log.level hits are always failures regardless of message content

*For any* ES hit where `log.level` equals `ERROR` (any case), `parseResponse` SHALL return `CpLogResult.Failure`, even when the `message` field contains `PRODUCTION` or `SANDBOX`.

**Validates: Requirements 4.1, 4.3**

### Property 4: pp_log success path returns CpLogResult.Success with output_directory

*For any* `output_directory` string returned by `pp_log` (with `process_code = 0`), `RefDbService.queryPpLogSuccess` SHALL return that string, and the calling code SHALL wrap it in `CpLogResult.Success` with the target derived from `detectOutputTarget(output_directory)`.

**Validates: Requirements 3.3, 3.4**

### Property 5: pp_log error path returns CpLogResult.Failure with log_message

*For any* `log_message` string returned by `pp_log` (with `process_code != 0`), `RefDbService.queryPpLogError` SHALL return that string, and the calling code SHALL wrap it in `CpLogResult.Failure` with that message.

**Validates: Requirements 3.5, 3.6**

### Property 6: Null or blank metadataId omits the idFile term filter

*For any* call to `buildQuery` where `idFile` is null or blank, the resulting ES query JSON SHALL NOT contain a `term` clause for `idFile` in the `must` array.

**Validates: Requirements 7.1**

## Error Handling

- **Null/blank `idFile`**: `buildQuery` skips the `idFile` term clause silently. No exception thrown.
- **`pp_log` `SQLException`**: Caught in `ElasticsearchLogService`; logs a warning and returns `CpLogResult.NotFound` so the record retries next cycle.
- **`RefDbService` unavailable**: `ElasticsearchLogService` guards with a null check; returns `CpLogResult.NotFound`.
- **ES hit with `log.level: ERROR` and blank message**: Falls back to `"CP processing error"` as the error message.
- **`pp_log` returns no rows for either query**: Returns `CpLogResult.NotFound` — record retries next cycle.

## Testing Strategy

### Unit tests (JUnit 5)

- `ElasticsearchLogServiceTest`: extend existing tests to verify `idFile` term appears in `must` array; verify `should` clauses are present; verify `minimum_should_match: 1`.
- `ElasticsearchLogServiceParseResponseTest`: verify ERROR priority, PRODUCTION/SANDBOX detection, "executed successfully" fallback trigger.
- `RefDbServicePpLogTest`: verify `queryPpLogSuccess` and `queryPpLogError` bind parameters correctly and return expected values.

### Property-based tests (jqwik)

Each correctness property maps to one `@Property` test:

- **P1** — `@Property` generates random non-blank `idFile` and `idData` strings; asserts both appear as `term` clauses in the `must` array of the built query JSON.
- **P2** — `@Property` generates random message strings (including those containing PRODUCTION, SANDBOX, both, or neither in random case); asserts `detectOutputTarget` returns the correct value.
- **P3** — `@Property` generates random ES hit JSON with `log.level: ERROR` and random message content (including PRODUCTION/SANDBOX); asserts `parseResponse` returns `CpLogResult.Failure`.
- **P4** — `@Property` generates random `output_directory` strings; asserts `queryPpLogSuccess` returns the string and the result is `CpLogResult.Success` with the correct target.
- **P5** — `@Property` generates random `log_message` strings; asserts `queryPpLogError` returns the string and the result is `CpLogResult.Failure` with that message.
- **P6** — `@Property` generates null and blank `idFile` values; asserts the built query JSON does not contain an `idFile` term clause in `must`.

Each property test runs a minimum of 100 iterations.
Tag format: `// Feature: cp-enrichment-id-file-matching, Property N: <property_text>`
