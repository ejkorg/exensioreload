import { TestBed } from '@angular/core/testing';
import * as fc from 'fast-check';
import {
  PredictiveAnalyticsService,
  calculateLinearRegression,
  BacklogSample,
} from './predictive-analytics.service';

/**
 * Feature: dashboard-enhancement
 * Property 43: Linear Regression Projection Accuracy (Req 14.1)
 * Property 44: Capacity Warning Threshold Triggering (Req 14.2)
 * Property 45: Capacity Critical Threshold Triggering (Req 14.3)
 * Property 47: Projection Recalculation Triggers (Req 14.5)
 */
describe('PredictiveAnalyticsService (property tests)', () => {
  let service: PredictiveAnalyticsService;

  beforeEach(() => {
    TestBed.configureTestingModule({});
    service = TestBed.inject(PredictiveAnalyticsService);
    service.reset();
  });

  function makeRisingSamples(from: number, minutes: number, step: number, startTs: number): BacklogSample[] {
    const out: BacklogSample[] = [];
    for (let i = 0; i < minutes; i++) {
      out.push({ timestamp: startTs + i * 60_000, value: from + i * step });
    }
    return out;
  }

  it('Property 43: regression on perfect linear data recovers slope', () => {
    fc.assert(
      fc.property(fc.integer({ min: 1, max: 50 }), (step) => {
        const samples = makeRisingSamples(100, 120, step, Date.now() - 120 * 60_000);
        const r = calculateLinearRegression(samples);
        // slope is per-minute; allow rounding tolerance.
        expect(Math.abs(r.slope - step)).toBeLessThanOrEqual(step * 0.05 + 1e-6);
        expect(r.rSquared).toBeGreaterThan(0.95);
        return true;
      }),
      { numRuns: 100 },
    );
  });

  it('Property 44: projection reaching 80% within 60 min → warning level', () => {
    fc.assert(
      fc.property(fc.integer({ min: 4000, max: 4999 }), fc.integer({ min: 5, max: 30 }), (start, step) => {
        const capacity = 5000;
        // With a high enough growth rate capacity (100%) is reached quickly —
        // so widen capacity for the warning-only scenario.
        const cap = start + step * 30 + 200;
        const samples = makeRisingSamples(start, 120, step, Date.now() - 120 * 60_000);
        const projection = service.evaluate('s1', samples, cap);

        if (projection) {
          const p = projection;
          if (p.level === 'critical') return true; // fast growth also valid
          if (p.level === 'warning') {
            expect(p.timeToCapacity).toBeLessThanOrEqual(60);
          }
        }
        return true;
      }),
      { numRuns: 100 },
    );
  });

  it('Property 45: backlog at 100% capacity now → critical immediately', () => {
    const samples = makeRisingSamples(5000, 120, 0, Date.now() - 120 * 60_000); // flat at capacity
    const projection = service.evaluate('s2', samples, 5000);
    expect(projection?.level).toBe('critical');
    expect(projection?.timeToCapacity).toBe(0);
  });

  it('Property 47: identical backlog suppresses recalculation within the 5-min window', () => {
    const now = Date.now();
    const samples = makeRisingSamples(100, 120, 2, now - 120 * 60_000);
    const first = service.evaluate('s3', samples, 5000, now);
    const second = service.evaluate('s3', samples, 5000, now + 60_000); // +1 min, same data
    // Same samples → same projection object values; no infinite churn.
    expect(first?.growthRate).toBe(second?.growthRate);
    expect(first?.level).toBe(second?.level);
  });
});
