import { Component, OnInit, signal, ChangeDetectionStrategy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ReactiveFormsModule, FormBuilder, FormGroup } from '@angular/forms';
import { BackendService, ScheduledReport } from '../../api/backend.service';
import { GlassButtonComponent } from './glass-button.component';
import { GlassInputComponent } from './glass-input.component';
import { GlassSelectComponent } from './glass-select.component';
import { GlassCheckboxComponent } from './glass-checkbox.component';
import { GlassIconComponent } from './glass-icon.component';

@Component({
    selector: 'app-export-reporting',
    standalone: true,
    imports: [
        CommonModule,
        ReactiveFormsModule,
        GlassButtonComponent,
        GlassInputComponent,
        GlassSelectComponent,
        GlassCheckboxComponent,
        GlassIconComponent
    ],
    template: `
        <div class="export-reporting-container">
            <h2>Export & Reporting</h2>

            <div class="export-section">
                <div class="glass-card">
                    <h3>Quick Export</h3>
                    <p class="card-subtitle">Export dashboard data immediately</p>
                    <p>Download current dashboard data in your preferred format:</p>
                    <div class="export-buttons">
                        <app-glass-button
                            variant="primary"
                            [loading]="exportLoading()"
                            (clicked)="exportDashboardCSV()">
                            Export as CSV
                        </app-glass-button>
                        <app-glass-button
                            variant="secondary"
                            [loading]="exportLoading()"
                            (clicked)="exportDashboardExcel()">
                            Export as Excel
                        </app-glass-button>
                    </div>
                </div>
            </div>

            <div class="scheduled-reports-section">
                <div class="glass-card">
                    <h3>Scheduled Reports</h3>
                    <p class="card-subtitle">Automatically receive reports on a schedule</p>

                    <div class="create-report-form">
                        <h4>Create New Report</h4>
                        <form [formGroup]="reportForm" (ngSubmit)="createReport()">
                            <div class="form-row">
                                <app-glass-input
                                    formControlName="name"
                                    label="Report Name"
                                    placeholder="Weekly Summary">
                                </app-glass-input>

                                <app-glass-input
                                    formControlName="description"
                                    label="Description"
                                    placeholder="Optional description">
                                </app-glass-input>
                            </div>

                            <div class="form-row three-col">
                                <app-glass-select
                                    formControlName="frequency"
                                    label="Frequency"
                                    [options]="frequencyOptions"
                                    placeholder="Select frequency">
                                </app-glass-select>

                                <app-glass-input
                                    formControlName="time"
                                    type="time"
                                    label="Time">
                                </app-glass-input>

                                <app-glass-select
                                    formControlName="format"
                                    label="Format"
                                    [options]="formatOptions"
                                    placeholder="Select format">
                                </app-glass-select>
                            </div>

                            <div class="form-row">
                                <app-glass-input
                                    formControlName="recipients"
                                    label="Recipients (comma-separated emails)"
                                    placeholder="user1@example.com, user2@example.com"
                                    prefixIcon="people">
                                </app-glass-input>
                            </div>

                            <div class="form-row">
                                <app-glass-checkbox formControlName="enabled" label="Enable Report"></app-glass-checkbox>
                            </div>

                            <div class="form-actions">
                                <app-glass-button
                                    type="submit"
                                    variant="primary"
                                    [loading]="reportCreating()"
                                    [disabled]="!reportForm.valid">
                                    Create Report
                                </app-glass-button>
                            </div>
                        </form>
                    </div>

                    @if (scheduledReports().length > 0) {
                        <div class="reports-list">
                            <h4>Active Reports</h4>
                            @for (report of scheduledReports(); track report.reportId) {
                                <div class="report-item glass-row">
                                    <div class="report-main">
                                        <div class="report-name">{{ report.name }}</div>
                                        <div class="report-meta">
                                            {{ report.frequency }} at {{ report.time }} •
                                            {{ report.format }} format •
                                            {{ report.recipients?.length || 0 }} recipient(s)
                                        </div>
                                    </div>
                                    <app-glass-icon
                                        [name]="report.enabled ? 'check_circle' : 'cancel'"
                                        [color]="report.enabled ? 'success' : 'muted'"
                                        [size]="20">
                                    </app-glass-icon>
                                </div>
                            }
                        </div>
                    }
                </div>
            </div>
        </div>
    `,
    styles: [`
        .export-reporting-container {
            padding: 2rem;
            max-width: 1000px;

            h2 {
                margin-bottom: 2rem;
                font-size: 1.8rem;
            }

            h3 {
                margin-bottom: 1.5rem;
                font-size: 1.2rem;
            }

            h4 {
                margin-bottom: 1rem;
                font-size: 1.05rem;
            }
        }

        .export-section,
        .scheduled-reports-section {
            margin-bottom: 2rem;
        }

        .glass-card {
            background: rgba(255, 255, 255, 0.04);
            border: 1px solid rgba(255, 255, 255, 0.12);
            border-radius: 16px;
            backdrop-filter: blur(14px);
            -webkit-backdrop-filter: blur(14px);
            padding: 1.25rem;
            margin-bottom: 1.5rem;
        }

        .card-subtitle {
            color: var(--text-muted);
            margin: -0.75rem 0 1rem;
        }

        .export-buttons {
            display: flex;
            gap: 1rem;
            flex-wrap: wrap;
            margin-top: 1rem;

            button {
                flex: 1;
                min-width: 200px;
            }
        }

        .create-report-form {
            margin-bottom: 2rem;
            padding: 1.5rem;
            background: rgba(255, 255, 255, 0.03);
            border: 1px solid rgba(255, 255, 255, 0.1);
            border-radius: 12px;
        }

        .form-row {
            display: flex;
            gap: 1rem;
            margin-bottom: 1rem;
            flex-wrap: wrap;

            app-glass-input,
            app-glass-select {
                flex: 1;
                min-width: 200px;
            }
        }

        .three-col {
            app-glass-select,
            app-glass-input {
                min-width: 180px;
            }
        }

        .form-actions {
            display: flex;
            gap: 1rem;
            margin-top: 1.5rem;
        }

        .reports-list {
            margin-top: 2rem;

            .report-item {
                display: flex;
                align-items: center;
                justify-content: space-between;
                gap: 1rem;
                padding: 0.9rem 1rem;
                border-radius: 12px;
                border: 1px solid rgba(255, 255, 255, 0.1);
                background: rgba(255, 255, 255, 0.03);
                margin-bottom: 0.65rem;
            }

            .report-name {
                font-weight: 600;
                margin-bottom: 0.2rem;
            }

            .report-meta {
                color: var(--text-muted);
                font-size: 0.9rem;
            }
        }

        @media (max-width: 768px) {
            .export-reporting-container {
                padding: 1rem;
            }

            .export-buttons {
                flex-direction: column;

                button {
                    width: 100%;
                }
            }

            .form-row {
                flex-direction: column;

                app-glass-input,
                app-glass-select {
                    width: 100%;
                }
            }
        }
    `],
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class ExportReportingComponent implements OnInit {
    reportForm: FormGroup;
    frequencyOptions = ['DAILY', 'WEEKLY', 'MONTHLY'];
    formatOptions = ['CSV', 'EXCEL', 'PDF'];

    exportLoading = signal(false);
    reportCreating = signal(false);
    scheduledReports = signal<ScheduledReport[]>([]);

    constructor(
        private fb: FormBuilder,
        private backend: BackendService
    ) {
        const now = new Date();
        const timeStr = `${String(now.getHours()).padStart(2, '0')}:00`;

        this.reportForm = this.fb.group({
            name: [''],
            description: [''],
            frequency: ['DAILY'],
            time: [timeStr],
            format: ['CSV'],
            recipients: [''],
            enabled: [true]
        });
    }

    ngOnInit(): void {
        this.loadScheduledReports();
    }

    exportDashboardCSV(): void {
        this.exportLoading.set(true);
        this.backend.exportDashboardCSV().subscribe({
            next: (blob: Blob) => this.downloadFile(blob, 'dashboard.csv'),
            error: () => this.exportLoading.set(false),
            complete: () => this.exportLoading.set(false)
        });
    }

    exportDashboardExcel(): void {
        this.exportLoading.set(true);
        this.backend.exportDashboardExcel().subscribe({
            next: (blob: Blob) => this.downloadFile(blob, 'dashboard.xlsx'),
            error: () => this.exportLoading.set(false),
            complete: () => this.exportLoading.set(false)
        });
    }

    createReport(): void {
        if (!this.reportForm.valid) return;

        this.reportCreating.set(true);

        const recipients = (this.reportForm.get('recipients')?.value || '')
            .split(',')
            .map((s: string) => s.trim())
            .filter((s: string) => s.length > 0);

        const report: ScheduledReport = {
            name: this.reportForm.get('name')?.value,
            description: this.reportForm.get('description')?.value,
            frequency: this.reportForm.get('frequency')?.value,
            time: this.reportForm.get('time')?.value,
            format: this.reportForm.get('format')?.value,
            recipients,
            enabled: this.reportForm.get('enabled')?.value,
            includeMetrics: ['backlog', 'completed', 'enqueued', 'ready']
        };

        this.backend.createScheduledReport(report).subscribe({
            next: (_created: ScheduledReport) => {
                this.reportCreating.set(false);
                this.loadScheduledReports();
                this.reportForm.reset({
                    frequency: 'DAILY',
                    time: `${String(new Date().getHours()).padStart(2, '0')}:00`,
                    format: 'CSV',
                    enabled: true
                });
            },
            error: () => this.reportCreating.set(false)
        });
    }

    private loadScheduledReports(): void {
        this.backend.getScheduledReports().subscribe({
            next: (reports: ScheduledReport[]) => this.scheduledReports.set(reports)
        });
    }

    private downloadFile(blob: Blob, filename: string): void {
        const url = window.URL.createObjectURL(blob);
        const link = document.createElement('a');
        link.href = url;
        link.download = filename;
        link.click();
        window.URL.revokeObjectURL(url);
    }
}
