import { TestBed } from '@angular/core/testing';
import * as fc from 'fast-check';
import { StateLegendService } from './state-legend.service';

/**
 * Feature: dashboard-enhancement
 * Property 48: Integration Status Badge Mapping - Success (Req 15.2/18.2)
 * Property 49: Integration Status Badge Mapping - Failure (Req 15.3/18.5)
 * Property 50: Integration Status Badge Mapping - Pending/Timeout (Req 18.3/18.4)
 * Property 51: Integration Status Badge Mapping - Not Configured (Req 18.6)
 * Property 54: State Legend Service Consistency Across Components (Req 16.1-16.3)
 */
describe('StateLegendService integration status (property tests)', () => {
  let service: StateLegendService;

  beforeEach(() => {
    TestBed.configureTestingModule({});
    service = TestBed.inject(StateLegendService);
  });

  it('Property 48: success status maps to green Connected definition', () => {
    const def = service.getIntegrationStatus('success');
    expect(def.color).toBe('#10b981');
    expect(def.icon).toBe('check_circle');
    expect(def.label).toBe('Connected');
  });

  it('Property 49: failure/error statuses map to red error definitions', () => {
    fc.assert(
      fc.property(fc.constantFrom('failure', 'error'), (status) => {
        const def = service.getIntegrationStatus(status);
        expect(def.color).toBe('#ef4444');
        expect(def.icon).toBe('error');
        return true;
      }),
      { numRuns: 100 },
    );
  });

  it('Property 50: timeout/not_found map to amber, pending maps to blue', () => {
    fc.assert(
      fc.property(fc.constantFrom('timeout', 'not_found'), (status) => {
        expect(service.getIntegrationStatus(status).color).toBe('#f59e0b');
        return true;
      }),
      { numRuns: 100 },
    );
    expect(service.getIntegrationStatus('pending').color).toBe('#3b82f6');
  });

  it('Property 51: not_configured maps to gray settings definition', () => {
    const def = service.getIntegrationStatus('not_configured');
    expect(def.color).toBe('#6b7280');
    expect(def.icon).toBe('settings');
    expect(def.label).toBe('Not Configured');
  });

  it('Property 54: all 9 pipeline states resolve a consistent definition', () => {
    fc.assert(
      fc.property(
        fc.constantFrom(
          'STAGED',
          'QUEUED_FOR_CP',
          'ELASTICSEARCH_MONITORING',
          'CP_TIMEOUT',
          'EXENSIO_MONITORING',
          'COMPLETED_MANUAL_VERIFICATION_REQUIRED',
          'COMPLETED',
          'CP_FAILED',
          'CANCELLED',
        ),
        (statusValue) => {
          const label = service.getLabelByStatus(statusValue);
          expect(label).toBeDefined();
          const state = service.getStateByLabel(label!);
          expect(state?.statusValue).toBe(statusValue);
          expect(state?.isTerminal).toEqual(jasmine.any(Boolean));
          return true;
        },
      ),
      { numRuns: 100 },
    );
  });
});
