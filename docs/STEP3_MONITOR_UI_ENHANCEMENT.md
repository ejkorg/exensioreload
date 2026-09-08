# Exensio Reload: Step 3 Monitor UI Enhancement Plan

This document extends the design and implementation of **Step 1 (Filter Configuration)** and **Step 2 (Discovery Preview)** by adding comprehensive schema bound indication and error reporting to **Step 3 (Monitor Dispatch)** for real-time and static monitoring views.

---

## Overview

The user requested enhancements to the Step 3 Monitor UI to:

1. **Show Schema Bound (PRODUCTION vs SANDBOX)**: Display whether each file is routed to PRODUCTION or SANDBOX schemas based on CP Elasticsearch logs, Oracle `pp_log`, and Exensio API results.
2. **Show Errors from All Sources**: Display error details from CP (Elasticsearch enrichment), `pp_log`, and Exensio API with clear source indicators.
3. **Unified Monitoring Experience**: Ensure both real-time SSE monitoring and static post-dispatch monitoring views have consistent schema bound and error display.

---

## Current Implementation Status

### ✅ Already Implemented (No Changes Needed)

Both monitoring components (`realtime-monitoring-file-list.component.ts` and `monitoring-file-list.component.ts`) **already have comprehensive implementations** for:

#### 1. Schema Bound Display

- **CP Output Target Badge**: Shows `PRODUCTION`, `SANDBOX`, or `UNKNOWN` based on `cpOutputTarget` field
- **Badge Styling**:
  - `.badge-production`: Emerald green glow (`rgba(16, 185, 129, 0.15)` background, `#10b981` text)
  - `.badge-sandbox`: Amber glow (`rgba(245, 158, 11, 0.15)` background, `#f59e0b` text)
  - `.badge-unknown`: Muted gray (`rgba(148, 163, 184, 0.1)` background, `#94a3b8` text)
- **Tooltip Support**: Hover tooltips explain routing reason:
  - `"Routed to PRODUCTION schema via CP ES / pp_log"` for production
  - `"Routed to SANDBOX schema via CP ES / pp_log"` for sandbox
  - `"Target schema pending resolution"` for unknown

#### 2. Error Source Detection and Display

- **Error Source Badge**: `detectErrorSourceForDisplay()` method identifies:
  - `"CP"` - for Elasticsearch/enrichment errors (amber badge)
  - `"Exensio"` - for Exensio API errors (blue badge)
- **Error Detection Logic**: Inspects error messages for patterns:
  - CP indicators: `[CP `, `cp enrichment`, `cp failure`, `cp timeout`, `cp pp_log`
  - Exensio indicators: `[Exensio `, `exensio load`, `exensio failure`, `exensio api`, `dead letter queue`
  - Falls back to `cpIntegrationStatus` and `exensioIntegrationStatus` fields

#### 3. Expandable Row Details

- **Click to Expand**: Rows with errors or CP output paths are expandable
- **Error Details Section**: Shows:
  - Error icon (red)
  - Source badge (CP or Exensio)
  - Full error message with line wrapping
- **CP Output Details Section**: Shows:
  - Folder icon
  - Output path label
  - Full output path (monospace, word-break)
  - Schema bound badge (PRODUCTION/SANDBOX/UNKNOWN) with tooltip

#### 4. Detail Line Implementation

Both components implement comprehensive detail line logic:

- `getDetailLine()`: Combines pipeline stage information
- `getEnrichmentSegment()`: Shows enrichment status
- `getExensioSegment()`: Shows Exensio loading status
- `getOutputTargetBadge()`: Shows schema bound for completed files
- `getErrorSummary()`: Shows truncated error with source prefix
- Color coding: success (green), error (red), warning (amber), muted (gray)

---

## Enhancement Requirements

### Current Image Analysis

From the attached monitor UI screenshot, we can see:

