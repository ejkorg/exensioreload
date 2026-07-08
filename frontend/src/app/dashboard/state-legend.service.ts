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
        statusValue: 'STAGED',
        color: 'secondary',
        icon: 'inbox',
        nextStates: ['Queued for Enrichment'],
        isTerminal: false,
        tooltip: `Staged (STAGED)
Loaded to REFDB. Ready for dispatch to the enrichment pipeline.
Example transition: STAGED → QUEUED_FOR_CP → ELASTICSEARCH_MONITORING → COMPLETED`,
      },
    ],
    [
      'Queued for Enrichment',
      {
        label: 'Queued for Enrichment',
        description: 'Waiting to enter enrichment pipeline',
        statusValue: 'QUEUED_FOR_CP',
        color: 'info',
        icon: 'schedule',
        nextStates: ['Enrichment Processing'],
        isTerminal: false,
        tooltip: `Queued for Enrichment (ENQUEUED)
Record has been inserted into the send queue and is waiting to be picked up for enrichment processing.
Example transition: ENQUEUED → ENRICHMENT → EXENSIO_LOADING → DONE`,
      },
    ],
    [
      'Enrichment Processing',
      {
        label: 'Enrichment Processing',
        description: 'Actively being enriched from Elasticsearch and pp_log',
        statusValue: 'ELASTICSEARCH_MONITORING',
        color: 'primary',
        icon: 'auto_awesome',
        nextStates: ['Exensio Monitoring', 'Enrichment Monitoring Timeout', 'Failed'],
        isTerminal: false,
        tooltip: `Enrichment Processing (ENRICHMENT)
Record has been consumed from the send queue and is actively being enriched.
Elasticsearch and pp_log are being queried for matching records.
Example transitions:
  → EXENSIO_LOADING (enrichment found, Exensio verification enabled)
  → ENRICHMENT_TIMEOUT (no enrichment log found within 15 min)
  → DONE (enrichment successful, no Exensio step needed)
  → FAILED (enrichment failed with a concrete error)`,
      },
    ],
    [
      'Enrichment Monitoring Timeout',
      {
        label: 'Enrichment Monitoring Timeout',
        description: 'No enrichment log found in ES or pp_log within monitoring window',
        statusValue: 'CP_TIMEOUT',
        color: 'warning',
        icon: 'schedule',
        nextStates: ['Completed', 'Failed', 'Enrichment Processing'],
        isTerminal: false,
        tooltip: `Enrichment Monitoring Timeout (ENRICHMENT_TIMEOUT)
No enrichment confirmation found in Elasticsearch or pp_log within the monitoring window (15 min default).
This is NOT a failure — the enrichment may have occurred but was not detected by the monitoring process.

Possible actions:
  → Completed (if manually verified that enrichment occurred)
  → Failed (if confirmed no enrichment available)
  → Enrichment Processing (if manual retry is triggered)

Notes:
  • Operator should verify the lot/file in the enrichment system before taking corrective action
  • May be automatically retried after cooldown period`,
      },
    ],
    [
      'Exensio Monitoring',
      {
        label: 'Exensio Monitoring',
        description: 'Monitoring Exensio load status',
        statusValue: 'EXENSIO_MONITORING',
        color: 'info',
        icon: 'cloud_download',
        nextStates: ['Completed', 'Completed — Verify in Exensio', 'Failed'],
        isTerminal: false,
        tooltip: `Exensio Monitoring (EXENSIO_LOADING)
Enrichment is complete. Record is now being monitored for confirmation of load into Exensio.
Only appears if Exensio integration is enabled.
Example transitions:
  → DONE (wafer confirmed loaded in Exensio)
  → EXENSIO_TIMEOUT (wafer not found within monitoring timeout — manual verification required)
  → FAILED (Exensio load failure detected)`,
      },
    ],
    [
      'Completed — Verify in Exensio',
      {
        label: 'Completed — Verify in Exensio',
        description: 'Exensio record not found within monitoring window; requires manual verification',
        statusValue: 'COMPLETED_MANUAL_VERIFICATION_REQUIRED',
        color: 'warning',
        icon: 'schedule',
        nextStates: ['Completed', 'Failed', 'Exensio Monitoring'],
        isTerminal: false,
        tooltip: `Completed — Verify in Exensio (EXENSIO_TIMEOUT)
The enrichment was consumed and no load failure was detected, but the record could not be confirmed in Exensio within the monitoring window.
This is NOT a failure — the record may have loaded successfully but was not detected by the monitoring process.

Operations should manually verify the lot/file in Exensio before taking corrective action.

Possible actions:
  → Completed (if manually verified that Exensio load occurred)
  → Failed (if confirmed wafer does not exist in Exensio)
  → Exensio Monitoring (if manual retry of monitoring is triggered)

Notes:
  • Wafer may appear in Exensio later (delayed indexing)
  • Do not mark as failed until manual verification is complete`,
      },
    ],
    [
      'Completed',
      {
        label: 'Completed',
        description: 'Successfully processed',
        statusValue: 'COMPLETED',
        color: 'success',
        icon: 'check_circle',
        nextStates: [],
        isTerminal: true,
        tooltip: `Completed (COMPLETED)
Record was successfully processed through the entire pipeline.
This is a terminal state — no further transitions.
Example origin: ELASTICSEARCH_MONITORING → Completed or EXENSIO_MONITORING → Completed`,
      },
    ],
    [
      'Failed',
      {
        label: 'Failed',
        description: 'Encountered error during processing',
        statusValue: 'CP_FAILED',
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
