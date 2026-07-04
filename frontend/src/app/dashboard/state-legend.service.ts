import { Injectable } from '@angular/core';

export interface StateDefinition {
  label: string;
  description: string;
  statusValue: string;
  color: string;
  icon: string;
  nextStates: string[];
  isTerminal: boolean;
  tooltip: string;
}

@Injectable({
  providedIn: 'root',
})
export class StateLegendService {
  private readonly states: Map<string, StateDefinition> = new Map([
    [
      'Staged',
      {
        label: 'Staged',
        description: 'Ready for dispatch',
        statusValue: 'pending',
        color: 'secondary',
        icon: 'inbox',
        nextStates: ['Queued for CP'],
        isTerminal: false,
        tooltip: `Staged (pending)
Ready for dispatch to the CP pipeline.
Example transition: pending → ENQUEUED → ENRICHMENT → DONE`,
      },
    ],
    [
      'Queued for CP',
      {
        label: 'Queued for CP',
        description: 'Waiting to enter CP pipeline',
        statusValue: 'ENQUEUED',
        color: 'info',
        icon: 'schedule',
        nextStates: ['In Enrichment'],
        isTerminal: false,
        tooltip: `Queued for CP (ENQUEUED)
Waiting to enter the Coverage Point (CP) enrichment pipeline.
Example transition: ENQUEUED → ENRICHMENT → EXENSIO_LOADING → DONE`,
      },
    ],
    [
      'In Enrichment',
      {
        label: 'In Enrichment',
        description: 'Currently being processed by CP',
        statusValue: 'ENRICHMENT',
        color: 'primary',
        icon: 'auto_awesome',
        nextStates: ['Exensio Loading', 'Enrichment Timeout', 'Failed'],
        isTerminal: false,
        tooltip: `In Enrichment (ENRICHMENT)
Currently being enriched and translated by Coverage Point.
Stuck records: If enrichment exceeds timeout (15 min), marked for manual review.
Example transitions:
  → EXENSIO_LOADING (if Exensio verification enabled)
  → ENRICHMENT_TIMEOUT (if enrichment times out with no result)
  → DONE (enrichment successful)
  → FAILED (enrichment failed)`,
      },
    ],
    [
      'Enrichment Timeout',
      {
        label: 'Enrichment Timeout',
        description: 'No enrichment confirmation from ES or pp_log after timeout',
        statusValue: 'ENRICHMENT_TIMEOUT',
        color: 'warning',
        icon: 'schedule',
        nextStates: ['Completed', 'Failed', 'In Enrichment'],
        isTerminal: false,
        tooltip: `Enrichment Timeout (ENRICHMENT_TIMEOUT)
No enrichment confirmation from Elasticsearch or pp_log after timeout (15 min default).
Exensio direct lookup also returned NotFound.
This is NOT a failure — enrichment status is uncertain.

Possible actions:
  → Completed (if verified enrichment occurred)
  → Failed (if confirmed no enrichment available)
  → In Enrichment (if manual retry is triggered)

Notes:
  • Operator should verify if enrichment actually occurred
  • May be automatically retried after cooldown period
  • Records requiring manual verification or retry`,
      },
    ],
    [
      'Exensio Loading',
      {
        label: 'Exensio Loading',
        description: 'Undergoing Exensio verification',
        statusValue: 'EXENSIO_LOADING',
        color: 'info',
        icon: 'cloud_download',
        nextStates: ['Completed', 'Exensio Timeout', 'Failed'],
        isTerminal: false,
        tooltip: `Exensio Loading (EXENSIO_LOADING)
Record is undergoing Exensio verification and enrichment.
Only appears if Exensio integration is enabled.
Example transitions:
  → DONE (wafer found and verified)
  → EXENSIO_TIMEOUT (wafer not found after timeout)
  → FAILED (Exensio verification failed)`,
      },
    ],
    [
      'Exensio Timeout',
      {
        label: 'Exensio Timeout',
        description: 'Wafer not found in Exensio after timeout',
        statusValue: 'EXENSIO_TIMEOUT',
        color: 'warning',
        icon: 'schedule',
        nextStates: ['Completed', 'Failed', 'Exensio Loading'],
        isTerminal: false,
        tooltip: `Exensio Timeout (EXENSIO_TIMEOUT)
Wafer not found in Exensio after timeout (60 min default).
May appear later or may genuinely not exist.
This is NOT a failure — wafer existence is uncertain.

Possible actions:
  → Completed (if wafer verified to exist)
  → Failed (if confirmed wafer doesn't exist)
  → Exensio Loading (if manual retry is triggered)

Notes:
  • Wafer may appear in Exensio later (delayed loading)
  • Wafer may not exist in Exensio at all
  • Needs manual verification or retry`,
      },
    ],
    [
      'Completed',
      {
        label: 'Completed',
        description: 'Successfully processed',
        statusValue: 'DONE',
        color: 'success',
        icon: 'check_circle',
        nextStates: [],
        isTerminal: true,
        tooltip: `Completed (DONE)
Record was successfully processed through the entire pipeline.
This is a terminal state — no further transitions.
Example origin: ENRICHMENT → Completed or EXENSIO_LOADING → Completed`,
      },
    ],
    [
      'Failed',
      {
        label: 'Failed',
        description: 'Encountered error during processing',
        statusValue: 'FAILED',
        color: 'danger',
        icon: 'error_outline',
        nextStates: [],
        isTerminal: true,
        tooltip: `Failed (FAILED)
Record encountered an error and cannot continue processing.
This is a terminal state — manual intervention may be required.
Can originate from: ENRICHMENT or EXENSIO_LOADING state`,
      },
    ],
    [
      'Cancelled',
      {
        label: 'Cancelled',
        description: 'Paused or soft-deleted by user',
        statusValue: 'CANCELLED',
        color: 'danger',
        icon: 'block',
        nextStates: [],
        isTerminal: true,
        tooltip: `Cancelled (CANCELLED)
Record was paused or deleted by a user bulk operation.
This is a terminal state — record is no longer processed.
May be re-activated through manual intervention.`,
      },
    ],
  ]);

  constructor() {}

  /**
   * Get state definition by label
   */
  getStateByLabel(label: string): StateDefinition | undefined {
    return this.states.get(label);
  }

  /**
   * Get all state definitions
   */
  getAllStates(): StateDefinition[] {
    return Array.from(this.states.values());
  }

  /**
   * Get state label by status value
   */
  getLabelByStatus(statusValue: string): string | undefined {
    for (const [label, state] of this.states) {
      if (state.statusValue === statusValue) {
        return label;
      }
    }
    return undefined;
  }

  /**
   * Get tooltip text for a state label
   */
  getTooltip(label: string): string {
    const state = this.getStateByLabel(label);
    return state?.tooltip || `${label} — State in processing pipeline.`;
  }

  /**
   * Check if a state is terminal (no further transitions)
   */
  isTerminal(label: string): boolean {
    const state = this.getStateByLabel(label);
    return state?.isTerminal ?? false;
  }

  /**
   * Get possible next states for a given state
   */
  getNextStates(label: string): string[] {
    const state = this.getStateByLabel(label);
    return state?.nextStates ?? [];
  }

  /**
   * Get a formatted legend entry with transitions
   */
  getFormattedLegend(label: string): string {
    const state = this.getStateByLabel(label);
    if (!state) return label;

    let result = `${state.label}: ${state.description}`;
    if (state.nextStates.length > 0) {
      result += `\nPossible next: ${state.nextStates.join(', ')}`;
    } else {
      result += '\n(Terminal state)';
    }
    return result;
  }
}