- **Status column**: Shows `EXENSIO MONITORING` badge with purple/blue icon
- **Filename column**: Displays `20250811611670721B_IT83889.1K_(1userscribe).zip`
- **Lot/Wafer columns**: Show `IT83889.1K` and `W_15`
- **Message column**: Shows `"Monitoring Exensio loading"`
- **Updated column**: Shows timestamp `2028-09-08 02:38:38 UTC`

### Missing Elements in Current UI

While the implementation is comprehensive, the **visual table layout needs additional columns**:

#### Table Column Additions Required:

| Current Columns | New Columns to Add                         |
| --------------- | ------------------------------------------ |
| Status          | ✅ Keep                                    |
| Filename        | ✅ Keep                                    |
| Lot             | ✅ Keep                                    |
| Wafer           | ✅ Keep                                    |
| Message         | ⚠️ **Replace with Detail Line**            |
| Updated         | ✅ Keep                                    |
|                 | ➕ **Schema Bound** (new dedicated column) |
|                 | ➕ **Step** (new column)                   |
|                 | ➕ **Tester** (new column)                 |
|                 | ➕ **Test Program/Recipe** (new column)    |

---

## Implementation Plan

### A. Realtime Monitoring File List (`realtime-monitoring-file-list.component.ts`)

#### 1. Update Table Headers (Template)

```typescript
<div class="table-header">
  <div class="col-status">Status</div>
  <div class="col-filename">Filename</div>
  <div class="col-lot">Lot</div>
  <div class="col-wafer">Wafer</div>
  <div class="col-step">Step</div>              // ➕ NEW
  <div class="col-tester">Tester</div>          // ➕ NEW
  <div class="col-recipe">Recipe</div>          // ➕ NEW
  <div class="col-schema">Schema</div>          // ➕ NEW (Schema Bound)
  <div class="col-detail">Pipeline Status</div> // 🔄 RENAMED from Message
  <div class="col-updated">Updated</div>
</div>
```

#### 2. Update Table Row Grid (Template)

```typescript
<div class="table-row"
     [style.grid-template-columns]="'140px 1fr 100px 80px 80px 100px 120px 120px 180px 180px'">

  <div class="col-status">...</div>
  <div class="col-filename">...</div>
  <div class="col-lot">{{ file.lot || '-' }}</div>
  <div class="col-wafer">{{ file.wafer || '-' }}</div>

  <!-- ➕ NEW COLUMNS -->
  <div class="col-step">
    <span class="step-badge" *ngIf="file.step">{{ file.step }}</span>
    <span *ngIf="!file.step" class="text-muted">-</span>
  </div>

  <div class="col-tester">
    <span class="tester-badge" *ngIf="file.testerId">{{ file.testerId }}</span>
    <span *ngIf="!file.testerId" class="text-muted">-</span>
  </div>

  <div class="col-recipe">
    <span class="recipe-code" *ngIf="file.testProgram" [glassTooltip]="file.testProgram">
      {{ file.testProgram }}
    </span>
    <span *ngIf="!file.testProgram" class="text-muted">-</span>
  </div>

  <div class="col-schema">
    <span class="schema-badge"
          [class.badge-production]="file.cpOutputTarget === 'PRODUCTION'"
          [class.badge-sandbox]="file.cpOutputTarget === 'SANDBOX'"
          [class.badge-pending]="!file.cpOutputTarget || file.cpOutputTarget === 'UNKNOWN'"
          [glassTooltip]="getSchemaTooltip(file)">
      {{ file.cpOutputTarget || '-' }}
    </span>
  </div>

  <!-- 🔄 UPDATED: Renamed from Message to Pipeline Status -->
  <div class="col-detail">
    <div class="detail-line"
         [class.detail-error]="getDetailLineColor(file) === 'error'"
         [class.detail-success]="getDetailLineColor(file) === 'success'"
         [class.detail-warning]="getDetailLineColor(file) === 'warning'"
         [class.detail-muted]="getDetailLineColor(file) === 'muted'"
         [glassTooltip]="isErrorTruncated(file) ? getFullErrorMessage(file) : null">
      {{ getDetailLine(file) }}
    </div>
  </div>

  <div class="col-updated">...</div>
</div>
```

