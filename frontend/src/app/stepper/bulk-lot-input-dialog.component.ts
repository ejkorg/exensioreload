import { CommonModule } from '@angular/common';
import { Component, ElementRef, Inject, OnInit, ViewChild, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { GlassButtonComponent } from '../shared/components/glass-button.component';
import { GlassIconComponent } from '../shared/components/glass-icon.component';
import { GLASS_DIALOG_DATA, GlassDialogRef } from '../shared/services/glass-dialog.service';
import { ToastService } from '../shared/services/toast.service';
import { parseLotInput, validateLots } from './lot-input-parser';

export interface BulkLotInputDialogData {
  existingLots: string[];
}

export interface BulkLotInputDialogResult {
  lots: string[];
}

@Component({
  selector: 'app-bulk-lot-input-dialog',
  standalone: true,
  imports: [CommonModule, FormsModule, GlassButtonComponent, GlassIconComponent],
  template: `
    <div class="bulk-lot-dialog" role="dialog" aria-labelledby="bulk-lot-dialog-title" aria-modal="true">
      <!-- Header -->
      <div class="dialog-header">
        <div class="header-icon">
          <app-glass-icon name="upload_file" [size]="26"></app-glass-icon>
        </div>
        <div class="header-text">
          <h2 id="bulk-lot-dialog-title">Bulk Add Lots</h2>
          <p class="header-sub">Paste or upload multiple lot identifiers at once</p>
        </div>
        <button class="close-btn" (click)="onCancel()" aria-label="Close dialog">
          <app-glass-icon name="close" [size]="18"></app-glass-icon>
        </button>
      </div>

      <!-- Input section -->
      <div class="input-section">
        <div class="input-label-row">
          <label for="bulk-lot-textarea">Lot identifiers</label>
          <span class="input-hint">Separate by comma, newline, or semicolon</span>
        </div>

        <textarea
          #lotTextarea
          id="bulk-lot-textarea"
          [(ngModel)]="rawInput"
          (input)="onInputChange()"
          placeholder="e.g. L12345, L67890&#10;or paste one per line"
          aria-describedby="bulk-lot-validation-summary"
          rows="8"
        ></textarea>

        <div class="upload-row">
          <button
            class="upload-btn"
            type="button"
            (click)="triggerFileUpload()"
            aria-label="Upload a .txt or .csv file containing lot identifiers"
          >
            <app-glass-icon name="upload" [size]="16"></app-glass-icon>
            <span>Upload File (.txt, .csv)</span>
          </button>
          <input
            #fileInput
            type="file"
            accept=".txt,.csv"
            class="hidden-file-input"
            aria-hidden="true"
            (change)="onFileSelected($event)"
          />
        </div>
      </div>

      <!-- Validation feedback -->
      <div
        class="validation-section"
        id="bulk-lot-validation-summary"
        aria-live="polite"
        aria-atomic="false"
        role="status"
        aria-label="Validation summary"
      >
        <!-- Loading indicator for large inputs (>500 lots) -->
        <div class="processing-row" *ngIf="isProcessing()" aria-label="Processing lots">
          <span class="processing-spinner" aria-hidden="true"></span>
          <span class="processing-label">Parsing lots…</span>
        </div>

        <ng-container *ngIf="!isProcessing()">
          <div class="stats-row" *ngIf="rawInput.length > 0" role="group" aria-label="Lot counts">
            <span class="stat valid">
              <app-glass-icon name="check_circle" [size]="14" color="success"></app-glass-icon>
              {{ validCount() }} valid
            </span>
            <span class="stat" *ngIf="invalidCount() > 0">
              <app-glass-icon name="error" [size]="14" color="error"></app-glass-icon>
              {{ invalidCount() }} invalid
            </span>
            <span class="stat" *ngIf="duplicateCount() > 0">
              <app-glass-icon name="warning" [size]="14" color="warning"></app-glass-icon>
              {{ duplicateCount() }} duplicate{{ duplicateCount() === 1 ? '' : 's' }}
            </span>
          </div>

          <div class="warnings" *ngIf="warnings().length > 0" role="list" aria-label="Validation warnings">
            <div class="warning-item" role="listitem" *ngFor="let w of warnings()">
              <app-glass-icon name="info" [size]="14" color="warning" aria-hidden="true"></app-glass-icon>
              <span>{{ w }}</span>
            </div>
          </div>
        </ng-container>
      </div>

      <!-- Actions -->
      <div class="dialog-actions" role="group" aria-label="Dialog actions">
        <app-glass-button variant="secondary" (clicked)="onCancel()">Cancel</app-glass-button>
        <app-glass-button variant="primary" [disabled]="validCount() === 0 || isProcessing()" (clicked)="onAddLots()">
          <app-glass-icon name="add" [size]="16"></app-glass-icon>
          Add {{ validCount() }} Lot{{ validCount() === 1 ? '' : 's' }}
        </app-glass-button>
      </div>
    </div>
  `,
  styles: [
    `
      .bulk-lot-dialog {
        width: 560px;
        max-width: 95vw;
        display: flex;
        flex-direction: column;
        background: var(--card-bg);
        border: 1px solid var(--card-border);
        border-radius: 20px;
        overflow: hidden;
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
        background: rgba(129, 140, 248, 0.12);
        border: 1px solid rgba(129, 140, 248, 0.25);
        color: var(--accent-color);
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

      .close-btn {
        display: flex;
        align-items: center;
        justify-content: center;
        width: 30px;
        height: 30px;
        border: none;
        flex-shrink: 0;
        background: rgba(255, 255, 255, 0.05);
        border-radius: 8px;
        cursor: pointer;
        color: var(--text-muted);
        transition: all 0.15s ease;

        &:hover {
          background: rgba(255, 255, 255, 0.1);
          color: var(--text-main);
        }
      }

      /* Input section */
      .input-section {
        padding: 1.25rem 1.5rem 0.75rem;
        display: flex;
        flex-direction: column;
        gap: 0.625rem;
      }

      .input-label-row {
        display: flex;
        align-items: baseline;
        justify-content: space-between;
        gap: 0.5rem;

        label {
          font-size: 0.8125rem;
          font-weight: 600;
          color: var(--text-main);
        }
      }

      .input-hint {
        font-size: 0.73rem;
        color: var(--text-muted);
      }

      textarea {
        width: 100%;
        padding: 0.75rem;
        background: rgba(255, 255, 255, 0.04);
        border: 1px solid rgba(255, 255, 255, 0.1);
        border-radius: 10px;
        color: var(--text-main);
        font-family: 'JetBrains Mono', 'Consolas', monospace;
        font-size: 0.8125rem;
        resize: vertical;
        min-height: 140px;
        transition: border-color 0.2s ease;
        box-sizing: border-box;
        outline: none;

        &::placeholder {
          color: var(--text-muted);
          opacity: 0.7;
        }

        &:focus {
          border-color: rgba(129, 140, 248, 0.45);
          background: rgba(255, 255, 255, 0.06);
          box-shadow: 0 0 0 3px rgba(129, 140, 248, 0.12);
        }
      }

      .upload-row {
        display: flex;
        align-items: center;
      }

      .upload-btn {
        display: inline-flex;
        align-items: center;
        gap: 0.4rem;
        padding: 0.4rem 0.875rem;
        background: rgba(255, 255, 255, 0.04);
        border: 1px dashed rgba(255, 255, 255, 0.18);
        border-radius: 8px;
        cursor: pointer;
        color: var(--text-muted);
        font-size: 0.78rem;
        font-weight: 500;
        transition: all 0.15s ease;

        &:hover {
          background: rgba(129, 140, 248, 0.08);
          border-color: rgba(129, 140, 248, 0.35);
          color: var(--accent-color);
        }
      }

      .hidden-file-input {
        display: none;
      }

      /* Validation section */
      .validation-section {
        padding: 0 1.5rem 0.875rem;
        display: flex;
        flex-direction: column;
        gap: 0.5rem;
        min-height: 1.5rem;
      }

      .processing-row {
        display: flex;
        align-items: center;
        gap: 0.5rem;
        font-size: 0.78rem;
        color: var(--text-muted);
      }

      .processing-spinner {
        display: inline-block;
        width: 14px;
        height: 14px;
        border: 2px solid rgba(129, 140, 248, 0.25);
        border-top-color: var(--accent-color);
        border-radius: 50%;
        animation: spin 0.7s linear infinite;
        flex-shrink: 0;
      }

      .processing-label {
        font-size: 0.78rem;
        color: var(--text-muted);
      }

      @keyframes spin {
        to {
          transform: rotate(360deg);
        }
      }

      .stats-row {
        display: flex;
        align-items: center;
        gap: 1rem;
        flex-wrap: wrap;
      }

      .stat {
        display: inline-flex;
        align-items: center;
        gap: 0.3rem;
        font-size: 0.78rem;
        font-weight: 600;
        color: var(--text-muted);

        &.valid {
          color: #10b981;
        }
      }

      .warnings {
        display: flex;
        flex-direction: column;
        gap: 0.3rem;
      }

      .warning-item {
        display: flex;
        align-items: flex-start;
        gap: 0.4rem;
        font-size: 0.775rem;
        color: var(--text-muted);
        background: rgba(245, 158, 11, 0.07);
        border: 1px solid rgba(245, 158, 11, 0.18);
        border-radius: 8px;
        padding: 0.4rem 0.65rem;

        span {
          line-height: 1.4;
        }
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
      }

      @media (max-width: 600px) {
        .bulk-lot-dialog {
          width: 100%;
          max-width: 100vw;
          border-radius: 16px 16px 0 0;
        }
        .dialog-actions {
          flex-direction: column-reverse;
        }
      }
    `,
  ],
})
export class BulkLotInputDialogComponent implements OnInit {
  @ViewChild('lotTextarea') lotTextareaRef!: ElementRef<HTMLTextAreaElement>;
  @ViewChild('fileInput') fileInputRef!: ElementRef<HTMLInputElement>;

  rawInput = '';

  validCount = signal(0);
  invalidCount = signal(0);
  duplicateCount = signal(0);
  warnings = signal<string[]>([]);
  isProcessing = signal(false);

  constructor(
    @Inject(GLASS_DIALOG_DATA) public data: BulkLotInputDialogData,
    public dialogRef: GlassDialogRef<BulkLotInputDialogComponent, BulkLotInputDialogResult | undefined>,
    private toastService: ToastService,
  ) {}

  ngOnInit(): void {
    // Focus the textarea after the view initialises
    setTimeout(() => {
      this.lotTextareaRef?.nativeElement.focus();
    }, 50);
  }

  onInputChange(): void {
    // For large inputs (>500 lots heuristic: >2000 chars), show a loading indicator
    // before running parse+validate so the UI doesn't appear frozen (Requirement 8.3).
    const isLarge = this.rawInput.length > 2000;
    if (isLarge) {
      this.isProcessing.set(true);
      // Yield to the browser to render the spinner, then do the heavy work.
      setTimeout(() => this.runValidation(), 0);
    } else {
      this.runValidation();
    }
  }

  private runValidation(): void {
    const parsed = parseLotInput(this.rawInput);
    const result = validateLots(parsed, this.data.existingLots ?? []);
    this.validCount.set(result.valid.length);
    this.invalidCount.set(result.invalid.length);
    this.duplicateCount.set(result.duplicates.length);
    this.warnings.set(result.warnings);
    this.isProcessing.set(false);
  }

  triggerFileUpload(): void {
    this.fileInputRef?.nativeElement.click();
  }

  onFileSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    if (!file) return;

    const reader = new FileReader();
    reader.onload = (e) => {
      this.rawInput = (e.target?.result as string) ?? '';
      this.onInputChange();
    };
    reader.onerror = () => {
      this.toastService.error(`Failed to read file "${file.name}". Please try again.`);
      // Reset file input so the same file can be retried
      input.value = '';
    };
    reader.readAsText(file);
  }

  onAddLots(): void {
    const parsed = parseLotInput(this.rawInput);
    const result = validateLots(parsed, this.data.existingLots ?? []);
    this.dialogRef.close({ lots: result.valid });
  }

  onCancel(): void {
    this.dialogRef.close(undefined);
  }
}
