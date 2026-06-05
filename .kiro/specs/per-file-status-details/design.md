# Design Document: Per-File Status Details

## Overview

This design implements a **rich inline detail line** beneath each filename in the monitoring file list. Instead of requiring users to click rows to see integration outcomes, each file now displays a compact pipeline summary showing:

- **Enrichment stage**: ✅ Done / 🔄 In Progress / ❌ Failed / ⏱ Timeout / 🔍 Not Found
- **CP output target**: PRODUCTION (green) / SANDBOX (amber) / UNKNOWN (muted)
- **Exensio load stage**: ✅ Loaded / ⬆ Loading / ❌ Failed / 🔍 Not Found / ⚠ Error
- **Error message** (for failed files): Specific failure reason, truncated to 120 chars with tooltip on hover

The detail line is always visible and updates in real-time via SSE `ROW_UPDATE` events. When a file reaches a terminal state (COMPLETED or ERROR), `StagingSessionService` also emits an activity feed message with the pipeline summary, so operators can see the history of all file outcomes.

## Architecture

### Frontend Data Flow

```
Backend SSE ROW_UPDATE Event
  ↓
StageRecordView (with cpIntegrationStatus, cpIntegrationMessage, etc.)
  ↓
StagingSessionService.updateFileInList()
  ↓
MonitoringFile (updated in sessionFiles signal)
  ↓
RealtimeMonitoringFileListComponent (detects signal change)
  ↓
paginatedFiles() computed re-evaluates
  ↓
Template re-renders with new detail line
```

### Key Components & Interfaces

#### 1. MonitoringFile Interface (frontend/src/app/shared/services/monitoring.service.ts)

Already includes the required fields:

- `cpIntegrationStatus: string` — "success" | "pending" | "failure" | "timeout" | "not_found" | "error" | "not_configured"
- `cpIntegrationMessage: string` — Integration outcome message or output directory
- `exensioIntegrationStatus: string` — "success" | "pending" | "failure" | "not_found" | "error" | "not_configured"
- `exensioIntegrationMessage: string` — Integration outcome message
- `errorMessage: string` — Error details if file failed
- `cpOutputPath: string` — Output path if available
- `cpOutputTarget: string` — "PRODUCTION" | "SANDBOX" | "UNKNOWN"

#### 2. StagingSessionService (frontend/src/app/shared/services/staging-session.service.ts)

**Enhanced ROW_UPDATE handler**:

- Updates file in `sessionFiles` array with all integration fields
- For terminal files (status COMPLETED or ERROR), calls `buildAndPushTerminalActivityMessage(file)`

**New method: buildAndPushTerminalActivityMessage(file: MonitoringFile)**:

- Generates pipeline summary from integration fields
- Emits activity event with formatted message
- For COMPLETED: `[filename] — Enrichment: Done · Exensio: Loaded · PRODUCTION`
- For ERROR: `[filename] — Failed: [error message truncated to 80 chars]`

#### 3. RealtimeMonitoringFileListComponent (frontend/src/app/shared/components/monitoring-file-list.component.ts)

**Enhanced template**:

- Each file row now contains a detail line beneath the filename
- Detail line shows pipeline summary for all integration stages
- Detail line is always visible (no expand required)

**New methods**:

| Method                       | Returns        | Purpose                                                                                     |
| ---------------------------- | -------------- | ------------------------------------------------------------------------------------------- |
| `getDetailLine(file)`        | string         | Generates the detail line summary (e.g., "Enrichment: Done · Exensio: Loaded · PRODUCTION") |
| `getEnrichmentSegment(file)` | string \| null | Returns enrichment stage summary or null if not configured                                  |
| `getExensioSegment(file)`    | string \| null | Returns Exensio stage summary or null if not configured                                     |
| `getOutputTargetBadge(file)` | string \| null | Returns output target (PRODUCTION/SANDBOX/UNKNOWN) or null                                  |
| `getErrorSummary(file)`      | string \| null | Returns truncated error message or null                                                     |
| `getDetailLineIcon(file)`    | string         | Returns icon name based on file status/integration states                                   |
| `getDetailLineColor(file)`   | string         | Returns color class (success, error, warning, muted)                                        |

## Components and Interfaces

### Template Changes (RealtimeMonitoringFileListComponent)

**Old structure** (per file row):

