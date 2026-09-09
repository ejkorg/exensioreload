import { Component, OnInit, signal, computed, ChangeDetectionStrategy, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatTabsModule } from '@angular/material/tabs';
import { MatChipsModule } from '@angular/material/chips';
import { MatBadgeModule } from '@angular/material/badge';
import { PageEvent } from '@angular/material/paginator';
import { BackendService, AlertConfiguration } from '../api/backend.service';

interface Alert {
  alertId: string;
  senderId: number;
  senderName: string;
  site: string;
  alertType: string;
  severity: 'CRITICAL' | 'WARNING' | 'INFO';
  status: 'ACTIVE' | 'ACKNOWLEDGED' | 'RESOLVED';
  threshold: number;
  currentValue: number;
  message: string;
  triggeredAt: string;
  acknowledgedBy?: string;
  acknowledgedAt?: string;
}

@Component({
  selector: 'app-alerts',
  standalone: true,
  imports: [
    CommonModule,
    RouterModule,
    MatButtonModule,
    MatIconModule,
    MatTabsModule,
    MatChipsModule,
    MatBadgeModule,
  ],
  template: `
    <div class="alerts-container">
      <header class="alerts-header">
        <div class="title-block">
          <h1>
            <mat-icon class="header-icon">notifications_active</mat-icon>
            Alert Center
          </h1>
          <p class="subtitle">Monitor and manage system alerts</p>
        </div>
        <div class="header-actions">
          <button mat-stroked-button routerLink="/">
            <mat-icon>dashboard</mat-icon>
            Back to Dashboard
          </button>
        </div>
      </header>

      <!-- Alert Summary Cards -->
      <div class="summary-row">
        <div class="summary-card critical">
          <mat-icon>error</mat-icon>
          <div class="summary-info">
            <span class="summary-count">{{ criticalCount() }}</span>
            <span class="summary-label">Critical</span>
          </div>
        </div>
        <div class="summary-card warning">
          <mat-icon>warning</mat-icon>
          <div class="summary-info">
            <span class="summary-count">{{ warningCount() }}</span>
            <span class="summary-label">Warning</span>
          </div>
        </div>
        <div class="summary-card active">
          <mat-icon>notifications</mat-icon>
          <div class="summary-info">
            <span class="summary-count">{{ activeCount() }}</span>
            <span class="summary-label">Active</span>
          </div>
        </div>
      </div>

      <!-- Alert List -->
      <div class="alerts-section">
        <div class="section-header">
          <h2>Active Alerts</h2>
          <mat-chip-listbox>
            <mat-chip-option
              *ngFor="let filter of filters"
              [selected]="selectedFilter() === filter.value"
              (click)="setFilter(filter.value)">
              {{ filter.label }}
            </mat-chip-option>
          </mat-chip-listbox>
        </div>

        @if (filteredAlerts().length === 0) {
          <div class="empty-state">
            <mat-icon class="empty-icon">check_circle</mat-icon>
            <h3>No Active Alerts</h3>
            <p>All systems operating normally. Alerts will appear here when thresholds are breached.</p>
          </div>
        } @else {
          <div class="alerts-list">
            @for (alert of filteredAlerts(); track alert.alertId) {
              <div class="alert-card" [class]="'severity-' + alert.severity.toLowerCase()">
                <div class="alert-indicator"></div>
                <div class="alert-content">
                  <div class="alert-header">
                    <div class="alert-title-row">
                      <mat-icon [class]="'severity-icon-' + alert.severity.toLowerCase()">
                        {{ getSeverityIcon(alert.severity) }}
                      </mat-icon>
                      <span class="alert-type">{{ alert.alertType }}</span>
                      <span class="alert-severity" [class]="'badge-' + alert.severity.toLowerCase()">
                        {{ alert.severity }}
                      </span>
                    </div>
                    <span class="alert-time">{{ formatTime(alert.triggeredAt) }}</span>
                  </div>
                  <p class="alert-message">{{ alert.message }}</p>
                  <div class="alert-meta">
                    <span class="meta-item">
                      <mat-icon>business</mat-icon>
                      {{ alert.site || 'Unknown' }}
                    </span>
                    <span class="meta-item">
                      <mat-icon>sensors</mat-icon>
                      Sender {{ alert.senderId }}
                    </span>
                    <span class="meta-item">
                      <mat-icon>trending_up</mat-icon>
                      {{ alert.currentValue }} / {{ alert.threshold }}
                    </span>
                  </div>
                  <div class="alert-actions">
                    @if (alert.status === 'ACTIVE') {
                      <button mat-stroked-button size="small" (click)="acknowledgeAlert(alert)">
                        <mat-icon>check</mat-icon>
                        Acknowledge
                      </button>
                    }
                    <button mat-button size="small" (click)="resolveAlert(alert)">
                      <mat-icon>done_all</mat-icon>
                      Resolve
                    </button>
                  </div>
                </div>
              </div>
            }
          </div>
        }
      </div>

      <!-- Configuration Section -->
      <div class="config-section">
        <h2>Notification Configuration</h2>
        <div class="config-cards">
          <div class="config-card" [class.enabled]="config().emailNotifications?.enabled">
            <mat-icon>email</mat-icon>
            <div class="config-info">
              <span class="config-title">Email Notifications</span>
              <span class="config-status">
                {{ config().emailNotifications?.enabled ? 'Enabled' : 'Disabled' }}
              </span>
            </div>
          </div>
          <div class="config-card" [class.enabled]="config().webhookNotifications?.enabled">
            <mat-icon>webhook</mat-icon>
            <div class="config-info">
              <span class="config-title">Webhook</span>
              <span class="config-status">
                {{ config().webhookNotifications?.enabled ? 'Enabled' : 'Disabled' }}
              </span>
            </div>
          </div>
          <div class="config-card" [class.enabled]="config().slackNotifications?.enabled">
            <mat-icon>chat</mat-icon>
            <div class="config-info">
              <span class="config-title">Slack</span>
              <span class="config-status">
                {{ config().slackNotifications?.enabled ? 'Enabled' : 'Disabled' }}
              </span>
            </div>
          </div>
        </div>
      </div>
    </div>
  `,
  styles: [`
    .alerts-container {
      max-width: 1400px;
      margin: 0 auto;
      padding: 2rem;
      display: flex;
      flex-direction: column;
      gap: 1.5rem;
    }

    .alerts-header {
      display: flex;
      justify-content: space-between;
      align-items: center;
      flex-wrap: wrap;
      gap: 1rem;
      border-bottom: 1px solid rgba(255, 255, 255, 0.1);
      padding-bottom: 1rem;
    }

    .title-block h1 {
      margin: 0;
      font-size: 1.6rem;
      display: flex;
      align-items: center;
      gap: 0.5rem;
    }

    .header-icon {
      color: #f59e0b;
      font-size: 1.8rem;
    }

    .subtitle {
      margin: 0.3rem 0 0 0;
      color: rgba(255, 255, 255, 0.6);
    }

    .summary-row {
      display: grid;
      grid-template-columns: repeat(3, 1fr);
      gap: 1rem;
    }

    @media (max-width: 768px) {
      .summary-row {
        grid-template-columns: 1fr;
      }
    }

    .summary-card {
      display: flex;
      align-items: center;
      gap: 1rem;
      padding: 1rem 1.25rem;
      border-radius: 12px;
      background: rgba(255, 255, 255, 0.04);
      border: 1px solid rgba(255, 255, 255, 0.1);
    }

    .summary-card.critical mat-icon { color: #ef4444; }
    .summary-card.warning mat-icon { color: #f59e0b; }
    .summary-card.active mat-icon { color: #6366f1; }

    .summary-card mat-icon {
      font-size: 2rem;
      width: 2rem;
      height: 2rem;
    }

    .summary-info {
      display: flex;
      flex-direction: column;
    }

    .summary-count {
      font-size: 1.5rem;
      font-weight: 800;
    }

    .summary-label {
      font-size: 0.8rem;
      color: rgba(255, 255, 255, 0.6);
      text-transform: uppercase;
      letter-spacing: 0.05em;
    }

    .alerts-section {
      background: rgba(255, 255, 255, 0.02);
      border-radius: 16px;
      border: 1px solid rgba(255, 255, 255, 0.08);
      padding: 1.5rem;
    }

    .section-header {
      display: flex;
      justify-content: space-between;
      align-items: center;
      margin-bottom: 1rem;
      flex-wrap: wrap;
      gap: 1rem;
    }

    .section-header h2 {
      margin: 0;
      font-size: 1.2rem;
    }

    .empty-state {
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: center;
      padding: 3rem;
      text-align: center;
    }

    .empty-icon {
      font-size: 3rem;
      width: 3rem;
      height: 3rem;
      color: #10b981;
      margin-bottom: 1rem;
    }

    .empty-state h3 {
      margin: 0 0 0.5rem;
    }

    .empty-state p {
      margin: 0;
      color: rgba(255, 255, 255, 0.6);
      max-width: 400px;
    }

    .alerts-list {
      display: flex;
      flex-direction: column;
      gap: 0.75rem;
    }

    .alert-card {
      display: flex;
      border-radius: 12px;
      background: rgba(255, 255, 255, 0.04);
      border: 1px solid rgba(255, 255, 255, 0.1);
      overflow: hidden;
      transition: all 0.2s ease;
    }

    .alert-card:hover {
      background: rgba(255, 255, 255, 0.06);
    }

    .alert-indicator {
      width: 4px;
    }

    .severity-critical .alert-indicator { background: #ef4444; }
    .severity-warning .alert-indicator { background: #f59e0b; }
    .severity-info .alert-indicator { background: #6366f1; }

    .alert-content {
      flex: 1;
      padding: 1rem;
    }

    .alert-header {
      display: flex;
      justify-content: space-between;
      align-items: flex-start;
      margin-bottom: 0.5rem;
    }

    .alert-title-row {
      display: flex;
      align-items: center;
      gap: 0.5rem;
    }

    .alert-type {
      font-weight: 600;
      font-size: 0.95rem;
    }

    .alert-severity {
      font-size: 0.65rem;
      font-weight: 700;
      padding: 0.15rem 0.5rem;
      border-radius: 4px;
      text-transform: uppercase;
    }

    .badge-critical { background: rgba(239, 68, 68, 0.2); color: #f87171; }
    .badge-warning { background: rgba(245, 158, 11, 0.2); color: #fbbf24; }
    .badge-info { background: rgba(99, 102, 241, 0.2); color: #a5b4fc; }

    .alert-time {
      font-size: 0.75rem;
      color: rgba(255, 255, 255, 0.5);
    }

    .severity-icon-critical { color: #ef4444; }
    .severity-icon-warning { color: #f59e0b; }
    .severity-icon-info { color: #6366f1; }

    .alert-message {
      margin: 0 0 0.75rem;
      font-size: 0.9rem;
      color: rgba(255, 255, 255, 0.8);
    }

    .alert-meta {
      display: flex;
      gap: 1rem;
      flex-wrap: wrap;
      margin-bottom: 0.75rem;
    }

    .meta-item {
      display: flex;
      align-items: center;
      gap: 0.25rem;
      font-size: 0.8rem;
      color: rgba(255, 255, 255, 0.5);
    }

    .meta-item mat-icon {
      font-size: 0.9rem;
      width: 0.9rem;
      height: 0.9rem;
    }

    .alert-actions {
      display: flex;
      gap: 0.5rem;
    }

    .config-section {
      background: rgba(255, 255, 255, 0.02);
      border-radius: 16px;
      border: 1px solid rgba(255, 255, 255, 0.08);
      padding: 1.5rem;
    }

    .config-section h2 {
      margin: 0 0 1rem;
      font-size: 1.2rem;
    }

    .config-cards {
      display: grid;
      grid-template-columns: repeat(3, 1fr);
      gap: 1rem;
    }

    @media (max-width: 768px) {
      .config-cards {
        grid-template-columns: 1fr;
      }
    }

    .config-card {
      display: flex;
      align-items: center;
      gap: 1rem;
      padding: 1rem;
      border-radius: 10px;
      background: rgba(255, 255, 255, 0.03);
      border: 1px solid rgba(255, 255, 255, 0.08);
      opacity: 0.6;
    }

    .config-card.enabled {
      opacity: 1;
      border-color: rgba(16, 185, 129, 0.4);
      background: rgba(16, 185, 129, 0.08);
    }

    .config-card mat-icon {
      font-size: 1.5rem;
      width: 1.5rem;
      height: 1.5rem;
    }

    .config-card.enabled mat-icon {
      color: #10b981;
    }

    .config-info {
      display: flex;
      flex-direction: column;
    }

    .config-title {
      font-weight: 600;
      font-size: 0.9rem;
    }

    .config-status {
      font-size: 0.75rem;
      color: rgba(255, 255, 255, 0.5);
    }
  `],
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class AlertsComponent implements OnInit {
  private backend = inject(BackendService);

  alerts = signal<Alert[]>([]);
  config = signal<AlertConfiguration>({
    emailNotifications: { enabled: false, recipients: [] },
    webhookNotifications: { enabled: false, url: '' },
    slackNotifications: { enabled: false, webhookUrl: '' },
    defaultSeverity: undefined,
    retentionDays: undefined
  });

  selectedFilter = signal<string>('all');

  filters = [
    { label: 'All', value: 'all' },
    { label: 'Critical', value: 'CRITICAL' },
    { label: 'Warning', value: 'WARNING' },
  ];

  criticalCount = computed(() =>
    this.alerts().filter(a => a.severity === 'CRITICAL' && a.status === 'ACTIVE').length
  );

  warningCount = computed(() =>
    this.alerts().filter(a => a.severity === 'WARNING' && a.status === 'ACTIVE').length
  );

  activeCount = computed(() =>
    this.alerts().filter(a => a.status === 'ACTIVE').length
  );

  filteredAlerts = computed(() => {
    const filter = this.selectedFilter();
    if (filter === 'all') return this.alerts().filter(a => a.status === 'ACTIVE');
    return this.alerts().filter(a => a.status === 'ACTIVE' && a.severity === filter);
  });

  ngOnInit(): void {
    this.loadAlerts();
    this.loadConfiguration();
  }

  private loadAlerts(): void {
    // For now, load from local state
    // In production, this would call backend.getAlerts()
    this.alerts.set([]);
  }

  private loadConfiguration(): void {
    this.backend.getAlertConfiguration().subscribe({
      next: (config) => this.config.set(config)
    });
  }

  setFilter(value: string): void {
    this.selectedFilter.set(value);
  }

  getSeverityIcon(severity: string): string {
    switch (severity) {
      case 'CRITICAL': return 'error';
      case 'WARNING': return 'warning';
      default: return 'info';
    }
  }

  acknowledgeAlert(alert: Alert): void {
    this.alerts.update(current =>
      current.map(a => a.alertId === alert.alertId
        ? { ...a, status: 'ACKNOWLEDGED' as const, acknowledgedBy: 'Current User', acknowledgedAt: new Date().toISOString() }
        : a
      )
    );
  }

  resolveAlert(alert: Alert): void {
    this.alerts.update(current =>
      current.filter(a => a.alertId !== alert.alertId)
    );
  }

  formatTime(timestamp: string): string {
    const date = new Date(timestamp);
    const now = new Date();
    const diffMs = now.getTime() - date.getTime();
    const diffMins = Math.floor(diffMs / 60000);

    if (diffMins < 1) return 'Just now';
    if (diffMins < 60) return `${diffMins}m ago`;
    const diffHours = Math.floor(diffMins / 60);
    if (diffHours < 24) return `${diffHours}h ago`;
    return date.toLocaleDateString([], { timeZone: 'UTC' });
  }
}
