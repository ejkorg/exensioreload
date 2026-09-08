import { CommonModule } from '@angular/common';
import {
  AfterViewInit,
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  EventEmitter,
  Input,
  Output,
} from '@angular/core';
import * as d3 from 'd3';

export interface PipelineFlowNode {
  key: string; // status key used by the dashboard
  label: string;
  /** Semantics token → hex mapped in the template style helper. */
  tone: 'success' | 'warning' | 'danger' | 'primary' | 'info' | 'secondary';
}

/** Linear main flow; timeout/verify/failure/cancel render as branch chips. */
export const PIPELINE_FLOW_DEF: PipelineFlowNode[] = [
  { key: 'staged', label: 'Staged', tone: 'secondary' },
  { key: 'enqueued', label: 'Queued', tone: 'info' },
  { key: 'enriching', label: 'Enriching', tone: 'primary' },
  { key: 'exensio', label: 'Exensio', tone: 'info' },
  { key: 'completed', label: 'Completed', tone: 'success' },
];

export const PIPELINE_BRANCH_DEF: PipelineFlowNode[] = [
  { key: 'timeout', label: 'CP Timeout', tone: 'warning' },
  { key: 'verify', label: 'Verify in Exensio', tone: 'warning' },
  { key: 'failed', label: 'Failed', tone: 'danger' },
  { key: 'cancelled', label: 'Cancelled', tone: 'secondary' },
];

const TONE_HEX: Record<PipelineFlowNode['tone'], string> = {
  success: '#10b981',
  warning: '#f59e0b',
  danger: '#ef4444',
  primary: '#818cf8',
  info: '#60a5fa',
  secondary: '#94a3b8',
};

/**
 * PipelineFlowVisualizationComponent — interactive 9-state flow visualization
 * (Requirement 19). Counts come from the dashboard snapshot; clicking a node
 * emits the state key so the host can flash/scroll the matching KPI card.
 * D3 is used to pulse the connector between nodes whenever counts change.
 */
@Component({
  selector: 'app-pipeline-flow',
  standalone: true,
  imports: [CommonModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="pipeline-flow glass-panel">
      <div class="flow-main">
        @for (node of mainNodes(); track node.key) {
          @if (!$first) {
            <div class="flow-connector" aria-hidden="true"></div>
          }
          <button
            type="button"
            class="flow-node"
            [class.has-count]="(counts?.[node.key] ?? 0) > 0"
            (click)="stateClick.emit(node.key)"
            [style.borderColor]="toneHex(node.tone)"
            [style.color]="toneHex(node.tone)"
          >
            <span class="node-count" [style.background]="toneHex(node.tone)">
              {{ counts?.[node.key] ?? 0 }}
            </span>
            <span class="node-label">{{ node.label }}</span>
          </button>
        }
      </div>

      <div class="flow-branches">
        @for (branch of branchNodes(); track branch.key) {
          <button
            type="button"
            class="flow-branch-chip"
            [class.has-count]="(counts?.[branch.key] ?? 0) > 0"
            (click)="stateClick.emit(branch.key)"
            [style.color]="toneHex(branch.tone)"
            [style.borderColor]="toneHex(branch.tone)"
          >
            <span class="branch-dot" [style.background]="toneHex(branch.tone)"></span>
            {{ branch.label }} · {{ counts?.[branch.key] ?? 0 }}
          </button>
        }
      </div>
    </div>
  `,
  styles: [
    `
      .pipeline-flow {
        display: flex;
        flex-direction: column;
        gap: 0.75rem;
        padding: 0.9rem 1rem;
        border-radius: 14px;
        border: 1px solid rgba(167, 139, 250, 0.16);
        background: rgba(22, 16, 52, 0.4);
      }
      .flow-main {
        display: flex;
        align-items: center;
        gap: 0.4rem;
        flex-wrap: wrap;
      }
      .flow-connector {
        flex: 1;
        min-width: 18px;
        height: 2px;
        background: linear-gradient(90deg, rgba(129, 140, 248, 0.35), rgba(96, 165, 250, 0.35));
        transition: background 0.3s ease;
      }
      .flow-node {
        display: inline-flex;
        flex-direction: column;
        align-items: center;
        gap: 0.25rem;
        padding: 0.4rem 0.7rem;
        border-radius: 12px;
        border: 1px solid;
        background: rgba(255, 255, 255, 0.02);
        cursor: pointer;
        min-width: 74px;
        transition: transform 0.15s ease, box-shadow 0.15s ease;
      }
      .flow-node:hover {
        transform: translateY(-2px);
      }
      .flow-node.has-count {
        background: rgba(255, 255, 255, 0.05);
        box-shadow: 0 0 14px rgba(129, 140, 248, 0.25);
      }
      .node-count {
        display: inline-flex;
        align-items: center;
        justify-content: center;
        min-width: 26px;
        height: 20px;
        padding: 0 0.3rem;
        border-radius: 999px;
        font-size: 0.72rem;
        font-weight: 800;
        color: #fff;
      }
      .node-label {
        font-size: 0.62rem;
        font-weight: 700;
        text-transform: uppercase;
        letter-spacing: 0.05em;
      }
      .flow-branches {
        display: flex;
        flex-wrap: wrap;
        gap: 0.4rem;
        padding-left: 0.25rem;
      }
      .flow-branch-chip {
        display: inline-flex;
        align-items: center;
        gap: 0.35rem;
        padding: 0.2rem 0.6rem;
        border-radius: 999px;
        border: 1px solid;
        background: transparent;
        font-size: 0.66rem;
        font-weight: 700;
        cursor: pointer;
        opacity: 0.75;
        transition: opacity 0.15s ease;
      }
      .flow-branch-chip.has-count {
        opacity: 1;
      }
      .flow-branch-chip:hover {
        opacity: 1;
      }
      .branch-dot {
        width: 7px;
        height: 7px;
        border-radius: 50%;
      }
    `,
  ],
})
export class PipelineFlowComponent implements AfterViewInit {
  @Input() counts: Record<string, number> | null = null;
  @Output() stateClick = new EventEmitter<string>();

  constructor(private readonly el: ElementRef) {}

  mainNodes(): PipelineFlowNode[] {
    return PIPELINE_FLOW_DEF;
  }

  branchNodes(): PipelineFlowNode[] {
    return PIPELINE_BRANCH_DEF;
  }

  toneHex(tone: PipelineFlowNode['tone']): string {
    return TONE_HEX[tone];
  }

  ngAfterViewInit(): void {
    this.pulseConnectors();
  }

  /** D3-driven pulse of the main connectors when counts change. */
  private pulseConnectors(): void {
    const host = this.el.nativeElement as HTMLElement;
    const connectors = d3.select(host).selectAll<HTMLDivElement, unknown>('.flow-connector');
    connectors
      .transition()
      .duration(350)
      .style('opacity', 0.4)
      .transition()
      .duration(350)
      .style('opacity', 1);
  }
}
