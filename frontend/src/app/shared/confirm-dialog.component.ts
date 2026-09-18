import { CommonModule } from '@angular/common';
import { Component, Inject } from '@angular/core';
import { GlassIconComponent } from './components/glass-icon.component';
import { GLASS_DIALOG_DATA, GlassDialogRef } from './services/glass-dialog.service';

export interface ConfirmDialogData {
  title: string;
  message: string;
  confirmText?: string;
  cancelText?: string;
  isDestructive?: boolean;
}

@Component({
  selector: 'app-confirm-dialog',
  standalone: true,
  imports: [CommonModule, GlassIconComponent],
  template: `
    <div class="confirm-dialog glass-panel">
      <div class="dialog-header">
        <app-glass-icon
          [name]="data.isDestructive ? 'warning' : 'info'"
          [size]="24"
          [class.destructive]="data.isDestructive"
        ></app-glass-icon>
        <h2>{{ data.title }}</h2>
      </div>
      <div class="dialog-content">
        <p>{{ data.message }}</p>
      </div>
      <div class="dialog-actions">
        <button class="cancel-btn" (click)="dialogRef.close(false)">
          {{ data.cancelText || 'Cancel' }}
        </button>
        <button class="confirm-btn" [class.destructive]="data.isDestructive" (click)="dialogRef.close(true)">
          {{ data.confirmText || 'Confirm' }}
        </button>
      </div>
    </div>
  `,
  styles: [
    `
      .confirm-dialog {
        padding: 0;
        border-radius: 16px;
        overflow: hidden;
        min-width: 400px;
      }

      .dialog-header {
        display: flex;
        align-items: center;
        gap: 1rem;
        padding: 1.5rem;
        background: rgba(255, 255, 255, 0.02);
        border-bottom: 1px solid rgba(255, 255, 255, 0.05);

        app-glass-icon {
          color: var(--accent-color);

          &.destructive {
            color: #ff6b6b;
          }
        }

        h2 {
          margin: 0;
          font-size: 1.25rem;
          font-weight: 600;
          color: white;
        }
      }

      .dialog-content {
        padding: 1.5rem;

        p {
          margin: 0;
          color: var(--text-muted);
          line-height: 1.6;
        }
      }

      .dialog-actions {
        display: flex;
        justify-content: flex-end;
        gap: 0.75rem;
        padding: 1rem 1.5rem;
        background: rgba(255, 255, 255, 0.02);
        border-top: 1px solid rgba(255, 255, 255, 0.05);
      }

      .cancel-btn,
      .confirm-btn {
        padding: 0.625rem 1.5rem;
        border-radius: 8px;
        font-weight: 500;
        font-size: 0.875rem;
        cursor: pointer;
        transition: all 0.2s ease;
        border: 1px solid;
      }

      .cancel-btn {
        background: rgba(255, 255, 255, 0.05);
        border-color: rgba(255, 255, 255, 0.1);
        color: var(--text-muted);

        &:hover {
          background: rgba(255, 255, 255, 0.08);
          border-color: rgba(255, 255, 255, 0.15);
          color: white;
        }
      }

      .confirm-btn {
        background: var(--accent-color);
        border-color: var(--accent-color);
        color: white;

        &:hover {
          filter: brightness(1.1);
          transform: translateY(-1px);
        }

        &.destructive {
          background: #ff6b6b;
          border-color: #ff6b6b;

          &:hover {
            background: #ff5252;
          }
        }
      }

      :host-context(body.light-theme) {
        .dialog-header {
          background: rgba(248, 250, 252, 0.9);
          border-bottom-color: rgba(15, 23, 42, 0.08);

          h2 {
            color: #0f172a;
          }

          app-glass-icon {
            color: #4f46e5;

            &.destructive {
              color: #dc2626;
            }
          }
        }

        .dialog-content p {
          color: rgba(51, 65, 85, 0.75);
        }

        .dialog-actions {
          background: rgba(248, 250, 252, 0.9);
          border-top-color: rgba(15, 23, 42, 0.08);
        }

        .cancel-btn {
          background: rgba(15, 23, 42, 0.05);
          border-color: rgba(15, 23, 42, 0.15);
          color: rgba(51, 65, 85, 0.75);

          &:hover {
            background: rgba(15, 23, 42, 0.08);
            color: #0f172a;
          }
        }

        .confirm-btn {
          background: #4f46e5;
          border-color: #4f46e5;

          &:hover {
            background: #4338ca;
          }

          &.destructive {
            background: #dc2626;
            border-color: #dc2626;

            &:hover {
              background: #b91c1c;
            }
          }
        }
      }
    `,
  ],
})
export class ConfirmDialogComponent {
  constructor(
    public dialogRef: GlassDialogRef<ConfirmDialogComponent>,
    @Inject(GLASS_DIALOG_DATA) public data: ConfirmDialogData,
  ) {}
}
