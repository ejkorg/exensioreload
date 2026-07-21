import { CommonModule } from '@angular/common';
import { Component, Inject } from '@angular/core';
import { GlassButtonComponent } from '../shared/components/glass-button.component';
import { GlassIconComponent } from '../shared/components/glass-icon.component';
import { GLASS_DIALOG_DATA, GlassDialogRef } from '../shared/services/glass-dialog.service';

/**
 * Data passed to the LotVerificationDialogComponent dialog.
 * Contains the verification results and metadata needed to display the dialog.
 *
 * Task 11: Added appliedDateRange to display date range in results dialog.
 * Wafer-level: Added wafer information for wafer-level classes (1, 4, 14).
 */
export interface LotVerificationDialogData {
  lots: string[];
  verificationResult: Map<string, { found: boolean; schema: string | null; wafers?: string[] }>;
  verifiedAt: Date;
  appliedDateRange?: { start: Date; end: Date } | null;
}

/**
 * Result returned when the dialog is closed.
 * Indicates which action the user chose and any filtered lot list.
 */
export interface LotVerificationDialogResult {
  action: 'all' | 'not-found' | 'cancel';
  filteredLots?: string[];
}

@Component({
  selector: 'app-lot-verification-dialog',
  standalone: true,
  imports: [CommonModule, GlassIconComponent, GlassButtonComponent],
  template: `
    <div class="verification-dialog" role="dialog" aria-labelledby="verification-dialog-title" aria-modal="true">
      <!-- Header -->
      <div class="dialog-header">
        <div class="header-icon">
          <app-glass-icon name="verified" [size]="26"></app-glass-icon>
        </div>
        <div class="header-text">
          <h2 id="verification-dialog-title">Lot Verification Results</h2>
          <p class="header-sub">Checked against Exensio</p>
        </div>
        <button class="close-btn" (click)="close()" aria-label="Close dialog">
          <app-glass-icon name="close" [size]="18"></app-glass-icon>
        </button>
        <button
          class="export-btn"
          (click)="exportToCsv()"
          title="Export results to CSV"
          aria-label="Export verification results to CSV"
        >
          <app-glass-icon name="download" [size]="16"></app-glass-icon>
          <span>Export CSV</span>
        </button>
      </div>

      <!-- Summary stats -->
      <div class="verification-summary">
        <div class="stat-card">
          <span class="stat-count">{{ totalLots }}</span>
          <span class="stat-label">Total Lots</span>
        </div>
        <div class="stat-card success">
          <span class="stat-count">{{ foundCount }}</span>
          <span class="stat-label">Found in Exensio</span>
          <app-glass-icon name="check_circle" [size]="18" color="success" class="stat-icon"></app-glass-icon>
        </div>
        <div class="stat-card error">
          <span class="stat-count">{{ notFoundCount }}</span>
          <span class="stat-label">Not Found</span>
          <app-glass-icon name="error" [size]="18" color="error" class="stat-icon"></app-glass-icon>
        </div>
      </div>

      <!-- Warning banner if all lots exist -->
      <div *ngIf="notFoundCount === 0" class="warning-banner">
        <app-glass-icon name="warning" [size]="16" color="warning"></app-glass-icon>
        <span>All lots already exist in Exensio. Discovery may return files that have already been loaded.</span>
      </div>

      <!-- Task 11: Date range info banner if applied -->
      <div *ngIf="dateRangeText" class="info-banner">
        <app-glass-icon name="calendar" [size]="16" color="primary"></app-glass-icon>
        <span>Date range filters applied: {{ dateRangeText }}</span>
      </div>

      <!-- Lot lists in two columns -->
      <div class="lot-lists">
        <div class="lot-section">
          <h3 class="section-title">
            <app-glass-icon name="check_circle" [size]="16" color="success"></app-glass-icon>
            Found in Exensio ({{ foundCount }})
          </h3>
          <div class="lot-scroll">
            <div *ngFor="let lot of foundLots" class="lot-item">
              <div class="lot-name">{{ lot }}</div>
              <div class="lot-schema" *ngIf="getSchemaForLot(lot)">
                <app-glass-icon name="database" [size]="12"></app-glass-icon>
                {{ getSchemaForLot(lot) }}
              </div>
              <div class="wafer-info" *ngIf="getWafersForLot(lot).length > 0">
                <app-glass-icon name="memory" [size]="12"></app-glass-icon>
                <span class="wafer-count">{{ getWafersForLot(lot).length }} wafer(s):</span>
                <span class="wafer-list">{{ getWafersForLot(lot).join(', ') }}</span>
              </div>
            </div>
            <div *ngIf="foundLots.length === 0" class="empty-state">No lots found</div>
          </div>
        </div>
        <div class="lot-section">
          <h3 class="section-title">
            <app-glass-icon name="error" [size]="16" color="error"></app-glass-icon>
            Not Found ({{ notFoundCount }})
          </h3>
          <div class="lot-scroll">
            <div *ngFor="let lot of notFoundLots" class="lot-item">{{ lot }}</div>
            <div *ngIf="notFoundLots.length === 0" class="empty-state">No lots to discover</div>
          </div>
        </div>
      </div>

      <!-- Action buttons -->
      <div class="dialog-actions" role="group" aria-label="Dialog actions">
        <app-glass-button variant="secondary" (clicked)="cancel()">Cancel</app-glass-button>
        <app-glass-button variant="primary" (clicked)="continueWithAll()">
          <app-glass-icon name="check" [size]="16"></app-glass-icon>
          Continue with All
        </app-glass-button>
        <app-glass-button
          variant="primary"
          [disabled]="notFoundCount === 0"
          [class.recommended]="notFoundCount > 0"
          (clicked)="continueWithNotFound()"
          [title]="notFoundCount === 0 ? 'No lots to discover' : ''"
        >
          <app-glass-icon name="check_circle" [size]="16"></app-glass-icon>
          Continue with Lots Not in Exensio
        </app-glass-button>
      </div>
    </div>
  `,
  styles: [
    `
      .verification-dialog {
        width: 900px;
        max-width: 95vw;
        display: flex;
        flex-direction: column;
        background: var(--card-bg);
        border: 1px solid var(--card-border);
        border-radius: 20px;
        overflow: hidden;
        max-height: 85vh;
      }

      /* Header */
      .dialog-header {
        display: flex;
        align-items: flex-start;
        gap: 0.875rem;
        padding: 1.25rem 1.5rem;
        border-bottom: 1px solid rgba(255, 255, 255, 0.05);
        background: rgba(255, 255, 255, 0.02);
        flex-shrink: 0;
      }

      .header-icon {
        display: flex;
        align-items: center;
        justify-content: center;
        width: 42px;
        height: 42px;
        border-radius: 12px;
        flex-shrink: 0;
        background: rgba(16, 185, 129, 0.12);
        border: 1px solid rgba(16, 185, 129, 0.25);
        color: #10b981;
      }

      .header-text {
        flex: 1;

        h2 {
          margin: 0 0 0.2rem;
          font-size: 1.1rem;
          font-weight: 700;
          color: var(--text-main);
        }
      }

      .header-sub {
        margin: 0;
        font-size: 0.8rem;
        color: var(--text-muted);
      }

      .close-btn,
      .export-btn {
        display: flex;
        align-items: center;
        justify-content: center;
        gap: 0.4rem;
        padding: 0.5rem 0.75rem;
        border: none;
        flex-shrink: 0;
        background: rgba(255, 255, 255, 0.05);
        border-radius: 8px;
        cursor: pointer;
        color: var(--text-muted);
        font-size: 0.8rem;
        font-weight: 500;
        transition: all 0.15s ease;

        &:hover {
          background: rgba(255, 255, 255, 0.1);
          color: var(--text-main);
        }
      }

      .close-btn {
        padding: 0.4rem;
        width: 30px;
        height: 30px;
      }

      /* Summary stats */
      .verification-summary {
        display: grid;
        grid-template-columns: repeat(3, 1fr);
        gap: 0.875rem;
        padding: 1.25rem 1.5rem;
        background: rgba(255, 255, 255, 0.02);
        border-bottom: 1px solid rgba(255, 255, 255, 0.05);
      }

      .stat-card {
        display: flex;
        flex-direction: column;
        align-items: center;
        justify-content: center;
        gap: 0.5rem;
        padding: 1rem;
        background: rgba(255, 255, 255, 0.04);
        border: 1px solid rgba(255, 255, 255, 0.1);
        border-radius: 12px;
        position: relative;

        &.success {
          background: rgba(16, 185, 129, 0.08);
          border-color: rgba(16, 185, 129, 0.25);
        }

        &.error {
          background: rgba(239, 68, 68, 0.08);
          border-color: rgba(239, 68, 68, 0.25);
        }
      }

      .stat-icon {
        position: absolute;
        top: 0.5rem;
        right: 0.5rem;
        opacity: 0.5;
      }

      .stat-count {
        font-size: 1.875rem;
        font-weight: 700;
        color: var(--text-main);
      }

      .stat-label {
        font-size: 0.75rem;
        font-weight: 600;
        color: var(--text-muted);
        text-align: center;
      }

      /* Warning banner */
      .warning-banner {
        display: flex;
        align-items: center;
        gap: 0.75rem;
        margin: 0.875rem 1.5rem 0.875rem;
        padding: 0.875rem 1rem;
        background: rgba(245, 158, 11, 0.08);
        border: 1px solid rgba(245, 158, 11, 0.25);
        border-radius: 10px;
        font-size: 0.8125rem;
        color: var(--text-main);
        line-height: 1.4;

        app-glass-icon {
          flex-shrink: 0;
        }
      }

      /* Task 11: Info banner for date range display */
      .info-banner {
        display: flex;
        align-items: center;
        gap: 0.75rem;
        margin: 0 1.5rem 0.875rem;
        padding: 0.875rem 1rem;
        background: rgba(59, 130, 246, 0.08);
        border: 1px solid rgba(59, 130, 246, 0.25);
        border-radius: 10px;
        font-size: 0.8125rem;
        color: var(--text-main);
        line-height: 1.4;

        app-glass-icon {
          flex-shrink: 0;
        }
      }

      /* Lot lists */
      .lot-lists {
        display: grid;
        grid-template-columns: 1fr 1fr;
        gap: 1rem;
        padding: 1rem 1.5rem;
        flex: 1;
        min-height: 0;
        overflow: hidden;
      }

      .lot-section {
        display: flex;
        flex-direction: column;
        gap: 0.5rem;
        min-height: 0;

        .section-title {
          display: flex;
          align-items: center;
          gap: 0.4rem;
          margin: 0;
          font-size: 0.85rem;
          font-weight: 600;
          color: var(--text-main);

          app-glass-icon {
            flex-shrink: 0;
          }
        }
      }

      .lot-scroll {
        flex: 1;
        overflow-y: auto;
        display: flex;
        flex-direction: column;
        gap: 0.3rem;
        padding: 0.5rem;
        background: rgba(255, 255, 255, 0.02);
        border: 1px solid rgba(255, 255, 255, 0.08);
        border-radius: 10px;
        min-height: 120px;

        /* Custom scrollbar styling */
        &::-webkit-scrollbar {
          width: 6px;
        }

        &::-webkit-scrollbar-track {
          background: rgba(255, 255, 255, 0.05);
          border-radius: 10px;
        }

        &::-webkit-scrollbar-thumb {
          background: rgba(255, 255, 255, 0.15);
          border-radius: 3px;

          &:hover {
            background: rgba(255, 255, 255, 0.25);
          }
        }
      }

      .lot-item {
        padding: 0.5rem 0.75rem;
        background: rgba(255, 255, 255, 0.04);
        border: 1px solid rgba(255, 255, 255, 0.08);
        border-radius: 8px;
        font-size: 0.8125rem;
        font-family: 'JetBrains Mono', 'Consolas', monospace;
        color: var(--text-main);
        word-break: break-all;
        transition: all 0.15s ease;
        display: flex;
        flex-direction: column;
        gap: 0.3rem;

        &:hover {
          background: rgba(255, 255, 255, 0.08);
          border-color: rgba(255, 255, 255, 0.12);
        }
      }

      .lot-name {
        font-weight: 500;
      }

      .lot-schema {
        display: flex;
        align-items: center;
        gap: 0.3rem;
        font-size: 0.7rem;
        font-family: 'JetBrains Mono', 'Consolas', monospace;
        color: var(--text-muted);
        padding: 0.2rem 0.5rem;
        background: rgba(255, 255, 255, 0.06);
        border-radius: 4px;
        width: fit-content;

        app-glass-icon {
          flex-shrink: 0;
        }

        &.production {
          background: rgba(16, 185, 129, 0.15);
          color: #10b981;
        }

        &.sandbox {
          background: rgba(59, 130, 246, 0.15);
          color: #3b82f6;
        }

        &.http {
          background: rgba(245, 158, 11, 0.15);
          color: #f59e0b;
        }
      }

      .wafer-info {
        display: flex;
        align-items: center;
        gap: 0.4rem;
        font-size: 0.7rem;
        color: var(--text-muted);
        padding: 0.3rem 0.5rem;
        background: rgba(99, 102, 241, 0.08);
        border: 1px solid rgba(99, 102, 241, 0.2);
        border-radius: 4px;
        margin-top: 0.2rem;

        app-glass-icon {
          flex-shrink: 0;
        }

        .wafer-count {
          font-weight: 600;
          color: #818cf8;
        }

        .wafer-list {
          font-family: 'JetBrains Mono', 'Consolas', monospace;
          font-size: 0.65rem;
          max-width: 300px;
          overflow: hidden;
          text-overflow: ellipsis;
          white-space: nowrap;
        }
      }

      .empty-state {
        display: flex;
        align-items: center;
        justify-content: center;
        height: 100px;
        color: var(--text-muted);
        font-size: 0.8rem;
      }

      /* Actions */
      .dialog-actions {
        display: flex;
        justify-content: flex-end;
        align-items: center;
        gap: 0.5rem;
        padding: 0.875rem 1.25rem;
        border-top: 1px solid rgba(255, 255, 255, 0.05);
        background: rgba(255, 255, 255, 0.02);
        flex-shrink: 0;
        flex-wrap: wrap;
      }

      /* Recommended button styling */
      ::ng-deep .dialog-actions app-glass-button.recommended .glass-btn-primary {
        background: linear-gradient(135deg, var(--accent-color), rgba(129, 140, 248, 0.8));
        box-shadow: 0 6px 20px rgba(129, 140, 248, 0.4);

        &:hover:not(:disabled) {
          box-shadow: 0 8px 25px rgba(129, 140, 248, 0.5);
        }
      }

      @media (max-width: 768px) {
        .verification-dialog {
          width: 100%;
          max-width: 100vw;
          border-radius: 16px 16px 0 0;
        }

        .verification-summary {
          grid-template-columns: 1fr;
          gap: 0.75rem;
        }

        .lot-lists {
          grid-template-columns: 1fr;
          padding: 0.875rem 1rem;
        }

        .dialog-actions {
          flex-direction: column-reverse;
        }

        .export-btn {
          width: 100%;
        }
      }
    `,
  ],
})
export class LotVerificationDialogComponent {
  foundLots: string[] = [];
  notFoundLots: string[] = [];
  totalLots = 0;
  foundCount = 0;
  notFoundCount = 0;
  verifiedAt: Date;
  appliedDateRange: { start: Date; end: Date } | null = null;
  dateRangeText: string = '';

