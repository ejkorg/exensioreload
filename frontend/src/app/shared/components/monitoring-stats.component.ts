import { CommonModule } from '@angular/common';
import { Component, Input } from '@angular/core';
import { IntegrationStatusSnapshot } from '../../api/backend.service';
import { MonitoringStats } from '../services/monitoring.service';
import { DualTimestampComponent } from './dual-timestamp.component';
import { GlassIconComponent } from './glass-icon.component';

@Component({
  selector: 'app-monitoring-stats',
  standalone: true,
  imports: [CommonModule, GlassIconComponent, DualTimestampComponent],
  template: `
    <div class="monitoring-stats">
      <!-- Overview Cards - 7 State Pipeline -->
      <div class="stats-grid">
        <div class="stat-card glass-panel">
          <div class="stat-icon total">
            <app-glass-icon name="dashboard" [size]="24" color="primary"></app-glass-icon>
          </div>
          <div class="stat-content">
            <div class="stat-value">{{ stats.total }}</div>
            <div class="stat-label">Total Files</div>
          </div>
        </div>

        <div class="stat-card glass-panel">
          <div class="stat-icon staged">
            <app-glass-icon name="upload" [size]="24" color="primary"></app-glass-icon>
          </div>
          <div class="stat-content">
            <div class="stat-value">{{ stats.ready }}</div>
            <div class="stat-label">Staged</div>
          </div>
        </div>

        <div class="stat-card glass-panel">
          <div class="stat-icon queued">
            <app-glass-icon name="clock" [size]="24" color="warning"></app-glass-icon>
          </div>
          <div class="stat-content">
            <div class="stat-value">{{ stats.enqueued }}</div>
            <div class="stat-label">Queued for Enrichment</div>
          </div>
        </div>

        <div class="stat-card glass-panel">
          <div class="stat-icon enriching">
            <app-glass-icon name="refresh" [size]="24" color="primary"></app-glass-icon>
          </div>
          <div class="stat-content">
            <div class="stat-value">{{ stats.enriching }}</div>
            <div class="stat-label">Enrichment Processing</div>
          </div>
        </div>

        <div class="stat-card glass-panel" *ngIf="stats.enrichmentTimeout > 0">
          <div class="stat-icon enrichment-timeout">
            <app-glass-icon name="schedule" [size]="24" color="warning"></app-glass-icon>
          </div>
          <div class="stat-content">
            <div class="stat-value">{{ stats.enrichmentTimeout }}</div>
            <div class="stat-label">Enrichment Monitoring Timeout</div>
          </div>
        </div>

        <div class="stat-card glass-panel">
          <div class="stat-icon exensio">
            <app-glass-icon name="cloud_upload" [size]="24" color="primary"></app-glass-icon>
          </div>
          <div class="stat-content">
            <div class="stat-value">{{ stats.exensioLoading }}</div>
            <div class="stat-label">Exensio Monitoring</div>
          </div>
        </div>

        <div class="stat-card glass-panel" *ngIf="stats.exensioTimeout > 0">
          <div class="stat-icon exensio-timeout">
            <app-glass-icon name="schedule" [size]="24" color="warning"></app-glass-icon>
          </div>
          <div class="stat-content">
            <div class="stat-value">{{ stats.exensioTimeout }}</div>
            <div class="stat-label">Completed — Verify in Exensio</div>
          </div>
        </div>

        <div class="stat-card glass-panel">
          <div class="stat-icon completed">
            <app-glass-icon name="check_circle" [size]="24" color="success"></app-glass-icon>
          </div>
          <div class="stat-content">
            <div class="stat-value">{{ stats.completed }}</div>
            <div class="stat-label">Completed</div>
          </div>
        </div>

        <div class="stat-card glass-panel" [class.has-errors]="stats.failed > 0">
          <div class="stat-icon failed">
            <app-glass-icon name="error" [size]="24" color="error"></app-glass-icon>
          </div>
          <div class="stat-content">
            <div class="stat-value">{{ stats.failed }}</div>
            <div class="stat-label">Failed</div>
          </div>
        </div>

        <div class="stat-card glass-panel" *ngIf="stats.cancelled > 0">
          <div class="stat-icon cancelled">
            <app-glass-icon name="cancel" [size]="24" color="warning"></app-glass-icon>
          </div>
          <div class="stat-content">
            <div class="stat-value">{{ stats.cancelled }}</div>
            <div class="stat-label">Cancelled</div>
          </div>
        </div>
      </div>

      <!-- Progress Bar -->
      <div class="progress-section glass-panel">
        <div class="progress-header">
          <span class="progress-label">Overall Progress</span>
          <span class="progress-percentage">{{ stats.progress }}%</span>
        </div>
        <div class="progress-bar">
          <div
            class="progress-fill"
            [style.width.%]="stats.progress"
            [class.complete]="stats.progress === 100"
            [class.has-errors]="stats.failed > 0"
          ></div>
        </div>
        <div class="progress-details">
          <span class="detail-item">
            <app-glass-icon name="clock" [size]="16" color="muted"></app-glass-icon>
            Elapsed: {{ stats.elapsedTime }}
          </span>
          <span class="detail-item" *ngIf="stats.progress < 100">
            <app-glass-icon name="calendar" [size]="16" color="muted"></app-glass-icon>
            ETA: {{ stats.eta }}
          </span>
          <span class="detail-item">
            <app-glass-icon name="dashboard" [size]="16" color="muted"></app-glass-icon>
            {{ stats.throughput }} files/min
          </span>
          <span
            class="detail-item"
            *ngIf="stats.completed + stats.failed > 0"
            [class.success]="stats.successRate >= 95"
            [class.warning]="stats.successRate < 95"
          >
            <app-glass-icon
              [name]="stats.successRate >= 95 ? 'check_circle' : 'warning'"
              [size]="16"
              [color]="stats.successRate >= 95 ? 'success' : 'warning'"
            ></app-glass-icon>
            {{ stats.successRate }}% success
          </span>
          <span class="detail-item" *ngIf="stats.completed + stats.failed === 0" class="muted">
            <app-glass-icon name="pending_actions" [size]="16" color="muted"></app-glass-icon>
            No results yet
          </span>
        </div>
      </div>

      <!-- Integration Status -->
      <div class="integration-status glass-panel" *ngIf="integrationItems().length > 0">
        <div class="integration-header">
          <app-glass-icon name="hub" [size]="18" color="primary"></app-glass-icon>
          <span>Integrations</span>
        </div>
        <div class="integration-grid">
          <div class="integration-row" *ngFor="let item of integrationItems()">
            <div class="integration-name-section">
              <app-glass-icon name="settings_ethernet" [size]="16" color="muted"></app-glass-icon>
              <span class="integration-name">{{ item.name }}</span>
            </div>
            <div class="integration-state" [ngClass]="item.statusClass">
              <app-glass-icon [name]="item.icon" [size]="14"></app-glass-icon>
              <span class="integration-label">{{ item.message }}</span>
            </div>
            <div class="integration-time" *ngIf="item.lastAt">
              <app-dual-timestamp [value]="item.lastAt"></app-dual-timestamp>
            </div>
            <div class="integration-time-placeholder" *ngIf="!item.lastAt">
              <span class="no-timestamp">—</span>
            </div>
          </div>
        </div>
      </div>

      <!-- Status Distribution -->
      <div class="status-distribution glass-panel">
        <div class="distribution-header">
          <span class="distribution-label">Status Distribution</span>
        </div>
        <div class="distribution-bars">
          <div class="distribution-bar" *ngIf="stats.completed > 0">
            <div class="bar-label">Completed</div>
            <div class="bar-track">
              <div class="bar-fill completed" [style.width.%]="getPercentage(stats.completed)"></div>
            </div>
            <div class="bar-value">{{ stats.completed }}</div>
          </div>
          <div class="distribution-bar" *ngIf="stats.enriching > 0">
            <div class="bar-label">Enrichment Processing</div>
            <div class="bar-track">
              <div class="bar-fill enriching" [style.width.%]="getPercentage(stats.enriching)"></div>
            </div>
            <div class="bar-value">{{ stats.enriching }}</div>
          </div>
          <div class="distribution-bar" *ngIf="stats.enrichmentTimeout > 0">
            <div class="bar-label">Enrichment Monitoring Timeout</div>
            <div class="bar-track">
              <div class="bar-fill enrichment-timeout" [style.width.%]="getPercentage(stats.enrichmentTimeout)"></div>
            </div>
            <div class="bar-value">{{ stats.enrichmentTimeout }}</div>
          </div>
          <div class="distribution-bar" *ngIf="stats.exensioLoading > 0">
            <div class="bar-label">Exensio Monitoring</div>
            <div class="bar-track">
              <div class="bar-fill exensio" [style.width.%]="getPercentage(stats.exensioLoading)"></div>
            </div>
            <div class="bar-value">{{ stats.exensioLoading }}</div>
          </div>
          <div class="distribution-bar" *ngIf="stats.exensioTimeout > 0">
            <div class="bar-label">Completed — Verify in Exensio</div>
            <div class="bar-track">
              <div class="bar-fill exensio-timeout" [style.width.%]="getPercentage(stats.exensioTimeout)"></div>
            </div>
            <div class="bar-value">{{ stats.exensioTimeout }}</div>
          </div>
          <div class="distribution-bar" *ngIf="stats.ready > 0">
            <div class="bar-label">Staged</div>
            <div class="bar-track">
              <div class="bar-fill staged" [style.width.%]="getPercentage(stats.ready)"></div>
            </div>
            <div class="bar-value">{{ stats.ready }}</div>
          </div>
          <div class="distribution-bar" *ngIf="stats.enqueued > 0">
            <div class="bar-label">Queued for Enrichment</div>
            <div class="bar-track">
              <div class="bar-fill queued" [style.width.%]="getPercentage(stats.enqueued)"></div>
            </div>
            <div class="bar-value">{{ stats.enqueued }}</div>
          </div>
          <div class="distribution-bar" *ngIf="stats.failed > 0">
            <div class="bar-label">Failed</div>
            <div class="bar-track">
              <div class="bar-fill failed" [style.width.%]="getPercentage(stats.failed)"></div>
            </div>
            <div class="bar-value">{{ stats.failed }}</div>
          </div>
          <div class="distribution-bar" *ngIf="stats.cancelled > 0">
            <div class="bar-label">Cancelled</div>
            <div class="bar-track">
              <div class="bar-fill cancelled" [style.width.%]="getPercentage(stats.cancelled)"></div>
            </div>
            <div class="bar-value">{{ stats.cancelled }}</div>
          </div>
        </div>
      </div>
    </div>
  `,
  styles: [
    `
      .monitoring-stats {
        display: flex;
        flex-direction: column;
        gap: 1.25rem;
      }

      .stats-grid {
        display: grid;
        grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
        gap: 1rem;
      }

      .stat-card {
        display: flex;
        align-items: center;
        gap: 0.75rem;
        padding: 0.75rem;
        transition: transform 0.2s ease;
      }

      .stat-card:hover {
        transform: translateY(-1px);
      }

      .stat-icon {
        width: 40px;
        height: 40px;
        border-radius: 10px;
        display: flex;
        align-items: center;
        justify-content: center;
        flex-shrink: 0;
      }

      .stat-icon.total {
        background: rgba(129, 140, 248, 0.15);
      }
      .stat-icon.staged {
        background: rgba(129, 140, 248, 0.15);
      }
      .stat-icon.queued {
        background: rgba(245, 158, 11, 0.15);
      }
      .stat-icon.enriching {
        background: rgba(129, 140, 248, 0.15);
      }
      .stat-icon.enrichment-timeout {
        background: rgba(245, 158, 11, 0.15);
      }
      .stat-icon.exensio {
        background: rgba(99, 102, 241, 0.15);
      }
      .stat-icon.exensio-timeout {
        background: rgba(245, 158, 11, 0.15);
      }
      .stat-icon.completed {
        background: rgba(16, 185, 129, 0.15);
      }
      .stat-icon.failed {
        background: rgba(239, 68, 68, 0.15);
      }
      .stat-icon.cancelled {
        background: rgba(245, 158, 11, 0.15);
      }

      .stat-content {
        flex: 1;
        min-width: 0;
      }

      .stat-value {
        font-size: 1.45rem;
        font-weight: 700;
        color: var(--text-main);
        line-height: 1;
        margin-bottom: 0.2rem;
      }

      .stat-label {
        font-size: 0.72rem;
        color: var(--text-muted);
        text-transform: uppercase;
        letter-spacing: 0.05em;
        font-weight: 600;
      }

      .progress-section {
        padding: 0.875rem 1rem;
      }

      .progress-header {
        display: flex;
        justify-content: space-between;
        align-items: center;
        margin-bottom: 0.75rem;
      }

      .progress-label {
        font-size: 0.875rem;
        font-weight: 600;
        color: var(--text-main);
      }

      .progress-percentage {
        font-size: 1.25rem;
        font-weight: 700;
        color: var(--accent-color);
      }

      .progress-bar {
        height: 12px;
        background: rgba(255, 255, 255, 0.05);
        border-radius: 6px;
        overflow: hidden;
        margin-bottom: 0.75rem;
      }

      .progress-fill {
        height: 100%;
        background: linear-gradient(90deg, var(--accent-color), #a78bfa);
        border-radius: 6px;
        transition: width 0.5s ease;
        position: relative;
        overflow: hidden;
      }

      .progress-fill::after {
        content: '';
        position: absolute;
        top: 0;
        left: 0;
        right: 0;
        bottom: 0;
        background: linear-gradient(90deg, transparent, rgba(255, 255, 255, 0.3), transparent);
        animation: shimmer 2s infinite;
      }

      .progress-fill.complete {
        background: linear-gradient(90deg, #10b981, #34d399);
      }

      .progress-fill.has-errors {
        background: linear-gradient(90deg, #f59e0b, #fbbf24);
      }

      @keyframes shimmer {
        0% {
          transform: translateX(-100%);
        }
        100% {
          transform: translateX(100%);
        }
      }

      .progress-details {
        display: flex;
        flex-wrap: wrap;
        gap: 1rem;
        font-size: 0.8125rem;
        color: var(--text-muted);
      }

      .detail-item {
        display: flex;
        align-items: center;
        gap: 0.375rem;
      }

      .detail-item.success {
        color: #10b981;
      }
      .detail-item.warning {
        color: #f59e0b;
      }

      .integration-status {
        padding: 1rem 1.25rem;
      }

      .integration-header {
        display: flex;
        align-items: center;
        gap: 0.5rem;
        font-size: 0.9375rem;
        font-weight: 700;
        color: var(--text-main);
        margin-bottom: 1rem;
        padding-bottom: 0.75rem;
        border-bottom: 1px solid rgba(255, 255, 255, 0.05);
      }

      .integration-grid {
        display: grid;
        gap: 0.75rem;
      }

      .integration-row {
        display: grid;
        grid-template-columns: 180px 1fr 160px;
        gap: 1rem;
        align-items: center;
        padding: 0.75rem;
        border-radius: 8px;
        background: rgba(255, 255, 255, 0.02);
        border: 1px solid rgba(255, 255, 255, 0.05);
        transition: all 0.2s ease;
      }

      .integration-row:hover {
        background: rgba(255, 255, 255, 0.04);
        border-color: rgba(255, 255, 255, 0.1);
        transform: translateX(2px);
      }

      .integration-name-section {
        display: flex;
        align-items: center;
        gap: 0.5rem;
      }

      .integration-name {
        font-size: 0.875rem;
        font-weight: 700;
        color: var(--text-main);
        letter-spacing: 0.02em;
      }

      .integration-state {
        display: inline-flex;
        align-items: center;
        gap: 0.5rem;
        padding: 0.375rem 0.75rem;
        border-radius: 8px;
        font-size: 0.75rem;
        font-weight: 600;
        background: rgba(255, 255, 255, 0.05);
        color: var(--text-muted);
        width: fit-content;
        border: 1px solid rgba(255, 255, 255, 0.1);
      }

      .integration-label {
        white-space: nowrap;
        overflow: hidden;
        text-overflow: ellipsis;
        max-width: 280px;
      }

      .integration-time {
        display: flex;
        justify-content: flex-end;
      }

      .integration-time-placeholder {
        display: flex;
        justify-content: flex-end;
      }

      .no-timestamp {
        font-size: 0.75rem;
        color: var(--text-muted);
        opacity: 0.5;
      }

      .status-success {
        color: #10b981;
        background: rgba(16, 185, 129, 0.15);
        border-color: rgba(16, 185, 129, 0.3);
      }
      .status-warning {
        color: #f59e0b;
        background: rgba(245, 158, 11, 0.15);
        border-color: rgba(245, 158, 11, 0.3);
        animation: pulse-integration 2.5s ease-in-out infinite;
      }
      .status-error {
        color: #ef4444;
        background: rgba(239, 68, 68, 0.15);
        border-color: rgba(239, 68, 68, 0.3);
      }
      .status-muted {
        color: var(--text-muted);
        background: rgba(255, 255, 255, 0.05);
        border-color: rgba(255, 255, 255, 0.1);
      }
      .status-pending {
        color: var(--text-muted);
        background: rgba(255, 255, 255, 0.05);
        border-color: rgba(255, 255, 255, 0.1);
      }

      .status-pending.active-monitoring {
        color: var(--accent-color);
        background: rgba(129, 140, 248, 0.15);
        border-color: rgba(129, 140, 248, 0.3);
        animation: pulse-integration 3s ease-in-out infinite;
      }

      .status-pending.active-monitoring app-glass-icon {
        animation: spin-icon 2s linear infinite;
      }

      .status-warning app-glass-icon {
        animation: pulse-icon 2.5s ease-in-out infinite;
      }

      .status-success app-glass-icon {
        color: #10b981;
      }

      .status-error app-glass-icon {
        color: #ef4444;
      }

      .status-warning app-glass-icon {
        color: #f59e0b;
      }

      .status-pending.active-monitoring app-glass-icon {
        color: var(--accent-color);
      }

      @keyframes pulse-integration {
        0%,
        100% {
          opacity: 1;
        }
        50% {
          opacity: 0.65;
        }
      }

      @keyframes pulse-icon {
        0%,
        100% {
          opacity: 1;
          transform: scale(1);
        }
        50% {
          opacity: 0.6;
          transform: scale(0.9);
        }
      }

      @keyframes spin-icon {
        0% {
          transform: rotate(0deg);
        }
        100% {
          transform: rotate(360deg);
        }
      }

      .status-distribution {
        padding: 0.875rem 1rem;
      }

      .distribution-header {
        margin-bottom: 1rem;
      }

      .distribution-label {
        font-size: 0.875rem;
        font-weight: 600;
        color: var(--text-main);
      }

      .distribution-bars {
        display: flex;
        flex-direction: column;
        gap: 0.75rem;
      }

      .distribution-bar {
        display: grid;
        grid-template-columns: 100px 1fr 60px;
        align-items: center;
        gap: 0.75rem;
      }

      .bar-label {
        font-size: 0.8125rem;
        color: var(--text-muted);
        font-weight: 500;
      }

      .bar-track {
        height: 8px;
        background: rgba(255, 255, 255, 0.05);
        border-radius: 4px;
        overflow: hidden;
      }

      .bar-fill {
        height: 100%;
        border-radius: 4px;
        transition: width 0.5s ease;
      }

      .bar-fill.completed {
        background: #10b981;
      }
      .bar-fill.enriching {
        background: var(--accent-color);
      }
      .bar-fill.enrichment-timeout {
        background: #f59e0b;
      }
      .bar-fill.exensio {
        background: #6366f1;
      }
      .bar-fill.exensio-timeout {
        background: #f59e0b;
      }
      .bar-fill.staged {
        background: #818cf8;
      }
      .bar-fill.queued {
        background: #f59e0b;
      }
      .bar-fill.failed {
        background: #ef4444;
      }
      .bar-fill.cancelled {
        background: #f59e0b;
      }

      .bar-value {
        font-size: 0.875rem;
        font-weight: 600;
        color: var(--text-main);
        text-align: right;
      }

      @media (max-width: 768px) {
        .stats-grid {
          grid-template-columns: repeat(2, 1fr);
        }

        .progress-details {
          flex-direction: column;
          gap: 0.5rem;
        }

        .distribution-bar {
          grid-template-columns: 80px 1fr 50px;
          gap: 0.5rem;
        }
      }
    `,
  ],
})
export class MonitoringStatsComponent {
  @Input() stats!: MonitoringStats;
  @Input() integration?: IntegrationStatusSnapshot | null;

