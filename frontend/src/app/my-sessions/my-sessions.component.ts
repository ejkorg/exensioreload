import { CommonModule } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, ElementRef, OnDestroy, OnInit, signal, ViewChild } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import * as echarts from 'echarts';
import { forkJoin } from 'rxjs';
import {
    BackendService,
    SessionAnalyticsResponse,
    SessionDailyStatusPoint,
    SessionLotWaferDailyPoint,
    StageRecordView,
    StagingSessionDetail,
    StagingSessionSummary
} from '../api/backend.service';
import { AuthService } from '../auth/auth.service';
import { GlassButtonComponent } from '../shared/components/glass-button.component';
import { GlassIconComponent } from '../shared/components/glass-icon.component';
import { GlassLoadingOverlayComponent } from '../shared/components/glass-loading-overlay.component';
import { SiteNamePipe } from '../shared/pipes/site-name.pipe';
import {
    formatUtcDate,
    formatUtcDateLabel,
    parseInstant,
    toUtcDayKey
} from '../shared/utils/datetime.util';

@Component({
    selector: 'app-my-sessions',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [CommonModule, GlassButtonComponent, GlassIconComponent, GlassLoadingOverlayComponent, SiteNamePipe],
    template: `
    <div class="container glass-panel">
      <glass-loading-overlay [visible]="loading()" message="Loading My Sessions..." subtext="Fetching session history and details."></glass-loading-overlay>
      <div class="page-header">
        <div class="page-header-left">
          <span class="page-title">My <span class="accent">Staging Sessions</span></span>
          <span class="filter-badge" *ngIf="siteFilter()">{{ siteFilter() | siteName }}</span>
        </div>
        <span class="page-subtitle">Track active and historical staging sessions.</span>
      </div>

      <div class="session-filters">
        <div class="filter-search-wrap">
          <app-glass-icon name="search" [size]="15" class="filter-search-icon"></app-glass-icon>
          <input class="filter-input filter-input--search" type="text" placeholder="Search sessions…" [value]="searchText()" (input)="onSearchTextChange($event)" />
        </div>
        <select class="filter-select" [value]="senderIdFilter() ?? ''" (change)="onSenderIdFilterChange($event)">
          <option value="">All Senders</option>
          <option *ngFor="let sender of senderFilterOptions()" [value]="sender.id">{{ sender.id }}{{ sender.name ? ' · ' + sender.name : '' }}</option>
        </select>
        <select *ngIf="showUserColumn()" class="filter-select" [value]="usernameFilter()" (change)="onUsernameFilterChange($event)">
          <option value="">All Users</option>
          <option *ngFor="let user of userFilterOptions()" [value]="user">{{ user }}</option>
        </select>
        <select class="filter-select" [value]="statusFilter()" (change)="onStatusFilterChange($event)">
          <option value="">All Status</option>
          <option value="STAGING">STAGING</option>
          <option value="DISPATCHING">DISPATCHING</option>
          <option value="MONITORING">MONITORING</option>
          <option value="COMPLETED">COMPLETED</option>
          <option value="PARTIALLY_FAILED">PARTIALLY_FAILED</option>
          <option value="CANCELLED">CANCELLED</option>
        </select>
        <div class="filter-actions">
          <app-glass-button variant="primary" size="small" (clicked)="applySessionFilters()">Apply</app-glass-button>
          <app-glass-button variant="secondary" size="small" (clicked)="clearSessionFilters()">Clear</app-glass-button>
        </div>
      </div>

      <div class="table-meta-bar">
        <span class="table-count" *ngIf="total() > 0">{{ total() }} session{{ total() !== 1 ? 's' : '' }}</span>
        <span class="table-count" *ngIf="total() === 0 && !loading()">No sessions found</span>
        <div class="table-meta-right">
          <label class="page-size-label">
            Rows
            <select class="page-size-select" [value]="size()" (change)="onPageSizeChange($event)">
              <option value="10">10</option>
              <option value="20">20</option>
              <option value="50">50</option>
              <option value="100">100</option>
            </select>
          </label>
        </div>
      </div>

      <div class="table-wrap">
        <table class="hub-table" role="table" aria-label="Staging sessions">
          <thead>
          <tr>
            <th>Session</th>
            <th *ngIf="showUserColumn()">User</th>
            <th>Site</th>
            <th>Sender</th>
            <th class="col-num">Files</th>
            <th class="col-progress">Progress</th>
            <th>Status</th>
            <th>Updated</th>
            <th>Actions</th>
          </tr>
          </thead>
          <tbody>
          <tr *ngFor="let session of sessions()" (click)="selectSession(session)" [class.active]="selectedSession()?.sessionId === session.sessionId" role="row">
            <td class="mono session-id-cell" [title]="session.sessionId">{{ session.sessionId.slice(0, 8) }}…</td>
            <td *ngIf="showUserColumn()" class="session-user">{{ session.username }}</td>
            <td class="site-cell">{{ session.site | siteName }}</td>
            <td class="sender-cell">
              <span class="sender-id">{{ session.senderId }}</span>
              <span class="sender-name" *ngIf="session.senderName">{{ session.senderName }}</span>
            </td>
            <td class="col-num">{{ session.totalFiles }}</td>
            <td class="col-progress">
              <div class="progress-wrap">
                <div class="progress-bar-track">
                  <div class="progress-bar-fill" [class]="getProgressClass(session)" [style.width.%]="session.progress"></div>
                </div>
                <span class="progress-pct">{{ session.progress | number:'1.0-0' }}%</span>
              </div>
            </td>
            <td><span class="status-badge" [class]="session.status.toLowerCase()">{{ session.status }}</span></td>
            <td class="date-cell">{{ formatShortDate(session.updatedAt) }}</td>
            <td class="table-actions-cell" (click)="$event.stopPropagation()">
              <app-glass-button
                *ngIf="canResumeMonitoring(session)"
                variant="primary"
                size="small"
                (clicked)="resumeSessionMonitoring(session)">
                <app-glass-icon name="play_arrow" [size]="14"></app-glass-icon>
                Resume
              </app-glass-button>
              <app-glass-button
                *ngIf="!isTerminal(session.status)"
                variant="danger"
                size="small"
                (clicked)="cancelSession(session.sessionId)">
                <app-glass-icon name="close" [size]="14"></app-glass-icon>
                Cancel
              </app-glass-button>
              <app-glass-button
                variant="secondary"
                size="small"
                [disabled]="session.totalFiles === 0"
                (clicked)="exportSessionFiles(session)">
                <app-glass-icon name="download" [size]="14"></app-glass-icon>
                Export CSV
              </app-glass-button>
            </td>
          </tr>
          <tr *ngIf="sessions().length === 0 && !loading()" class="empty-row">
            <td [attr.colspan]="showUserColumn() ? 8 : 7" class="empty-cell">
              <div class="empty-state">
                <app-glass-icon name="history" [size]="32"></app-glass-icon>
                <span>No sessions match your filters.</span>
              </div>
            </td>
          </tr>
          </tbody>
        </table>
      </div>

      <div class="pagination" *ngIf="total() > 0">
        <button class="page-btn" [disabled]="page() === 0" (click)="goToFirstPage()" title="First page" aria-label="First page">«</button>
        <button class="page-btn" [disabled]="page() === 0" (click)="prevPage()" title="Previous page" aria-label="Previous page">
          <app-glass-icon name="chevron_left" [size]="15"></app-glass-icon>
          Prev
        </button>
        <span class="pagination-info">
          Page <strong>{{ page() + 1 }}</strong> of <strong>{{ totalPages() }}</strong>
          <span class="pagination-range">({{ paginationRangeStart() }}–{{ paginationRangeEnd() }} of {{ total() }})</span>
        </span>
        <button class="page-btn" [disabled]="(page() + 1) * size() >= total()" (click)="nextPage()" title="Next page" aria-label="Next page">
          Next
          <app-glass-icon name="chevron_right" [size]="15"></app-glass-icon>
        </button>
        <button class="page-btn" [disabled]="(page() + 1) * size() >= total()" (click)="goToLastPage()" title="Last page" aria-label="Last page">»</button>
      </div>

      <div class="detail-overlay" *ngIf="selectedSession()" (click)="closeDetail()">
        <div class="detail-modal" role="dialog" aria-modal="true" aria-labelledby="modal-session-title" (click)="$event.stopPropagation()">
          <div class="detail-head" *ngIf="selectedSession() as session">
            <div class="detail-head-left">
              <h3 id="modal-session-title">Session Detail — {{ truncateSessionId(session.sessionId) }}</h3>
              <div class="head-meta">
                <span class="status-badge" [class]="selectedDetail()?.status?.toLowerCase() || session.status.toLowerCase()">
                  {{ selectedDetail()?.status || session.status }}
                </span>
                <span class="head-updated">Updated: {{ formatShortDate(session.updatedAt) }}</span>
                <app-glass-button variant="secondary" size="small" class="copy-id-btn" (clicked)="copySessionId(session.sessionId)">
                  {{ copiedSessionId() === session.sessionId ? 'Copied' : 'Copy ID' }}
                </app-glass-button>
              </div>
            </div>
            <div class="actions">
              <app-glass-button
                *ngIf="canResumeMonitoring(session)"
                variant="primary"
                size="small"
                (clicked)="resumeSessionMonitoring(session)">
                <app-glass-icon name="play_arrow" [size]="16"></app-glass-icon>
                Resume Monitoring
              </app-glass-button>
              <app-glass-button variant="secondary" size="small" (clicked)="refreshSession(session.sessionId)">
                <app-glass-icon name="refresh" [size]="16"></app-glass-icon>
                Refresh
              </app-glass-button>
              <app-glass-button variant="secondary" size="small" [disabled]="files().length === 0" (clicked)="exportCurrentSessionFiles()">
                <app-glass-icon name="download" [size]="16"></app-glass-icon>
                Export Files CSV
              </app-glass-button>
              <app-glass-button variant="secondary" size="small" class="cancel-soft" (clicked)="cancelSession(session.sessionId)" [disabled]="isTerminal(session.status)">
                <app-glass-icon name="close" [size]="16"></app-glass-icon>
                Cancel
              </app-glass-button>
              <app-glass-button variant="secondary" size="small" (clicked)="closeDetail()">
                <app-glass-icon name="close" [size]="16"></app-glass-icon>
                Close
              </app-glass-button>
            </div>
          </div>

          <div class="modal-body">
          <div class="metrics-cards" *ngIf="selectedDetail() as detail">
            <div class="metric-card metric-card--total">
              <app-glass-icon name="description" [size]="18"></app-glass-icon>
              <div class="metric-value">{{ detail.totalFiles }}</div>
              <div class="metric-label">Total</div>
            </div>
            <div class="metric-card metric-card--staged">
              <app-glass-icon name="upload" [size]="18"></app-glass-icon>
              <div class="metric-value">{{ detail.filesStaged }}</div>
              <div class="metric-label">Staged</div>
            </div>
            <div class="metric-card metric-card--enqueued">
              <app-glass-icon name="clock" [size]="18"></app-glass-icon>
              <div class="metric-value">{{ detail.filesEnqueued }}</div>
              <div class="metric-label">Enqueued</div>
            </div>
            <div class="metric-card metric-card--done">
              <app-glass-icon name="check_circle" [size]="18"></app-glass-icon>
              <div class="metric-value">{{ detail.filesDone }}</div>
              <div class="metric-label">Done</div>
            </div>
            <div class="metric-card metric-card--failed" [class.zero]="detail.filesFailed === 0">
              <app-glass-icon name="error" [size]="18"></app-glass-icon>
              <div class="metric-value">{{ detail.filesFailed }}</div>
              <div class="metric-label">Failed</div>
            </div>
          </div>
          <div class="status-row" *ngIf="selectedDetail() as detail">
            <span class="status-badge" [class]="detail.status.toLowerCase()">{{ detail.status }}</span>
          </div>

          <div class="coverage-banner" *ngIf="fileCoverage() as cov" role="region" aria-label="File end-time coverage">
            <div class="coverage-icon">
              <app-glass-icon name="calendar" [size]="18"></app-glass-icon>
            </div>
            <div class="coverage-body">
              <div class="coverage-label">File End-Time Coverage</div>
              <div class="coverage-range">
                <span class="coverage-date coverage-date--from">{{ cov.fromLabel }}</span>
                <span class="coverage-arrow">→</span>
                <span class="coverage-date coverage-date--to">{{ cov.toLabel }}</span>
                <span class="coverage-span" *ngIf="cov.days > 0">({{ cov.days }} day{{ cov.days !== 1 ? 's' : '' }})</span>
              </div>
              <div class="coverage-hint">Files in this session have end-times spanning this range. Use this to avoid re-staging already-covered dates.</div>
            </div>
          </div>

          <div class="charts-section" *ngIf="selectedSession()">
            <button type="button" class="section-toggle" [class.expanded]="chartsExpanded()" (click)="toggleChartsPanel()">
              <span class="toggle-label">Session Analytics</span>
              <span class="toggle-chevron">▾</span>
            </button>

            <div class="charts-panel" *ngIf="chartsExpanded()">
              <div class="charts-head">
                <h4>Session Analytics</h4>
                <span class="charts-sub">Lots/Wafers and status trend across session days</span>
                <div class="analytics-toolbar">
                  <label class="date-input-wrap">
                    <span>From</span>
                    <div class="date-input-row">
                      <input #startDateInput type="date" [value]="analyticsStartDate() || ''" (change)="onAnalyticsStartDateChange($event)" />
                      <button type="button" class="date-picker-btn" (click)="openDatePicker(startDateInput)">📅</button>
                    </div>
                  </label>
                  <label class="date-input-wrap">
                    <span>To</span>
                    <div class="date-input-row">
                      <input #endDateInput type="date" [value]="analyticsEndDate() || ''" (change)="onAnalyticsEndDateChange($event)" />
                      <button type="button" class="date-picker-btn" (click)="openDatePicker(endDateInput)">📅</button>
                    </div>
                  </label>
                  <app-glass-button variant="secondary" size="small" (clicked)="applyAnalyticsDateRange()">Apply Range</app-glass-button>
                  <app-glass-button variant="secondary" size="small" (clicked)="clearAnalyticsDateRange()">Clear</app-glass-button>
                  <div class="toolbar-divider"></div>
                  <button type="button" class="preset-btn" (click)="applyQuickPreset('today')">Today</button>
                  <button type="button" class="preset-btn" (click)="applyQuickPreset('7d')">Last 7d</button>
                  <button type="button" class="preset-btn" (click)="applyQuickPreset('30d')">Last 30d</button>
                  <button type="button" class="preset-btn" (click)="applyQuickPreset('90d')">Last 90d</button>
                  <button type="button" class="preset-btn" (click)="applyQuickPreset('month')">This Month</button>
                  <button type="button" class="preset-btn" (click)="applyQuickPreset('all')">All</button>
                </div>
                <div class="status-summary" *ngIf="analyticsStatusSummary().total > 0">
                  <span class="summary-pill summary-pill--completed">Completed {{ analyticsStatusSummary().completedPct }}%</span>
                  <span class="summary-pill summary-pill--failed">Failed {{ analyticsStatusSummary().failedPct }}%</span>
                  <span class="summary-pill summary-pill--cancelled">Cancelled {{ analyticsStatusSummary().cancelledPct }}%</span>
                  <span class="summary-pill summary-pill--total">Total {{ analyticsStatusSummary().total }}</span>
                </div>
              </div>
              <div class="charts-grid">
                <div class="chart-card">
                  <div class="chart-title">Daily Status Trend</div>
                  <div #trendChartContainer class="trend-chart-container" *ngIf="dailyStatusRows().length > 0; else noTrendData"></div>
                  <ng-template #noTrendData>
                    <div class="chart-empty">No daily trend data available.</div>
                  </ng-template>
                </div>
                <div class="chart-card">
                  <div class="chart-title">Status Distribution</div>
                  <div #statusChartContainer class="status-chart-container" *ngIf="sessionAnalytics()?.dailyStatus; else noStatusData"></div>
                  <ng-template #noStatusData>
                    <div class="chart-empty">No status data available.</div>
                  </ng-template>
                </div>
              </div>
            </div>
          </div>

          <div class="files-section" *ngIf="files().length > 0">
            <div class="files-section-header" (click)="toggleFilesTable()">
              <div class="files-section-title">
                <app-glass-icon name="description" [size]="15"></app-glass-icon>
                <span>Files Details</span>
                <span class="files-count-badge">{{ files().length }}</span>
              </div>
              <div class="files-section-right">
                <span class="files-toggle-hint">{{ filesTableExpanded() ? 'Collapse' : 'Expand' }}</span>
                <span class="toggle-chevron" [class.expanded]="filesTableExpanded()">▾</span>
              </div>
            </div>

            <div class="files-table-container" *ngIf="filesTableExpanded()">
              <div class="files-toolbar">
                <div class="files-search-wrap">
                  <app-glass-icon name="search" [size]="13" class="files-search-icon"></app-glass-icon>
                  <input class="files-search-input" type="text" placeholder="Filter by lot, wafer or filename…"
                    [value]="filesSearchText()" (input)="onFilesSearchChange($event)" />
                </div>
                <div class="files-toolbar-right">
                  <select class="files-status-filter" [value]="filesStatusFilter()" (change)="onFilesStatusFilterChange($event)">
                    <option value="">All Status</option>
                    <option value="DONE">Done</option>
                    <option value="ENQUEUED">Enqueued</option>
                    <option value="FAILED">Failed</option>
                    <option value="CANCELLED">Cancelled</option>
                    <option value="READY">Ready</option>
                  </select>
                  <span class="files-showing">{{ filesPageStart() + 1 }}–{{ filesPageEnd() }} of {{ filteredFiles().length }}{{ filteredFiles().length < files().length ? ' filtered' : '' }}</span>
                </div>
              </div>

              <div class="files-table-scroll">
                <table class="files-table" role="table" aria-label="Session files">
                  <thead>
                    <tr>
                      <th class="ft-col-lot">Lot</th>
                      <th class="ft-col-wafer">Wafer</th>
                      <th class="ft-col-filename">Filename</th>
                      <th class="ft-col-status">Status</th>
                      <th class="ft-col-date">Created</th>
                      <th class="ft-col-date">End Time</th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr *ngFor="let f of pagedFiles(); let odd = odd" [class.ft-row-odd]="odd">
                      <td class="ft-col-lot ft-mono">{{ f.lot || '—' }}</td>
                      <td class="ft-col-wafer ft-mono">{{ f.wafer || '—' }}</td>
                      <td class="ft-col-filename">
                        <span class="ft-filename" [title]="f.filename">{{ f.filename }}</span>
                      </td>
                      <td class="ft-col-status">
                        <span class="status-badge" [class]="f.status.toLowerCase()">{{ f.status }}</span>
                      </td>
                      <td class="ft-col-date ft-date">{{ formatShortDate(f.createdAt) }}</td>
                      <td class="ft-col-date ft-date ft-endtime">{{ formatEndTime(f.endTime) }}</td>
                    </tr>
                    <tr *ngIf="filteredFiles().length === 0" class="ft-empty-row">
                      <td colspan="6" class="ft-empty-cell">No files match the current filter.</td>
                    </tr>
                  </tbody>
                </table>
              </div>

              <div class="ft-pagination" *ngIf="filesTotalPages() > 1">
                <button class="ft-page-btn" [disabled]="filesPage() === 0" (click)="filesPage.set(0)">«</button>
                <button class="ft-page-btn" [disabled]="filesPage() === 0" (click)="filesPrevPage()">‹ Prev</button>
                <span class="ft-page-info">Page <strong>{{ filesPage() + 1 }}</strong> of <strong>{{ filesTotalPages() }}</strong></span>
                <button class="ft-page-btn" [disabled]="filesPage() >= filesTotalPages() - 1" (click)="filesNextPage()">Next ›</button>
                <button class="ft-page-btn" [disabled]="filesPage() >= filesTotalPages() - 1" (click)="filesPage.set(filesTotalPages() - 1)">»</button>
              </div>
            </div>
          </div>
          </div><!-- /.modal-body -->
        </div>
      </div>
    </div>
  `,
    styles: [`
    .container { padding: 1.25rem 1.5rem; display: flex; flex-direction: column; gap: 0.875rem; position: relative; }
    .page-header { display: flex; align-items: center; justify-content: space-between; gap: 1rem; flex-wrap: wrap; padding-bottom: 0.125rem; }
    .page-header-left { display: flex; align-items: center; gap: 0.5rem; }
    .page-title { font-size: 1.05rem; font-weight: 700; color: rgba(226, 232, 255, 0.95); line-height: 1; }
    .page-subtitle { font-size: 0.78rem; color: var(--text-muted); }
    :host-context(body.light-theme) .page-title { color: #1e293b; }
    :host-context(body.light-theme) .page-subtitle { color: #64748b; }
    .table-wrap { overflow: auto; border-radius: 14px; border: 1px solid rgba(167, 139, 250, 0.18); background: rgba(22, 16, 52, 0.45); backdrop-filter: blur(8px); }
    .session-filters { display: flex; flex-wrap: wrap; gap: 0.5rem; align-items: center; padding: 0.75rem 1rem; background: rgba(30, 22, 68, 0.5); border: 1px solid rgba(167, 139, 250, 0.16); border-radius: 14px; }
    .filter-search-wrap { position: relative; display: flex; align-items: center; }
    .filter-search-icon { position: absolute; left: 0.6rem; pointer-events: none; opacity: 0.55; }
    .filter-input, .filter-select { height: 34px; border-radius: 10px; border: 1px solid rgba(167, 139, 250, 0.24); background: rgba(20, 16, 44, 0.65) url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='10' height='6' viewBox='0 0 10 6'%3E%3Cpath d='M1 1l4 4 4-4' stroke='%23a78bfa' stroke-width='1.5' fill='none' stroke-linecap='round' stroke-linejoin='round'/%3E%3C/svg%3E") no-repeat right 0.6rem center; color: rgba(226, 232, 255, 0.94); padding: 0 2rem 0 0.6rem; min-width: 140px; font-size: 0.82rem; appearance: none; -webkit-appearance: none; cursor: pointer; transition: border-color 0.18s ease, box-shadow 0.18s ease; }
    .filter-input { background-image: none; padding-right: 0.6rem; cursor: text; }
    .filter-input--search { padding-left: 2rem; min-width: 200px; }
    .filter-select:focus, .filter-input:focus { outline: none; border-color: rgba(167, 139, 250, 0.5); box-shadow: 0 0 0 2px rgba(129, 140, 248, 0.15); }
    .filter-select option { background: #1a1240; color: rgba(226, 232, 255, 0.94); }
    .filter-actions { display: flex; gap: 0.4rem; margin-left: auto; }
    .table-meta-bar { display: flex; align-items: center; justify-content: space-between; padding: 0 0.25rem; }
    .table-count { font-size: 0.8rem; color: var(--text-muted); font-weight: 500; }
    .table-meta-right { display: flex; align-items: center; gap: 0.75rem; }
    .page-size-label { display: flex; align-items: center; gap: 0.4rem; font-size: 0.78rem; color: var(--text-muted); }
    .page-size-select { height: 28px; border-radius: 8px; border: 1px solid rgba(167, 139, 250, 0.22); background: rgba(20, 16, 44, 0.65) url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='10' height='6' viewBox='0 0 10 6'%3E%3Cpath d='M1 1l4 4 4-4' stroke='%23a78bfa' stroke-width='1.5' fill='none' stroke-linecap='round' stroke-linejoin='round'/%3E%3C/svg%3E") no-repeat right 0.45rem center; color: rgba(226, 232, 255, 0.9); padding: 0 1.6rem 0 0.4rem; font-size: 0.78rem; appearance: none; -webkit-appearance: none; cursor: pointer; transition: border-color 0.18s ease, box-shadow 0.18s ease; }
    .page-size-select:focus { outline: none; border-color: rgba(167, 139, 250, 0.5); box-shadow: 0 0 0 2px rgba(129, 140, 248, 0.15); }
    .page-size-select option { background: #1a1240; color: rgba(226, 232, 255, 0.94); }
    .hub-table { width: 100%; border-collapse: collapse; font-size: 0.84rem; }
    .hub-table th { padding: 0.65rem 0.75rem; text-align: left; font-size: 0.72rem; font-weight: 700; text-transform: uppercase; letter-spacing: 0.06em; color: rgba(167, 139, 250, 0.85); background: rgba(49, 35, 98, 0.55); border-bottom: 1px solid rgba(167, 139, 250, 0.2); white-space: nowrap; }
    .hub-table th:first-child { border-radius: 14px 0 0 0; }
    .hub-table th:last-child { border-radius: 0 14px 0 0; }
    .hub-table td { padding: 0.6rem 0.75rem; text-align: left; border-bottom: 1px solid rgba(255,255,255,0.05); vertical-align: middle; }
    .hub-table tbody tr {
      cursor: pointer;
      transition: all 0.2s cubic-bezier(0.4, 0, 0.2, 1);
    }
    .hub-table tbody tr:last-child td { border-bottom: none; }
    .hub-table tbody tr:hover {
      background: linear-gradient(135deg, rgba(129, 140, 248, 0.14) 0%, rgba(56, 189, 248, 0.07) 100%);
    }
    .hub-table tbody tr.active { background: rgba(129, 140, 248, 0.1); border-left: 3px solid rgba(129, 140, 248, 0.6); }
    .hub-table tbody tr.active td:first-child { padding-left: calc(0.75rem - 3px); }
    .col-num { text-align: right; width: 60px; }
    .col-progress { width: 140px; }
    .date-cell { white-space: nowrap; font-size: 0.78rem; color: var(--text-muted); }
    .progress-wrap { display: flex; align-items: center; gap: 0.5rem; }
    .progress-bar-track { flex: 1; height: 6px; border-radius: 999px; background: rgba(100, 116, 139, 0.25); overflow: hidden; }
    .progress-bar-fill { height: 100%; border-radius: 999px; transition: width 0.4s ease; }
    .progress-bar-fill.fill-done { background: linear-gradient(90deg, #10b981, #34d399); }
    .progress-bar-fill.fill-failed { background: linear-gradient(90deg, #ef4444, #f87171); }
    .progress-bar-fill.fill-active { background: linear-gradient(90deg, #818cf8, #38bdf8); }
    .progress-pct { font-size: 0.75rem; font-weight: 600; color: var(--text-muted); min-width: 32px; text-align: right; }
    .session-id-cell { font-family: 'JetBrains Mono', 'Fira Code', monospace; font-size: 0.78rem; color: rgba(167, 139, 250, 0.9); }
    .site-cell { font-weight: 600; font-size: 0.82rem; }
    .empty-row td { padding: 0; border: none; }
    .empty-cell { padding: 3rem 1rem !important; }
    .empty-state { display: flex; flex-direction: column; align-items: center; gap: 0.75rem; color: var(--text-muted); font-size: 0.88rem; opacity: 0.7; }
    .mono { font-family: monospace; }
    .status-badge { padding: 0.2rem 0.55rem; border-radius: 999px; font-size: 0.72rem; font-weight: 700; letter-spacing: 0.03em; white-space: nowrap; }
    .status-badge.completed { background: rgba(16,185,129,.18); color: #10b981; border: 1px solid rgba(16,185,129,.25); }
    .status-badge.monitoring, .status-badge.dispatching, .status-badge.staging { background: rgba(245,158,11,.16); color: #f59e0b; border: 1px solid rgba(245,158,11,.22); }
    .status-badge.partially_failed, .status-badge.cancelled { background: rgba(239,68,68,.18); color: #ef4444; border: 1px solid rgba(239,68,68,.22); }
    .status-badge.ready { background: rgba(129,140,248,.16); color: #a5b4fc; border: 1px solid rgba(129,140,248,.22); }
    .status-badge.enqueued { background: rgba(245,158,11,.16); color: #f59e0b; border: 1px solid rgba(245,158,11,.22); }
    .status-badge.processing { background: rgba(56,189,248,.16); color: #38bdf8; border: 1px solid rgba(56,189,248,.22); }
    .status-badge.error { background: rgba(239,68,68,.18); color: #ef4444; border: 1px solid rgba(239,68,68,.22); }
    .status-badge.done { background: rgba(16,185,129,.18); color: #10b981; border: 1px solid rgba(16,185,129,.25); }
    .pagination { display: flex; align-items: center; gap: 0.5rem; justify-content: center; padding: 0.25rem 0; }
    .pagination-info { font-size: 0.82rem; color: var(--text-muted); padding: 0 0.5rem; }
    .pagination-info strong { color: rgba(226, 232, 255, 0.9); }
    .pagination-range { margin-left: 0.35rem; font-size: 0.75rem; opacity: 0.75; }
    .page-btn { display: inline-flex; align-items: center; gap: 0.25rem; height: 32px; padding: 0 0.65rem; border-radius: 8px; border: 1px solid rgba(167, 139, 250, 0.24); background: rgba(67, 56, 132, 0.35); color: rgba(226, 232, 255, 0.9); font-size: 0.8rem; font-weight: 600; cursor: pointer; transition: all 0.18s ease; white-space: nowrap; }
    .page-btn:hover:not(:disabled) { border-color: rgba(167, 139, 250, 0.5); background: rgba(99, 102, 241, 0.32); }
    .page-btn:disabled { opacity: 0.35; cursor: not-allowed; }
    :host-context(body.light-theme) .page-btn { border-color: rgba(99, 102, 241, 0.22); background: rgba(99, 102, 241, 0.08); color: #334155; }
    :host-context(body.light-theme) .page-btn:hover:not(:disabled) { border-color: rgba(99, 102, 241, 0.42); background: rgba(99, 102, 241, 0.16); }
    .filter-badge { margin-left: 0.5rem; padding: 0.25rem 0.6rem; border-radius: 999px; background: rgba(129, 140, 248, 0.2); color: #818cf8; font-size: 0.85rem; font-weight: 500; }
    .sender-cell { display: flex; flex-direction: column; gap: 0.2rem; }
    .table-actions-cell { width: 1%; white-space: nowrap; }
    .session-user { font-weight: 600; color: var(--text-main); }
    .sender-id { font-weight: 700; font-size: 0.85rem; }
    .sender-name { font-size: 0.75rem; color: var(--text-muted); }
    :host-context(body.light-theme) .filter-badge { background: rgba(79, 70, 229, 0.12); color: #4f46e5; }
    :host-context(body.light-theme) .filter-input, :host-context(body.light-theme) .filter-select, :host-context(body.light-theme) .page-size-select { border: 1px solid rgba(99, 102, 241, 0.2); background-color: rgba(255, 255, 255, 0.88); color: #1e293b; }
    :host-context(body.light-theme) .filter-select, :host-context(body.light-theme) .page-size-select { background-image: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='10' height='6' viewBox='0 0 10 6'%3E%3Cpath d='M1 1l4 4 4-4' stroke='%234f46e5' stroke-width='1.5' fill='none' stroke-linecap='round' stroke-linejoin='round'/%3E%3C/svg%3E"); }
    :host-context(body.light-theme) .filter-select option, :host-context(body.light-theme) .page-size-select option { background: #ffffff; color: #1e293b; }
    :host-context(body.light-theme) .filter-input::placeholder { color: #64748b; }
    :host-context(body.light-theme) .session-filters { background: rgba(240, 238, 255, 0.6); border-color: rgba(99, 102, 241, 0.18); }
    :host-context(body.light-theme) .table-wrap { border-color: rgba(99, 102, 241, 0.18); background: rgba(255, 255, 255, 0.6); }
    :host-context(body.light-theme) .hub-table th { background: rgba(99, 102, 241, 0.08); color: #4f46e5; border-bottom: 1px solid rgba(99, 102, 241, 0.15); }
    :host-context(body.light-theme) .hub-table td { border-bottom: 1px solid rgba(0, 0, 0, 0.06); color: var(--text-main); }
    :host-context(body.light-theme) .hub-table tbody tr:hover { background: linear-gradient(135deg, rgba(79, 70, 229, 0.08) 0%, rgba(14, 165, 233, 0.05) 100%); }
    :host-context(body.light-theme) .hub-table tbody tr.active { background: rgba(79, 70, 229, 0.07); border-left-color: rgba(79, 70, 229, 0.5); }
    :host-context(body.light-theme) .session-id-cell { color: #4f46e5; }
    :host-context(body.light-theme) .date-cell { color: #64748b; }
    :host-context(body.light-theme) .progress-pct { color: #64748b; }
    :host-context(body.light-theme) .pagination-info { color: #64748b; }
    :host-context(body.light-theme) .pagination-info strong { color: #1e293b; }
    :host-context(body.light-theme) .table-count { color: #64748b; }
    :host-context(body.light-theme) .page-size-label { color: #64748b; }
    :host-context(body.light-theme) .status-badge.completed { background: rgba(16, 185, 129, 0.12); color: #059669; border-color: rgba(16, 185, 129, 0.2); }
    :host-context(body.light-theme) .status-badge.monitoring, :host-context(body.light-theme) .status-badge.dispatching, :host-context(body.light-theme) .status-badge.staging { background: rgba(245, 158, 11, 0.12); color: #d97706; border-color: rgba(245, 158, 11, 0.2); }
    :host-context(body.light-theme) .status-badge.partially_failed, :host-context(body.light-theme) .status-badge.cancelled { background: rgba(239, 68, 68, 0.12); color: #dc2626; border-color: rgba(239, 68, 68, 0.2); }
    :host-context(body.light-theme) .status-badge.ready { background: rgba(99,102,241,.1); color: #4f46e5; border-color: rgba(99,102,241,.18); }
    :host-context(body.light-theme) .status-badge.enqueued { background: rgba(217,119,6,.1); color: #d97706; border-color: rgba(217,119,6,.18); }
    :host-context(body.light-theme) .status-badge.processing { background: rgba(2,132,199,.1); color: #0284c7; border-color: rgba(2,132,199,.18); }
    :host-context(body.light-theme) .status-badge.error { background: rgba(220,38,38,.1); color: #dc2626; border-color: rgba(220,38,38,.18); }
    :host-context(body.light-theme) .status-badge.done { background: rgba(16,185,129,.12); color: #059669; border-color: rgba(16,185,129,.2); }
    .date-range { display: none; }
    .coverage-banner { display: flex; align-items: flex-start; gap: 0.875rem; padding: 0.875rem 1rem; background: linear-gradient(135deg, rgba(56, 189, 248, 0.1) 0%, rgba(129, 140, 248, 0.12) 100%); border: 1px solid rgba(56, 189, 248, 0.28); border-radius: 12px; }
    .coverage-icon { flex-shrink: 0; color: #38bdf8; margin-top: 0.1rem; }
    .coverage-body { display: flex; flex-direction: column; gap: 0.3rem; min-width: 0; }
    .coverage-label { font-size: 0.7rem; font-weight: 700; text-transform: uppercase; letter-spacing: 0.07em; color: #38bdf8; }
    .coverage-range { display: flex; align-items: center; gap: 0.5rem; flex-wrap: wrap; }
    .coverage-date { font-size: 0.92rem; font-weight: 700; color: rgba(226, 232, 255, 0.95); }
    .coverage-date--from { color: #a5b4fc; }
    .coverage-date--to { color: #34d399; }
    .coverage-arrow { color: rgba(203, 213, 225, 0.5); font-size: 1rem; }
    .coverage-span { font-size: 0.78rem; color: rgba(203, 213, 225, 0.65); font-weight: 500; }
    .coverage-hint { font-size: 0.74rem; color: rgba(203, 213, 225, 0.6); line-height: 1.4; }
    :host-context(body.light-theme) .coverage-banner { background: linear-gradient(135deg, rgba(2, 132, 199, 0.07) 0%, rgba(79, 70, 229, 0.08) 100%); border-color: rgba(2, 132, 199, 0.25); }
    :host-context(body.light-theme) .coverage-icon { color: #0284c7; }
    :host-context(body.light-theme) .coverage-label { color: #0284c7; }
    :host-context(body.light-theme) .coverage-date { color: #1e293b; }
    :host-context(body.light-theme) .coverage-date--from { color: #4f46e5; }
    :host-context(body.light-theme) .coverage-date--to { color: #059669; }
    :host-context(body.light-theme) .coverage-arrow { color: #94a3b8; }
    :host-context(body.light-theme) .coverage-span { color: #64748b; }
    :host-context(body.light-theme) .coverage-hint { color: #64748b; }
    .charts-section { display: flex; flex-direction: column; gap: 0.5rem; }
    .section-toggle { width: 100%; height: 40px; border-radius: 12px; border: 1px solid rgba(167, 139, 250, 0.26); background: rgba(67, 56, 132, 0.35); color: rgba(226, 232, 255, 0.95); display: flex; align-items: center; justify-content: space-between; padding: 0 0.875rem; font-size: 0.82rem; font-weight: 600; cursor: pointer; transition: background 0.2s ease, border-color 0.2s ease; }
    .section-toggle:hover { border-color: rgba(167, 139, 250, 0.48); background: rgba(99, 102, 241, 0.28); }
    .toggle-label { text-align: left; }
    .toggle-chevron { display: inline-block; transition: transform 0.2s ease; font-size: 1rem; line-height: 1; }
    .section-toggle.expanded .toggle-chevron { transform: rotate(180deg); }
    .charts-panel { display: flex; flex-direction: column; gap: 0.7rem; padding: 0.7rem 0.75rem; border-radius: 12px; border: 1px solid rgba(167, 139, 250, 0.18); background: rgba(49, 35, 98, 0.22); }
    .charts-head { display: flex; flex-direction: column; gap: 0.2rem; }
    .charts-head h4 { margin: 0; font-size: 0.95rem; color: rgba(226, 232, 255, 0.95); font-weight: 700; }
    .charts-sub { font-size: 0.78rem; color: rgba(203, 213, 225, 0.82); }
    .analytics-toolbar { display: flex; align-items: flex-end; gap: 0.45rem; flex-wrap: wrap; margin-top: 0.25rem; padding: 0.6rem 0.75rem; border-radius: 10px; border: 1px solid rgba(167, 139, 250, 0.16); background: rgba(49, 35, 98, 0.28); }
    .toolbar-divider { width: 1px; height: 28px; background: rgba(167, 139, 250, 0.22); align-self: center; flex-shrink: 0; }
    .date-input-wrap { display: flex; flex-direction: column; gap: 0.18rem; font-size: 0.7rem; color: rgba(203, 213, 225, 0.86); }
    .date-input-row { display: flex; align-items: center; gap: 0.3rem; }
    .date-input-wrap input { height: 30px; padding: 0 0.45rem; border-radius: 8px; border: 1px solid rgba(167, 139, 250, 0.24); background: rgba(20, 16, 44, 0.56); color: rgba(226, 232, 255, 0.94); }
    .date-picker-btn { width: 30px; height: 30px; border-radius: 8px; border: 1px solid rgba(167, 139, 250, 0.24); background: rgba(67, 56, 132, 0.45); color: rgba(226, 232, 255, 0.95); cursor: pointer; }
    .date-picker-btn:hover { border-color: rgba(167, 139, 250, 0.5); background: rgba(99, 102, 241, 0.38); }
    .preset-btn { height: 28px; padding: 0 0.55rem; border-radius: 999px; border: 1px solid rgba(167, 139, 250, 0.24); background: rgba(67, 56, 132, 0.38); color: rgba(226, 232, 255, 0.92); font-size: 0.72rem; line-height: 1; cursor: pointer; transition: all 0.2s ease; }
    .preset-btn:hover { border-color: rgba(167, 139, 250, 0.48); background: rgba(99, 102, 241, 0.32); transform: translateY(-1px); }
    .status-summary { display: flex; flex-wrap: wrap; gap: 0.4rem; margin-top: 0.25rem; }
    .summary-pill { padding: 0.24rem 0.6rem; border-radius: 999px; font-size: 0.72rem; font-weight: 600; border: 1px solid transparent; }
    .summary-pill--completed { background: rgba(16, 185, 129, 0.18); border-color: rgba(16, 185, 129, 0.3); color: #10b981; }
    .summary-pill--failed { background: rgba(239, 68, 68, 0.18); border-color: rgba(239, 68, 68, 0.3); color: #ef4444; }
    .summary-pill--cancelled { background: rgba(239, 68, 68, 0.14); border-color: rgba(239, 68, 68, 0.24); color: #f87171; }
    .summary-pill--total { background: rgba(99, 102, 241, 0.18); border-color: rgba(99, 102, 241, 0.3); color: #818cf8; }
    .charts-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 0.75rem; }
    @media (max-width: 600px) { .charts-grid { grid-template-columns: 1fr; } }
    .chart-card { border: 1px solid rgba(167, 139, 250, 0.22); border-radius: 12px; background: rgba(22, 16, 52, 0.6); padding: 0.75rem; box-shadow: inset 0 1px 0 rgba(167, 139, 250, 0.08); }
    .chart-title { font-size: 0.78rem; font-weight: 700; color: rgba(226, 232, 255, 0.9); margin-bottom: 0.4rem; }
    .chart-empty { font-size: 0.82rem; color: rgba(203, 213, 225, 0.78); padding: 0.4rem 0; }
    .trend-chart { display: flex; flex-direction: column; gap: 0.42rem; max-height: 230px; overflow: auto; padding-right: 0.2rem; }
    .trend-row { display: grid; grid-template-columns: 76px 1fr 34px; gap: 0.45rem; align-items: center; }
    .trend-day { font-size: 0.72rem; color: rgba(203, 213, 225, 0.86); }
    .trend-total { font-size: 0.72rem; color: rgba(203, 213, 225, 0.86); text-align: right; }
    .trend-stack { display: flex; height: 10px; border-radius: 999px; overflow: hidden; background: rgba(100, 116, 139, 0.24); }
    .trend-seg { height: 100%; min-width: 0; }
    .heatmap-wrap { display: flex; flex-direction: column; gap: 0.32rem; max-height: 230px; overflow: auto; }
    .heatmap-head, .heatmap-row { display: grid; grid-template-columns: 92px repeat(7, minmax(26px, 1fr)); gap: 0.25rem; align-items: center; min-width: 520px; }
    .heatmap-head span { font-size: 0.65rem; color: rgba(203, 213, 225, 0.76); text-align: center; }
    .heatmap-label { font-size: 0.67rem; color: rgba(226, 232, 255, 0.9); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
    .heatmap-cell { height: 22px; border-radius: 6px; display: inline-flex; align-items: center; justify-content: center; font-size: 0.67rem; color: rgba(255, 255, 255, 0.95); }
    .heatmap-hint { font-size: 0.68rem; color: rgba(203, 213, 225, 0.78); text-align: right; padding-top: 0.15rem; }
    .detail-overlay { position: absolute !important; top: 0; right: 0; bottom: 0; left: 0; width: 100%; height: 100%; background: radial-gradient(1200px 460px at 50% 0%, rgba(79, 70, 229, 0.2), rgba(24, 18, 56, 0.68) 54%), rgba(20, 16, 48, 0.62); backdrop-filter: blur(8px) saturate(108%); -webkit-backdrop-filter: blur(8px) saturate(108%); display: flex; align-items: stretch; justify-content: stretch; z-index: 100; overflow: hidden; border-radius: inherit; }
    .detail-modal { width: 100%; height: 100%; min-width: 0; max-width: none; max-height: none; min-height: 0; overflow: hidden; display: flex; flex-direction: column; border-radius: inherit; border: none; background: linear-gradient(165deg, rgba(31, 23, 61, 0.97) 0%, rgba(22, 17, 52, 0.95) 100%); backdrop-filter: blur(14px) saturate(118%); -webkit-backdrop-filter: blur(14px) saturate(118%); box-shadow: none; }
    .files-section { display: flex; flex-direction: column; gap: 0; border-radius: 14px; border: 1px solid rgba(167, 139, 250, 0.2); overflow: hidden; }
    .files-section-header { display: flex; align-items: center; justify-content: space-between; padding: 0.7rem 1rem; background: rgba(49, 35, 98, 0.55); cursor: pointer; user-select: none; transition: background 0.18s ease; }
    .files-section-header:hover { background: rgba(67, 50, 120, 0.65); }
    .files-section-title { display: flex; align-items: center; gap: 0.5rem; font-size: 0.82rem; font-weight: 700; color: rgba(226, 232, 255, 0.95); }
    .files-count-badge { padding: 0.1rem 0.5rem; border-radius: 999px; background: rgba(129, 140, 248, 0.22); color: #a5b4fc; font-size: 0.72rem; font-weight: 700; }
    .files-section-right { display: flex; align-items: center; gap: 0.5rem; }
    .files-toggle-hint { font-size: 0.72rem; color: rgba(203, 213, 225, 0.5); }
    .files-table-container { display: flex; flex-direction: column; background: rgba(18, 12, 42, 0.6); max-height: 480px; overflow: hidden; }
    .files-toolbar { display: flex; align-items: center; gap: 0.6rem; padding: 0.6rem 0.875rem; border-bottom: 1px solid rgba(167, 139, 250, 0.12); flex-wrap: wrap; flex-shrink: 0; }
    .files-search-wrap { position: relative; display: flex; align-items: center; flex: 1; min-width: 180px; }
    .files-search-icon { position: absolute; left: 0.55rem; pointer-events: none; opacity: 0.45; }
    .files-search-input { width: 100%; height: 30px; padding: 0 0.6rem 0 1.8rem; border-radius: 8px; border: 1px solid rgba(167, 139, 250, 0.2); background: rgba(20, 16, 44, 0.55); color: rgba(226, 232, 255, 0.92); font-size: 0.78rem; }
    .files-search-input::placeholder { color: rgba(203, 213, 225, 0.4); }
    .files-toolbar-right { display: flex; align-items: center; gap: 0.6rem; flex-shrink: 0; }
    .files-status-filter { height: 30px; padding: 0 1.8rem 0 0.65rem; border-radius: 8px; border: 1px solid rgba(167, 139, 250, 0.24); background: rgba(20, 16, 44, 0.65) url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='10' height='6' viewBox='0 0 10 6'%3E%3Cpath d='M1 1l4 4 4-4' stroke='%23a78bfa' stroke-width='1.5' fill='none' stroke-linecap='round' stroke-linejoin='round'/%3E%3C/svg%3E") no-repeat right 0.55rem center; color: rgba(226, 232, 255, 0.9); font-size: 0.78rem; appearance: none; -webkit-appearance: none; cursor: pointer; }
    .files-status-filter:focus { outline: none; border-color: rgba(167, 139, 250, 0.5); box-shadow: 0 0 0 2px rgba(129, 140, 248, 0.15); }
    .files-showing { font-size: 0.74rem; color: rgba(203, 213, 225, 0.5); white-space: nowrap; }
    .files-table-scroll { overflow-y: auto; overflow-x: auto; flex: 1; min-height: 0; }
    .files-table { width: 100%; border-collapse: collapse; font-size: 0.82rem; }
    .files-table th { position: sticky; top: 0; z-index: 1; padding: 0.55rem 0.75rem; text-align: left; font-size: 0.68rem; font-weight: 700; text-transform: uppercase; letter-spacing: 0.07em; color: rgba(167, 139, 250, 0.8); background: rgba(35, 25, 75, 0.95); border-bottom: 1px solid rgba(167, 139, 250, 0.18); white-space: nowrap; backdrop-filter: blur(8px); }
    .files-table td { padding: 0.5rem 0.75rem; border-bottom: 1px solid rgba(255, 255, 255, 0.04); vertical-align: middle; }
    .files-table tbody tr:last-child td { border-bottom: none; }
    .files-table tbody tr:hover { background: rgba(129, 140, 248, 0.08); }
    .ft-row-odd { background: rgba(99, 102, 241, 0.04); }
    .ft-col-lot { width: 100px; }
    .ft-col-wafer { width: 80px; }
    .ft-col-filename { min-width: 0; }
    .ft-col-status { width: 100px; }
    .ft-col-date { width: 140px; white-space: nowrap; }
    .ft-mono { font-family: 'JetBrains Mono', 'Fira Code', monospace; font-size: 0.78rem; color: rgba(165, 180, 252, 0.9); }
    .ft-filename { display: block; max-width: 340px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-family: 'JetBrains Mono', 'Fira Code', monospace; font-size: 0.76rem; color: rgba(226, 232, 255, 0.85); }
    .ft-date { font-size: 0.76rem; color: rgba(203, 213, 225, 0.65); }
    .ft-endtime { color: rgba(52, 211, 153, 0.85); }
    .ft-empty-row td { padding: 1.5rem; text-align: center; color: rgba(203, 213, 225, 0.45); font-size: 0.82rem; }
    .ft-pagination { display: flex; align-items: center; gap: 0.4rem; justify-content: center; padding: 0.6rem 0.875rem; border-top: 1px solid rgba(167, 139, 250, 0.12); background: rgba(18, 12, 42, 0.4); }
    .ft-page-btn { height: 28px; padding: 0 0.6rem; border-radius: 7px; border: 1px solid rgba(167, 139, 250, 0.22); background: rgba(67, 56, 132, 0.35); color: rgba(226, 232, 255, 0.88); font-size: 0.78rem; font-weight: 600; cursor: pointer; transition: all 0.15s ease; }
    .ft-page-btn:hover:not(:disabled) { border-color: rgba(167, 139, 250, 0.48); background: rgba(99, 102, 241, 0.32); }
    .ft-page-btn:disabled { opacity: 0.3; cursor: not-allowed; }
    .ft-page-info { font-size: 0.78rem; color: rgba(203, 213, 225, 0.6); padding: 0 0.4rem; }
    .ft-page-info strong { color: rgba(226, 232, 255, 0.9); }
    :host-context(body.light-theme) .ft-pagination { border-top-color: rgba(99, 102, 241, 0.12); background: rgba(240, 238, 255, 0.4); }
    :host-context(body.light-theme) .ft-page-btn { border-color: rgba(99, 102, 241, 0.2); background: rgba(99, 102, 241, 0.08); color: #334155; }
    :host-context(body.light-theme) .ft-page-btn:hover:not(:disabled) { border-color: rgba(99, 102, 241, 0.4); background: rgba(99, 102, 241, 0.16); }
    :host-context(body.light-theme) .ft-page-info { color: #64748b; }
    :host-context(body.light-theme) .ft-page-info strong { color: #1e293b; }
    :host-context(body.light-theme) .files-section { border-color: rgba(99, 102, 241, 0.18); }
    :host-context(body.light-theme) .files-section-header { background: rgba(99, 102, 241, 0.1); }
    :host-context(body.light-theme) .files-section-header:hover { background: rgba(99, 102, 241, 0.16); }
    :host-context(body.light-theme) .files-section-title { color: #1e293b; }
    :host-context(body.light-theme) .files-count-badge { background: rgba(79, 70, 229, 0.12); color: #4f46e5; }
    :host-context(body.light-theme) .files-toggle-hint { color: #94a3b8; }
    :host-context(body.light-theme) .files-table-container { background: rgba(255, 255, 255, 0.7); }
    :host-context(body.light-theme) .files-toolbar { border-bottom-color: rgba(99, 102, 241, 0.12); }
    :host-context(body.light-theme) .files-search-input, :host-context(body.light-theme) .files-status-filter { border-color: rgba(99, 102, 241, 0.2); background: rgba(255, 255, 255, 0.9); color: #1e293b; }
    :host-context(body.light-theme) .files-status-filter { background-image: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='10' height='6' viewBox='0 0 10 6'%3E%3Cpath d='M1 1l4 4 4-4' stroke='%234f46e5' stroke-width='1.5' fill='none' stroke-linecap='round' stroke-linejoin='round'/%3E%3C/svg%3E"); background-repeat: no-repeat; background-position: right 0.55rem center; }
    :host-context(body.light-theme) .files-search-input::placeholder { color: #94a3b8; }
    :host-context(body.light-theme) .files-showing { color: #94a3b8; }
    :host-context(body.light-theme) .files-table th { background: rgba(240, 238, 255, 0.95); color: #4f46e5; border-bottom-color: rgba(99, 102, 241, 0.15); }
    :host-context(body.light-theme) .files-table td { border-bottom-color: rgba(0, 0, 0, 0.05); color: #1e293b; }
    :host-context(body.light-theme) .files-table tbody tr:hover { background: rgba(79, 70, 229, 0.05); }
    :host-context(body.light-theme) .ft-row-odd { background: rgba(99, 102, 241, 0.03); }
    :host-context(body.light-theme) .ft-mono { color: #4f46e5; }
    :host-context(body.light-theme) .ft-filename { color: #334155; }
    :host-context(body.light-theme) .ft-date { color: #64748b; }
    :host-context(body.light-theme) .ft-endtime { color: #059669; }
    :host-context(body.light-theme) .detail-modal { background: rgba(255, 255, 255, 0.97); border: none; backdrop-filter: blur(16px); -webkit-backdrop-filter: blur(16px); box-shadow: none; }
    :host-context(body.light-theme) .detail-overlay { background: radial-gradient(1100px 380px at 50% 0%, rgba(99, 102, 241, 0.16), rgba(15, 23, 42, 0.26) 58%), rgba(15, 23, 42, 0.34); }
    :host-context(body.light-theme) .section-toggle { border: 1px solid rgba(99, 102, 241, 0.2); background: rgba(99, 102, 241, 0.1); color: #334155; }
    :host-context(body.light-theme) .section-toggle:hover { border-color: rgba(99, 102, 241, 0.4); background: rgba(99, 102, 241, 0.16); }
    :host-context(body.light-theme) .toggle-chevron { color: #4f46e5; }
    :host-context(body.light-theme) .detail-table-wrap { background: rgba(99, 102, 241, 0.06); border: 1px solid rgba(99, 102, 241, 0.18); }
    :host-context(body.light-theme) .detail-table-wrap .hub-table th { background: rgba(99, 102, 241, 0.08); border-bottom: 1px solid rgba(99, 102, 241, 0.12); }
    :host-context(body.light-theme) .detail-table-wrap .hub-table tbody tr:nth-child(even) { background: rgba(99, 102, 241, 0.04); }
    :host-context(body.light-theme) .charts-panel { border: 1px solid rgba(99, 102, 241, 0.18); background: rgba(99, 102, 241, 0.06); }
    :host-context(body.light-theme) .charts-head h4 { color: #1e293b; }
    :host-context(body.light-theme) .charts-sub { color: #64748b; }
    :host-context(body.light-theme) .analytics-toolbar { border: 1px solid rgba(99, 102, 241, 0.18); background: rgba(99, 102, 241, 0.06); }
    :host-context(body.light-theme) .toolbar-divider { background: rgba(99, 102, 241, 0.2); }
    :host-context(body.light-theme) .date-input-wrap { color: #64748b; }
    :host-context(body.light-theme) .date-input-wrap input { border: 1px solid rgba(99, 102, 241, 0.22); background: rgba(255, 255, 255, 0.88); color: #1e293b; }
    :host-context(body.light-theme) .date-picker-btn { border: 1px solid rgba(99, 102, 241, 0.22); background: rgba(99, 102, 241, 0.1); color: #334155; }
    :host-context(body.light-theme) .date-picker-btn:hover { border-color: rgba(99, 102, 241, 0.42); background: rgba(99, 102, 241, 0.18); }
    :host-context(body.light-theme) .preset-btn { border: 1px solid rgba(99, 102, 241, 0.22); background: rgba(99, 102, 241, 0.08); color: #334155; }
    :host-context(body.light-theme) .preset-btn:hover { border-color: rgba(99, 102, 241, 0.42); background: rgba(99, 102, 241, 0.16); }
    :host-context(body.light-theme) .summary-pill--completed { background: rgba(5, 150, 105, 0.12); border-color: rgba(5, 150, 105, 0.25); color: #059669; }
    :host-context(body.light-theme) .summary-pill--failed { background: rgba(220, 38, 38, 0.1); border-color: rgba(220, 38, 38, 0.22); color: #dc2626; }
    :host-context(body.light-theme) .summary-pill--cancelled { background: rgba(220, 38, 38, 0.08); border-color: rgba(220, 38, 38, 0.18); color: #ef4444; }
    :host-context(body.light-theme) .summary-pill--total { background: rgba(79, 70, 229, 0.1); border-color: rgba(79, 70, 229, 0.22); color: #4f46e5; }
    :host-context(body.light-theme) .chart-card { border: 1px solid rgba(99, 102, 241, 0.2); background: rgba(255, 255, 255, 0.82); box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.95); }
    :host-context(body.light-theme) .chart-title { color: #334155; }
    :host-context(body.light-theme) .chart-empty { color: #64748b; }
    :host-context(body.light-theme) .trend-day, :host-context(body.light-theme) .trend-total { color: #475569; }
    :host-context(body.light-theme) .trend-stack { background: rgba(148, 163, 184, 0.24); }
    :host-context(body.light-theme) .heatmap-head span { color: #64748b; }
    :host-context(body.light-theme) .heatmap-label { color: #334155; }
    :host-context(body.light-theme) .heatmap-cell { color: #ffffff; }
    :host-context(body.light-theme) .heatmap-hint { color: #64748b; }
    .detail-head { display: flex; justify-content: space-between; gap: 1rem; align-items: center; position: sticky; top: 0; z-index: 2; padding: 0.75rem 1.25rem; background: linear-gradient(to bottom, rgba(31,23,61,0.98), rgba(31,23,61,0.85)); backdrop-filter: blur(12px); border-bottom: 1px solid rgba(167,139,250,0.15); flex-shrink: 0; }
    .detail-head-left { display: flex; flex-direction: column; gap: 0.25rem; min-width: 0; }
    .detail-head h3 { font-size: 0.95rem; font-weight: 700; margin: 0; color: rgba(226, 232, 255, 0.95); }
    .head-meta { display: flex; align-items: center; gap: 0.5rem; flex-wrap: wrap; }
    .modal-body { flex: 1; overflow-y: auto; padding: 0.875rem 1.25rem 1rem; display: flex; flex-direction: column; gap: 0.65rem; }
    .head-updated { font-size: 0.82rem; color: var(--text-muted); }
    :host-context(body.light-theme) .head-updated { color: #64748b; }
    :host-context(body.light-theme) .detail-head h3 { color: var(--text-main); }
    .actions { display: flex; gap: 0.5rem; flex-wrap: wrap; justify-content: flex-end; align-items: center; }
    .metrics-cards { display: flex; gap: 0.75rem; flex-wrap: wrap; padding: 0.25rem 0; }
    .metric-card { flex: 1; min-width: 100px; padding: 0.875rem 1rem; border-radius: 14px; border: 1px solid rgba(255,255,255,0.06); display: flex; flex-direction: column; align-items: center; gap: 0.25rem; transition: transform 0.2s ease; }
    .metric-card:hover { transform: translateY(-2px); }
    .metric-value { font-size: 1.5rem; font-weight: 700; line-height: 1; }
    .metric-label { font-size: 0.72rem; font-weight: 500; opacity: 0.75; text-transform: uppercase; letter-spacing: 0.04em; }
    .metric-card--total { background: rgba(129,140,248,0.12); border-color: rgba(129,140,248,0.22); color: #a5b4fc; }
    .metric-card--staged { background: rgba(129,140,248,0.12); border-color: rgba(129,140,248,0.22); color: #a5b4fc; }
    .metric-card--enqueued { background: rgba(245,158,11,0.12); border-color: rgba(245,158,11,0.22); color: #f59e0b; }
    .metric-card--done { background: rgba(16,185,129,0.12); border-color: rgba(16,185,129,0.22); color: #10b981; }
    .metric-card--failed { background: rgba(239,68,68,0.12); border-color: rgba(239,68,68,0.22); color: #ef4444; }
    .metric-card--failed.zero { opacity: 0.45; }
    .status-row { display: flex; align-items: center; gap: 0.5rem; padding: 0.1rem 0 0.25rem; }
    :host-context(body.light-theme) .metric-card--total { background: rgba(99,102,241,0.08); border-color: rgba(99,102,241,0.2); color: #4f46e5; }
    :host-context(body.light-theme) .metric-card--staged { background: rgba(99,102,241,0.08); border-color: rgba(99,102,241,0.2); color: #4f46e5; }
    :host-context(body.light-theme) .metric-card--enqueued { background: rgba(217,119,6,0.08); border-color: rgba(217,119,6,0.2); color: #d97706; }
    :host-context(body.light-theme) .metric-card--done { background: rgba(5,150,105,0.08); border-color: rgba(5,150,105,0.2); color: #059669; }
    :host-context(body.light-theme) .metric-card--failed { background: rgba(220,38,38,0.08); border-color: rgba(220,38,38,0.2); color: #dc2626; }
    :host-context(body.light-theme) .metric-card { border-color: rgba(15,23,42,0.1); }
    :host-context(body.light-theme) .metric-label { color: #64748b; }
    .copy-id-btn { opacity: 0.92; }
    .cancel-soft { opacity: 0.82; filter: saturate(0.7); }
    .cancel-soft:hover { opacity: 0.94; filter: saturate(0.85); }
    :host-context(body.light-theme) .detail-head { background: linear-gradient(to bottom, rgba(255,255,255,0.98), rgba(255,255,255,0.85)); border-bottom: 1px solid rgba(99,102,241,0.15); }
    :host-context(body.light-theme) .detail-head h3 { color: #1e293b; }
    .trend-chart-container { min-height: 220px; padding: 0.5rem 0; width: 100%; }
    .status-chart-container { min-height: 220px; padding: 0.5rem 0; width: 100%; }
    .files tbody tr {
      cursor: default;
      transition: all 0.3s cubic-bezier(0.4, 0, 0.2, 1);
    }
    .files tbody tr:hover {
      transform: translateY(-4px);
      background: linear-gradient(135deg, rgba(129, 140, 248, 0.18) 0%, rgba(56, 189, 248, 0.1) 100%);
      box-shadow: 0 20px 40px rgba(0, 150, 255, 0.08), inset 0 1px 0 rgba(255, 255, 255, 0.1);
    }
    .files tbody tr:hover td { border-bottom-color: rgba(129, 140, 248, 0.18); }
    :host-context(body.light-theme) .files tbody tr:hover {
      transform: translateY(-4px);
      background: linear-gradient(135deg, rgba(79, 70, 229, 0.12) 0%, rgba(14, 165, 233, 0.08) 100%);
      box-shadow: 0 10px 28px rgba(79, 70, 229, 0.1), inset 0 1px 0 rgba(255, 255, 255, 0.9);
    }
    :host-context(body.light-theme) .files tbody tr:hover td { border-bottom-color: rgba(79, 70, 229, 0.16); }

    @media (max-width: 768px) {
      .detail-head { flex-direction: column; align-items: flex-start; padding: 1rem; }
      .actions { width: 100%; }
      .heatmap-head, .heatmap-row { grid-template-columns: 84px repeat(5, minmax(24px, 1fr)); }
      .detail-table-wrap { max-height: 50vh; }
    }
    @media (max-width: 640px) {
      .metrics-cards { display: grid; grid-template-columns: 1fr 1fr; }
      .actions { flex-direction: column; align-items: stretch; width: 100%; }
      .actions app-glass-button { width: 100%; }
    }
  `]
})
export class MySessionsComponent implements OnInit, OnDestroy {
  private readonly monitoringResumeStorageKey = 'exensioreload.activeMonitoringSessionId';
  @ViewChild('trendChartContainer', { static: false }) trendChartContainer?: ElementRef;
  @ViewChild('statusChartContainer', { static: false }) statusChartContainer?: ElementRef;
  private resizeObserver?: ResizeObserver;
  private resizeObserverSetup = false;
  private searchDebounceHandle: ReturnType<typeof setTimeout> | null = null;