```
[Status Badge] [Filename] [Lot] [Wafer] [Message]
```

**New structure** (per file row):

```
[Status Badge] [Filename]                [Lot] [Wafer] [Message]
               [Detail Line: pipeline summary]
```

The detail line is a secondary text line within the filename column, rendered in smaller font with muted color.

**Example detail lines**:

- Queued file: `Queued`
- Enriching file: `Enrichment: In Progress`
- Completed file: `Enrichment: Done · Exensio: Loaded · PRODUCTION`
- Failed enrichment: `Enrichment: Failed · Schema validation error: unknown column 'wafer_id'`
- Failed Exensio load: `Enrichment: Done · Exensio: Failed · Cannot find wafer in mapping`

### CSS Styling

**Detail line class**: `.file-detail-line`

- Font size: `0.75rem` (smaller than main filename)
- Color: `var(--text-muted)` (grey)
- Line height: `1.2`
- Max width: inherit from parent cell
- Overflow: `text-overflow: ellipsis` with `white-space: nowrap` for truncation

**Segment badges** (inline within detail line):

- Enrichment: icon + text (e.g., "✅ Enrichment: Done")
- Exensio: icon + text (e.g., "☁ Exensio: Loaded")
- Output target: badge style (e.g., `[PRODUCTION]` in green)

**Icon colors**:

- Success: `#10b981` (green)
- Error: `#ef4444` (red)
- Warning: `#f59e0b` (amber)
- Muted: `#94a3b8` (grey)

## Data Models

### Integration Status Values

**CP Integration Status** (`cpIntegrationStatus`):

- `"success"`: File enriched successfully in Elasticsearch/CP
- `"pending"`: Enrichment in progress
- `"failure"`: Enrichment failed (error logged)
- `"timeout"`: Enrichment did not complete within timeout
- `"not_found"`: No enrichment record found yet
- `"error"`: System error during enrichment lookup
- `"not_configured"`: CP enrichment not enabled

**Exensio Integration Status** (`exensioIntegrationStatus`):

- `"success"`: File confirmed loaded into Exensio
- `"pending"`: Awaiting Exensio confirmation
- `"failure"`: File failed to load into Exensio
- `"not_found"`: File not found in Exensio records yet
- `"error"`: System error during Exensio lookup
- `"not_configured"`: Exensio integration not enabled

### Activity Event Message Format

When a file reaches a terminal state, `StagingSessionService` emits an activity event:

**For COMPLETED files**:

```
format: "[filename] — [enrichment] · [exensio] · [target]"
example: "lot_123.klarf — Enrichment: Done · Exensio: Loaded · PRODUCTION"
```

**For ERROR files**:

```
format: "[filename] — Failed: [error_message_80_chars_truncated]"
example: "lot_456.klarf — Failed: Schema validation error: unknown column 'wafer_id'"
```

**For partial pipelines** (e.g., no Exensio enabled):

```
example: "lot_789.klarf — Enrichment: Done · SANDBOX"
```

## Correctness Properties

A property is a characteristic or behavior that should hold true across all valid executions of a system—essentially, a formal statement about what the system should do. Properties serve as the bridge between human-readable specifications and machine-verifiable correctness guarantees.

### Property 1: Detail Line Always Visible for All Files

**For all** files in the monitoring file list, the detail line element SHALL be present in the DOM beneath the filename, regardless of integration status or file state.

**Validates: Requirements 1.1, 1.2**

### Property 2: Detail Line Updates in Place on SSE ROW_UPDATE

**For all** SSE `ROW_UPDATE` events received, if the file's integration status fields (`cpIntegrationStatus`, `exensioIntegrationStatus`, `errorMessage`, etc.) change, the detail line SHALL update within 50ms without requiring a full list re-render.

**Validates: Requirement 1.4**

### Property 3: Enrichment Status Renders Correct Icon and Label

**For all** files with a defined `cpIntegrationStatus` value, the detail line SHALL display the appropriate icon and text matching the status: success (green check + "Enrichment: Done"), pending (muted spinner + "Enrichment: In Progress"), failure (red error + "Enrichment: Failed"), timeout (amber clock + "Enrichment: Timeout"), not_found (amber search + "Enrichment: Not Found").

**Validates: Requirements 2.1-2.6**

### Property 4: Enrichment Segment Omitted When Not Configured

