# Exensio Reload - Discovery, Precheck & Reload Architecture

## Unified Single-Grid UX Architecture

The Exensio Reload user experience combines **Discovery** and **Precheck** into a single, interactive data grid. The user inputs search criteria, inspects discovered wafer-level records, decorates the same grid in-place with **Exensio Status**, uses **Quick Filters** to isolate missing wafers, and reloads only the targeted selection.

```
┌────────────────────────────────────────────────────────────────────────┐
│ STEP 1: USER INPUT / SEARCH                                            │
│ User enters Lot ID (e.g., LOTA), Date Range, Site Location, Data Type  │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│ STEP 2: DISCOVERY POPULATES UNIFIED GRID                              │
│ Local DTP view query returns wafer/file rows into primary data table  │
│                                                                        │
│ Grid State:                                                            │
│ ┌───┬──────┬───────┬──────────┬────────────────┬─────────────────────┐ │
│ │   │ Lot  │ Wafer │ File     │ Exensio Status │ Action              │ │
│ ├───┼──────┼───────┼──────────┼────────────────┼─────────────────────┤ │
│ │[ ]│ LOTA │ W01   │ file_01  │ - (Unchecked)  │                     │ │
│ │[ ]│ LOTA │ W02   │ file_02  │ - (Unchecked)  │                     │ │
│ │[ ]│ LOTA │ W03   │ file_03  │ - (Unchecked)  │                     │ │
│ │[ ]│ LOTA │ W04   │ file_04  │ - (Unchecked)  │                     │ │
│ └───┴──────┴───────┴──────────┴────────────────┴─────────────────────┘ │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│ STEP 3: USER CLICKS "CHECK EXENSIO"                                    │
│ Parallel API Precheck (/v1/key/raw-sql) decorates grid rows in-place   │
│                                                                        │
│ Updated Grid State:                                                    │
│ ┌───┬──────┬───────┬──────────┬────────────────┬─────────────────────┐ │
│ │   │ Lot  │ Wafer │ File     │ Exensio Status │ Action              │ │
│ ├───┼──────┼───────┼──────────┼────────────────┼─────────────────────┤ │
│ │[ ]│ LOTA │ W01   │ file_01  │ FOUND PROD     │                     │ │
│ │[ ]│ LOTA │ W02   │ file_02  │ FOUND SBX      │                     │ │
│ │[ ]│ LOTA │ W03   │ file_03  │ NOT FOUND      │                     │ │
│ │[ ]│ LOTA │ W04   │ file_04  │ NOT FOUND      │                     │ │
│ └───┴──────┴───────┴──────────┴────────────────┴─────────────────────┘ │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│ STEP 4: QUICK FILTERS & TARGETED RELOAD                                │
│ User clicks [Show Missing Only] -> Isolates NOT FOUND rows             │
│ User checks [x] W03, [x] W04 -> Clicks "Start Reload (2 Selected)"     │
│                                                                        │
│ Filtered Grid View:                                                    │
│ ┌───┬──────┬───────┬──────────┬────────────────┬─────────────────────┐ │
│ │   │ Lot  │ Wafer │ File     │ Exensio Status │ Action              │ │
│ ├───┼──────┼───────┼──────────┼────────────────┼─────────────────────┤ │
│ │[x]│ LOTA │ W03   │ file_03  │ NOT FOUND      │ Ready to Stage      │ │
│ │[x]│ LOTA │ W04   │ file_04  │ NOT FOUND      │ Ready to Stage      │ │
│ └───┴──────┴───────┴──────────┴────────────────┴─────────────────────┘ │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│ STEP 5: STAGING & CP DISPATCH                                          │
│ Selected missing rows (W03, W04) are staged to SENDER_STAGE DB         │
│ and dispatched to CP worker queue                                      │
└────────────────────────────────────────────────────────────────────────┘
```

---

## 1. Quick Filter Control Specifications

| Filter Button | Action / Criteria | Target Operator Use Case |
| :--- | :--- | :--- |
| **`[Show Missing Only]`** | Filters grid to display only rows where `Exensio Status` == `NOT FOUND`. Auto-checks all visible rows. | Fast 1-click reload of missing wafers without manual unchecking. |
| **`[Show Existing Only]`** | Filters grid to display rows where `Exensio Status` is `FOUND PROD` or `FOUND SBX`. | Verification / auditing to verify what was already loaded into Exensio. |
| **`[Show All]`** | Resets filter; displays all discovered rows (both existing and missing). | Complete overview of all lot files. |

---

## 2. Grid Column Specifications

