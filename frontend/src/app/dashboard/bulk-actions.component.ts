import { Component, input, Output, EventEmitter, signal, computed, ChangeDetectionStrategy, effect } from '@angular/core';
import { CommonModule } from '@angular/common';
import { trigger, transition, style, animate } from '@angular/animations';
import { BackendService } from '../api/backend.service';
import { GlassButtonComponent } from '../shared/components/glass-button.component';
import { GlassCheckboxComponent } from '../shared/components/glass-checkbox.component';
import { GlassIconComponent } from '../shared/components/glass-icon.component';

export interface SelectableItem {
    id: number;
    type: 'sender' | 'site';
    label: string;
    /** NEW records still cancellable (not yet dispatched) */
    cancellableCount: number;
    /** ENQUEUED records already in the sender queue — cannot be cancelled */
    enqueuedCount: number;
}

@Component({
    selector: 'app-bulk-actions',
    standalone: true,
    imports: [
        CommonModule,
        GlassButtonComponent,
        GlassCheckboxComponent,
        GlassIconComponent
    ],
    template: `
        <!-- Bulk Action Floating Bar (shown when items selected) -->
        @if (selectedItems().size > 0) {
            <div class="bulk-action-bar" [@slideUp]>
                <div class="bulk-action-left">
                    <app-glass-checkbox
                        [checked]="allItemsSelected()"
                        [indeterminate]="someItemsSelected() && !allItemsSelected()"
                        [label]="''"
                        (checkedChange)="toggleSelectAll()">
                    </app-glass-checkbox>
                    <span class="selection-info">
                        {{ selectedItems().size }}
                        {{ selectedItems().size === 1 ? 'item' : 'items' }} selected
                    </span>
                </div>

                <div class="bulk-action-center">
                    @if (operationStatus() === 'IN_PROGRESS') {
                        <span class="loader"></span>
                        <span class="status-text">{{ operationMessage() }}</span>
                    } @else if (operationStatus() === 'SUCCESS') {
                        <app-glass-icon class="success-icon" name="check_circle" color="success" [size]="20"></app-glass-icon>
                        <span class="status-text success">{{ operationMessage() }}</span>
                    } @else if (operationStatus() === 'ERROR') {
                        <app-glass-icon class="error-icon" name="error" color="error" [size]="20"></app-glass-icon>
                        <span class="status-text error">{{ operationMessage() }}</span>
                    }
                </div>

                <div class="bulk-action-buttons">
                    <app-glass-button
                        variant="secondary"
                        size="small"
                        [disabled]="isOperationInProgress()"
                        (clicked)="performBulkResume()">
                        Resume
                    </app-glass-button>

                    <app-glass-button
                        variant="secondary"
                        size="small"
                        [disabled]="isOperationInProgress() || nothingCancellable()"
                        [title]="nothingCancellable() ? 'No pending (NEW) records to pause — all records are already dispatched or completed' : ''"
                        (clicked)="performBulkPause()">
                        Pause
                    </app-glass-button>

                    <app-glass-button
                        variant="secondary"
                        size="small"
                        [disabled]="isOperationInProgress()"
                        (clicked)="performBulkExport('csv')">
                        Export CSV
                    </app-glass-button>

                    <app-glass-button
                        variant="danger"
                        size="small"
                        [disabled]="isOperationInProgress() || nothingCancellable()"
                        [title]="nothingCancellable() ? 'No cancellable records — ENQUEUED and DONE records cannot be deleted' : ''"
                        (clicked)="confirmBulkDelete()">
                        Delete
                    </app-glass-button>

                    <app-glass-button
                        variant="tertiary"
                        size="small"
                        (clicked)="clearSelection()">
                        Clear
                    </app-glass-button>
                </div>
            </div>
        }

        <!-- Delete Confirmation Dialog -->
        @if (showDeleteConfirm()) {
            <div class="delete-confirm-overlay" (click)="showDeleteConfirm.set(false)">
                <div class="delete-confirm-dialog" (click)="$event.stopPropagation()">
                    <h3>Confirm Delete</h3>
                    <p>
                        This will cancel <strong>{{ selectedCancellableCount() }} pending record(s)</strong>
                        (status: NEW or FAILED) for {{ selectedItems().size }} selected sender(s).
                    </p>
                    @if (selectedEnqueuedCount() > 0) {
                        <div class="enqueued-warning">
                            <span class="warn-icon">⚠</span>
                            <span>
                                <strong>{{ selectedEnqueuedCount() }} record(s) are already dispatched</strong>
                                (ENQUEUED) and cannot be cancelled — they have been sent to the external
                                sender queue and may already be processing.
                            </span>
                        </div>
                    }
                    <div class="dialog-actions">
                        <app-glass-button variant="secondary" (clicked)="showDeleteConfirm.set(false)">
                            Cancel
                        </app-glass-button>
                        <app-glass-button variant="danger" (clicked)="performBulkDelete()">
                            Delete {{ selectedCancellableCount() }} record(s)
                        </app-glass-button>
                    </div>
                </div>
            </div>
        }
    `,
    styles: [`
        .bulk-action-bar {
            position: fixed;
            bottom: 0;
            left: 0;
            right: 0;
            height: 64px;
            background: rgba(15, 23, 42, 0.78);
            border-top: 1px solid rgba(255, 255, 255, 0.14);
            backdrop-filter: blur(18px);
            -webkit-backdrop-filter: blur(18px);
            display: flex;
            align-items: center;
            justify-content: space-between;
            padding: 0 1.5rem;
            gap: 2rem;
            z-index: 1000;
            box-shadow: 0 -4px 20px rgba(0, 0, 0, 0.2);

            @media (max-width: 768px) {
                height: 56px;
                padding: 0 1rem;
                gap: 1rem;
            }
        }

        :host-context(body.light-theme) .bulk-action-bar {
            background: rgba(255, 255, 255, 0.92);
            border-top: 1px solid rgba(15, 23, 42, 0.12);
            color: #0f172a;
            box-shadow: 0 -6px 24px rgba(15, 23, 42, 0.08);
        }

        :host-context(body.light-theme) .selection-info,
        :host-context(body.light-theme) .status-text {
            color: #0f172a;
        }

        :host-context(body.light-theme) .delete-confirm-dialog {
            background: rgba(255, 255, 255, 0.96);
            border: 1px solid rgba(15, 23, 42, 0.12);
        }

        .bulk-action-left {
            display: flex;
            align-items: center;
            gap: 1rem;
            min-width: 150px;

            .selection-info {
                font-weight: 600;
                color: white;
                font-size: 0.95rem;
            }
        }

        .bulk-action-center {
            flex: 1;
            display: flex;
            align-items: center;
            justify-content: center;
            gap: 0.75rem;
            min-height: 24px;

            .status-text {
                color: white;
                font-weight: 500;
                font-size: 0.9rem;

                &.success {
                    color: #e8f5e9;
                }

                &.error {
                    color: #ffebee;
                }
            }

            .success-icon {
                color: #e8f5e9;
            }

            .error-icon {
                color: #ffebee;
            }

            .loader {
                width: 18px;
                height: 18px;
                border: 2px solid rgba(255, 255, 255, 0.25);
                border-top-color: rgba(255, 255, 255, 0.95);
                border-radius: 50%;
                display: inline-block;
                animation: spin 0.8s linear infinite;
            }
        }

        .bulk-action-buttons {
            display: flex;
            gap: 0.5rem;
            align-items: center;
        }

        .delete-confirm-overlay {
            position: fixed;
            top: 0;
            left: 0;
            right: 0;
            bottom: 0;
            background: rgba(0, 0, 0, 0.5);
            display: flex;
            align-items: center;
            justify-content: center;
            z-index: 1100;
            animation: fadeIn 0.2s ease-out;
        }

        .delete-confirm-dialog {
            background: rgba(15, 23, 42, 0.82);
            border: 1px solid rgba(255, 255, 255, 0.15);
            border-radius: 16px;
            backdrop-filter: blur(20px);
            -webkit-backdrop-filter: blur(20px);
            padding: 2rem;
            max-width: 400px;
            box-shadow: 0 10px 40px rgba(0, 0, 0, 0.3);
            animation: slideUp 0.3s ease-out;

            h3 {
                margin: 0 0 1rem 0;
                font-size: 1.3rem;
                color: #ef4444;
            }

            p {
                margin: 0 0 1.5rem 0;
                color: var(--text-muted);
                line-height: 1.5;
            }

            .dialog-actions {
                display: flex;
                gap: 1rem;
                justify-content: flex-end;

                button {
                    min-width: 100px;
                }
            }
        }

        .enqueued-warning {
            display: flex;
            gap: 0.6rem;
            align-items: flex-start;
            background: rgba(234, 179, 8, 0.12);
            border: 1px solid rgba(234, 179, 8, 0.35);
            border-radius: 8px;
            padding: 0.75rem 1rem;
            margin-bottom: 1.25rem;
            font-size: 0.875rem;
            color: #fef08a;
            line-height: 1.5;

            .warn-icon {
                font-size: 1rem;
                flex-shrink: 0;
                margin-top: 1px;
            }
        }

        @keyframes spin {
            to {
                transform: rotate(360deg);
            }
        }

        @keyframes fadeIn {
            from {
                opacity: 0;
            }
            to {
                opacity: 1;
            }
        }

        @keyframes slideUp {
            from {
                transform: translateY(20px);
                opacity: 0;
            }
            to {
                transform: translateY(0);
                opacity: 1;
            }
        }
    `],
    animations: [
        trigger('slideUp', [
            transition(':enter', [
                style({ transform: 'translateY(100%)', opacity: 0 }),
                animate('300ms ease-out', style({ transform: 'translateY(0)', opacity: 1 }))
            ]),
            transition(':leave', [
                animate('200ms ease-in', style({ transform: 'translateY(100%)', opacity: 0 }))
            ])
        ])
    ],
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class BulkActionsComponent {
    /** Plain SelectableItem[] passed from the parent template — wrapped as an InputSignal so it
     *  remains callable as this.allItems() inside computed() / effects. */
    allItems = input<SelectableItem[]>([]);
    /** Optional selection set from parent (dashboard card checkboxes) */
    selectedIds = input<Set<number> | null>(null);
    @Output() selectionChanged = new EventEmitter<Set<number>>();

    selectedItems = signal<Set<number>>(new Set<number>());
    showDeleteConfirm = signal(false);

    operationStatus = signal<'IDLE' | 'IN_PROGRESS' | 'SUCCESS' | 'ERROR'>('IDLE');
    operationMessage = signal('');

    private clearStatusTimeout: any;

    constructor(private backend: BackendService) {
        effect(() => {
            const incoming = this.selectedIds();
            if (!incoming) {
                return;
            }

            if (!this.areSetsEqual(this.selectedItems(), incoming)) {
                this.selectedItems.set(new Set(incoming));
            }
        });
    }

    someItemsSelected = computed(() => {
        return this.selectedItems().size > 0 && this.selectedItems().size < this.allItems().length;
    });

    allItemsSelected = computed(() => {
        return this.allItems().length > 0 && this.selectedItems().size === this.allItems().length;
    });

    isOperationInProgress = computed(() => {
        return this.operationStatus() === 'IN_PROGRESS';
    });

    /** Total NEW (cancellable) records across all selected items */
    selectedCancellableCount = computed(() => {
        const selected = this.selectedItems();
        return this.allItems()
            .filter((item: SelectableItem) => selected.has(item.id))
            .reduce((sum: number, item: SelectableItem) => sum + (item.cancellableCount ?? 0), 0);
    });

    /** Total ENQUEUED (in-flight, non-cancellable) records across all selected items */
    selectedEnqueuedCount = computed(() => {
        const selected = this.selectedItems();
        return this.allItems()
            .filter((item: SelectableItem) => selected.has(item.id))
            .reduce((sum: number, item: SelectableItem) => sum + (item.enqueuedCount ?? 0), 0);
    });

    /** True when selected senders have nothing that can be cancelled */
    nothingCancellable = computed(() => this.selectedCancellableCount() === 0 && this.selectedItems().size > 0);

    toggleSelectAll(): void {
        if (this.allItemsSelected()) {
            this.selectedItems.set(new Set());
        } else {
            const all = new Set<number>(this.allItems().map((item: SelectableItem) => item.id));
            this.selectedItems.set(all);
        }
        this.selectionChanged.emit(this.selectedItems());
    }

    toggleItem(itemId: number): void {
        const updated = new Set(this.selectedItems());
        if (updated.has(itemId)) {
            updated.delete(itemId);
        } else {
            updated.add(itemId);
        }
        this.selectedItems.set(updated);
        this.selectionChanged.emit(updated);
    }

    clearSelection(): void {
        this.selectedItems.set(new Set());
        this.selectionChanged.emit(new Set());
    }

    performBulkResume(): void {
        const senderIds: number[] = Array.from(this.selectedItems().values());
        if (senderIds.length === 0) return;

        this.operationStatus.set('IN_PROGRESS');
        this.operationMessage.set('Resuming monitoring...');

        this.backend.bulkResumeMonitoring(senderIds).subscribe({
            next: (result: { success: number; failed: number; message: string }) => {
                this.operationStatus.set('SUCCESS');
                this.operationMessage.set(`Resumed ${result.success} sender(s)`);
                this.scheduleStatusClear();
                this.selectedItems.set(new Set());
            },
            error: () => {
                this.operationStatus.set('ERROR');
                this.operationMessage.set('Failed to resume monitoring');
                this.scheduleStatusClear();
            }
        });
    }

    performBulkPause(): void {
        const senderIds: number[] = Array.from(this.selectedItems().values());
        if (senderIds.length === 0) return;

        this.operationStatus.set('IN_PROGRESS');
        this.operationMessage.set('Pausing monitoring...');

        this.backend.bulkPauseMonitoring(senderIds).subscribe({
            next: (result: { success: number; failed: number; message: string }) => {
                this.operationStatus.set('SUCCESS');
                this.operationMessage.set(`Paused ${result.success} sender(s)`);
                this.scheduleStatusClear();
                this.selectedItems.set(new Set());
            },
            error: () => {
                this.operationStatus.set('ERROR');
                this.operationMessage.set('Failed to pause monitoring');
                this.scheduleStatusClear();
            }
        });
    }

    performBulkExport(format: 'csv' | 'excel'): void {
        const senderIds: number[] = Array.from(this.selectedItems().values());
        if (senderIds.length === 0) return;

        this.operationStatus.set('IN_PROGRESS');
        this.operationMessage.set(`Exporting as ${format.toUpperCase()}...`);

        this.backend.bulkExportData(senderIds, format).subscribe({
            next: (blob: Blob) => {
                // Create download link
                const url = window.URL.createObjectURL(blob);
                const link = document.createElement('a');
                link.href = url;
                link.download = `senders-export-${Date.now()}.${format === 'csv' ? 'csv' : 'xlsx'}`;
                link.click();
                window.URL.revokeObjectURL(url);

                this.operationStatus.set('SUCCESS');
                this.operationMessage.set(`Exported ${senderIds.length} sender(s)`);
                this.scheduleStatusClear();
                this.selectedItems.set(new Set());
            },
            error: () => {
                this.operationStatus.set('ERROR');
                this.operationMessage.set('Failed to export data');
                this.scheduleStatusClear();
            }
        });
    }

    confirmBulkDelete(): void {
        this.showDeleteConfirm.set(true);
    }

    performBulkDelete(): void {
        const senderIds: number[] = Array.from(this.selectedItems().values());
        if (senderIds.length === 0) return;

        this.showDeleteConfirm.set(false);
        this.operationStatus.set('IN_PROGRESS');
        this.operationMessage.set('Deleting data...');

        this.backend.bulkDeleteData(senderIds).subscribe({
            next: (result: { success: number; failed: number; message: string }) => {
                this.operationStatus.set('SUCCESS');
                this.operationMessage.set(`Deleted data for ${result.success} sender(s)`);
                this.scheduleStatusClear();
                this.selectedItems.set(new Set());
            },
            error: () => {
                this.operationStatus.set('ERROR');
                this.operationMessage.set('Failed to delete data');
                this.scheduleStatusClear();
            }
        });
    }

    private scheduleStatusClear(): void {
        if (this.clearStatusTimeout) {
            clearTimeout(this.clearStatusTimeout);
        }
        this.clearStatusTimeout = setTimeout(() => {
            this.operationStatus.set('IDLE');
            this.operationMessage.set('');
        }, 3000);
    }

    private areSetsEqual(a: Set<number>, b: Set<number>): boolean {
        if (a.size !== b.size) return false;
        for (const value of a) {
            if (!b.has(value)) return false;
        }
        return true;
    }
}
