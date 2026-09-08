import { Injectable, signal, computed, effect, WritableSignal } from '@angular/core';

/**
 * Represents the union of all pipeline state values surfaced on the dashboard,
 * aligned with the 9-state pipeline (Requirement 16.1).
 */
export type DashboardPipelineState =
  | 'STAGED'
  | 'QUEUED_FOR_CP'
  | 'ELASTICSEARCH_MONITORING'
  | 'CP_TIMEOUT'
  | 'EXENSIO_MONITORING'
  | 'COMPLETED_MANUAL_VERIFICATION_REQUIRED'
  | 'COMPLETED'
  | 'CP_FAILED'
  | 'CANCELLED';

export interface DashboardFilterState {
  devices: string[];
  siteIds: string[];
  senderSearch: string;
  pipelineStates: DashboardPipelineState[];
  dateRange: { start: Date; end: Date } | null;
}

export interface LayoutSectionConfig {
  id: string;
  title: string;
  visible: boolean;
  order: number;
}

export interface DashboardLayoutConfig {
  sections: LayoutSectionConfig[];
  customOrder: string[];
  hiddenSections: string[];
}

export interface ComparisonPeriodConfig {
  currentPeriod: { start: Date; end: Date };
  comparisonPeriod: { start: Date; end: Date };
  enabled: boolean;
}

export interface DashboardState {
  activeFilters: DashboardFilterState;
  layoutConfig: DashboardLayoutConfig;
  comparisonMode: ComparisonPeriodConfig | null;
}

const EMPTY_FILTERS: DashboardFilterState = {
  devices: [],
  siteIds: [],
  senderSearch: '',
  pipelineStates: [],
  dateRange: null,
};

const FILTER_STORAGE_KEY = 'exensioreload.dashboard.filters'; // Requirement 5.4
const LAYOUT_STORAGE_KEY = 'exensioreload.dashboard.layout'; // Requirement 12.4

/**
 * Central dashboard state service.
 *
 * Owns reactive state for filters, layout customization, and comparison mode.
 * Persists:
 *  - filter state to sessionStorage (Requirement 5.4 round-trip)
 *  - layout configuration to localStorage  (Requirement 12.4)
 *
 * Default layout section order matches the current dashboard structure.
 */
@Injectable({ providedIn: 'root' })
export class DashboardStateService {
  /** Writable reactive filter state (also mirrored to sessionStorage). */
  readonly filters: WritableSignal<DashboardFilterState> = signal<DashboardFilterState>(this.readStoredFilters());

  /** Writable reactive layout configuration (also mirrored to localStorage). */
  readonly layout: WritableSignal<DashboardLayoutConfig> = signal<DashboardLayoutConfig>(
    this.readStoredLayout(),
  );

  /** Writable reactive comparison-mode configuration. */
  readonly comparisonMode = signal<ComparisonPeriodConfig | null>(null);

  /** Number of non-empty, active filters. Drives the "Clear all filters" count badge (Requirement 5.5). */
  readonly activeFilterCount = computed(() => {
    const f = this.filters();
    let count = 0;
    if (f.devices.length > 0) count++;
    if (f.siteIds.length > 0) count++;
    if ((f.senderSearch || '').trim().length > 0) count++;
    if (f.pipelineStates.length > 0) count++;
    if (f.dateRange) count++;
    return count;
  });

  /** True when at least one filter is active. */
  readonly hasActiveFilters = computed(() => this.activeFilterCount() > 0);

  constructor() {
    // Persist filter changes to sessionStorage as they happen.
    effect(() => {
      const f = this.filters();
      try {
        sessionStorage.setItem(FILTER_STORAGE_KEY, JSON.stringify(f));
      } catch {
        // Storage may be unavailable (private mode / quota); filters stay in-memory.
      }
    });

    // Persist layout changes to localStorage as they happen.
    effect(() => {
      const l = this.layout();
      try {
        localStorage.setItem(LAYOUT_STORAGE_KEY, JSON.stringify(l));
      } catch {
        // ignore storage failures
      }
    });
  }

  // ── Filter API ────────────────────────────────────────────────

  /** Replace the whole filter state (Requirement 5.1). */
  applyFilters(filters: Partial<DashboardFilterState>): void {
    this.filters.update((current) => ({ ...current, ...filters }));
  }