#### 3. Add New Badge Styles (Styles)

```scss
// Step Badge (cyan pill)
.step-badge {
  display: inline-flex;
  align-items: center;
  padding: 0.25rem 0.625rem;
  border-radius: 6px;
  font-size: 0.7rem;
  font-weight: 700;
  text-transform: uppercase;
  letter-spacing: 0.06em;
  background: rgba(6, 182, 212, 0.15);
  color: #06b6d4;
  border: 1px solid rgba(6, 182, 212, 0.3);
}

// Tester Badge (purple monospace pill)
.tester-badge {
  display: inline-flex;
  align-items: center;
  padding: 0.25rem 0.625rem;
  border-radius: 6px;
  font-size: 0.7rem;
  font-weight: 700;
  font-family: monospace;
  text-transform: uppercase;
  letter-spacing: 0.06em;
  background: rgba(168, 85, 247, 0.15);
  color: #a855f7;
  border: 1px solid rgba(168, 85, 247, 0.3);
}

// Recipe Code (monospace code)
.recipe-code {
  font-family: monospace;
  font-size: 0.75rem;
  color: var(--text-main);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  display: inline-block;
  max-width: 120px;
}

// Schema Badge (prominent PRODUCTION/SANDBOX indicator)
.schema-badge {
  display: inline-flex;
  align-items: center;
  padding: 0.3rem 0.75rem;
  border-radius: 6px;
  font-size: 0.7rem;
  font-weight: 700;
  text-transform: uppercase;
  letter-spacing: 0.06em;
  white-space: nowrap;
}

.schema-badge.badge-production {
  background: rgba(16, 185, 129, 0.15);
  color: #10b981;
  border: 1px solid rgba(16, 185, 129, 0.3);
  box-shadow: 0 0 8px rgba(16, 185, 129, 0.2);
}

.schema-badge.badge-sandbox {
  background: rgba(245, 158, 11, 0.15);
  color: #f59e0b;
  border: 1px solid rgba(245, 158, 11, 0.3);
  box-shadow: 0 0 8px rgba(245, 158, 11, 0.2);
}

.schema-badge.badge-pending {
  background: rgba(148, 163, 184, 0.1);
  color: #94a3b8;
  border: 1px solid rgba(148, 163, 184, 0.2);
}

// Detail Line (Pipeline Status column)
.col-detail {
  overflow: hidden;
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
  min-height: 0;
}

.detail-line {
  font-size: 0.75rem;
  line-height: 1.2;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-weight: 500;
  letter-spacing: 0.02em;
}

.detail-line.detail-success {
  color: #10b981;
}

.detail-line.detail-error {
  color: #ef4444;
}

.detail-line.detail-warning {
  color: #f59e0b;
}

.detail-line.detail-muted {
  color: var(--text-muted);
}
```

#### 4. Add Helper Method (Component)

```typescript
/**
 * Get schema bound tooltip text based on file status and target.
 */
getSchemaTooltip(file: MonitoringFileItem): string {
  const target = file.cpOutputTarget;
  const cpStatus = file.cpIntegrationStatus;

  if (!target || target === 'UNKNOWN') {
    if (cpStatus === 'pending' || cpStatus === 'not_configured') {
      return 'Schema bound pending enrichment completion';
    }
    return 'Schema bound could not be determined';
  }

  if (target === 'PRODUCTION') {
    return 'Routed to PRODUCTION schema via CP Elasticsearch / pp_log analysis';
  }

  if (target === 'SANDBOX') {
    return 'Routed to SANDBOX schema via CP Elasticsearch / pp_log analysis';
  }

  return '';
}
```

#### 5. Update MonitoringFileItem Interface

```typescript
// In monitoring-pagination.service.ts or shared types file
export interface MonitoringFileItem {
  // ... existing fields ...
  step?: string | null; // ➕ NEW: Test step (e.g., "CP1", "PRB1")
  testerId?: string | null; // ➕ NEW: Tester equipment ID (e.g., "TST-02")
  testProgram?: string | null; // ➕ NEW: Recipe/program name (e.g., "RECIPE_A")
}
```

