import { CommonModule } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, OnDestroy, OnInit, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { Router, RouterModule } from '@angular/router';
import { Subscription, timer } from 'rxjs';
import { environment } from '../../environments/environment';
import {
  BackendService,
  DashboardSenderSnapshot,
  DashboardSiteSnapshot,
  DashboardSnapshot,
  LimitsConfig,
  StagingSessionDetail,
} from '../api/backend.service';
import { GlassCheckboxComponent } from '../shared/components/glass-checkbox.component';
import { GlassDeviceFilterComponent } from '../shared/components/glass-device-filter.component';
import { GlassDialogService } from '../shared/services/glass-dialog.service';
import { StagingSessionService } from '../shared/services/staging-session.service';
import { ToastService } from '../shared/services/toast.service';
import { BulkActionsComponent, SelectableItem } from './bulk-actions.component';
import { MetricCardDetailSidebarComponent } from './metric-card-detail-sidebar.component';
import { SenderAlertSettingsComponent } from './sender-alert-settings.component';
import { SiteDetailModalComponent } from './site-detail-modal.component';
import { StateLegendTooltipComponent } from './state-legend-tooltip.component';
import { StateLegendService } from './state-legend.service';

/**
 * Represents an aggregated state change event broadcast via SSE.
 * Contains list of state changes and updated totals for real-time dashboard updates.
 */
interface StateAggregationEvent {
  timestamp: string;
  changes: StateChange[];
  totals: {
    [key: string]: number;
  };
  requestId: string;
}

/**
 * Represents a single state change within an aggregation event.
 * Shows the previous and new counts for a specific state.
 */
interface StateChange {
  state: string;
  previousCount: number;
  newCount: number;
}

interface MetricCard {
  key?: keyof MetricSnapshot;
  label: string;
  abbrev?: string;
  value: number;
  icon: string;
  color: string;
  accentColor?: string;
  trend?: number;
  alert?: boolean;
  subtext?: string;
}

interface SenderPerformance {
  senderId: number;
  senderLabel: string;
  site: string;
  backlog: number;
  throughput: number;
  successRate: number;
  alert: boolean;
  isCritical?: boolean;
  cancellableCount: number;
  enqueuedCount: number;
}

interface ActiveMonitoringSessionContext {
  sessionId: string;
  site: string;
  senderId: number;
  senderLabel: string;
}

interface MetricSnapshot {
  timestamp: number;
  backlog: number;
  ready: number;
  enqueued: number;
  completed: number;
}

interface DashboardErrorDetails {
  message: string;
  code: 'NO_CONNECTION' | 'TIMEOUT' | 'AUTH_ERROR' | 'SERVER_ERROR' | 'CLIENT_ERROR' | 'UNKNOWN';
  details?: string;
  retryIn?: number;
  retryAttempts: number;
  maxRetries: number;
  timestamp: Date;
}

