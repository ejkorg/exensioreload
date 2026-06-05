# Requirements Document

## Introduction

The monitoring file list currently shows each file row with a status badge, filename, lot, wafer, and a generic message column. When a file completes or fails, the user must expand the row to see details. This is insufficient for a fast-moving session with dozens of files — operators need to see the per-file pipeline summary at a glance, without clicking each row.

This feature refactors the file list row display to show a **rich inline detail line** beneath each filename that summarises the full pipeline state for that file: enrichment outcome, Exensio load outcome, CP output target, and the specific error message when a file fails.

The per-file integration status fields (`cpIntegrationStatus`, `cpIntegrationMessage`, `exensioIntegrationStatus`, `exensioIntegrationMessage`, `cpOutputPath`, `cpOutputTarget`, `errorMessage`) are already present in `StageRecordView` and `MonitoringFile` — this feature is purely a **frontend display change**.

## Glossary

- **File Row**: One row in the monitoring file list table representing a single staged file.
- **Detail Line**: A secondary text line rendered beneath the filename inside a file row, always visible (not requiring a click to expand).
- **Pipeline Summary**: A compact, structured summary of all completed and in-progress pipeline stages for one file.
- **CP Enrichment**: The process by which a file's metadata is enriched via Elasticsearch/CP. Outcome stored in `cpIntegrationStatus` / `cpIntegrationMessage`.
- **Exensio Load**: The process by which an enriched file is confirmed loaded into Exensio. Outcome stored in `exensioIntegrationStatus` / `exensioIntegrationMessage`.
- **CP Output Target**: The schema environment the file was enriched into — `PRODUCTION`, `SANDBOX`, or `UNKNOWN`. Stored in `cpOutputTarget`.
- **MonitoringFileListComponent**: The Angular component (`monitoring-file-list.component.ts`) that renders the file table in the monitoring UI.
- **StageRecordView**: The backend DTO returned per file, containing all status and integration fields.
- **MonitoringFile**: The frontend TypeScript interface mirroring `StageRecordView` used inside `MonitoringFileListComponent`.

---

## Requirements

### Requirement 1: Always-Visible Detail Line Per File Row

**User Story:** As a monitoring operator, I want to see a pipeline summary line beneath each filename in the file list, so that I can understand the current state of every file at a glance without clicking to expand rows.

#### Acceptance Criteria

1. THE `MonitoringFileListComponent` SHALL render a detail line beneath the filename for every file row, always visible.
2. THE detail line SHALL be rendered inside the existing file row element without requiring row expansion.
3. WHEN a file row has no pipeline data yet (all integration fields null or empty), THE detail line SHALL display a muted "Waiting..." placeholder.
4. WHEN a file row's status transitions to a new state via SSE update, THE detail line SHALL update in place without full list re-render.

---

### Requirement 2: Enrichment Stage in Detail Line

**User Story:** As a monitoring operator, I want to see the CP enrichment outcome per file in the detail line, so that I know whether enrichment succeeded, is in progress, or failed for each individual file.

#### Acceptance Criteria

1. WHEN `cpIntegrationStatus` is `"success"`, THE detail line SHALL show a green check icon and the text `Enrichment: Done`.
2. WHEN `cpIntegrationStatus` is `"success"` and `cpIntegrationMessage` is non-empty, THE detail line SHALL append the integration message (e.g. output directory) after `Enrichment: Done`.
3. WHEN `cpIntegrationStatus` is `"pending"`, THE detail line SHALL show a muted spinner icon and the text `Enrichment: In Progress`.
4. WHEN `cpIntegrationStatus` is `"failure"`, THE detail line SHALL show a red error icon and the text `Enrichment: Failed`.
5. WHEN `cpIntegrationStatus` is `"timeout"`, THE detail line SHALL show an amber clock icon and the text `Enrichment: Timeout`.
6. WHEN `cpIntegrationStatus` is `"not_found"`, THE detail line SHALL show an amber search icon and the text `Enrichment: Not Found`.
7. WHEN `cpIntegrationStatus` is `"not_configured"` or null, THE detail line SHALL omit the enrichment stage segment entirely.

---

### Requirement 3: CP Output Target in Detail Line

**User Story:** As a monitoring operator, I want to see the CP output schema target (PRODUCTION or SANDBOX) per file in the detail line, so that I can verify data landed in the expected environment.

#### Acceptance Criteria

1. WHEN `cpOutputTarget` is `"PRODUCTION"`, THE detail line SHALL show a green `PRODUCTION` badge after the enrichment segment.
2. WHEN `cpOutputTarget` is `"SANDBOX"`, THE detail line SHALL show an amber `SANDBOX` badge after the enrichment segment.
3. WHEN `cpOutputTarget` is `"UNKNOWN"` or null and `cpIntegrationStatus` is `"success"`, THE detail line SHALL show a muted `UNKNOWN` badge.
4. WHEN `cpOutputTarget` is null and `cpIntegrationStatus` is not `"success"`, THE detail line SHALL omit the target badge entirely.