    sessions = signal<StagingSessionSummary[]>([]);
    filterOptionSessions = signal<StagingSessionSummary[]>([]);
    selectedSession = signal<StagingSessionSummary | null>(null);
    selectedDetail = signal<StagingSessionDetail | null>(null);
    files = signal<StageRecordView[]>([]);
    loading = signal(false);
    filesTableExpanded = signal<boolean>(true);
    filesSearchText = signal<string>('');
    filesStatusFilter = signal<string>('');

    filteredFiles = computed(() => {
      const list = this.files();
      const q = this.filesSearchText().toLowerCase().trim();
      const status = this.filesStatusFilter().toUpperCase();
      return list.filter((f: StageRecordView) => {
        if (status && (f.status || '').toUpperCase() !== status) return false;
        if (!q) return true;
        return (f.lot || '').toLowerCase().includes(q)
          || (f.wafer || '').toLowerCase().includes(q)
          || (f.filename || '').toLowerCase().includes(q);
      });
    });

    filesPage = signal(0);
    filesPageSize = signal(25);
    filesTotalPages = computed(() => Math.max(1, Math.ceil(this.filteredFiles().length / this.filesPageSize())));
    filesPageStart = computed(() => this.filesPage() * this.filesPageSize());
    filesPageEnd = computed(() => Math.min(this.filesPageStart() + this.filesPageSize(), this.filteredFiles().length));
    pagedFiles = computed(() => this.filteredFiles().slice(this.filesPageStart(), this.filesPageEnd()));
    page = signal(0);
    size = signal(20);
    total = signal(0);
    siteFilter = signal<string | null>(null);
    copiedSessionId = signal<string | null>(null);
    activeMonitoringSessionId = signal<string | null>(null);
    sessionAnalytics = signal<SessionAnalyticsResponse | null>(null);
    chartsExpanded = signal<boolean>(true);
    analyticsStartDate = signal<string | null>(null);
    analyticsEndDate = signal<string | null>(null);
    searchText = signal<string>('');
    senderIdFilter = signal<number | null>(null);
    usernameFilter = signal<string>('');
    statusFilter = signal<string>('');
    showUserColumn = computed(() => this.auth.isAdmin() || this.auth.isSuperAdmin());
    senderFilterOptions = computed(() => {
      const map = new Map<number, string>();
      const source = this.filterOptionSessions().length > 0 ? this.filterOptionSessions() : this.sessions();
      source.forEach((s: StagingSessionSummary) => {
        if (typeof s.senderId === 'number') {
          map.set(s.senderId, s.senderName || '');
        }
      });
      return Array.from(map.entries())
        .sort((a: [number, string], b: [number, string]) => a[0] - b[0])
        .map(([id, name]: [number, string]) => ({ id, name }));
    });
    userFilterOptions = computed(() => {
      const source = this.filterOptionSessions().length > 0 ? this.filterOptionSessions() : this.sessions();
      const users = Array.from(new Set(
        source
          .map((s: StagingSessionSummary) => (s.username || '').trim())
          .filter((u: string) => u.length > 0)
      ));
      return (users as string[]).sort((a: string, b: string) => a.localeCompare(b));
    });
    private readonly statusOrder = ['DONE', 'ENQUEUED', 'FAILED', 'CANCELLED', 'NEW'];

