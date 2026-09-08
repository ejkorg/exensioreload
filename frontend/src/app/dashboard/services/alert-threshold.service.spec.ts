import { TestBed } from '@angular/core/testing';
import * as fc from 'fast-check';
import { AlertThresholdService } from './alert-threshold.service';

/**
 * Feature: dashboard-enhancement
 * Property 8:  Threshold Breach Visual Indicators (Req 3.2)
 * Property 9:  Threshold Persistence Round-Trip (Req 3.3)
 * Property 10: Threshold Modification Reactivity (Req 3.4)
 */
describe('AlertThresholdService (property tests)', () => {
  let service: AlertThresholdService;

  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({});
    service = TestBed.inject(AlertThresholdService);
  });

  it('Property 8: metrics above critical threshold produce a critical alert', () => {
    fc.assert(
      fc.property(fc.integer({ min: 5001, max: 100000 }), (backlog) => {
        const senderId = 1;
        service.resetThresholds(senderId);
        const events = service.evaluateMetrics(senderId, {
          backlog,
          errorRate: 0,
          throughput: 9999,
        });

        expect(events.some((e) => e.metric === 'backlog' && e.level === 'critical')).toBe(true);
        return true;
      }),
      { numRuns: 100 },
    );
  });

  it('Property 9: thresholds persist and survive a localStorage round-trip', () => {
    fc.assert(
      fc.property(
        fc.integer({ min: 100, max: 10000 }),
        fc.integer({ min: 100, max: 25 }),
        fc.integer({ min: 26, max: 100 }),
        (backlogWarning, errorWarning, errorCritical) => {
          const senderId = fc.sample(fc.integer(), 1)[0];
          service.resetThresholds(senderId);
          service.updateThresholds(senderId, {
            backlogWarning,
            errorRateWarning: Math.min(errorWarning, errorCritical - 1),
            errorRateCritical: errorCritical,
          });

          const restored = service.getThresholds(senderId);
          expect(restored.backlogWarning).toBe(backlogWarning);
          expect(restored.errorRateCritical).toBe(errorCritical);
          expect(restored.errorRateWarning).toBeLessThan(restored.errorRateCritical);
          return true;
        },
      ),
      { numRuns: 100 },
    );
  });

  it('Property 10: lowering a threshold immediately surfaces a new alert', () => {
    fc.assert(
      fc.property(fc.integer({ min: 100, max: 5000 }), (newWarning) => {
        const senderId = 'sender-A';
        service.resetThresholds(senderId);
        const metrics = { backlog: newWarning + 1, errorRate: 0, throughput: 1000 };

        // No warning when backlog is at/below default 500... newWarning could be ≥500,
        // so reset thresholds first to defaults with a high cap to make the check deterministic.
        service.updateThresholds(senderId, { backlogWarning: 5000, backlogCritical: 10000 });
        expect(service.evaluateMetrics(senderId, metrics).some((e) => e.metric === 'backlog')).toBe(false);

        // Lower the threshold below the current backlog → alert must appear immediately.
        service.updateThresholds(senderId, { backlogWarning: newWarning }, metrics);
        expect(service.evaluateLevel(senderId, metrics)).not.toBeNull();
        return true;
      }),
      { numRuns: 100 },
    );
  });
});
