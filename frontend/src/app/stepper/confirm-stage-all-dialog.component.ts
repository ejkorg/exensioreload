import { Component, Inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { GlassButtonComponent } from '../shared/components/glass-button.component';
import { GlassIconComponent } from '../shared/components/glass-icon.component';
import { GlassDialogRef, GLASS_DIALOG_DATA } from '../shared/services/glass-dialog.service';
import { SiteNamePipe } from '../shared/pipes/site-name.pipe';

export interface ConfirmStageAllDialogData {
    queryFilters: {
        site: string;
        environment: string;
        startDate?: string;
        endDate?: string;
        lots?: any;
        wafers?: any;
        pairs?: any;
        testerType?: string;
        dataType?: string;
        dataTypeExt?: string;
        testPhase?: string;
        location?: string;
        historicalMode: boolean;
    };
    totalDiscovered: number;
    selectedCount: number;
}

@Component({
    selector: 'app-confirm-stage-all-dialog',
    standalone: true,
    imports: [CommonModule, FormsModule, GlassButtonComponent, GlassIconComponent, SiteNamePipe],
    template: `
        <div class="confirm-dialog">
            <div class="dialog-header">
                <div class="header-title">
                    <app-glass-icon name="warning" class="warning-icon"></app-glass-icon>
                    <h2>Stage All {{ data.totalDiscovered | number }} Matching Files</h2>
                </div>
                <button class="close-btn" (click)="onCancel()" aria-label="Close dialog">×</button>
            </div>

            <div class="dialog-body">
                <p class="warning-text">
                    This will stage <strong>all {{ data.totalDiscovered | number }} files</strong> matching your discovery query and trigger immediate dispatch.
                </p>

                <div class="query-summary">
                    <h3>Query Parameters</h3>
                    <ul class="filter-list">
                        <li><strong>Site:</strong> {{ data.queryFilters.site | siteName }}</li>
                        <li><strong>Environment:</strong> {{ data.queryFilters.environment }}</li>
                        <li><strong>Location:</strong> {{ data.queryFilters.location || 'N/A' }}</li>
                        <li><strong>Data Type:</strong> {{ data.queryFilters.dataType || 'N/A' }}</li>
                        <li *ngIf="data.queryFilters.testerType"><strong>Tester Type:</strong> {{ data.queryFilters.testerType }}</li>
                        <li *ngIf="data.queryFilters.testPhase"><strong>Test Phase:</strong> {{ data.queryFilters.testPhase }}</li>
                        <li *ngIf="data.queryFilters.startDate"><strong>Date Range:</strong> {{ data.queryFilters.startDate }} to {{ data.queryFilters.endDate }}</li>
                        <li *ngIf="data.queryFilters.pairs && data.queryFilters.pairs.length"><strong>Lot/Wafer Pairs:</strong> {{ data.queryFilters.pairs.length }} pair(s)</li>
                    </ul>
                </div>

                <!-- Duplicate handling policy -->
                <div class="duplicate-policy">
                    <h3>Duplicate Handling</h3>
                    <p class="policy-hint">Some files may already be staged or processed by another user.</p>
                    <div class="policy-options">
                        <label class="policy-option" [class.selected]="duplicatePolicy === 'skip'">
                            <input type="radio" name="dupPolicy" value="skip"
                                   [(ngModel)]="duplicatePolicy" class="policy-radio" />
                            <div class="policy-content">
                                <span class="policy-title">Skip duplicates</span>
                                <span class="policy-desc">Only stage files not already staged by others. Safer — avoids re-sending data already in progress.</span>
                            </div>
                        </label>
                        <label class="policy-option" [class.selected]="duplicatePolicy === 'include'">
                            <input type="radio" name="dupPolicy" value="include"
                                   [(ngModel)]="duplicatePolicy" class="policy-radio" />
                            <div class="policy-content">
                                <span class="policy-title">Include duplicates</span>
                                <span class="policy-desc">Re-stage all files regardless of existing records. Use when you need a full re-send of the entire result set.</span>
                            </div>
                        </label>
                    </div>
                </div>
            </div>

            <div class="dialog-actions">
                <app-glass-button (click)="onCancel()" [variant]="'secondary'">Cancel</app-glass-button>
                <app-glass-button (click)="onConfirm()" [variant]="'danger'">
                    Stage All {{ data.totalDiscovered | number }} Files
                </app-glass-button>
            </div>
        </div>
    `,
    styles: [`
        .confirm-dialog {
            display: flex;
            flex-direction: column;
            gap: 1.5rem;
            padding: 1.5rem;
            min-width: 400px;
            max-width: 600px;
        }

        .dialog-header {
            display: flex;
            justify-content: space-between;
            align-items: flex-start;
            gap: 1rem;
        }

        .header-title {
            display: flex;
            align-items: center;
            gap: 0.75rem;

            h2 {
                margin: 0;
                font-size: 1.25rem;
                font-weight: 600;
                color: var(--text-primary);
            }
        }

        .warning-icon {
            font-size: 1.5rem;
            color: var(--warning-color, #ff9800);
        }

        .close-btn {
            background: none;
            border: none;
            font-size: 1.5rem;
            cursor: pointer;
            color: var(--text-secondary);
            padding: 0;
            width: 2rem;
            height: 2rem;
            display: flex;
            align-items: center;
            justify-content: center;
            border-radius: 4px;
            transition: all 0.2s ease;

            &:hover {
                background-color: var(--bg-hover);
                color: var(--text-primary);
            }
        }

        .dialog-body {
            display: flex;
            flex-direction: column;
            gap: 1rem;
        }

        .warning-text {
            color: var(--warning-color, #ff9800);
            font-weight: 500;
            margin: 0;
            padding: 0.75rem 1rem;
            background-color: var(--warning-bg, rgba(255, 152, 0, 0.1));
            border-radius: 4px;
            border-left: 3px solid var(--warning-color, #ff9800);
        }

        .query-summary {
            padding: 1rem;
            background-color: var(--bg-secondary);
            border-radius: 8px;

            h3 {
                margin: 0 0 0.75rem 0;
                font-size: 0.875rem;
                font-weight: 600;
                text-transform: uppercase;
                color: var(--text-secondary);
                letter-spacing: 0.5px;
            }

            .filter-list {
                list-style: none;
                margin: 0;
                padding: 0;
                display: flex;
                flex-direction: column;
                gap: 0.5rem;

                li {
                    font-size: 0.875rem;
                    color: var(--text-primary);

                    strong {
                        color: var(--text-secondary);
                        min-width: 100px;
                        display: inline-block;
                    }
                }
            }
        }

        .duplicate-policy {
            padding: 1rem;
            border-radius: 8px;
            border: 1px solid rgba(167, 139, 250, 0.2);
            background: rgba(30, 22, 68, 0.5);

            h3 {
                margin: 0 0 0.3rem 0;
                font-size: 0.875rem;
                font-weight: 700;
                text-transform: uppercase;
                letter-spacing: 0.05em;
                color: rgba(167, 139, 250, 0.85);
            }

            .policy-hint {
                margin: 0 0 0.85rem 0;
                font-size: 0.8rem;
                color: rgba(203, 213, 225, 0.6);
            }
        }

        .policy-options {
            display: flex;
            flex-direction: column;
            gap: 0.5rem;
        }

        .policy-option {
            display: flex;
            align-items: flex-start;
            gap: 0.75rem;
            padding: 0.75rem 0.875rem;
            border-radius: 8px;
            border: 1px solid rgba(167, 139, 250, 0.18);
            background: rgba(20, 16, 44, 0.5);
            cursor: pointer;
            transition: all 0.18s ease;

            &:hover { border-color: rgba(167, 139, 250, 0.4); background: rgba(99, 102, 241, 0.12); }
            &.selected { border-color: rgba(129, 140, 248, 0.6); background: rgba(99, 102, 241, 0.18); }
        }

        .policy-radio { margin-top: 0.15rem; flex-shrink: 0; accent-color: #818cf8; }

        .policy-content {
            display: flex;
            flex-direction: column;
            gap: 0.2rem;
        }

        .policy-title {
            font-size: 0.875rem;
            font-weight: 600;
            color: rgba(226, 232, 255, 0.95);
        }

        .policy-desc {
            font-size: 0.78rem;
            color: rgba(203, 213, 225, 0.65);
            line-height: 1.4;
        }

        .dialog-actions {
            display: flex;
            gap: 1rem;
            justify-content: flex-end;
            padding-top: 0.5rem;
            border-top: 1px solid var(--border-color);
        }

        @media (max-width: 600px) {
            .confirm-dialog { min-width: 100%; }
            .dialog-actions { flex-direction: column-reverse; }
            app-glass-button { width: 100%; }
        }
    `]
})
export class ConfirmStageAllDialogComponent {
    duplicatePolicy: 'skip' | 'include' = 'skip';

    constructor(
        public dialogRef: GlassDialogRef<ConfirmStageAllDialogComponent, boolean | { confirmed: boolean; forceDuplicates: boolean }>,
        @Inject(GLASS_DIALOG_DATA) public data: ConfirmStageAllDialogData
    ) {}

    onConfirm() {
        this.dialogRef.close({ confirmed: true, forceDuplicates: this.duplicatePolicy === 'include' });
    }

    onCancel() {
        this.dialogRef.close(false);
    }
}