    dailyStatusRows = computed(() => {
      const analytics = this.sessionAnalytics();
      const points = analytics?.dailyStatus || [];
      const statusColors: Record<string, string> = {
          DONE: '#10b981',
          ENQUEUED: '#f59e0b',
          FAILED: '#ef4444',
        CANCELLED: '#f97316',
          NEW: '#818cf8'
      };

      return points.map((p: SessionDailyStatusPoint) => {
        const dayMap = new Map<string, number>([
        ['DONE', p.done || 0],
        ['ENQUEUED', p.enqueued || 0],
        ['FAILED', p.failed || 0],
        ['CANCELLED', p.cancelled || 0],
        ['NEW', p.staged || 0]
        ]);
        const total = p.total || Array.from(dayMap.values()).reduce((a: number, b: number) => a + b, 0);
          const segments = this.statusOrder
              .filter((status: string) => (dayMap.get(status) || 0) > 0)
              .map((status: string) => {
                  const count = dayMap.get(status) || 0;
                  return {
                      status,
                      count,
                      percent: total > 0 ? (count / total) * 100 : 0,
                      color: statusColors[status] || '#64748b'
                  };
              });
          return { day: p.day, total, segments };
      });
    });

    lotWaferHeatmap = computed(() => {
      const analytics = this.sessionAnalytics();
      const points: SessionLotWaferDailyPoint[] = analytics?.lotWaferHeatmap || [];
      const pairs = Array.from(new Set(points.map((p: SessionLotWaferDailyPoint) => `${p.lot || '-'} / ${p.wafer || '-'}`)));
      const days = Array.from(new Set(points.map((p: SessionLotWaferDailyPoint) => p.day))).sort((a, b) => a.localeCompare(b));

      const pairIndex = new Map<string, number>(pairs.map((p: string, i: number) => [p, i]));
      const dayIndex = new Map<string, number>(days.map((d: string, i: number) => [d, i]));
      const dataMap = new Map<string, number>();

      points.forEach((p: SessionLotWaferDailyPoint) => {
        const day = p.day;
        const pair = `${p.lot || '-'} / ${p.wafer || '-'}`;
        if (!day || !dayIndex.has(day) || !pairIndex.has(pair)) return;
        const key = `${day}|${pair}`;
        dataMap.set(key, (dataMap.get(key) || 0) + (p.count || 0));
      });

      const matrix: Array<Record<string, number>> = [];
      pairs.forEach((pair: string) => {
        const row: Record<string, number> = {};
        days.forEach((d: string) => {
          row[d] = dataMap.get(`${d}|${pair}`) || 0;
        });
        matrix.push(row);
      });

      const values = matrix.flatMap((row: Record<string, number>) => Object.values(row));
      const max = Math.max(1, ...values);

      return { days, pairs, matrix, max };
    });

