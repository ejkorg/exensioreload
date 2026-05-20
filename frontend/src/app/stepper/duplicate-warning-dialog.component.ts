import { Component, Inject, signal, computed } from '@angular/core';
import { CommonModule } from '@angular/common';
import { GlassIconComponent } from '../shared/components/glass-icon.component';
import { GlassButtonComponent } from '../shared/components/glass-button.component';
import { GlassDialogRef, GLASS_DIALOG_DATA } from '../shared/services/glass-dialog.service';

export interface DuplicatePayloadInfo {
  metadataId: string;
  dataId: string;
  lot: string;
  wafer: string;
  filename: string;
  previousStatus?: string;
  originalEndTime?: string;
  duplicateEndTime?: string;
  requiresConfirmation: boolean;
  stagedBy?: string | null;
  stagedAt?: string | null;
  lastRequestedBy?: string | null;
  lastRequestedAt?: string | null;
}

export interface DuplicateWarningDialogData {
  duplicates: DuplicatePayloadInfo[];
  totalSelected: number;
}

export interface DuplicateWarningDialogResult {
  confirmed: boolean;
  selectedKeys?: Set<string>;
}

const PAGE_SIZE = 25;

@Component({
  selector: 'app-duplicate-warning-dialog',
  standalone: true,
  imports: [CommonModule, GlassIconComponent, GlassButtonComponent],
  template: `
    <div class="dialog-container glass-panel">
      <!-- Header -->
      <div class="dialog-header">
        <div class="header-icon warning-icon">
          <app-glass-icon name="warning" [size]="28" color="warning"></app-glass-icon>
        </div>
        <div class="header-text">
          <h2>Duplicate Payloads Detected</h2>
          <p class="header-sub">
            <strong>{{ data.duplicates.length }}</strong> of <strong>{{ data.totalSelected }}</strong> selected files were already staged or processed.
          </p>
        </div>
        <button class="close-btn" (click)="dismiss()" aria-label="Close dialog">
          <app-glass-icon name="close" [size]="20"></app-glass-icon>
        </button>
      </div>

      <!-- Toolbar -->
      <div class="list-toolbar">
        <div class="toolbar-left">
          <label class="select-all-label">
            <input type="checkbox"
              [checked]="allPageSelected()"
              [indeterminate]="somePageSelected()"
              (change)="togglePageSelection($event)"
              class="native-checkbox" />
            <span>Select page</span>
          </label>
          <span class="selection-count" *ngIf="selectedKeys().size > 0">
            {{ selectedKeys().size }} selected
          </span>
        </div>
        <div class="toolbar-right">
          <span class="page-info">{{ startIndex() + 1 }}–{{ endIndex() }} of {{ data.duplicates.length }}</span>
          <button class="page-btn" [disabled]="page() === 0" (click)="page.set(page() - 1)">
            <app-glass-icon name="chevron_left" [size]="18"></app-glass-icon>
          </button>
          <button class="page-btn" [disabled]="page() >= totalPages() - 1" (click)="page.set(page() + 1)">
            <app-glass-icon name="chevron_right" [size]="18"></app-glass-icon>
          </button>
        </div>
      </div>

      <!-- List -->
      <div class="duplicate-items">
        <div *ngFor="let dup of pagedDuplicates()"
             class="duplicate-item"
             [class.selected]="isSelected(dup)"
             (click)="toggleItem(dup)">
          <input type="checkbox"
            [checked]="isSelected(dup)"
            (click)="$event.stopPropagation()"
            (change)="toggleItem(dup)"
            class="native-checkbox item-check" />
          <div class="item-details">
            <div class="item-filename">{{ dup.filename || '(filename unavailable)' }}</div>
            <div class="item-meta">Lot: {{ dup.lot || '—' }} &nbsp;|&nbsp; Wafer: {{ dup.wafer || '—' }}</div>
            <div class="item-staged-by" *ngIf="dup.stagedBy || dup.lastRequestedBy">
              <app-glass-icon name="person" [size]="12"></app-glass-icon>
              <span>
                Staged by <strong>{{ dup.stagedBy || 'unknown' }}</strong>
                <ng-container *ngIf="dup.lastRequestedBy && dup.lastRequestedBy !== dup.stagedBy">
                  &nbsp;&bull; Last by <strong>{{ dup.lastRequestedBy }}</strong>
                </ng-container>
                <ng-container *ngIf="dup.stagedAt">
                  &nbsp;&bull; {{ formatDate(dup.stagedAt) }}
                </ng-container>
              </span>
            </div>
          </div>
        </div>
      </div>

      <!-- Actions -->
      <div class="dialog-actions">
        <app-glass-button variant="secondary" (clicked)="cancel()">
          Skip All Duplicates
        </app-glass-button>
        <app-glass-button
          variant="secondary"
          [disabled]="selectedKeys().size === 0"
          (clicked)="confirmSelected()">
          Include Selected ({{ selectedKeys().size }})
        </app-glass-button>
        <app-glass-button variant="primary" (clicked)="confirmAll()">
          <app-glass-icon name="check_circle" [size]="18"></app-glass-icon>
          Include All ({{ data.duplicates.length }})
        </app-glass-button>
      </div>
    </div>
  `,
  styles: [`
    .dialog-container {
      width: 640px;
      max-width: 92vw;
      max-height: 85vh;
      display: flex;
      flex-direction: column;
      background: var(--card-bg);
      border: 1px solid var(--card-border);
      border-radius: 20px;
      overflow: hidden;
    }

    .dialog-header {
      display: flex;
      align-items: flex-start;
      gap: 1rem;
      padding: 1.25rem 1.5rem;
      border-bottom: 1px solid rgba(255,255,255,0.05);
      background: rgba(255,255,255,0.02);
      flex-shrink: 0;
    }

    .header-icon {
      display: flex; align-items: center; justify-content: center;
      width: 44px; height: 44px; border-radius: 12px; flex-shrink: 0;
      background: rgba(245,158,11,0.15); border: 1px solid rgba(245,158,11,0.3);
    }

    .header-text {
      flex: 1;
      h2 { margin: 0 0 0.2rem; font-size: 1.15rem; font-weight: 700; }
    }

    .header-sub {
      margin: 0; font-size: 0.82rem; color: var(--text-muted);
      strong { color: var(--accent-color); }
    }

    .close-btn {
      display: flex; align-items: center; justify-content: center;
      width: 32px; height: 32px; border: none; flex-shrink: 0;
      background: rgba(255,255,255,0.05); border-radius: 8px;
      cursor: pointer; color: var(--text-muted);
      &:hover { background: rgba(255,255,255,0.1); color: var(--text-main); }
    }

    .list-toolbar {
      display: flex; align-items: center; justify-content: space-between;
      padding: 0.5rem 1rem;
      border-bottom: 1px solid rgba(255,255,255,0.05);
      background: rgba(255,255,255,0.02);
      flex-shrink: 0;
      gap: 1rem;
    }

    .toolbar-left { display: flex; align-items: center; gap: 0.75rem; }
    .toolbar-right { display: flex; align-items: center; gap: 0.35rem; }

    .select-all-label {
      display: flex; align-items: center; gap: 0.4rem;
      font-size: 0.78rem; font-weight: 600; color: var(--text-muted);
      cursor: pointer; user-select: none;
    }

    .selection-count {
      font-size: 0.75rem; font-weight: 700;
      color: var(--accent-color);
      padding: 0.15rem 0.5rem;
      background: rgba(129,140,248,0.12);
      border: 1px solid rgba(129,140,248,0.25);
      border-radius: 999px;
    }

    .page-info { font-size: 0.72rem; color: var(--text-muted); padding: 0 0.25rem; }

    .page-btn {
      display: flex; align-items: center; justify-content: center;
      width: 26px; height: 26px; border-radius: 6px;
      border: 1px solid rgba(255,255,255,0.1);
      background: rgba(255,255,255,0.04);
      color: var(--text-muted); cursor: pointer;
      &:hover:not(:disabled) { background: rgba(129,140,248,0.15); color: var(--accent-color); }
      &:disabled { opacity: 0.3; cursor: not-allowed; }
    }

    .native-checkbox {
      width: 15px; height: 15px; accent-color: var(--accent-color);
      cursor: pointer; flex-shrink: 0;
    }

    .duplicate-items {
      flex: 1; overflow-y: auto; padding: 0.5rem 0.75rem;
      display: flex; flex-direction: column; gap: 0.4rem;
    }

    .duplicate-item {
      display: flex; align-items: flex-start; gap: 0.75rem;
      padding: 0.6rem 0.75rem;
      background: rgba(255,255,255,0.02);
      border: 1px solid rgba(255,255,255,0.05);
      border-radius: 10px; cursor: pointer;
      transition: all 0.15s ease;

      &:hover { background: rgba(255,255,255,0.05); border-color: rgba(129,140,248,0.2); }
      &.selected {
        background: rgba(129,140,248,0.08);
        border-color: rgba(129,140,248,0.35);
      }
    }

    .item-check { margin-top: 2px; }

    .item-details { flex: 1; min-width: 0; }

    .item-filename {
      font-size: 0.8125rem; font-weight: 600;
      font-family: 'JetBrains Mono', monospace;
      color: var(--text-main);
      overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
    }

    .item-meta {
      font-size: 0.75rem; color: var(--text-muted); margin-top: 0.2rem;
    }

    .item-staged-by {
      display: flex; align-items: center; gap: 0.3rem;
      margin-top: 0.25rem; font-size: 0.72rem; color: var(--text-muted);
      strong { color: var(--accent-color); font-weight: 600; }
      app-glass-icon { opacity: 0.55; flex-shrink: 0; }
    }

    .dialog-actions {
      display: flex; justify-content: flex-end; gap: 0.5rem;
      padding: 1rem 1.25rem;
      border-top: 1px solid rgba(255,255,255,0.05);
      background: rgba(255,255,255,0.02);
      flex-shrink: 0; flex-wrap: wrap;
    }

    @media (max-width: 600px) {
      .dialog-container { width: 100%; max-width: 100vw; max-height: 100vh; border-radius: 0; }
      .dialog-actions { flex-direction: column-reverse; }
    }
  `]
})
export class DuplicateWarningDialogComponent {
  page = signal(0);
  selectedKeys = signal<Set<string>>(new Set());

