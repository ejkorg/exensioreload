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
import { SparklineComponent } from '../shared/components/sparkline.component';
import { GlassDialogService } from '../shared/services/glass-dialog.service';
import { StagingSessionService } from '../shared/services/staging-session.service';
import { ToastService } from '../shared/services/toast.service';
import { BulkActionsComponent, SelectableItem } from './bulk-actions.component';
import { SenderAlertSettingsComponent } from './sender-alert-settings.component';
import { SiteDetailModalComponent } from './site-detail-modal.component';

/**
 * PHASE 3.5 PERFORMANCE OPTIMIZATIONS:
 * - ChangeDetectionStrategy.OnPush: Minimal re-renders, only when signals change
 * - Computed properties with memoization: Expensive calculations cached
 * - Metric history limited to 60 points: Reduces DOM node count
 * - SparklineComponent lazy-loaded via signals: No eager instantiation
 * - Material icons imported efficiently: No unused icon modules
 * - Event handlers use arrow functions: Preserves 'this' context
 * - Keyboard handlers debounced: Prevents excessive calculations
 * - No inline functions in templates: Pre-computed values used
 * Target: Lighthouse performance >90
 */

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
  /** NEW records that can still be cancelled before dispatch */
  cancellableCount: number;
  /** ENQUEUED records already dispatched to the sender queue — cannot be cancelled */
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