    analyticsStatusSummary = computed(() => {
      const points = this.sessionAnalytics()?.dailyStatus || [];
      const total = points.reduce((acc: number, p: SessionDailyStatusPoint) => acc + (p.total || 0), 0);
      const completed = points.reduce((acc: number, p: SessionDailyStatusPoint) => acc + (p.done || 0), 0);
      const failed = points.reduce((acc: number, p: SessionDailyStatusPoint) => acc + (p.failed || 0), 0);
      const cancelled = points.reduce((acc: number, p: SessionDailyStatusPoint) => acc + (p.cancelled || 0), 0);
      const pct = (value: number) => total > 0 ? Math.round((value / total) * 100) : 0;
      return {
        total,
        completedPct: pct(completed),
        failedPct: pct(failed),
        cancelledPct: pct(cancelled)
      };
    });

    fileCoverage = computed(() => {
      const filesList = this.files();
      if (!filesList.length) return null;
      const dayKeys = filesList
        .map((f: StageRecordView) => toUtcDayKey(f.endTime))
        .filter((d: string | null): d is string => !!d);
      if (!dayKeys.length) return null;
      const sorted = [...dayKeys].sort();
      const fromKey = sorted[0];
      const toKey = sorted[sorted.length - 1];
      const fromMs = Date.parse(`${fromKey}T00:00:00Z`);
      const toMs = Date.parse(`${toKey}T00:00:00Z`);
      const days = Number.isFinite(fromMs) && Number.isFinite(toMs)
        ? Math.round((toMs - fromMs) / 86400000)
        : 0;
      return {
        from: fromKey,
        to: toKey,
        days,
        fromLabel: formatUtcDateLabel(fromKey),
        toLabel: formatUtcDateLabel(toKey)
      };
    });