**For all** files with `cpIntegrationStatus` set to `"not_configured"` or null, the detail line SHALL not include any enrichment stage segment.

**Validates: Requirement 2.7**

### Property 5: Enrichment Message Appended When Available

**For all** files with `cpIntegrationStatus === "success"` and non-empty `cpIntegrationMessage`, the detail line SHALL append the message after "Enrichment: Done".

**Validates: Requirement 2.2**

### Property 6: Output Target Badge Renders Correctly for Success

**For all** files with `cpIntegrationStatus === "success"`, the detail line SHALL show a target badge matching `cpOutputTarget`: PRODUCTION (green), SANDBOX (amber), UNKNOWN (muted).

**Validates: Requirements 3.1, 3.2, 3.3**

### Property 7: Output Target Badge Omitted for Non-Success Enrichment

**For all** files with `cpIntegrationStatus` not equal to `"success"`, the detail line SHALL not include an output target badge.

**Validates: Requirement 3.4**

### Property 8: Exensio Status Renders Correct Icon and Label

**For all** files with a defined `exensioIntegrationStatus` value, the detail line SHALL display the appropriate icon and text matching the status: success (green cloud + "Exensio: Loaded"), pending (muted cloud-upload + "Exensio: Loading"), failure (red cloud-off + "Exensio: Failed"), not_found (amber cloud + "Exensio: Not Found"), error (red warning + "Exensio: Error").

**Validates: Requirements 4.1-4.5**

### Property 9: Exensio Segment Omitted When Not Configured

**For all** files with `exensioIntegrationStatus` set to `"not_configured"` or null, the detail line SHALL not include any Exensio stage segment.

**Validates: Requirement 4.6**

### Property 10: Error Message Priority Order for Failed Files

**For all** files with `status === ERROR` or `cpIntegrationStatus === "failure"` or `exensioIntegrationStatus === "failure"`, the detail line SHALL show the error message from the highest priority source in this order: `errorMessage` → `cpIntegrationMessage` → `exensioIntegrationMessage`, truncated to 120 characters if longer.

**Validates: Requirements 5.1-5.4**

### Property 11: Error Message Tooltip Shows Full Text

**For all** truncated error messages (longer than 120 characters), hovering over the detail line segment SHALL show a tooltip containing the full untruncated message.

**Validates: Requirement 5.5**

### Property 12: Activity Event Format for COMPLETED Files

**For all** files transitioning to `COMPLETED` status, `StagingSessionService` SHALL push an activity event with type `file`, icon `check_circle`, color `success`, and a message in the format: `[filename] — Enrichment: Done · Exensio: Loaded · [cpOutputTarget]` (or omit Exensio segment if not configured).

**Validates: Requirements 6.1, 6.3, 6.4**

### Property 13: Activity Event Format for ERROR Files

**For all** files transitioning to `ERROR` status, `StagingSessionService` SHALL push an activity event with type `file`, icon `error`, color `error`, and a message in the format: `[filename] — Failed: [error message truncated to 80 chars]`.

**Validates: Requirement 6.2**

### Property 14: Activity Event Includes Filename as First Token

**For all** activity events emitted for terminal files, the message SHALL always include the filename as the first token so operators can identify which file the event refers to.

**Validates: Requirement 6.5**

### Property 15: Detail Line Renders Queued Label for Queued Files

**For all** files with `status` equal to `READY` or `ENQUEUED`, the detail line SHALL show a muted "Queued" label.

**Validates: Requirement 7.1**

### Property 16: Detail Line Renders Enrichment In Progress for Enrichment Files

**For all** files with `status` equal to `ENRICHMENT` and `cpIntegrationStatus` equal to `"pending"` or null, the detail line SHALL show "Enrichment: In Progress".

**Validates: Requirement 7.2**

### Property 17: Detail Line Renders Loading State for Exensio Loading Files

**For all** files with `status` equal to `EXENSIO_LOADING` and `exensioIntegrationStatus` equal to `"pending"` or null, the detail line SHALL show "Enrichment: Done · Exensio: Loading".

**Validates: Requirement 7.3**

### Property 18: Detail Line Shows Full Pipeline Summary for Completed Files

**For all** files with `status` equal to `COMPLETED`, the detail line SHALL show the complete pipeline summary including enrichment status, output target badge, and Exensio status.

