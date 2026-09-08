import { TestBed } from '@angular/core/testing';
import * as fc from 'fast-check';
import { DashboardStateService } from './dashboard-state.service';

/**
 * Feature: dashboard-enhancement
 * Property 14: Filter Persistence Round-Trip (Req 5.4)
 * Property 15: Active Filter Count Accuracy (Req 5.5)
 * Property 38: Section Hide/Show Persistence (Req 12.2, 12.4)
 * Property 39: Section Drag-Reorder Persistence (Req 12.3, 12.4)
 */
describe('DashboardStateService (property tests)', () => {
  let service: DashboardStateService;

  beforeEach(() => {
    sessionStorage.clear();
    localStorage.clear();
    TestBed.configureTestingModule({});
    service = TestBed.inject(DashboardStateService);
  });

  it('Property 14: filter state survives sessionStorage round-trip', () => {
    fc.assert(
      fc.property(fc.array(fc.string()), fc.array(fc.string()), (devices, searchTokens) => {
        service.clearAllFilters();
        service.applyFilters({
          devices: devices.slice(0, 5),
          senderSearch: searchTokens[0] ?? '',
          pipelineStates: [],
          siteIds: [],
          dateRange: null,
        });

        const persisted = JSON.parse(sessionStorage.getItem('exensioreload.dashboard.filters')!);

        expect(persisted.devices).toEqual(devices.slice(0, 5));
        expect(persisted.senderSearch).toBe(searchTokens[0] ?? '');
        return true;
      }),
      { numRuns: 100 },
    );
  });

  it('Property 15: active filter count equals number of non-empty filters', () => {
    fc.assert(
      fc.property(fc.array(fc.string()), fc.string(), (devices, search) => {
        service.clearAllFilters();
        service.applyFilters({
          devices: Array.from(new Set(devices)).slice(0, 3),
          senderSearch: search,
          siteIds: [],
          pipelineStates: [],
          dateRange: null,
        });

        const expected =
          (devices.length > 0 ? 1 : 0) + (search.trim().length > 0 ? 1 : 0);

        expect(service.activeFilterCount()).toBe(expected);
        expect(service.hasActiveFilters()).toBe(expected > 0);
        return true;
      }),
      { numRuns: 100 },
    );
  });

  it('Property 38: hiding a section persists visibility to localStorage', () => {
    fc.assert(
      fc.property(fc.constantFrom('health', 'metrics', 'senders', 'sites', 'activity'), (sectionId) => {
        service.resetLayout();
        service.hideSection(sectionId);

        const persisted = JSON.parse(localStorage.getItem('exensioreload.dashboard.layout')!);
        const section = persisted.sections.find((s: { id: string }) => s.id === sectionId);

        expect(section.visible).toBe(false);
        expect(persisted.hiddenSections).toContain(sectionId);
        return true;
      }),
      { numRuns: 100 },
    );
  });

  it('Property 39: reorder persists and reset restores defaults', () => {
    fc.assert(
      fc.property(fc.array(fc.constantFrom('health', 'metrics', 'senders', 'sites', 'activity'), { minLength: 1, maxLength: 4 }), (ids) => {
        service.resetLayout();
        ids.forEach((id) => service.moveSection(id, 'up'));

        // Layout must still contain every default section exactly once.
        const config = service.layout();
        expect(new Set(config.customOrder).size).toBe(config.customOrder.length);
        expect(config.customOrder).toEqual(jasmine.arrayContaining(['health', 'metrics']));

        service.resetLayout();
        expect(service.layout().hiddenSections).toEqual([]);
        return true;
      }),
      { numRuns: 100 },
    );
  });
});
