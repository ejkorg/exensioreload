# Requirements Document

## Introduction

Currently, when a record disappears from the `DTP_SENDER_QUEUE_ITEM` table (meaning CP consumed it), the system incorrectly marks it as `DONE`. In reality, the file has only been picked up by CP for enrichment — it still needs to go through enrichment/translation and then Exensio loading before it is truly complete. This feature adds visibility into the CP enrichment pipeline by polling Elasticsearch logs that CP writes, and uses those logs to drive accurate status transitions and surface the output folder path (PRODUCTION or SANDBOX bound) for reporting.

## Glossary

- **CP (Command Processor):** Third-party application that consumes files from `DTP_SENDER_QUEUE_ITEM`, enriches/translates them, and writes the output to a folder for Exensio loading.
- **Enrichment:** The CP process of transforming the raw file into an enriched format (e.g. SXML).
- **Exensio Loading:** The process of loading the enriched file into the Exensio Oracle DB schema (PRODUCTION or SANDBOX).
- **SENDER_STAGE:** Our internal tracking table with statuses: `NEW`, `ENQUEUED`, `ENRICHMENT`, `EXENSIO_LOADING`, `DONE`, `FAILED`, `CANCELLED`.
- **ES / Elasticsearch:** The log aggregation system where CP writes structured JSON logs per file processed.
- **Output Path:** The folder path written by CP in the `message` field of the success log, indicating where the enriched file was placed for Exensio loading.
- **PRODUCTION bound:** Output path destined for the production Oracle DB schema in Exensio.
- **SANDBOX bound:** Output path destined for the sandbox Oracle DB schema in Exensio.
- **CpLogMonitor:** New scheduled backend service that polls ES to detect CP enrichment outcomes.
- **idData:** ES log field that maps to `data_id` in `SENDER_STAGE`.
- **mLot:** ES log field that maps to `lot` in `SENDER_STAGE`.
- **cpConfig:** ES log field containing the CP configuration name — always contains `sender` (case-insensitive) for files triggered by our ExensioReload system.

## Requirements

### Requirement 1: Correct Status on Queue Consumption

**User Story:** As an operator, I want the status to accurately reflect that CP has consumed the file from the queue but enrichment is not yet confirmed, so that I am not misled into thinking the file is fully processed.

#### Acceptance Criteria

1. WHEN a record disappears from `DTP_SENDER_QUEUE_ITEM`, THE `SenderQueueMonitor` SHALL update the record status to `ENRICHMENT` instead of `DONE`.
2. THE `SenderQueueMonitor` SHALL broadcast an SSE `ROW_UPDATE` event with `status: "ENRICHMENT"` and `msg: "Consumed by CP (processing)"` when transitioning to `ENRICHMENT`.
3. WHILE a record has status `ENRICHMENT`, THE system SHALL NOT mark it as `DONE` without a confirmed enrichment success signal from Elasticsearch.

---

### Requirement 2: CP Log Polling via Elasticsearch

**User Story:** As an operator, I want the system to automatically detect CP enrichment outcomes by reading CP's Elasticsearch logs, so that status transitions happen without manual intervention.

#### Acceptance Criteria

1. THE `CpLogMonitor` SHALL poll the Elasticsearch index `logs*dataport*` on a configurable interval (default: 60 seconds).
2. WHEN polling, THE `CpLogMonitor` SHALL query only for records currently in `ENRICHMENT` status.
3. THE `CpLogMonitor` SHALL filter ES logs using `cpConfig: *sender*` (case-insensitive) to isolate logs triggered by our ExensioReload system.
4. THE `CpLogMonitor` SHALL match ES log entries to `SENDER_STAGE` records using `idData` → `data_id` as the primary key.
5. WHEN `idData` alone is ambiguous, THE `CpLogMonitor` SHALL additionally match on `mLot` → `lot` to confirm the correct record.
6. THE `CpLogMonitor` SHALL only consider ES log entries with `@timestamp` greater than or equal to the record's `updated_at` when it entered `ENRICHMENT` status, to avoid matching stale logs from previous runs.
7. IF no ES log is found for a record within a configurable timeout (default: 30 minutes) after entering `ENRICHMENT`, THE `CpLogMonitor` SHALL mark the record as `FAILED` with `error_message: "CP enrichment timeout — no log found in Elasticsearch after 30 minutes"`.

---

### Requirement 3: Enrichment Success Detection

**User Story:** As an operator, I want the system to detect when CP successfully enriches a file and transition it to Exensio Loading status, so I can track the full pipeline.

#### Acceptance Criteria