  /** Add a single device to the device filter. */
  toggleDevice(device: string, enabled: boolean): void {
    this.filters.update((f) => {
      const devices = new Set(f.devices);
      if (enabled) {
        devices.add(device);
      } else {
        devices.delete(device);
      }
      return { ...f, devices: Array.from(devices) };
    });
  }

  /** Set the sender search text (Requirement 5.3). */
  setSenderSearch(text: string): void {
    this.filters.update((f) => ({ ...f, senderSearch: text }));
  }

  /** Reset every filter to empty and clear persisted state (Requirement 20.5). */
  clearAllFilters(): void {
    this.filters.set({ ...EMPTY_FILTERS, dateRange: null });
    try {
      sessionStorage.removeItem(FILTER_STORAGE_KEY);
    } catch {
      // ignore
    }
  }

  // ── Layout API ────────────────────────────────────────────────

  /** Hide a section and remember it (Requirement 12.2). */
  hideSection(sectionId: string): void {
    this.layout.update((l) => ({
      ...l,
      sections: l.sections.map((s) => (s.id === sectionId ? { ...s, visible: false } : s)),
      hiddenSections: l.hiddenSections.includes(sectionId)
        ? l.hiddenSections
        : [...l.hiddenSections, sectionId],
    }));
  }

  /** Re-show a hidden section (Requirement 12.2 "Hidden sections" dropdown). */
  showSection(sectionId: string): void {
    this.layout.update((l) => ({
      ...l,
      sections: l.sections.map((s) => (s.id === sectionId ? { ...s, visible: true } : s)),
      hiddenSections: l.hiddenSections.filter((id) => id !== sectionId),
    }));
  }

  /** Move a section up or down in display order (Requirement 12.1). */
  moveSection(sectionId: string, direction: 'up' | 'down'): void {
    this.layout.update((l) => {
      const order = [...l.customOrder];
      const idx = order.indexOf(sectionId);
      if (idx < 0) return l;
      const target = direction === 'up' ? idx - 1 : idx + 1;
      if (target < 0 || target >= order.length) return l;
      [order[idx], order[target]] = [order[target], order[idx]];
      return { ...l, customOrder: order };
    });
  }

  /** Restore default section order/visibility and clear persisted layout (Requirement 12.5). */
  resetLayout(): void {
    try {
      localStorage.removeItem(LAYOUT_STORAGE_KEY);
    } catch {
      // ignore
    }
    this.layout.set(this.defaultLayout());
  }

  // ── Comparison mode API (Requirement 13.1) ────────────────────

  setComparisonMode(config: ComparisonPeriodConfig | null): void {
    this.comparisonMode.set(config);
  }

  // ── Private helpers ───────────────────────────────────────────

  private defaultLayout(): DashboardLayoutConfig {
    return {
      sections: [
        { id: 'health', title: 'System Health', visible: true, order: 0 },
        { id: 'metrics', title: 'Pipeline State Overview', visible: true, order: 1 },
        { id: 'flow', title: 'Pipeline Flow', visible: true, order: 2 },
        { id: 'supporting', title: 'Infrastructure', visible: true, order: 3 },
        { id: 'trends', title: 'Live Pipeline Trends', visible: true, order: 4 },
        { id: 'activity', title: 'Live Activity', visible: true, order: 5 },
        { id: 'integrations', title: 'Integrations', visible: true, order: 6 },
        { id: 'senders', title: 'Top Senders by Backlog', visible: true, order: 7 },
        { id: 'sites', title: 'Site Overview', visible: true, order: 8 },
      ],
      customOrder: ['health', 'metrics', 'flow', 'supporting', 'trends', 'activity', 'integrations', 'senders', 'sites'],
      hiddenSections: [],
    };
  }

  private readStoredFilters(): DashboardFilterState {
    try {
      const raw = sessionStorage.getItem(FILTER_STORAGE_KEY);
      if (!raw) return { ...EMPTY_FILTERS };
      const parsed = JSON.parse(raw) as Partial<DashboardFilterState>;
      return { ...EMPTY_FILTERS, ...parsed };
    } catch {
      return { ...EMPTY_FILTERS };
    }
  }

  private readStoredLayout(): DashboardLayoutConfig {
    try {
      const raw = localStorage.getItem(LAYOUT_STORAGE_KEY);
      if (!raw) return this.defaultLayout();
      return JSON.parse(raw) as DashboardLayoutConfig;
    } catch {
      return this.defaultLayout();
    }
  }
}