---

### Requirement 4: Exensio Load Stage in Detail Line

**User Story:** As a monitoring operator, I want to see the Exensio load outcome per file in the detail line, so that I know whether each file has been confirmed loaded into Exensio.

#### Acceptance Criteria

1. WHEN `exensioIntegrationStatus` is `"success"`, THE detail line SHALL show a green cloud icon and the text `Exensio: Loaded`.
2. WHEN `exensioIntegrationStatus` is `"pending"`, THE detail line SHALL show a muted cloud-upload icon and the text `Exensio: Loading`.
3. WHEN `exensioIntegrationStatus` is `"failure"`, THE detail line SHALL show a red cloud-off icon and the text `Exensio: Failed`.
4. WHEN `exensioIntegrationStatus` is `"not_found"`, THE detail line SHALL show an amber cloud icon and the text `Exensio: Not Found`.
5. WHEN `exensioIntegrationStatus` is `"error"`, THE detail line SHALL show a red warning icon and the text `Exensio: Error`.
6. WHEN `exensioIntegrationStatus` is `"not_configured"` or null, THE detail line SHALL omit the Exensio stage segment entirely.

---

### Requirement 5: Specific Error Message in Detail Line for Failed Files

**User Story:** As a monitoring operator, I want to see the specific failure reason directly in the file row detail line when a file fails, so that I can diagnose the issue without opening a separate panel.

#### Acceptance Criteria

1. WHEN a file's `status` is `ERROR` and `errorMessage` is non-empty, THE detail line SHALL display the `errorMessage` text, truncated to 120 characters with an ellipsis if longer.
2. WHEN a file's `cpIntegrationStatus` is `"failure"` and `cpIntegrationMessage` is non-empty, THE detail line SHALL display the CP failure message, truncated to 120 characters.
3. WHEN a file's `exensioIntegrationStatus` is `"failure"` or `"error"` and `exensioIntegrationMessage` is non-empty, THE detail line SHALL display the Exensio failure message, truncated to 120 characters.
4. WHEN multiple failure messages are present for the same file, THE detail line SHALL show the most specific one in this priority order: `errorMessage` → `cpIntegrationMessage` → `exensioIntegrationMessage`.
5. WHEN a user hovers over a truncated error message in the detail line, THE System SHALL show the full untruncated message in a tooltip.

---

### Requirement 6: Activity Feed Emits Pipeline Summary on File Completion

**User Story:** As a monitoring operator, I want the activity feed to include a pipeline summary message when a file reaches a terminal state, so that I can see the history of what happened to each file without looking at the file list.

#### Acceptance Criteria

1. WHEN a file transitions to status `COMPLETED` via SSE update, THE `StagingSessionService` SHALL push an activity event with type `file`, icon `check_circle`, color `success`, and a message in the format: `[filename] — Enrichment: Done · Exensio: Loaded · [cpOutputTarget]`.
2. WHEN a file transitions to status `ERROR` via SSE update, THE `StagingSessionService` SHALL push an activity event with type `file`, icon `error`, color `error`, and a message in the format: `[filename] — Failed: [errorMessage truncated to 80 chars]`.
3. WHEN a file transitions to `COMPLETED` but `exensioIntegrationStatus` is `"not_configured"` (Exensio not used), THE activity message SHALL omit the Exensio segment and show: `[filename] — Enrichment: Done · [cpOutputTarget]`.
4. WHEN a file transitions to `COMPLETED` but `cpIntegrationStatus` is `"not_configured"` (CP not used), THE activity message SHALL show: `[filename] — Exensio: Loaded`.
5. THE activity message for a terminal file SHALL always include the filename as the first token so that operators can identify which file the event refers to.

---

### Requirement 7: Detail Line Rendering for Each File Status State

**User Story:** As a monitoring operator, I want the detail line to reflect the current processing stage accurately for all possible file states, so that I always know what is happening at any moment.

#### Acceptance Criteria

1. WHEN a file's `status` is `READY` or `ENQUEUED`, THE detail line SHALL show a muted `Queued` label.
2. WHEN a file's `status` is `ENRICHMENT` and `cpIntegrationStatus` is `"pending"` or null, THE detail line SHALL show `Enrichment: In Progress`.
3. WHEN a file's `status` is `EXENSIO_LOADING` and `exensioIntegrationStatus` is `"pending"` or null, THE detail line SHALL show `Enrichment: Done · Exensio: Loading`.
4. WHEN a file's `status` is `COMPLETED`, THE detail line SHALL show the full pipeline summary per Requirements 2, 3, and 4.
5. WHEN a file's `status` is `ERROR`, THE detail line SHALL show the specific error per Requirement 5.
