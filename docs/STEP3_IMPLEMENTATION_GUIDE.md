# Step 3 Monitor UI: Implementation Guide

This document provides step-by-step implementation instructions for the complete Step 3 Monitor UI redesign.

---

## Phase 1: Update MonitoringFileItem Interface

### File: `frontend/src/app/shared/services/monitoring-pagination.service.ts`

Add new fields to the `MonitoringFileItem` interface:

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

## Phase 2: Add Helper Methods to Realtime Component

### File: `frontend/src/app/shared/components/realtime-monitoring-file-list.component.ts`

Add these methods to the component class (after the existing methods):

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
  // TODO: Calculate from monitoring start timestamp
  // For now, return empty to hide the metric
  return '';
});

/**
 * Get throughput (files/min)
 */
throughput = computed(() => {
  // TODO: Calculate from completed files and elapsed time
  // For now, return 0 to hide the metric
  return 0;
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

/**
 * Export filtered files to CSV
 */
exportCSV(): void {
  const files = this.filteredFiles();
  const headers = [
    'Status',
    'Filename',
    'Lot',
    'Wafer',
    'Step',
    'Tester',
    'Recipe',
    'Schema',
    'Pipeline Status',
    'Error'
  ];

  const rows = files.map((f) => [
    f.status,
    f.filename,
    f.lot,
    f.wafer || '',
    f.step || '',
    f.testerId || '',
    f.testProgram || '',
    f.cpOutputTarget || '',
    this.getDetailLine(f),
    f.errorMessage || ''
  ]);

  const csv = [headers.join(','), ...rows.map((row) => row.map((cell) => `"${cell}"`).join(','))].join('\n');

  const blob = new Blob([csv], { type: 'text/csv' });
  const url = window.URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = `monitoring-files-${new Date().toISOString()}.csv`;
  a.click();
  window.URL.revokeObjectURL(url);
}
```

---

## Phase 3: Update Filtered Files Logic

Update the `filteredFiles` computed to support the new "processing" filter:

```typescript
filteredFiles = computed(() => {
  let files = this.state().items;
  const status = this.statusFilter();
  const search = this.searchText().toLowerCase();

  if (status !== 'all') {
    if (status === 'READY') {
      files = files.filter((f: MonitoringFileItem) => f.status === 'READY');
    } else if (status === 'QUEUED_FOR_CP') {
      files = files.filter((f: MonitoringFileItem) => f.status === 'QUEUED_FOR_CP');
    } else if (status === 'processing') {
      // NEW: Combined processing filter
      files = files.filter(
        (f: MonitoringFileItem) => f.status === 'ELASTICSEARCH_MONITORING' || f.status === 'EXENSIO_MONITORING',
      );
    } else if (status === 'COMPLETED') {
      files = files.filter((f: MonitoringFileItem) => f.status === 'COMPLETED');
    } else if (status === 'ERROR') {
      files = files.filter(
        (f: MonitoringFileItem) => f.status === 'ERROR' || f.status === 'CP_FAILED' || f.status === 'LOAD_FAILED',
      );
    } else {
      files = files.filter((f: MonitoringFileItem) => f.status === status);
    }
  }

  if (search) {
    files = files.filter(
      (f: MonitoringFileItem) =>
        f.filename.toLowerCase().includes(search) ||
        f.lot.toLowerCase().includes(search) ||
        f.wafer?.toLowerCase().includes(search) ||
        f.step?.toLowerCase().includes(search) ||
        f.testerId?.toLowerCase().includes(search) ||
        f.testProgram?.toLowerCase().includes(search),
    );
  }

  return files;
});
```

---

## Phase 4: Update Status Filter Signal Type

Update the `statusFilter` signal to accept the new "processing" type:

```typescript
statusFilter = signal<'all' | 'READY' | 'QUEUED_FOR_CP' | 'processing' | 'COMPLETED' | 'ERROR'>('all');
```

---

## Phase 5: Update Table Template

The table template has already been updated with the new 2-tier design. Now we need to update the table row rendering to include the new columns.

Find the table row section (around line 160-200) and ensure it includes these columns:

```html
<div
  class="table-row"
  [class.status-ready]="file.status === 'READY'"
  [class.status-enqueued]="file.status === 'QUEUED_FOR_CP'"
  [class.status-processing]="isProcessingStatus(file.status)"
  [class.status-completed]="file.status === 'COMPLETED'"
  [class.status-error]="isErrorStatus(file.status)"
  [class.recently-updated]="file.isRecentlyUpdated"
  [class.expandable]="!!(file.errorMessage || file.cpOutputPath)"
  (click)="toggleExpand(file)"
>
  <!-- Status Badge (Icon Only) -->
  <div class="col-status">
    <div class="status-badge-compact" [class]="'badge-' + file.status.toLowerCase()">
      <app-glass-icon [name]="getStatusIcon(file.status)" [size]="16" [color]="getStatusColor(file.status)">
      </app-glass-icon>
    </div>
  </div>

  <!-- Filename -->
  <div class="col-filename" [glassTooltip]="file.filename">
    <div class="filename-text">{{ file.filename }}</div>
  </div>

  <!-- Lot -->
  <div class="col-lot">{{ file.lot || '-' }}</div>

  <!-- Wafer -->
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
    <span class="recipe-code" *ngIf="file.testProgram" [glassTooltip]="file.testProgram"> {{ file.testProgram }} </span>
    <span *ngIf="!file.testProgram" class="text-muted">-</span>
  </div>

  <!-- ➕ NEW: Schema Bound -->
  <div class="col-schema">
    <span
      class="schema-badge"
      [class.badge-production]="file.cpOutputTarget === 'PRODUCTION'"
      [class.badge-sandbox]="file.cpOutputTarget === 'SANDBOX'"
      [class.badge-pending]="!file.cpOutputTarget || file.cpOutputTarget === 'UNKNOWN'"
      [glassTooltip]="getSchemaTooltip(file)"
    >
      {{ file.cpOutputTarget || '-' }}
    </span>
  </div>

  <!-- 🔄 UPDATED: Pipeline Status (was file-detail-line under filename) -->
  <div class="col-pipeline">
    <div
      class="pipeline-status"
      [class.status-success]="getDetailLineColor(file) === 'success'"
      [class.status-error]="getDetailLineColor(file) === 'error'"
      [class.status-warning]="getDetailLineColor(file) === 'warning'"
      [class.status-muted]="getDetailLineColor(file) === 'muted'"
      [glassTooltip]="isErrorTruncated(file) ? getFullErrorMessage(file) : null"
    >
      {{ getDetailLine(file) }}
    </div>
  </div>

  <!-- Updated -->
  <div class="col-updated">
    <app-dual-timestamp [value]="file.updatedAt"></app-dual-timestamp>
    <app-glass-icon
      *ngIf="file.cpOutputPath || file.errorMessage"
      [name]="isExpanded(file) ? 'expand_less' : 'expand_more'"
      [size]="14"
      color="muted"
    >
    </app-glass-icon>
  </div>
</div>
```

---

## Phase 6: Update Styles

Replace the existing styles section with the complete styles from the redesign document (STEP3_MONITOR_UI_COMPLETE_REDESIGN.md, section 2).

Key new styles to add:

### 1. Monitoring Metrics Bar

```scss
.monitoring-metrics-bar {
}
.metrics-left {
}
.metrics-right {
}
.metric-item {
}
.metric-value {
}
.metric-label {
}
.stream-status-indicator {
}
.status-dot {
}
// ... (full styles in redesign doc)
```

### 2. Monitoring Filters Bar

```scss
.monitoring-filters-bar {
}
.filters-left {
}
.filters-right {
}
.status-filter-pill {
}
.pill-count {
}
.search-input-compact {
}
// ... (full styles in redesign doc)
```

### 3. Updated Table Grid

```scss
.table-header {
  grid-template-columns: 60px 1fr 100px 80px 80px 100px 120px 110px 200px 180px;
  min-width: 1650px;
}

.table-row {
  grid-template-columns: 60px 1fr 100px 80px 80px 100px 120px 110px 200px 180px;
}
```

### 4. New Badge Styles

```scss
.status-badge-compact {
}
.step-badge {
}
.tester-badge {
}
.recipe-code {
}
.schema-badge {
}
.pipeline-status {
}
// ... (full styles in redesign doc)
```

---

## Phase 7: Apply Same Changes to Static Monitoring Component

### File: `frontend/src/app/shared/components/monitoring-file-list.component.ts`

Apply the same changes as above, but:

- Remove stream status indicator (no live updates)
- Keep rest of metrics bar (file count, elapsed time if applicable)
- Use identical filter bar and table structure
- Use identical badge styles

---

## Phase 8: Backend Integration (Optional)

If `step`, `testerId`, and `testProgram` are not currently in the database:

### 1. Update Entity

```java
// MonitoredFileEntity.java
@Column(name = "step")
private String step;

@Column(name = "tester_id")
private String testerId;

@Column(name = "test_program")
private String testProgram;
```

### 2. Update DTO

```java
// MonitoredFileDto.java
private String step;
private String testerId;
private String testProgram;
```

### 3. Populate During Staging

```java
// When staging files
monitoredFile.setStep(discoveryRow.getStep());
monitoredFile.setTesterId(discoveryRow.getTesterId());
monitoredFile.setTestProgram(discoveryRow.getTestProgram());
```

---

## Testing Checklist

- [ ] **Tier 1 Metrics Bar**:
  - [ ] Shows file count
  - [ ] Stream status indicator works (live/polling/connecting)
  - [ ] Elapsed time displays when available
  - [ ] Throughput displays when > 0
  - [ ] Refresh button works
- [ ] **Tier 2 Filters Bar**:
  - [ ] All filter shows total count
  - [ ] Individual filters show counts and hide when 0
  - [ ] "Processing" combines Enrichment + Exensio
  - [ ] Active filter has accent glow
  - [ ] Search works across all fields
  - [ ] Export CSV includes new columns
- [ ] **New Table Columns**:
  - [ ] Step shows cyan badge
  - [ ] Tester shows purple monospace badge
  - [ ] Recipe shows monospace code with tooltip
  - [ ] Schema shows glowing PROD/SANDBOX badge
  - [ ] Pipeline Status shows colored detail line
  - [ ] All show "-" when data is null
- [ ] **Table Behavior**:
  - [ ] Horizontal scrolling works
  - [ ] Custom scrollbar appears
  - [ ] Headers stay sticky
  - [ ] Row hover effects work
  - [ ] Recently updated rows highlight
- [ ] **Expanded Details**:
  - [ ] Click to expand/collapse
  - [ ] Error shows source badge (CP/Exensio)
  - [ ] CP output shows path and schema badge
  - [ ] Tooltips work on schema badges

---

## File Summary

### Files to Modify:

1. ✅ `monitoring-pagination.service.ts` - Add fields to interface
2. ✅ `realtime-monitoring-file-list.component.ts` - Complete redesign
3. ✅ `monitoring-file-list.component.ts` - Apply same changes
4. ⚠️ Backend files (optional) - Add database fields

### Files Already Modified:

- ✅ `monitoring-stats.component.ts` - Integrations card enhancement (completed separately)

---

## Notes

- The template changes are already partially applied
- Helper methods need to be added to component class
- Styles need complete replacement
- Test thoroughly with real data
- Monitor performance with large file counts (virtual scrolling should handle it)