    constructor(private backend: BackendService, private route: ActivatedRoute, private auth: AuthService, private router: Router) {
    }

    ngOnInit() {
      this.refreshActiveMonitoringSession();
        // Check for site filter in query params
        this.route.queryParams.subscribe((params: any) => {
            const site = params['site'] || null;
            this.siteFilter.set(site);
            this.page.set(0);
            this.loading.set(true);
            this.loadSessions();
        });
    }

    private loadFilterOptionSessions(useActiveFilters = false) {
      // Derive filter options from already-loaded sessions — no extra API call needed
      // Only fetch a broader set when explicitly applying filters that might narrow the current page
      if (!useActiveFilters) {
        this.filterOptionSessions.set(this.sessions());
        return;
      }
      // When filters are active, use the current sessions page for options
      this.filterOptionSessions.set(this.sessions());
    }

    ngOnDestroy() {
      if (this.searchDebounceHandle) {
        clearTimeout(this.searchDebounceHandle);
        this.searchDebounceHandle = null;
      }
      if (this.resizeObserver) {
        this.resizeObserver.disconnect();
      }
      // Dispose ECharts instances
      if (this.trendChartContainer?.nativeElement) {
        echarts.dispose(this.trendChartContainer.nativeElement);
      }
      if (this.statusChartContainer?.nativeElement) {
        echarts.dispose(this.statusChartContainer.nativeElement);
      }
    }