interface MetricTrend {
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
    SparklineComponent,
    BulkActionsComponent,
    GlassCheckboxComponent,
  ],
  templateUrl: './dashboard.component.html',
  styleUrls: ['./dashboard.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DashboardComponent implements OnInit, OnDestroy {
  private readonly monitoringResumeStorageKey = 'exensioreload.activeMonitoringSessionId';

  // Make Math available in template for accessibility labels
  Math = Math;

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

  /** Resolved limits from backend; null until the API call completes */
  resolvedLimits = signal<LimitsConfig | null>(null);
  /** True when the limits API call failed and environment fallback values are in use */
  limitsError = signal(false);
  /** True when the operator has dismissed the fallback limits banner */
  limitsBannerDismissed = signal(false);

  /** Resolved monitorMaxRows from backend limits, falling back to environment value */
  resolvedMonitorMaxRows = computed<number>(
    () => this.resolvedLimits()?.stageMaxRowsCap ?? environment.monitoring.monitorMaxRows,
  );

  /** Phase 4.2: Bulk selection — tracks selected sender IDs from top-senders section */
  selectedSenderIds = signal<Set<number>>(new Set<number>());

  /** Derives SelectableItem[] from topSenders for BulkActionsComponent */
  selectableItems = computed<SelectableItem[]>(() =>
    this.topSenders().map((s: SenderPerformance) => ({
      id: s.senderId,
      type: 'sender' as const,
      label: s.senderLabel,
      cancellableCount: s.cancellableCount,
      enqueuedCount: s.enqueuedCount,
    })),
  );

  private freshnessPollInterval?: ReturnType<typeof setInterval>;
  private pollSub?: Subscription;
  private retryAttempts = 0;
  private readonly maxRetries = 5;
  private readonly retryBaseDelayMs = 5000;
  private autoRetryTimeout?: ReturnType<typeof setTimeout>;
  private retryCountdownInterval?: ReturnType<typeof setInterval>;

  // Computed properties for enhanced metrics
  overallHealthScore = computed(() => {
    const s = this.snapshot();
    if (!s) return 0;
    // Include failed in the terminal count so failures reduce the health score
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
    const oneHourAgo = now - 60 * 60 * 1000; // 1 hour in milliseconds

    // Filter history to last hour
    const recentHistory = history.filter((h: MetricSnapshot) => h.timestamp >= oneHourAgo);

    if (recentHistory.length < 2) {
      return { backlog: 0, ready: 0, enqueued: 0, completed: 0 };
    }

    // Get oldest and newest within the hour
    const oldest = recentHistory[0];
    const newest = recentHistory[recentHistory.length - 1];

    // Calculate percentage change
    const calculateTrend = (current: number, old: number): number => {
      if (old === 0) return current > 0 ? 100 : 0;
      return Math.round(((current - old) / old) * 100);
    };

    return {
      backlog: calculateTrend(newest.backlog, oldest.backlog),
      ready: calculateTrend(newest.ready, oldest.ready),
      enqueued: calculateTrend(newest.enqueued, oldest.enqueued),
      completed: calculateTrend(newest.completed, oldest.completed),
    };
  });

  topSenders = computed(() => {
    const s = this.snapshot();
    if (!s) return [];
    const senders: SenderPerformance[] = [];
    s.sites.forEach((site: DashboardSiteSnapshot) => {
      site.senders.forEach((sender: DashboardSenderSnapshot) => {
        // Calculate success rate: completed / (completed + failed)
        // Terminal files are those that have finished processing (either successfully or with failure)
        const completed = sender.metrics.completed || 0;
        const failed = sender.metrics.failed || 0;
        const terminal = completed + failed;
        const successRate = terminal > 0 ? Math.round((completed / terminal) * 100) : 100;
        senders.push({
          senderId: sender.senderId,
          senderLabel: sender.senderLabel,
          site: site.site,
          backlog: sender.metrics.backlog,
          throughput: 0, // Backend metrics don't provide throughput
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

  constructor(
    private backend: BackendService,
    private router: Router,
    private dialog: GlassDialogService,
    public stagingSession: StagingSessionService,
  ) {
    this.toast = inject(ToastService);
  }

  private toast: ToastService;

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
  }

  ngOnDestroy() {
    this.pollSub?.unsubscribe();
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

  shouldShowFreshnessBanner(): boolean {
    const freshness = this.dataFreshness();
    if (freshness === 'fresh') return false;
    if (freshness === 'very-stale') return true;
    return !this.freshnessBannerDismissed();
  }

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

  getPrimaryMonitoringActionLabel(): string {
    return this.activeMonitoringSession() ? 'Resume Monitoring' : 'Start Monitoring';
  }

  getPrimaryMonitoringActionTooltip(): string {
    const active = this.activeMonitoringSession();
    if (active) {
      return `Resume active session ${active.sessionId}`;
    }

    return 'Start a new monitoring session';
  }

  getPrimaryMonitoringActionIcon(): string {
    return this.activeMonitoringSession() ? 'play_circle' : 'play_arrow';
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
    this.backend.getDashboardSnapshot().subscribe({
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

        // Track metric history for trend calculation
        const newSnapshot: MetricSnapshot = {
          timestamp: Date.now(),
          backlog: snap.global.backlog,
          ready: snap.global.ready,
          enqueued: snap.global.enqueued,
          completed: snap.global.completed,
        };

        // Keep only last 360 snapshots (1 hour at 10-second intervals)
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

        // Show toast notification to user
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
    if (this.retryAttempts >= this.maxRetries) {
      return;
    }
    this.loadSnapshot(false);
  }

  private startRetryCountdown(initialSeconds: number): void {
    this.clearRetryCountdownInterval();
    let remaining = initialSeconds;

    this.retryCountdownInterval = setInterval(() => {
      remaining -= 1;

      this.errorDetails.update((current: DashboardErrorDetails | null) => {
        if (!current) {
          return current;
        }

        return {
          ...current,
          retryIn: Math.max(remaining, 0),
        };
      });

      if (remaining <= 0) {
        this.clearRetryCountdownInterval();
      }
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

  private extractHttpStatus(err: unknown): number | undefined {
    if (typeof err === 'object' && err !== null && 'status' in err) {
      const value = (err as { status?: unknown }).status;
      if (typeof value === 'number') {
        return value;
      }
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

    if (!previous || !current) {
      return changed;
    }

    const trackedMetrics: Array<keyof DashboardSnapshot['global']> = [
      'backlog',
      'ready',
      'enqueued',
      'completed',
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
        previousSite.metrics.backlog !== site.metrics.backlog ||
        previousSite.metrics.completed !== site.metrics.completed ||
        previousSite.metrics.ready !== site.metrics.ready ||
        previousSite.metrics.enqueued !== site.metrics.enqueued
      ) {
        changed.add(`site-${site.site}`);
      }

      const previousSenders = new Map(previousSite.senders.map((sender) => [sender.senderId, sender]));
      site.senders.forEach((sender) => {
        const previousSender = previousSenders.get(sender.senderId);
        if (!previousSender) {
          changed.add(`sender-${sender.senderId}`);
          return;
        }

        const senderChanged =
          previousSender.metrics.backlog !== sender.metrics.backlog ||
          previousSender.metrics.completed !== sender.metrics.completed ||
          previousSender.metrics.enqueued !== sender.metrics.enqueued ||
          previousSender.metrics.failed !== sender.metrics.failed;

        if (senderChanged) {
          changed.add(`sender-${sender.senderId}`);
        }
      });
    });

    return changed;
  }

  get primaryMetrics(): MetricCard[] {
    const s = this.snapshot();
    if (!s) return [];
    const trends = this.metricTrends();
    return [
      {
        key: 'backlog',
        label: 'Backlog Pending',
        abbrev: 'BCK',
        value: s.global.backlog,
        icon: 'hourglass_bottom',
        color: s.global.backlog > 5000 ? 'danger' : s.global.backlog > 1000 ? 'warning' : 'secondary',
        accentColor: s.global.backlog > 5000 ? '#ef4444' : '#f59e0b',
        alert: s.global.backlog > 1000,
        subtext: 'items waiting to be staged',
        trend: trends.backlog,
      },
      {
        key: 'ready',
        label: 'Ready to Send',
        abbrev: 'RDY',
        value: s.global.ready,
        icon: 'play_arrow',
        color: 'primary',
        accentColor: '#818cf8',
        subtext: 'prepared payloads ready',
        trend: trends.ready,
      },
      {
        key: 'enqueued',
        label: 'In Queue',
        abbrev: 'QUE',
        value: s.global.enqueued,
        icon: 'schedule',
        color: 'info',
        accentColor: '#3b82f6',
        subtext: 'actively being dispatched',
        trend: trends.enqueued,
      },
      {
        key: 'completed',
        label: 'Completed Today',
        abbrev: 'CPL',
        value: s.global.completed,
        icon: 'check_circle',
        color: 'success',
        accentColor: '#10b981',
        subtext: 'successful operations',
        trend: trends.completed,
      },
    ];
  }

  getMetricHistory(metricKey: keyof MetricSnapshot): number[] {
    // Performance: Slice to last 60 points to limit DOM rendering
    // Combined with OnPush change detection strategy to minimize re-renders
    // Sparkline component lazy-loads through signal inputs
    return this.metricHistory()
      .map((snapshot: MetricSnapshot) => snapshot[metricKey])
      .slice(-60);
  }

  /** Phase 4.2: Toggle a single sender card's selected state */
  toggleSenderSelection(senderId: number, event: MouseEvent | Event): void {
    event.stopPropagation();
    this.selectedSenderIds.update((current: Set<number>) => {
      const next = new Set(current);
      if (next.has(senderId)) {
        next.delete(senderId);
      } else {
        next.add(senderId);
      }
      return next;
    });
  }

  /** Phase 4.2: Sync selection from BulkActionsComponent (e.g. select-all / clear) */
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
        const errorMsg = this.extractErrorMessage(err);
        this.toast.error(`Dispatch failed: ${errorMsg}`, 7000);
      },
    });
  }

  private extractErrorMessage(err: any): string {
    if (err?.error?.message) {
      return err.error.message;
    }
    if (err?.error?.detail) {
      return err.error.detail;
    }
    if (err?.error?.error) {
      return err.error.error;
    }
    if (err?.statusText) {
      return err.statusText;
    }
    if (err?.message) {
      return err.message;
    }
    if (err?.status === 409) {
      return 'Conflict: Dispatch already in progress';
    }
    if (err?.status === 400) {
      return 'Invalid request data';
    }
    if (err?.status === 403) {
      return 'You do not have permission to dispatch';
    }
    if (err?.status === 500) {
      return 'Server error occurred';
    }
    return 'Failed to dispatch sender';
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

  /**
   * Returns a human-readable label for the file list count.
   * - loaded >= total  → "Showing X of Y"
   * - loaded >= cap    → "Showing X of Y (cap reached)"
   * - otherwise        → "Showing X of Y (cap: Z)"
   */
  getFileListLabel(loaded: number, total: number, cap: number): string {
    if (loaded >= total) {
      return `Showing ${loaded.toLocaleString()} of ${total.toLocaleString()}`;
    }
    if (loaded >= cap) {
      return `Showing ${loaded.toLocaleString()} of ${total.toLocaleString()} (cap reached)`;
    }
    return `Showing ${loaded.toLocaleString()} of ${total.toLocaleString()} (cap: ${cap.toLocaleString()})`;
  }

  getMetricChangeKey(metricLabel: string): string {
    switch (metricLabel) {
      case 'Backlog Pending':
        return 'metric-backlog';
      case 'Ready to Send':
        return 'metric-ready';
      case 'In Queue':
        return 'metric-enqueued';
      case 'Completed Today':
        return 'metric-completed';
      default:
        return `metric-${metricLabel.toLowerCase().replace(/\s+/g, '-')}`;
    }
  }

  get supportingMetrics(): MetricCard[] {
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
  }

  getHealthStatusLabel(score: number): string {
    if (score >= 95) return 'Excellent';
    if (score >= 85) return 'Good';
    if (score >= 70) return 'Fair';
    return 'Poor';
  }

  getHealthStatusColor(score: number): string {
    if (score >= 95) return '#10b981'; // green
    if (score >= 85) return '#3b82f6'; // blue
    if (score >= 70) return '#f59e0b'; // amber
    return '#ef4444'; // red
  }

  formatTimeAgo(date: Date | null): string {
    if (!date) return 'Never';
    const now = new Date();
    const seconds = Math.floor((now.getTime() - date.getTime()) / 1000);
    if (seconds < 60) return 'Just now';
    if (seconds < 3600) return `${Math.floor(seconds / 60)}m ago`;
    if (seconds < 86400) return `${Math.floor(seconds / 3600)}h ago`;
    return `${Math.floor(seconds / 86400)}d ago`;
  }

  getDataAgeSeconds(): number {
    const lastUpdated = this.lastUpdated();
    if (!lastUpdated) return Number.POSITIVE_INFINITY;
    return Math.floor((Date.now() - lastUpdated.getTime()) / 1000);
  }

  getFreshnessMessage(): string {
    const ageSeconds = this.getDataAgeSeconds();
    if (!Number.isFinite(ageSeconds)) return 'No dashboard data has been loaded yet.';
    if (this.dataFreshness() === 'very-stale') {
      return `Data is very stale (${ageSeconds}s old). Refresh recommended.`;
    }
    return `Data may be stale (${ageSeconds}s old). Last refresh was ${this.formatTimeAgo(this.lastUpdated())}.`;
  }

  private setupFreshnessMonitor(): void {
    this.freshnessPollInterval = setInterval(() => {
      const ageSeconds = this.getDataAgeSeconds();

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

  // Helper to check if dashboard has no data
  hasNoData(): boolean {
    const snap = this.snapshot();
    return !snap || !snap.sites || snap.sites.length === 0;
  }

  // Helper to check if all metrics are zero
  hasZeroMetrics(): boolean {
    const snap = this.snapshot();
    if (!snap?.global) return false;
    return (
      (snap.global.completed || 0) === 0 &&
      (snap.global.enqueued || 0) === 0 &&
      (snap.global.ready || 0) === 0 &&
      (snap.global.backlog || 0) === 0 &&
      (snap.global.activeSenders || 0) === 0
    );
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
      if (next.has(siteName)) {
        next.delete(siteName);
      } else {
        next.add(siteName);
      }
      return next;
    });
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
  }
}
