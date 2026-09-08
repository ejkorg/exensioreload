import { Injectable } from '@angular/core';
import { BehaviorSubject, Observable } from 'rxjs';

export interface SenderThresholds {
  senderId: string | number;
  backlogWarning: number; // default 500
  backlogCritical: number; // default 5000
  errorRateWarning: number; // default 10 (%)
  errorRateCritical: number; // default 25 (%)
  throughputMin: number; // files per hour minimum (0 = disabled)
}

export interface SenderMetrics {
  backlog: number;
  errorRate: number; // percentage 0-100
  throughput: number; // files per hour
}

export type AlertLevel = 'warning' | 'critical';

export interface AlertEvent {
  senderId: string | number;
  metric: 'backlog' | 'errorRate' | 'throughput';
  level: AlertLevel;
  currentValue: number;
  threshold: number;
  timestamp: Date;
}

const DEFAULT_THRESHOLDS: Omit<SenderThresholds, 'senderId'> = {
  backlogWarning: 500,
  backlogCritical: 5000,
  errorRateWarning: 10,
  errorRateCritical: 25,
  throughputMin: 0,
};

const storageKey = (senderId: string | number) => `exensioreload.alerts.sender.${senderId}`; // Req 3.3

/**
 * AlertThresholdService manages per-sender threshold configuration persisted to
 * localStorage (key pattern `exensioreload.alerts.sender.{senderId}`) and
 * evaluates live metrics against those thresholds to emit alert events.
 *
 * Requirement 3.1 – configurable threshold sliders (UI reads via getThresholds)
 * Requirement 3.2 – evaluateMetrics drives visual indicators
 * Requirement 3.3 – persistence round-trip via localStorage
 * Requirement 3.4 – re-evaluate immediately after updateThresholds
 * Requirement 3.5 – documented defaults above
 */
@Injectable({ providedIn: 'root' })
export class AlertThresholdService {
  private readonly alertsSubject = new BehaviorSubject<AlertEvent | null>(null);
  private readonly thresholdsCache = new Map<string | number, SenderThresholds>();

  /** Stream of triggered alert events for UI subscription. */
  readonly alerts$: Observable<AlertEvent | null> = this.alertsSubject.asObservable();

  /** Retrieve thresholds from localStorage or return defaults (Requirement 3.1). */
  getThresholds(senderId: string | number): SenderThresholds {
    const cached = this.thresholdsCache.get(senderId);
    if (cached) return { ...cached };

    try {
      const raw = localStorage.getItem(storageKey(senderId));
      if (raw) {
        const parsed = JSON.parse(raw) as Partial<SenderThresholds>;
        const thresholds: SenderThresholds = { senderId, ...DEFAULT_THRESHOLDS, ...parsed };
        this.thresholdsCache.set(senderId, thresholds);
        return { ...thresholds };
      }
    } catch {
      // fall through to defaults
    }

    const defaults: SenderThresholds = { senderId, ...DEFAULT_THRESHOLDS };
    this.thresholdsCache.set(senderId, defaults);
    return { ...defaults };
  }

  /**
   * Persist threshold overrides (Requirement 3.3) then immediately re-evaluate
   * the provided metrics so visual indicators update in the same cycle (Req 3.4).
   */
  updateThresholds(senderId: string | number, thresholds: Partial<SenderThresholds>, metrics?: SenderMetrics): SenderThresholds {
    const current = this.getThresholds(senderId);
    const merged: SenderThresholds = { ...current, ...thresholds, senderId };

    try {
      localStorage.setItem(storageKey(senderId), JSON.stringify(merged));
    } catch {
      // storage unavailable — still update in-memory cache
    }
    this.thresholdsCache.set(senderId, merged);

    if (metrics) {
      this.evaluateMetrics(senderId, metrics);
    }
    return { ...merged };
  }

  /**
   * Compare current metrics against configured thresholds.
   * Returns all triggered alert events (Requirement 3.2).
   */
  evaluateMetrics(senderId: string | number, metrics: SenderMetrics): AlertEvent[] {
    const t = this.getThresholds(senderId);
    const events: AlertEvent[] = [];
    const push = (metric: AlertEvent['metric'], level: AlertLevel, currentValue: number, threshold: number) => {
      events.push({ senderId, metric, level, currentValue, threshold, timestamp: new Date() });
      this.alertsSubject.next({ senderId, metric, level, currentValue, threshold, timestamp: new Date() });
    };

    // Backlog
    if (metrics.backlog > t.backlogCritical) {
      push('backlog', 'critical', metrics.backlog, t.backlogCritical);
    } else if (metrics.backlog > t.backlogWarning) {
      push('backlog', 'warning', metrics.backlog, t.backlogWarning);
    }

    // Error rate (higher is worse)
    if (metrics.errorRate > t.errorRateCritical) {
      push('errorRate', 'critical', metrics.errorRate, t.errorRateCritical);
    } else if (metrics.errorRate > t.errorRateWarning) {
      push('errorRate', 'warning', metrics.errorRate, t.errorRateWarning);
    }

    // Throughput (lower is worse, only when a floor is configured)
    if (t.throughputMin > 0 && metrics.throughput < t.throughputMin) {
      push('throughput', 'warning', metrics.throughput, t.throughputMin);
    }

    return events;
  }

  /**
   * Convenience: highest severity currently breached, or null when healthy.
   */
  evaluateLevel(senderId: string | number, metrics: SenderMetrics): AlertLevel | null {
    const events = this.evaluateMetrics(senderId, metrics);
    if (events.some((e) => e.level === 'critical')) return 'critical';
    if (events.length > 0) return 'warning';
    return null;
  }

  /** Reset a sender's thresholds back to defaults and clear storage. */
  resetThresholds(senderId: string | number): SenderThresholds {
    try {
      localStorage.removeItem(storageKey(senderId));
    } catch {
      // ignore
    }
    this.thresholdsCache.delete(senderId);
    return this.getThresholds(senderId);
  }
}