  readonly totalPages = computed(() => Math.max(1, Math.ceil(this.data.duplicates.length / PAGE_SIZE)));
  readonly startIndex = computed(() => this.page() * PAGE_SIZE);
  readonly endIndex = computed(() => Math.min(this.startIndex() + PAGE_SIZE, this.data.duplicates.length));
  readonly pagedDuplicates = computed(() => this.data.duplicates.slice(this.startIndex(), this.endIndex()));

  readonly allPageSelected = computed(() =>
    this.pagedDuplicates().length > 0 &&
    this.pagedDuplicates().every(d => this.selectedKeys().has(this.key(d)))
  );

  readonly somePageSelected = computed(() =>
    !this.allPageSelected() &&
    this.pagedDuplicates().some(d => this.selectedKeys().has(this.key(d)))
  );

  constructor(
    @Inject(GLASS_DIALOG_DATA) public data: DuplicateWarningDialogData,
    public dialogRef: GlassDialogRef<DuplicateWarningDialogComponent, DuplicateWarningDialogResult | undefined>
  ) {}

  key(dup: DuplicatePayloadInfo): string {
    return `${dup.metadataId}::${dup.dataId}`;
  }

  isSelected(dup: DuplicatePayloadInfo): boolean {
    return this.selectedKeys().has(this.key(dup));
  }

  toggleItem(dup: DuplicatePayloadInfo): void {
    const k = this.key(dup);
    const next = new Set(this.selectedKeys());
    next.has(k) ? next.delete(k) : next.add(k);
    this.selectedKeys.set(next);
  }

  togglePageSelection(event: Event): void {
    const checked = (event.target as HTMLInputElement).checked;
    const next = new Set(this.selectedKeys());
    this.pagedDuplicates().forEach(d => checked ? next.add(this.key(d)) : next.delete(this.key(d)));
    this.selectedKeys.set(next);
  }

  formatDate(value: string | null | undefined): string {
    if (!value) return '';
    try {
      return new Date(value).toLocaleString([], { year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' });
    } catch { return value ?? ''; }
  }

  confirmSelected(): void {
    this.dialogRef.close({ confirmed: true, selectedKeys: new Set(this.selectedKeys()) });
  }

  confirmAll(): void {
    this.dialogRef.close({ confirmed: true, selectedKeys: undefined }); // undefined = all
  }

  cancel(): void {
    this.dialogRef.close({ confirmed: false });
  }

  dismiss(): void {
    this.dialogRef.close(undefined);
  }
}
