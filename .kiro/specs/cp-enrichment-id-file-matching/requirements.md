# Requirements Document

## Introduction

The CP enrichment pipeline currently queries Elasticsearch using only `idData` to match log entries for a staged file. This is insufficient because `idData` alone can be ambiguous — multiple files may share the same `idData`. The metadata view exposes both `id` (the file-level key, referred to as `idFile` in ES documents) and `id_data` (the data-level key, referred to as `idData` in ES documents). Both values are already stored on `SENDER_STAGE` as `metadata_id` and `data_id` respectively.

This feature refactors the ES query to use both `idFile` and `idData` as `must` term filters, improving match precision. It also replaces the current `service.environment` + "output path" success detection with a boosted `should`-clause approach that looks for PRODUCTION or SANDBOX keywords in the `message` field. When neither keyword is present but the message indicates successful execution, the system falls back to querying the `refdb.pp_log` table to determine the output directory and detect errors.

## Glossary

- **idFile**: The file-level identifier in Elasticsearch documents, corresponding to `metadata_id` on `SENDER_STAGE` and the `id` column in the metadata view.
- **idData**: The data-level identifier in Elasticsearch documents, corresponding to `data_id` on `SENDER_STAGE` and the `id_data` column in the metadata view.
- **pp_log**: A table in the RefDB (Oracle) database that records CP processing outcomes, including `lot`, `output_directory`, `extension`, `file_name`, `process_code`, and `log_message`.
- **process_code**: A column in `pp_log` where `0` indicates success and any non-zero value indicates an error.
- **output_directory**: A column in `pp_log` that contains the path to the output folder, used to determine whether the file was sent to PRODUCTION or SANDBOX.
- **SENDER_STAGE**: The internal staging table that holds one row per payload being processed through the pipeline.
- **StageRecord**: The Java record representing one row from `SENDER_STAGE`.
- **ElasticsearchLogService**: The backend service that builds and executes ES queries and parses results.
- **CpLogMonitor**: The scheduled monitor that polls Elasticsearch for CP enrichment outcomes.
- **CpLogResult**: The sealed interface representing the outcome of a CP ES log lookup (Success, Failure, NotFound).
- **RefDbService**: The service that manages the `SENDER_STAGE` table and the RefDB Oracle connection.

## Requirements

### Requirement 1: Use Both idFile and idData in the Elasticsearch Query

**User Story:** As the CP enrichment pipeline, I want to filter ES log entries by both `idFile` and `idData`, so that the query matches only the specific file being monitored and avoids false positives from other files sharing the same `idData`.

#### Acceptance Criteria

1. WHEN `ElasticsearchLogService.buildQuery` is called, THE System SHALL include a `term` filter on `idFile` using `StageRecord.metadataId()` as a `must` clause in the ES query.
2. WHEN `ElasticsearchLogService.buildQuery` is called, THE System SHALL include a `term` filter on `idData` using `StageRecord.dataId()` as a `must` clause in the ES query.
3. THE System SHALL include both `idFile` and `idData` `term` filters in the same `must` array so that both must match for a document to be returned.
4. THE `_source` fields requested from ES SHALL include `idFile` and `idData` in addition to the existing fields (`@timestamp`, `cpConfig`, `message`, `log.level`).

### Requirement 2: Replace service.environment Detection with Message-Based Should Clauses

**User Story:** As the CP enrichment pipeline, I want to detect the output target (PRODUCTION or SANDBOX) by inspecting the `message` field of ES log entries, so that the detection is based on the actual log content rather than a metadata field.

#### Acceptance Criteria

1. THE ES query SHALL include a `should` clause with a boosted wildcard on `message` matching `*output path*PRODUCTION*` (case-insensitive, boost 4) to identify PRODUCTION-bound files.
2. THE ES query SHALL include a `should` clause with a boosted wildcard on `message` matching `*SANDBOX*` (case-insensitive, boost 3) to identify SANDBOX-bound files.
3. THE ES query SHALL include a `should` clause that boosts non-ERROR log entries (boost 3) using a `must_not` on `log.level: ERROR`.
4. THE ES query SHALL include a `should` clause that boosts ERROR log entries (boost 1) using a `term` on `log.level: ERROR`.
5. THE ES query SHALL set `minimum_should_match: 1` so that at least one `should` clause must match.
6. WHEN parsing ES hits, THE System SHALL determine the output target by checking whether the `message` field contains the string `PRODUCTION` (case-insensitive) → `"PRODUCTION"`, or `SANDBOX` (case-insensitive) → `"SANDBOX"`.
7. WHEN the `message` field contains neither `PRODUCTION` nor `SANDBOX`, THE System SHALL treat the hit as requiring a RefDB fallback lookup rather than immediately returning a `Success` or `Failure` result.

### Requirement 3: RefDB pp_log Fallback When Message Contains "executed successfully"

**User Story:** As the CP enrichment pipeline, I want to fall back to querying `refdb.pp_log` when the ES message indicates successful execution but does not contain PRODUCTION or SANDBOX, so that I can determine the output directory and confirm the correct schema.