---

### B. Static Monitoring File List (`monitoring-file-list.component.ts`)

Apply the same changes as above:

1. Add new columns to table headers
2. Update grid template columns to accommodate new fields
3. Add badge styling for Step, Tester, Recipe, and Schema
4. Add `getSchemaTooltip()` helper method
5. Update `MonitoringFile` interface with `step`, `testerId`, `testProgram` fields

---

### C. Backend Integration (Optional Enhancement)

If `step`, `testerId`, and `testProgram` fields are not currently populated in the monitoring file records:

#### 1. Update `MonitoredFileEntity` (JPA Entity)

```java
@Entity
@Table(name = "monitored_files")
public class MonitoredFileEntity {
    // ... existing fields ...

    @Column(name = "step")
    private String step;

    @Column(name = "tester_id")
    private String testerId;

    @Column(name = "test_program")
    private String testProgram;
}
```

#### 2. Populate Fields During File Staging

In `StagingController.java` or wherever files are initially staged:

```java
// Extract from metadata or discovery preview
monitoredFile.setStep(previewRow.getStep());
monitoredFile.setTesterId(previewRow.getTesterId());
monitoredFile.setTestProgram(previewRow.getTestProgram());
```

#### 3. Include in DTOs

Update `MonitoredFileDto.java` to include the new fields:

```java
public class MonitoredFileDto {
    // ... existing fields ...
    private String step;
    private String testerId;
    private String testProgram;
}
```

---

## Visual Design Summary

### Expanded Table Layout (Step 3 Monitor)

```
┌─────────┬──────────┬─────┬───────┬──────┬────────┬────────┬──────────┬───────────────┬─────────┐
│ Status  │ Filename │ Lot │ Wafer │ Step │ Tester │ Recipe │  Schema  │   Pipeline    │ Updated │
│         │          │     │       │      │        │        │  Bound   │    Status     │         │
├─────────┼──────────┼─────┼───────┼──────┼────────┼────────┼──────────┼───────────────┼─────────┤
│ [icon]  │ 2025...  │ L01 │  W_1  │ CP1  │ TST-02 │ RCPE_A │ [PROD]   │ Enrichment:   │ 5m ago  │
│ EXENSIO │ file.zip │     │       │ [cy] │ [purp] │ [mono] │ [green]  │ Done · Exen-  │         │
│ MONITOR │          │     │       │      │        │        │  glow    │ sio: Monitoring│         │
└─────────┴──────────┴─────┴───────┴──────┴────────┴────────┴──────────┴───────────────┴─────────┘
```

**Key Visual Elements:**

- **Step Badge**: Cyan pill (`#06b6d4`) with uppercase text
- **Tester Badge**: Purple monospace pill (`#a855f7`)
- **Recipe Code**: Monospace gray text with ellipsis overflow
- **Schema Bound Badge**:
  - PRODUCTION: Emerald green glow with shadow
  - SANDBOX: Amber glow with shadow
  - `-` or UNKNOWN: Muted gray, no glow
- **Pipeline Status**:
  - Replaces generic "Message" field
  - Shows rich detail line with color coding
  - Green for success, amber for warning, red for error

---

## Horizontal Scrolling Support

With 10 columns (Status, Filename, Lot, Wafer, Step, Tester, Recipe, Schema, Pipeline Status, Updated), the table will be approximately **1550-1650px wide**.

### Responsive Strategy:

```scss
.file-table-wrapper {
  overflow-x: auto;
  overflow-y: auto;
}

.table-header,
.table-row {
  min-width: 1600px; // Ensure all columns have breathing room
  white-space: nowrap;
}

// Custom sleek scrollbars
.file-table-wrapper::-webkit-scrollbar {
  height: 10px;
  background: rgba(255, 255, 255, 0.03);
  border-radius: 5px;
}

.file-table-wrapper::-webkit-scrollbar-thumb {
  background: rgba(129, 140, 248, 0.3);
  border-radius: 5px;
}

.file-table-wrapper::-webkit-scrollbar-thumb:hover {
  background: rgba(129, 140, 248, 0.5);
}
```

