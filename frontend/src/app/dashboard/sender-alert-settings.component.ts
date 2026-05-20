import { Component, ChangeDetectionStrategy, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ReactiveFormsModule, FormBuilder, Validators } from '@angular/forms';
import { BackendService, AlertThreshold } from '../api/backend.service';
import { GlassButtonComponent } from '../shared/components/glass-button.component';
import { GlassInputComponent } from '../shared/components/glass-input.component';
import { GlassCheckboxComponent } from '../shared/components/glass-checkbox.component';
import { GlassIconComponent } from '../shared/components/glass-icon.component';
import { GLASS_DIALOG_DATA, GlassDialogRef } from '../shared/services/glass-dialog.service';

export interface SenderAlertSettingsData {
    senderId: number;
    senderLabel: string;
    site: string;
    backlog: number;
    successRate: number;
}

@Component({
    selector: 'app-sender-alert-settings',
    standalone: true,
    imports: [
        CommonModule,
        ReactiveFormsModule,
        GlassButtonComponent,
        GlassInputComponent,
        GlassCheckboxComponent,
        GlassIconComponent
    ],
    template: `
        <div class="alert-settings-container">
            <div class="dialog-header">
                <div class="header-info">
                    <h2>Alert Thresholds</h2>
                    <p>{{ data.senderLabel }} • {{ data.site }}</p>
                </div>
                <button type="button" class="icon-close" (click)="close()" aria-label="Close">
                    <app-glass-icon name="close" color="muted" [size]="20"></app-glass-icon>
                </button>
            </div>

            <div class="metrics-preview">
                <div class="metric-pill">
                    <span class="label">Current Backlog</span>
                    <strong>{{ data.backlog | number }}</strong>
                </div>
                <div class="metric-pill">
                    <span class="label">Failure Rate</span>
                    <strong>{{ failureRate }}%</strong>
                </div>
            </div>

            <form [formGroup]="form" class="form-grid">
                <div class="glass-card">
                    <div class="card-header">
                        <app-glass-icon name="notifications" color="primary" [size]="20"></app-glass-icon>
                        <h3>Enable Alerts</h3>
                    </div>
                    <app-glass-checkbox
                        formControlName="enabled"
                        label="Enable alert monitoring for this sender">
                    </app-glass-checkbox>
                </div>

                <div class="glass-card">
                    <div class="card-header">
                        <app-glass-icon name="warning" color="warning" [size]="20"></app-glass-icon>
                        <h3>Backlog Threshold</h3>
                    </div>
                    <p class="helper-text">Alert when backlog exceeds this number of items.</p>
                    <app-glass-input
                        type="number"
                        formControlName="backlogThreshold"
                        label="Backlog threshold"
                        placeholder="1000">
                    </app-glass-input>
                </div>

                <div class="glass-card">
                    <div class="card-header">
                        <app-glass-icon name="error" color="error" [size]="20"></app-glass-icon>
                        <h3>Failure Rate Threshold</h3>
                    </div>
                    <p class="helper-text">Alert when failure rate exceeds this percentage.</p>
                    <app-glass-input
                        type="number"
                        formControlName="failureRateThreshold"
                        label="Failure rate (%)"
                        placeholder="10">
                    </app-glass-input>
                </div>
            </form>

            <div class="status-row" *ngIf="statusMessage">
                <app-glass-icon [name]="statusIcon" [color]="statusColor" [size]="18"></app-glass-icon>
                <span [class.error]="statusColor === 'error'">{{ statusMessage }}</span>
            </div>

            <div class="dialog-actions">
                <app-glass-button variant="secondary" (clicked)="close()">Cancel</app-glass-button>
                <app-glass-button
                    variant="primary"
                    [disabled]="form.invalid || saving"
                    [loading]="saving"
                    (clicked)="save()">
                    Save Thresholds
                </app-glass-button>
            </div>
        </div>
    `,
    styles: [`
        .alert-settings-container {
            display: flex;
            flex-direction: column;
            gap: 1.5rem;
            padding: 1.75rem;
            color: var(--text-primary);
            min-width: 360px;
        }

        .dialog-header {
            display: flex;
            justify-content: space-between;
            align-items: flex-start;
            gap: 1rem;
        }

        .header-info h2 {
            margin: 0;
            font-size: 1.5rem;
        }

        .header-info p {
            margin: 0.35rem 0 0 0;
            color: var(--text-secondary);
            font-size: 0.95rem;
        }

        .icon-close {
            border: none;
            background: transparent;
            cursor: pointer;
            padding: 0.25rem;
            border-radius: 8px;
        }

        .icon-close:hover {
            background: rgba(255, 255, 255, 0.08);
        }

        .metrics-preview {
            display: grid;
            grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
            gap: 0.75rem;
        }

        .metric-pill {
            background: rgba(255, 255, 255, 0.05);
            border: 1px solid rgba(255, 255, 255, 0.08);
            border-radius: 12px;
            padding: 0.75rem 1rem;
            display: flex;
            flex-direction: column;
            gap: 0.3rem;
        }

        .metric-pill .label {
            font-size: 0.75rem;
            text-transform: uppercase;
            letter-spacing: 0.08em;
            color: var(--text-secondary);
        }

        .metric-pill strong {
            font-size: 1.1rem;
        }

        .form-grid {
            display: grid;
            grid-template-columns: 1fr;
            gap: 1rem;
        }

        .glass-card {
            background: rgba(255, 255, 255, 0.04);
            border: 1px solid rgba(255, 255, 255, 0.08);
            border-radius: 14px;
            padding: 1rem 1.25rem;
            display: flex;
            flex-direction: column;
            gap: 0.75rem;
        }

        .card-header {
            display: flex;
            align-items: center;
            gap: 0.5rem;
        }

        .card-header h3 {
            margin: 0;
            font-size: 1rem;
        }

        .helper-text {
            margin: 0;
            color: var(--text-secondary);
            font-size: 0.9rem;
        }

        .status-row {
            display: flex;
            align-items: center;
            gap: 0.5rem;
            font-size: 0.95rem;
        }

        .status-row span.error {
            color: #fca5a5;
        }

        .dialog-actions {
            display: flex;
            justify-content: flex-end;
            gap: 0.75rem;
        }

        :host-context(body.light-theme) .alert-settings-container {
            color: #0f172a;
        }

        :host-context(body.light-theme) .metric-pill,
        :host-context(body.light-theme) .glass-card {
            background: rgba(255, 255, 255, 0.95);
            border: 1px solid rgba(15, 23, 42, 0.12);
        }

        :host-context(body.light-theme) .helper-text,
        :host-context(body.light-theme) .header-info p,
        :host-context(body.light-theme) .metric-pill .label {
            color: rgba(51, 65, 85, 0.9);
        }

        @media (max-width: 480px) {
            .alert-settings-container {
                min-width: unset;
                padding: 1.25rem;
            }
        }
    `],
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class SenderAlertSettingsComponent {
    private backend = inject(BackendService);
    private dialogRef = inject(GlassDialogRef<SenderAlertSettingsComponent, boolean>);
    readonly data = inject(GLASS_DIALOG_DATA as any) as SenderAlertSettingsData;
    readonly failureRate = Math.max(0, Math.min(100, 100 - this.data.successRate));

    saving = false;
    statusMessage = '';
    statusColor: 'primary' | 'success' | 'error' = 'primary';
    statusIcon = 'info';

    form = inject(FormBuilder).group({
        enabled: [true],
        backlogThreshold: [1000, [Validators.required, Validators.min(0)]],
        failureRateThreshold: [10, [Validators.required, Validators.min(0), Validators.max(100)]]
    });

    constructor() {
        this.backend.getAlertThresholds(this.data.senderId).subscribe({
            next: (thresholds: AlertThreshold) => {
                this.form.patchValue({
                    enabled: thresholds.enabled,
                    backlogThreshold: thresholds.backlogThreshold,
                    failureRateThreshold: thresholds.failureRateThreshold
                });
            },
            error: () => {
                this.statusMessage = 'Unable to load alert thresholds. Using defaults.';
                this.statusColor = 'error';
                this.statusIcon = 'error';
            }
        });
    }

    close(): void {
        this.dialogRef.close(false);
    }

    save(): void {
        if (this.form.invalid) return;
        const values = this.form.getRawValue();
        this.saving = true;
        this.statusMessage = 'Saving thresholds...';
        this.statusColor = 'primary';
        this.statusIcon = 'sync';

        const payload: AlertThreshold = {
            senderId: this.data.senderId,
            backlogThreshold: Number(values.backlogThreshold ?? 0),
            failureRateThreshold: Number(values.failureRateThreshold ?? 0),
            enabled: !!values.enabled
        };

        this.backend.updateAlertThresholds(this.data.senderId, payload).subscribe({
            next: () => {
                this.saving = false;
                this.statusMessage = 'Alert thresholds saved.';
                this.statusColor = 'success';
                this.statusIcon = 'check_circle';
                setTimeout(() => this.dialogRef.close(true), 600);
            },
            error: () => {
                this.saving = false;
                this.statusMessage = 'Failed to save thresholds.';
                this.statusColor = 'error';
                this.statusIcon = 'error';
            }
        });
    }
}