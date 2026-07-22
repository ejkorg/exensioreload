import { CommonModule } from '@angular/common';
import { Component, Inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { GlassButtonComponent } from '../shared/components/glass-button.component';
import { GlassCheckboxComponent } from '../shared/components/glass-checkbox.component';
import { GlassIconComponent } from '../shared/components/glass-icon.component';
import { GLASS_DIALOG_DATA, GlassDialogRef } from '../shared/services/glass-dialog.service';

export interface CheckExensioDialogData {
  lotsCount: number;
  wafersCount: number;
  dataType: string;
  enableSnowflakeFallback?: boolean;
}

export interface CheckExensioDialogResult {
  confirmed: boolean;
  enableSnowflakeFallback: boolean;
}

@Component({
  selector: 'app-check-exensio-dialog',
  standalone: true,
  imports: [CommonModule, FormsModule, GlassIconComponent, GlassButtonComponent, GlassCheckboxComponent],
  template: `
    <div class="check-exensio-dialog" role="dialog" aria-labelledby="dialog-title">
      <div class="dialog-header">
        <div class="header-icon">
          <app-glass-icon name="fact_check" [size]="24"></app-glass-icon>
        </div>
        <div class="header-text">
          <h2 id="dialog-title">Check Exensio Status</h2>
          <p class="header-sub">Verify payloads against Exensio before staging</p>
        </div>
        <button class="close-btn" (click)="cancel()" aria-label="Close dialog">
          <app-glass-icon name="close" [size]="18"></app-glass-icon>
        </button>
      </div>

      <div class="dialog-body">
        <div class="target-summary-grid">
          <div class="summary-card">
            <span class="summary-val">{{ data.lotsCount }}</span>
            <span class="summary-lbl">Discovered Lot(s)</span>
          </div>
          <div class="summary-card">
            <span class="summary-val">{{ data.wafersCount }}</span>
            <span class="summary-lbl">Wafer Record(s)</span>
          </div>
          <div class="summary-card">
            <span class="summary-val text-accent">{{ data.dataType || 'N/A' }}</span>
            <span class="summary-lbl">Data Type</span>
          </div>
        </div>

        <div class="info-note">
          <app-glass-icon name="info" [size]="16" color="primary"></app-glass-icon>
          <span>
            Exensio status will be checked in parallel across <strong>PRODUCTION</strong> and <strong>SANDBOX</strong> schemas using raw-SQL wafer lookups.
          </span>
        </div>

        <div class="option-box">
          <app-glass-checkbox
            label="Also search Snowflake as fallback if Exensio returns nothing"
            [(ngModel)]="enableSnowflakeFallback"
          >
          </app-glass-checkbox>
          <p class="option-hint">
            Queries <code>EXENSIO_PROD_OPLOG_METADATA</code> in Snowflake if Exensio HTTP raw-SQL yields no matching records.
          </p>
        </div>
      </div>

      <div class="dialog-actions">
        <app-glass-button variant="secondary" (clicked)="cancel()">Cancel</app-glass-button>
        <app-glass-button variant="primary" (clicked)="confirm()">
          <app-glass-icon name="fact_check" [size]="16"></app-glass-icon>
          <span>Run Check Exensio</span>
        </app-glass-button>
      </div>
    </div>
  `,
  styles: [`
    .check-exensio-dialog {
      width: 520px;
      max-width: 95vw;
      display: flex;
      flex-direction: column;
      background: var(--card-bg, rgba(15, 23, 42, 0.95));
      border: 1px solid var(--card-border, rgba(255, 255, 255, 0.1));
      border-radius: 20px;
      overflow: hidden;
      box-shadow: 0 20px 50px rgba(0, 0, 0, 0.5);
    }

    .dialog-header {
      display: flex;
      align-items: center;
      gap: 0.875rem;
      padding: 1.25rem 1.5rem;
      border-bottom: 1px solid rgba(255, 255, 255, 0.06);
      background: rgba(255, 255, 255, 0.02);
    }

    .header-icon {
      display: flex;
      align-items: center;
      justify-content: center;
      width: 42px;
      height: 42px;
      border-radius: 12px;
      background: rgba(129, 140, 248, 0.15);
      border: 1px solid rgba(129, 140, 248, 0.3);
      color: #818cf8;
      flex-shrink: 0;
    }

    .header-text {
      flex: 1;

      h2 {
        margin: 0 0 0.2rem;
        font-size: 1.1rem;
        font-weight: 700;
        color: var(--text-main, #fff);
      }

      .header-sub {
        margin: 0;
        font-size: 0.8rem;
        color: var(--text-muted, #94a3b8);
      }
    }

    .close-btn {
      display: flex;
      align-items: center;
      justify-content: center;
      width: 32px;
      height: 32px;
      border: none;
      background: rgba(255, 255, 255, 0.05);
      border-radius: 8px;
      color: var(--text-muted, #94a3b8);
      cursor: pointer;
      transition: all 0.2s ease;

      &:hover {
        background: rgba(255, 255, 255, 0.1);
        color: var(--text-main, #fff);
      }
    }

    .dialog-body {
      padding: 1.25rem 1.5rem;
      display: flex;
      flex-direction: column;
      gap: 1rem;
    }

    .target-summary-grid {
      display: grid;
      grid-template-columns: repeat(3, 1fr);
      gap: 0.75rem;
    }

    .summary-card {
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: center;
      padding: 0.75rem 0.5rem;
      background: rgba(255, 255, 255, 0.03);
      border: 1px solid rgba(255, 255, 255, 0.08);
      border-radius: 12px;
      text-align: center;
    }

    .summary-val {
      font-size: 1.25rem;
      font-weight: 700;
      color: var(--text-main, #fff);
      line-height: 1.2;

      &.text-accent {
        color: #818cf8;
      }
    }

    .summary-lbl {
      font-size: 0.7rem;
      font-weight: 600;
      color: var(--text-muted, #94a3b8);
      text-transform: uppercase;
      letter-spacing: 0.03em;
      margin-top: 0.25rem;
    }

    .info-note {
      display: flex;
      align-items: flex-start;
      gap: 0.6rem;
      padding: 0.75rem 1rem;
      background: rgba(129, 140, 248, 0.08);
      border: 1px solid rgba(129, 140, 248, 0.2);
      border-radius: 10px;
      font-size: 0.8rem;
      color: var(--text-main, #e2e8f0);
      line-height: 1.4;

      app-glass-icon {
        flex-shrink: 0;
        margin-top: 0.1rem;
      }
    }

    .option-box {
      padding: 0.875rem 1rem;
      background: rgba(255, 255, 255, 0.02);
      border: 1px solid rgba(255, 255, 255, 0.06);
      border-radius: 12px;
    }

    .option-hint {
      margin: 0.35rem 0 0 1.75rem;
      font-size: 0.75rem;
      color: var(--text-muted, #94a3b8);
      line-height: 1.35;

      code {
        font-family: 'JetBrains Mono', monospace;
        font-size: 0.7rem;
        background: rgba(255, 255, 255, 0.06);
        padding: 0.1rem 0.3rem;
        border-radius: 4px;
      }
    }

    .dialog-actions {
      display: flex;
      justify-content: flex-end;
      align-items: center;
      gap: 0.75rem;
      padding: 1rem 1.5rem;
      border-top: 1px solid rgba(255, 255, 255, 0.06);
      background: rgba(255, 255, 255, 0.02);
    }
  `]
})
export class CheckExensioDialogComponent {
  enableSnowflakeFallback = false;

  constructor(
    @Inject(GLASS_DIALOG_DATA) public data: CheckExensioDialogData,
    public dialogRef: GlassDialogRef<CheckExensioDialogComponent, CheckExensioDialogResult>,
  ) {
    this.enableSnowflakeFallback = data.enableSnowflakeFallback ?? false;
  }

  cancel(): void {
    this.dialogRef.close({ confirmed: false, enableSnowflakeFallback: false });
  }

  confirm(): void {
    this.dialogRef.close({
      confirmed: true,
      enableSnowflakeFallback: this.enableSnowflakeFallback,
    });
  }
}