| Column Header | Data Source | Field Details & Rendering |
| :--- | :--- | :--- |
| **Selection (`[x]`)** | Component State | Checkbox for staging selection. Supports header "Select All Visible". |
| **Lot ID** | Local DTP View (`lot`) | Lot identification string (e.g. `LOTA`). |
| **Wafer ID** | Local DTP View (`wafer` / `wf_id`) | Sourced wafer number (e.g. `W01`, `W02`). |
| **File / Data ID** | Local DTP View (`filename` / `data_id` / `metadata_id`) | Original payload filename or ID. |
| **Exensio Status** | Exensio Raw-SQL API (`/v1/key/raw-sql`) | **`- (Unchecked)`** (default)<br>**`FOUND PROD`** (green badge)<br>**`FOUND SBX`** (blue/yellow badge)<br>**`NOT FOUND`** (grey/red badge) |
| **Actions** | Component State | Staging status indicator (`Ready`, `Staged`, `Dispatched`). |

---

## 3. System Architecture & Component Mapping

```
┌────────────────────────────────────────────────────────────────────────┐
│                         UI Layer (Frontend)                            │
│  - Unified Data Grid (Lot, Wafer, File, Exensio Status, Checkbox)      │
│  - Quick Filter Toolbar: [Show Missing Only] [Show Existing Only] [Show All]│
│  - Primary Actions: "Check Exensio" & "Start Reload Selected"          │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
               ┌────────────────────┴────────────────────┐
               │ HTTP POST /discover/preview-with-duplicates
               ▼                                         ▼
┌──────────────────────────────────────┐   ┌─────────────────────────────┐
│    MetadataImporterService           │   │    SenderController         │
│  - Queries local site DTP views      │   │  - Orchestrates Discovery   │
│    (dtp_probe_view, dtp_pcm_view,    │   │    + Parallel Precheck     │
│     dtp_defect_view, etc.)           │   └──────────────┬──────────────┘
│  - Sources Lot & Wafer relationships │                  │
└──────────────────┬───────────────────┘                  │
                   │                                      ▼
                   │                       ┌─────────────────────────────┐
                   │                       │ ParallelSchemaCheckService  │
                   │                       │ - Thread A: PRODUCTION      │
                   │                       │ - Thread B: SANDBOX         │
                   │                       └──────────────┬──────────────┘
                   │                                      │
                   │                                      ▼
                   │                       ┌─────────────────────────────┐
                   │                       │ ExensioPreCheckService      │
                   │                       │ - Queries Exensio API       │
                   │                       │   POST /v1/key/raw-sql      │
                   │                       │ - Optional Snowflake        │
                   │                       │   secondary fallback        │
                   │                       └──────────────┬──────────────┘
                   │                                      │
                   └──────────────────┬───────────────────┘
                                      │
                                      ▼
┌────────────────────────────────────────────────────────────────────────┐
│                       SENDER_STAGE & RELOAD                            │
│  - RefDbService / StageSessionService                                  │
│  - Enqueue selected payloads -> Dispatch to CP -> Monitor completion   │
└────────────────────────────────────────────────────────────────────────┘
```

---

## 4. Class Responsibility Summary

| Class Name | Responsibility | Sourcing & Dependencies |
| :--- | :--- | :--- |
| **`MetadataImporterService`** | Queries site-specific local DTP views (`dtp_probe_view`, etc.) to populate the initial grid rows with lots, files, and wafer numbers. | Direct JDBC to Site DB via `ExternalDbConfig` |
| **`WaferDiscoveryService`** | Discovers wafer list for given lots from database when explicit wafer numbers are needed for lot-level requests. | Queries local/Exensio DB (`op_log`, `lot`, `wafer`) |
| **`ExensioPreCheckService`** | Executes Exensio API `POST /v1/key/raw-sql` queries against `PRODUCTION` & `SANDBOX` schemas to update the grid `Exensio Status` column. | Exensio HTTP API + Auth Token Service |
| **`ParallelSchemaCheckService`** | Runs `ExensioPreCheckService` in parallel threads across `PRODUCTION` and `SANDBOX` schemas for selected grid rows. | `CompletableFuture` async execution |
| **`SenderController`** | Provides HTTP endpoints for preview discovery (`/discover/preview-with-duplicates`), precheck verification (`/verify-lots`), and staging selected items (`/enqueue`, `/dispatch`). | Spring REST Controller |
| **`RefDbService`** | Manages `SENDER_STAGE` persistence for selected missing grid rows and lifecycle updates (`STAGED` -> `QUEUED_FOR_CP` -> `COMPLETED`). | JDBC DataSource |