  constructor(
    @Inject(GLASS_DIALOG_DATA) public data: LotVerificationDialogData,
    public dialogRef: GlassDialogRef<LotVerificationDialogComponent, LotVerificationDialogResult>,
  ) {
    this.verifiedAt = this.data.verifiedAt;
    this.appliedDateRange = this.data.appliedDateRange || null;
    this.computeDateRangeText();
    this.processVerificationResults();
  }

  /**
   * Task 11: Compute formatted date range text for display
   */
  private computeDateRangeText(): void {
    if (!this.appliedDateRange) {
      this.dateRangeText = '';
      return;
    }

    const startStr = this.appliedDateRange.start.toLocaleDateString('en-US', {
      month: '2-digit',
      day: '2-digit',
      year: 'numeric',
    });
    const endStr = this.appliedDateRange.end.toLocaleDateString('en-US', {
      month: '2-digit',
      day: '2-digit',
      year: 'numeric',
    });

    this.dateRangeText = `${startStr} - ${endStr}`;
  }

  /**
   * Task 4.2: Process verification results
   * Separates lots into foundLots and notFoundLots arrays
   * Calculates totalLots, foundCount, notFoundCount
   */
  private processVerificationResults(): void {
    this.data.lots.forEach((lot) => {
      const result = this.data.verificationResult.get(lot);
      if (result?.found) {
        this.foundLots.push(lot);
      } else {
        this.notFoundLots.push(lot);
      }
    });
    this.totalLots = this.data.lots.length;
    this.foundCount = this.foundLots.length;
    this.notFoundCount = this.notFoundLots.length;
  }

