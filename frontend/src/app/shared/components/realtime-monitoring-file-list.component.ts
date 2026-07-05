import { ScrollingModule } from '@angular/cdk/scrolling';
import { CommonModule } from '@angular/common';
import { Component, OnDestroy, OnInit, computed, inject, signal } from '@angular/core';
import { GlassTooltipDirective } from '../directives/glass-tooltip.directive';
import { MonitoringFileItem, MonitoringPaginationService } from '../services/monitoring-pagination.service';
import { GlassButtonComponent } from './glass-button.component';
import { GlassIconComponent } from './glass-icon.component';
import { GlassInputComponent } from './glass-input.component';

@Component({
  selector: 'app-realtime-monitoring-file-list',
  standalone: true,
  imports: [
    CommonModule,
    ScrollingModule,
    GlassIconComponent,
    GlassInputComponent,
    GlassButtonComponent,
    GlassTooltipDirective,
  ],
  template: `
    <div class="realtime-file-list glass-panel">
      <!-- Header -->
      <div class="file-list-header">
        <div class="header-left">
          <h3>Staged Files <span class="file-count">({{ state().totalCount | number }})</span></h3>
          <div class="stream-indicator" [class.live]="state().streamStatus === 'live'"
                                        [class.polling]="state().streamStatus === 'polling'"
                                        [class.connecting]="state().streamStatus === 'connecting'"
                                        [glassTooltip]="streamStatusTooltip()">
            <div class="indicator-dot"></div>
            <span class="indicator-text">{{ state().streamStatus === 'live' ? 'Live Updates' : state().streamStatus === 'polling' ? 'Polling Updates' : 'Connecting...' }}</span>
          </div>
        </div>
        <div class="header-right">
          <app-glass-input
            placeholder="Search files..."
            prefixIcon="search"
            [value]="searchText()"
            (valueChange)="searchText.set($event)">
          </app-glass-input>
        </div>
      </div>

      <!-- Status Filters -->
      <div class="status-filters-bar">
        <button class="filter-chip"
                [class.active]="statusFilter() === 'all'"
                (click)="statusFilter.set('all')">
          All ({{ allCount() }})
        </button>
        <button class="filter-chip"
                [class.active]="statusFilter() === 'READY'"
                (click)="statusFilter.set('READY')">
          Ready
        </button>
        <button class="filter-chip"
                [class.active]="statusFilter() === 'ENQUEUED'"
                (click)="statusFilter.set('ENQUEUED')">
          In Queue
        </button>
        <button class="filter-chip"
                [class.active]="statusFilter() === 'ENRICHMENT'"
                (click)="statusFilter.set('ENRICHMENT')">
          Enrichment
        </button>
        <button class="filter-chip"
                [class.active]="statusFilter() === 'EXENSIO_LOADING'"
                (click)="statusFilter.set('EXENSIO_LOADING')">
          Exensio Monitoring
        </button>
        </button>
        <button class="filter-chip"
                [class.active]="statusFilter() === 'COMPLETED'"
                (click)="statusFilter.set('COMPLETED')">
          Completed
        </button>
        <button class="filter-chip"
                [class.active]="statusFilter() === 'ERROR'"
                (click)="statusFilter.set('ERROR')">
          Failed
        </button>
      </div>

      <!-- File Table -->
      <div class="file-table-wrapper">
        <div class="table-header">
          <div class="col-status">Status</div>
          <div class="col-filename">Filename</div>
          <div class="col-lot">Lot</div>
          <div class="col-wafer">Wafer</div>
          <div class="col-updated">Updated</div>
        </div>

        <cdk-virtual-scroll-viewport itemSize="64" class="table-viewport"
                                     (scrolledIndexChange)="onVirtualScrollIndexChange($event)">
          <div class="table-row-wrapper"
               *cdkVirtualFor="let file of filteredFiles()">
            <div class="table-row"
               [class.status-ready]="file.status === 'READY'"
               [class.status-enqueued]="file.status === 'ENQUEUED'"
               [class.status-processing]="file.status === 'ENRICHMENT' || file.status === 'EXENSIO_LOADING'"
               [class.status-completed]="file.status === 'COMPLETED'"
               [class.status-error]="file.status === 'ERROR'"
               [class.recently-updated]="file.isRecentlyUpdated"
               [class.expandable]="!!(file.errorMessage || file.cpOutputPath)"
               (click)="toggleExpand(file)">

              <div class="col-status">
                <div class="status-badge" [class]="'status-' + file.status.toLowerCase()">
                  <app-glass-icon
                    [name]="getStatusIcon(file.status)"
                    [size]="14"
                    [color]="getStatusColor(file.status)">
                  </app-glass-icon>
                  <span class="status-text">{{ getStatusLabel(file.status) }}</span>
                </div>
                <div class="recently-updated-badge" *ngIf="file.isRecentlyUpdated">
                  <app-glass-icon name="rate_increase" [size]="12" color="success"></app-glass-icon>
                </div>
              </div>

              <div class="col-filename" [glassTooltip]="file.filename">
                <div class="filename-main">{{ file.filename }}</div>
                <div class="file-detail-line"
                     [class.detail-error]="getDetailLineColor(file) === 'error'"
                     [class.detail-success]="getDetailLineColor(file) === 'success'"
                     [class.detail-warning]="getDetailLineColor(file) === 'warning'"
                     [class.detail-muted]="getDetailLineColor(file) === 'muted'"
                     [glassTooltip]="isErrorTruncated(file) ? getFullErrorMessage(file) : null">
                  {{ getDetailLine(file) }}
                </div>
              </div>
              <div class="col-lot">{{ file.lot || '-' }}</div>
              <div class="col-wafer">{{ file.wafer || '-' }}</div>
              <div class="col-updated">
                <span class="time-ago">{{ getTimeAgo(file.updatedAt) }}</span>
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
                      [class.badge-unknown]="file.cpOutputTarget === 'UNKNOWN' || !file.cpOutputTarget">
                  {{ file.cpOutputTarget || 'UNKNOWN' }}
                </span>
              </div>
            </div>
          </div>

          <!-- Empty State -->
          <div class="empty-state" *ngIf="filteredFiles().length === 0 && !state().isLoading">
            <app-glass-icon name="search" [size]="48" color="muted"></app-glass-icon>
            <p>{{ state().totalCount === 0 ? 'No files staged yet' : 'No files match filter' }}</p>
          </div>

          <!-- Loading State -->
          <div class="loading-state" *ngIf="state().isLoading">
            <app-glass-icon name="autorenew" [size]="32" color="primary"></app-glass-icon>
            <p>Loading files...</p>
          </div>
        </cdk-virtual-scroll-viewport>
      </div>

      <!-- Pagination -->
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
  styles: [
    `
      .realtime-file-list {
        display: flex;
        flex-direction: column;
        height: min(700px, calc(100vh - 360px));
        min-height: 460px;
        overflow: hidden;
      }

      .file-list-header {
        display: flex;
        justify-content: space-between;
        align-items: center;
        padding: 1.25rem;
        border-bottom: 1px solid rgba(255, 255, 255, 0.05);
        gap: 1rem;
      }

      .header-left {
        display: flex;
        align-items: center;
        gap: 1.5rem;
      }

      .header-left h3 {
        margin: 0;
        font-size: 1.0625rem;
        font-weight: 700;
        color: var(--text-main);
      }

      .file-count {
        font-size: 0.875rem;
        font-weight: 500;
        color: var(--text-muted);
      }

      .stream-indicator {
        display: flex;
        align-items: center;
        gap: 0.5rem;
        padding: 0.375rem 0.75rem;
        border-radius: 6px;
        background: rgba(255, 255, 255, 0.03);
        border: 1px solid rgba(255, 255, 255, 0.08);
        font-size: 0.75rem;
        font-weight: 600;
        text-transform: uppercase;
        letter-spacing: 0.05em;
        color: var(--text-muted);
      }

      .indicator-dot {
        width: 6px;
        height: 6px;
        border-radius: 50%;
        background: var(--text-muted);
        animation: pulse 0.5s linear;
      }

      .stream-indicator.live {
        background: rgba(16, 185, 129, 0.1);
        border-color: rgba(16, 185, 129, 0.3);
        color: #10b981;
      }

      .stream-indicator.live .indicator-dot {
        background: #10b981;
      }

      .stream-indicator.polling {
        background: rgba(245, 158, 11, 0.1);
        border-color: rgba(245, 158, 11, 0.3);
        color: #f59e0b;
      }

      .stream-indicator.polling .indicator-dot {
        background: #f59e0b;
      }

      .stream-indicator.connecting {
        background: rgba(129, 140, 248, 0.1);
        border-color: rgba(129, 140, 248, 0.3);
        color: var(--accent-color);
      }

      .stream-indicator.connecting .indicator-dot {
        background: var(--accent-color);
      }

      @keyframes pulse {
        0%,
        100% {
          opacity: 1;
        }
        50% {
          opacity: 0.4;
        }
      }

      .header-right {
        min-width: 250px;
      }

      .status-filters-bar {
        display: flex;
        gap: 0.5rem;
        padding: 0.75rem 1.25rem;
        border-bottom: 1px solid rgba(255, 255, 255, 0.05);
        overflow-x: auto;
        flex-wrap: wrap;
      }

      .filter-chip {
        display: flex;
        align-items: center;
        gap: 0.375rem;
        padding: 0.375rem 0.75rem;
        background: rgba(255, 255, 255, 0.05);
        border: 1px solid rgba(255, 255, 255, 0.1);
        border-radius: 8px;
        font-size: 0.75rem;
        font-weight: 600;
        color: var(--text-muted);
        cursor: pointer;
        transition: all 0.2s ease;
        white-space: nowrap;
      }

      .filter-chip:hover {
        background: rgba(255, 255, 255, 0.08);
        border-color: rgba(255, 255, 255, 0.2);
      }

      .filter-chip.active {
        background: rgba(129, 140, 248, 0.15);
        border-color: var(--accent-color);
        color: var(--accent-color);
      }

      .file-table-wrapper {
        flex: 1;
        display: flex;
        flex-direction: column;
        min-height: 0;
      }

      .table-header {
        display: grid;
        grid-template-columns: 120px 1fr 100px 80px 120px;
        gap: 1rem;
        padding: 0.75rem 1.25rem;
        background: rgba(255, 255, 255, 0.02);
        border-bottom: 1px solid rgba(255, 255, 255, 0.1);
        font-size: 0.75rem;
        font-weight: 600;
        color: var(--text-muted);
        text-transform: uppercase;
        letter-spacing: 0.05em;
        sticky: top;
        z-index: 10;
      }

      .table-viewport {
        flex: 1;
        min-height: 0;
      }

      .table-row-wrapper {
        border-bottom: 1px solid rgba(255, 255, 255, 0.03);
      }

      .table-row {
        display: grid;
        grid-template-columns: 120px 1fr 100px 80px 120px;
        gap: 1rem;
        padding: 0.5rem 1.25rem;
        font-size: 0.875rem;
        color: var(--text-main);
        transition: background 0.15s ease;
        align-items: center;
        min-height: 60px;
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
      }

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

      .error-label {
        font-size: 0.75rem;
        font-weight: 600;
        color: #ef4444;
        text-transform: uppercase;
        letter-spacing: 0.05em;
        white-space: nowrap;
      }

      .error-message {
        font-size: 0.8125rem;
        color: var(--text-main);
        line-height: 1.4;
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
        white-space: nowrap;
      }

      .cp-output-path {
        font-family: monospace;
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

      .col-status {
        display: flex;
        align-items: center;
        gap: 0.5rem;
      }

      .status-badge {
        display: flex;
        align-items: center;
        gap: 0.375rem;
        padding: 0.25rem 0.5rem;
        border-radius: 6px;
        font-size: 0.7rem;
        font-weight: 600;
        text-transform: uppercase;
        letter-spacing: 0.05em;
      }

      .status-badge.status-ready {
        background: rgba(148, 163, 184, 0.15);
        color: #94a3b8;
      }

      .status-badge.status-enqueued {
        background: rgba(245, 158, 11, 0.15);
        color: #f59e0b;
      }

      .status-badge.status-processing {
        background: rgba(129, 140, 248, 0.15);
        color: var(--accent-color);
      }

      .status-badge.status-completed {
        background: rgba(16, 185, 129, 0.15);
        color: #10b981;
      }

      .status-badge.status-error {
        background: rgba(239, 68, 68, 0.15);
        color: #ef4444;
      }

      .status-text {
        display: none;
      }

      .recently-updated-badge {
        display: flex;
        align-items: center;
        justify-content: center;
        width: 20px;
        height: 20px;
        border-radius: 50%;
        background: rgba(16, 185, 129, 0.2);
        animation: pulse 0.6s ease-in-out;
      }

      .col-filename {
        overflow: hidden;
        display: flex;
        flex-direction: column;
        gap: 0.25rem;
        min-height: 0;
      }

      .filename-main {
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
        font-size: 0.875rem;
        color: var(--text-main);
      }

      .file-detail-line {
        font-size: 0.75rem;
        line-height: 1.2;
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
        font-weight: 500;
        letter-spacing: 0.02em;
      }

      .file-detail-line.detail-success {
        color: #10b981;
      }

      .file-detail-line.detail-error {
        color: #ef4444;
      }

      .file-detail-line.detail-warning {
        color: #f59e0b;
      }

      .file-detail-line.detail-muted {
        color: var(--text-muted);
      }

      .col-lot,
      .col-wafer,
      .col-updated {
        font-size: 0.875rem;
        color: var(--text-muted);
      }

      .time-ago {
        display: inline-block;
        font-size: 0.75rem;
        color: var(--text-muted);
      }

      .empty-state,
      .loading-state {
        display: flex;
        flex-direction: column;
        align-items: center;
        justify-content: center;
        gap: 0.75rem;
        height: 100%;
        color: var(--text-muted);
        padding: 2rem;
        text-align: center;
      }

      .empty-state p,
      .loading-state p {
        margin: 0;
        font-size: 0.875rem;
      }

      .loading-state app-glass-icon {
        animation: spin 1.5s linear infinite;
      }

      @keyframes spin {
        from {
          transform: rotate(0deg);
        }
        to {
          transform: rotate(360deg);
        }
      }

      .pagination-footer {
        display: flex;
        justify-content: space-between;
        align-items: center;
        padding: 1rem 1.25rem;
        border-top: 1px solid rgba(255, 255, 255, 0.05);
        background: rgba(255, 255, 255, 0.01);
      }

      .page-info {
        font-size: 0.875rem;
        color: var(--text-muted);
        font-weight: 500;
      }

      .pagination-controls {
        display: flex;
        gap: 0.5rem;
      }
    `,
  ],
})
export class RealtimeMonitoringFileListComponent implements OnInit, OnDestroy {
  protected paginationService = inject(MonitoringPaginationService);

  // Signals for UI
  statusFilter = signal<'all' | 'READY' | 'ENQUEUED' | 'ENRICHMENT' | 'EXENSIO_LOADING' | 'COMPLETED' | 'ERROR'>('all');
  searchText = signal('');
  expandedFiles = signal<Set<number | string>>(new Set());

  // Expose service state for template
  state = this.paginationService.paginationState;

  // Computed: Count files by status
  allCount = this.countByStatus('all');
  readyCount = this.countByStatus('READY');
  enqueuedCount = this.countByStatus('ENQUEUED');
  enrichmentCount = this.countByStatus('ENRICHMENT');
  exensioLoadingCount = this.countByStatus('EXENSIO_LOADING');
  processingCount = this.countByStatus('ENRICHMENT'); // legacy alias
  completedCount = this.countByStatus('COMPLETED');
  errorCount = this.countByStatus('ERROR');

  // Computed: Filter files by status and search
  filteredFiles = computed(() => {
    let files = this.state().items;
    const status = this.statusFilter();
    const search = this.searchText().toLowerCase();

    if (status !== 'all') {
      files = files.filter((f: MonitoringFileItem) => f.status === status);
    }

    if (search) {
      files = files.filter(
        (f: MonitoringFileItem) =>
          f.filename.toLowerCase().includes(search) ||
          f.lot.toLowerCase().includes(search) ||
          f.wafer?.toLowerCase().includes(search),
      );
    }

    return files;
  });

  Math = Math;

  ngOnInit(): void {
    // Load first page on init
    this.paginationService.loadFirstPage();
  }

  ngOnDestroy(): void {
    // Cleanup handled by service
  }

  // Helper: Count files by status
  private countByStatus(
    status: 'all' | 'READY' | 'ENQUEUED' | 'ENRICHMENT' | 'EXENSIO_LOADING' | 'COMPLETED' | 'ERROR',
  ) {
    return computed(() => {
      const items = this.state().items;
      if (status === 'all') {
        return items.length;
      }
      return items.filter((f: MonitoringFileItem) => f.status === status).length;
    });
  }

  async previousPage(): Promise<void> {
    const currentPage = this.state().currentPage;
    if (currentPage > 0) {
      await this.paginationService.loadPage(currentPage - 1);
    }
  }

  async nextPage(): Promise<void> {
    if (this.state().hasMore) {
      await this.paginationService.loadNextPage();
    }
  }

  onVirtualScrollIndexChange(index: number): void {
    // Trigger next page load when user scrolls to ~80% of current page
    const currentItems = this.state().items;
    const threshold = currentItems.length * 0.8;

    if (index > threshold && this.state().hasMore && !this.state().isLoading) {
      this.paginationService.loadNextPage();
    }
  }

  getStatusLabel(status: string): string {
    switch (status) {
      case 'READY':
        return 'Staged';
      case 'ENQUEUED':
        return 'Queued for Enrichment';
      case 'ENRICHMENT':
        return 'Enrichment Processing';
      case 'ENRICHMENT_TIMEOUT':
        return 'Enrichment Monitoring Timeout';
      case 'EXENSIO_LOADING':
        return 'Exensio Monitoring';
      case 'EXENSIO_TIMEOUT':
        return 'Completed — Verify in Exensio';
      case 'PROCESSING':
        return 'Enrichment Processing'; // legacy compat
      case 'COMPLETED':
        return 'Completed';
      case 'ERROR':
        return 'Failed';
      default:
        return status;
    }
  }

  getStatusIcon(status: string): string {
    switch (status) {
      case 'READY':
        return 'schedule';
      case 'ENQUEUED':
        return 'schedule_send';
      case 'ENRICHMENT':
        return 'hourglass_empty';
      case 'EXENSIO_LOADING':
        return 'cloud_upload';
      case 'PROCESSING':
        return 'hourglass_empty';
      case 'COMPLETED':
        return 'check_circle';
      case 'ERROR':
        return 'error';
      default:
        return 'help';
    }
  }

  getStatusColor(status: string): 'primary' | 'error' | 'default' | 'success' | 'warning' | 'muted' {
    switch (status) {
      case 'READY':
        return 'muted';
      case 'ENQUEUED':
        return 'warning';
      case 'ENRICHMENT':
        return 'primary';
      case 'EXENSIO_LOADING':
        return 'primary';
      case 'PROCESSING':
        return 'primary';
      case 'COMPLETED':
        return 'success';
      case 'ERROR':
        return 'error';
      default:
        return 'default';
    }
  }

  streamStatusTooltip(): string {
    const status = this.state().streamStatus;
    switch (status) {
      case 'live':
        return 'Real-time SSE stream active. Updates flow immediately as files change status.';
      case 'polling':
        return 'Using polling updates (every 5s). SSE stream unavailable or reconnecting.';
      case 'connecting':
        return 'Attempting to connect to real-time stream...';
      case 'error':
        return 'Stream error. Using polling as fallback.';
      default:
        return 'Monitoring not active';
    }
  }

  getTimeAgo(timestamp: string | undefined): string {
    if (!timestamp) return '-';
    try {
      const date = new Date(timestamp);
      const now = new Date();
      const seconds = Math.floor((now.getTime() - date.getTime()) / 1000);

      if (seconds < 60) return 'now';
      if (seconds < 3600) return `${Math.floor(seconds / 60)}m ago`;
      if (seconds < 86400) return `${Math.floor(seconds / 3600)}h ago`;
      return `${Math.floor(seconds / 86400)}d ago`;
    } catch {
      return '-';
    }
  }

  toggleExpand(file: MonitoringFileItem): void {
    if (!file.errorMessage && !file.cpOutputPath) return;
    const expanded = new Set(this.expandedFiles());
    if (expanded.has(file.id)) {
      expanded.delete(file.id);
    } else {
      expanded.add(file.id);
    }
    this.expandedFiles.set(expanded);
  }

  isExpanded(file: MonitoringFileItem): boolean {
    return this.expandedFiles().has(file.id);
  }

  /**
   * Check if a file has no integration data (all integration fields null/empty).
   * Returns true if cpIntegrationStatus, cpIntegrationMessage, exensioIntegrationStatus,
   * exensioIntegrationMessage, cpOutputPath, cpOutputTarget, and errorMessage are all
   * null, empty, or "not_configured".
   */
  private hasNoIntegrationData(file: MonitoringFileItem): boolean {
    const {
      cpIntegrationStatus,
      cpIntegrationMessage,
      exensioIntegrationStatus,
      exensioIntegrationMessage,
      cpOutputPath,
      cpOutputTarget,
      errorMessage,
    } = file;

    // Check all integration fields
    const hasCpIntegration = cpIntegrationStatus && cpIntegrationStatus !== 'not_configured';
    const hasCpMessage = !!cpIntegrationMessage?.trim();
    const hasExensioIntegration = exensioIntegrationStatus && exensioIntegrationStatus !== 'not_configured';
    const hasExensioMessage = !!exensioIntegrationMessage?.trim();
    const hasOutputPath = !!cpOutputPath?.trim();
    const hasOutputTarget = !!cpOutputTarget?.trim();
    const hasErrorMessage = !!errorMessage?.trim();

    // Return true if ALL integration fields are null/empty/not_configured
    return (
      !hasCpIntegration &&
      !hasCpMessage &&
      !hasExensioIntegration &&
      !hasExensioMessage &&
      !hasOutputPath &&
      !hasOutputTarget &&
      !hasErrorMessage
    );
  }

  /**
   * Get the detail line text for a file, combining all pipeline segments.
   * Requirements 1.1, 1.2, 2.1-2.7, 3.1-3.4, 4.1-4.6, 5.1-5.5, 7.1-7.5
   */
  getDetailLine(file: MonitoringFileItem): string {
    const { status, errorMessage } = file;

    // File in READY/ENQUEUED: show "Queued for Enrichment" label (Requirement 7.1)
    if (status === 'READY' || status === 'ENQUEUED') {
      return 'Queued for Enrichment';
    }

    // File in ENRICHMENT: show "Enrichment: Processing" (Requirement 7.2)
    if (status === 'ENRICHMENT') {
      return 'Enrichment: Processing';
    }

    // File in ENRICHMENT_TIMEOUT: show timeout label
    if (status === 'ENRICHMENT_TIMEOUT') {
      return 'Enrichment: Monitoring Timeout — verify manually';
    }

    // File in EXENSIO_LOADING: show "Enrichment: Done · Exensio: Monitoring" (Requirement 7.3)
    if (status === 'EXENSIO_LOADING') {
      return 'Enrichment: Done · Exensio: Monitoring';
    }

    // File in EXENSIO_TIMEOUT: show verify label
    if (status === 'EXENSIO_TIMEOUT') {
      return 'Enrichment: Done · Exensio: Not confirmed — verify in Exensio';
    }

    // File COMPLETED: show full pipeline summary (Requirements 7.4, 2.1-2.7, 3.1-3.4, 4.1-4.6)
    if (status === 'COMPLETED') {
      const segments: string[] = [];

      const enrichmentSegment = this.getEnrichmentSegment(file);
      if (enrichmentSegment) {
        segments.push(enrichmentSegment);
      }

      const outputBadge = this.getOutputTargetBadge(file);
      if (outputBadge) {
        segments.push(outputBadge);
      }

      const exensioSegment = this.getExensioSegment(file);
      if (exensioSegment) {
        segments.push(exensioSegment);
      }

      // Requirement 1.3: Show placeholder if no integration data
      return segments.length > 0 ? segments.join(' · ') : 'Waiting...';
    }

    // File ERROR: show error message (Requirement 7.5, 5.1-5.4)
    if (status === 'ERROR') {
      const errorSummary = this.getErrorSummary(file);
      return errorSummary || 'Failed';
    }

    // Requirement 1.3: Check if all integration fields are null/empty
    // This applies to files not in the specific statuses above
    if (this.hasNoIntegrationData(file)) {
      return 'Waiting...';
    }

    // Default fallback: show placeholder
    return 'Waiting...';
  }

  /**
   * Get enrichment stage text based on cpIntegrationStatus.
   * Requirements 2.1-2.7
   */
  getEnrichmentSegment(file: MonitoringFileItem): string | null {
    const status = file.cpIntegrationStatus;
    const message = file.cpIntegrationMessage;

    // Requirement 2.7: Omit if not configured or null
    if (!status || status === 'not_configured') {
      return null;
    }

    // Requirement 2.1: Success
    if (status === 'success') {
      return message ? `Enrichment: Done (${message})` : 'Enrichment: Done';
    }

    // Requirement 2.3: Pending
    if (status === 'pending') {
      return 'Enrichment: Processing';
    }

    // Requirement 2.4: Failure
    if (status === 'failure') {
      return 'Enrichment: Failed';
    }

    // Requirement 2.5: Timeout
    if (status === 'timeout') {
      return 'Enrichment: Monitoring Timeout';
    }

    // Requirement 2.6: Not found
    if (status === 'not_found') {
      return 'Enrichment: Not Found';
    }

    // Requirement 2.6: Error variant
    if (status === 'error') {
      return 'Enrichment: Error';
    }

    return null;
  }

  /**
   * Get Exensio load stage text based on exensioIntegrationStatus.
   * Requirements 4.1-4.6
   */
  getExensioSegment(file: MonitoringFileItem): string | null {
    const status = file.exensioIntegrationStatus;

    // Requirement 4.6: Omit if not configured or null
    if (!status || status === 'not_configured') {
      return null;
    }

    // Requirement 4.1: Success
    if (status === 'success') {
      return 'Exensio: Loaded';
    }

    // Requirement 4.2: Pending
    if (status === 'pending') {
      return 'Exensio: Monitoring';
    }

    // Requirement 4.3: Failure
    if (status === 'failure') {
      return 'Exensio: Load Failed';
    }

    // Requirement 4.4: Not found
    if (status === 'not_found') {
      return 'Exensio: Not confirmed — verify manually';
    }

    // Requirement 4.5: Error
    if (status === 'error') {
      return 'Exensio: Error';
    }

    return null;
  }

  /**
   * Get CP output target badge text.
   * Requirements 3.1-3.4
   */
  getOutputTargetBadge(file: MonitoringFileItem): string | null {
    const cpStatus = file.cpIntegrationStatus;
    const target = file.cpOutputTarget;

    // Requirement 3.4: Omit badge if enrichment not successful
    if (cpStatus !== 'success') {
      return null;
    }

    // Requirement 3.1-3.3: Show badge for success with any target
    if (target === 'PRODUCTION' || target === 'SANDBOX' || target === 'UNKNOWN' || !target) {
      return target || 'UNKNOWN';
    }

    return null;
  }

  /**
   * Get error summary with source label, truncated to 140 chars.
   * Sources: "CP" for Elasticsearch/Enrichment errors, "Exensio" for Exensio API errors.
   * Requirements 5.1-5.4
   */
  getErrorSummary(file: MonitoringFileItem): string | null {
    let errorText: string | null = null;
    let source = '';

    // Requirement 5.4: Priority order — determine source and error text
    if (file.errorMessage) {
      errorText = file.errorMessage;
      source = this.detectErrorSource(file.errorMessage, file);
    } else if (file.cpIntegrationStatus === 'failure' && file.cpIntegrationMessage) {
      errorText = file.cpIntegrationMessage;
      source = 'CP';
    } else if (
      (file.exensioIntegrationStatus === 'failure' || file.exensioIntegrationStatus === 'error') &&
      file.exensioIntegrationMessage
    ) {
      errorText = file.exensioIntegrationMessage;
      source = 'Exensio';
    }

    if (!errorText) {
      return null;
    }

    // Build prefixed summary
    const prefix = source ? `${source} — ` : '';
    const maxLen = 140;
    const available = maxLen - prefix.length;
    const truncated = errorText.length > available ? errorText.substring(0, available) + '…' : errorText;
    return `${prefix}${truncated}`;
  }

  /**
   * Detect the error source from the error message content or integration status fields.
   * Returns "CP" for Elasticsearch/enrichment errors, "Exensio" for Exensio API errors, "" if unknown.
   */
  private detectErrorSource(errorMessage: string, file: MonitoringFileItem): string {
    const msg = errorMessage.toLowerCase();

    // Check backend-prefixed error messages
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

    // Fall back to integration status fields
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
   * Get the full untruncated error message with source prefix for tooltip.
   * Requirements 5.5
   */
  getFullErrorMessage(file: MonitoringFileItem): string {
    let errorText: string | null = null;
    let source = '';

    if (file.errorMessage) {
      errorText = file.errorMessage;
      source = this.detectErrorSource(file.errorMessage, file);
    } else if (file.cpIntegrationStatus === 'failure' && file.cpIntegrationMessage) {
      errorText = file.cpIntegrationMessage;
      source = 'CP';
    } else if (
      (file.exensioIntegrationStatus === 'failure' || file.exensioIntegrationStatus === 'error') &&
      file.exensioIntegrationMessage
    ) {
      errorText = file.exensioIntegrationMessage;
      source = 'Exensio';
    }

    if (!errorText) return '';
    return source ? `${source} — ${errorText}` : errorText;
  }

  /**
   * Check if the error message is truncated (longer than 120 chars).
   * Used to determine if tooltip should be shown.
   */
  isErrorTruncated(file: MonitoringFileItem): boolean {
    const fullMessage = this.getFullErrorMessage(file);
    return fullMessage.length > 120;
  }

  /**
   * Get detail line icon based on file status.
   */
  getDetailLineIcon(file: MonitoringFileItem): string {
    const { status, cpIntegrationStatus, exensioIntegrationStatus, errorMessage } = file;

    if (status === 'ERROR' || errorMessage) {
      return 'error';
    }

    if (status === 'COMPLETED') {
      if (exensioIntegrationStatus === 'success') {
        return 'check_circle';
      }
      if (cpIntegrationStatus === 'success') {
        return 'check_circle';
      }
    }

    if (status === 'ENRICHMENT') {
      return 'hourglass_empty';
    }

    if (status === 'EXENSIO_LOADING') {
      return 'cloud_upload';
    }

    return 'schedule';
  }

  /**
   * Get detail line color class based on file status.
   */
  getDetailLineColor(file: MonitoringFileItem): 'success' | 'error' | 'warning' | 'muted' {
    const { status, errorMessage } = file;

    // Files in READY/ENQUEUED show "Queued" - should be muted
    if (status === 'READY' || status === 'ENQUEUED') {
      return 'muted';
    }

    // Files in ENRICHMENT/EXENSIO_LOADING show status text - should be warning
    if (status === 'ENRICHMENT' || status === 'EXENSIO_LOADING') {
      return 'warning';
    }

    // Files in ERROR show error message - should be error
    if (status === 'ERROR' || errorMessage) {
      return 'error';
    }

    // Files in COMPLETED show pipeline summary - should be success
    if (status === 'COMPLETED') {
      return 'success';
    }

    // Files with no integration data show "Waiting..." - should be muted
    if (this.hasNoIntegrationData(file)) {
      return 'muted';
    }

    // Default fallback
    return 'muted';
  }

  /**
   * Detect error source for display in the expanded row badge.
   * Returns "CP", "Exensio", or "" if unknown.
   */
  detectErrorSourceForDisplay(file: MonitoringFileItem): string {
    if (!file.errorMessage) return '';
    return this.detectErrorSource(file.errorMessage, file);
  }
}
