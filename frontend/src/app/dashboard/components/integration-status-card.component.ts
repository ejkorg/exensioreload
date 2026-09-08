import { CommonModule } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, Input, Output, EventEmitter, signal } from '@angular/core';
import { IntegrationStatusSnapshot, IntegrationStatusEntry } from '../../api/backend.service';
import { StateLegendService, IntegrationStatusDefinition } from '../state-legend.service';

interface IntegrationRow {
  service: 'elasticsearch' | 'exensio';
  serviceLabel: string;
  entry: IntegrationStatusEntry;
  definition: IntegrationStatusDefinition;
  lastAt?: string | null;
}

/**
 * IntegrationStatusCardComponent shows Elasticsearch + Exensio health using the
 * StateLegendService integration definitions as the single source of truth
 * (Requirement 18.7). The snapshot is provided by the host (sourced from an
 * active monitoring session's `integration` payload when available) and polled
 * every 30 seconds by the dashboard.
 *
 * Requirements: 15.1-15.5, 18.1-18.7
 */
@Component({
  selector: 'app-integration-status-card',
  standalone: true,
  imports: [CommonModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="integration-card">
      <div class="integration-head">
        <h3 class="integration-title">Integrations</h3>
        <span class="integration-updated" *ngIf="lastUpdatedLabel()">Updated {{ lastUpdatedLabel() }}</span>
      </div>

      <div class="integration-rows">
        <div class="integration-row" *ngFor="let row of rows()">
          <div class="service-label">
            <span class="service-icon">
              <span class="icon-glyph" [style.color]="row.definition.color">
                {{ iconText(row.definition.icon) }}
              </span>
            </span>
            <span class="service-name">{{ row.serviceLabel }}</span>
          </div>

          <div class="service-status">
            <span
              class="status-pill"
              [style.background]="pillBackground(row.definition.color)"
              [style.color]="row.definition.color"
              [style.borderColor]="pillBorder(row.definition.color)"
            >
              <span class="status-dot" [style.background]="row.definition.color"></span>
              {{ row.entry.status === 'success' ? 'Connected' : row.definition.label }}
            </span>

            <span class="status-meta" *ngIf="row.entry.status === 'success' && row.lastAt">
              Last OK {{ row.lastAt | date: 'HH:mm:ss' }}
            </span>
            <span
              class="status-meta error-meta"
              *ngIf="row.entry.message && row.entry.status !== 'success' && row.entry.status !== 'not_configured'"
            >
              {{ row.entry.message }}
            </span>
            <span class="status-meta muted-meta" *ngIf="row.entry.status === 'not_configured'">Not configured</span>
          </div>
        </div>
      </div>
    </div>
  `,
  styles: [
    `
      .integration-card {
        display: flex;
        flex-direction: column;
        gap: 0.6rem;
        padding: 0.9rem 1rem;
        border-radius: 14px;
        border: 1px solid rgba(167, 139, 250, 0.18);
        background: rgba(22, 16, 52, 0.45);
        min-width: 260px;
        max-width: 520px;
      }
      .integration-head {
        display: flex;
        align-items: baseline;
        justify-content: space-between;
        gap: 0.5rem;
      }
      .integration-title {
        margin: 0;
        font-size: 0.78rem;
        font-weight: 700;
        text-transform: uppercase;
        letter-spacing: 0.06em;
        color: rgba(226, 232, 255, 0.92);
      }
      .integration-updated {
        font-size: 0.65rem;
        color: rgba(203, 213, 225, 0.5);
      }
      .integration-rows {
        display: flex;
        flex-direction: column;
        gap: 0.5rem;
      }
      .integration-row {
        display: flex;
        align-items: center;
        justify-content: space-between;
        gap: 0.75rem;
      }
      .service-label {
        display: flex;
        align-items: center;
        gap: 0.5rem;
        min-width: 0;
      }
      .icon-glyph {
        font-size: 1rem;
        line-height: 1;
        width: 24px;
        height: 24px;
        display: inline-flex;
        align-items: center;
        justify-content: center;
        border-radius: 6px;
        background: rgba(255, 255, 255, 0.05);
      }
      .service-name {
        font-size: 0.78rem;
        font-weight: 600;
        color: rgba(226, 232, 255, 0.9);
      }
      .service-status {
        display: flex;
        align-items: center;
        gap: 0.5rem;
        min-width: 0;
      }
      .status-pill {
        display: inline-flex;
        align-items: center;
        gap: 0.35rem;
        padding: 0.2rem 0.6rem;
        border-radius: 999px;
        border: 1px solid transparent;
        font-size: 0.65rem;
        font-weight: 800;
        text-transform: uppercase;
        letter-spacing: 0.06em;
        white-space: nowrap;
        flex-shrink: 0;
      }
      .status-dot {
        width: 6px;
        height: 6px;
        border-radius: 50%;
        flex-shrink: 0;
      }
      .status-meta {
        font-size: 0.65rem;
        color: rgba(203, 213, 225, 0.6);
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
        max-width: 170px;
      }
      .error-meta {
        color: #f87171;
      }
      .muted-meta {
        color: rgba(203, 213, 225, 0.45);
      }
    `,
  ],
})
export class IntegrationStatusCardComponent {
  @Output() retryRequested = new EventEmitter<'elasticsearch' | 'exensio'>();

  private readonly snapshotSig = signal<IntegrationStatusSnapshot | null>(null);

  @Input() set snapshot(value: IntegrationStatusSnapshot | null) {
    this.snapshotSig.set(value ?? null);
  }

  readonly rows = computed<IntegrationRow[]>(() => {
    const snap = this.snapshotSig();
    const mk = (service: 'elasticsearch' | 'exensio', label: string, entry?: IntegrationStatusEntry): IntegrationRow => {
      const resolved: IntegrationStatusEntry = entry ?? {
        configured: false,
        status: 'not_configured',
        message: '',
        lastAt: null,
      };
      return {
        service,
        serviceLabel: label,
        entry: resolved,
        definition: this.legend.getIntegrationStatus(resolved.status),
        lastAt: resolved.lastAt,
      };
    };
    return [
      mk('elasticsearch', 'Elasticsearch', snap?.elasticsearch),
      mk('exensio', 'Exensio API', snap?.exensio),
    ];
  });

  readonly lastUpdatedLabel = computed<string | null>(() => {
    const snap = this.snapshotSig();
    const times = snap
      ? [snap.elasticsearch, snap.exensio]
          .map((i) => (i?.lastAt ? new Date(i.lastAt).getTime() : null))
          .filter((t): t is number => t !== null)
      : [];
    if (times.length === 0) return null;
    return new Date(Math.max(...times)).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
  });

  constructor(private readonly legend: StateLegendService) {}

  onRetry(service: 'elasticsearch' | 'exensio'): void {
    this.retryRequested.emit(service);
  }

  private iconText(icon: string): string {
    switch (icon) {
      case 'check_circle':
        return '✓';
      case 'error':
        return '✕';
      case 'warning':
        return '!';
      case 'hourglass_empty':
        return '◷';
      case 'settings':
        return '⚙';
      default:
        return '•';
    }
  }

  private pillBackground(color: string): string {
    return this.hexToRgba(color, 0.12);
  }

  private pillBorder(color: string): string {
    return this.hexToRgba(color, 0.3);
  }

  private hexToRgba(hex: string, alpha: number): string {
    const clean = hex.replace('#', '');
    const full = clean.length === 3 ? clean.split('').map((c) => c + c).join('') : clean;
    const num = Number.parseInt(full, 16);
    if (Number.isNaN(num)) return `rgba(148,163,184,${alpha})`;
    return `rgba(${(num >> 16) & 255},${(num >> 8) & 255},${num & 255},${alpha})`;
  }
}
