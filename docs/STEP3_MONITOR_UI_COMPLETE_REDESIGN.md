# Step 3 Monitor UI: Complete Redesign & Enhancement Plan

This document outlines a comprehensive redesign of **Step 3 (Monitor Dispatch)** UI, bringing it in line with the modern, decluttered design introduced in **Step 1** and **Step 2** while adding schema bound indication and enhanced error reporting.

---

## Overview

### Design Goals

1. **Declutter the UI**: Implement 2-tier control ribbon like Step 2
2. **Show Schema Bound**: Dedicated column for PRODUCTION vs SANDBOX routing
3. **Add Manufacturing Context**: Step, Tester, Recipe columns for operational tracking
4. **Enhanced Error Display**: Clear source indication (CP vs Exensio) with expandable details
5. **Better Visual Hierarchy**: Separate file counts, actions, filters, and search

---

## Current UI Problems

From the attached monitor screenshot:

### Toolbar Issues:

- **Cramped Single-Row Layout**: Status filters, file count, search, and export all compete in one row
- **8 Status Filter Chips**: Too many buttons (`All`, `Staged`, `Queued`, `Enrichment`, `Exensio`, `Verify`, `Completed`, `Failed`)
- **Poor Visual Separation**: No clear distinction between metrics, actions, and filters
- **Hidden Stream Status**: Live/polling indicator buried in header

### Table Issues:

- **Generic "Message" Column**: Doesn't convey rich pipeline information
- **Hidden Schema Info**: PRODUCTION/SANDBOX only visible in expanded rows
- **Missing Context**: No Step, Tester, or Recipe columns
- **Cluttered Status Column**: Status text sometimes hidden on smaller screens

---

## Redesigned UI Architecture

### **2-Tier Control Ribbon** (Like Step 2 Discovery)

```
┌────────────────────────────────────────────────────────────────────────────┐
│ TIER 1: METRICS & STREAM STATUS (Monitoring Overview)                      │
├────────────────────────────────────────────────────────────────────────────┤
│  📊 100 files  •  🟢 Live Updates  •  ⏱ Elapsed: 17m 38s  •  📈 0 files/min │
└────────────────────────────────────────────────────────────────────────────┘

┌────────────────────────────────────────────────────────────────────────────┐
│ TIER 2: FILTERS & SEARCH (Control Bar)                                     │
├────────────────────────────────────────────────────────────────────────────┤
│ [All (100)] [Ready] [Queued] [Processing] [Completed] [Failed]             │
│                                                  [Search...] [Export CSV]    │
└────────────────────────────────────────────────────────────────────────────┘

┌────────────────────────────────────────────────────────────────────────────┐
│ TABLE: Enhanced Columns with Schema Bound                                  │
└────────────────────────────────────────────────────────────────────────────┘
```

---

## Implementation: Realtime Monitoring File List

### 1. Complete Template Redesign

