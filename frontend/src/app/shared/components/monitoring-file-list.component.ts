import { Component, Input, signal, computed } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MonitoringFile } from '../services/monitoring.service';
import { GlassIconComponent } from './glass-icon.component';
import { GlassInputComponent } from './glass-input.component';
import { GlassButtonComponent } from './glass-button.component';
import { GlassTooltipDirective } from '../directives/glass-tooltip.directive';
import { GlassPaginationComponent, PaginationEvent } from './glass-pagination.component';

@Component({
  selector: 'app-monitoring-file-list',
  standalone: true,
  imports: [
    CommonModule,
    GlassIconComponent,
    GlassInputComponent,
    GlassButtonComponent,
    GlassTooltipDirective,
    GlassPaginationComponent
  ],
  template: `
    <div class="file-list-container glass-panel">
      <!-- Toolbar -->
      <div class="file-list-toolbar">
        <div class="toolbar-left">
          <span class="file-count">{{ filteredFiles().length }} files</span>
          <div class="status-filters">
            <button class="filter-chip"
                    [class.active]="statusFilter() === 'all'"
                    (click)="statusFilter.set('all')">
              All
            </button>
            <button class="filter-chip"
                    [class.active]="statusFilter() === 'ENRICHMENT'"
                    (click)="statusFilter.set('ENRICHMENT')">
              <app-glass-icon name="refresh" [size]="14"></app-glass-icon>
              Enrichment
            </button>
            <button class="filter-chip"
                    [class.active]="statusFilter() === 'EXENSIO_LOADING'"
                    (click)="statusFilter.set('EXENSIO_LOADING')">
              <app-glass-icon name="cloud_upload" [size]="14"></app-glass-icon>
              Exensio Loading
            </button>
            <button class="filter-chip"
                    [class.active]="statusFilter() === 'COMPLETED'"
                    (click)="statusFilter.set('COMPLETED')">
              <app-glass-icon name="check" [size]="14"></app-glass-icon>
              Completed
            </button>
            <button class="filter-chip"
                    [class.active]="statusFilter() === 'ERROR'"
                    (click)="statusFilter.set('ERROR')">
              <app-glass-icon name="error" [size]="14"></app-glass-icon>
              Failed
            </button>
          </div>
        </div>
        <div class="toolbar-right">
          <app-glass-input
            class="search-input"
            placeholder="Search files..."
            prefixIcon="search"
            [value]="searchText()"
            (valueChange)="searchText.set($event)">
          </app-glass-input>
          <app-glass-button
            class="export-btn"
            variant="icon"
            [glassTooltip]="'Export to CSV'"
            (clicked)="exportCSV()">
            <app-glass-icon name="download" [size]="20"></app-glass-icon>
          </app-glass-button>
        </div>
      </div>

      <!-- Virtual Scrolling Table -->
      <div class="file-list-table">
        <div class="table-header">
          <div class="col-status">Status</div>
          <div class="col-filename">Filename</div>
          <div class="col-lot">Lot</div>
          <div class="col-wafer" *ngIf="showWaferColumn()">Wafer</div>
          <div class="col-message">Message</div>
        </div>

        <div class="table-body">
          <div class="table-row"
               *ngFor="let file of paginatedFiles()"
               [class.status-ready]="file.status === 'READY'"
               [class.status-enqueued]="file.status === 'ENQUEUED'"
               [class.status-processing]="file.status === 'ENRICHMENT' || file.status === 'EXENSIO_LOADING'"
               [class.status-completed]="file.status === 'COMPLETED'"
               [class.status-error]="file.status === 'ERROR'"
               (click)="toggleExpand(file)">
            <div class="col-status">
              <div class="status-badge" [class]="'status-' + file.status.toLowerCase()">
                <app-glass-icon
                  [name]="getStatusIcon(file.status)"
                  [size]="16"
                  [color]="getStatusColor(file.status)">
                </app-glass-icon>
                <span class="status-text">{{ getStatusLabel(file.status) }}</span>
              </div>
            </div>
            <div class="col-filename" [glassTooltip]="file.filename">
              {{ file.filename }}
            </div>
            <div class="col-lot">{{ file.lot }}</div>
            <div class="col-wafer" *ngIf="showWaferColumn()">{{ file.wafer || '-' }}</div>
            <div class="col-message" [glassTooltip]="file.message">
              {{ file.message }}
            </div>

            <!-- Expanded Details -->
            <div class="row-expanded" *ngIf="isExpanded(file) && (file.errorMessage || file.cpOutputPath)">
              <div class="error-details" *ngIf="file.errorMessage">
                <app-glass-icon name="error" [size]="18" color="error"></app-glass-icon>
                <div class="error-content">
                  <div class="error-label">Error Details:</div>
                  <div class="error-message">{{ file.errorMessage }}</div>
                </div>
              </div>
              <div class="cp-output-details" *ngIf="file.cpOutputPath">
                <div class="cp-output-row">
                  <app-glass-icon name="folder" [size]="16" color="muted"></app-glass-icon>
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
        (page)="onPageChange($event)">
      </app-glass-pagination>
    </div>
  `,
  styles: [`
    .file-list-container {
      display: flex;
      flex-direction: column;
      height: 100%;
      min-height: 420px;
      overflow: hidden;
    }

    .file-list-toolbar {
      display: flex;
      justify-content: space-between;
      align-items: center;
      padding: 1rem;
      border-bottom: 1px solid rgba(255, 255, 255, 0.05);
      gap: 1rem;
      flex-wrap: wrap;
    }

    .toolbar-left {
      display: flex;
      align-items: center;
      gap: 1rem;
      flex: 1;
      min-width: 0;
    }

    .toolbar-right {
      display: flex;
      align-items: center;
      gap: 0.5rem;
    }

    .file-count {
      font-size: 0.875rem;
      font-weight: 600;
      color: var(--text-main);
      white-space: nowrap;
    }

    .status-filters {
      display: flex;
      gap: 0.5rem;
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
      font-size: 0.8125rem;
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

    .search-input {
      width: 250px;
    }

    app-glass-button.export-btn {
      display: inline-flex;
    }

    app-glass-button.export-btn ::ng-deep .glass-btn {
      width: 42px;
      height: 42px;
      border-radius: 10px;
      background: rgba(255, 255, 255, 0.08);
      border: 1px solid rgba(255, 255, 255, 0.22);
      color: #e2e8f0;
      box-shadow: 0 6px 14px rgba(15, 23, 42, 0.22);
    }

    app-glass-button.export-btn ::ng-deep .glass-btn:hover:not(:disabled) {
      background: rgba(129, 140, 248, 0.16);
      border-color: rgba(129, 140, 248, 0.45);
      color: #c7d2fe;
      transform: translateY(-1px);
    }

    app-glass-button.export-btn ::ng-deep .glass-btn:disabled {
      opacity: 0.45;
    }

    app-glass-button.export-btn ::ng-deep app-glass-icon {
      color: currentColor;
      opacity: 1;
    }

    :host-context(body.light-theme) app-glass-button.export-btn ::ng-deep .glass-btn {
      background: rgba(255, 255, 255, 0.98);
      border: 1px solid rgba(15, 23, 42, 0.18);
      color: #334155;
      box-shadow: 0 4px 10px rgba(15, 23, 42, 0.1);
    }

    :host-context(body.light-theme) app-glass-button.export-btn ::ng-deep .glass-btn:hover:not(:disabled) {
      background: rgba(238, 242, 255, 1);
      border-color: rgba(79, 70, 229, 0.42);
      color: #3730a3;
    }

    .file-list-table {
      flex: 1;
      display: flex;
      flex-direction: column;
      min-height: 0;
    }

    .table-header {
      display: grid;
      grid-template-columns: 140px 1fr 120px 100px 200px;
      gap: 1rem;
      padding: 0.75rem 1rem;
      background: rgba(255, 255, 255, 0.02);
      border-bottom: 1px solid rgba(255, 255, 255, 0.1);
      font-size: 0.75rem;
      font-weight: 600;
      color: var(--text-muted);
      text-transform: uppercase;
      letter-spacing: 0.05em;
    }

    .table-body {
      flex: 1;
      min-height: 0;
      overflow-y: auto;
    }

    .table-row {
      display: grid;
      grid-template-columns: 140px 1fr 120px 100px 200px;
      gap: 1rem;
      padding: 0.75rem 1rem;
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

    .col-status {
      display: flex;
      align-items: center;
    }

    .status-badge {
      display: flex;
      align-items: center;
      gap: 0.5rem;
      padding: 0.375rem 0.75rem;
      border-radius: 8px;
      font-size: 0.75rem;
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

    @media (min-width: 1024px) {
      .status-text {
        display: inline;
      }
    }

    .col-filename {
      font-family: monospace;
      font-size: 0.8125rem;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .col-lot,
    .col-wafer {
      font-weight: 500;
    }

    .col-message {
      color: var(--text-muted);
      font-size: 0.8125rem;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

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

    .error-label {
      font-size: 0.75rem;
      font-weight: 600;
      color: #ef4444;
      text-transform: uppercase;
      letter-spacing: 0.05em;
      margin-bottom: 0.375rem;
    }

    .error-message {
      font-size: 0.875rem;
      color: var(--text-main);
      line-height: 1.5;
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

    @media (max-width: 1024px) {
      .table-header,
      .table-row {
        grid-template-columns: 100px 1fr 100px 80px;
      }

      .col-message {
        display: none;
      }
    }

    @media (max-width: 768px) {
      .file-list-toolbar {
        flex-direction: column;
        align-items: stretch;
      }

      .toolbar-left,
      .toolbar-right {
        width: 100%;
      }

      .search-input {
        width: 100%;
      }

      app-glass-button.export-btn {
        align-self: flex-end;
      }

      .table-header,
      .table-row {
        grid-template-columns: 1fr 100px 80px;
      }

      .col-lot {
        display: none;
      }
    }
  `]
})
export class MonitoringFileListComponent {
  @Input() set files(value: MonitoringFile[]) {
    this._files.set(value);
  }

