import { Injectable, signal } from '@angular/core';
import { Subject, throttleTime } from 'rxjs';

export type MetricTimeWindow = '1h' | '6h' | '24h' | '7d';

export interface MetricDataPoint {
  timestamp: number; // epoch ms
  value: number;
}

export interface MetricSnapshot {
  timestamp: number;
  metricKey: string;
  value: number;
}

export const DEFAULT_TIME_WINDOW: MetricTimeWindow = '6h';

const WINDOW_DURATION_MS: Record<MetricTimeWindow, number> = {
  '1h': 60 * 60 * 1000,
  '6h': 6 * 60 * 60 * 1000,
  '24h': 24 * 60 * 60 * 1000,
  '7d': 7 * 24 * 60 * 60 * 1000,
};

/** Max data points retained per metric before down-sampling oldest samples. */
const MAX_POINTS_PER_METRIC = 720;

/**
 * MetricsHistoryService aggregates pipeline metric snapshots in memory and
 * serves sliding-window time-series series for charts.
 *
 * Data arrives from:
 *  - dashboard snapshot polling (initial hydration)
 *  - SSE state aggregation events (appendRealTimeSnapshot)
 *
 * Charts subscribe to {@link updates$} which is throttled to one emission per
 * 500ms so rapid SSE bursts cause exactly one re-render (Requirement 6.4).
 */
@Injectable({ providedIn: 'root' })
export class MetricsHistoryService {
  /** One in-memory series per metric key, oldest → newest. */
  private readonly history = new Map<string, MetricDataPoint[]>();

  /** Per-metric latest value (for KPI sparkline tails and throughput math). */
  readonly latestValues = signal<Record<string, number>>({});

  /**
   * Throttled update stream. Charts subscribe here and re-render at most once
   * per 500ms window (Requirement 6.4 / Property 16).
   */
  readonly updates$ = new Subject<void>();

  constructor() {
    // One emission per 500ms window; leading + trailing so the final state is always painted.
    this.updates$.pipe(throttleTime(500, undefined, { leading: true, trailing: true })).subscribe();
  }

  /**
   * Append a full dashboard metric snapshot (all metric keys at one timestamp).
   */
  appendSnapshot(timestamp: number, totals: Record<string, number>): void {
    let changed = false;
    for (const [metricKey, value] of Object.entries(totals)) {
      if (typeof value !== 'number' || !Number.isFinite(value)) continue;
      this.pushPoint(metricKey, { timestamp, value });
      changed = true;
    }
    if (changed) {
      this.updates$.next();
    }
  }

  /**
   * Append one data point for a metric key.
   */
  appendPoint(metricKey: string, point: MetricDataPoint): void {
    this.pushPoint(metricKey, point);
    this.updates$.next();
  }

  /**
   * Get the latest known value for a metric, or 0 when nothing recorded yet.
   */
  latestValue(metricKey: string): number {
    return this.latestValues()[metricKey] ?? 0;
  }

  /**
   * Get the time-series for a metric within the given window, newest last.
   * Returns a defensive copy so callers cannot mutate internal state.
   */
  getSeries(metricKey: string, window: MetricTimeWindow = DEFAULT_TIME_WINDOW): MetricDataPoint[] {
    const duration = WINDOW_DURATION_MS[window];
    const now = Date.now();
    const raw = this.history.get(metricKey) ?? [];
    const start = now - duration;
    const filtered = raw.filter((p) => p.timestamp >= start);
    return filtered.map((p) => ({ ...p }));
  }

  /**
   * How many points are currently retained for a metric within the given window.
   * Used by property tests to validate window alignment.
   */
  seriesLength(metricKey: string, window: MetricTimeWindow = DEFAULT_TIME_WINDOW): number {
    return this.getSeries(metricKey, window).length;
  }

  /** Clear all retained history (e.g., session reset). */
  reset(): void {
    this.history.clear();
    this.latestValues.set({});
  }

  // ── Private ───────────────────────────────────────────────────

  private pushPoint(metricKey: string, point: MetricDataPoint): void {
    const series = this.history.get(metricKey) ?? [];
    // Only append if strictly newer than the previous point.
    const last = series[series.length - 1];
    if (last && point.timestamp <= last.timestamp) {
      if (point.timestamp === last.timestamp) {
        // Replace same-timestamp sample (a corrected total).
        series[series.length - 1] = point;
      }
      // Out-of-order older point: ignore.
    } else {
      series.push(point);
    }

    // Trim to the 7-day retention horizon first.
    const horizon = WINDOW_DURATION_MS['7d'];
    const cutoff = Date.now() - horizon;
    while (series.length > 0 && series[0].timestamp < cutoff) {
      series.shift();
    }

    // Cap total retained points; drop from the oldest end.
    while (series.length > MAX_POINTS_PER_METRIC) {
      series.shift();
    }

    this.history.set(metricKey, series);
    this.latestValues.update((values) => ({ ...values, [metricKey]: point.value }));
  }
}
