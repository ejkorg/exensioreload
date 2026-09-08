import { ScrollingModule } from '@angular/cdk/scrolling';
import { CommonModule } from '@angular/common';
import { Component, OnDestroy, OnInit, computed, inject, signal } from '@angular/core';
import { GlassTooltipDirective } from '../directives/glass-tooltip.directive';
import { MonitoringFileItem, MonitoringPaginationService } from '../services/monitoring-pagination.service';
import { DualTimestampComponent } from './dual-timestamp.component';
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
    DualTimestampComponent,
  ],
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

          <div
            class="stream-status-indicator"
            [class.live]="state().streamStatus === 'live'"
            [class.polling]="state().streamStatus === 'polling'"
            [class.connecting]="state().streamStatus === 'connecting'"
            [glassTooltip]="streamStatusTooltip()"
          >
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
            (clicked)="refreshMonitoring()"
          >
            <app-glass-icon name="refresh" [size]="18"></app-glass-icon>
          </app-glass-button>
        </div>
      </div>

      <!-- ═══════════════════════════════════════════════════════ -->
      <!-- TIER 2: FILTERS & SEARCH                                -->
      <!-- ═══════════════════════════════════════════════════════ -->
      <div class="monitoring-filters-bar">
        <div class="filters-left">
          <button
            class="status-filter-pill"
            [class.active]="statusFilter() === 'all'"
            (click)="statusFilter.set('all')"
          >
            All <span class="pill-count">({{ allCount() }})</span>
          </button>

          <button
            class="status-filter-pill status-ready"
            [class.active]="statusFilter() === 'READY'"
            (click)="statusFilter.set('READY')"
            *ngIf="readyCount() > 0"
          >
            <app-glass-icon name="check" [size]="14"></app-glass-icon>
            Ready <span class="pill-count">({{ readyCount() }})</span>
          </button>

          <button
            class="status-filter-pill status-queued"
            [class.active]="statusFilter() === 'QUEUED_FOR_CP'"
            (click)="statusFilter.set('QUEUED_FOR_CP')"
            *ngIf="enqueuedCount() > 0"
          >
            <app-glass-icon name="schedule_send" [size]="14"></app-glass-icon>
            Queued <span class="pill-count">({{ enqueuedCount() }})</span>
          </button>

          <button
            class="status-filter-pill status-processing"
            [class.active]="statusFilter() === 'processing'"
            (click)="statusFilter.set('processing')"
            *ngIf="processingCount() > 0"
          >
            <app-glass-icon name="autorenew" [size]="14"></app-glass-icon>
            Processing <span class="pill-count">({{ processingCount() }})</span>
          </button>

          <button
            class="status-filter-pill status-completed"
            [class.active]="statusFilter() === 'COMPLETED'"
            (click)="statusFilter.set('COMPLETED')"
            *ngIf="completedCount() > 0"
          >
            <app-glass-icon name="check_circle" [size]="14"></app-glass-icon>
            Completed <span class="pill-count">({{ completedCount() }})</span>
          </button>

          <button
            class="status-filter-pill status-failed"
            [class.active]="statusFilter() === 'ERROR'"
            (click)="statusFilter.set('ERROR')"
            *ngIf="errorCount() > 0"
          >
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
            (valueChange)="searchText.set($event)"
          >
          </app-glass-input>

          <app-glass-button variant="secondary" size="small" [glassTooltip]="'Export to CSV'" (clicked)="exportCSV()">
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

        <cdk-virtual-scroll-viewport
          itemSize="64"
          class="table-viewport"
          (scrolledIndexChange)="onVirtualScrollIndexChange($event)"
        >
          <div class="table-row-wrapper" *cdkVirtualFor="let file of filteredFiles()">
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

            <!-- Expanded Details -->
            <div class="row-expanded" *ngIf="isExpanded(file) && (file.errorMessage || file.cpOutputPath)">
              <div class="error-details" *ngIf="file.errorMessage">
                <app-glass-icon name="error" [size]="16" color="error"></app-glass-icon>
                <div class="error-content">
                  <span
                    class="error-source-badge"
                    *ngIf="detectErrorSourceForDisplay(file) as src"
                    [class.source-cp]="src === 'CP'"
                    [class.source-exensio]="src === 'Exensio'"
                    >{{ src }}</span
                  >
                  <span class="error-message">{{ file.errorMessage }}</span>
                </div>
              </div>
              <div class="cp-output-details" *ngIf="file.cpOutputPath">
                <app-glass-icon name="folder" [size]="14" color="muted"></app-glass-icon>
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
            (clicked)="previousPage()"
          >
            <app-glass-icon name="chevron_left" [size]="16"></app-glass-icon>
            Previous
          </app-glass-button>
          <app-glass-button variant="secondary" size="small" [disabled]="!state().hasMore" (clicked)="nextPage()">
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

      .table-row-wrapper {
        border-bottom: 1px solid rgba(255, 255, 255, 0.03);
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
      // EXPANDED ROW DETAILS
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

      .col-updated {
        display: flex;
        align-items: center;
        justify-content: space-between;
        gap: 0.5rem;
        font-size: 0.875rem;
        color: var(--text-muted);
      }

      .text-muted {
        color: var(--text-muted);
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

      // ═══════════════════════════════════════════════════════
      // EMPTY & LOADING STATES
      // ═══════════════════════════════════════════════════════

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

      // ═══════════════════════════════════════════════════════
      // PAGINATION FOOTER
      // ═══════════════════════════════════════════════════════

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
  statusFilter = signal<'all' | 'READY' | 'QUEUED_FOR_CP' | 'processing' | 'COMPLETED' | 'ERROR'>('all');
  searchText = signal('');
  expandedFiles = signal<Set<number | string>>(new Set());

  // Expose service state for template
  state = this.paginationService.paginationState;

  // Computed: Count files by status
  allCount = this.countByStatus('all');
  readyCount = this.countByStatus('READY');
  enqueuedCount = this.countByStatus('QUEUED_FOR_CP');
  enrichmentCount = this.countByStatus('ELASTICSEARCH_MONITORING');
  exensioLoadingCount = this.countByStatus('EXENSIO_MONITORING');
  processingCount = this.countByStatus('ELASTICSEARCH_MONITORING'); // legacy alias
  completedCount = this.countByStatus('COMPLETED');
  manualVerificationCount = this.countByStatus('COMPLETED_MANUAL_VERIFICATION_REQUIRED');
  errorCount = this.countByStatus('ERROR');

  // Computed: Filter files by status and search
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

  Math = Math;

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

  ngOnInit(): void {
    // Load first page on init
    this.paginationService.loadFirstPage();
  }

  ngOnDestroy(): void {
    // Cleanup handled by service
  }

  // Helper: Count files by status
  private countByStatus(
    status:
      | 'all'
      | 'READY'
      | 'QUEUED_FOR_CP'
      | 'ELASTICSEARCH_MONITORING'
      | 'CP_TIMEOUT'
      | 'EXENSIO_MONITORING'
      | 'COMPLETED_MANUAL_VERIFICATION_REQUIRED'
      | 'COMPLETED'
      | 'ERROR'
      | 'CP_FAILED'
      | 'LOAD_FAILED'
      | 'CANCELLED',
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
        return 'schedule';
      case 'QUEUED_FOR_CP':
        return 'schedule_send';
      case 'ELASTICSEARCH_MONITORING':
        return 'hourglass_empty';
      case 'CP_TIMEOUT':
        return 'schedule';
      case 'EXENSIO_MONITORING':
        return 'cloud_upload';
      case 'COMPLETED_MANUAL_VERIFICATION_REQUIRED':
        return 'schedule';
      case 'PROCESSING':
        return 'hourglass_empty';
      case 'COMPLETED':
        return 'check_circle';
      case 'CP_FAILED':
      case 'LOAD_FAILED':
      case 'ERROR':
        return 'error';
      case 'CANCELLED':
        return 'cancel';
      default:
        return 'help';
    }
  }

  getStatusColor(status: string): 'primary' | 'error' | 'default' | 'success' | 'warning' | 'muted' {
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
    if (status === 'READY' || status === 'QUEUED_FOR_CP') {
      return 'Queued for Enrichment';
    }

    // File in ENRICHMENT: show "Enrichment: Processing" (Requirement 7.2)
    if (status === 'ELASTICSEARCH_MONITORING') {
      return 'Enrichment: Processing';
    }

    // File in ENRICHMENT_TIMEOUT: show timeout label
    if (status === 'CP_TIMEOUT') {
      return 'Enrichment: Monitoring Timeout — Exensio not configured';
    }

    // File in EXENSIO_LOADING: show "Enrichment: Done · Exensio: Monitoring" (Requirement 7.3)
    if (status === 'EXENSIO_MONITORING') {
      return 'Enrichment: Done · Exensio: Monitoring';
    }

    // File in EXENSIO_TIMEOUT: show verify label
    if (status === 'COMPLETED_MANUAL_VERIFICATION_REQUIRED') {
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

    if (status === 'ELASTICSEARCH_MONITORING') {
      return 'hourglass_empty';
    }

    if (status === 'EXENSIO_MONITORING') {
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
    if (status === 'READY' || status === 'QUEUED_FOR_CP') {
      return 'muted';
    }

    // Files in ENRICHMENT/EXENSIO_LOADING show status text - should be warning
    if (status === 'ELASTICSEARCH_MONITORING' || status === 'EXENSIO_MONITORING') {
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