    loadSessions() {
      this.backend.getStagingSessions(this.page(), this.size(), {
        q: this.searchText() || undefined,
        senderId: this.senderIdFilter() || undefined,
        username: this.showUserColumn() ? (this.usernameFilter() || undefined) : undefined,
        site: this.siteFilter() || undefined,
        status: this.statusFilter() || undefined
      }).subscribe((page: { items: StagingSessionSummary[]; total: number }) => {
            let filteredItems = (page.items || []);
            this.sessions.set(filteredItems);
            this.filterOptionSessions.set(filteredItems); // keep filter options in sync
            this.total.set(page.total || 0);
            this.loading.set(false);

            const activeId = this.activeMonitoringSessionId();
            if (!activeId) return;

            const activeSession = filteredItems.find((s: StagingSessionSummary) => s.sessionId === activeId);
            if (activeSession && this.isTerminal(activeSession.status)) {
                this.clearPersistedMonitoringSession();
            }
        });
    }

    onSearchTextChange(event: Event) {
      this.searchText.set(((event.target as HTMLInputElement)?.value || '').trim());
      this.page.set(0);
      this.scheduleSearchReload();
    }

    onSenderIdFilterChange(event: Event) {
      const raw = ((event.target as HTMLSelectElement)?.value || '').trim();
      const parsed = raw ? Number(raw) : null;
      this.senderIdFilter.set(parsed && parsed > 0 ? parsed : null);
    }

    onUsernameFilterChange(event: Event) {
      this.usernameFilter.set(((event.target as HTMLSelectElement)?.value || '').trim());
    }

    onStatusFilterChange(event: Event) {
      this.statusFilter.set(((event.target as HTMLSelectElement)?.value || '').trim());
    }

    applySessionFilters() {
      this.page.set(0);
      this.loading.set(true);
      this.loadSessions();
    }

    clearSessionFilters() {
      this.searchText.set('');
      this.senderIdFilter.set(null);
      this.usernameFilter.set('');
      this.statusFilter.set('');
      this.page.set(0);
      this.loading.set(true);
      this.loadSessions();
    }

    private scheduleSearchReload() {
      if (this.searchDebounceHandle) {
        clearTimeout(this.searchDebounceHandle);
      }
      this.searchDebounceHandle = setTimeout(() => {
        this.loading.set(true);
        this.loadSessions();
      }, 220);
    }

    canResumeMonitoring(session: StagingSessionSummary): boolean {
      const activeId = this.activeMonitoringSessionId();
      if (!activeId) return false;
      return activeId === session.sessionId && !this.isTerminal(session.status);
    }

    resumeSessionMonitoring(session: StagingSessionSummary) {
      if (!session?.sessionId || this.isTerminal(session.status)) {
        return;
      }
      this.persistMonitoringSession(session.sessionId);
      this.router.navigate(['/new']);
    }

    selectSession(session: StagingSessionSummary) {
        this.selectedSession.set(session);
        this.analyticsStartDate.set(null);
        this.analyticsEndDate.set(null);
        this.files.set([]);
        this.selectedDetail.set(null);

        const fileCount = Math.min(Math.max(session.totalFiles || 0, 50), 200);

        forkJoin({
          detail: this.backend.getStagingSession(session.sessionId),
          files: this.backend.getStagingSessionFiles(session.sessionId, 0, fileCount)
        }).subscribe({
          next: ({ detail, files }) => {
            this.selectedDetail.set(detail);
            this.files.set(files.items || []);
            setTimeout(() => this.renderStatusChart(), 0);
            this.loadSessionAnalytics(session.sessionId);
          },
          error: () => {
            this.files.set([]);
            this.loadSessionAnalytics(session.sessionId);
          }
        });
    }

