import { CommonModule } from '@angular/common';
import { Component, inject, OnInit, signal } from '@angular/core';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { debounceTime, distinctUntilChanged } from 'rxjs';
import { DualTimestampComponent } from '../shared/components/dual-timestamp.component';
import { DateRange, GlassDateRangeComponent } from '../shared/components/glass-date-range.component';
import { GlassIconComponent } from '../shared/components/glass-icon.component';
import { GlassPaginationComponent, PaginationEvent } from '../shared/components/glass-pagination.component';
import { GlassSelectComponent } from '../shared/components/glass-select.component';
import { GlassTooltipDirective } from '../shared/directives/glass-tooltip.directive';
import { GlassDialogService } from '../shared/services/glass-dialog.service';
import { ToastService } from '../shared/services/toast.service';
import { AuditLogDetailDialogComponent } from './audit-log-detail-dialog.component';
import { AuditService, EtlAuditLog } from './audit.service';

@Component({
  selector: 'app-audit-log-table',
  standalone: true,
  imports: [
    CommonModule,
    ReactiveFormsModule,
    GlassSelectComponent,
    GlassIconComponent,
    GlassTooltipDirective,
    GlassPaginationComponent,
    DualTimestampComponent,
    GlassDateRangeComponent,
  ],
  template: `
    <div class="audit-container">
      <header class="audit-title">
        <div class="audit-header-text">
          <h1>ETL Trigger <span class="accent">Audit</span></h1>
          <p class="subtitle">Monitor and review all ETL SSH trigger executions.</p>
        </div>
        <div class="audit-actions">
          <button
            type="button"
            (click)="exportCsv()"
            class="audit-action-btn"
            [glassTooltip]="'Export to CSV'"
            [disabled]="loading() || exporting()"
          >
            <app-glass-icon name="download" [size]="18"></app-glass-icon>
          </button>
          <button
            type="button"
            (click)="refresh()"
            class="audit-action-btn"
            [glassTooltip]="'Refresh data'"
            [disabled]="loading()"
          >
            <app-glass-icon name="refresh" [size]="18"></app-glass-icon>
          </button>
        </div>
      </header>

      <div class="filter-bar glass-panel">
        <div class="search-box">
          <app-glass-icon name="search" [size]="18"></app-glass-icon>
          <input type="text" [formControl]="searchControl" placeholder="Find by request ID, user, or message..." />
        </div>
        <div class="filter-group">
          <app-glass-select
            class="mini-filter"
            label="Resource Type"
            placeholder="All Resource Types"
            [formControl]="resourceTypeFilter"
            [options]="resourceTypeOptions"
          ></app-glass-select>

          <app-glass-select
            class="mini-filter"
            label="Site"
            placeholder="All Sites"
            [formControl]="siteFilter"
            [options]="sites"
          ></app-glass-select>

          <app-glass-select
            class="mini-filter"
            label="Status"
            placeholder="All Statuses"
            [formControl]="statusFilter"
            [options]="['success', 'failure', 'not_configured']"
          ></app-glass-select>

          <app-glass-select
            class="mini-filter"
            label="Server"
            placeholder="All Servers"
            [formControl]="serverFilter"
            [options]="servers"
          ></app-glass-select>

          <div class="date-range-filter">
            <app-glass-date-range
              label="Created Date"
              [includeTime]="true"
              [inline]="true"
              [formControl]="dateRangeControl"
            ></app-glass-date-range>
          </div>
        </div>
      </div>

      <div class="content-area glass-panel table-overflow">
        <table class="exensioreload-table">
          <thead>
            <tr>
              <th>
                <button type="button" class="sort-header-btn" (click)="setSort('timestamp')">
                  Time
                  <app-glass-icon [name]="sortIcon('timestamp')" [size]="14"></app-glass-icon>
                </button>
              </th>
              <th>Request ID</th>
              <th>User</th>
              <th>Resource Type</th>
              <th>Site</th>
              <th>Server</th>
              <th>Port</th>
              <th>Status</th>
              <th>Message</th>
              <th class="action-column">Actions</th>
            </tr>
          </thead>
          <tbody>
            <tr *ngFor="let log of dataSource(); trackBy: trackById">
              <td *ngIf="isEtlAuditLog(log)">
                <app-dual-timestamp [value]="log.timestamp"></app-dual-timestamp>
              </td>
              <td *ngIf="isEtlAuditLog(log)">
                <div class="request-id-cell">
                  <code class="request-id">{{ log.requestId }}</code>
                </div>
              </td>
              <td>{{ log.userId }}</td>
              <td *ngIf="isEtlAuditLog(log)">
                <span class="resource-type-badge" [attr.data-resource-type]="getResourceType(log)">
                  {{ getResourceType(log) }}
                </span>
              </td>
              <td *ngIf="isEtlAuditLog(log)">{{ log.site }}</td>
              <td *ngIf="isEtlAuditLog(log)">{{ log.etlServerName }}</td>
              <td *ngIf="isEtlAuditLog(log)">
                <span class="port-value" *ngIf="log.senderPort !== undefined && log.senderPort !== null">
                  {{ log.senderPort }}
                </span>
                <span class="port-placeholder" *ngIf="log.senderPort === undefined || log.senderPort === null">
                  —
                </span>
              </td>
              <td>
                <span class="status-tag" [attr.data-status]="log.status">
                  {{ log.status }}
                </span>
              </td>
              <td class="message-cell" *ngIf="isEtlAuditLog(log)">
                <span class="message-text" [title]="log.message || ''">
                  {{ log.message || '' | slice: 0 : 50 }}{{ (log.message || '').length > 50 ? '...' : '' }}
                </span>
              </td>
              <td class="action-column" *ngIf="isEtlAuditLog(log)">
                <button type="button" class="action-btn" (click)="viewDetails(log)" [glassTooltip]="'View Details'">
                  <app-glass-icon name="info" [size]="18"></app-glass-icon>
                </button>
              </td>
            </tr>
          </tbody>
        </table>

        <div class="loading-overlay" *ngIf="loading()">
          <div class="hub-spinner" aria-label="Loading"></div>
        </div>

        <app-glass-pagination
          [length]="totalElements()"
          [pageSize]="pageSize"
          [pageIndex]="pageIndex"
          [pageSizeOptions]="[10, 20, 50]"
          (page)="onPage($event)"
        >
        </app-glass-pagination>
      </div>
    </div>
  `,
  styleUrls: ['./audit-log-table.component.scss'],
})
export class AuditLogTableComponent implements OnInit {
  private auditService = inject(AuditService);
  private dialogService = inject(GlassDialogService);
  private toastService = inject(ToastService);

