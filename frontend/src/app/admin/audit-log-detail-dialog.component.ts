import { CommonModule } from '@angular/common';
import { Component, Inject, inject } from '@angular/core';
import { GlassIconComponent } from '../shared/components/glass-icon.component';
import { GlassTooltipDirective } from '../shared/directives/glass-tooltip.directive';
import { GLASS_DIALOG_DATA, GlassDialogRef } from '../shared/services/glass-dialog.service';
import { AuditLogDto } from './audit.service';

@Component({
  selector: 'app-audit-log-detail-dialog',
  standalone: true,
  imports: [CommonModule, GlassIconComponent, GlassTooltipDirective],
  template: `
    <div class="audit-detail-dialog">
      <div class="dialog-header">
        <h2>Audit Log Details</h2>
        <button type="button" class="close-btn" (click)="close()" [glassTooltip]="'Close'">
          <app-glass-icon name="close" [size]="20"></app-glass-icon>
        </button>
      </div>

      <div class="dialog-content">
        <!-- Basic Information Section -->
        <div class="detail-section">
          <h3 class="section-title">Basic Information</h3>
          <div class="detail-grid">
            <div class="detail-item">
              <span class="label">ID</span>
              <span class="value">{{ auditLog.id }}</span>
            </div>
            <div class="detail-item">
              <span class="label">Timestamp</span>
              <span class="value">{{ formatTimestamp(auditLog.createdAt) }}</span>
            </div>
            <div class="detail-item">
              <span class="label">User ID</span>
              <span class="value">{{ auditLog.userId || '—' }}</span>
            </div>
            <div class="detail-item">
              <span class="label">Action</span>
              <span class="value action-badge">{{ auditLog.action }}</span>
            </div>
            <div class="detail-item">
              <span class="label">Resource Type</span>
              <span class="value resource-badge">{{ auditLog.resourceType }}</span>
            </div>
            <div class="detail-item">
              <span class="label">Resource ID</span>
              <span class="value">{{ auditLog.resourceId || '—' }}</span>
            </div>
            <div class="detail-item">
              <span class="label">Status</span>
              <span class="value status-badge" [attr.data-status]="auditLog.status">
                {{ auditLog.status || '—' }}
              </span>
            </div>
            <div class="detail-item">
              <span class="label">IP Address</span>
              <span class="value">{{ auditLog.ipAddress || '—' }}</span>
            </div>
          </div>
        </div>

        <!-- Error Information (if present) -->
        <div class="detail-section" *ngIf="auditLog.errorMessage">
          <h3 class="section-title">Error</h3>
          <div class="error-message">
            <app-glass-icon name="error" [size]="18"></app-glass-icon>
            <span>{{ auditLog.errorMessage }}</span>
          </div>
        </div>

        <!-- Details Section -->
        <div class="detail-section" *ngIf="auditLog.details">
          <h3 class="section-title">Change Details</h3>
          <div class="details-container">
            <div *ngIf="isUpdateAction(); else createDetails">
              <!-- For UPDATE actions, show before/after comparison -->
              <div class="comparison-view">
                <div class="before-section" *ngIf="beforeState">
                  <h4 class="before-label">Before</h4>
                  <pre class="json-content"><code [innerHTML]="syntaxHighlightJson(beforeState)"></code></pre>
                </div>
                <div class="arrow-divider">
                  <app-glass-icon name="arrow_forward" [size]="24"></app-glass-icon>
                </div>
                <div class="after-section" *ngIf="afterState">
                  <h4 class="after-label">After</h4>
                  <pre class="json-content"><code [innerHTML]="syntaxHighlightJson(afterState)"></code></pre>
                </div>
              </div>
            </div>
            <ng-template #createDetails>
              <!-- For CREATE/DELETE actions, show full details -->
              <pre class="json-content"><code [innerHTML]="syntaxHighlightJson(parsedDetails)"></code></pre>
            </ng-template>
          </div>
        </div>

        <!-- User Agent Section -->
        <div class="detail-section" *ngIf="auditLog.userAgent">
          <h3 class="section-title">User Agent</h3>
          <div class="user-agent">{{ auditLog.userAgent }}</div>
        </div>
      </div>

      <div class="dialog-footer">
        <button type="button" class="btn-primary" (click)="close()">Close</button>
      </div>
    </div>
  `,
  styleUrls: ['./audit-log-detail-dialog.component.scss'],
})
export class AuditLogDetailDialogComponent {
  private dialogRef = inject(GlassDialogRef);
  auditLog: AuditLogDto;

  beforeState: any = null;
  afterState: any = null;
  parsedDetails: any = null;

  constructor(@Inject(GLASS_DIALOG_DATA) data: AuditLogDto) {
    this.auditLog = data;
    this.parseDetails();
  }

  private parseDetails(): void {
    if (!this.auditLog.details) {
      return;
    }

    try {
      const details = JSON.parse(this.auditLog.details);

      // For UPDATE actions, extract before/after
      if (this.isUpdateAction()) {
        this.beforeState = details.before || null;
        this.afterState = details.after || null;
      } else {
        this.parsedDetails = details;
      }
    } catch (e) {
      // If not valid JSON, treat as raw text
      this.parsedDetails = { raw: this.auditLog.details };
    }
  }

  isUpdateAction(): boolean {
    return !!(this.auditLog.action && this.auditLog.action.includes('UPDATED'));
  }

  formatTimestamp(value: string | Date | null | undefined): string {
    if (!value) return '—';
    try {
      const date = new Date(value);
      return date.toLocaleString('en-US', {
        year: 'numeric',
        month: 'short',
        day: 'numeric',
        hour: '2-digit',
        minute: '2-digit',
        second: '2-digit',
        timeZoneName: 'short',
      });
    } catch {
      return '—';
    }
  }

  syntaxHighlightJson(json: any): string {
    if (!json) return '';

    let jsonString: string;
    if (typeof json === 'string') {
      jsonString = json;
    } else {
      jsonString = JSON.stringify(json, null, 2);
    }

    // Escape HTML special characters first
    jsonString = jsonString
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#039;');

    // Apply syntax highlighting
    return jsonString
      .replace(/&quot;([^&]*?)&quot;:/g, '<span class="json-key">&quot;$1&quot;</span>:')
      .replace(/: &quot;([^&]*)&quot;/g, ': <span class="json-string">&quot;$1&quot;</span>')
      .replace(/: ([0-9]+)/g, ': <span class="json-number">$1</span>')
      .replace(/: (true|false)/g, ': <span class="json-boolean">$1</span>')
      .replace(/: (null)/g, ': <span class="json-null">$1</span>');
  }

  close(): void {
    this.dialogRef.close();
  }
}