#### Acceptance Criteria

1. WHEN an ES hit has a `message` containing the string `executed successfully` (case-insensitive) and does not contain `PRODUCTION` or `SANDBOX`, THE System SHALL query `refdb.pp_log` to determine the output directory.
2. THE `pp_log` query SHALL be: `SELECT lot, output_directory FROM pp_log WHERE lot = ? AND (extension LIKE ? OR file_name LIKE ?) AND process_code = 0`, binding the `StageRecord.lot()` and `'%<idFile>%'` (where `idFile` = `StageRecord.metadataId()`).
3. WHEN the `pp_log` query returns at least one row, THE System SHALL use the `output_directory` value to determine the output target using the existing `detectOutputTarget` logic (`PRODUCTION`, `SANDBOX`, or `UNKNOWN`).
4. WHEN the `pp_log` query returns at least one row, THE System SHALL return a `CpLogResult.Success` with the `output_directory` as the output path and the detected target.
5. WHEN the `pp_log` query returns no rows, THE System SHALL query `pp_log` for the error: `SELECT log_message FROM pp_log WHERE lot = ? AND (extension LIKE ? OR file_name LIKE ?) AND process_code != 0`.
6. WHEN the error query returns at least one row, THE System SHALL return a `CpLogResult.Failure` with the `log_message` as the error message.
7. WHEN both the success and error `pp_log` queries return no rows, THE System SHALL return `CpLogResult.NotFound` to allow the monitor to retry on the next cycle.
8. IF the `pp_log` query throws a `SQLException`, THEN THE System SHALL log a warning and return `CpLogResult.NotFound` so the record retries rather than failing permanently.

### Requirement 4: Error Detection via log.level in ES Hits

**User Story:** As the CP enrichment pipeline, I want to detect CP errors from the `log.level` field in ES hits, so that error records are identified and marked as failed without requiring a RefDB lookup.

#### Acceptance Criteria

1. WHEN an ES hit has `log.level` equal to `ERROR` (case-insensitive), THE System SHALL treat it as a `CpLogResult.Failure` and extract the error message from the `message` field.
2. WHEN an ES hit has `log.level` equal to `ERROR` and the `message` field is blank, THE System SHALL use `"CP processing error"` as the fallback error message.
3. THE error detection from `log.level` SHALL take priority over the PRODUCTION/SANDBOX message check — if a hit has `log.level: ERROR`, it is a failure regardless of message content.

### Requirement 5: Pass idFile Through the CpLogMonitor Call Chain

**User Story:** As the CP enrichment pipeline, I want `CpLogMonitor` to pass `StageRecord.metadataId()` as the `idFile` parameter when calling `ElasticsearchLogService.findCpLog`, so that the ES query can filter by both identifiers.

#### Acceptance Criteria

1. THE `ElasticsearchLogService.findCpLog` method signature SHALL accept an `idFile` parameter (the `metadata_id` value from `StageRecord`).
2. WHEN `CpLogMonitor.processRecord` calls `elasticsearchLogService.findCpLog`, THE System SHALL pass `record.metadataId()` as the `idFile` argument.
3. THE `ElasticsearchLogService.buildQuery` method SHALL accept and use the `idFile` parameter when constructing the `must` term filter.

### Requirement 6: RefDB pp_log Query Execution via RefDbService

**User Story:** As the system, I want the `pp_log` fallback query to be executed through `RefDbService` using the existing RefDB Oracle connection, so that connection pooling and error handling are consistent with the rest of the pipeline.

#### Acceptance Criteria

1. THE System SHALL add a method to `RefDbService` that executes the `pp_log` success query and returns the `output_directory` string, or `null` if no row is found.
2. THE System SHALL add a method to `RefDbService` that executes the `pp_log` error query and returns the `log_message` string, or `null` if no row is found.
3. WHEN `ElasticsearchLogService` needs to perform a `pp_log` fallback, THE System SHALL delegate to `RefDbService` rather than opening a direct JDBC connection.
4. IF `RefDbService` is not available (null) or the RefDB is not configured, THEN THE System SHALL skip the `pp_log` fallback and return `CpLogResult.NotFound`.

### Requirement 7: Backward Compatibility

**User Story:** As an operator, I want existing records and configurations to continue working correctly after this change, so that the upgrade does not break in-flight enrichment records.

#### Acceptance Criteria

1. WHEN `StageRecord.metadataId()` is null or blank, THE System SHALL omit the `idFile` term filter from the ES query and fall back to the existing `idData`-only query behavior.
2. WHEN the `pp_log` fallback is not needed (PRODUCTION or SANDBOX is found in the ES message), THE System SHALL not execute any `pp_log` queries.
3. THE existing `detectOutputTarget` logic SHALL be reused for both the ES message-based detection and the `pp_log` `output_directory`-based detection.
4. THE existing `CpLogResult.Success`, `CpLogResult.Failure`, and `CpLogResult.NotFound` types SHALL remain unchanged.