  formatUtcTimestamp(value: string | Date | null | undefined): string {
    if (!value) return '-';
    const d = new Date(value);
    if (isNaN(d.getTime())) return '-';
    return d.toLocaleString([], {
      year: 'numeric',
      month: 'short',
      day: 'numeric',
      hour: '2-digit',
      minute: '2-digit',
      timeZone: 'UTC',
    });
  }

  formatUtcSeconds(value: string | Date | null | undefined): string {
    if (!value) return '';
    const d = new Date(value);
    return isNaN(d.getTime()) ? '' : String(d.getUTCSeconds()).padStart(2, '0');
  }

  dataSource = signal<EtlAuditLog[]>([]);
  loading = signal(false);
  exporting = signal(false);
  totalElements = signal(0);
  pageSize = 20;
  pageIndex = 0;
  sortBy = 'timestamp';
  sortDir: 'asc' | 'desc' = 'desc';

  searchControl = new FormControl('');
  resourceTypeFilter = new FormControl('');
  siteFilter = new FormControl('');
  statusFilter = new FormControl('');
  serverFilter = new FormControl('');
  dateRangeControl = new FormControl<DateRange | null>(null);

  // Resource type options for filter
  resourceTypeOptions = ['USER', 'PIPELINE', 'ETL_SERVER', 'DB_CONNECTION', 'SESSION', 'ETL_TRIGGER', 'SYSTEM'];

  sites: string[] = [];
  servers: string[] = [];

  ngOnInit(): void {
    this.loadAuditLogs();
    this.loadMetadata();

    // Setup reactive filters
    this.searchControl.valueChanges.pipe(debounceTime(400), distinctUntilChanged()).subscribe(() => this.reload());
    this.resourceTypeFilter.valueChanges.subscribe(() => this.reload());
    this.siteFilter.valueChanges.subscribe(() => this.reload());
    this.statusFilter.valueChanges.subscribe(() => this.reload());
    this.serverFilter.valueChanges.subscribe(() => this.reload());
    this.dateRangeControl.valueChanges.subscribe(() => this.reload());
  }

