import { CommonModule } from '@angular/common';
import { Component, Inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { DashboardSenderSnapshot, DashboardSiteSnapshot } from '../api/backend.service';
import { GLASS_DIALOG_DATA, GlassDialogRef } from '../shared/services/glass-dialog.service';

type SiteDetailDialogResult =
  | { action: 'refresh' }
  | { action: 'resume'; sender: DashboardSenderSnapshot }
  | { action: 'close' };

interface SiteDetailDialogData {
  site: DashboardSiteSnapshot;
}

@Component({
  selector: 'app-site-detail-modal',
  standalone: true,
  imports: [CommonModule, MatButtonModule, MatIconModule],
  template: `
    <div class="site-detail-modal">
      <header class="modal-header">
        <div>
          <p class="eyebrow">Site details</p>
          <h2>{{ data.site.site }}</h2>
        </div>
        <button mat-icon-button type="button" (click)="close()" aria-label="Close site details">
          <mat-icon>close</mat-icon>
        </button>
      </header>

      <section class="metric-grid">
        <div class="metric-item">
          <span class="label">Backlog</span>
          <span class="value alert">{{ data.site.metrics.backlog | number }}</span>
        </div>
        <div class="metric-item">
          <span class="label">Ready</span>
          <span class="value good">{{ data.site.metrics.ready | number }}</span>
        </div>
        <div class="metric-item">
          <span class="label">Queue</span>
          <span class="value info">{{ data.site.metrics.enqueued | number }}</span>
        </div>
        <div class="metric-item">
          <span class="label">Completed</span>
          <span class="value success">{{ data.site.metrics.completed | number }}</span>
        </div>
      </section>

      <section class="senders-section">
        <div class="section-heading">
          <h3>Senders</h3>
          <span>{{ data.site.senders.length }} total</span>
        </div>

        <div class="senders-list">
          @for (sender of data.site.senders; track sender.senderId) {
            <article class="sender-item">
              <div class="sender-copy">
                <strong>{{ sender.senderLabel }}</strong>
                <span>{{ sender.metrics.backlog | number }} backlog • {{ sender.metrics.completed | number }} completed</span>
              </div>
              <button mat-stroked-button type="button" matTooltip="Start a new monitoring session for this sender" (click)="resumeSender(sender)">
                <mat-icon>play_circle</mat-icon>
                Start Monitoring
              </button>
            </article>
          }
        </div>
      </section>

      <footer class="modal-footer">
        <button mat-stroked-button type="button" (click)="close()">Close</button>
        <button mat-raised-button color="primary" type="button" (click)="refresh()">
          <mat-icon>refresh</mat-icon>
          Refresh
        </button>
      </footer>
    </div>
  `,
  styles: [`
    :host {
      display: block;
      color: inherit;
    }

    .site-detail-modal {
      display: flex;
      flex-direction: column;
      gap: 1.25rem;
      padding: 1.25rem;
      min-width: min(96vw, 520px);
      max-width: 100%;
    }

    .modal-header {
      display: flex;
      justify-content: space-between;
      align-items: flex-start;
      gap: 1rem;

      h2 {
        margin: 0;
        font-size: 1.5rem;
        line-height: 1.2;
      }

      .eyebrow {
        margin: 0 0 0.35rem;
        font-size: 0.75rem;
        text-transform: uppercase;
        letter-spacing: 0.08em;
        color: rgba(255, 255, 255, 0.55);
      }
    }

    .metric-grid {
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: 0.85rem;
    }

    .metric-item,
    .sender-item {
      border-radius: 14px;
      border: 1px solid rgba(255, 255, 255, 0.1);
      background: rgba(255, 255, 255, 0.04);
      backdrop-filter: blur(16px);
      -webkit-backdrop-filter: blur(16px);
    }

    .metric-item {
      padding: 1rem;
      display: flex;
      flex-direction: column;
      gap: 0.35rem;

      .label {
        font-size: 0.8rem;
        color: rgba(255, 255, 255, 0.65);
        text-transform: uppercase;
        letter-spacing: 0.04em;
      }

      .value {
        font-size: 1.35rem;
        font-weight: 700;

        &.alert { color: #ef4444; }
        &.good { color: #10b981; }
        &.info { color: #3b82f6; }
        &.success { color: #8b5cf6; }
      }
    }

    .senders-section {
      display: flex;
      flex-direction: column;
      gap: 0.75rem;
    }

    .section-heading {
      display: flex;
      justify-content: space-between;
      align-items: center;
      gap: 1rem;

      h3 {
        margin: 0;
        font-size: 1rem;
      }

      span {
        font-size: 0.85rem;
        color: rgba(255, 255, 255, 0.6);
      }
    }

    .senders-list {
      display: flex;
      flex-direction: column;
      gap: 0.75rem;
      max-height: 45vh;
      overflow: auto;
      padding-right: 0.15rem;
    }

    .sender-item {
      display: flex;
      justify-content: space-between;
      align-items: center;
      gap: 1rem;
      padding: 0.9rem 1rem;
    }

    .sender-copy {
      display: flex;
      flex-direction: column;
      gap: 0.2rem;

      strong {
        font-size: 0.95rem;
      }

      span {
        font-size: 0.8rem;
        color: rgba(255, 255, 255, 0.6);
      }
    }

    .modal-footer {
      display: flex;
      justify-content: flex-end;
      gap: 0.75rem;
      flex-wrap: wrap;
      padding-top: 0.25rem;
    }

    @media (max-width: 640px) {
      .site-detail-modal {
        padding: 1rem;
      }

      .metric-grid {
        grid-template-columns: 1fr;
      }

      .sender-item {
        align-items: flex-start;
        flex-direction: column;
      }

      .modal-footer {
        justify-content: stretch;

        button {
          flex: 1;
        }
      }
    }
  `]
})
export class SiteDetailModalComponent {
  constructor(
    @Inject(GLASS_DIALOG_DATA) public data: SiteDetailDialogData,
    private dialogRef: GlassDialogRef<SiteDetailModalComponent, SiteDetailDialogResult>
  ) {}

  close(): void {
    this.dialogRef.close({ action: 'close' });
  }

  refresh(): void {
    this.dialogRef.close({ action: 'refresh' });
  }

  resumeSender(sender: DashboardSenderSnapshot): void {
    this.dialogRef.close({ action: 'resume', sender });
  }
}