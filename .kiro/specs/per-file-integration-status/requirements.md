# Requirements Document

## Introduction

The monitoring UI currently shows a single integration status (CP/Elasticsearch and Exensio) per **session**, using the `IntegrationStatusService` which stores one status entry per `requestId`. This is insufficient when a session contains multiple files — users cannot tell which specific file succeeded, which failed in Elasticsearch, or which is still waiting on Exensio.

### Enrichment Context

The **CP enrichment process** works as follows:

1. **Elasticsearch Check (Primary)**: `CpLogMonitor` queries Elasticsearch for CP logs containing:
   - `PRODUCTION` → Enrichment succeeded in PROD
   - `SANDBOX` → Enrichment succeeded in SBX
   - If found → Transition to `EXENSIO_LOADING`

2. **pp_log Fallback (Secondary)**: If ES doesn't find PRODUCTION/SANDBOX, falls back to `refdb.pp_log` table:
   - `queryPpLogSuccess`: Returns `output_directory` where `process_code = 0` (success)
   - `queryPpLogError`: Returns `log_message` where `process_code != 0` (failure)

**Important**: For PRODUCTION and SANDBOX environments, enrichment happens directly in the database (no CP output path). The ES log may only contain "executed successfully" message without an output directory. In these cases, the actual enrichment success/failure is stored in `pp_log`.

This feature replaces the session-level integration tracking with **per-file integration status** so that every row in the monitoring UI file table shows its own live CP (Elasticsearch) status and Exensio status. The CP status is derived from the ES query results in `CpLogMonitor` / `ElasticsearchLogService` (including pp_log fallback), and the Exensio status is derived from the `raw-sql` endpoint queries in `ExensioClient`. Both statuses are stored per stage-record ID (not per requestId) and surfaced in the file table on the monitoring page.

## Glossary

- **StageRecord**: One row in `SENDER_STAGE`, representing a single staged file being processed through the pipeline.
- **PerFileIntegrationStatus**: A new status record keyed by `StageRecord.id()` (the numeric primary key) rather than `requestId`.
- **CpStatus**: The CP / Elasticsearch enrichment outcome for a single file: one of `pending`, `not_found`, `success`, `failure`, `timeout`, `error`, `not_configured`.
- **ExensioStatus**: The Exensio load-monitor outcome for a single file: one of `pending`, `not_found`, `success`, `failure`, `error`, `not_configured`.
- **IntegrationStatusService**: The existing service that currently holds per-`requestId` integration status in memory. To be extended to also hold per-`stageRecordId` status.
- **CpLogMonitor**: The scheduled monitor that polls Elasticsearch for CP enrichment outcomes across ENRICHMENT-status records.
- **ExensioLoadMonitor**: The scheduled monitor that polls the Exensio API for EXENSIO_LOADING-status records.
- **ExensioClient**: The service that executes HTTP calls to the Exensio API, including the `raw-sql` endpoint.
- **StageController**: The REST controller that exposes stage record data to the frontend.
- **StageRecordView**: The DTO returned to the frontend for each file row.
- **MonitoringFileItem**: The TypeScript interface representing one file row in the monitoring UI.
- **RealtimeMonitoringFileListComponent**: The Angular component that renders the file table in the monitoring UI.

## Requirements

### Requirement 1: Per-File CP Status Tracking in IntegrationStatusService

**User Story:** As the CP enrichment pipeline, I want to record the Elasticsearch/PP_LOG check result for each individual file by its stage record ID, so that the monitoring UI can show which specific files are found, failed, or still pending in the enrichment process.

#### Acceptance Criteria

1. THE `IntegrationStatusService` SHALL store a `CpStatus` entry keyed by `stageRecordId` (a `long`) in addition to the existing per-`requestId` ES status.
2. WHEN `CpLogMonitor.processRecord` processes a record with result `Success` (from ES PRODUCTION/SANDBOX), THE System SHALL call a new `updateCpStatusForRecord(long stageRecordId, String status, String message)` method on `IntegrationStatusService` with status `"success"`.
3. WHEN `CpLogMonitor.processRecord` processes a record with result `Success` (from pp_log fallback with `process_code = 0`), THE System SHALL call `updateCpStatusForRecord` with status `"success"` and the output directory in the message.
4. WHEN `CpLogMonitor.processRecord` processes a record with result `Failure` (from ES failure or pp_log error with `process_code != 0`), THE System SHALL call `updateCpStatusForRecord` with status `"failure"` and the error message.
5. WHEN `CpLogMonitor.processRecord` produces `NotFound` within timeout (no ES log and no pp_log entry), THE System SHALL call `updateCpStatusForRecord` with status `"not_found"`.
6. WHEN `CpLogMonitor.processRecord` produces `NotFound` and the timeout is exceeded, THE System SHALL call `updateCpStatusForRecord` with status `"timeout"` and a message indicating enrichment did not complete within the expected timeframe.
7. WHEN `CpLogMonitor.processRecord` encounters an `ElasticsearchQueryException`, THE System SHALL call `updateCpStatusForRecord` with status `"error"` and the exception message.
8. WHEN no CP or ES is available (disabled or unreachable), THE System SHALL fall back to query `refdb.pp_log` directly with the same timeout behavior as with CP/ES.
9. WHEN enrichment completes successfully via pp_log fallback (no ES PRODUCTION/SANDBOX but pp_log success found), THE `updateCpStatusForRecord` message SHALL include the pp_log output directory to help users verify the enrichment result.
10. THE `IntegrationStatusService` SHALL expose a method `getCpStatusForRecord(long stageRecordId)` that returns the stored `CpStatus` or `null` if none has been recorded yet.

