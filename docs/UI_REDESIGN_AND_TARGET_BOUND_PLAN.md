# Redesign Discovery Preview UI & Add Tester, Recipe, Step, and Target Bound (PRODUCTION vs SANDBOX)

## Overview
The user requested:
1. **Redesign the UI** because it currently feels very cluttered in the toolbar and preview area (duplicate pagination controls, crowded inputs, floating label misalignment on Device select).
2. **Add columns to the table**:
   - **`Tester` / `Tester ID`** (`row.testerId`)
   - **`Test Program` / `Recipe`** (`row.testProgram`)
   - **`Step`** (`row.step`)
   - **`Target Bound`**: Show whether the payload will be **`PRODUCTION`** or **`SANDBOX`** bound based on **CP ES logs** and/or **Oracle `pp_log` info**.
3. **Horizontal Scrolling**: If columns do not fit into the table width, provide smooth, styled horizontal scrolling without squishing or truncating text awkwardly.

---

## Architecture & Logic
> [!IMPORTANT]
> **PRODUCTION vs SANDBOX Bound Evaluation Architecture**:
> - **Source 1: CP Elasticsearch Logs**:
>   - Evaluated by `ElasticsearchLogService`:
>     - Message containing `"SANDBOX"` $\rightarrow$ **`SANDBOX`** bound
>     - Message containing `"PRODUCTION"`, `"COMMANDS FLOW EXECUTED SUCCESSFULLY"`, or `"OUTPUT PATH = "` $\rightarrow$ **`PRODUCTION`** bound
> - **Source 2: Oracle `pp_log` Information**:
>   - Evaluated by `RefDbService` (`queryPpLog` & `getSandboxReason`):
>     - `output_directory` containing `sandbox` or `log_message` containing `sandbox` $\rightarrow$ **`SANDBOX`** bound
>     - `output_directory` pointing to production path $\rightarrow$ **`PRODUCTION`** bound
>     - If sandbox, the reason (e.g. `--- sandbox: qualification / engineering test ---`) is extracted and surfaced as a tooltip
> - **Source 3: Exensio Pre-check / Verification**:
>   - When pre-checked against Exensio schemas (`verify-lots`), lots existing in `PRODUCTION` or `SANDBOX` are also indicated (`FOUND PROD`, `FOUND SBX`).
> - **Active Dispatch Monitoring Fix**:
>   - In `CpLogMonitor.java`, `ppSuccess` currently passes a static `"PP_LOG"` string as the output target. We enhance it to inspect `output_directory` and `log_message` so `cpOutputTarget` resolves to **`PRODUCTION`** or **`SANDBOX`** directly.

---

## Table Columns Order & Design
With horizontal scrolling enabled (`min-width: 1550px`), the table displays:

| Column Header | Field | Visual Style | Notes |
| :--- | :--- | :--- | :--- |
| `[Checkbox]` | Select | Checkbox | Stop propagation on row click |
| `Lot` | `row.lot` | Bold text | Main lot identifier |
| `Wafer` | `row.wafer` | Center mono | Shown if wafer-level data exists |
| **`Step`** | `row.step` | Blue glass tag | Test operation / probe step (e.g. `CP1`, `PRB1`) |
| **`Tester`** | `row.testerId` | Purple glass tag | Equipment / tester ID (e.g. `TST-02`) |
| **`Test Program`** | `row.testProgram` | Monospace code | Recipe / program name with full tooltip |
| **`Target Bound`** | CP ES & `pp_log` | **Green/Amber badge** | **`PROD BOUND`** or **`SANDBOX BOUND`** (with reason tooltip) |
| `Exensio Status` | `statusMap` | Status badge | `FOUND PROD`, `FOUND SBX`, `NOT FOUND`, `-` |
| `Device` | `row.device` | Standard text | e.g. `EPI`, `PREBONDSOI-DEV-OX` |
| `Metadata ID` | `row.metadataId` | Muted mono | e.g. `18025961` |
| `Data ID` | `row.dataId` | Muted mono | e.g. `19527541` |
| `Filename` | `row.filename` | Truncated mono | Full tooltip on hover |

---

## Proposed Changes

### Backend Updates

#### [CpLogMonitor.java](file:///c:/Users/fg8n8x/Desktop/wip/exensioreload/backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/service/CpLogMonitor.java)
- In `processRecord` (lines 222–228):
  - When `ppSuccess` succeeds, resolve target to `"SANDBOX"` if `ppSuccess.outputDirectory()` contains `"sandbox"` or if `row.logMessage()` indicates sandbox; otherwise `"PRODUCTION"`.
  - Pass the resolved target to `pipelineOrchestrator.onCpEnrichmentSuccess(record, ppSuccess.outputDirectory(), target)` so `cpOutputTarget` stores `PRODUCTION` or `SANDBOX`.

#### [RefDbService.java](file:///c:/Users/fg8n8x/Desktop/wip/exensioreload/backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/service/RefDbService.java)
- Expose a helper to resolve whether a `PpLogRow` or output path is sandbox-bound vs production-bound.

---

### Frontend Component Updates