  loadAuditLogs(): void {
    this.loading.set(true);
    const dateRange = this.dateRangeControl.value;
    this.auditService
      .getAuditLogs({
        page: this.pageIndex,
        size: this.pageSize,
        requestId: this.searchControl.value || undefined,
        resourceType: this.resourceTypeFilter.value || undefined,
        site: this.siteFilter.value || undefined,
        status: this.statusFilter.value || undefined,
        userId: this.searchControl.value || undefined,
        etlServerName: this.serverFilter.value || undefined,
        startDate: dateRange?.start || undefined,
        endDate: dateRange?.end || undefined,
      })
      .subscribe({
        next: (res) => {
          this.dataSource.set(res.content as EtlAuditLog[]);
          this.totalElements.set(res.totalElements);
          this.loading.set(false);
        },
        error: () => this.loading.set(false),
      });
  }

  setSort(field: string): void {
    if (this.sortBy === field) {
      this.sortDir = this.sortDir === 'asc' ? 'desc' : 'asc';
    } else {
      this.sortBy = field;
      this.sortDir = 'asc';
    }
    this.reload();
  }

  sortIcon(field: string): string {
    if (this.sortBy !== field) return 'unfold_more';
    return this.sortDir === 'asc' ? 'arrow_upward' : 'arrow_downward';
  }

  trackById = (_: number, log: EtlAuditLog): number => log.id;

  isEtlAuditLog(log: any): log is EtlAuditLog {
    return log && typeof log === 'object' && 'requestId' in log && 'site' in log;
  }

  private loadMetadata(): void {
    // Load unique sites and servers from audit logs
    this.auditService.getAuditLogs({ size: 1000 }).subscribe({
      next: (res) => {
        const sites = new Set<string>();
        const servers = new Set<string>();
        res.content.forEach((log) => {
          if (log instanceof Object && 'site' in log) sites.add((log as EtlAuditLog).site);
          if (log instanceof Object && 'etlServerName' in log) servers.add((log as EtlAuditLog).etlServerName);
        });
        this.sites = Array.from(sites).sort();
        this.servers = Array.from(servers).sort();
      },
      error: () => {},
    });
  }

  private reload(): void {
    this.pageIndex = 0;
    this.loadAuditLogs();
  }

  onPage(event: PaginationEvent): void {
    this.pageIndex = event.pageIndex;
    this.pageSize = event.pageSize;
    this.loadAuditLogs();
  }

  refresh(): void {
    this.loadAuditLogs();
  }

  getResourceType(log: EtlAuditLog): string {
    // Default to ETL_TRIGGER for existing ETL audit logs
    return 'ETL_TRIGGER';
  }

  viewDetails(log: EtlAuditLog): void {
    // For ETL audit logs, we need to fetch the full audit log details from the backend
    // Since the table shows EtlAuditLog but we need AuditLogDto for the detail dialog
    // We'll convert the available data to show in the dialog
    const auditLogData = {
      id: log.id,
      action: 'ETL_TRIGGERED', // This would come from the API
      resourceType: 'ETL_TRIGGER',
      resourceId: log.requestId,
      details: JSON.stringify({
        site: log.site,
        etlServerName: log.etlServerName,
        senderPort: log.senderPort,
        status: log.status,
        message: log.message,
      }),
      ipAddress: log.remoteIp || undefined,
      userAgent: undefined,
      createdAt: log.timestamp,
      status: log.status,
      userId: log.userId ? parseInt(log.userId) : undefined,
    };

    this.dialogService.open(AuditLogDetailDialogComponent, {
      width: '900px',
      maxHeight: '90vh',
      data: auditLogData,
    });
  }

  exportCsv(): void {
    this.exporting.set(true);

    // Build filter parameters from current filter state
    const dateRange = this.dateRangeControl.value;
    const filterParams = {
      requestId: this.searchControl.value || undefined,
      userId: this.searchControl.value || undefined,
      resourceType: this.resourceTypeFilter.value || undefined,
      site: this.siteFilter.value || undefined,
      status: this.statusFilter.value || undefined,
      etlServerName: this.serverFilter.value || undefined,
      startDate: dateRange?.start || undefined,
      endDate: dateRange?.end || undefined,
    };

    this.auditService.exportEtlAuditLogs(filterParams).subscribe({
      next: (blob: Blob) => {
        // Create a blob URL and trigger download
        const url = window.URL.createObjectURL(blob);
        const link = document.createElement('a');
        link.href = url;
        link.download = `audit-logs-${new Date().toISOString().split('T')[0]}.csv`;
        document.body.appendChild(link);
        link.click();
        document.body.removeChild(link);
        window.URL.revokeObjectURL(url);

        this.exporting.set(false);
        this.toastService.success('Audit logs exported successfully');
      },
      error: (error) => {
        this.exporting.set(false);
        console.error('Failed to export audit logs:', error);
        this.toastService.error('Failed to export audit logs');
      },
    });
  }
}