### Requirement 2: Per-File Exensio Status Tracking in IntegrationStatusService

**User Story:** As the Exensio load monitor, I want to record the Exensio raw-sql check result for each individual file by its stage record ID, so that the monitoring UI can show which specific files are confirmed, pending, or failed in Exensio.

#### Acceptance Criteria

1. THE `IntegrationStatusService` SHALL store an `ExensioStatus` entry keyed by `stageRecordId` (a `long`).
2. WHEN `ExensioLoadMonitor` resolves a record to `DONE` (wafer confirmed), THE System SHALL call `updateExensioStatusForRecord(long stageRecordId, String status, String message)` with status `"success"`.
3. WHEN `ExensioLoadMonitor` resolves a record to `NOT_FOUND` within timeout, THE System SHALL call `updateExensioStatusForRecord` with status `"not_found"`.
4. WHEN `ExensioLoadMonitor` resolves a record to `FAILED` (timeout exceeded or explicit failure), THE System SHALL call `updateExensioStatusForRecord` with status `"failure"`.
5. WHEN `ExensioLoadMonitor` resolves a record to `ERROR`, THE System SHALL call `updateExensioStatusForRecord` with status `"error"`.
6. THE `IntegrationStatusService` SHALL expose a method `getExensioStatusForRecord(long stageRecordId)` that returns the stored `ExensioStatus` or `null` if none has been recorded yet.

### Requirement 3: Expose Per-File Integration Status in StageRecordView

**User Story:** As the frontend, I want each file row returned by the stage records API to include the latest CP status and Exensio status for that file, so that the monitoring table can display them without additional HTTP round-trips.

#### Acceptance Criteria

1. THE `StageRecordView` DTO SHALL include two new optional fields: `cpIntegrationStatus` (String) and `exensioIntegrationStatus` (String).
2. THE `StageRecordView` DTO SHALL include two new optional message fields: `cpIntegrationMessage` (String) and `exensioIntegrationMessage` (String).
3. WHEN `StageRecordMapper.toView` converts a `StageRecord` to a `StageRecordView`, THE System SHALL look up the per-file statuses from `IntegrationStatusService` using `StageRecord.id()` and populate the four new fields.
4. WHEN no per-file status has been recorded for a given record ID, THE `StageRecordMapper` SHALL set `cpIntegrationStatus` to `"pending"` if the record's overall `status` is `ENRICHMENT`, or `"not_configured"` if CP/ES is not enabled.
5. WHEN no per-file Exensio status has been recorded for a given record ID, THE `StageRecordMapper` SHALL set `exensioIntegrationStatus` to `"pending"` if the record's overall `status` is `EXENSIO_LOADING`, or `"not_configured"` if Exensio is not enabled.
6. THE `StageRecordMapper` SHALL have `IntegrationStatusService`, `CpElasticsearchProperties`, and `ExensioProperties` injected so it can resolve configuration flags when defaulting status values.

### Requirement 4: SSE ROW_UPDATE Events Include Per-File Integration Status

**User Story:** As the monitoring page, I want real-time SSE updates for a file row to carry the updated CP status and Exensio status, so the UI updates without polling the records endpoint.

#### Acceptance Criteria

1. WHEN `StageMonitorService` emits a `ROW_UPDATE` SSE event for a file, THE event payload SHALL include the `cpIntegrationStatus`, `cpIntegrationMessage`, `exensioIntegrationStatus`, and `exensioIntegrationMessage` fields from the current `StageRecordView` for that record.
2. WHEN `CpLogMonitor` updates a per-file CP status, THE System SHALL trigger a `ROW_UPDATE` SSE event for the affected record so the frontend receives the update without waiting for the next page poll.
3. WHEN `ExensioLoadMonitor` updates a per-file Exensio status, THE System SHALL trigger a `ROW_UPDATE` SSE event for the affected record.
4. THE SSE `ROW_UPDATE` trigger SHALL be best-effort — if no active SSE subscriber exists for the record's session, THE System SHALL silently skip the push without throwing an error.

