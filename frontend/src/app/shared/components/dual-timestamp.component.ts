import { Component, Input, computed, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { formatDualTimestamp, DualTimestamp } from '../utils/datetime.util';

/**
 * Displays a timestamp in dual-line format:
 *  - **Bold top line**: UTC time (YYYY-MM-DD HH:MM:SS)
 *  - Muted bottom line: Local time (YYYY-MM-DD HH:MM:SS TZ)
 *
 * Usage:
 *   <app-dual-timestamp [value]="file.updatedAt"></app-dual-timestamp>
 *   <app-dual-timestamp [value]="alert.triggered_at" layout="inline"></app-dual-timestamp>
 */
@Component({
  selector: 'app-dual-timestamp',
  standalone: true,
  imports: [CommonModule],
  template: `
    <span class="dual-ts" [class.inline]="layout === 'inline'" *ngIf="ts() as t; else empty" [title]="t.utc + ' (' + t.local + ')'">
      <span class="ts-utc">{{ t.utc }}</span>
      <span class="ts-local">{{ t.local }}</span>
    </span>
    <ng-template #empty><span class="dual-ts-empty">-</span></ng-template>
  `,
  styles: [`
    :host { display: inline-block; line-height: 1; }

    .dual-ts {
      display: flex;
      flex-direction: column;
      gap: 1px;
    }

    .dual-ts.inline {
      display: inline-flex;
      flex-direction: row;
      gap: 6px;
      align-items: baseline;
    }

    .ts-utc {
      font-weight: 700;
      font-size: 0.82rem;
      color: rgba(255, 255, 255, 0.95);
      white-space: nowrap;
    }

    .ts-local {
      font-weight: 400;
      font-size: 0.72rem;
      color: rgba(255, 255, 255, 0.45);
      white-space: nowrap;
    }

    .dual-ts-empty {
      color: rgba(255, 255, 255, 0.3);
      font-size: 0.82rem;
    }

    /* Light theme support */
    :host-context(body.light-theme) .ts-utc {
      font-weight: 700;
      color: rgba(0, 0, 0, 0.9);
    }

    :host-context(body.light-theme) .ts-local {
      font-weight: 400;
      color: rgba(0, 0, 0, 0.5);
    }

    :host-context(body.light-theme) .dual-ts-empty {
      color: rgba(0, 0, 0, 0.3);
    }
  `],
})
export class DualTimestampComponent {
  private _value = signal<unknown>(null);

  @Input()
  set value(v: unknown) { this._value.set(v); }

  @Input() layout: 'stacked' | 'inline' = 'stacked';

  ts = computed<DualTimestamp | null>(() => formatDualTimestamp(this._value()));
}