  private _files = signal<MonitoringFile[]>([]);
  searchText = signal('');
  statusFilter = signal<string>('all');
  expandedFiles = signal<Set<string | number>>(new Set());

  pageIndex = signal(0);
  pageSize = signal(10);

  filteredFiles = computed(() => {
    let files = this._files();
    const search = this.searchText().toLowerCase();
    const status = this.statusFilter();

    if (status !== 'all') {
      files = files.filter(f => f.status === status);
    }

    if (search) {
      files = files.filter(f =>
        f.filename.toLowerCase().includes(search) ||
        (f.lot ?? '').toLowerCase().includes(search) ||
        (f.wafer ?? '').toLowerCase().includes(search) ||
        f.message.toLowerCase().includes(search)
      );
    }

    return files;
  });

  /** Hide the Wafer column when no file in the current list has a wafer value */
  showWaferColumn = computed(() =>
    this._files().some(f => f.wafer != null && f.wafer.trim() !== '' && f.wafer !== '-')
  );

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
      case 'READY': return 'Staged';
      case 'ENQUEUED': return 'In Queue (pending CP)';
      case 'ENRICHMENT': return 'Enrichment / Translation';
      case 'EXENSIO_LOADING': return 'Exensio Loading';
      case 'PROCESSING': return 'Enrichment / Translation'; // legacy compat
      case 'COMPLETED': return 'Completed';
      case 'ERROR': return 'Failed';
      default: return status;
    }
  }

  getStatusIcon(status: string): string {
    switch (status) {
      case 'READY': return 'check';
      case 'ENQUEUED': return 'clock';
      case 'ENRICHMENT': return 'refresh';
      case 'EXENSIO_LOADING': return 'cloud_upload';
      case 'PROCESSING': return 'refresh';
      case 'COMPLETED': return 'check_circle';
      case 'ERROR': return 'error';
      default: return 'info';
    }
  }

  getStatusColor(status: string): 'default' | 'primary' | 'success' | 'warning' | 'error' | 'muted' {
    switch (status) {
      case 'READY': return 'muted';
      case 'ENQUEUED': return 'warning';
      case 'ENRICHMENT': return 'primary';
      case 'EXENSIO_LOADING': return 'primary';
      case 'PROCESSING': return 'primary';
      case 'COMPLETED': return 'success';
      case 'ERROR': return 'error';
      default: return 'default';
    }
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
    const headers = ['Status', 'Filename', 'Lot', 'Wafer', 'Message', 'Error'];
    const rows = files.map(f => [
      f.status,
      f.filename,
      f.lot,
      f.wafer || '',
      f.message,
      f.errorMessage || ''
    ]);

    const csv = [
      headers.join(','),
      ...rows.map(row => row.map(cell => `"${cell}"`).join(','))
    ].join('\n');

    const blob = new Blob([csv], { type: 'text/csv' });
    const url = window.URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `monitoring-files-${new Date().toISOString()}.csv`;
    a.click();
    window.URL.revokeObjectURL(url);
  }
}
