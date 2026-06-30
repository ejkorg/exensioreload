import { CommonModule } from '@angular/common';
import { Component, Input } from '@angular/core';

@Component({
  selector: 'app-integration-badge',
  standalone: true,
  imports: [CommonModule],
  template: `
    <span
      class="integration-badge"
      [class.integration-badge-pending]="status === 'pending'"
      [class.integration-badge-not-found]="status === 'not_found'"
      [class.integration-badge-success]="status === 'success'"
      [class.integration-badge-failure]="status === 'failure'"
      [class.integration-badge-timeout]="status === 'timeout'"
      [class.integration-badge-error]="status === 'error'"
      [class.integration-badge-not-configured]="status === 'not_configured'"
      [class.integration-badge-cp]="type === 'cp'"
      [class.integration-badge-exensio]="type === 'exensio'"
      [glassTooltip]="tooltip()"
    >
      <app-glass-icon [icon]="icon()"></app-glass-icon>
      <span class="integration-badge-text">{{ displayText() }}</span>
    </span>
  `,
  styles: [
    `
      .integration-badge {
        display: inline-flex;
        align-items: center;
        gap: 4px;
        padding: 2px 8px;
        border-radius: 12px;
        font-size: 11px;
        font-weight: 500;
        text-transform: uppercase;
      }

      .integration-badge-text {
        line-height: 1;
      }

      /* Pending state */
      .integration-badge-pending.integration-badge-cp,
      .integration-badge-pending.integration-badge-exensio {
        background-color: #e0e0e0;
        color: #666666;
        animation: integration-badge-pulse 3s ease-in-out infinite;
      }

      /* Not found state */
      .integration-badge-not-found.integration-badge-cp {
        background-color: #fff3e0;
        color: #f57c00;
        animation: integration-badge-pulse 2.5s ease-in-out infinite;
      }
      .integration-badge-not-found.integration-badge-exensio {
        background-color: #fff3e0;
        color: #f57c00;
        animation: integration-badge-pulse 2.5s ease-in-out infinite;
      }

      /* Success state */
      .integration-badge-success.integration-badge-cp {
        background-color: #e8f5e9;
        color: #2e7d32;
      }
      .integration-badge-success.integration-badge-exensio {
        background-color: #e8f5e9;
        color: #2e7d32;
      }

      /* Failure state */
      .integration-badge-failure.integration-badge-cp {
        background-color: #ffebee;
        color: #c62828;
      }
      .integration-badge-failure.integration-badge-exensio {
        background-color: #ffebee;
        color: #c62828;
      }

      /* Timeout state */
      .integration-badge-timeout.integration-badge-cp {
        background-color: #ffebee;
        color: #c62828;
      }
      .integration-badge-timeout.integration-badge-exensio {
        background-color: #ffebee;
        color: #c62828;
      }

      /* Error state */
      .integration-badge-error.integration-badge-cp {
        background-color: #ffebee;
        color: #c62828;
      }
      .integration-badge-error.integration-badge-exensio {
        background-color: #ffebee;
        color: #c62828;
      }

      /* Not configured state */
      .integration-badge-not-configured.integration-badge-cp,
      .integration-badge-not-configured.integration-badge-exensio {
        background-color: #f5f5f5;
        color: #9e9e9e;
      }

      @keyframes integration-badge-pulse {
        0%, 100% {
          opacity: 1;
        }
        50% {
          opacity: 0.6;
        }
      }
    `,
  ],
})
export class IntegrationBadgeComponent {
  @Input() status: string | null = null;
  @Input() type: 'cp' | 'exensio' = 'cp';

  icon() {
    if (this.status === 'pending') return 'schedule';
    if (this.status === 'not_found') return 'search';
    if (this.status === 'success') return 'check_circle';
    if (this.status === 'failure') return 'error';
    if (this.status === 'timeout') return 'schedule';
    if (this.status === 'error') return 'warning';
    if (this.status === 'not_configured') return 'minus';
    return 'help';
  }

  displayText() {
    if (this.status === 'pending') return 'Pending';
    if (this.status === 'not_found') return 'Not Found';
    if (this.status === 'success') return 'Success';
    if (this.status === 'failure') return 'Failed';
    if (this.status === 'timeout') return 'Timeout';
    if (this.status === 'error') return 'Error';
    if (this.status === 'not_configured') return 'N/A';
    return 'Unknown';
  }

  tooltip() {
    if (this.status === 'pending') return this.type === 'cp' ? 'CP enrichment pending' : 'Exensio load pending';
    if (this.status === 'not_found') return this.type === 'cp' ? 'CP log not found yet' : 'Exensio wafer not found yet';
    if (this.status === 'success') return this.type === 'cp' ? 'CP enrichment completed' : 'Exensio load completed';
    if (this.status === 'failure') return this.type === 'cp' ? 'CP enrichment failed' : 'Exensio load failed';
    if (this.status === 'timeout') return this.type === 'cp' ? 'CP enrichment timeout' : 'Exensio load timeout';
    if (this.status === 'error') return this.type === 'cp' ? 'CP enrichment error' : 'Exensio load error';
    if (this.status === 'not_configured') return this.type === 'cp' ? 'CP not configured' : 'Exensio not configured';
    return 'Unknown status';
  }
}