@Component({
  selector: 'app-dashboard',
  standalone: true,
  imports: [
    CommonModule,
    MatIconModule,
    MatButtonModule,
    MatTooltipModule,
    MatProgressBarModule,
    RouterModule,
    BulkActionsComponent,
    GlassCheckboxComponent,
    GlassDeviceFilterComponent,
    StateLegendTooltipComponent,
  ],
  templateUrl: './dashboard.component.html',
  styleUrls: ['./dashboard.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DashboardComponent implements OnInit, OnDestroy {
  private readonly monitoringResumeStorageKey = 'exensioreload.activeMonitoringSessionId';

  /** Exposed for template Math.abs() call in aria-label */
  readonly Math = Math;

  formatUtcTimestamp(value: string | Date | null | undefined): string {
    if (!value) return '-';
    const d = new Date(value);
    return isNaN(d.getTime())
      ? '-'
      : d.toLocaleString([], {
          year: 'numeric',
          month: 'short',
          day: 'numeric',
          hour: '2-digit',
          minute: '2-digit',
          timeZone: 'UTC',
        });
  }

  snapshot = signal<DashboardSnapshot | null>(null);
  loading = signal(false);
  error = signal<string | null>(null);
  errorDetails = signal<DashboardErrorDetails | null>(null);
  lastUpdated = signal<Date | null>(null);
  dataFreshness = signal<'fresh' | 'stale' | 'very-stale'>('fresh');
  freshnessBannerDismissed = signal(false);
  expandedSites = signal<Set<string>>(new Set<string>());
  activeMonitoringSession = signal<ActiveMonitoringSessionContext | null>(null);
  metricHistory = signal<MetricSnapshot[]>([]);
  previousSnapshot = signal<DashboardSnapshot | null>(null);
  changedMetrics = signal<Set<string>>(new Set<string>());
  lastUpdatePulse = signal(false);
  devices = signal<string[]>([]);

  resolvedLimits = signal<LimitsConfig | null>(null);
  limitsError = signal(false);
  limitsBannerDismissed = signal(false);

  resolvedMonitorMaxRows = computed<number>(
    () => this.resolvedLimits()?.stageMaxRowsCap ?? environment.monitoring.monitorMaxRows,
  );

  selectedSenderIds = signal<Set<number>>(new Set<number>());

  selectableItems = computed<SelectableItem[]>(() =>
    this.topSenders().map((s: SenderPerformance) => ({
      id: s.senderId,
      type: 'sender' as const,
      label: s.senderLabel,
      cancellableCount: s.cancellableCount,
      enqueuedCount: s.enqueuedCount,
    })),
  );

  // ── Computed KPIs ───────────────────────────────────────────────

  overallHealthScore = computed(() => {
    const s = this.snapshot();
    if (!s) return 0;
    const terminal = s.global.completed + s.global.failed;
    if (terminal === 0) return 100;
    return Math.round((s.global.completed / terminal) * 100);
  });

  systemUtilization = computed(() => {
    const s = this.snapshot();
    if (!s) return 0;
    const maxCapacity = s.global.enqueued + s.global.ready + s.global.completed + 1;
    return Math.round((s.global.enqueued / maxCapacity) * 100);
  });

  metricTrends = computed(() => {
    const history = this.metricHistory();
    const now = Date.now();
    const oneHourAgo = now - 60 * 60 * 1000;
    const recentHistory = history.filter((h: MetricSnapshot) => h.timestamp >= oneHourAgo);

    if (recentHistory.length < 2) {
      return { backlog: 0, ready: 0, enqueued: 0, completed: 0 };
    }

    const oldest = recentHistory[0];
    const newest = recentHistory[recentHistory.length - 1];

    const calcTrend = (current: number, old: number): number => {
      if (old === 0) return current > 0 ? 100 : 0;
      return Math.round(((current - old) / old) * 100);
    };

    return {
      backlog: calcTrend(newest.backlog, oldest.backlog),
      ready: calcTrend(newest.ready, oldest.ready),
      enqueued: calcTrend(newest.enqueued, oldest.enqueued),
      completed: calcTrend(newest.completed, oldest.completed),
    };
  });

  topSenders = computed(() => {
    const s = this.snapshot();
    if (!s) return [];
    const senders: SenderPerformance[] = [];
    s.sites.forEach((site: DashboardSiteSnapshot) => {
      site.senders.forEach((sender: DashboardSenderSnapshot) => {
        const completed = sender.metrics.completed || 0;
        const failed = sender.metrics.failed || 0;
        const terminal = completed + failed;
        const successRate = terminal > 0 ? Math.round((completed / terminal) * 100) : 100;
        senders.push({
          senderId: sender.senderId,
          senderLabel: sender.senderLabel,
          site: site.site,
          backlog: sender.metrics.backlog,
          throughput: 0,
          successRate: successRate,
          alert: sender.metrics.backlog > 500,
          isCritical: sender.metrics.backlog > 5000,
          cancellableCount: sender.metrics.ready ?? 0,
          enqueuedCount: sender.metrics.enqueued ?? 0,
        });
      });
    });
    return senders.sort((a, b) => b.backlog - a.backlog).slice(0, 6);
  });

  /** Computed KPI cards — memoized via signal (9 state cards including 2 timeout states) */
  primaryMetrics = computed<MetricCard[]>(() => {
    const s = this.snapshot();
    if (!s) return [];
    const trends = this.metricTrends();
    return [
      {
        label: 'Staged',
        abbrev: 'STG',
        value: s.global.ready,
        icon: 'inbox',
        color: 'secondary',
        accentColor: '#8b5cf6',
        subtext: 'ready to dispatch',
      },
      {
        label: 'Queued for Enrichment',
        abbrev: 'QUE',
        value: s.global.queued,
        icon: 'schedule',
        color: 'info',
        accentColor: '#3b82f6',
        subtext: 'waiting in queue',
      },
      {
        label: 'Enrichment Processing',
        abbrev: 'ENR',
        value: s.global.enriching,
        icon: 'auto_awesome',
        color: 'primary',
        accentColor: '#818cf8',
        subtext: 'actively enriching',
      },
      {
        label: 'Enrichment Monitoring Timeout',
        abbrev: 'ENT',
        value: s.global.enrichmentTimeout,
        icon: 'schedule',
        color: 'warning',
        accentColor: '#f59e0b',
        subtext: 'no log found — verify manually',
      },
      {
        label: 'Exensio Monitoring',
        abbrev: 'EXL',
        value: s.global.exensioLoading,
        icon: 'cloud_download',
        color: 'info',
        accentColor: '#06b6d4',
        subtext: 'monitoring exensio load',
      },
      {
        label: 'Completed — Verify in Exensio',
        abbrev: 'EXT',
        value: s.global.exensioTimeout,
        icon: 'schedule',
        color: 'warning',
        accentColor: '#f59e0b',
        subtext: 'not confirmed — verify in exensio',
      },
      {
        label: 'Completed',
        abbrev: 'CPL',
        value: s.global.completed,
        icon: 'check_circle',
        color: 'success',
        accentColor: '#10b981',
        subtext: 'successful operations',
        trend: trends.completed,
      },
      {
        label: 'Failed',
        abbrev: 'FAL',
        value: s.global.failed,
        icon: 'error_outline',
        color: 'danger',
        accentColor: '#ef4444',
        subtext: 'encountered errors',
      },
      {
        label: 'Cancelled',
        abbrev: 'CAN',
        value: s.global.cancelled,
        icon: 'block',
        color: 'danger',
        accentColor: '#f97316',
        subtext: 'paused or deleted',
      },
    ];
  });

  /** Supporting infrastructure metrics — memoized */
  supportingMetrics = computed<MetricCard[]>(() => {
    const s = this.snapshot();
    if (!s) return [];
    return [
      {
        label: 'Active Senders',
        value: s.global.activeSenders,
        icon: 'groups',
        color: 'info',
        accentColor: '#3b82f6',
      },
      {
        label: 'Active Sites',
        value: s.sites.length,
        icon: 'location_on',
        color: 'secondary',
        accentColor: '#8b5cf6',
      },
    ];
  });

  /** Pre-computed per-key histories (avoids 8x map+slice per render) */
  metricHistories = computed<Map<string, number[]>>(() => {
    const history = this.metricHistory();
    const keys: (keyof MetricSnapshot)[] = ['backlog', 'ready', 'enqueued', 'completed'];
    const map = new Map<string, number[]>();
    for (const key of keys) {
      map.set(key, history.map((m: MetricSnapshot) => m[key]).slice(-60));
    }
    return map;
  });

  // ── Monitoring action computeds ─────────────────────────────────

  primaryMonitoringActionLabel = computed<string>(() =>
    this.activeMonitoringSession() ? 'Resume Monitoring' : 'Start Monitoring',
  );

  primaryMonitoringActionTooltip = computed<string>(() => {
    const active = this.activeMonitoringSession();
    return active ? `Resume active session ${active.sessionId}` : 'Start a new monitoring session';
  });

  primaryMonitoringActionIcon = computed<string>(() => (this.activeMonitoringSession() ? 'play_circle' : 'play_arrow'));

  // ── Freshness / error computeds ─────────────────────────────────

  /** Seconds since last successful data fetch */
  dataAgeSeconds = computed<number>(() => {
    const lastUpdated = this.lastUpdated();
    if (!lastUpdated) return Number.POSITIVE_INFINITY;
    return Math.floor((Date.now() - lastUpdated.getTime()) / 1000);
  });

  /** Human-readable freshness banner message */
  freshnessMessage = computed<string>(() => {
    const ageSeconds = this.dataAgeSeconds();
    if (!Number.isFinite(ageSeconds)) return 'No dashboard data has been loaded yet.';
    if (this.dataFreshness() === 'very-stale') {
      return `Data is very stale (${ageSeconds}s old). Refresh recommended.`;
    }
    return `Data may be stale (${ageSeconds}s old). Last refresh was ${this.fmtTimeAgo(this.lastUpdated())}.`;
  });

  /** Whether to render the freshness banner */
  showFreshnessBanner = computed<boolean>(() => {
    const freshness = this.dataFreshness();
    if (freshness === 'fresh') return false;
    if (freshness === 'very-stale') return true;
    return !this.freshnessBannerDismissed();
  });

  /** True when dashboard has zero sites */
  hasNoData = computed<boolean>(() => {
    const snap = this.snapshot();
    return !snap || !snap.sites || snap.sites.length === 0;
  });

  /** True when all global metrics are zero */
  hasZeroMetrics = computed<boolean>(() => {
    const snap = this.snapshot();
    if (!snap?.global) return false;
    return (
      (snap.global.completed || 0) === 0 &&
      (snap.global.enqueued || 0) === 0 &&
      (snap.global.ready || 0) === 0 &&
      (snap.global.backlog || 0) === 0 &&
      (snap.global.activeSenders || 0) === 0
    );
  });

  // ── Private state ───────────────────────────────────────────────

  private freshnessPollInterval?: ReturnType<typeof setInterval>;
  private pollSub?: Subscription;
  private stateStreamSub?: Subscription;
  private retryAttempts = 0;
  private readonly maxRetries = 5;
  private readonly retryBaseDelayMs = 5000;
  private autoRetryTimeout?: ReturnType<typeof setTimeout>;
  private retryCountdownInterval?: ReturnType<typeof setInterval>;
  private toast: ToastService;

  constructor(
    private backend: BackendService,
    private router: Router,
    private dialog: GlassDialogService,
    public stagingSession: StagingSessionService,
    protected legendService: StateLegendService,
  ) {
    this.toast = inject(ToastService);
  }

  ngOnInit() {
    this.backend.getLimits().subscribe({
      next: (limits: LimitsConfig) => {
        this.resolvedLimits.set(limits);
        this.limitsError.set(false);
      },
      error: () => {
        this.limitsError.set(true);
      },
    });
    this.refresh();
    this.loadActiveMonitoringSessionContext();
    this.pollSub = timer(10000, 10000).subscribe(() => this.loadSnapshot(false));
    this.setupFreshnessMonitor();

    // Connect to dashboard state stream for real-time timeout state updates
    // Requirements 7.1, 7.2, 7.3, 7.4
    this.connectDashboardStateStream();
  }

  ngOnDestroy() {
    this.pollSub?.unsubscribe();
    this.stateStreamSub?.unsubscribe();
    if (this.freshnessPollInterval) {
      clearInterval(this.freshnessPollInterval);
    }
    this.clearRetryTimers();
  }

  refresh() {
    this.loadSnapshot(true);
    this.loadActiveMonitoringSessionContext();
  }

  dismissFreshnessBanner(): void {
    this.freshnessBannerDismissed.set(true);
  }

  /**
   * Handle device filter changes.
   * When user selects or deselects devices, reload dashboard data with the filter applied.
   * Requirements: 4.1, 4.2
   */
  onDeviceFilterChange(selectedDevices: string[]): void {
    this.devices.set(selectedDevices);
    this.refresh();
  }

  /** Whether an active session exists for a given site + sender */
  hasActiveSessionForSender(site: string, senderId: number): boolean {
    const active = this.activeMonitoringSession();
    if (!active) return false;
    return active.site === site && active.senderId === senderId;
  }

  resumeMonitoring() {
    this.router.navigate(['/new'], { queryParams: { resume: '1' } });
  }

  startOrResumeMonitoring(): void {
    if (this.activeMonitoringSession()) {
      this.resumeMonitoring();
      return;
    }
    this.router.navigate(['/new']);
  }

  openAlertSettings(sender: SenderPerformance): void {
    this.dialog.open(SenderAlertSettingsComponent, {
      data: {
        senderId: sender.senderId,
        senderLabel: sender.senderLabel,
        site: sender.site,
        backlog: sender.backlog,
        successRate: sender.successRate,
      },
      panelClass: 'alert-settings-dialog',
      width: '480px',
      maxWidth: '90vw',
    });
  }

  openMetricCardDetail(metric: MetricCard): void {
    const stateMap: { [key: string]: string } = {
      Staged: 'STAGED',
      'Queued for Enrichment': 'QUEUED_FOR_CP',
      'Enrichment Processing': 'ELASTICSEARCH_MONITORING',
      'Enrichment Monitoring Timeout': 'CP_TIMEOUT',
      'Exensio Monitoring': 'EXENSIO_MONITORING',
      'Completed — Verify in Exensio': 'COMPLETED_MANUAL_VERIFICATION_REQUIRED',
      Completed: 'COMPLETED',
      Failed: 'FAILED',
      Cancelled: 'CANCELLED',
    };

    const state = stateMap[metric.label] || metric.label.toUpperCase();

    // Use first site and sender for context, or defaults
    const snap = this.snapshot();
    const firstSite = snap?.sites?.[0];
    const firstSender = firstSite?.senders?.[0];
    const site = firstSite?.site ?? 'ALL';
    const senderId = firstSender?.senderId ?? 0;
    const senderLabel = firstSender?.senderLabel ?? 'All Senders';

    this.dialog.open(MetricCardDetailSidebarComponent, {
      data: {
        state,
        label: metric.label,
        site,
        senderId,
        senderLabel,
      },
      panelClass: 'metric-detail-sidebar-dialog',
      width: '90vw',
      maxWidth: '900px',
      height: '90vh',
      maxHeight: '90vh',
    });
  }

  onSiteClick(site: DashboardSiteSnapshot): void {
    if (window.innerWidth < 768) {
      const dialogRef = this.dialog.open(SiteDetailModalComponent, {
        data: { site },
        panelClass: 'site-detail-modal-panel',
        width: '100vw',
        maxWidth: '100vw',
        height: '100vh',
        maxHeight: '100vh',
      });

      dialogRef.afterClosed().then((result) => {
        if (result?.action === 'refresh') {
          this.refresh();
        } else if (result?.action === 'resume') {
          this.resumeMonitoring();
        }
      });
      return;
    }

    this.toggleSiteSenders(site.site);
  }

  private loadSnapshot(showLoading: boolean) {
    if (showLoading) this.loading.set(true);
    this.backend.getDashboardSnapshot(this.devices()).subscribe({
      next: (snap: DashboardSnapshot) => {
        this.clearRetryTimers();
        this.retryAttempts = 0;
        const changed = this.detectChanges(this.snapshot(), snap);
        this.changedMetrics.set(changed);
        this.previousSnapshot.set(this.snapshot());
        this.snapshot.set(snap);
        this.lastUpdated.set(new Date());
        this.dataFreshness.set('fresh');
        this.loading.set(false);
        this.error.set(null);
        this.errorDetails.set(null);
        this.lastUpdatePulse.set(true);
        setTimeout(() => this.lastUpdatePulse.set(false), 300);
        if (changed.size > 0) {
          setTimeout(() => this.changedMetrics.set(new Set<string>()), 600);
        }

        const newSnapshot: MetricSnapshot = {
          timestamp: Date.now(),
          backlog: snap.global.backlog,
          ready: snap.global.ready,
          enqueued: snap.global.enqueued,
          completed: snap.global.completed,
        };

        this.metricHistory.update((history: MetricSnapshot[]) => {
          const updated = [...history, newSnapshot];
          if (updated.length > 360) {
            updated.shift();
          }
          return updated;
        });
      },
      error: (err: unknown) => {
        console.error('Failed to load dashboard', err);
        this.loading.set(false);

        const status = this.extractHttpStatus(err);
        const parsed = this.classifyDashboardError(status);

        this.retryAttempts++;

        const delayMs = Math.min(this.retryBaseDelayMs * Math.pow(2, this.retryAttempts - 1), 30000);

        this.error.set(parsed.message);
        this.errorDetails.set({
          message: parsed.message,
          code: parsed.code,
          details: parsed.details,
          retryIn: this.retryAttempts < this.maxRetries ? Math.ceil(delayMs / 1000) : undefined,
          retryAttempts: this.retryAttempts,
          maxRetries: this.maxRetries,
          timestamp: new Date(),
        });

        this.toast.error(parsed.message, 7000);

        if (this.retryAttempts < this.maxRetries) {
          this.startRetryCountdown(Math.ceil(delayMs / 1000));
          this.autoRetryTimeout = setTimeout(() => this.autoRetry(), delayMs);
        }
      },
    });
  }

  manualRetry(): void {
    this.clearRetryTimers();
    this.retryAttempts = 0;
    this.error.set(null);
    this.errorDetails.set(null);
    this.loadSnapshot(true);
  }

  contactSupport(): void {
    const details = this.errorDetails();
    const payload = encodeURIComponent(
      `Dashboard Error\nCode: ${details?.code ?? 'UNKNOWN'}\nAttempts: ${details?.retryAttempts ?? 0}/${details?.maxRetries ?? this.maxRetries}`,
    );
    window.open(`/support?error=${payload}`, '_blank', 'noopener');
  }

  private autoRetry(): void {
    if (this.retryAttempts >= this.maxRetries) return;
    this.loadSnapshot(false);
  }

  private startRetryCountdown(initialSeconds: number): void {
    this.clearRetryCountdownInterval();
    let remaining = initialSeconds;

    this.retryCountdownInterval = setInterval(() => {
      remaining -= 1;
      this.errorDetails.update((current: DashboardErrorDetails | null) => {
        if (!current) return current;
        return { ...current, retryIn: Math.max(remaining, 0) };
      });
      if (remaining <= 0) this.clearRetryCountdownInterval();
    }, 1000);
  }

  private clearRetryCountdownInterval(): void {
    if (this.retryCountdownInterval) {
      clearInterval(this.retryCountdownInterval);
      this.retryCountdownInterval = undefined;
    }
  }

  private clearRetryTimers(): void {
    if (this.autoRetryTimeout) {
      clearTimeout(this.autoRetryTimeout);
      this.autoRetryTimeout = undefined;
    }
    this.clearRetryCountdownInterval();
  }

  toggleSenderSelection(senderId: number, event: MouseEvent | Event): void {
    event.stopPropagation();
    this.selectedSenderIds.update((current: Set<number>) => {
      const next = new Set(current);
      if (next.has(senderId)) next.delete(senderId);
      else next.add(senderId);
      return next;
    });
  }

  onBulkSelectionChanged(ids: Set<number>): void {
    this.selectedSenderIds.set(ids);
  }

  dismissLimitsBanner(): void {
    this.limitsBannerDismissed.set(true);
  }

  dispatchSender(sender: SenderPerformance): void {
    this.backend.dispatch({ site: sender.site, senderId: sender.senderId }).subscribe({
      next: () => {
        this.toast.success(`Dispatch initiated for ${sender.senderLabel}`, 3000);
      },
      error: (err: unknown) => {
        console.error('Dispatch failed for sender', sender.senderId, err);
        this.toast.error(`Dispatch failed: ${this.extractErrorMessage(err)}`, 7000);
      },
    });
  }

  // ── Parameterized helpers (can't be computed — called with template variables) ──

  getMetricChangeKey(metricLabel: string): string {
    switch (metricLabel) {
      case 'Staged':
        return 'metric-ready';
      case 'Queued for Enrichment':
        return 'metric-queued';
      case 'Enrichment Processing':
        return 'metric-enriching';
      case 'Enrichment Monitoring Timeout':
        return 'metric-enrichmentTimeout';
      case 'Exensio Monitoring':
        return 'metric-exensioLoading';
      case 'Completed — Verify in Exensio':
        return 'metric-exensioTimeout';
      case 'Completed':
        return 'metric-completed';
      case 'Failed':
        return 'metric-failed';
      case 'Cancelled':
        return 'metric-cancelled';
      default:
        return `metric-${metricLabel.toLowerCase().replace(/\s+/g, '-')}`;
    }
  }

  getHealthStatusLabel(score: number): string {
    if (score >= 95) return 'Excellent';
    if (score >= 85) return 'Good';
    if (score >= 70) return 'Fair';
    return 'Poor';
  }

  getHealthStatusColor(score: number): string {
    if (score >= 95) return '#10b981';
    if (score >= 85) return '#3b82f6';
    if (score >= 70) return '#f59e0b';
    return '#ef4444';
  }

  fmtTimeAgo(date: Date | null): string {
    if (!date) return 'Never';
    const seconds = Math.floor((Date.now() - date.getTime()) / 1000);
    if (seconds < 60) return 'Just now';
    if (seconds < 3600) return `${Math.floor(seconds / 60)}m ago`;
    if (seconds < 86400) return `${Math.floor(seconds / 3600)}h ago`;
    return `${Math.floor(seconds / 86400)}d ago`;
  }

  getBacklogCapacity(_sender: SenderPerformance): number {
    const cap = this.resolvedLimits()?.stageMaxRowsCap ?? 0;
    return cap > 0 ? cap : environment.monitoring.monitorMaxRows;
  }

  getBacklogRatio(sender: SenderPerformance): number {
    const capacity = this.getBacklogCapacity(sender);
    return capacity > 0 ? sender.backlog / capacity : 0;
  }

  getBacklogFillPercentage(sender: SenderPerformance): number {
    return Math.min(this.getBacklogRatio(sender) * 100, 100);
  }

  getBacklogTooltip(sender: SenderPerformance): string {
    const cap = this.getBacklogCapacity(sender);
    return `${sender.backlog.toLocaleString()} / ${cap.toLocaleString()} backlog (cap: ${cap.toLocaleString()})`;
  }

  getBacklogStatus(sender: SenderPerformance): 'normal' | 'warning' | 'critical' {
    const ratio = this.getBacklogRatio(sender);
    if (ratio > 1) return 'critical';
    if (ratio >= 0.75) return 'warning';
    return 'normal';
  }

  getFileListLabel(loaded: number, total: number, cap: number): string {
    if (loaded >= total) {
      return `Showing ${loaded.toLocaleString()} of ${total.toLocaleString()}`;
    }
    if (loaded >= cap) {
      return `Showing ${loaded.toLocaleString()} of ${total.toLocaleString()} (cap reached)`;
    }
    return `Showing ${loaded.toLocaleString()} of ${total.toLocaleString()} (cap: ${cap.toLocaleString()})`;
  }

  isSiteExpanded(siteName: string): boolean {
    return this.expandedSites().has(siteName);
  }

  visibleSenders(site: DashboardSiteSnapshot): DashboardSenderSnapshot[] {
    return this.isSiteExpanded(site.site) ? site.senders : site.senders.slice(0, 3);
  }

  hasHiddenSenders(site: DashboardSiteSnapshot): boolean {
    return site.senders.length > 3;
  }

  hiddenSenderCount(site: DashboardSiteSnapshot): number {
    return Math.max(site.senders.length - 3, 0);
  }

  toggleSiteSenders(siteName: string): void {
    this.expandedSites.update((current: Set<string>) => {
      const next = new Set(current);
      if (next.has(siteName)) next.delete(siteName);
      else next.add(siteName);
      return next;
    });
  }

  // ── Private helpers ─────────────────────────────────────────────

  private setupFreshnessMonitor(): void {
    this.freshnessPollInterval = setInterval(() => {
      const ageSeconds = this.dataAgeSeconds();
      if (!Number.isFinite(ageSeconds)) {
        this.dataFreshness.set('fresh');
        return;
      }
      if (ageSeconds < 30) {
        this.dataFreshness.set('fresh');
        this.freshnessBannerDismissed.set(false);
      } else if (ageSeconds < 60) {
        this.dataFreshness.set('stale');
      } else {
        this.dataFreshness.set('very-stale');
      }
    }, 1000);
  }

  private extractHttpStatus(err: unknown): number | undefined {
    if (typeof err === 'object' && err !== null && 'status' in err) {
      const value = (err as { status?: unknown }).status;
      return typeof value === 'number' ? value : undefined;
    }
    return undefined;
  }

  private classifyDashboardError(status?: number): Pick<DashboardErrorDetails, 'message' | 'code' | 'details'> {
    if (status === 0) {
      return {
        message: 'Unable to connect to server',
        code: 'NO_CONNECTION',
        details: 'Check your network connection, VPN, or firewall settings.',
      };
    }
    if (status === 408 || status === 504) {
      return {
        message: 'Request timed out',
        code: 'TIMEOUT',
        details: 'The server took too long to respond. Please wait while we retry automatically.',
      };
    }
    if (status === 401 || status === 403) {
      return {
        message: 'Authentication failed',
        code: 'AUTH_ERROR',
        details: 'Your session may have expired. Please sign in again.',
      };
    }
    if (typeof status === 'number' && (status === 500 || status === 502 || status === 503)) {
      return {
        message: 'Server error',
        code: 'SERVER_ERROR',
        details: 'The backend service is currently unavailable. Retrying automatically.',
      };
    }
    if (typeof status === 'number' && status >= 400 && status < 500) {
      return {
        message: 'Invalid request',
        code: 'CLIENT_ERROR',
        details: `The server returned ${status}. Please retry or contact support if this persists.`,
      };
    }
    return {
      message: 'Could not connect to backend services.',
      code: 'UNKNOWN',
      details: 'An unexpected error occurred while loading dashboard data.',
    };
  }

  private detectChanges(previous: DashboardSnapshot | null, current: DashboardSnapshot | null): Set<string> {
    const changed = new Set<string>();
    if (!previous || !current) return changed;

    const trackedMetrics: Array<keyof DashboardSnapshot['global']> = [
      'ready',
      'queued',
      'enriching',
      'enrichmentTimeout',
      'exensioLoading',
      'exensioTimeout',
      'completed',
      'failed',
      'cancelled',
      'activeSenders',
    ];
    trackedMetrics.forEach((metric) => {
      if (previous.global[metric] !== current.global[metric]) {
        changed.add(`metric-${metric}`);
      }
    });

    const previousSites = new Map(previous.sites.map((site) => [site.site, site]));
    current.sites.forEach((site) => {
      const previousSite = previousSites.get(site.site);
      if (!previousSite) {
        changed.add(`site-${site.site}`);
        return;
      }
      if (
        previousSite.metrics.ready !== site.metrics.ready ||
        previousSite.metrics.completed !== site.metrics.completed ||
        previousSite.metrics.failed !== site.metrics.failed ||
        previousSite.metrics.cancelled !== site.metrics.cancelled
      ) {
        changed.add(`site-${site.site}`);
      }
      const previousSenders = new Map(previousSite.senders.map((s) => [s.senderId, s]));
      site.senders.forEach((sender) => {
        const previousSender = previousSenders.get(sender.senderId);
        if (!previousSender) {
          changed.add(`sender-${sender.senderId}`);
          return;
        }
        if (
          previousSender.metrics.ready !== sender.metrics.ready ||
          previousSender.metrics.completed !== sender.metrics.completed ||
          previousSender.metrics.failed !== sender.metrics.failed ||
          previousSender.metrics.cancelled !== sender.metrics.cancelled
        ) {
          changed.add(`sender-${sender.senderId}`);
        }
      });
    });

    return changed;
  }

  private extractErrorMessage(err: any): string {
    if (err?.error?.message) return err.error.message;
    if (err?.error?.detail) return err.error.detail;
    if (err?.error?.error) return err.error.error;
    if (err?.statusText) return err.statusText;
    if (err?.message) return err.message;
    if (err?.status === 409) return 'Conflict: Dispatch already in progress';
    if (err?.status === 400) return 'Invalid request data';
    if (err?.status === 403) return 'You do not have permission to dispatch';
    if (err?.status === 500) return 'Server error occurred';
    return 'Failed to dispatch sender';
  }

  private loadActiveMonitoringSessionContext() {
    const persistedId = this.getPersistedMonitoringSession();
    if (!persistedId) {
      this.activeMonitoringSession.set(null);
      return;
    }
    this.backend.getStagingSession(persistedId).subscribe({
      next: (session: StagingSessionDetail) => {
        const status = (session?.status || '').toUpperCase();
        if (['COMPLETED', 'PARTIALLY_FAILED', 'CANCELLED'].includes(status)) {
          this.clearPersistedMonitoringSession();
          this.activeMonitoringSession.set(null);
          return;
        }
        this.activeMonitoringSession.set({
          sessionId: session.sessionId,
          site: session.site,
          senderId: session.senderId,
          senderLabel: session.senderName ?? String(session.senderId),
        });
      },
      error: () => {
        this.clearPersistedMonitoringSession();
        this.activeMonitoringSession.set(null);
      },
    });
  }

  private getPersistedMonitoringSession(): string | null {
    try {
      return localStorage.getItem(this.monitoringResumeStorageKey);
    } catch {
      return null;
    }
  }

  private clearPersistedMonitoringSession() {
    try {
      localStorage.removeItem(this.monitoringResumeStorageKey);
    } catch {
      // no-op
    }
  }

  /**
   * Connect to dashboard state stream and handle timeout state events.
   * Updates dashboard card counts in real-time when records transition to timeout states.
   * Validates: Requirements 7.1, 7.2, 7.3, 7.4
   * Property 9: Frontend SSE Event Handling
   */
  private connectDashboardStateStream(): void {
    this.stateStreamSub = this.backend.connectDashboardStateStream().subscribe({
      next: (event: any) => {
        this.handleStateChangeEvent(event);
      },
      error: (err: unknown) => {
        console.warn('Dashboard state stream error (non-fatal, dashboard will use polling):', err);
        // Non-fatal error - dashboard continues to work with polling updates
      },
      complete: () => {
        console.log('Dashboard state stream closed');
      },
    });
  }

  /**
   * Handle a single state change event from the SSE stream.
   * Updates dashboard metrics when records transition to ENRICHMENT_TIMEOUT or EXENSIO_TIMEOUT.
   * Filters events by active device filter (Requirements 4.3).
   *
   * @param event The state change event containing before/after states and count
   */
  private handleStateChangeEvent(event: any): void {
    const currentSnap = this.snapshot();
    if (!currentSnap) {
      return; // No snapshot loaded yet
    }

    const afterState = event.afterState || '';
    const count = event.count || 0;
    const eventDevice = event.device || null;

    if (count <= 0) {
      return; // No records changed
    }

    // Filter by active device filter
    // If device filters are active, only process events matching those devices
    // Requirements 4.3: Apply device filter to real-time SSE updates
    const activeDevices = this.devices();
    if (activeDevices && activeDevices.length > 0 && eventDevice) {
      if (!activeDevices.includes(eventDevice)) {
        return; // Event device not in active filter, skip it
      }
    }

    // Create updated snapshot with incremented timeout counts
    const updated: DashboardSnapshot = {
      ...currentSnap,
      global: { ...currentSnap.global },
      sites: currentSnap.sites.map((site) => ({
        ...site,
        metrics: { ...site.metrics },
        senders: site.senders.map((sender) => ({
          ...sender,
          metrics: { ...sender.metrics },
        })),
      })),
    };

    // Update global metrics based on state transition
    // ENRICHMENT → ENRICHMENT_TIMEOUT: decrement enriching, increment enrichmentTimeout
    if (afterState === 'CP_TIMEOUT') {
      updated.global.enriching = Math.max(0, (updated.global.enriching || 0) - count);
      updated.global.enrichmentTimeout = (updated.global.enrichmentTimeout || 0) + count;

      // Update per-site and per-sender metrics
      updated.sites.forEach((site) => {
        site.metrics.enriching = Math.max(0, (site.metrics.enriching || 0) - count);
        site.metrics.enrichmentTimeout = (site.metrics.enrichmentTimeout || 0) + count;
        site.senders.forEach((sender) => {
          sender.metrics.enriching = Math.max(0, (sender.metrics.enriching || 0) - count);
          sender.metrics.enrichmentTimeout = (sender.metrics.enrichmentTimeout || 0) + count;
        });
      });

      // Mark metrics as changed for visual feedback
      this.changedMetrics.set(new Set(['metric-enrichmentTimeout']));
      setTimeout(() => this.changedMetrics.set(new Set<string>()), 600);
    }
    // EXENSIO_LOADING → EXENSIO_TIMEOUT: decrement exensioLoading, increment exensioTimeout
    else if (afterState === 'COMPLETED_MANUAL_VERIFICATION_REQUIRED') {
      updated.global.exensioLoading = Math.max(0, (updated.global.exensioLoading || 0) - count);
      updated.global.exensioTimeout = (updated.global.exensioTimeout || 0) + count;

      // Update per-site and per-sender metrics
      updated.sites.forEach((site) => {
        site.metrics.exensioLoading = Math.max(0, (site.metrics.exensioLoading || 0) - count);
        site.metrics.exensioTimeout = (site.metrics.exensioTimeout || 0) + count;
        site.senders.forEach((sender) => {
          sender.metrics.exensioLoading = Math.max(0, (sender.metrics.exensioLoading || 0) - count);
          sender.metrics.exensioTimeout = (sender.metrics.exensioTimeout || 0) + count;
        });
      });

      // Mark metrics as changed for visual feedback
      this.changedMetrics.set(new Set(['metric-exensioTimeout']));
      setTimeout(() => this.changedMetrics.set(new Set<string>()), 600);
    }

    // Update snapshot with new metrics
    this.snapshot.set(updated);
    this.lastUpdated.set(new Date());
    this.lastUpdatePulse.set(true);
    setTimeout(() => this.lastUpdatePulse.set(false), 300);
  }
}
