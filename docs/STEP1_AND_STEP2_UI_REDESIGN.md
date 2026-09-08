# Exensio Reload: UI Redesign & Architecture Walkthrough

This document records the design and implementation of the UI overhaul across **Step 1 (Filter Configuration)** and **Step 2 (Discovery Preview)**, along with the backend **Target Bound (PRODUCTION vs SANDBOX)** resolution.

---

## Step 1: Filter Configuration Form Redesign

### The Problems in the Previous UI:
1. **Broken Flex Wrapping & Staggered Row 4**:
   - `Time Range`, `Device`, `Step`, `Recipe`, and `Equipment ID` were placed together in `.time-device-row`.
   - `Recipe` and `Equipment ID` wrapped underneath `Time Range` into an unbalanced half-row.
   - Placeholder text was truncated (`"values, comr..."`, `"values, co..."`).
   - Device select had multi-line helper text while Step had none, resulting in awkward vertical alignment.
2. **Floating Historical Mode Checkbox**:
   - Historical mode sat as an isolated checkbox on row 3 with empty space below.
3. **Missing Icon Question Mark `(?)`**:
   - The "Bulk Add Lots" button referenced `upload_file`, which was missing in `glass-icon.component.ts` and triggered the `<g *ngSwitchDefault>` question mark icon.
4. **Unstructured Flat Form**:
   - Everything sat in one flat card separated only by 1px divider lines without section hierarchy.

### The New Modular Design System:
- **Scope & Pipeline Panel (`.form-subpanel`)**:
  - **Row 1 (4 Columns)**: `Environment` (PROD / Dev), `Site / DTP Instance`, `Location`, and `Data Type` with identical widths and clean alignments.
  - **Row 2 (4 Columns)**: `Tester Type`, `Data Type Ext`, `Test Phase` (with inline loading spinner), and the **Historical Mode Toggle Card**.
- **Historical Mode Card (`.historical-mode-card`)**:
  - Clickable glass card with history icon, title, description ("Scan archive records"), and an animated toggle switch that lights up with an accent border on activation.
- **Dedicated Time Range Subpanel (`.time-range-subpanel`)**:
  - Sits in its own dedicated card with clock icon and subtitle.
  - Full-width spacing so `From Date`, `To Date`, `From Time`, and `To Time` fit comfortably without arrow collisions or truncation.
- **Advanced Manufacturing Criteria Grid (`.admin-filters-subpanel`)**:
  - Clean **4-column responsive grid** for `Device`, `Step`, `Recipe`, and `Equipment ID`.
  - Consistent 52px field wrapper / 38px native input heights.
  - Aligned icons (`filter_list`, `science`, `precision_manufacturing`) and clear, untruncated placeholder examples (`e.g. CP1`, `e.g. RECIPE_A`, `e.g. TST-01`).
- **Sender Configuration Subpanel (`.sender-subpanel`)**:
  - Shows locked explanation when prerequisites are not met.
  - Shows verified `Auto-Resolved` badge and aligned sender selector when prerequisites are met.
- **Lot / Wafer Criteria Subpanel (`.lot-wafer-subpanel`)**:
  - Header actions: Count pill, **Bulk Add Lots** button with real `upload_file` SVG (no question mark), and **Hide / Show** toggle.
  - Symmetrical Lot and Wafer input rows with aligned red glass delete buttons.
  - Styled "+ Add Lot/Wafer Pair" button.
- **New Icons (`glass-icon.component.ts`)**:
  - Added `upload_file`, `restart_alt`, `tune`, and `filter_list` SVGs.

---

## Step 2: Discovery Preview Table & Target Bound

### 1. 2-Tier Discovery Control Ribbon
- **Tier 1 (Actions)**: Select-all-page checkbox, "Select All Matching", "Clear" button, count pill, hint, and `Check Exensio` button. Removed redundant top pagination arrows.
- **Tier 2 (Filters)**: Status pills (`Show All`, `Show Missing Only`, `Show Existing Only`), compact `Device` select, compact `File Type` select, and unified search input.

### 2. Expanded Table Columns & Badges
- **`Step`**: Formatted with `.step-badge` (cyan pill).
- **`Tester`**: Formatted with `.tester-badge` (monospace purple pill).
- **`Test Program / Recipe`**: Formatted with `.recipe-code` monospace pill and tooltip.
- **`Target Bound`**:
  - **`PROD BOUND`**: Emerald green glowing badge.
  - **`SANDBOX BOUND`**: Amber glowing badge with routing reason tooltip.
  - **`-`**: Neutral badge when pending dispatch.

### 3. Smooth Horizontal Scroll
- Table `min-width: 1550px` and cell `white-space: nowrap`.
- Container with `overflow-x: auto; overflow-y: auto;` and sticky blurred table headers.

### 4. Backend & Frontend Target Bound Resolution
- In `CpLogMonitor.java`: `ppSuccess` inspects `output_directory` and `log_message` for sandbox indicators, writing `"SANDBOX"` or `"PRODUCTION"` into `cp_output_target`.
- In `stepper.component.ts`: `getTargetBoundForRow()` evaluates CP output target, Exensio multi-schema pre-check (`FOUND PROD` vs `FOUND SBX`), and test program naming patterns in real time.