```typescript
template: `
  <div class="realtime-file-list glass-panel">

    <!-- ═══════════════════════════════════════════════════════ -->
    <!-- TIER 1: MONITORING METRICS & STREAM STATUS              -->
    <!-- ═══════════════════════════════════════════════════════ -->
    <div class="monitoring-metrics-bar">
      <div class="metrics-left">
        <div class="metric-item">
          <app-glass-icon name="dashboard" [size]="16" color="primary"></app-glass-icon>
          <span class="metric-value">{{ state().totalCount }}</span>
          <span class="metric-label">files</span>
        </div>

        <div class="stream-status-indicator"
             [class.live]="state().streamStatus === 'live'"
             [class.polling]="state().streamStatus === 'polling'"
             [class.connecting]="state().streamStatus === 'connecting'"
             [glassTooltip]="streamStatusTooltip()">
          <div class="status-dot"></div>
          <span class="status-text">{{ streamStatusLabel() }}</span>
        </div>

        <div class="metric-item" *ngIf="elapsedTime()">
          <app-glass-icon name="schedule" [size]="16" color="muted"></app-glass-icon>
          <span class="metric-label">Elapsed:</span>
          <span class="metric-value">{{ elapsedTime() }}</span>
        </div>

        <div class="metric-item" *ngIf="throughput() > 0">
          <app-glass-icon name="speed" [size]="16" color="muted"></app-glass-icon>
          <span class="metric-value">{{ throughput() }}</span>
          <span class="metric-label">files/min</span>
        </div>
      </div>

      <div class="metrics-right">
        <app-glass-button
          variant="icon"
          size="small"
          [glassTooltip]="'Refresh monitoring data'"
          (clicked)="refreshMonitoring()">
          <app-glass-icon name="refresh" [size]="18"></app-glass-icon>
        </app-glass-button>
      </div>
    </div>

    <!-- ═══════════════════════════════════════════════════════ -->
    <!-- TIER 2: FILTERS & SEARCH                                -->
    <!-- ═══════════════════════════════════════════════════════ -->
    <div class="monitoring-filters-bar">
      <div class="filters-left">
        <button class="status-filter-pill"
                [class.active]="statusFilter() === 'all'"
                (click)="statusFilter.set('all')">
          All <span class="pill-count">({{ allCount() }})</span>
        </button>

        <button class="status-filter-pill status-ready"
                [class.active]="statusFilter() === 'READY'"
                (click)="statusFilter.set('READY')"
                *ngIf="readyCount() > 0">
          <app-glass-icon name="check" [size]="14"></app-glass-icon>
          Ready <span class="pill-count">({{ readyCount() }})</span>
        </button>

        <button class="status-filter-pill status-queued"
                [class.active]="statusFilter() === 'QUEUED_FOR_CP'"
                (click)="statusFilter.set('QUEUED_FOR_CP')"
                *ngIf="enqueuedCount() > 0">
          <app-glass-icon name="schedule_send" [size]="14"></app-glass-icon>
          Queued <span class="pill-count">({{ enqueuedCount() }})</span>
        </button>

        <button class="status-filter-pill status-processing"
                [class.active]="statusFilter() === 'processing'"
                (click)="statusFilter.set('processing')"
                *ngIf="processingCount() > 0">
          <app-glass-icon name="autorenew" [size]="14"></app-glass-icon>
          Processing <span class="pill-count">({{ processingCount() }})</span>
        </button>

        <button class="status-filter-pill status-completed"
                [class.active]="statusFilter() === 'COMPLETED'"
                (click)="statusFilter.set('COMPLETED')"
                *ngIf="completedCount() > 0">
          <app-glass-icon name="check_circle" [size]="14"></app-glass-icon>
          Completed <span class="pill-count">({{ completedCount() }})</span>
        </button>

        <button class="status-filter-pill status-failed"
                [class.active]="statusFilter() === 'ERROR'"
                (click)="statusFilter.set('ERROR')"
                *ngIf="errorCount() > 0">
          <app-glass-icon name="error" [size]="14"></app-glass-icon>
          Failed <span class="pill-count">({{ errorCount() }})</span>
        </button>
      </div>

      <div class="filters-right">
        <app-glass-input
          class="search-input-compact"
          placeholder="Search..."
          prefixIcon="search"
          [value]="searchText()"
          (valueChange)="searchText.set($event)">
        </app-glass-input>

        <app-glass-button
          variant="secondary"
          size="small"
          [glassTooltip]="'Export to CSV'"
          (clicked)="exportCSV()">
          <app-glass-icon name="download" [size]="16"></app-glass-icon>
        </app-glass-button>
      </div>
    </div>

    <!-- ═══════════════════════════════════════════════════════ -->
    <!-- TABLE: ENHANCED WITH NEW COLUMNS                        -->
    <!-- ═══════════════════════════════════════════════════════ -->
    <div class="file-table-wrapper">
      <div class="table-header">
        <div class="col-status">Status</div>
        <div class="col-filename">Filename</div>
        <div class="col-lot">Lot</div>
        <div class="col-wafer">Wafer</div>
        <div class="col-step">Step</div>
        <div class="col-tester">Tester</div>
        <div class="col-recipe">Recipe</div>
        <div class="col-schema">Schema</div>
        <div class="col-pipeline">Pipeline Status</div>
        <div class="col-updated">Updated</div>
      </div>

      <cdk-virtual-scroll-viewport itemSize="64" class="table-viewport"
                                   (scrolledIndexChange)="onVirtualScrollIndexChange($event)">
        <div class="table-row-wrapper"
             *cdkVirtualFor="let file of filteredFiles()">
          <div class="table-row"
               [class.status-ready]="file.status === 'READY'"
               [class.status-enqueued]="file.status === 'QUEUED_FOR_CP'"
               [class.status-processing]="isProcessingStatus(file.status)"
               [class.status-completed]="file.status === 'COMPLETED'"
               [class.status-error]="isErrorStatus(file.status)"
               [class.recently-updated]="file.isRecentlyUpdated"
               [class.expandable]="!!(file.errorMessage || file.cpOutputPath)"
               (click)="toggleExpand(file)">

            <div class="col-status">
              <div class="status-badge-compact" [class]="'badge-' + file.status.toLowerCase()">
                <app-glass-icon
                  [name]="getStatusIcon(file.status)"
                  [size]="16"
                  [color]="getStatusColor(file.status)">
                </app-glass-icon>
              </div>
            </div>

            <div class="col-filename" [glassTooltip]="file.filename">
              <div class="filename-text">{{ file.filename }}</div>
            </div>

            <div class="col-lot">{{ file.lot || '-' }}</div>
            <div class="col-wafer">{{ file.wafer || '-' }}</div>

            <!-- ➕ NEW: Step -->
            <div class="col-step">
              <span class="step-badge" *ngIf="file.step">{{ file.step }}</span>
              <span *ngIf="!file.step" class="text-muted">-</span>
            </div>

            <!-- ➕ NEW: Tester -->
            <div class="col-tester">
              <span class="tester-badge" *ngIf="file.testerId">{{ file.testerId }}</span>
              <span *ngIf="!file.testerId" class="text-muted">-</span>
            </div>

            <!-- ➕ NEW: Recipe -->
            <div class="col-recipe">
              <span class="recipe-code" *ngIf="file.testProgram" [glassTooltip]="file.testProgram">
                {{ file.testProgram }}
              </span>
              <span *ngIf="!file.testProgram" class="text-muted">-</span>
            </div>

            <!-- ➕ NEW: Schema Bound -->
            <div class="col-schema">
              <span class="schema-badge"
                    [class.badge-production]="file.cpOutputTarget === 'PRODUCTION'"
                    [class.badge-sandbox]="file.cpOutputTarget === 'SANDBOX'"
                    [class.badge-pending]="!file.cpOutputTarget || file.cpOutputTarget === 'UNKNOWN'"
                    [glassTooltip]="getSchemaTooltip(file)">
                {{ file.cpOutputTarget || '-' }}
              </span>
            </div>

            <!-- 🔄 UPDATED: Pipeline Status (was "Message") -->
            <div class="col-pipeline">
              <div class="pipeline-status"
                   [class.status-success]="getDetailLineColor(file) === 'success'"
                   [class.status-error]="getDetailLineColor(file) === 'error'"
                   [class.status-warning]="getDetailLineColor(file) === 'warning'"
                   [class.status-muted]="getDetailLineColor(file) === 'muted'"
                   [glassTooltip]="isErrorTruncated(file) ? getFullErrorMessage(file) : null">
                {{ getDetailLine(file) }}
              </div>
            </div>

            <div class="col-updated">
              <app-dual-timestamp [value]="file.updatedAt"></app-dual-timestamp>
              <app-glass-icon *ngIf="file.cpOutputPath || file.errorMessage"
                [name]="isExpanded(file) ? 'expand_less' : 'expand_more'"
                [size]="14" color="muted">
              </app-glass-icon>
            </div>
          </div>

          <!-- Expanded Details -->
          <div class="row-expanded" *ngIf="isExpanded(file) && (file.errorMessage || file.cpOutputPath)">
            <div class="error-details" *ngIf="file.errorMessage">
              <app-glass-icon name="error" [size]="16" color="error"></app-glass-icon>
              <div class="error-content">
                <span class="error-source-badge" *ngIf="detectErrorSourceForDisplay(file) as src"
                      [class.source-cp]="src === 'CP'"
                      [class.source-exensio]="src === 'Exensio'">{{ src }}</span>
                <span class="error-message">{{ file.errorMessage }}</span>
              </div>
            </div>
            <div class="cp-output-details" *ngIf="file.cpOutputPath">
              <app-glass-icon name="folder" [size]="14" color="muted"></app-glass-icon>
              <span class="cp-output-label">Output Path:</span>
              <span class="cp-output-path">{{ file.cpOutputPath }}</span>
              <span class="cp-target-badge"
                    [class.badge-production]="file.cpOutputTarget === 'PRODUCTION'"
                    [class.badge-sandbox]="file.cpOutputTarget === 'SANDBOX'"
                    [class.badge-unknown]="file.cpOutputTarget === 'UNKNOWN' || !file.cpOutputTarget"
                    [glassTooltip]="getSchemaTooltip(file)">
                {{ file.cpOutputTarget || 'UNKNOWN' }}
              </span>
            </div>
          </div>
        </div>

        <!-- Empty State -->
        <div class="empty-state" *ngIf="filteredFiles().length === 0 && !state().isLoading">
          <app-glass-icon name="search" [size]="48" color="muted"></app-glass-icon>
          <p>{{ state().totalCount === 0 ? 'No files in monitoring' : 'No files match filter' }}</p>
        </div>

        <!-- Loading State -->
        <div class="loading-state" *ngIf="state().isLoading">
          <app-glass-icon name="autorenew" [size]="32" color="primary"></app-glass-icon>
          <p>Loading files...</p>
        </div>
      </cdk-virtual-scroll-viewport>
    </div>

    <!-- Pagination Footer -->
    <div class="pagination-footer">
      <span class="page-info">
        Page {{ state().currentPage + 1 }} of {{ Math.ceil(state().totalCount / state().pageSize) }}
      </span>
      <div class="pagination-controls">
        <app-glass-button
          variant="secondary"
          size="small"
          [disabled]="state().currentPage === 0"
          (clicked)="previousPage()">
          <app-glass-icon name="chevron_left" [size]="16"></app-glass-icon>
          Previous
        </app-glass-button>
        <app-glass-button
          variant="secondary"
          size="small"
          [disabled]="!state().hasMore"
          (clicked)="nextPage()">
          Next
          <app-glass-icon name="chevron_right" [size]="16"></app-glass-icon>
        </app-glass-button>
      </div>
    </div>
  </div>
