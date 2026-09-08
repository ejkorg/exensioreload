import { CommonModule } from '@angular/common';
import { Component, computed, Input, signal } from '@angular/core';
import { GlassTooltipDirective } from '../directives/glass-tooltip.directive';
import { MonitoringFile } from '../services/monitoring.service';
import { GlassButtonComponent } from './glass-button.component';
import { GlassIconComponent } from './glass-icon.component';
import { GlassInputComponent } from './glass-input.component';
import { GlassPaginationComponent, PaginationEvent } from './glass-pagination.component';
import { DualTimestampComponent } from './dual-timestamp.component';

@Component({
  selector: 'app-monitoring-file-list',
  standalone: true,
  imports: [
    CommonModule,
    GlassIconComponent,
    GlassInputComponent,
    GlassButtonComponent,
    GlassTooltipDirective,
    GlassPaginationComponent,
    DualTimestampComponent,
  ],
  template: `
    <div class="file-list-container glass-panel">

      <!-- ═══════════════════════════════════════════════════════ -->
      <!-- TIER 1: MONITORING METRICS                                -->
      <!-- ═══════════════════════════════════════════════════════ -->
      <div class="monitoring-metrics-bar">
        <div class="metrics-left">
          <div class="metric-item">
            <app-glass-icon name="dashboard" [size]="16" color="primary"></app-glass-icon>
            <span class="metric-value">{{ filteredFiles().length }}</span>
            <span class="metric-label">files</span>
          </div>
        </div>
      </div>

      <!-- ═══════════════════════════════════════════════════════ -->
      <!-- TIER 2: FILTERS & SEARCH                                  -->
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

          <app-glass-button variant="secondary" size="small" [glassTooltip]="'Export to CSV'" (clicked)="exportCSV()">
            <app-glass-icon name="download" [size]="16"></app-glass-icon>
          </app-glass-button>
        </div>
      </div>

      <!-- ═══════════════════════════════════════════════════════ -->
      <!-- TABLE: ENHANCED WITH NEW COLUMNS                          -->
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

        <div class="table-body">
          <div
            class="table-row"
            *ngFor="let file of paginatedFiles()"
            [class.status-ready]="file.status === 'READY'"
            [class.status-enqueued]="file.status === 'QUEUED_FOR_CP'"
            [class.status-processing]="isProcessingStatus(file.status)"
            [class.status-completed]="file.status === 'COMPLETED'"
            [class.status-error]="isErrorStatus(file.status)"
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

            <!-- Step -->
            <div class="col-step">
              <span class="step-badge" *ngIf="file.step">{{ file.step }}</span>
              <span *ngIf="!file.step" class="text-muted">-</span>
            </div>

            <!-- Tester -->
            <div class="col-tester">
              <span class="tester-badge" *ngIf="file.testerId">{{ file.testerId }}</span>
              <span *ngIf="!file.testerId" class="text-muted">-</span>
            </div>

            <!-- Recipe -->
            <div class="col-recipe">
              <span class="recipe-code" *ngIf="file.testProgram" [glassTooltip]="file.testProgram">
                {{ file.testProgram }}
              </span>
              <span *ngIf="!file.testProgram" class="text-muted">-</span>
            </div>

            <!-- Schema Bound -->
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

            <!-- Pipeline Status -->
            <div class="col-pipeline">
              <div
                class="pipeline-status"
                [class.status-success]="getDetailLine(file).hasError === false && file.status === 'COMPLETED'"
                [class.status-error]="getDetailLine(file).hasError"
                [class.status-warning]="file.status === 'ELASTICSEARCH_MONITORING' || file.status === 'EXENSIO_MONITORING'"
                [class.status-muted]="file.status === 'READY' || file.status === 'QUEUED_FOR_CP'"
                [glassTooltip]="getErrorSummary(file)?.fullText || ''"
              >
                {{ getDetailLine(file).text }}
              </div>
            </div>

            <!-- Updated -->
            <div class="col-updated">
              <app-dual-timestamp [value]="file.updatedAt"></app-dual-timestamp>
              <app-glass-icon
                *ngIf="file.cpOutputPath || file.errorMessage"
                [name]="isExpanded(file) ? 'expand_less' : 'expand_more'"
                [size]="14"
                color="muted">
              </app-glass-icon>
            </div>

            <!-- Expanded Details -->
            <div class="row-expanded" *ngIf="isExpanded(file) && (file.errorMessage || file.cpOutputPath)">
              <div class="error-details" *ngIf="file.errorMessage">
                <app-glass-icon name="error" [size]="18" color="error"></app-glass-icon>
                <div class="error-content">
                  <span
                    class="error-source-badge"
                    *ngIf="detectErrorSourceForDisplay(file) as src"
                    [class.source-cp]="src === 'CP'"
                    [class.source-exensio]="src === 'Exensio'"
                    >{{ src }}</span
                  >
                  <div class="error-message">{{ file.errorMessage }}</div>
                </div>
              </div>
              <div class="cp-output-details" *ngIf="file.cpOutputPath">
                <div class="cp-output-row">
                  <app-glass-icon name="folder" [size]="16" color="muted"></app-glass-icon>
                  <span class="cp-output-label">Output Path:</span>
                  <span class="cp-output-path">{{ file.cpOutputPath }}</span>
                  <span
                    class="cp-target-badge"
                    [class.badge-production]="file.cpOutputTarget === 'PRODUCTION'"
                    [class.badge-sandbox]="file.cpOutputTarget === 'SANDBOX'"
                    [class.badge-unknown]="file.cpOutputTarget === 'UNKNOWN' || !file.cpOutputTarget"
                    [glassTooltip]="getSchemaTooltip(file)"
                  >
                    {{ file.cpOutputTarget || 'UNKNOWN' }}
                  </span>
                </div>
              </div>
            </div>
          </div>

          <!-- Empty State -->
          <div class="empty-state" *ngIf="filteredFiles().length === 0">
            <app-glass-icon name="search" [size]="48" color="muted"></app-glass-icon>
            <p>No files found</p>
          </div>
        </div>
      </div>

      <app-glass-pagination
        [length]="filteredFiles().length"
        [pageIndex]="pageIndex()"
        [pageSize]="pageSize()"
        [pageSizeOptions]="[10, 25, 50, 100]"
        (page)="onPageChange($event)"
      >
      </app-glass-pagination>
    </div>
  `,
  styles: [
    `
      .file-list-container {
        display: flex;
        flex-direction: column;
        height: 100%;
        min-height: 420px;
        overflow: hidden;
      }

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

      .table-body {
        flex: 1;
        min-height: 0;
        overflow-y: auto;
      }

      .table-row {
        display: grid;
        grid-template-columns: 60px 1fr 100px 80px 80px 100px 120px 110px 200px 180px;
        gap: 1rem;
        padding: 0.75rem 1.25rem;
        border-bottom: 1px solid rgba(255, 255, 255, 0.03);
        font-size: 0.875rem;
        color: var(--text-main);
        cursor: pointer;
        transition: background 0.15s ease;
        align-items: center;
      }

      .table-row:hover {
        background: rgba(255, 255, 255, 0.03);
      }

      .table-row.status-error {
        background: rgba(239, 68, 68, 0.05);
      }

      .table-row.status-completed {
        opacity: 0.7;
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

      .col-filename {
        overflow: hidden;
        display: flex;
        flex-direction: column;
        gap: 0.25rem;
        min-height: 0;
      }

      .filename-text {
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
        font-size: 0.875rem;
        color: var(--text-main);
      }

      .col-lot,
      .col-wafer {
        font-size: 0.875rem;
        color: var(--text-muted);
      }

      .text-muted {
        color: var(--text-muted);
      }

      // ═══════════════════════════════════════════════════════
      // EXPANDED ROW DETAILS
      // ═══════════════════════════════════════════════════════

      .row-expanded {
        grid-column: 1 / -1;
        padding: 1rem;
        background: rgba(255, 255, 255, 0.03);
        border-radius: 8px;
        margin-top: 0.5rem;
        display: flex;
        flex-direction: column;
        gap: 0.75rem;
      }

      .error-details {
        display: flex;
        gap: 0.75rem;
        align-items: flex-start;
        background: rgba(239, 68, 68, 0.05);
        padding: 0.75rem;
        border-radius: 6px;
      }

      .error-content {
        flex: 1;
      }

      .error-message {
        font-size: 0.875rem;
        color: var(--text-main);
        line-height: 1.5;
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
        margin-right: 0.375rem;
        margin-bottom: 0.375rem;
        vertical-align: middle;
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

      .cp-output-details {
        background: rgba(129, 140, 248, 0.05);
        border: 1px solid rgba(129, 140, 248, 0.15);
        border-radius: 6px;
        padding: 0.75rem;
      }

      .cp-output-row {
        display: flex;
        align-items: center;
        gap: 0.5rem;
        flex-wrap: wrap;
      }

      .cp-output-label {
        font-size: 0.75rem;
        font-weight: 600;
        color: var(--text-muted);
        text-transform: uppercase;
        letter-spacing: 0.05em;
        white-space: nowrap;
      }

      .cp-output-path {
        font-family: monospace;
        font-size: 0.8125rem;
        color: var(--text-main);
        word-break: break-all;
        flex: 1;
      }

      .cp-target-badge {
        display: inline-flex;
        align-items: center;
        padding: 0.25rem 0.625rem;
        border-radius: 6px;
        font-size: 0.7rem;
        font-weight: 700;
        text-transform: uppercase;
        letter-spacing: 0.06em;
        white-space: nowrap;
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
      // EMPTY STATE & CUSTOM SCROLLBAR
      // ═══════════════════════════════════════════════════════

      .empty-state {
        display: flex;
        flex-direction: column;
        align-items: center;
        justify-content: center;
        padding: 3rem;
        color: var(--text-muted);
      }

      .empty-state p {
        margin-top: 0.75rem;
        font-size: 0.9375rem;
      }

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
    `,
  ],
})
export class MonitoringFileListComponent {
  @Input() set files(value: MonitoringFile[]) {
    this._files.set(value);
  }

