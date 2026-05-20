import { Component, OnInit, signal, ChangeDetectionStrategy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ReactiveFormsModule, FormBuilder, FormGroup, Validators } from '@angular/forms';
import { BackendService, AlertConfiguration, SenderAlert } from '../../api/backend.service';
import { GlassButtonComponent } from './glass-button.component';
import { GlassInputComponent } from './glass-input.component';
import { GlassCheckboxComponent } from './glass-checkbox.component';
import { GlassIconComponent } from './glass-icon.component';

@Component({
    selector: 'app-alert-configuration',
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
        <div class="alert-config-container">
            <h2>Alert Configuration</h2>

            <div class="tab-bar">
                <button
                    type="button"
                    class="tab-pill"
                    [class.active]="activeTab() === 'global'"
                    (click)="setActiveTab('global')">
                    Global Settings
                </button>
                <button
                    type="button"
                    class="tab-pill"
                    [class.active]="activeTab() === 'alerts'"
                    (click)="setActiveTab('alerts')">
                    Active Alerts
                </button>
                <button
                    type="button"
                    class="tab-pill"
                    [class.active]="activeTab() === 'templates'"
                    (click)="setActiveTab('templates')">
                    Threshold Templates
                </button>
            </div>

            <div class="tab-content">
                @if (activeTab() === 'global') {
                    <form [formGroup]="globalConfigForm" (ngSubmit)="saveGlobalConfig()">
                        <div class="glass-card">
                            <h3>Email Notifications</h3>
                            <div class="form-section">
                                <app-glass-checkbox
                                    formControlName="emailEnabled"
                                    label="Enable Email Notifications">
                                </app-glass-checkbox>

                                @if (globalConfigForm.get('emailEnabled')?.value) {
                                    <app-glass-input
                                        formControlName="emailRecipients"
                                        label="Email Recipients (comma-separated)"
                                        placeholder="user1@example.com, user2@example.com"
                                        prefixIcon="people">
                                    </app-glass-input>
                                }
                            </div>
                        </div>

                        <div class="glass-card">
                            <h3>Webhook Notifications</h3>
                            <div class="form-section">
                                <app-glass-checkbox
                                    formControlName="webhookEnabled"
                                    label="Enable Webhook Notifications">
                                </app-glass-checkbox>

                                @if (globalConfigForm.get('webhookEnabled')?.value) {
                                    <app-glass-input
                                        formControlName="webhookUrl"
                                        label="Webhook URL"
                                        placeholder="https://your-service.com/webhook"
                                        prefixIcon="link">
                                    </app-glass-input>
                                }
                            </div>
                        </div>

                        <div class="glass-card">
                            <h3>Slack Notifications</h3>
                            <div class="form-section">
                                <app-glass-checkbox
                                    formControlName="slackEnabled"
                                    label="Enable Slack Notifications">
                                </app-glass-checkbox>

                                @if (globalConfigForm.get('slackEnabled')?.value) {
                                    <app-glass-input
                                        formControlName="slackWebhookUrl"
                                        label="Slack Webhook URL"
                                        placeholder="https://hooks.slack.com/services/..."
                                        prefixIcon="chat">
                                    </app-glass-input>
                                }
                            </div>
                        </div>

                        <div class="form-actions">
                            <app-glass-button
                                type="submit"
                                variant="primary"
                                [disabled]="!globalConfigForm.valid"
                                [loading]="globalConfigLoading()">
                                Save Configuration
                            </app-glass-button>

                            @if (globalConfigSuccess()) {
                                <span class="success-message">
                                    <app-glass-icon name="check_circle" color="success" [size]="18"></app-glass-icon>
                                    Saved successfully
                                </span>
                            }
                        </div>
                    </form>
                }

                @if (activeTab() === 'alerts') {
                    @if (activeAlerts().length === 0) {
                        <div class="empty-state glass-card">
                            <app-glass-icon name="info" color="muted" [size]="48"></app-glass-icon>
                            <p>No active alerts</p>
                        </div>
                    } @else {
                        <div class="alerts-list">
                            @for (alert of activeAlerts(); track alert.alertId) {
                                <div class="glass-card" [class.alert-critical]="alert.severity === 'CRITICAL'">
                                    <div class="alert-header">
                                        <span class="alert-sender">{{ alert.senderName }}</span>
                                        <app-glass-icon
                                            [name]="getSeverityIcon(alert.severity)"
                                            [color]="getSeverityColor(alert.severity)"
                                            [size]="20">
                                        </app-glass-icon>
                                    </div>
                                    <div class="alert-details">
                                        <p><strong>{{ alert.alertType }}</strong></p>
                                        <p>Current Value: {{ alert.currentValue }} / Threshold: {{ alert.threshold }}</p>
                                        <p class="timestamp">{{ formatTime(alert.triggered_at) }}</p>
                                    </div>
                                    <div class="alert-actions">
                                        @if (!alert.acknowledged) {
                                            <app-glass-button
                                                variant="secondary"
                                                size="small"
                                                (clicked)="acknowledgeAlert(alert)">
                                                Acknowledge
                                            </app-glass-button>
                                        } @else {
                                            <span class="acknowledged">Acknowledged by {{ alert.acknowledged_by }}</span>
                                        }
                                    </div>
                                </div>
                            }
                        </div>
                    }
                }

                @if (activeTab() === 'templates') {
                    <form [formGroup]="thresholdTemplateForm" (ngSubmit)="saveTemplateThresholds()">
                        <div class="glass-card">
                            <h3>Backlog Threshold</h3>
                            <p>Alert when backlog exceeds this number of items</p>
                            <app-glass-input
                                formControlName="backlogThreshold"
                                type="number"
                                label="Default Backlog Threshold"
                                placeholder="1000">
                            </app-glass-input>
                        </div>

                        <div class="glass-card">
                            <h3>Failure Rate Threshold</h3>
                            <p>Alert when failure rate exceeds this percentage</p>
                            <app-glass-input
                                formControlName="failureRateThreshold"
                                type="number"
                                label="Default Failure Rate Threshold (%)"
                                placeholder="10">
                            </app-glass-input>
                        </div>

                        <div class="form-actions">
                            <app-glass-button
                                type="submit"
                                variant="primary"
                                [disabled]="!thresholdTemplateForm.valid"
                                [loading]="thresholdTemplateLoading()">
                                Save Threshold Templates
                            </app-glass-button>

                            @if (thresholdTemplateSuccess()) {
                                <span class="success-message">
                                    <app-glass-icon name="check_circle" color="success" [size]="18"></app-glass-icon>
                                    Templates saved
                                </span>
                            }
                        </div>
                    </form>
                }
            </div>
        </div>
    `,
    styles: [`
        .alert-config-container {
            padding: 2rem;
            max-width: 900px;

            h2 {
                margin-bottom: 2rem;
                font-size: 1.8rem;
            }
        }

        .tab-bar {
            display: flex;
            gap: 0.75rem;
            margin-bottom: 1rem;
            flex-wrap: wrap;
        }

        .tab-pill {
            border: 1px solid rgba(255, 255, 255, 0.14);
            background: rgba(255, 255, 255, 0.04);
            color: var(--text-muted);
            border-radius: 999px;
            padding: 0.5rem 0.9rem;
            font-weight: 600;
            cursor: pointer;
            transition: all 0.2s ease;
        }

        .tab-pill.active {
            background: rgba(129, 140, 248, 0.2);
            border-color: rgba(129, 140, 248, 0.45);
            color: var(--text-main);
            box-shadow: 0 0 16px rgba(129, 140, 248, 0.2);
        }

        .tab-content {
            padding: 0.25rem 0;
        }

        .glass-card {
            background: rgba(255, 255, 255, 0.04);
            border: 1px solid rgba(255, 255, 255, 0.12);
            border-radius: 16px;
            padding: 1.25rem;
            backdrop-filter: blur(14px);
            -webkit-backdrop-filter: blur(14px);
            margin-bottom: 1.5rem;

            &.alert-critical {
                border-left: 4px solid #ef4444;
            }

            h3 {
                margin: 0 0 0.75rem 0;
                font-size: 1.05rem;
            }

            p {
                color: var(--text-muted);
                margin-bottom: 1rem;
            }
        }

        .form-section {
            display: flex;
            flex-direction: column;
            gap: 1rem;
            margin-bottom: 1.5rem;
        }

        .form-actions {
            display: flex;
            gap: 1rem;
            align-items: center;
            flex-wrap: wrap;
            margin-top: 2rem;

            .success-message {
                display: flex;
                align-items: center;
                gap: 0.5rem;
                color: #10b981;
                font-weight: 500;
            }
        }

        .empty-state {
            display: flex;
            flex-direction: column;
            align-items: center;
            justify-content: center;
            padding: 3rem 1rem;
            color: var(--text-muted);
        }

        .alerts-list {
            display: flex;
            flex-direction: column;
            gap: 1rem;
        }

        .alert-header {
            display: flex;
            justify-content: space-between;
            align-items: center;
            margin-bottom: 1rem;

            .alert-sender {
                font-weight: 600;
                font-size: 1.1rem;
            }
        }

        .alert-details {
            margin-bottom: 1rem;

            p {
                margin: 0.5rem 0;
                font-size: 0.95rem;

                &.timestamp {
                    color: var(--text-muted);
                    font-size: 0.85rem;
                }
            }
        }

        .alert-actions {
            display: flex;
            gap: 0.5rem;

            .acknowledged {
                color: var(--text-muted);
                font-size: 0.9rem;
            }
        }

        @media (max-width: 768px) {
            .alert-config-container {
                padding: 1rem;
            }
        }
    `],
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class AlertConfigurationComponent implements OnInit {
    activeTab = signal<'global' | 'alerts' | 'templates'>('global');

    globalConfigForm: FormGroup;
    thresholdTemplateForm: FormGroup;

    globalConfigLoading = signal(false);
    globalConfigSuccess = signal(false);
    thresholdTemplateLoading = signal(false);
    thresholdTemplateSuccess = signal(false);
    activeAlerts = signal<SenderAlert[]>([]);

    constructor(
        private fb: FormBuilder,
        private backend: BackendService
    ) {
        this.globalConfigForm = this.fb.group({
            emailEnabled: [false],
            emailRecipients: ['', []],
            webhookEnabled: [false],
            webhookUrl: ['', []],
            slackEnabled: [false],
            slackWebhookUrl: ['', []]
        });

        this.thresholdTemplateForm = this.fb.group({
            backlogThreshold: [1000, [Validators.required, Validators.min(1)]],
            failureRateThreshold: [10, [Validators.required, Validators.min(0), Validators.max(100)]]
        });
    }

    ngOnInit(): void {
        this.loadGlobalConfig();
        this.loadActiveAlerts();
    }

    setActiveTab(tab: 'global' | 'alerts' | 'templates'): void {
        this.activeTab.set(tab);
    }

    private loadGlobalConfig(): void {
        this.backend.getAlertConfiguration().subscribe({
            next: (config: AlertConfiguration) => {
                this.globalConfigForm.patchValue({
                    emailEnabled: config.emailNotifications?.enabled || false,
                    emailRecipients: (config.emailNotifications?.recipients || []).join(', '),
                    webhookEnabled: config.webhookNotifications?.enabled || false,
                    webhookUrl: config.webhookNotifications?.url || '',
                    slackEnabled: config.slackNotifications?.enabled || false,
                    slackWebhookUrl: config.slackNotifications?.webhookUrl || ''
                });
            }
        });
    }

    private loadActiveAlerts(): void {
        // In a real app, this would load alerts from the backend
        // For now, we'll load from localStorage or mock data
        this.activeAlerts.set([]);
    }

    saveGlobalConfig(): void {
        if (!this.globalConfigForm.valid) return;

        this.globalConfigLoading.set(true);

        const emailRecipients = (this.globalConfigForm.get('emailRecipients')?.value || '')
            .split(',')
            .map((s: string) => s.trim())
            .filter((s: string) => s.length > 0);

        const config: AlertConfiguration = {
            emailNotifications: {
                enabled: this.globalConfigForm.get('emailEnabled')?.value || false,
                recipients: emailRecipients
            },
            webhookNotifications: {
                enabled: this.globalConfigForm.get('webhookEnabled')?.value || false,
                url: this.globalConfigForm.get('webhookUrl')?.value || ''
            },
            slackNotifications: {
                enabled: this.globalConfigForm.get('slackEnabled')?.value || false,
                webhookUrl: this.globalConfigForm.get('slackWebhookUrl')?.value || ''
            }
        };

        this.backend.updateAlertConfiguration(config).subscribe({
            next: () => {
                this.globalConfigLoading.set(false);
                this.globalConfigSuccess.set(true);
                setTimeout(() => this.globalConfigSuccess.set(false), 3000);
            },
            error: () => {
                this.globalConfigLoading.set(false);
            }
        });
    }

    saveTemplateThresholds(): void {
        if (!this.thresholdTemplateForm.valid) return;

        this.thresholdTemplateLoading.set(true);

        // Placeholder until backend endpoint for template thresholds is available.
        // Persist locally to maintain UX continuity.
        const values = this.thresholdTemplateForm.getRawValue();
        localStorage.setItem('resender.alertTemplates', JSON.stringify(values));

        setTimeout(() => {
            this.thresholdTemplateLoading.set(false);
            this.thresholdTemplateSuccess.set(true);
            setTimeout(() => this.thresholdTemplateSuccess.set(false), 2500);
        }, 300);
    }

    acknowledgeAlert(alert: SenderAlert): void {
        const nowIso = new Date().toISOString();
        this.activeAlerts.update((current: SenderAlert[]) =>
            current.map((item: SenderAlert) =>
                item.alertId === alert.alertId
                    ? {
                        ...item,
                        acknowledged: true,
                        acknowledged_by: 'Current User',
                        acknowledged_at: nowIso
                    }
                    : item
            )
        );
    }

    getSeverityIcon(severity: string): string {
        switch (severity) {
            case 'CRITICAL': return 'error';
            case 'WARNING': return 'warning';
            default: return 'info';
        }
    }

    getSeverityColor(severity: string): 'error' | 'warning' | 'primary' {
        switch (severity) {
            case 'CRITICAL': return 'error';
            case 'WARNING': return 'warning';
            default: return 'primary';
        }
    }

    formatTime(timestamp: string): string {
        const date = new Date(timestamp);
        const now = new Date();
        const diffMs = now.getTime() - date.getTime();
        const diffMins = Math.floor(diffMs / 60000);

        if (diffMins < 60) return `${diffMins}m ago`;
        const diffHours = Math.floor(diffMins / 60);
        if (diffHours < 24) return `${diffHours}h ago`;
        return date.toLocaleDateString();
    }
}
