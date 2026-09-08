import { CommonModule } from '@angular/common';
import { Component, ElementRef, Input, OnDestroy, signal } from '@angular/core';
import { ExportService, DashboardExportData, ExportFormat } from '../services/export.service';

export interface ExportOption {
  format: ExportFormat;
  label: string;
  icon: string;
  description: string;
}

export const EXPORT_OPTIONS: ExportOption[] = [
  { format: 'csv', label: 'CSV', icon: 'table_chart', description: 'Flat file with all visible metrics' },
  { format: 'excel', label: 'Excel', icon: 'grid_on', description: 'Workbook (Summary/Senders/Sites/History)' },
  { format: 'json', label: 'JSON', icon: 'data_object', description: 'Raw dashboard state' },
  { format: 'pdf', label: 'PDF', icon: 'picture_as_pdf', description: 'Print-optimized report' },
];

/**
 * ExportMenuComponent renders a dropdown with the four export formats and
 * delegates generation to ExportService (Requirement 8.1).
 */
@Component({
  selector: 'app-export-menu',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="export-menu" #container>
      <button
        type="button"
        class="export-trigger"
        (click)="toggle()"
        [disabled]="busy()"
        [attr.aria-expanded]="open()"
        aria-haspopup="menu"
      >
        @if (busy()) {
          <span class="spinner" aria-hidden="true"></span>
        } @else {
          <span class="trigger-glyph" aria-hidden="true">⇩</span>
        }
        <span>{{ busy() ? 'Exporting…' : 'Export' }}</span>
      </button>

      @if (open() && !busy()) {
        <div class="export-dropdown" role="menu" (click)="$event.stopPropagation()">
          <div class="dropdown-head">Export dashboard</div>
          @for (opt of options; track opt.format) {
            <button type="button" class="export-item" role="menuitem" (click)="run(opt.format)">
              <span class="export-icon" aria-hidden="true">{{ glyph(opt.icon) }}</span>
              <span class="export-text">
                <span class="export-label">{{ opt.label }}</span>
                <span class="export-desc">{{ opt.description }}</span>
              </span>
            </button>
          }
        </div>
      }
    </div>
  `,
  styles: [
    `
      .export-menu {
        position: relative;
        display: inline-block;
      }
      .export-trigger {
        display: inline-flex;
        align-items: center;
        gap: 0.4rem;
        height: 36px;
        padding: 0 0.85rem;
        border-radius: 10px;
        border: 1px solid rgba(167, 139, 250, 0.22);
        background: rgba(67, 56, 132, 0.35);
        color: rgba(226, 232, 255, 0.92);
        font-size: 0.8rem;
        font-weight: 600;
        cursor: pointer;
        transition: all 0.18s ease;
      }
      .export-trigger:hover:not(:disabled) {
        background: rgba(99, 102, 241, 0.28);
        border-color: rgba(167, 139, 250, 0.5);
      }
      .export-trigger:disabled {
        opacity: 0.6;
        cursor: not-allowed;
      }
      .trigger-glyph {
        font-size: 0.95rem;
        line-height: 1;
      }
      .spinner {
        width: 13px;
        height: 13px;
        border-radius: 50%;
        border: 2px solid rgba(167, 139, 250, 0.3);
        border-top-color: #a5b4fc;
        animation: spin 0.8s linear infinite;
      }
      @keyframes spin {
        to {
          transform: rotate(360deg);
        }
      }
      .export-dropdown {
        position: absolute;
        right: 0;
        top: calc(100% + 6px);
        min-width: 260px;
        padding: 0.35rem;
        border-radius: 12px;
        border: 1px solid rgba(167, 139, 250, 0.2);
        background: rgba(24, 17, 52, 0.97);
        backdrop-filter: blur(12px);
        box-shadow: 0 12px 32px rgba(2, 8, 23, 0.55);
        z-index: 60;
      }
      .dropdown-head {
        padding: 0.4rem 0.6rem 0.5rem;
        font-size: 0.7rem;
        font-weight: 700;
        text-transform: uppercase;
        letter-spacing: 0.06em;
        color: rgba(203, 213, 225, 0.55);
      }
      .export-item {
        display: flex;
        align-items: center;
        gap: 0.6rem;
        width: 100%;
        padding: 0.5rem 0.6rem;
        border: none;
        border-radius: 8px;
        background: transparent;
        color: rgba(226, 232, 255, 0.9);
        cursor: pointer;
        text-align: left;
        transition: background 0.15s ease;
      }
      .export-item:hover {
        background: rgba(99, 102, 241, 0.18);
      }
      .export-icon {
        width: 26px;
        height: 26px;
        display: inline-flex;
        align-items: center;
        justify-content: center;
        border-radius: 7px;
        background: rgba(129, 140, 248, 0.15);
        color: #a5b4fc;
        font-size: 0.8rem;
        flex-shrink: 0;
      }
      .export-text {
        display: flex;
        flex-direction: column;
        gap: 0.1rem;
      }
      .export-label {
        font-size: 0.8rem;
        font-weight: 700;
      }
      .export-desc {
        font-size: 0.68rem;
        color: rgba(203, 213, 225, 0.55);
      }
    `,
  ],
})
export class ExportMenuComponent implements OnDestroy {
  /** Returns the data snapshot to export at click time. */
  @Input() dataProvider?: () => DashboardExportData;

  readonly options = EXPORT_OPTIONS;
  readonly open = signal(false);
  readonly busy = signal(false);

  private docClickListener?: (e: Event) => void;

  constructor(
    private readonly exportService: ExportService,
    private readonly elementRef: ElementRef,
  ) {}

  ngOnDestroy(): void {
    this.removeDocListener();
  }

  toggle(): void {
    this.open.update((o) => !o);
    if (this.open()) {
      this.installDocListener();
    } else {
      this.removeDocListener();
    }
  }

  async run(format: ExportFormat): Promise<void> {
    if (!this.dataProvider) return;
    this.busy.set(true);
    this.open.set(false);
    try {
      const data = this.dataProvider();
      await this.exportService.export(data, format);
    } finally {
      this.busy.set(false);
    }
  }

  private installDocListener(): void {
    this.removeDocListener();
    this.docClickListener = (e: Event) => {
      if (!this.elementRef.nativeElement.contains(e.target as Node)) {
        this.open.set(false);
        this.removeDocListener();
      }
    };
    document.addEventListener('click', this.docClickListener);
  }

  private removeDocListener(): void {
    if (this.docClickListener) {
      document.removeEventListener('click', this.docClickListener);
      this.docClickListener = undefined;
    }
  }

  /** Template helper: map a material-ish icon key to a safe glyph. */
  glyph(icon: string): string {
    switch (icon) {
      case 'table_chart':
        return '▦';
      case 'grid_on':
        return '⊞';
      case 'data_object':
        return '{}';
      case 'picture_as_pdf':
        return '▤';
      default:
        return '⇩';
    }
  }
}
