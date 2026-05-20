# Design Document: Session Detail Modal Redesign

## Overview

The Session Detail Modal in `my-sessions.component.ts` will be refactored in-place — no new component files are created. The redesign targets the `.detail-overlay` / `.detail-modal` block and all its child sections. The goal is a visually centered, spatially balanced modal that feels like a premium dialog rather than a dense data dump, while keeping every existing feature intact.

The existing design language (glassmorphism, CSS custom properties, `glass-panel`, `glass-button`) is preserved and extended.

---

## Architecture

The modal lives entirely within `my-sessions.component.ts` as inline template + styles. The refactor touches:

- **Template**: restructure the `detail-overlay` block — new header layout, metric cards, section toggles
- **Styles**: replace/extend the relevant CSS rules (`.detail-overlay`, `.detail-modal`, `.detail-head`, `.metrics`, `.charts-*`, `.files-*`)
- **Component logic**: no new signals or methods needed; existing `chartsExpanded()`, `filesTableExpanded()`, `copiedSessionId()`, `toggleChartsPanel()`, `toggleFilesTable()` are reused as-is

No new Angular components, services, or modules are introduced.

---

## Components and Interfaces

### Modal Shell

```
.detail-overlay          — fixed full-screen backdrop, flex center
  .detail-modal          — max-w 900px, max-h 88vh, border-radius 20px, overflow-y auto
    .modal-header        — sticky top-0, z-index 10
      .modal-title-row   — session ID + status badge
      .modal-meta-row    — updated date + copy-id button
      .modal-actions     — action toolbar (pill group)
    .modal-body          — scrollable content area
      .metrics-cards     — 5 metric cards in flex row
      .status-row        — standalone status badge
      .date-range-bar    — file date range (conditional)
      .section-toggle    — Analytics toggle button
      .analytics-panel   — collapsible analytics content
      .section-toggle    — Files toggle button
      .files-panel       — collapsible files table
```

### Metric Card Structure (per card)

```html
<div class="metric-card metric-card--{type}">
  <div class="metric-icon"><!-- SVG/icon --></div>
  <div class="metric-value">{{ value }}</div>
  <div class="metric-label">{{ label }}</div>
</div>
```

Types: `total`, `staged`, `enqueued`, `done`, `failed`

### Section Toggle Button

```html
<button class="section-toggle" [class.expanded]="isExpanded" (click)="toggle()">
  <span class="toggle-label">Section Name · <span class="toggle-count">N</span></span>
  <span class="toggle-chevron">▾</span>
</button>
```

---

## Data Models

No new data models. Existing types from `BackendService` are used:

- `StagingSessionSummary` — session row data (sessionId, status, updatedAt, etc.)
- `StagingSessionDetail` — detail data (totalFiles, filesStaged, filesEnqueued, filesDone, filesFailed, status)
- `StageRecordView` — individual file record (lot, wafer, filename, status, createdAt, endTime)

---

## Correctness Properties

*A property is a characteristic or behavior that should hold true across all valid executions of a system — essentially, a formal statement about what the system should do. Properties serve as the bridge between human-readable specifications and machine-verifiable correctness guarantees.*

### Property 1: Session ID truncation is consistent

*For any* session ID string, `truncateSessionId(id)` should return a string that starts with the first 8 characters of the input and ends with `"..."`, and should always be shorter than the original input when the input is longer than 8 characters.

**Validates: Requirements 2.1**

### Property 2: Copy ID confirmation state resets

*For any* session ID, after the "Copy ID" button is clicked, `copiedSessionId()` should equal that session ID immediately, and after 2000ms it should return to a falsy/different value.

**Validates: Requirements 2.5**

### Property 3: Metric cards render correct values and color classes

*For any* `StagingSessionDetail` object, the rendered metric cards should: (a) display the exact numeric values from the detail object, (b) apply the correct color-class (`metric-card--done` for filesDone, `metric-card--failed` for filesFailed, etc.), and (c) the Failed card should be present in the DOM regardless of whether filesFailed is 0 (de-emphasized, not absent).

**Validates: Requirements 3.1, 3.3, 3.4**

### Property 4: File status badges use correct CSS class

*For any* file status value in `{READY, ENQUEUED, PROCESSING, COMPLETED, ERROR, CANCELLED}`, the status badge rendered in the files table should have a CSS class that matches the lowercase status value.

**Validates: Requirements 6.3**

---

## Error Handling

- If `selectedDetail()` is null when the modal is open, metric cards render with `0` values (existing behavior preserved)
- If `files()` is empty, the Files section toggle is hidden (CSS `*ngIf` on the files section)
- Copy-to-clipboard failure (e.g. insecure context): the button falls back to showing "Copy ID" without error state change

---

## Testing Strategy

### Unit Tests (Jasmine/Karma — existing test setup)

- `truncateSessionId('')` → `'...'` (edge case: empty string)
- `truncateSessionId('abc')` → `'abc...'` (shorter than 8 chars)
- `truncateSessionId('c013ed61-d37f-4b2a-9100431b9e')` → `'c013ed61...'`
- Copy ID: after click, signal equals session ID; after 2s timeout, signal is falsy
- Files panel hidden when `files()` is empty