---

## Verification Plan

### Manual Verification Checklist:

- [ ] **Schema Bound Column**:
  - [ ] Shows `PRODUCTION` with emerald green glow for production-bound files
  - [ ] Shows `SANDBOX` with amber glow for sandbox-bound files
  - [ ] Shows `-` or `UNKNOWN` with muted gray for pending files
  - [ ] Tooltip explains routing source (CP ES / pp_log)
- [ ] **New Columns (Step, Tester, Recipe)**:
  - [ ] Step shows cyan badge (e.g., `CP1`, `PRB1`)
  - [ ] Tester shows purple monospace badge (e.g., `TST-02`)
  - [ ] Recipe shows monospace code with ellipsis overflow
  - [ ] All show `-` when data is null/empty
- [ ] **Pipeline Status Column**:
  - [ ] Replaces generic "Message" field
  - [ ] Shows enrichment stage (e.g., "Enrichment: Done")
  - [ ] Shows Exensio stage (e.g., "Exensio: Monitoring")
  - [ ] Shows error with source prefix (e.g., "CP — Failed to parse...")
  - [ ] Color coded: green (success), amber (warning/processing), red (error)
- [ ] **Expanded Row Details**:
  - [ ] Click row to expand when errors or CP output exist
  - [ ] Error section shows source badge (CP or Exensio) and full message
  - [ ] CP output section shows path and schema badge with tooltip
- [ ] **Horizontal Scrolling**:
  - [ ] Table scrolls smoothly horizontally when window is narrow
  - [ ] Custom styled scrollbar appears
  - [ ] No awkward text truncation or column squishing
  - [ ] Table headers stay fixed when scrolling vertically

---

## Example Scenarios

### Scenario 1: File in Enrichment Processing

```
Status: ELASTICSEARCH_MONITORING (blue badge with refresh icon)
Schema Bound: - (gray, no glow, tooltip: "Schema bound pending enrichment completion")
Pipeline Status: "Enrichment: Processing" (amber text)
```

### Scenario 2: File Completed to PRODUCTION

```
Status: COMPLETED (green badge with check icon)
Schema Bound: PRODUCTION (emerald green glow, tooltip: "Routed to PRODUCTION schema...")
Pipeline Status: "Enrichment: Done · [PRODUCTION] · Exensio: Loaded" (green text)
Expanded Row: Shows CP output path with PRODUCTION badge
```

### Scenario 3: File Failed with CP Error

```
Status: ERROR (red badge with error icon)
Schema Bound: UNKNOWN (gray badge, tooltip: "Schema bound could not be determined")
Pipeline Status: "CP — Failed to parse pp_log output: invalid format" (red text)
Expanded Row: Shows "CP" source badge and full error message
```

### Scenario 4: File Completed to SANDBOX

```
Status: COMPLETED (green badge with check icon)
Schema Bound: SANDBOX (amber glow, tooltip: "Routed to SANDBOX schema via CP ES / pp_log analysis")
Pipeline Status: "Enrichment: Done · [SANDBOX] · Exensio: Loaded" (green text)
Expanded Row: Shows CP output path with SANDBOX badge and sandbox reason tooltip
```

---

## Summary

The Step 3 Monitor UI enhancement adds:

1. **Dedicated Schema Bound Column**: Prominent visual indicator with color-coded badges and informative tooltips
2. **Manufacturing Context Columns**: Step, Tester, Recipe fields for operational context
3. **Enhanced Pipeline Status**: Replaces generic message with rich, color-coded pipeline stage information
4. **Error Source Transparency**: Clear CP vs Exensio error attribution in both collapsed and expanded views
5. **Horizontal Scrolling Support**: Smooth, styled scrolling for wider table layout

All changes maintain the existing glass morphism design system and are consistent with Step 1 and Step 2 UI patterns.
