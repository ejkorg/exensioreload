import { Component, OnInit, signal, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { GlassSelectComponent } from '../shared/components/glass-select.component';
import { GlassPaginationComponent, PaginationEvent } from '../shared/components/glass-pagination.component';
import { GlassIconComponent } from '../shared/components/glass-icon.component';
import { GlassTooltipDirective } from '../shared/directives/glass-tooltip.directive';
import { debounceTime, distinctUntilChanged } from 'rxjs';
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
    GlassPaginationComponent
  ],
  template: `
    <div class="audit-container">
      <header class="audit-title">
        <div class="audit-header-text">
          <h1>ETL Trigger <span class="accent">Audit</span></h1>
          <p class="subtitle">Monitor and review all ETL SSH trigger executions.</p>
        </div>
        <div class="audit-actions">
          <button type="button" (click)="refresh()" class="audit-action-btn" [glassTooltip]="'Refresh data'">
            <app-glass-icon name="refresh" [size]="18"></app-glass-icon>
          </button>
        </div>
      </header>

      <div class="filter-bar glass-panel">
        <div class="search-box">
          <app-glass-icon name="search" [size]="18"></app-glass-icon>
          <input type="text" [formControl]="searchControl" placeholder="Find by request ID, user, or message...">
        </div>
        <div class="filter-group">
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
              <th>Site</th>
              <th>Server</th>
              <th>Port</th>
              <th>Status</th>
              <th>Message</th>
            </tr>
          </thead>
          <tbody>
            <tr *ngFor="let log of dataSource(); trackBy: trackById">
              <td>
                <div class="time-cell">
                  <span class="time-value">{{ log.timestamp | date:'short' }}</span>
                  <span class="time-seconds">{{ log.timestamp | date:'ss' }}s</span>
                </div>
              </td>
              <td>
                <div class="request-id-cell">
                  <code class="request-id">{{ log.requestId }}</code>
                </div>
              </td>
              <td>{{ log.userId }}</td>
              <td>{{ log.site }}</td>
              <td>{{ log.etlServerName }}</td>
              <td>
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
              <td class="message-cell">
                <span class="message-text" [title]="log.message || ''">
                  {{ log.message || '' | slice:0:50 }}{{ (log.message || '').length > 50 ? '...' : '' }}
                </span>
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
          (page)="onPage($event)">
        </app-glass-pagination>
      </div>
    </div>
  `,
  styleUrls: ['./audit-log-table.component.scss']
})
export class AuditLogTableComponent implements OnInit {
  private auditService = inject(AuditService);

  dataSource = signal<EtlAuditLog[]>([]);
  loading = signal(false);
  totalElements = signal(0);
  pageSize = 20;
  pageIndex = 0;
  sortBy = 'timestamp';
  sortDir: 'asc' | 'desc' = 'desc';

  searchControl = new FormControl('');
  siteFilter = new FormControl('');
  statusFilter = new FormControl('');
  serverFilter = new FormControl('');

  sites: string[] = [];
  servers: string[] = [];

  ngOnInit(): void {
    this.loadAuditLogs();
    this.loadMetadata();

    // Setup reactive filters
    this.searchControl.valueChanges.pipe(debounceTime(400), distinctUntilChanged()).subscribe(() => this.reload());
    this.siteFilter.valueChanges.subscribe(() => this.reload());
    this.statusFilter.valueChanges.subscribe(() => this.reload());
    this.serverFilter.valueChanges.subscribe(() => this.reload());
  }

  loadAuditLogs(): void {
    this.loading.set(true);
    this.auditService.getAuditLogs({
      page: this.pageIndex,
      size: this.pageSize,
      requestId: this.searchControl.value || undefined,
      site: this.siteFilter.value || undefined,
      status: this.statusFilter.value || undefined,
      userId: this.searchControl.value || undefined,
      etlServerName: this.serverFilter.value || undefined
    }).subscribe({
      next: (res) => {
        this.dataSource.set(res.content);
        this.totalElements.set(res.totalElements);
        this.loading.set(false);
      },
      error: () => this.loading.set(false)
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

  private loadMetadata(): void {
    // Load unique sites and servers from audit logs
    this.auditService.getAuditLogs({ size: 1000 }).subscribe({
      next: (res) => {
        const sites = new Set<string>();
        const servers = new Set<string>();
        res.content.forEach(log => {
          if (log.site) sites.add(log.site);
          if (log.etlServerName) servers.add(log.etlServerName);
        });
        this.sites = Array.from(sites).sort();
        this.servers = Array.from(servers).sort();
      },
      error: () => {}
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
}