### Property-Based Tests (fast-check — to be added)

Each property test runs a minimum of 100 iterations.

**Feature: session-detail-modal-redesign, Property 1: Session ID truncation is consistent**
- Generator: arbitrary string of length 0–200
- Assertion: result starts with `input.slice(0, 8)` and ends with `'...'`

**Feature: session-detail-modal-redesign, Property 3: Metric cards render correct values and color classes**
- Generator: arbitrary `StagingSessionDetail` with non-negative integer fields
- Assertion: each card value matches the corresponding field; correct class applied; failed card always present

**Feature: session-detail-modal-redesign, Property 4: File status badges use correct CSS class**
- Generator: arbitrary status from the known enum set
- Assertion: rendered badge has class equal to `status.toLowerCase()`

---

## Visual Design Reference

### Color Tokens (dark theme)

| Token | Value | Usage |
|---|---|---|
| `--metric-total-bg` | `rgba(129,140,248,0.12)` | Total card background |
| `--metric-done-bg` | `rgba(16,185,129,0.12)` | Done card background |
| `--metric-done-color` | `#10b981` | Done value + icon |
| `--metric-failed-bg` | `rgba(239,68,68,0.12)` | Failed card background |
| `--metric-failed-color` | `#ef4444` | Failed value + icon |
| `--metric-enqueued-bg` | `rgba(245,158,11,0.12)` | Enqueued card background |
| `--metric-enqueued-color` | `#f59e0b` | Enqueued value + icon |
| `--modal-shadow` | `0 32px 80px rgba(10,8,28,0.6), 0 0 0 1px rgba(167,139,250,0.18), 0 8px 32px rgba(99,102,241,0.25)` | Modal box-shadow |

### Layout Sketch

```
┌─────────────────────────────────────────────────────────┐
│  [●] c013ed61...  COMPLETED                    [× Close] │  ← sticky header
│  Updated: 4/13/2026  [Copy ID]                           │
│  [Refresh] [Export CSV] [Cancel]                         │
├─────────────────────────────────────────────────────────┤
│  ┌──────┐ ┌──────┐ ┌──────┐ ┌──────┐ ┌──────┐          │
│  │ 100  │ │  0   │ │  0   │ │ 100  │ │  0   │          │
│  │Total │ │Staged│ │Enq'd │ │ Done │ │Failed│          │
│  └──────┘ └──────┘ └──────┘ └──────┘ └──────┘          │
│  Status: [COMPLETED]                                     │
│  File Date Range: Earliest … Latest                      │
├─────────────────────────────────────────────────────────┤
│  ▾ Session Analytics                                     │  ← toggle
│    [From] [To] [Apply] [Clear]                           │
│    [Today][7d][30d][90d][Month][All]                     │
│    [Completed 100%] [Failed 0%] [Total 100]              │
│    ┌──────────────────┐  ┌──────────────────┐           │
│    │  Daily Trend     │  │  Distribution    │           │
│    └──────────────────┘  └──────────────────┘           │
├─────────────────────────────────────────────────────────┤
│  ▾ Files Details · 100                                   │  ← toggle
│    ┌─────────────────────────────────────────────────┐  │
│    │ Lot │ Wafer │ Filename │ Status │ Created │ End  │  │
│    │ ... │  ...  │   ...    │  ...   │   ...   │ ... │  │
│    └─────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────┘
```

### Key CSS Changes Summary

```css
/* Modal shell */
.detail-modal {
  max-width: 900px;
  max-height: 88vh;
  border-radius: 20px;
  box-shadow: var(--modal-shadow);
  display: flex;
  flex-direction: column;
  overflow: hidden; /* header sticky works inside */
}

/* Sticky header */
.modal-header {
  position: sticky;
  top: 0;
  z-index: 10;
  padding: 1.25rem 1.5rem 1rem;
  background: linear-gradient(to bottom, rgba(31,23,61,0.98), rgba(31,23,61,0.85));
  backdrop-filter: blur(12px);
  border-bottom: 1px solid rgba(167,139,250,0.15);
}

/* Metric cards */
.metrics-cards {
  display: flex;
  gap: 0.75rem;
  flex-wrap: wrap;
}
.metric-card {
  flex: 1;
  min-width: 100px;
  padding: 0.875rem 1rem;
  border-radius: 14px;
  border: 1px solid rgba(255,255,255,0.06);
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 0.25rem;
  transition: transform 0.2s ease;
}
.metric-card:hover { transform: translateY(-2px); }
.metric-card--failed.zero { opacity: 0.45; }

/* Section toggles */
.section-toggle {
  width: 100%;
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 0.75rem 1rem;
  border-radius: 12px;
  border: 1px solid rgba(167,139,250,0.2);
  background: rgba(67,56,132,0.3);
  cursor: pointer;
  transition: background 0.2s ease;
}
.section-toggle:hover { background: rgba(99,102,241,0.25); }
.toggle-chevron {
  transition: transform 0.2s ease;
  display: inline-block;
}
.section-toggle.expanded .toggle-chevron { transform: rotate(180deg); }
```
