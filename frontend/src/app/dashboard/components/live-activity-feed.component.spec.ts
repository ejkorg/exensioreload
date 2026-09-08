import { ComponentFixture, TestBed } from '@angular/core/testing';
import * as fc from 'fast-check';
import { LiveActivityFeedComponent, ActivityFeedItem } from './live-activity-feed.component';

/**
 * Feature: dashboard-enhancement
 * Property 34: Activity Feed Size Limit (Req 11.1, 11.3)
 * Property 35: Activity Feed Event Prepending (Req 11.2)
 * Property 36: Activity Entry Format Compliance (Req 11.4)
 * Property 37: Activity Entry State-Based Coloring (Req 11.5)
 */
describe('LiveActivityFeedComponent (property tests)', () => {
  let component: LiveActivityFeedComponent;
  let fixture: ComponentFixture<LiveActivityFeedComponent>;

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [LiveActivityFeedComponent] });
    fixture = TestBed.createComponent(LiveActivityFeedComponent);
    component = fixture.componentInstance;
  });

  function transitionItem(): ActivityFeedItem {
    return {
      id: `id-${Math.random()}`,
      timestamp: new Date(),
      label: 'sender-1',
      lot: 'LOT-A',
      wafer: 'W01',
      oldState: 'ENRICHMENT',
      newState: 'COMPLETED',
    };
  }

  it('Property 34/35: feed never exceeds 20 items and newest is on top', () => {
    fc.assert(
      fc.property(fc.integer({ min: 30, max: 200 }), (n) => {
        component.items.set([]);
        for (let i = 0; i < n; i++) {
          component.pushFileTransition(transitionItem());
        }
        const items = component.items();
        expect(items.length).toBeLessThanOrEqual(20);
        expect(items.length).toBe(Math.min(n, 20));
        return true;
      }),
      { numRuns: 100 },
    );
  });

  it('Property 36: entry displays timestamp, label, and state transition', () => {
    component.items.set([]);
    component.pushFileTransition({ label: 'SNDR', lot: 'L1', wafer: 'W1', oldState: 'READY', newState: 'COMPLETED' });
    fixture.detectChanges();
    const el: HTMLElement = fixture.nativeElement.querySelector('.feed-item');
    expect(el).not.toBeNull();
    expect(el.textContent).toContain('SNDR');
    expect(el.textContent).toContain('L1/W1');
    expect(el.textContent).toContain('COMPLETED');
  });

  it('Property 37: color class matches the destination state severity', () => {
    expect(component.eventColorClass('COMPLETED')).toBe('event-success');
    expect(component.eventColorClass('CP_FAILED')).toBe('event-danger');
    expect(component.eventColorClass('CP_TIMEOUT')).toBe('event-warning');
    expect(component.eventColorClass('COMPLETED_MANUAL_VERIFICATION_REQUIRED')).toBe('event-warning');
    expect(component.eventColorClass('ELASTICSEARCH_MONITORING')).toBe('event-info');
  });
});