    refreshSession(sessionId: string) {
      const fileCount = Math.min(Math.max(this.selectedDetail()?.totalFiles || 50, 50), 200);
      forkJoin({
        detail: this.backend.refreshStagingSession(sessionId),
        files: this.backend.getStagingSessionFiles(sessionId, 0, fileCount)
      }).subscribe({
        next: ({ detail, files }) => {
          this.selectedDetail.set(detail);
          this.files.set(files.items || []);
          setTimeout(() => this.renderStatusChart(), 0);
          this.loadSessionAnalytics(sessionId);
          // Update just this session row in the table without a full reload
          this.sessions.update(list =>
            list.map(s => s.sessionId === sessionId
              ? { ...s, status: detail.status, progress: detail.progress,
                  totalFiles: detail.totalFiles, filesDone: detail.filesDone,
                  filesFailed: detail.filesFailed, updatedAt: detail.updatedAt }
              : s)
          );
        },
        error: () => {}
      });
    }

    cancelSession(sessionId: string) {
        this.backend.cancelStagingSession(sessionId).subscribe(() => {
            this.refreshSession(sessionId);
        });
    }

    closeDetail() {
      if (this.trendChartContainer?.nativeElement) {
        echarts.dispose(this.trendChartContainer.nativeElement);
      }
      if (this.statusChartContainer?.nativeElement) {
        echarts.dispose(this.statusChartContainer.nativeElement);
      }
      this.selectedSession.set(null);
      this.selectedDetail.set(null);
      this.sessionAnalytics.set(null);
      this.chartsExpanded.set(true);
      this.analyticsStartDate.set(null);
      this.analyticsEndDate.set(null);
      this.files.set([]);
      this.filesTableExpanded.set(true);
      this.filesSearchText.set('');
      this.filesStatusFilter.set('');
      this.filesPage.set(0);
      this.resizeObserverSetup = false;
    }

    toggleFilesTable() {
      this.filesTableExpanded.update((v: boolean) => !v);
    }

    onFilesSearchChange(event: Event) {
      this.filesSearchText.set(((event.target as HTMLInputElement).value || '').trim());
      this.filesPage.set(0);
    }

    onFilesStatusFilterChange(event: Event) {
      this.filesStatusFilter.set(((event.target as HTMLSelectElement).value || '').trim());
      this.filesPage.set(0);
    }

    filesPrevPage() { if (this.filesPage() > 0) this.filesPage.update((v: number) => v - 1); }
    filesNextPage() { if (this.filesPage() < this.filesTotalPages() - 1) this.filesPage.update((v: number) => v + 1); }

    toggleChartsPanel() {
      this.chartsExpanded.update((v: boolean) => !v);
      if (this.chartsExpanded()) {
        setTimeout(() => this.renderCharts(), 0);
      }
    }

    onAnalyticsStartDateChange(event: Event) {
      const value = (event.target as HTMLInputElement)?.value || null;
      this.analyticsStartDate.set(value);
    }

    onAnalyticsEndDateChange(event: Event) {
      const value = (event.target as HTMLInputElement)?.value || null;
      this.analyticsEndDate.set(value);
    }

    applyAnalyticsDateRange() {
      const session = this.selectedSession();
      if (!session) return;
      this.loadSessionAnalytics(session.sessionId);
    }

    clearAnalyticsDateRange() {
      this.analyticsStartDate.set(null);
      this.analyticsEndDate.set(null);
      const session = this.selectedSession();
      if (!session) return;
      this.loadSessionAnalytics(session.sessionId);
    }

    applyQuickPreset(preset: 'today' | '7d' | '30d' | '90d' | 'month' | 'all') {
      const today = new Date();
      const toIsoDate = (d: Date) => {
        const y = d.getFullYear();
        const m = String(d.getMonth() + 1).padStart(2, '0');
        const day = String(d.getDate()).padStart(2, '0');
        return `${y}-${m}-${day}`;
      };

      if (preset === 'all') {
        this.analyticsStartDate.set(null);
        this.analyticsEndDate.set(null);
      } else if (preset === 'today') {
        const d = toIsoDate(today);
        this.analyticsStartDate.set(d);
        this.analyticsEndDate.set(d);
      } else if (preset === 'month') {
        const start = new Date(today.getFullYear(), today.getMonth(), 1);
        this.analyticsStartDate.set(toIsoDate(start));
        this.analyticsEndDate.set(toIsoDate(today));
      } else {
        const days = preset === '7d' ? 6 : preset === '30d' ? 29 : 89;
        const start = new Date(today);
        start.setDate(today.getDate() - days);
        this.analyticsStartDate.set(toIsoDate(start));
        this.analyticsEndDate.set(toIsoDate(today));
      }

      const session = this.selectedSession();
      if (!session) return;
      this.loadSessionAnalytics(session.sessionId);
    }

    private loadSessionAnalytics(sessionId: string) {
      this.backend.getStagingSessionAnalytics(
        sessionId,
        10,
        this.analyticsStartDate() || undefined,
        this.analyticsEndDate() || undefined
      ).subscribe({
        next: (analytics: SessionAnalyticsResponse) => {
          this.sessionAnalytics.set(analytics);
          // Defer chart rendering to avoid NG02100 errors
          setTimeout(() => this.renderCharts(), 0);
        },
        error: () => {
          this.sessionAnalytics.set(this.buildFallbackAnalytics(sessionId));
          // Defer chart rendering to avoid NG02100 errors
          setTimeout(() => this.renderCharts(), 0);
        }
      });
    }

    private buildFallbackAnalytics(sessionId: string): SessionAnalyticsResponse {
      const start = this.analyticsStartDate() ? new Date(`${this.analyticsStartDate()}T00:00:00`) : null;
      const end = this.analyticsEndDate() ? new Date(`${this.analyticsEndDate()}T23:59:59.999`) : null;

      const records = (this.files() || []).filter((f: StageRecordView) => {
        const raw = f.endTime || f.createdAt;
        if (!raw) return true;
        const d = new Date(raw);
        if (Number.isNaN(d.getTime())) return true;
        if (start && d < start) return false;
        if (end && d > end) return false;
        return true;
      });

      const byDay = new Map<string, { done: number; enqueued: number; failed: number; cancelled: number; staged: number; total: number }>();
      const pairTotals = new Map<string, number>();
      const heatKeyCount = new Map<string, number>();

      records.forEach((r: StageRecordView) => {
        const day = (r.endTime ? toUtcDayKey(r.endTime) : this.toDayKey(r.createdAt)) || 'unknown';
        const entry = byDay.get(day) || { done: 0, enqueued: 0, failed: 0, cancelled: 0, staged: 0, total: 0 };
        const normalized = this.normalizeStatus(r.status);
        if (normalized === 'DONE') entry.done += 1;
        else if (normalized === 'ENQUEUED') entry.enqueued += 1;
        else if (normalized === 'CANCELLED') entry.cancelled += 1;
        else if (normalized === 'FAILED') entry.failed += 1;
        else entry.staged += 1;
        entry.total += 1;
        byDay.set(day, entry);

        const lot = r.lot || '-';
        const wafer = r.wafer || '-';
        const pair = `${lot} / ${wafer}`;
        pairTotals.set(pair, (pairTotals.get(pair) || 0) + 1);
        heatKeyCount.set(`${day}|${lot}|${wafer}`, (heatKeyCount.get(`${day}|${lot}|${wafer}`) || 0) + 1);
      });

      const dailyStatus = Array.from(byDay.entries())
        .sort((a: [string, any], b: [string, any]) => a[0].localeCompare(b[0]))
        .map(([day, v]) => ({
          day,
          total: v.total,
          done: v.done,
          enqueued: v.enqueued,
          failed: v.failed,
          cancelled: v.cancelled,
          staged: v.staged
        } as SessionDailyStatusPoint));

      const topPairs = Array.from(pairTotals.entries())
        .sort((a: [string, number], b: [string, number]) => b[1] - a[1])
        .slice(0, 10)
        .map(([pair, total]) => {
          const [lot, wafer] = pair.split(' / ');
          return { lot, wafer, total };
        });

      const selectedPairs = new Set(topPairs.map((p: { lot: string; wafer: string }) => `${p.lot}|${p.wafer}`));
      const lotWaferHeatmap: SessionLotWaferDailyPoint[] = [];
      heatKeyCount.forEach((count: number, key: string) => {
        const [day, lot, wafer] = key.split('|');
        if (!selectedPairs.has(`${lot}|${wafer}`)) return;
        lotWaferHeatmap.push({ day, lot, wafer, count });
      });

      return {
        sessionId,
        dailyStatus,
        topLotWaferPairs: topPairs,
        lotWaferHeatmap
      };
    }

    private normalizeStatus(status?: string | null): 'DONE' | 'ENQUEUED' | 'FAILED' | 'CANCELLED' | 'NEW' {
      const value = (status || '').toUpperCase();
      if (value.includes('COMPLETE') || value === 'DONE') return 'DONE';
      if (value.includes('QUEUE') || value === 'ENQUEUED' || value === 'PROCESSING' || value === 'ENRICHMENT' || value === 'EXENSIO_LOADING') return 'ENQUEUED';
      if (value.includes('CANCEL')) return 'CANCELLED';
      if (value.includes('FAIL') || value === 'ERROR') return 'FAILED';
      return 'NEW';
    }

    private toDayKey(value?: string | null): string | null {
      return toUtcDayKey(value);
    }

    openDatePicker(input: HTMLInputElement) {
      if (!input) return;
      const anyInput = input as HTMLInputElement & { showPicker?: () => void };
      if (typeof anyInput.showPicker === 'function') {
        anyInput.showPicker();
        return;
      }
      input.focus();
      input.click();
    }

    totalPages = computed(() => Math.max(1, Math.ceil(this.total() / this.size())));
    paginationRangeStart = computed(() => this.total() === 0 ? 0 : this.page() * this.size() + 1);
    paginationRangeEnd = computed(() => Math.min((this.page() + 1) * this.size(), this.total()));

    getProgressClass(session: StagingSessionSummary): string {
      const s = (session.status || '').toUpperCase();
      if (s === 'COMPLETED') return 'fill-done';
      if (s === 'PARTIALLY_FAILED' || s === 'CANCELLED') return 'fill-failed';
      return 'fill-active';
    }

    onPageSizeChange(event: Event) {
      const val = Number((event.target as HTMLSelectElement).value);
      if (val > 0) {
        this.size.set(val);
        this.page.set(0);
        this.loadSessions();
      }
    }

    goToFirstPage() {
      if (this.page() > 0) {
        this.page.set(0);
        this.loadSessions();
      }
    }

    goToLastPage() {
      const last = this.totalPages() - 1;
      if (this.page() < last) {
        this.page.set(last);
        this.loadSessions();
      }
    }

    prevPage() {
        if (this.page() > 0) {
        this.page.update((v: number) => v - 1);
            this.loadSessions();
        }
    }

    nextPage() {
        if ((this.page() + 1) * this.size() < this.total()) {
        this.page.update((v: number) => v + 1);
            this.loadSessions();
        }
    }

    isTerminal(status: string): boolean {
        return ['COMPLETED', 'PARTIALLY_FAILED', 'CANCELLED'].includes((status || '').toUpperCase());
    }

    formatShortDate(value: unknown): string {
      if (value === null || value === undefined || value === '') return '-';
      const d = parseInstant(value);
      if (!d) return '-';
      return new Intl.DateTimeFormat(undefined, {
        year: 'numeric',
        month: 'numeric',
        day: 'numeric',
        hour: 'numeric',
        minute: '2-digit'
      }).format(d);
    }

    formatEndTime(value: unknown): string {
      return formatUtcDate(value, {
        year: 'numeric',
        month: 'numeric',
        day: 'numeric',
        hour: 'numeric',
        minute: '2-digit'
      });
    }

    truncateSessionId(sessionId: string): string {
      if (!sessionId) return '...';
      if (sessionId.length <= 8) return `${sessionId}...`;
      return `${sessionId.slice(0, 8)}...`;
    }

    copySessionId(sessionId: string) {
      if (!sessionId) return;
      const done = () => {
        this.copiedSessionId.set(sessionId);
        setTimeout(() => {
          if (this.copiedSessionId() === sessionId) this.copiedSessionId.set(null);
        }, 1500);
      };

      if (typeof navigator !== 'undefined' && navigator.clipboard?.writeText) {
        navigator.clipboard.writeText(sessionId).then(done).catch(() => this.copyViaTextarea(sessionId, done));
        return;
      }
      this.copyViaTextarea(sessionId, done);
    }

    getEarliestDate(): Date | null {
      const keys = this.files()
        .map((f: StageRecordView) => toUtcDayKey(f.endTime))
        .filter((k: string | null): k is string => !!k)
        .sort();
      if (!keys.length) return null;
      const parsed = parseInstant(`${keys[0]}T00:00:00Z`);
      return parsed;
    }