  /**
   * Get schema name for a found lot for display
   * Maps internal schema names to user-friendly labels
   */
  getSchemaForLot(lot: string): string {
    const result = this.data.verificationResult.get(lot);
    if (!result?.found || !result.schema) {
      return '';
    }

    // Map schema names to display labels
    switch (result.schema.toUpperCase()) {
      case 'PRODUCTION':
        return '📊 Production';
      case 'SANDBOX':
        return '🧪 Sandbox';
      case 'FOUND':
        return '✓ HTTP';
      default:
        return result.schema;
    }
  }

  /**
   * Get wafer IDs for a found lot (wafer-level classes only)
   * Returns empty array if no wafers or lot not found
   */
  getWafersForLot(lot: string): string[] {
    const result = this.data.verificationResult.get(lot);
    if (!result?.found || !result.wafers) {
      return [];
    }
    return result.wafers;
  }

  /**
   * Get CSS class for schema badge styling
   */
  getSchemaClass(lot: string): string {
    const result = this.data.verificationResult.get(lot);
    if (!result?.schema) return '';

    switch (result.schema.toUpperCase()) {
      case 'PRODUCTION':
        return 'production';
      case 'SANDBOX':
        return 'sandbox';
      case 'FOUND':
        return 'http';
      default:
        return '';
    }
  }