`,
```

### 2. Enhanced Styles

```scss
// ═══════════════════════════════════════════════════════
// TIER 1: MONITORING METRICS BAR
// ═══════════════════════════════════════════════════════

.monitoring-metrics-bar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 1rem 1.25rem;
  border-bottom: 1px solid rgba(255, 255, 255, 0.05);
  background: rgba(255, 255, 255, 0.02);
  gap: 1rem;
}

.metrics-left {
  display: flex;
  align-items: center;
  gap: 1.5rem;
  flex-wrap: wrap;
}

.metrics-right {
  display: flex;
  align-items: center;
  gap: 0.5rem;
}

.metric-item {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  font-size: 0.875rem;
}

.metric-value {
  font-weight: 700;
  color: var(--text-main);
}

.metric-label {
  color: var(--text-muted);
  font-weight: 500;
}

.stream-status-indicator {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.375rem 0.75rem;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.05);
  border: 1px solid rgba(255, 255, 255, 0.1);
  font-size: 0.75rem;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.05em;
  color: var(--text-muted);
}

.status-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--text-muted);
  animation: pulse-dot 2s ease-in-out infinite;
}

.stream-status-indicator.live {
  background: rgba(16, 185, 129, 0.1);
  border-color: rgba(16, 185, 129, 0.3);
  color: #10b981;
}

.stream-status-indicator.live .status-dot {
  background: #10b981;
}

.stream-status-indicator.polling {
  background: rgba(245, 158, 11, 0.1);
  border-color: rgba(245, 158, 11, 0.3);
  color: #f59e0b;
}

.stream-status-indicator.polling .status-dot {
  background: #f59e0b;
}

.stream-status-indicator.connecting {
  background: rgba(129, 140, 248, 0.1);
  border-color: rgba(129, 140, 248, 0.3);
  color: var(--accent-color);
}

.stream-status-indicator.connecting .status-dot {
  background: var(--accent-color);
  animation: pulse-dot 0.8s ease-in-out infinite;
}

@keyframes pulse-dot {
  0%,
  100% {
    opacity: 1;
  }
  50% {
    opacity: 0.4;
  }
}

// ═══════════════════════════════════════════════════════
// TIER 2: MONITORING FILTERS BAR
// ═══════════════════════════════════════════════════════

.monitoring-filters-bar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 0.875rem 1.25rem;
  border-bottom: 1px solid rgba(255, 255, 255, 0.05);
  gap: 1rem;
  flex-wrap: wrap;
}

.filters-left {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  flex: 1;
  min-width: 0;
}

.filters-right {
  display: flex;
  align-items: center;
  gap: 0.5rem;
}

.status-filter-pill {
  display: flex;
  align-items: center;
  gap: 0.375rem;
  padding: 0.5rem 0.875rem;
  background: rgba(255, 255, 255, 0.05);
  border: 1px solid rgba(255, 255, 255, 0.1);
  border-radius: 10px;
  font-size: 0.8125rem;
  font-weight: 600;
  color: var(--text-muted);
  cursor: pointer;
  transition: all 0.2s ease;
  white-space: nowrap;
}

.status-filter-pill:hover {
  background: rgba(255, 255, 255, 0.08);
  border-color: rgba(255, 255, 255, 0.2);
  transform: translateY(-1px);
}

.status-filter-pill.active {
  background: rgba(129, 140, 248, 0.15);
  border-color: var(--accent-color);
  color: var(--accent-color);
  box-shadow: 0 0 12px rgba(129, 140, 248, 0.3);
}

.pill-count {
  font-size: 0.75rem;
  opacity: 0.8;
}

.search-input-compact {
  width: 220px;
}

// ═══════════════════════════════════════════════════════
// TABLE: ENHANCED GRID WITH NEW COLUMNS
// ═══════════════════════════════════════════════════════

.file-table-wrapper {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-height: 0;
  overflow-x: auto;
  overflow-y: hidden;
}

.table-header {
  display: grid;
  grid-template-columns: 60px 1fr 100px 80px 80px 100px 120px 110px 200px 180px;
  gap: 1rem;
  padding: 0.75rem 1.25rem;
  background: rgba(255, 255, 255, 0.02);
  border-bottom: 1px solid rgba(255, 255, 255, 0.1);
  font-size: 0.75rem;
  font-weight: 600;
  color: var(--text-muted);
  text-transform: uppercase;
  letter-spacing: 0.05em;
  position: sticky;
  top: 0;
  z-index: 10;
  backdrop-filter: blur(10px);
  min-width: 1650px;
}

.table-viewport {
  flex: 1;
  min-height: 0;
  min-width: 1650px;
}

.table-row {
  display: grid;
  grid-template-columns: 60px 1fr 100px 80px 80px 100px 120px 110px 200px 180px;
  gap: 1rem;
  padding: 0.75rem 1.25rem;
  font-size: 0.875rem;
  color: var(--text-main);
  transition: background 0.15s ease;
  align-items: center;
  min-height: 64px;
}

.table-row.expandable {
  cursor: pointer;
}

.table-row:hover {
  background: rgba(255, 255, 255, 0.03);
}

.table-row.status-error {
  background: rgba(239, 68, 68, 0.03);
}

.table-row.recently-updated {
  background: rgba(16, 185, 129, 0.05) !important;
  animation: highlight-update 0.5s ease-out;
}

@keyframes highlight-update {
  0% {
    background: rgba(16, 185, 129, 0.2);
  }
  100% {
    background: rgba(16, 185, 129, 0.05);
  }
}

// Status Badge Compact (icon only)
.status-badge-compact {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  border-radius: 8px;
}

.status-badge-compact.badge-ready {
  background: rgba(148, 163, 184, 0.15);
}

.status-badge-compact.badge-queued_for_cp {
  background: rgba(245, 158, 11, 0.15);
}

.status-badge-compact.badge-elasticsearch_monitoring,
.status-badge-compact.badge-exensio_monitoring {
  background: rgba(129, 140, 248, 0.15);
}

.status-badge-compact.badge-completed {
  background: rgba(16, 185, 129, 0.15);
}

.status-badge-compact.badge-error,
.status-badge-compact.badge-cp_failed,
.status-badge-compact.badge-load_failed {
  background: rgba(239, 68, 68, 0.15);
}

// ═══════════════════════════════════════════════════════
// NEW COLUMN BADGES
// ═══════════════════════════════════════════════════════

// Step Badge (cyan pill)
.step-badge {
  display: inline-flex;
  align-items: center;
  padding: 0.3rem 0.65rem;
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
  padding: 0.3rem 0.65rem;
  border-radius: 6px;
  font-size: 0.7rem;
  font-weight: 700;
  font-family: 'Courier New', monospace;
  text-transform: uppercase;
  letter-spacing: 0.06em;
  background: rgba(168, 85, 247, 0.15);
  color: #a855f7;
  border: 1px solid rgba(168, 85, 247, 0.3);
}

// Recipe Code (monospace)
.recipe-code {
  font-family: 'Courier New', monospace;
  font-size: 0.75rem;
  color: var(--text-main);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  display: inline-block;
  max-width: 120px;
}

// Schema Badge (PRODUCTION/SANDBOX with glow)
.schema-badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 0.35rem 0.75rem;
  border-radius: 8px;
  font-size: 0.7rem;
  font-weight: 700;
  text-transform: uppercase;
  letter-spacing: 0.08em;
  white-space: nowrap;
  min-width: 80px;
}

.schema-badge.badge-production {
  background: rgba(16, 185, 129, 0.15);
  color: #10b981;
  border: 1px solid rgba(16, 185, 129, 0.4);
  box-shadow:
    0 0 12px rgba(16, 185, 129, 0.25),
    inset 0 1px 0 rgba(255, 255, 255, 0.1);
}

.schema-badge.badge-sandbox {
  background: rgba(245, 158, 11, 0.15);
  color: #f59e0b;
  border: 1px solid rgba(245, 158, 11, 0.4);
  box-shadow:
    0 0 12px rgba(245, 158, 11, 0.25),
    inset 0 1px 0 rgba(255, 255, 255, 0.1);
}

.schema-badge.badge-pending {
  background: rgba(148, 163, 184, 0.1);
  color: #94a3b8;
  border: 1px solid rgba(148, 163, 184, 0.2);
}

// Pipeline Status Column (rich detail line)
.col-pipeline {
  overflow: hidden;
}

.pipeline-status {
  font-size: 0.75rem;
  line-height: 1.3;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-weight: 500;
  letter-spacing: 0.02em;
}

.pipeline-status.status-success {
  color: #10b981;
}

.pipeline-status.status-error {
  color: #ef4444;
}

.pipeline-status.status-warning {
  color: #f59e0b;
}

.pipeline-status.status-muted {
  color: var(--text-muted);
}

// ═══════════════════════════════════════════════════════
// EXPANDED ROW DETAILS (Keep existing styles)
// ═══════════════════════════════════════════════════════

.row-expanded {
  padding: 0.75rem 1.25rem;
  background: rgba(255, 255, 255, 0.02);
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.error-details {
  display: flex;
  align-items: flex-start;
  gap: 0.5rem;
  background: rgba(239, 68, 68, 0.05);
  border-radius: 6px;
  padding: 0.5rem 0.75rem;
}

.error-content {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
}

.error-source-badge {
  display: inline-flex;
  align-items: center;
  padding: 0.15rem 0.5rem;
  border-radius: 4px;
  font-size: 0.65rem;
  font-weight: 700;
  text-transform: uppercase;
  letter-spacing: 0.06em;
  white-space: nowrap;
}

.error-source-badge.source-cp {
  background: rgba(245, 158, 11, 0.15);
  color: #f59e0b;
  border: 1px solid rgba(245, 158, 11, 0.3);
}

.error-source-badge.source-exensio {
  background: rgba(129, 140, 248, 0.15);
  color: var(--accent-color);
  border: 1px solid rgba(129, 140, 248, 0.3);
}

.error-message {
  font-size: 0.8125rem;
  color: var(--text-main);
  line-height: 1.4;
}

.cp-output-details {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  flex-wrap: wrap;
  background: rgba(129, 140, 248, 0.05);
  border: 1px solid rgba(129, 140, 248, 0.15);
  border-radius: 6px;
  padding: 0.5rem 0.75rem;
}

.cp-output-label {
  font-size: 0.75rem;
  font-weight: 600;
  color: var(--text-muted);
  text-transform: uppercase;
  letter-spacing: 0.05em;
}

.cp-output-path {
  font-family: 'Courier New', monospace;
  font-size: 0.8rem;
  color: var(--text-main);
  word-break: break-all;
  flex: 1;
}

.cp-target-badge {
  display: inline-flex;
  align-items: center;
  padding: 0.2rem 0.5rem;
  border-radius: 5px;
  font-size: 0.7rem;
  font-weight: 700;
  text-transform: uppercase;
  letter-spacing: 0.06em;
}

.cp-target-badge.badge-production {
  background: rgba(16, 185, 129, 0.15);
  color: #10b981;
  border: 1px solid rgba(16, 185, 129, 0.3);
}

.cp-target-badge.badge-sandbox {
  background: rgba(245, 158, 11, 0.15);
  color: #f59e0b;
  border: 1px solid rgba(245, 158, 11, 0.3);
}

.cp-target-badge.badge-unknown {
  background: rgba(148, 163, 184, 0.1);
  color: #94a3b8;
  border: 1px solid rgba(148, 163, 184, 0.2);
}

// ═══════════════════════════════════════════════════════
// CUSTOM SCROLLBAR
// ═══════════════════════════════════════════════════════

.file-table-wrapper::-webkit-scrollbar {
  height: 10px;
  background: rgba(255, 255, 255, 0.03);
  border-radius: 5px;
}

.file-table-wrapper::-webkit-scrollbar-thumb {
  background: rgba(129, 140, 248, 0.3);
  border-radius: 5px;
  transition: background 0.2s ease;
}

.file-table-wrapper::-webkit-scrollbar-thumb:hover {
  background: rgba(129, 140, 248, 0.5);
}
```