    getLatestDate(): Date | null {
      const keys = this.files()
        .map((f: StageRecordView) => toUtcDayKey(f.endTime))
        .filter((k: string | null): k is string => !!k)
        .sort();
      if (!keys.length) return null;
      const parsed = parseInstant(`${keys[keys.length - 1]}T00:00:00Z`);
      return parsed;
    }

      exportCurrentSessionFiles() {
        const session = this.selectedSession();
        if (!session || this.files().length === 0) return;
        const csv = this.buildFilesCsv(session, this.files());
        this.downloadCsv(csv, `session_${this.safeForFileName(session.sessionId)}_files.csv`);
      }

      exportSessionFiles(session: StagingSessionSummary) {
        this.backend.getStagingSessionFiles(session.sessionId, 0, 5000).subscribe((filePage: { items: StageRecordView[] }) => {
          const list = filePage.items || [];
          if (list.length === 0) return;
          const csv = this.buildFilesCsv(session, list);
          this.downloadCsv(csv, `session_${this.safeForFileName(session.sessionId)}_files.csv`);
        });
      }

      private buildFilesCsv(session: StagingSessionSummary, records: StageRecordView[]): string {
        const header = ['sessionId', 'site', 'senderId', 'senderName', 'lot', 'wafer', 'filename', 'status', 'createdAt', 'endTime'];
        const rows = records.map((r: StageRecordView) => ([
          session.sessionId,
          session.site,
          session.senderId,
          session.senderName || '',
          r.lot || '',
          r.wafer || '',
          r.filename || '',
          r.status || '',
          r.createdAt || '',
          r.endTime || ''
        ].map((v: string | number) => this.csvEscape(v)).join(',')));

        return [header.join(','), ...rows].join('\n');
      }

      private downloadCsv(content: string, filename: string) {
        const blob = new Blob([content], { type: 'text/csv;charset=utf-8;' });
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = filename;
        a.click();
        URL.revokeObjectURL(url);
      }

      private csvEscape(value: string | number): string {
        const normalized = String(value ?? '');
        return `"${normalized.replace(/"/g, '""')}"`;
      }

      private safeForFileName(value: string): string {
        return (value || 'session').replace(/[^a-zA-Z0-9_-]/g, '_');
      }

      private copyViaTextarea(value: string, onSuccess: () => void) {
        const textarea = document.createElement('textarea');
        textarea.value = value;
        textarea.style.position = 'fixed';
        textarea.style.left = '-9999px';
        document.body.appendChild(textarea);
        textarea.focus();
        textarea.select();
        try {
          document.execCommand('copy');
          onSuccess();
        } finally {
          document.body.removeChild(textarea);
        }
      }

      private renderCharts() {
        this.renderTrendChart();
        this.renderStatusChart();
        // Only set up ResizeObserver once
        if (!this.resizeObserverSetup) {
          this.setupResizeObserver();
          this.resizeObserverSetup = true;
        }
      }

      private setupResizeObserver() {
        if (typeof ResizeObserver === 'undefined') return;

        this.resizeObserver = new ResizeObserver(() => {
          if (this.trendChartContainer?.nativeElement) {
            const chart = echarts.getInstanceByDom(this.trendChartContainer.nativeElement);
            if (chart) chart.resize();
          }
          if (this.statusChartContainer?.nativeElement) {
            const chart = echarts.getInstanceByDom(this.statusChartContainer.nativeElement);
            if (chart) chart.resize();
          }
        });

        if (this.trendChartContainer?.nativeElement) {
          this.resizeObserver.observe(this.trendChartContainer.nativeElement);
        }
        if (this.statusChartContainer?.nativeElement) {
          this.resizeObserver.observe(this.statusChartContainer.nativeElement);
        }
      }

      private renderTrendChart() {
        if (!this.trendChartContainer?.nativeElement) return;

        const rows = this.dailyStatusRows();
        if (!rows || rows.length === 0) return;

        const days = rows.map((r: any) => r.day);
        const doneData = rows.map((r: any) => r.segments.find((s: any) => s.status === 'DONE')?.count || 0);
        const enqueuedData = rows.map((r: any) => r.segments.find((s: any) => s.status === 'ENQUEUED')?.count || 0);
        const failedData = rows.map((r: any) => r.segments.find((s: any) => s.status === 'FAILED')?.count || 0);
        const cancelledData = rows.map((r: any) => r.segments.find((s: any) => s.status === 'CANCELLED')?.count || 0);
        const stagedData = rows.map((r: any) => r.segments.find((s: any) => s.status === 'NEW')?.count || 0);

        const option = {
          textStyle: { color: '#cbd5e1', fontFamily: 'system-ui, -apple-system, sans-serif' },
          backgroundColor: 'transparent',
          tooltip: {
            trigger: 'axis',
            axisPointer: { type: 'shadow' },
            backgroundColor: 'rgba(15, 23, 42, 0.92)',
            borderColor: 'rgba(167, 139, 250, 0.3)',
            textStyle: { color: '#e2e8f0', fontSize: 12 },
            formatter: (params: any) => {
              const nonZero = params.filter((item: any) => item.value > 0);
              if (!nonZero.length) return '';
              const day = String(params[0]?.axisValue || '');
              const lotWaferLines = this.getTrendTooltipLotWaferLines(day);
              let result = `<div style="padding: 4px 8px; font-weight: 600; border-bottom: 1px solid rgba(167,139,250,0.2); margin-bottom: 4px;">End Time: ${this.escapeHtml(day)}</div>`;
              if (lotWaferLines.length > 0) {
                result += `<div style="padding: 0 8px 4px; color: #cbd5e1; font-size: 11px; line-height: 1.45; border-bottom: 1px solid rgba(167,139,250,0.12); margin-bottom: 4px;">${lotWaferLines.map((line: string) => this.escapeHtml(line)).join('<br/>')}</div>`;
              }
              nonZero.forEach((item: any) => {
                result += `<div style="color: ${item.color}; padding: 2px 8px;">${item.seriesName}: <strong>${item.value}</strong> files</div>`;
              });
              return result;
            }
          },
          legend: {
            data: ['Done', 'Enqueued', 'Failed', 'Cancelled', 'Staged'],
            bottom: '2%',
            textStyle: { color: '#cbd5e1', fontSize: 12 },
            itemGap: 16
          },
          grid: { left: '3%', right: '3%', bottom: '15%', top: '8%', containLabel: true },
          xAxis: {
            type: 'category',
            data: days,
            axisLine: { lineStyle: { color: 'rgba(167, 139, 250, 0.2)' } },
            axisLabel: { color: '#cbd5e1', fontSize: 11 },
            splitLine: { show: false }
          },
          yAxis: {
            type: 'value',
            axisLine: { lineStyle: { color: 'rgba(167, 139, 250, 0.2)' } },
            axisLabel: { color: '#cbd5e1', fontSize: 11 },
            splitLine: { lineStyle: { color: 'rgba(167, 139, 250, 0.15)' } }
          },
          series: [
            { name: 'Done', type: 'bar', data: doneData, itemStyle: { color: '#10b981' }, stack: 'status' },
            { name: 'Enqueued', type: 'bar', data: enqueuedData, itemStyle: { color: '#f59e0b' }, stack: 'status' },
            { name: 'Failed', type: 'bar', data: failedData, itemStyle: { color: '#ef4444' }, stack: 'status' },
            { name: 'Cancelled', type: 'bar', data: cancelledData, itemStyle: { color: '#f97316' }, stack: 'status' },
            { name: 'Staged', type: 'bar', data: stagedData, itemStyle: { color: '#818cf8', borderRadius: [4, 4, 0, 0] }, stack: 'status' }
          ]
        };

        const chart = echarts.getInstanceByDom(this.trendChartContainer!.nativeElement) || echarts.init(this.trendChartContainer!.nativeElement, null, { renderer: 'canvas' });
        chart.setOption(option);
      }

      private getTrendTooltipLotWaferLines(day: string): string[] {
        const rows = (this.files() || []).filter((file: StageRecordView) => {
          const fileDay = (file.endTime ? this.toDayKey(file.endTime) : this.toDayKey(file.createdAt)) || '';
          return fileDay === day;
        });

        const lines = rows
          .map((file: StageRecordView) => {
            const lot = this.normalizeTooltipValue(file.lot);
            const wafer = this.normalizeTooltipValue(file.wafer);
            if (!lot && !wafer) return '';
            if (lot && wafer) return `Lot: ${lot}, Wafer: ${wafer}`;
            if (lot) return `Lot: ${lot}`;
            return `Wafer: ${wafer}`;
          })
          .filter((line: string) => line.length > 0);

        return Array.from(new Set(lines)).slice(0, 3);
      }

      private normalizeTooltipValue(value?: string | null): string {
        const normalized = String(value ?? '').trim();
        if (!normalized) return '';
        const upper = normalized.toUpperCase();
        if (upper === 'NA' || upper === 'N/A' || upper === 'NAN' || normalized === '-') return '';
        return normalized;
      }

      private escapeHtml(value: string): string {
        return value
          .replace(/&/g, '&amp;')
          .replace(/</g, '&lt;')
          .replace(/>/g, '&gt;')
          .replace(/"/g, '&quot;')
          .replace(/'/g, '&#39;');
      }

      private renderStatusChart() {
        if (!this.statusChartContainer?.nativeElement) return;

        const detail = this.selectedDetail();
        const total = detail?.totalFiles ?? 0;
        if (total === 0) return;

        const completedCount = detail?.filesDone ?? 0;
        const failedCount = detail?.filesFailed ?? 0;
        const enqueuedCount = detail?.filesEnqueued ?? 0;
        const stagedCount = detail?.filesStaged ?? 0;
        const otherCount = Math.max(0, total - completedCount - failedCount - enqueuedCount - stagedCount);

        const data = [
          { value: completedCount, name: 'Completed', itemStyle: { color: '#10b981' } },
          { value: failedCount, name: 'Failed', itemStyle: { color: '#ef4444' } },
          { value: enqueuedCount, name: 'Enqueued', itemStyle: { color: '#f59e0b' } },
          { value: stagedCount, name: 'Staged', itemStyle: { color: '#818cf8' } },
          ...(otherCount > 0 ? [{ value: otherCount, name: 'Other', itemStyle: { color: '#64748b' } }] : [])
        ].filter((d: any) => d.value > 0);

        const option = {
          textStyle: { color: '#cbd5e1', fontFamily: 'system-ui, -apple-system, sans-serif' },
          backgroundColor: 'transparent',
          tooltip: {
            trigger: 'item',
            backgroundColor: 'rgba(15, 23, 42, 0.92)',
            borderColor: 'rgba(167, 139, 250, 0.3)',
            textStyle: { color: '#e2e8f0', fontSize: 12 },
            formatter: (params: any) => `<div style="padding: 4px 0;"><strong>${params.name}</strong><br/>${params.value} files (${params.percent}%)</div>`
          },
          legend: {
            data: data.map((d: any) => d.name),
            bottom: '3%',
            textStyle: { color: '#cbd5e1', fontSize: 12 },
            itemGap: 16
          },
          series: [
            {
              type: 'pie',
              radius: ['35%', '60%'],
              center: ['50%', '52%'],
              data: data,
              emphasis: {
                itemStyle: { shadowBlur: 10, shadowOffsetX: 0, shadowColor: 'rgba(129, 140, 248, 0.5)' }
              },
              label: {
                show: true,
                position: 'outside',
                color: '#cbd5e1',
                fontSize: 11,
                formatter: '{b}\n{d}%'
              },
              labelLine: { lineStyle: { color: 'rgba(167, 139, 250, 0.3)' } }
            }
          ]
        };

        const chart = echarts.getInstanceByDom(this.statusChartContainer!.nativeElement) || echarts.init(this.statusChartContainer!.nativeElement, null, { renderer: 'canvas' });
        chart.setOption(option);
      }

      heatColor(value: number, max: number): string {
        if (!max || value <= 0) return 'rgba(100, 116, 139, 0.2)';
        const t = Math.min(1, value / max);
        const alpha = 0.22 + (t * 0.68);
        return `rgba(129, 140, 248, ${alpha.toFixed(3)})`;
      }

      private refreshActiveMonitoringSession() {
        this.activeMonitoringSessionId.set(this.getPersistedMonitoringSession());
      }

      private persistMonitoringSession(sessionId: string) {
        try {
          sessionStorage.setItem(this.monitoringResumeStorageKey, sessionId);
          this.activeMonitoringSessionId.set(sessionId);
        } catch {
          // no-op
        }
      }

      private getPersistedMonitoringSession(): string | null {
        try {
          return sessionStorage.getItem(this.monitoringResumeStorageKey);
        } catch {
          return null;
        }
      }

      private clearPersistedMonitoringSession() {
        try {
          sessionStorage.removeItem(this.monitoringResumeStorageKey);
        } catch {
          // no-op
        }
        this.activeMonitoringSessionId.set(null);
      }
}