  integrationItems() {
    if (!this.integration) {
      return [];
    }
    return [
      this.buildIntegrationItem('Elasticsearch', this.integration.elasticsearch),
      this.buildIntegrationItem('Exensio', this.integration.exensio),
    ];
  }

  private buildIntegrationItem(
    name: string,
    entry: { configured: boolean; status: string; message: string; lastAt?: string | null },
  ) {
    const status = (entry?.status || 'pending').toLowerCase();
    const message = entry?.message || this.statusLabel(status);
    const isActivelyMonitoring =
      status === 'pending' &&
      (message.toLowerCase().includes('monitoring') ||
        message.toLowerCase().includes('retrying') ||
        message.toLowerCase().includes('enriching'));
    const statusClass = this.statusClass(status) + (isActivelyMonitoring ? ' active-monitoring' : '');
    return {
      name,
      status,
      message,
      lastAt: entry?.lastAt || null,
      statusClass,
      icon: this.statusIcon(status, message),
    };
  }

  private statusLabel(status: string): string {
    switch (status) {
      case 'success':
        return 'Success';
      case 'not_found':
        return 'Not found (retrying)';
      case 'timeout':
        return 'Timed out';
      case 'failure':
        return 'Failed';
      case 'error':
        return 'Error';
      case 'not_configured':
        return 'Not configured';
      case 'pending':
      default:
        return 'Waiting for first check';
    }
  }

  private statusClass(status: string): string {
    switch (status) {
      case 'success':
        return 'status-success';
      case 'not_found':
        return 'status-warning';
      case 'timeout':
      case 'failure':
      case 'error':
        return 'status-error';
      case 'not_configured':
        return 'status-muted';
      case 'pending':
        return 'status-pending';
      default:
        return 'status-muted';
    }
  }

  private statusIcon(status: string, message?: string): string {
    switch (status) {
      case 'success':
        return 'check_circle';
      case 'not_found':
        return 'hourglass_empty';
      case 'timeout':
      case 'failure':
      case 'error':
        return 'error';
      case 'not_configured':
        return 'settings';
      case 'pending':
      default: {
        const msg = (message || '').toLowerCase();
        if (msg.includes('monitoring') || msg.includes('retrying') || msg.includes('enriching')) {
          return 'refresh';
        }
        return 'clock';
      }
    }
  }

  getPercentage(value: number): number {
    return this.stats.total > 0 ? (value / this.stats.total) * 100 : 0;
  }
}