### 3. Component Helper Methods

```typescript
/**
 * Get stream status label for display
 */
streamStatusLabel(): string {
  const status = this.state().streamStatus;
  switch (status) {
    case 'live': return 'Live Updates';
    case 'polling': return 'Polling';
    case 'connecting': return 'Connecting...';
    default: return 'Offline';
  }
}

/**
 * Get elapsed time from monitoring start
 */
elapsedTime = computed(() => {
  // Calculate from monitoring start timestamp
  // Return formatted string like "17m 38s"
  return '17m 38s'; // Placeholder
});

/**
 * Get throughput (files/min)
 */
throughput = computed(() => {
  // Calculate from completed files and elapsed time
  return 0; // Placeholder
});

/**
 * Combined count for "Processing" filter
 */
processingCount = computed(() => {
  const items = this.state().items;
  return items.filter((f: MonitoringFileItem) =>
    f.status === 'ELASTICSEARCH_MONITORING' || f.status === 'EXENSIO_MONITORING'
  ).length;
});

/**
 * Check if status is processing
 */
isProcessingStatus(status: string): boolean {
  return status === 'ELASTICSEARCH_MONITORING' || status === 'EXENSIO_MONITORING';
}

/**
 * Check if status is error
 */
isErrorStatus(status: string): boolean {
  return status === 'ERROR' || status === 'CP_FAILED' || status === 'LOAD_FAILED';
}

/**
 * Get schema bound tooltip
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

/**
 * Refresh monitoring data
 */
refreshMonitoring(): void {
  this.paginationService.loadFirstPage();
}
```

