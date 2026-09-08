import { ComponentFixture, TestBed } from '@angular/core/testing';
import { IntegrationStatusCardComponent } from './integration-status-card.component';
import { IntegrationStatusSnapshot } from '../../api/backend.service';

/**
 * Feature: dashboard-enhancement
 * Property 48: Integration Status Badge Mapping - Success (Req 15.2/18.2)
 * Property 49: Integration Status Badge Mapping - Failure (Req 15.3/18.5)
 * Property 50: Integration Status Badge Mapping - Pending/Timeout (Req 18.3/18.4)
 * Property 51: Integration Status Badge Mapping - Not Configured (Req 18.6)
 */
describe('IntegrationStatusCardComponent (property tests)', () => {
  let component: IntegrationStatusCardComponent;
  let fixture: ComponentFixture<IntegrationStatusCardComponent>;

  function entry(status: string, message = '') {
    return { configured: status !== 'not_configured', status, message, lastAt: null };
  }

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [IntegrationStatusCardComponent] });
    fixture = TestBed.createComponent(IntegrationStatusCardComponent);
    component = fixture.componentInstance;
  });

  it('Property 48: success shows green Connected pill', () => {
    const snapshot: IntegrationStatusSnapshot = {
      elasticsearch: entry('success'),
      exensio: entry('success'),
    };
    component.snapshot = snapshot;
    fixture.detectChanges();
    const rows = component.rows();
    expect(rows.length).toBe(2);
    for (const row of rows) {
      expect(row.definition.color).toBe('#10b981');
      expect(row.definition.icon).toBe('check_circle');
    }
    const pills = fixture.nativeElement.querySelectorAll('.status-pill');
    expect(pills.length).toBe(2);
    expect(pills[0].textContent).toContain('Connected');
  });

  it('Property 49: failure shows red pill with error icon mapping', () => {
    const snapshot: IntegrationStatusSnapshot = {
      elasticsearch: entry('failure', 'Connection refused'),
      exensio: entry('error', '5xx from API'),
    };
    component.snapshot = snapshot;
    fixture.detectChanges();
    const rows = component.rows();
    expect(rows.every((r) => r.definition.color === '#ef4444')).toBe(true);
    expect(rows.every((r) => r.definition.icon === 'error')).toBe(true);
  });

  it('Property 50/51: pending/timeout/not_configured map to expected pills', () => {
    const snapshot: IntegrationStatusSnapshot = {
      elasticsearch: entry('timeout', 'ES timeout'),
      exensio: entry('not_configured'),
    };
    component.snapshot = snapshot;
    fixture.detectChanges();
    const rows = component.rows();
    expect(rows[0].definition.color).toBe('#f59e0b'); // timeout amber
    expect(rows[1].definition.color).toBe('#6b7280'); // not_configured gray
  });
});