1. WHEN an ES log entry for a matched record contains `"output path"` in the `message` field AND has no `error.type` or `error.message` value, THE `CpLogMonitor` SHALL consider enrichment successful.
2. WHEN enrichment is successful, THE `CpLogMonitor` SHALL update the record status to `EXENSIO_LOADING`.
3. WHEN enrichment is successful, THE `CpLogMonitor` SHALL extract the output path from the `message` field and store it in a new `cp_output_path` column in `SENDER_STAGE`.
4. THE `CpLogMonitor` SHALL determine the output target (PRODUCTION or SANDBOX) by inspecting the extracted output path string and store it in a new `cp_output_target` column (`PRODUCTION` or `SANDBOX`).
5. WHEN enrichment is successful, THE `CpLogMonitor` SHALL broadcast an SSE `ROW_UPDATE` event with `status: "EXENSIO_LOADING"`, `msg: "Exensio Loading"`, `cpOutputPath`, and `cpOutputTarget`.

---

### Requirement 4: Enrichment Failure Detection

**User Story:** As an operator, I want the system to detect when CP fails to enrich a file and surface the error, so I can investigate and take corrective action.

#### Acceptance Criteria

1. WHEN an ES log entry for a matched record has a non-null `error.type` OR `error.message` field, THE `CpLogMonitor` SHALL consider enrichment failed.
2. WHEN enrichment fails, THE `CpLogMonitor` SHALL update the record status to `FAILED`.
3. WHEN enrichment fails, THE `CpLogMonitor` SHALL store the `error.message` value (or `error.type` if `error.message` is absent) in the `error_message` column of `SENDER_STAGE`.
4. WHEN enrichment fails, THE `CpLogMonitor` SHALL broadcast an SSE `ROW_UPDATE` event with `status: "FAILED"` and `msg` containing the error summary.
5. IF the `error.message` field exceeds 500 characters, THE `CpLogMonitor` SHALL truncate it to 500 characters before storing.

---

### Requirement 5: Output Path Storage and Reporting

**User Story:** As an operator, I want to know whether an enriched file is destined for the PRODUCTION or SANDBOX Exensio Oracle DB schema, so I can include this in reports and verify correct loading.

#### Acceptance Criteria

1. THE `SENDER_STAGE` table SHALL have a new nullable column `cp_output_path VARCHAR2(1000)` to store the enriched file output path.
2. THE `SENDER_STAGE` table SHALL have a new nullable column `cp_output_target VARCHAR2(20)` to store either `PRODUCTION` or `SANDBOX`.
3. THE system SHALL determine `cp_output_target` by checking whether the extracted output path contains the folder name `PRODUCTION` (case-insensitive) → store `PRODUCTION`, or `SANDBOX` (case-insensitive) → store `SANDBOX`. IF neither is found, THE system SHALL store `UNKNOWN`.
4. WHEN listing stage records via the API, THE `StageRecordView` SHALL include `cpOutputPath` and `cpOutputTarget` fields.
5. WHEN a session file detail is displayed in the UI, THE monitoring view SHALL show `cp_output_path` and `cp_output_target` when available.
6. WHERE `cp_output_target` is `PRODUCTION`, THE UI SHALL display a distinct visual indicator (e.g. badge color) to differentiate from `SANDBOX`.

---

### Requirement 6: Elasticsearch Configuration

**User Story:** As a system administrator, I want all Elasticsearch connection and query parameters to be configurable without code changes, so the system can adapt to index name changes or credential rotation.

#### Acceptance Criteria

1. THE system SHALL read Elasticsearch connection details from `application.yml` under a `cp.elasticsearch` prefix, supporting API key auth (preferred when `api-key` is set) and basic auth (`username` + `password`) as a fallback. IF neither is configured, THE system SHALL connect unauthenticated.
2. THE system SHALL read the ES index pattern from configuration (default: `logs*dataport*`).
3. THE system SHALL read the `cpConfig` filter pattern from configuration (default: `*sender*`).
4. THE system SHALL read the polling interval from configuration (default: 60 seconds).
5. THE system SHALL read the enrichment timeout from configuration (default: 30 minutes).
6. THE system SHALL read the polling interval and enrichment timeout from configuration. No path pattern configuration is needed — PRODUCTION and SANDBOX are detected by literal folder name match in the output path.
7. IF Elasticsearch is unreachable, THE `CpLogMonitor` SHALL log a warning and skip the polling cycle without marking any records as failed.

---

### Requirement 7: UI Status Display

**User Story:** As an operator, I want the monitoring UI to accurately reflect the enrichment and Exensio loading stages, so I have full pipeline visibility.

#### Acceptance Criteria

1. THE UI SHALL display `ENRICHMENT` status as `"Enrichment / Translation"` with a processing indicator.
2. THE UI SHALL display `EXENSIO_LOADING` status as `"Exensio Loading"` with a cloud upload indicator.
3. WHEN a file detail is expanded in the monitoring view, THE UI SHALL show the `cp_output_path` if available.
4. WHEN a file detail is expanded in the monitoring view, THE UI SHALL show a `PRODUCTION` or `SANDBOX` badge based on `cp_output_target`.
5. THE monitoring stats component SHALL NOT count `ENRICHMENT` or `EXENSIO_LOADING` records as completed.