### 4. Update MonitoringFileItem Interface

```typescript
export interface MonitoringFileItem {
  id: number | string;
  filename: string;
  lot: string;
  wafer: string | null;
  status: string;
  message: string;
  updatedAt: string;
  isRecentlyUpdated?: boolean;

  // Enrichment fields
  cpIntegrationStatus?: string | null;
  cpIntegrationMessage?: string | null;
  cpOutputPath?: string | null;
  cpOutputTarget?: string | null;

  // Exensio fields
  exensioIntegrationStatus?: string | null;
  exensioIntegrationMessage?: string | null;

  // Error field
  errorMessage?: string | null;

  // ➕ NEW: Manufacturing context fields
  step?: string | null; // Test step (e.g., "CP1", "PRB1")
  testerId?: string | null; // Tester equipment ID (e.g., "TST-02")
  testProgram?: string | null; // Recipe/program name (e.g., "RECIPE_A")
}
```

---

## Apply Same Changes to Static Monitoring List

The `monitoring-file-list.component.ts` should receive identical enhancements:

- 2-tier control ribbon (without live stream indicator)
- Same new columns and badges
- Same filter organization
- Same horizontal scrolling support

---

## Visual Comparison

### Before (Current):

```
┌───────────────────────────────────────────────────────────────┐
│ 100 files [All] [Staged] [Queued] [Enrichment] [Exensio] ... │
│ [Verify] [Completed] [Failed]     [Search...] [Export CSV]    │  ❌ Cramped
├───────────────────────────────────────────────────────────────┤
│ Status │ Filename │ Lot │ Wafer │ Message │ Updated          │  ❌ No schema
└───────────────────────────────────────────────────────────────┘
```