  /**
   * Task 4.3: Continue with all lots action
   * Closes dialog with action 'all'
   */
  continueWithAll(): void {
    this.dialogRef.close({ action: 'all' });
  }

  /**
   * Task 4.3: Continue with not found lots action
   * Closes dialog with action 'not-found' and filteredLots array
   */
  continueWithNotFound(): void {
    this.dialogRef.close({
      action: 'not-found',
      filteredLots: this.notFoundLots,
    });
  }

  /**
   * Task 4.3: Cancel action
   * Closes dialog with action 'cancel'
   */
  cancel(): void {
    this.dialogRef.close({ action: 'cancel' });
  }

  /**
   * Task 4.3: Close action (same as cancel)
   * Closes dialog with action 'cancel'
   */
  close(): void {
    this.dialogRef.close({ action: 'cancel' });
  }

  /**
   * Task 4.4: Export verification results to CSV
   * Generates CSV with headers: "Lot ID", "Status", "Schema", "Wafers", "Verified At"
   * Format filename as: `lot-verification-YYYYMMDD-HHMMSS.csv`
   * Triggers browser download using Blob and temporary link
   */
  exportToCsv(): void {
    // Generate timestamp in YYYYMMDD-HHMMSS format
    const now = new Date();
    const year = now.getUTCFullYear();
    const month = String(now.getUTCMonth() + 1).padStart(2, '0');
    const day = String(now.getUTCDate()).padStart(2, '0');
    const hours = String(now.getUTCHours()).padStart(2, '0');
    const minutes = String(now.getUTCMinutes()).padStart(2, '0');
    const seconds = String(now.getUTCSeconds()).padStart(2, '0');

    const timestamp = `${year}${month}${day}-${hours}${minutes}${seconds}`;
    const filename = `lot-verification-${timestamp}.csv`;

    // Build CSV content with headers: "Lot ID", "Status", "Schema", "Wafers", "Verified At"
    let csv = 'Lot ID,Status,Schema,Wafers,Verified At\n';

    // Add rows for found lots
    this.foundLots.forEach((lot) => {
      const escapedLot = this.escapeCsvField(lot);
      const status = 'Found in Exensio';
      const result = this.data.verificationResult.get(lot);
      const schema = result?.schema || '';
      const wafers = result?.wafers && result.wafers.length > 0 ? result.wafers.join('; ') : '';
      const verifiedAt = this.verifiedAt.toISOString();
      csv += `${escapedLot},"${status}","${schema}","${wafers}","${verifiedAt}"\n`;
    });

    // Add rows for not found lots
    this.notFoundLots.forEach((lot) => {
      const escapedLot = this.escapeCsvField(lot);
      const status = 'Not Found in Exensio';
      const verifiedAt = this.verifiedAt.toISOString();
      csv += `${escapedLot},"${status}","","","${verifiedAt}"\n`;
    });

    // Create Blob and trigger download
    const blob = new Blob([csv], { type: 'text/csv;charset=utf-8;' });
    const link = document.createElement('a');
    link.href = URL.createObjectURL(blob);
    link.download = filename;
    link.click();
    URL.revokeObjectURL(link.href);

    // Do not close dialog after export (Requirement 11.5)
  }

  /**
   * Escape CSV field: wrap in quotes and escape internal quotes
   */
  private escapeCsvField(field: string): string {
    // Escape quotes by doubling them
    const escaped = field.replace(/"/g, '""');
    // Wrap in quotes
    return `"${escaped}"`;
  }
}
