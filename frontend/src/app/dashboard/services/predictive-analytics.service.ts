import { Injectable } from '@angular/core';
import { BehaviorSubject, Observable } from 'rxjs';

export interface RegressionResult {
  slope: number; // records per minute
  intercept: number;
  rSquared: number; // 0..1 confidence proxy
}

export interface CapacityProjection {
  senderId: string | number;
  currentBacklog: number;
  projectedBacklog: number;
  timeToCapacity: number; // minutes, -1 if not reaching capacity within horizon
  growthRate: number; // records per minute
  confidence: number; // 0..1 based on R²
  level: 'none' | 'warning' | 'critical';
}

export interface BacklogSample {
  timestamp: number;
  value: number;
}

const WARNING_MINUTES = 60; // Requirement 14.2
const CRITICAL_MINUTES = 30; // Requirement 14.3
const WARNING_FRACTION = 0.8;
const CRITICAL_FRACTION = 1.0;
const WINDOW_MS = 2 * 60 * 60 * 1000; // Requirement 14.1: last 2 hours
const RECALC_INTERVAL_MS = 5 * 60 * 1000; // Requirement 14.5a
const RECALC_DELTA_FRACTION = 0.1; // Requirement 14.5b

/**
 * Pure least-squares linear regression over (x=minutes, y=backlog).
 * Extracted as a pure function so it can be unit/property tested and, if
 * desired later, moved into a Web Worker without changing the API.
 */
export function calculateLinearRegression(samples: BacklogSample[]): RegressionResult {
  const n = samples.length;
  if (n < 2) {
    return { slope: 0, intercept: samples[0]?.value ?? 0, rSquared: 0 };
  }

  const origin = samples[0].timestamp;
  const points = samples.map((s) => ({ x: (s.timestamp - origin) / 60_000, y: s.value }));

  const sumX = points.reduce((sum, p) => sum + p.x, 0);
  const sumY = points.reduce((sum, p) => sum + p.y, 0);
  const sumXY = points.reduce((sum, p) => sum + p.x * p.y, 0);
  const sumX2 = points.reduce((sum, p) => sum + p.x * p.x, 0);

  const denom = n * sumX2 - sumX * sumX;
  const slope = denom === 0 ? 0 : (n * sumXY - sumX * sumY) / denom;
  const intercept = (sumY - slope * sumX) / n;

  // R² for confidence
  const meanY = sumY / n;
  const ssRes = points.reduce((sum, p) => {
    const predicted = slope * p.x + intercept;
    return sum + Math.pow(p.y - predicted, 2);
  }, 0);
  const ssTot = points.reduce((sum, p) => sum + Math.pow(p.y - meanY, 2), 0);
  const rSquared = ssTot === 0 ? 1 : Math.max(0, 1 - ssRes / ssTot);

  return { slope, intercept, rSquared };
}

/**
 * PredictiveAnalyticsService projects when a sender's backlog will hit capacity
 * using linear regression over the last 2 hours of samples (Requirement 14).
 *
 * Recalculation triggers (Requirement 14.5):
 *  - at least every 5 minutes
 *  - immediately when the latest backlog differs >10% from the last projection
 */
@Injectable({ providedIn: 'root' })
export class PredictiveAnalyticsService {
  private readonly projections = new Map<string | number, CapacityProjection>();
  private readonly projectionSubject = new BehaviorSubject<Map<string | number, CapacityProjection>>(
    new Map(),
  );
  private lastCalculationAt = new Map<string | number, number>();

  /** Stream of the latest projection per sender. */
  readonly capacityWarnings$: Observable<Map<string | number, CapacityProjection>> =
    this.projectionSubject.asObservable();

  /**
   * Feed the latest backlog sample for a sender; returns a projection or null
   * when there is not yet 2 hours of data to regress over.
   */
  evaluate(
    senderId: string | number,
    samples: BacklogSample[],
    capacity: number,
    now = Date.now(),
  ): CapacityProjection | null {
    const lastCalc = this.lastCalculationAt.get(senderId) ?? 0;
    const lastProjection = this.projections.get(senderId);
    const latest = samples.length > 0 ? samples[samples.length - 1].value : 0;

    // Trigger (a): 5-minute interval has elapsed.
    const intervalElapsed = now - lastCalc >= RECALC_INTERVAL_MS;
    // Trigger (b): backlog moved >10% since last projection.
    const changedSignificantly =
      lastProjection !== undefined &&
      lastProjection.currentBacklog > 0 &&
      Math.abs(latest - lastProjection.currentBacklog) / lastProjection.currentBacklog > RECALC_DELTA_FRACTION;

    if (!intervalElapsed && !changedSignificantly && lastProjection && latest === lastProjection.currentBacklog) {
      return { ...lastProjection };
    }

    const windowStart = now - WINDOW_MS;
    const windowSamples = samples.filter((s) => s.timestamp >= windowStart);

    if (windowSamples.length < 2) {
      return null;
    }

    const regression = calculateLinearRegression(windowSamples);
    const projection = this.buildProjection(senderId, latest, capacity, regression);

    this.projections.set(senderId, projection);
    this.lastCalculationAt.set(senderId, now);
    this.projectionSubject.next(new Map(this.projections));
    return projection;
  }

  /** Retrieve the latest stored projection for a sender (may be undefined). */
  getProjection(senderId: string | number): CapacityProjection | undefined {
    const p = this.projections.get(senderId);
    return p ? { ...p } : undefined;
  }

  private buildProjection(
    senderId: string | number,
    currentBacklog: number,
    capacity: number,
    regression: RegressionResult,
  ): CapacityProjection {
    const capacityNum = capacity > 0 ? capacity : 1;
    const growthRate = regression.slope;

    let timeToCapacity = -1;
    let level: CapacityProjection['level'] = 'none';

    if (growthRate > 0) {
      const remaining = capacityNum - currentBacklog;
      if (remaining > 0) {
        timeToCapacity = remaining / growthRate; // minutes
      } else {
        timeToCapacity = 0;
      }

      if (timeToCapacity <= CRITICAL_MINUTES) {
        level = 'critical';
      } else if (timeToCapacity <= WARNING_MINUTES) {
        // Only warn when we actually project crossing 80% within the warning window.
        const backlogAtWarning = currentBacklog + growthRate * WARNING_MINUTES;
        if (backlogAtWarning >= capacityNum * WARNING_FRACTION) {
          level = 'warning';
        }
      }
    }

    const projectedBacklog = growthRate > 0 ? currentBacklog + growthRate * WARNING_MINUTES : currentBacklog;

    return {
      senderId,
      currentBacklog,
      projectedBacklog: Math.round(projectedBacklog),
      timeToCapacity: Math.round(timeToCapacity),
      growthRate,
      confidence: regression.rSquared,
      level,
    };
  }

  reset(): void {
    this.projections.clear();
    this.lastCalculationAt.clear();
    this.projectionSubject.next(new Map());
  }
}