### After (Redesigned):

```
┌───────────────────────────────────────────────────────────────┐
│ 📊 100 files • 🟢 Live Updates • ⏱ 17m 38s • 📈 0 files/min  │  ✅ Metrics tier
├───────────────────────────────────────────────────────────────┤
│ [All (100)] [Ready] [Queued] [Processing] [Completed] [Failed]│  ✅ Clean filters
│                                        [Search...] [Export CSV]│
├───────────────────────────────────────────────────────────────┤
│ St │ Filename │ Lot │ Wfr │ Step │ Tester │ Recipe │ Schema  │  ✅ Rich context
│    │          │     │     │      │        │        │ Status  │
└───────────────────────────────────────────────────────────────┘
```

---

## Verification Checklist

- [ ] **2-Tier Ribbon**:
  - [ ] Top tier shows file count, stream status, elapsed time, throughput
  - [ ] Bottom tier shows status filters and search
  - [ ] Clean visual separation between tiers
- [ ] **Status Filters**:
  - [ ] "All" shows total count
  - [ ] Individual filters show counts in pills
  - [ ] "Processing" combines Enrichment + Exensio
  - [ ] Active filter has accent glow
  - [ ] Filters hide when count is 0
- [ ] **New Columns**:
  - [ ] Step shows cyan badge
  - [ ] Tester shows purple monospace badge
  - [ ] Recipe shows monospace code with tooltip
  - [ ] Schema shows glowing badge (green PROD, amber SANDBOX)
  - [ ] Pipeline Status replaces generic "Message"
- [ ] **Horizontal Scrolling**:
  - [ ] Table scrolls smoothly
  - [ ] Custom styled scrollbar
  - [ ] Headers stay sticky
  - [ ] Min-width 1650px enforced
- [ ] **Expanded Details**:
  - [ ] Error shows source badge (CP/Exensio)
  - [ ] CP output shows path and schema badge
  - [ ] Click to expand/collapse

---

## Summary

This complete redesign transforms the Step 3 Monitor UI from a cluttered, flat interface into a modern, hierarchical, information-rich monitoring dashboard that matches the quality of Step 1 and Step 2 while adding critical schema bound visibility and manufacturing context.