### Requirement 5: Frontend MonitoringFileItem Includes Integration Status Columns

**User Story:** As a monitoring user, I want to see a CP Status column and an Exensio Status column in the file table, so that I can identify at a glance which integration step each file is at and whether it succeeded or failed.

#### Acceptance Criteria

1. THE `MonitoringFileItem` TypeScript interface SHALL include `cpIntegrationStatus`, `cpIntegrationMessage`, `exensioIntegrationStatus`, and `exensioIntegrationMessage` as optional string fields.
2. THE `MonitoringPaginationService` (and any other service that maps backend file rows to `MonitoringFileItem`) SHALL map the four new backend fields onto the corresponding `MonitoringFileItem` fields.
3. WHEN a `ROW_UPDATE` SSE event is received, THE frontend SHALL update the matching `MonitoringFileItem` in the current list, including the four new integration status fields.
4. THE `RealtimeMonitoringFileListComponent` SHALL display a "CP Status" mini-badge column in the file table showing the `cpIntegrationStatus` value with an appropriate icon and color.
5. THE `RealtimeMonitoringFileListComponent` SHALL display an "Exensio Status" mini-badge column in the file table showing the `exensioIntegrationStatus` value with an appropriate icon and color.
6. WHEN a user clicks to expand a file row, THE expanded detail panel SHALL show the full `cpIntegrationMessage` and `exensioIntegrationMessage` in addition to the existing `errorMessage` and `cpOutputPath`.

### Requirement 6: CP Status Badge Visual States

**User Story:** As a monitoring user, I want the CP Status badge color to clearly communicate the current integration state, so that I can quickly spot files that are waiting, succeeded, or failed in Elasticsearch.

#### Acceptance Criteria

1. WHEN `cpIntegrationStatus` is `"pending"`, THE badge SHALL display with a muted/grey style and a spinner or clock icon.
2. WHEN `cpIntegrationStatus` is `"not_found"`, THE badge SHALL display with an amber/warning style and a search icon.
3. WHEN `cpIntegrationStatus` is `"success"`, THE badge SHALL display with a green/success style and a check icon.
4. WHEN `cpIntegrationStatus` is `"failure"`, THE badge SHALL display with a red/error style and an error icon.
5. WHEN `cpIntegrationStatus` is `"timeout"`, THE badge SHALL display with a red/error style and a clock-off icon.
6. WHEN `cpIntegrationStatus` is `"error"`, THE badge SHALL display with a red/error style and a warning icon.
7. WHEN `cpIntegrationStatus` is `"not_configured"`, THE badge SHALL display with a muted/grey style and a dash or minus icon.

### Requirement 7: Exensio Status Badge Visual States

**User Story:** As a monitoring user, I want the Exensio Status badge color to clearly communicate the current Exensio load state, so that I can quickly spot files waiting for Exensio confirmation versus those already loaded.

#### Acceptance Criteria

1. WHEN `exensioIntegrationStatus` is `"pending"`, THE badge SHALL display with a muted/grey style and a spinner or clock icon.
2. WHEN `exensioIntegrationStatus` is `"not_found"`, THE badge SHALL display with an amber/warning style and a cloud-search icon.
3. WHEN `exensioIntegrationStatus` is `"success"`, THE badge SHALL display with a green/success style and a cloud-done icon.
4. WHEN `exensioIntegrationStatus` is `"failure"`, THE badge SHALL display with a red/error style and a cloud-off icon.
5. WHEN `exensioIntegrationStatus` is `"error"`, THE badge SHALL display with a red/error style and a warning icon.
6. WHEN `exensioIntegrationStatus` is `"not_configured"`, THE badge SHALL display with a muted/grey style and a dash or minus icon.

### Requirement 8: Memory Management for Per-File Status Entries

**User Story:** As a system operator, I want per-file integration status entries to be evicted from memory when they are no longer needed, so that the in-memory store does not grow unboundedly over long-running deployments.

#### Acceptance Criteria

1. THE `IntegrationStatusService` SHALL evict per-file status entries for records whose pipeline has reached a terminal state (`DONE`, `FAILED`, `COMPLETED`, `ERROR`) after a configurable TTL (default: 2 hours).
2. WHEN the eviction TTL is reached for a terminal-state record's per-file entries, THE System SHALL remove both the CP status and Exensio status entries for that record ID.
3. THE eviction TTL SHALL be configurable via `integration.status.record-ttl-minutes` (default: `120`).
4. IF `IntegrationStatusService` holds more than a configurable maximum number of per-file entries (default: 50 000), THE System SHALL evict the oldest entries first regardless of TTL.
