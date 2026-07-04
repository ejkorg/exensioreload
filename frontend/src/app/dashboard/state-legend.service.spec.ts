import { StateLegendService } from './state-legend.service';

/**
 * Unit tests for StateLegendService
 * Tests the state legend lookup and tooltip generation functionality
 */
describe('StateLegendService', () => {
  let service: StateLegendService;

  beforeEach(() => {
    service = new StateLegendService();
  });

  /**
   * Property 1: State Label Consistency
   * For any valid state label, getStateByLabel returns a definition with matching label
   *
   * Feature: monitor-accounting-improvements, Property: State Label Consistency
   * Validates: Requirements 5
   */
  it('Property 1a: getStateByLabel returns definition for valid state labels', () => {
    const validLabels = [
      'Staged',
      'Queued for CP',
      'In Enrichment',
      'Enrichment Timeout',
      'Exensio Loading',
      'Exensio Timeout',
      'Completed',
      'Failed',
      'Cancelled',
    ];

    validLabels.forEach((label) => {
      const definition = service.getStateByLabel(label);
      expect(definition).toBeDefined();
      expect(definition?.label).toBe(label);
    });
  });

  it('Property 1b: getStateByLabel returns undefined for invalid state labels', () => {
    const invalidLabels = ['Invalid', 'Unknown', '', 'PENDING', 'enqueued'];

    invalidLabels.forEach((label) => {
      const definition = service.getStateByLabel(label);
      expect(definition).toBeUndefined();
    });
  });

  /**
   * Property 2: Terminal State Correctness
   * For any state, terminal status must match whether nextStates is empty
   *
   * Feature: monitor-accounting-improvements, Property: Terminal State Correctness
   * Validates: Requirements 5
   */
  it('Property 2: terminal states have no next states', () => {
    const terminalStates = ['Completed', 'Failed', 'Cancelled'];

    terminalStates.forEach((label) => {
      const definition = service.getStateByLabel(label);
      expect(definition?.isTerminal).toBe(true);
      expect(definition?.nextStates.length).toBe(0);
    });
  });

  it('Property 2b: non-terminal states have at least one next state', () => {
    const nonTerminalStates = [
      'Staged',
      'Queued for CP',
      'In Enrichment',
      'Enrichment Timeout',
      'Exensio Loading',
      'Exensio Timeout',
    ];

    nonTerminalStates.forEach((label) => {
      const definition = service.getStateByLabel(label);
      expect(definition?.isTerminal).toBe(false);
      expect(definition?.nextStates.length).toBeGreaterThan(0);
    });
  });

  /**
   * Property 3: State Transition Validity
   * For any non-terminal state, all nextStates must be valid state labels
   *
   * Feature: monitor-accounting-improvements, Property: State Transition Validity
   * Validates: Requirements 5
   */
  it('Property 3: all next states reference valid states', () => {
    const allStates = service.getAllStates();
    const validLabels = new Set(allStates.map((s) => s.label));

    allStates.forEach((state) => {
      state.nextStates.forEach((nextLabel) => {
        expect(validLabels.has(nextLabel)).toBe(true);
      });
    });
  });

  /**
   * Property 4: Tooltip Generation Consistency
   * For any valid state label, getTooltip returns non-empty string
   *
   * Feature: monitor-accounting-improvements, Property: Tooltip Generation Consistency
   * Validates: Requirements 5
   */
  it('Property 4a: tooltip text is always non-empty for valid states', () => {
    const validLabels = [
      'Staged',
      'Queued for CP',
      'In Enrichment',
      'Enrichment Timeout',
      'Exensio Loading',
      'Exensio Timeout',
      'Completed',
      'Failed',
      'Cancelled',
    ];

    validLabels.forEach((label) => {
      const tooltip = service.getTooltip(label);
      expect(tooltip).toBeTruthy();
      expect(tooltip.length).toBeGreaterThan(0);
      expect(tooltip).toContain(label);
    });
  });

  it('Property 4b: tooltip for invalid label returns fallback message', () => {
    const tooltip = service.getTooltip('InvalidState');
    expect(tooltip).toContain('State in processing pipeline');
  });

  /**
   * Property 5: Formatted Legend Structure
   * For any valid state, formatted legend includes description and transitions
   *
   * Feature: monitor-accounting-improvements, Property: Formatted Legend Structure
   * Validates: Requirements 5
   */
  it('Property 5: formatted legend includes state name and description', () => {
    const label = 'In Enrichment';
    const legend = service.getFormattedLegend(label);

    expect(legend).toContain('In Enrichment');
    expect(legend).toContain('Currently being processed');
  });

  it('Property 5b: formatted legend for terminal state indicates terminal status', () => {
    const legend = service.getFormattedLegend('Completed');
    expect(legend).toContain('Terminal state');
  });

  it('Property 5c: formatted legend for non-terminal state lists next states', () => {
    const legend = service.getFormattedLegend('Staged');
    expect(legend).toContain('Possible next');
  });

  /**
   * Property 6: Status Value Uniqueness
   * For any valid state label, statusValue is unique across all states
   *
   * Feature: monitor-accounting-improvements, Property: Status Value Uniqueness
   * Validates: Requirements 5
   */
  it('Property 6: status values are unique per state', () => {
    const allStates = service.getAllStates();
    const statusValues = allStates.map((s) => s.statusValue);
    const uniqueStatusValues = new Set(statusValues);

    expect(uniqueStatusValues.size).toBe(statusValues.length);
  });

  /**
   * Property 7: Status-to-Label Bidirectional Mapping
   * For any status value, getLabelByStatus returns correct label
   *
   * Feature: monitor-accounting-improvements, Property: Status-to-Label Bidirectional Mapping
   * Validates: Requirements 5
   */
  it('Property 7: getLabelByStatus returns correct label for each status', () => {
    const allStates = service.getAllStates();

    allStates.forEach((state) => {
      const foundLabel = service.getLabelByStatus(state.statusValue);
      expect(foundLabel).toBe(state.label);
    });
  });

  it('Property 7b: getLabelByStatus returns undefined for invalid status', () => {
    const label = service.getLabelByStatus('INVALID_STATUS');
    expect(label).toBeUndefined();
  });

  /**
   * Property 8: State Definition Completeness
   * For any state, all required fields are present and non-empty
   *
   * Feature: monitor-accounting-improvements, Property: State Definition Completeness
   * Validates: Requirements 5
   */
  it('Property 8: all states have required fields', () => {
    const allStates = service.getAllStates();

    allStates.forEach((state) => {
      expect(state.label).toBeTruthy();
      expect(state.description).toBeTruthy();
      expect(state.statusValue).toBeTruthy();
      expect(state.color).toBeTruthy();
      expect(state.icon).toBeTruthy();
      expect(state.tooltip).toBeTruthy();
      expect(state.nextStates).toBeDefined();
      expect(typeof state.isTerminal).toBe('boolean');
    });
  });

  /**
   * Property 9: Transition Path Coherence
   * For all non-terminal states, at least one path must lead to terminal state
   *
   * Feature: monitor-accounting-improvements, Property: Transition Path Coherence
   * Validates: Requirements 5
   */
  it('Property 9: from any non-terminal state, there is a path to terminal state', () => {
    const allStates = service.getAllStates();
    const terminalStates = new Set(allStates.filter((s) => s.isTerminal).map((s) => s.label));

    function hasPathToTerminal(label: string, visited: Set<string> = new Set()): boolean {
      if (visited.has(label)) return false;
      visited.add(label);

      if (terminalStates.has(label)) return true;

      const state = service.getStateByLabel(label);
      if (!state) return false;

      return state.nextStates.some((nextLabel) => hasPathToTerminal(nextLabel, new Set(visited)));
    }

    allStates
      .filter((s) => !s.isTerminal)
      .forEach((state) => {
        expect(hasPathToTerminal(state.label)).toBe(true);
      });
  });

  /**
   * Property 10: Color Consistency
   * For any state, color value is one of the known color schemes
   *
   * Feature: monitor-accounting-improvements, Property: Color Consistency
   * Validates: Requirements 5
   */
  it('Property 10: all states use valid color schemes', () => {
    const validColors = ['primary', 'secondary', 'success', 'danger', 'info'];
    const allStates = service.getAllStates();

    allStates.forEach((state) => {
      expect(validColors).toContain(state.color);
    });
  });

  /**
   * Property 11: Icon Validity
   * For any state, icon value is a valid Material icon name
   *
   * Feature: monitor-accounting-improvements, Property: Icon Validity
   * Validates: Requirements 5
   */
  it('Property 11: all states use valid Material icon names', () => {
    const validIcons = [
      'inbox',
      'schedule',
      'auto_awesome',
      'cloud_download',
      'check_circle',
      'error_outline',
      'block',
    ];
    const allStates = service.getAllStates();

    allStates.forEach((state) => {
      expect(validIcons).toContain(state.icon);
    });
  });

  // ─────────────────────────────────────────────────────────────────────
  // Property-Based Tests (using fast-check)
  // ─────────────────────────────────────────────────────────────────────

  /**
   * Property 12: getAllStates returns consistent set
   * For repeated calls, getAllStates always returns identical states
   *
   * Feature: monitor-accounting-improvements, Property: Consistent State Set
   * Validates: Requirements 5
   */
  it('Property 12: getAllStates returns consistent set across calls', () => {
    const states1 = service.getAllStates();
    const states2 = service.getAllStates();

    expect(states1.length).toBe(states2.length);
    states1.forEach((state, idx) => {
      expect(state.label).toBe(states2[idx].label);
      expect(state.statusValue).toBe(states2[idx].statusValue);
    });
  });

  /**
   * Property 13: isTerminal consistency
   * For any state label, isTerminal matches whether nextStates is empty
   *
   * Feature: monitor-accounting-improvements, Property: Terminal State Consistency
   * Validates: Requirements 5
   */
  it('Property 13: isTerminal value matches nextStates emptiness', () => {
    const allStates = service.getAllStates();

    allStates.forEach((state) => {
      const isTerminal = service.isTerminal(state.label);
      const isEmpty = state.nextStates.length === 0;

      expect(isTerminal).toBe(isEmpty);
    });
  });

  // ─────────────────────────────────────────────────────────────────────
  // Tests for Timeout State Definitions (Requirement 11)
  // ─────────────────────────────────────────────────────────────────────

  /**
   * Validates: Requirements 6.1, 6.2
   */
  it('Requirement 6.1: Enrichment Timeout state is defined with description', () => {
    const state = service.getStateByLabel('Enrichment Timeout');
    expect(state).toBeDefined();
    expect(state?.statusValue).toBe('ENRICHMENT_TIMEOUT');
    expect(state?.description).toContain('No enrichment confirmation');
    expect(state?.description).toContain('ES or pp_log after timeout');
  });

  /**
   * Validates: Requirements 6.2
   */
  it('Requirement 6.2: Exensio Timeout state is defined with description', () => {
    const state = service.getStateByLabel('Exensio Timeout');
    expect(state).toBeDefined();
    expect(state?.statusValue).toBe('EXENSIO_TIMEOUT');
    expect(state?.description).toContain('Wafer not found in Exensio after timeout');
  });

  /**
   * Validates: Requirements 6.3
   */
  it('Requirement 6.3: Enrichment Timeout legend indicates uncertainty vs failure', () => {
    const state = service.getStateByLabel('Enrichment Timeout');
    expect(state?.tooltip).toContain('NOT a failure');
    expect(state?.tooltip).toContain('enrichment status is uncertain');
    expect(state?.tooltip).toContain('manual verification');
  });

  /**
   * Validates: Requirements 6.3
   */
  it('Requirement 6.3: Exensio Timeout legend indicates uncertainty vs failure', () => {
    const state = service.getStateByLabel('Exensio Timeout');
    expect(state?.tooltip).toContain('NOT a failure');
    expect(state?.tooltip).toContain('wafer existence is uncertain');
    expect(state?.tooltip).toContain('manual verification');
  });

  /**
   * Validates: Requirements 6.4
   */
  it('Requirement 6.4: Enrichment Timeout defines possible transitions', () => {
    const state = service.getStateByLabel('Enrichment Timeout');
    expect(state?.nextStates).toContain('Completed');
    expect(state?.nextStates).toContain('Failed');
    expect(state?.nextStates).toContain('In Enrichment');
  });

  /**
   * Validates: Requirements 6.4
   */
  it('Requirement 6.4: Exensio Timeout defines possible transitions', () => {
    const state = service.getStateByLabel('Exensio Timeout');
    expect(state?.nextStates).toContain('Completed');
    expect(state?.nextStates).toContain('Failed');
    expect(state?.nextStates).toContain('Exensio Loading');
  });

  /**
   * Validates: Requirements 6.1, 6.2
   */
  it('Requirement 6.1 & 6.2: Timeout states use warning color to indicate uncertainty', () => {
    const enrichmentTimeout = service.getStateByLabel('Enrichment Timeout');
    const exensioTimeout = service.getStateByLabel('Exensio Timeout');

    expect(enrichmentTimeout?.color).toBe('warning');
    expect(exensioTimeout?.color).toBe('warning');
  });

  /**
   * Validates: Requirements 6.1, 6.2
   */
  it('Requirement 6.1 & 6.2: Timeout states use schedule icon', () => {
    const enrichmentTimeout = service.getStateByLabel('Enrichment Timeout');
    const exensioTimeout = service.getStateByLabel('Exensio Timeout');

    expect(enrichmentTimeout?.icon).toBe('schedule');
    expect(exensioTimeout?.icon).toBe('schedule');
  });

  /**
   * Validates: Requirements 6.5
   */
  it('Requirement 6.5: Enrichment Timeout notes explain need for manual verification', () => {
    const state = service.getStateByLabel('Enrichment Timeout');
    expect(state?.tooltip).toContain('Operator should verify');
    expect(state?.tooltip).toContain('May be automatically retried');
    expect(state?.tooltip).toContain('Records requiring manual verification');
  });

  /**
   * Validates: Requirements 6.5
   */
  it('Requirement 6.5: Exensio Timeout notes explain wafer may appear later', () => {
    const state = service.getStateByLabel('Exensio Timeout');
    expect(state?.tooltip).toContain('May appear later');
    expect(state?.tooltip).toContain('delayed loading');
    expect(state?.tooltip).toContain('Needs manual verification or retry');
  });

  /**
   * Validates: Requirements 6.1, 6.2, 6.3, 6.4
   * All timeout states reachable from their upstream states
   */
  it('Requirement 6.1, 6.2, 6.3, 6.4: Timeout states are valid transitions from their upstream states', () => {
    // Enrichment Timeout should be reachable from In Enrichment
    const inEnrichment = service.getStateByLabel('In Enrichment');
    expect(inEnrichment?.nextStates).toContain('Enrichment Timeout');

    // Exensio Timeout should be reachable from Exensio Loading
    const exensioLoading = service.getStateByLabel('Exensio Loading');
    expect(exensioLoading?.nextStates).toContain('Exensio Timeout');
  });
});
