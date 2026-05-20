# Implementation Plan: Session Detail Modal Redesign

## Overview

All changes are confined to `new_frontend/src/app/my-sessions/my-sessions.component.ts`. The refactor restructures the modal template and replaces/extends the relevant CSS rules. No new files or Angular components are created.

## Tasks

- [x] 1. Redesign the modal shell and sticky header
  - Replace `.detail-modal` CSS with max-width 900px, max-height 88vh, border-radius 20px, layered box-shadow, and `overflow: hidden` so the sticky header works correctly
  - Restructure the `.detail-head` template block into three rows: title row (session ID + status badge), meta row (updated date + copy-id button), and action toolbar row
  - Make the header `position: sticky; top: 0; z-index: 10` with a frosted-glass gradient background
  - Add a `border-bottom` separator line beneath the header
  - _Requirements: 1.1, 1.2, 1.5, 2.1, 2.2, 2.3, 2.4, 7.3_

- [x] 1.1 Write unit tests for truncateSessionId
  - Test empty string, string shorter than 8 chars, and a full UUID
  - _Requirements: 2.1_

- [x] 2. Replace metrics pills with metric cards
  - Replace the `.metrics` flex-pill row with a `.metrics-cards` flex row of 5 cards (Total, Staged, Enqueued, Done, Failed)
  - Each card: icon + large numeric value + label, using `GlassIconComponent` for icons
  - Apply color modifier classes: `metric-card--total`, `metric-card--staged`, `metric-card--enqueued`, `metric-card--done`, `metric-card--failed`
  - When `filesFailed === 0`, add class `zero` to the failed card (opacity 0.45) — do NOT use `*ngIf` to hide it
  - Move the Status value to a standalone `Status_Badge` row below the cards
  - Add all corresponding light-theme CSS overrides
  - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5, 8.1, 8.2, 8.3_

- [x] 2.1 Write property test for metric card rendering
  - **Property 3: Metric cards render correct values and color classes**
  - **Validates: Requirements 3.1, 3.3, 3.4**

- [x] 3. Redesign section toggle buttons with chevron animation
  - Replace `.charts-toggle` and `.files-toggle` with a unified `.section-toggle` style
  - Add a `.toggle-chevron` span that rotates 180° via CSS transition when the parent has class `expanded`
  - Bind `[class.expanded]="chartsExpanded()"` and `[class.expanded]="filesTableExpanded()"` on the respective toggles
  - Show file count inline in the Files toggle label: "Files Details · {{ files().length }}"
  - Hide the Files toggle entirely when `files().length === 0` using `*ngIf`
  - Add light-theme overrides for the new toggle style
  - _Requirements: 4.1, 4.2, 4.4, 6.1, 6.4_

- [x] 4. Improve the Analytics panel layout
  - Group date-range inputs and preset buttons into a single `.analytics-toolbar` row
  - Style the status summary pills with color-coded backgrounds: completed=green, failed=red, cancelled=red, total=indigo
  - Ensure the `.charts-grid` uses `grid-template-columns: 1fr 1fr` with a `@media (max-width: 600px)` override to `1fr`
  - Set `min-height: 220px` on `.trend-chart-container` and `.status-chart-container`
  - Add a subtle inner border and distinct background to `.chart-card`
  - Add light-theme overrides
  - _Requirements: 5.1, 5.2, 5.3, 5.4, 8.1_

- [x] 5. Improve the Files table panel
  - Set `max-height: 340px` on `.detail-table-wrap` with `overflow-y: auto`
  - Add `position: sticky; top: 0` to `thead th` inside the files table
  - Add alternating row backgrounds using `tbody tr:nth-child(even)`
  - Replace plain text in the Status column with colored status badge spans using the same `.status-badge` class pattern
  - Add light-theme overrides for alternating rows and sticky headers
  - _Requirements: 6.2, 6.3, 8.1_

- [x] 5.1 Write property test for file status badge CSS class
  - **Property 4: File status badges use correct CSS class**
  - **Validates: Requirements 6.3**

- [x] 6. Add responsive breakpoints and accessibility attributes
  - Add `@media (max-width: 640px)` rules: action toolbar stacks vertically with full-width buttons; metrics cards switch to `grid-template-columns: 1fr 1fr`
  - Ensure `role="dialog"`, `aria-modal="true"`, and `aria-labelledby="modal-session-title"` are on `.detail-modal`
  - Add `id="modal-session-title"` to the session ID heading element
  - Verify the overlay click-to-close handler is on `.detail-overlay` (already exists — confirm it's intact)
  - _Requirements: 7.1, 7.2, 7.4, 7.5_

- [x] 7. Final checkpoint — verify all tests pass
  - Ensure all tests pass, ask the user if questions arise.
