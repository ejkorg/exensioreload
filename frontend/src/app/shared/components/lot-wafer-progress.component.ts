import { Component, Input, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { GlassIconComponent } from './glass-icon.component';

export interface LotProgress {
  lot: string;
  totalWafers: number;
  completedWafers: number;
  failedWafers: number;
  progress: number;
  expanded?: boolean;
  wafers?: WaferProgress[];
}

export interface WaferProgress {
  wafer: string;
  filename: string;
  status: string;
  displayStatus: string;
}

@Component({
  selector: 'app-lot-wafer-progress',
  standalone: true,
  imports: [CommonModule, GlassIconComponent],
  template: `
    <div class="lot-wafer-progress">
      <div class="section-header">
        <app-glass-icon name="layers" [size]="20" color="primary"></app-glass-icon>
        <span class="section-title">Lot & Wafer Progress</span>
      </div>

      <div class="lots-container" *ngIf="lots().length > 0; else noLots">
        <div class="lot-card glass-panel" *ngFor="let lot of lots()">
          <!-- Lot Header -->
          <div class="lot-header" (click)="toggleLot(lot)">
            <div class="lot-info">
              <div class="lot-name">
                <app-glass-icon
                  [name]="lot.expanded ? 'expand_more' : 'chevron_right'"
                  [size]="20"
                  color="muted">
                </app-glass-icon>
                <span class="lot-label">{{ lot.lot }}</span>
              </div>
              <div class="lot-stats">
                <span class="stat-badge completed">
                  <app-glass-icon name="check_circle" [size]="14" color="success"></app-glass-icon>
                  {{ lot.completedWafers }}
                </span>
                <span class="stat-badge failed" *ngIf="lot.failedWafers > 0">
                  <app-glass-icon name="error" [size]="14" color="error"></app-glass-icon>
                  {{ lot.failedWafers }}
                </span>
                <span class="stat-badge total">
                  {{ getLotCountLabel(lot) }}
                </span>
              </div>
            </div>
            <div class="lot-progress-bar">
              <div class="progress-track">
                <div class="progress-fill"
                     [style.width.%]="lot.progress"
                     [class.complete]="lot.progress === 100"
                     [class.has-errors]="lot.failedWafers > 0">
                </div>
              </div>
              <span class="progress-text">{{ lot.progress.toFixed(1) }}%</span>
            </div>
          </div>

          <!-- Wafer List (Expanded) -->
          <div class="wafer-list" *ngIf="lot.expanded && lot.wafers && lot.wafers.length > 0">
            <div class="wafer-item" *ngFor="let wafer of lot.wafers">
              <div class="wafer-icon">
                <app-glass-icon
                  [name]="getStatusIcon(wafer.displayStatus)"
                  [size]="16"
                  [color]="getStatusColor(wafer.displayStatus)">
                </app-glass-icon>
              </div>
              <div class="wafer-info">
                <span class="wafer-name">{{ wafer.wafer }}</span>
                <span class="wafer-filename">{{ wafer.filename }}</span>
              </div>
              <span class="wafer-status" [class]="'status-' + wafer.displayStatus.toLowerCase()">
                {{ wafer.displayStatus }}
              </span>
            </div>
          </div>
        </div>
      </div>

      <ng-template #noLots>
        <div class="empty-state glass-panel">
          <app-glass-icon name="layers" [size]="48" color="muted"></app-glass-icon>
          <p class="empty-message">No lot data available</p>
        </div>
      </ng-template>
    </div>
  `,
  styles: [`
    .lot-wafer-progress {
      display: flex;
      flex-direction: column;
      gap: 1rem;
    }

    .section-header {
      display: flex;
      align-items: center;
      gap: 0.5rem;
      padding: 0 0.25rem;
    }

    .section-title {
      font-size: 0.875rem;
      font-weight: 600;
      color: var(--text-main);
      text-transform: uppercase;
      letter-spacing: 0.05em;
    }

    .lots-container {
      display: flex;
      flex-direction: column;
      gap: 0.75rem;
    }

    .lot-card {
      padding: 0;
      overflow: hidden;
    }

    .lot-header {
      padding: 1rem;
      cursor: pointer;
      transition: background 0.2s ease;
      display: flex;
      flex-direction: column;
      gap: 0.75rem;
    }

    .lot-header:hover {
      background: rgba(255, 255, 255, 0.03);
    }

    .lot-info {
      display: flex;
      justify-content: space-between;
      align-items: center;
      gap: 1rem;
    }

    .lot-name {
      display: flex;
      align-items: center;
      gap: 0.5rem;
    }

    .lot-label {
      font-size: 0.9375rem;
      font-weight: 600;
      color: var(--text-main);
    }

    .lot-stats {
      display: flex;
      align-items: center;
      gap: 0.5rem;
      flex-wrap: wrap;
    }

    .stat-badge {
      display: flex;
      align-items: center;
      gap: 0.25rem;
      padding: 0.25rem 0.5rem;
      border-radius: 6px;
      font-size: 0.75rem;
      font-weight: 500;
      background: rgba(255, 255, 255, 0.05);
    }

    .stat-badge.completed {
      background: rgba(16, 185, 129, 0.15);
      color: #10b981;
    }

    .stat-badge.failed {
      background: rgba(239, 68, 68, 0.15);
      color: #ef4444;
    }

    .stat-badge.total {
      color: var(--text-muted);
    }

    .lot-progress-bar {
      display: flex;
      align-items: center;
      gap: 0.75rem;
    }

    .progress-track {
      flex: 1;
      height: 8px;
      background: rgba(255, 255, 255, 0.05);
      border-radius: 4px;
      overflow: hidden;
    }

    .progress-fill {
      height: 100%;
      background: linear-gradient(90deg, var(--accent-color), #a78bfa);
      border-radius: 4px;
      transition: width 0.5s ease;
    }

    .progress-fill.complete {
      background: linear-gradient(90deg, #10b981, #34d399);
    }

    .progress-fill.has-errors {
      background: linear-gradient(90deg, #f59e0b, #fbbf24);
    }

    .progress-text {
      font-size: 0.8125rem;
      font-weight: 600;
      color: var(--text-main);
      min-width: 45px;
      text-align: right;
    }

    .wafer-list {
      border-top: 1px solid rgba(255, 255, 255, 0.05);
      padding: 0.5rem;
      background: rgba(0, 0, 0, 0.1);
    }

    .wafer-item {
      display: flex;
      align-items: center;
      gap: 0.75rem;
      padding: 0.625rem;
      border-radius: 6px;
      transition: background 0.2s ease;
    }

    .wafer-item:hover {
      background: rgba(255, 255, 255, 0.03);
    }

    .wafer-icon {
      flex-shrink: 0;
    }

    .wafer-info {
      flex: 1;
      min-width: 0;
      display: flex;
      flex-direction: column;
      gap: 0.125rem;
    }

    .wafer-name {
      font-size: 0.8125rem;
      font-weight: 600;
      color: var(--text-main);
    }

    .wafer-filename {
      font-size: 0.75rem;
      color: var(--text-muted);
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .wafer-status {
      font-size: 0.75rem;
      font-weight: 500;
      padding: 0.25rem 0.5rem;
      border-radius: 4px;
      text-transform: uppercase;
      letter-spacing: 0.05em;
    }

    .wafer-status.status-staged {
      background: rgba(129, 140, 248, 0.15);
      color: #818cf8;
    }

    .wafer-status.status-queued {
      background: rgba(245, 158, 11, 0.15);
      color: #f59e0b;
    }

    .wafer-status.status-processing {
      background: rgba(129, 140, 248, 0.15);
      color: #818cf8;
    }

    .wafer-status.status-completed {
      background: rgba(16, 185, 129, 0.15);
      color: #10b981;
    }

    .wafer-status.status-failed {
      background: rgba(239, 68, 68, 0.15);
      color: #ef4444;
    }

    .empty-state {
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: center;
      padding: 3rem 1rem;
      gap: 1rem;
    }

    .empty-message {
      font-size: 0.875rem;
      color: var(--text-muted);
      margin: 0;
    }

    @media (max-width: 768px) {
      .lot-info {
        flex-direction: column;
        align-items: flex-start;
        gap: 0.5rem;
      }

      .lot-stats {
        width: 100%;
      }
    }
  `]
})
export class LotWaferProgressComponent {
  @Input() set lotData(value: LotProgress[]) {
    this.lots.set(value || []);
  }

  lots = signal<LotProgress[]>([]);

  toggleLot(lot: LotProgress): void {
    const updated = this.lots().map((l: LotProgress) =>
      l.lot === lot.lot ? { ...l, expanded: !l.expanded } : l
    );
    this.lots.set(updated);
  }

  getStatusIcon(status: string): string {
    switch (status.toLowerCase()) {
      case 'completed': return 'check_circle';
      case 'failed': return 'error';
      case 'enrichment / translation': return 'refresh';
      case 'exensio loading': return 'cloud_upload';
      case 'processing': return 'refresh'; // legacy
      case 'queued':
      case 'in queue (pending cp)': return 'clock';
      case 'staged': return 'inbox';
      default: return 'help';
    }
  }

  getStatusColor(status: string): 'primary' | 'success' | 'warning' | 'error' | 'muted' | 'default' {
    switch (status.toLowerCase()) {
      case 'completed': return 'success';
      case 'failed': return 'error';
      case 'enrichment / translation':
      case 'exensio loading':
      case 'processing': return 'primary'; // legacy
      case 'queued':
      case 'in queue (pending cp)': return 'warning';
      case 'staged': return 'primary';
      default: return 'muted';
    }
  }

  getLotCountLabel(lot: LotProgress): string {
    const count = Number(lot?.totalWafers ?? 0);
    const hasRealWafer = (lot?.wafers || []).some((item: WaferProgress) => {
      const value = String(item?.wafer ?? '').trim();
      return value.length > 0 && value !== '-';
    });

    const noun = hasRealWafer
      ? (count === 1 ? 'wafer' : 'wafers')
      : (count === 1 ? 'file' : 'files');

    return `${count} ${noun}`;
  }
}
