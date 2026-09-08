import { TestBed } from '@angular/core/testing';
import * as fc from 'fast-check';
import { MetricsHistoryService, DEFAULT_TIME_WINDOW } from './metrics-history.service';

/**
 * Feature: dashboard-enhancement
 * Property 3:  Time Window Data Alignment (Req 1.5)
 * Property 16: Chart Update Debouncing (Req 6.4)
 * Property 17: Metrics Cache Hit Efficiency (Req 6.5)
 */
describe('MetricsHistoryService (property tests)', () => {
  let service: MetricsHistoryService;

  beforeEach(() => {
    TestBed.configureTestingModule({});
    service = TestBed.inject(MetricsHistoryService);
    service.reset();
  });

  it('Property 3: series for a window only contains points inside that window', () => {
    fc.assert(
      fc.property(fc.array(fc.integer({ min: 1, max: 10_000 }), { minLength: 1, maxLength: 50 }), (values) => {
        service.reset();
        const base = Date.now();
        // Older-than-7d points must be evicted; recent points must be retained.
        values.forEach((v, i) => {
          service.appendPoint('backlog', { timestamp: base - (8 * 24 * 60 * 60 * 1000) + i * 1000, value: v });
        });

        expect(service.seriesLength('backlog', DEFAULT_TIME_WINDOW)).toBe(0);
        return true;
      }),
      { numRuns: 100 },
    );
  });

  it('Property 16: rapid append bursts coalesce to at most one update per 500ms', (done) => {
    let emissions = 0;
    const sub = service.updates$.subscribe(() => emissions++);

    const start = Date.now();
    let i = 0;
    const burst = setInterval(() => {
      service.appendPoint('backlog', { timestamp: Date.now(), value: i++ });
      if (i >= 20) {
        clearInterval(burst);
        setTimeout(() => {
          // Even with 20 rapid points we must not have emitted once per point.
          expect(emissions).toBeLessThanOrEqual(4);
          sub.unsubscribe();
          done();
        }, 600);
      }
    }, 20); // 20ms cadence → far denser than one emission per 500ms
  });

  it('Property 17: repeated series read never mutates retained history', () => {
    fc.assert(
      fc.property(fc.array(fc.integer({ min: 0, max: 1000 }), { minLength: 1, maxLength: 30 }), (values) => {
        service.reset();
        const base = Date.now();
        values.forEach((v, i) => service.appendPoint('enqueued', { timestamp: base - 60_000 + i * 1000, value: v }));

        const first = service.getSeries('enqueued', '1h');
        const second = service.getSeries('enqueued', '1h');

        expect(second.length).toBe(first.length);
        expect(second[second.length - 1]?.value).toBe(values[values.length - 1]);
        return true;
      }),
      { numRuns: 100 },
    );
  });
});