**Validates: Requirement 7.4**

### Property 19: Detail Line Shows Error Details for ERROR Files

**For all** files with `status` equal to `ERROR`, the detail line SHALL show the specific error message from the highest priority source per Property 10.

**Validates: Requirement 7.5**

### Property 20: Round-Trip Activity Message Consistency

**For all** files reaching `COMPLETED` or `ERROR` status, the activity event message SHALL contain text that matches the content of the detail line for that file.

**Validates: Requirements 6.1-6.5**

---

## Property Reflection Summary

The following redundant properties were identified and consolidated during reflection:

- **Property 1 and 2**: Combined "always visible" and "no expansion required" into a single property about DOM presence
- **Property 3, 4, 5**: Split enrichment status into multiple properties for clarity (status rendering, message appending, not_configured behavior)
- **Property 6, 7**: Separated output target badge rendering for success vs non-success cases
- **Property 8, 9**: Split Exensio status rendering into status-specific rendering and not_configured omission
- **Property 10, 11**: Separated error message priority ordering from tooltip behavior
- **Property 12, 13, 14**: Separated COMPLETED and ERROR event formats with additional priority property
- **Property 15-19**: Kept status-specific rendering properties separate as they test distinct behaviors
- **Property 20**: Added as a round-trip consistency property to verify activity messages match detail lines

## Error Handling

### SSE ROW_UPDATE Parsing Error

- **Issue**: Invalid JSON in ROW_UPDATE event payload
- **Handling**: Catch JSON parse error, log to console, skip detail line update for that row, continue processing
- **Result**: File row displays with stale detail line until next successful update

### Missing Integration Fields

- **Issue**: File has no `cpIntegrationStatus` or `exensioIntegrationStatus`
- **Handling**: Treat missing fields as `"pending"` or `"not_configured"` per Requirement 3.4 and 4.6
- **Result**: Detail line shows placeholder text, e.g., "Waiting..." or status-appropriate default

### Tooltip Overflow

- **Issue**: Error message truncated to 120 chars, user needs to see full error
- **Handling**: Attach full message to GlassTooltipDirective on detail line, show on hover
- **Result**: User can see complete error without expanding row

## Testing Strategy

### Unit Tests

**DetailLineComponent** (new reusable component, optional):

- Test each integration status renders correct icon + label
- Test output target badge displays correct color for PRODUCTION/SANDBOX/UNKNOWN
- Test error message is truncated to 120 chars
- Test detail line is always visible (not hidden by default)

**StagingSessionService.buildAndPushTerminalActivityMessage()**:

- Test activity message format for COMPLETED files
- Test activity message format for ERROR files
- Test activity message includes filename
- Test activity message truncates error to 80 chars

**RealtimeMonitoringFileListComponent.getDetailLine()**:

- Test detail line includes enrichment segment when cpIntegrationStatus is populated
- Test detail line includes Exensio segment when exensioIntegrationStatus is populated
- Test detail line includes output target badge for PRODUCTION/SANDBOX
- Test detail line shows error message for failed files
- Test detail line shows "Queued" for READY/ENQUEUED files
- Test detail line shows status-appropriate text for all file states

### Property-Based Tests

**Property 1**: For all generated file objects with random integration statuses, verify detail line text matches enum values

**Property 2**: For all files with ERROR status and errorMessage set, verify detail line contains (truncated) error text

**Property 3**: Simulate rapid ROW_UPDATE SSE events; verify detail line updates without full list re-render

**Property 4**: For all files reaching COMPLETED/ERROR status, verify activity feed receives matching summary message

**Property 5**: For all possible file status combinations, verify detail line renders without null/undefined errors

---

## Implementation Notes

1. **No Breaking Changes**: The detail line is additive—it doesn't remove or change existing columns. Existing expanded view remains unchanged.

2. **Performance**: Detail line is computed once per file in `getDetailLine()` method. Updates only when file object changes via SSE, not on every component re-render.

3. **Accessibility**: Detail line text uses semantic icons with ARIA labels. Truncated error messages are accessible via tooltip.

4. **Mobile Responsiveness**: Detail line may wrap on narrow screens. Grid layout adjusts automatically.

5. **Backwards Compatibility**: Frontend works with backend that hasn't populated integration fields yet (defaults to "pending" or "not_configured").