  private _files = signal<MonitoringFile[]>([]);
  searchText = signal('');
  statusFilter = signal<'all' | 'READY' | 'QUEUED_FOR_CP' | 'processing' | 'COMPLETED' | 'ERROR'>('all');
  expandedFiles = signal<Set<string | number>>(new Set());

  pageIndex = signal(0);
  pageSize = signal(10);

  // Computed: Count files by status
  allCount = computed(() => this._files().length);
  readyCount = computed(() => this._files().filter((f) => f.status === 'READY').length);
  enqueuedCount = computed(() => this._files().filter((f) => f.status === 'QUEUED_FOR_CP').length);
  processingCount = computed(() =>
    this._files().filter((f) => f.status === 'ELASTICSEARCH_MONITORING' || f.status === 'EXENSIO_MONITORING').length
  );
  completedCount = computed(() => this._files().filter((f) => f.status === 'COMPLETED').length);
  errorCount = computed(() =>
    this._files().filter((f) => f.status === 'ERROR' || f.status === 'CP_FAILED' || f.status === 'LOAD_FAILED').length
  );

  filteredFiles = computed(() => {
    let files = this._files();
    const search = this.searchText().toLowerCase();
    const status = this.statusFilter();

    if (status !== 'all') {
      if (status === 'READY') {
        files = files.filter((f) => f.status === 'READY');
      } else if (status === 'QUEUED_FOR_CP') {
        files = files.filter((f) => f.status === 'QUEUED_FOR_CP');
      } else if (status === 'processing') {
        files = files.filter((f) => f.status === 'ELASTICSEARCH_MONITORING' || f.status === 'EXENSIO_MONITORING');
      } else if (status === 'COMPLETED') {
        files = files.filter((f) => f.status === 'COMPLETED');
      } else if (status === 'ERROR') {
        files = files.filter((f) => f.status === 'ERROR' || f.status === 'CP_FAILED' || f.status === 'LOAD_FAILED');
      }
    }

    if (search) {
      files = files.filter(
        (f) =>
          f.filename.toLowerCase().includes(search) ||
          (f.lot ?? '').toLowerCase().includes(search) ||
          (f.wafer ?? '').toLowerCase().includes(search) ||
          (f.step ?? '').toLowerCase().includes(search) ||
          (f.testerId ?? '').toLowerCase().includes(search) ||
          (f.testProgram ?? '').toLowerCase().includes(search),
      );
    }

    return files;
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
  getSchemaTooltip(file: MonitoringFile): string {
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

  paginatedFiles = computed(() => {
    const all = this.filteredFiles();
    const start = this.pageIndex() * this.pageSize();
    return all.slice(start, start + this.pageSize());
  });

  onPageChange(event: PaginationEvent) {
    this.pageIndex.set(event.pageIndex);
    this.pageSize.set(event.pageSize);
  }

  getStatusLabel(status: string): string {
    switch (status) {
      case 'READY':
        return 'Staged';
      case 'QUEUED_FOR_CP':
        return 'Queued for Enrichment';
      case 'ELASTICSEARCH_MONITORING':
        return 'Enrichment Processing';
      case 'CP_TIMEOUT':
        return 'Enrichment Monitoring Timeout';
      case 'EXENSIO_MONITORING':
        return 'Exensio Monitoring';
      case 'COMPLETED_MANUAL_VERIFICATION_REQUIRED':
        return 'Completed — Verify in Exensio';
      case 'PROCESSING':
        return 'Enrichment Processing'; // legacy compat
      case 'COMPLETED':
        return 'Completed';
      case 'CP_FAILED':
        return 'Enrichment Failed';
      case 'LOAD_FAILED':
        return 'Load Failed';
      case 'ERROR':
        return 'Failed';
      case 'CANCELLED':
        return 'Cancelled';
      default:
        return status;
    }
  }

  getStatusIcon(status: string): string {
    switch (status) {
      case 'READY':
        return 'check';
      case 'QUEUED_FOR_CP':
        return 'clock';
      case 'ELASTICSEARCH_MONITORING':
        return 'refresh';
      case 'CP_TIMEOUT':
        return 'schedule';
      case 'EXENSIO_MONITORING':
        return 'cloud_upload';
      case 'COMPLETED_MANUAL_VERIFICATION_REQUIRED':
        return 'schedule';
      case 'PROCESSING':
        return 'refresh';
      case 'COMPLETED':
        return 'check_circle';
      case 'CP_FAILED':
      case 'LOAD_FAILED':
      case 'ERROR':
        return 'error';
      case 'CANCELLED':
        return 'cancel';
      default:
        return 'info';
    }
  }

  getStatusColor(status: string): 'default' | 'primary' | 'success' | 'warning' | 'error' | 'muted' {
    switch (status) {
      case 'READY':
        return 'muted';
      case 'QUEUED_FOR_CP':
        return 'warning';
      case 'ELASTICSEARCH_MONITORING':
      case 'EXENSIO_MONITORING':
      case 'PROCESSING':
        return 'primary';
      case 'CP_TIMEOUT':
      case 'COMPLETED_MANUAL_VERIFICATION_REQUIRED':
        return 'warning';
      case 'COMPLETED':
        return 'success';
      case 'CP_FAILED':
      case 'LOAD_FAILED':
      case 'ERROR':
        return 'error';
      case 'CANCELLED':
        return 'muted';
      default:
        return 'default';
    }
  }

  // ========== Detail Line Rendering Methods ==========

  /**
   * Returns the enrichment stage segment for the detail line based on cpIntegrationStatus.
   * Implements Requirements 2.1-2.7.
   */
  getEnrichmentSegment(file: MonitoringFile): string | null {
    const status = file.cpIntegrationStatus;
    if (!status || status === 'not_configured') {
      return null;
    }

    const icon = this.getEnrichmentIcon(status);
    const label = this.getEnrichmentLabel(status);

    if (status === 'success' && file.cpIntegrationMessage) {
      return `${icon} ${label}: ${file.cpIntegrationMessage}`;
    }

    return `${icon} ${label}`;
  }

  /**
   * Returns the enrichment icon based on cpIntegrationStatus.
   */
  private getEnrichmentIcon(status: string): string {
    switch (status) {
      case 'success':
        return 'check_circle';
      case 'pending':
        return 'refresh';
      case 'failure':
        return 'error';
      case 'timeout':
        return 'schedule';
      case 'not_found':
        return 'search';
      case 'error':
        return 'warning';
      default:
        return 'info';
    }
  }

  /**
   * Returns the enrichment label text based on cpIntegrationStatus.
   */
  private getEnrichmentLabel(status: string): string {
    switch (status) {
      case 'success':
        return 'Enrichment: Done';
      case 'pending':
        return 'Enrichment: Processing';
      case 'failure':
        return 'Enrichment: Failed';
      case 'timeout':
        return 'Enrichment: Monitoring Timeout';
      case 'not_found':
        return 'Enrichment: Not Found';
      case 'error':
        return 'Enrichment: Error';
      default:
        return 'Enrichment';
    }
  }

  /**
   * Returns the Exensio stage segment for the detail line based on exensioIntegrationStatus.
   * Implements Requirements 4.1-4.6.
   */
  getExensioSegment(file: MonitoringFile): string | null {
    const status = file.exensioIntegrationStatus;
    if (!status || status === 'not_configured') {
      return null;
    }

    const icon = this.getExensioIcon(status);
    const label = this.getExensioLabel(status);

    return `${icon} ${label}`;
  }

  /**
   * Returns the Exensio icon based on exensioIntegrationStatus.
   */
  private getExensioIcon(status: string): string {
    switch (status) {
      case 'success':
        return 'cloud_done';
      case 'pending':
        return 'cloud_upload';
      case 'failure':
        return 'cloud_off';
      case 'not_found':
        return 'search';
      case 'error':
        return 'warning';
      default:
        return 'cloud';
    }
  }

  /**
   * Returns the Exensio label text based on exensioIntegrationStatus.
   */
  private getExensioLabel(status: string): string {
    switch (status) {
      case 'success':
        return 'Exensio: Loaded';
      case 'pending':
        return 'Exensio: Loading';
      case 'failure':
        return 'Exensio: Failed';
      case 'not_found':
        return 'Exensio: Not Found';
      case 'error':
        return 'Exensio: Error';
      default:
        return 'Exensio';
    }
  }

  /**
   * Returns the CP output target badge text (PRODUCTION/SANDBOX/UNKNOWN).
   * Implements Requirements 3.1-3.4.
   */
  getOutputTargetBadge(file: MonitoringFile): string | null {
    if (!file.cpOutputTarget || file.cpIntegrationStatus !== 'success') {
      return null;
    }

    return file.cpOutputTarget;
  }

  /**
   * Returns a truncated error message with source label (max 140 chars) and tooltip support for full text.
   * Sources: "CP" for Elasticsearch/Enrichment errors, "Exensio" for Exensio API errors.
   * Implements Requirements 5.1-5.5.
   */
  getErrorSummary(file: MonitoringFile): { text: string; fullText: string } | null {
    let errorText = '';
    let source = '';

    if (file.errorMessage) {
      errorText = file.errorMessage;
      source = this.detectErrorSource(file.errorMessage, file);
    } else if (file.cpIntegrationMessage && file.cpIntegrationStatus === 'failure') {
      errorText = file.cpIntegrationMessage;
      source = 'CP';
    } else if (
      file.exensioIntegrationMessage &&
      (file.exensioIntegrationStatus === 'failure' || file.exensioIntegrationStatus === 'error')
    ) {
      errorText = file.exensioIntegrationMessage;
      source = 'Exensio';
    }

    if (!errorText) {
      return null;
    }

    const prefix = source ? `${source} — ` : '';
    const maxLength = 140;
    const available = maxLength - prefix.length;
    const truncated = errorText.length > available ? errorText.substring(0, available) + '...' : errorText;
    const fullText = `${prefix}${errorText}`;
    const text = `${prefix}${truncated}`;

    return { text, fullText };
  }

  /**
   * Detect the error source from the error message content or integration status fields.
   * Returns "CP" for Elasticsearch/enrichment errors, "Exensio" for Exensio API errors, "" if unknown.
   */
  private detectErrorSource(errorMessage: string, file: MonitoringFile): string {
    const msg = errorMessage.toLowerCase();

    if (
      msg.startsWith('[cp ') ||
      msg.includes('cp enrichment') ||
      msg.includes('cp failure') ||
      msg.includes('cp timeout') ||
      msg.includes('cp pp_log')
    ) {
      return 'CP';
    }
    if (
      msg.startsWith('[exensio ') ||
      msg.includes('exensio load') ||
      msg.includes('exensio failure') ||
      msg.includes('exensio api') ||
      msg.includes('dead letter queue')
    ) {
      return 'Exensio';
    }

    if (
      file.cpIntegrationStatus === 'failure' ||
      file.cpIntegrationStatus === 'timeout' ||
      file.cpIntegrationStatus === 'error'
    ) {
      return 'CP';
    }
    if (file.exensioIntegrationStatus === 'failure' || file.exensioIntegrationStatus === 'error') {
      return 'Exensio';
    }

    return '';
  }

  /**
   * Combines all segments into the final detail line string for a file.
   * Implements Requirements 1.1, 1.2, 2.1-2.7, 3.1-3.4, 4.1-4.6, 5.1-5.5, 7.1-7.5.
   */
  getDetailLine(file: MonitoringFile): { text: string; hasError: boolean } {
    const segments: string[] = [];

    // Handle queued status
    if (file.status === 'READY' || file.status === 'QUEUED_FOR_CP') {
      return { text: 'Queued', hasError: false };
    }

    // Handle enrichment status
    if (file.status === 'ELASTICSEARCH_MONITORING') {
      const enrichment = this.getEnrichmentSegment(file);
      if (enrichment) {
        segments.push(enrichment);
      } else {
        segments.push('Enrichment: In Progress');
      }
      return { text: segments.join(' · '), hasError: false };
    }

    // Handle Exensio loading status
    if (file.status === 'EXENSIO_MONITORING') {
      const enrichment = this.getEnrichmentSegment(file);
      const exensio = this.getExensioSegment(file);

      if (enrichment && exensio) {
        segments.push(enrichment, exensio);
      } else if (enrichment) {
        segments.push(enrichment, 'Exensio: Loading');
      } else if (exensio) {
        segments.push('Enrichment: Done', exensio);
      } else {
        segments.push('Enrichment: Done · Exensio: Loading');
      }
      return { text: segments.join(' · '), hasError: false };
    }

    // Handle COMPLETED status
    if (file.status === 'COMPLETED') {
      const enrichment = this.getEnrichmentSegment(file);
      const outputTarget = this.getOutputTargetBadge(file);
      const exensio = this.getExensioSegment(file);

      if (enrichment) {
        segments.push(enrichment);
      }
      if (outputTarget) {
        segments.push(`[${outputTarget}]`);
      }
      if (exensio) {
        segments.push(exensio);
      }

      return { text: segments.join(' · '), hasError: false };
    }

    // Handle ERROR status
    if (file.status === 'ERROR') {
      const errorSummary = this.getErrorSummary(file);
      if (errorSummary) {
        return { text: errorSummary.text, hasError: true };
      }
    }

    // Fallback for other states
    return { text: 'Queued', hasError: false };
  }

  toggleExpand(file: MonitoringFile) {
    if (!file.errorMessage && !file.cpOutputPath) return;

    const expanded = new Set(this.expandedFiles());
    const id = file.id || file.metadataId || '';

    if (expanded.has(id)) {
      expanded.delete(id);
    } else {
      expanded.add(id);
    }

    this.expandedFiles.set(expanded);
  }

  isExpanded(file: MonitoringFile): boolean {
    const id = file.id || file.metadataId || '';
    return this.expandedFiles().has(id);
  }

  exportCSV() {
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
      this.getDetailLine(f).text,
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

  /**
   * Detect error source for display in the expanded row badge.
   * Returns "CP", "Exensio", or "" if unknown.
   */
  detectErrorSourceForDisplay(file: MonitoringFile): string {
    if (!file.errorMessage) return '';
    return this.detectErrorSource(file.errorMessage, file);
  }
}