#### [stepper.component.html](file:///c:/Users/fg8n8x/Desktop/wip/exensioreload/frontend/src/app/stepper/stepper.component.html)
- **Declutter Toolbar into 2 Clean Tiers**:
  - **Tier 1 (Selection & Primary Actions Ribbon)**:
    - Left: Select page checkbox, "Select All Matching" button, "Clear" button, selected counter badge, contextual selection hint.
    - Right: Primary `Check Exensio` button (prominent glass button with icon, loading spinner, and tooltip).
    - Removed redundant duplicate pagination buttons (`<< < Page 60 of 60 > >>`) from the top toolbar (relying on the full-featured bottom pagination bar).
  - **Tier 2 (Unified Filter & Search Ribbon)**:
    - Left: Segmented status quick-filter pills (`All`, `Missing in Exensio`, `Existing in Exensio`).
    - Right: Compact `Device` select (without misplaced floating label), `File Type` select, and global `Search` input.
- **Table Structure**:
  - Add headers: `Step`, `Tester`, `Test Program / Recipe`, and **`Target Bound`**.
  - Add data cells for `row.step`, `row.testerId`, `row.testProgram`, and target bound badge with `pp_log` / CP ES evaluation.
  - Wrap table in `.table-container-optimized` with horizontal scrolling.

#### [stepper.component.scss](file:///c:/Users/fg8n8x/Desktop/wip/exensioreload/frontend/src/app/stepper/stepper.component.scss)
- Replace `.table-toolbar` and `.quick-filter-toolbar` with `.discovery-actions-bar` and `.discovery-filters-bar`.
- Fix `.device-filter` floating label bug so placeholder and selected text align seamlessly without sticking out.
- Update `.table-container-optimized`:
  - `overflow-x: auto; overflow-y: auto;`
  - Custom sleek scrollbars for both dark and light modes.
- Update `.glass-table`:
  - `min-width: 1550px;` with `white-space: nowrap;` so all 12 columns have comfortable breathing room.
  - Sticky header (`th`) with solid glass blur backdrop.
  - Add badge styles:
    - `.target-badge.badge-prod`: Emerald green glow, indicating PRODUCTION bound.
    - `.target-badge.badge-sandbox`: Amber/orange glow with sandbox icon/tooltip, indicating SANDBOX bound.
    - `.step-badge`, `.tester-badge`, `.recipe-code`, `.cell-mono`, `.cell-bold`, `.cell-center`.
  - Light theme overrides for all new badges and ribbons.

#### [stepper.component.ts](file:///c:/Users/fg8n8x/Desktop/wip/exensioreload/frontend/src/app/stepper/stepper.component.ts)
- Add target bound helper / signal `getTargetBoundForRow(row: DiscoveryPreviewRow)`:
  - Determines if row is bound to `PRODUCTION` or `SANDBOX` based on:
    1. CP ES log result / `cpOutputTarget` if available
    2. `pp_log` info (matching output path or sandbox log)
    3. Exensio pre-check schema (`FOUND PROD` $\rightarrow$ `PRODUCTION`, `FOUND SBX` $\rightarrow$ `SANDBOX`)
    4. Default pending/unresolved indicator
- Update `filteredPreviewRows` search logic to search across `row.step`, `row.testerId`, `row.testProgram`, and target bound values in addition to `lot`, `wafer`, and `filename`.

#### [realtime-monitoring-file-list.component.ts](file:///c:/Users/fg8n8x/Desktop/wip/exensioreload/frontend/src/app/shared/components/realtime-monitoring-file-list.component.ts) & [monitoring-file-list.component.ts](file:///c:/Users/fg8n8x/Desktop/wip/exensioreload/frontend/src/app/shared/components/monitoring-file-list.component.ts)
- Ensure the `cpOutputTarget` badge properly highlights `PRODUCTION` vs `SANDBOX` with source indicators (`CP ES` or `pp_log`) and renders sandbox reason tooltips.

---

## Verification Plan

### Automated Verification
- Code compilation checks for Angular frontend.
- Verify that all interfaces, types, signals, and methods are properly typed.

### Manual Verification
- **Toolbar Decluttering**:
  - Open Step 2: verify top toolbar is clean, uncrowded, and free of duplicate pagination arrows.
  - Check that `Device` and `File Type` dropdown labels are properly aligned without awkward overlapping text.
- **New Columns**:
  - Verify `Step`, `Tester`, `Test Program`, and `Target Bound` columns appear in the table with appropriate badges.
  - Verify fallback `-` is shown when values are null.
- **PRODUCTION vs SANDBOX Bound**:
  - Check that files routed to PRODUCTION show the green `PROD` badge.
  - Check that files routed to SANDBOX show the amber `SANDBOX` badge with tooltip showing reason.
- **Horizontal Scrolling**:
  - Resize window: confirm table has smooth horizontal scroll with custom styled scrollbars.
  - Confirm table headers stay fixed at top when scrolling vertically.
- **Search & Filter**:
  - Verify searching by tester ID, recipe name, or step filters the table in real-time.
  - Verify quick filters and page selection checkboxes continue to function smoothly.
