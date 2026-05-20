import { Component, Input, Output, EventEmitter, signal, computed } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { GlassSelectComponent, GlassOption } from './glass-select.component';
import { GlassIconComponent } from './glass-icon.component';
import { GlassButtonComponent } from './glass-button.component';
import { SenderOption } from '../../api/backend.service';

@Component({
    selector: 'app-glass-sender-selector',
    standalone: true,
    imports: [CommonModule, FormsModule, GlassSelectComponent, GlassIconComponent, GlassButtonComponent],
    template: `
        <div class="sender-selector" [class.sender-selector--auto-resolved]="autoResolved()">
            <!-- Auto-resolved display (non-editable) -->
            <div *ngIf="autoResolved() && selectedId() && !showDropdown()" class="sender-auto-resolved">
                <div class="sender-auto-resolved__header">
                    <app-glass-icon name="check_circle" [size]="20" color="success"></app-glass-icon>
                    <span class="sender-auto-resolved__label">Sender Auto-Resolved</span>
                </div>
                <div class="sender-auto-resolved__value">
                    {{ selectedName() || ('Sender #' + selectedId()) }}
                </div>
                <div class="sender-auto-resolved__note">
                    Automatically matched based on your filter selection
                </div>
                <app-glass-button
                    variant="secondary"
                    size="small"
                    (clicked)="showDropdown.set(true)">
                    <app-glass-icon name="edit" [size]="16"></app-glass-icon>
                    Change Sender
                </app-glass-button>
            </div>

            <!-- Loading state -->
            <div *ngIf="loading()" class="sender-loading">
                <div class="sender-loading__spinner"></div>
                <span class="sender-loading__text">Searching for matching senders...</span>
            </div>

            <!-- Dropdown selector (fallback or manual selection) -->
            <div *ngIf="(showDropdown() || fallback()) && !loading() && !autoResolved()" class="sender-dropdown">
                <app-glass-select
                    label="Select Sender"
                    placeholder="Choose a sender"
                    [ngModel]="selectedId()"
                    (ngModelChange)="onSenderChange($event)"
                    [options]="senderOptionsForSelect()"
                    [disabled]="loading()">
                </app-glass-select>
                <div *ngIf="options().length === 0" class="sender-dropdown__empty">
                    <app-glass-icon name="warning" [size]="24" color="warning"></app-glass-icon>
                    <p>No senders available for the selected filters</p>
                    <p class="sender-dropdown__hint">Try adjusting your filter selection</p>
                </div>
            </div>

            <!-- Manual trigger button (when no auto-resolution) -->
            <div *ngIf="!autoResolved() && !loading() && !selectedId() && showFindButton()" class="sender-find">
                <app-glass-button
                    variant="primary"
                    size="medium"
                    (clicked)="findSender.emit()"
                    [disabled]="!canFind()">
                    <app-glass-icon name="search" [size]="18"></app-glass-icon>
                    Find Sender
                </app-glass-button>
                <p class="sender-find__hint">
                    Click to search for matching senders based on your filters
                </p>
            </div>
        </div>
    `,
    styles: [`
        .sender-selector {
            display: flex;
            flex-direction: column;
            gap: 1rem;
            padding: 1.5rem;
            background: rgba(255, 255, 255, 0.03);
            border: 1px solid rgba(255, 255, 255, 0.1);
            border-radius: 16px;
            backdrop-filter: blur(12px);
            transition: all 0.3s cubic-bezier(0.4, 0, 0.2, 1);
        }

        :host-context(body.light-theme) .sender-selector {
            background: rgba(255, 255, 255, 0.9);
            border: 1px solid rgba(0, 0, 0, 0.12);
        }

        .sender-selector--auto-resolved {
            border-color: rgba(16, 185, 129, 0.3);
            background: rgba(16, 185, 129, 0.05);
        }

        :host-context(body.light-theme) .sender-selector--auto-resolved {
            border-color: rgba(16, 185, 129, 0.4);
            background: rgba(16, 185, 129, 0.08);
        }

        /* Auto-resolved display */
        .sender-auto-resolved {
            display: flex;
            flex-direction: column;
            gap: 0.75rem;
        }

        .sender-auto-resolved__header {
            display: flex;
            align-items: center;
            gap: 0.5rem;
            font-size: 0.875rem;
            font-weight: 600;
            color: #10b981;
        }

        .sender-auto-resolved__value {
            font-size: 1.125rem;
            font-weight: 700;
            color: var(--text-primary);
            padding: 0.75rem 1rem;
            background: rgba(255, 255, 255, 0.05);
            border: 1px solid rgba(255, 255, 255, 0.1);
            border-radius: 12px;
        }

        :host-context(body.light-theme) .sender-auto-resolved__value {
            color: var(--text-main);
            background: rgba(255, 255, 255, 0.95);
            border: 1px solid rgba(0, 0, 0, 0.12);
        }

        .sender-auto-resolved__note {
            font-size: 0.875rem;
            color: var(--text-muted);
            font-style: italic;
        }

        /* Loading state */
        .sender-loading {
            display: flex;
            align-items: center;
            gap: 1rem;
            padding: 1rem;
            background: rgba(59, 130, 246, 0.05);
            border: 1px solid rgba(59, 130, 246, 0.2);
            border-radius: 12px;
        }

        :host-context(body.light-theme) .sender-loading {
            background: rgba(59, 130, 246, 0.08);
            border: 1px solid rgba(59, 130, 246, 0.25);
        }

        .sender-loading__spinner {
            width: 24px;
            height: 24px;
            border: 3px solid rgba(59, 130, 246, 0.2);
            border-top-color: #3b82f6;
            border-radius: 50%;
            animation: spin 0.8s linear infinite;
        }

        @keyframes spin {
            to { transform: rotate(360deg); }
        }

        .sender-loading__text {
            font-size: 0.875rem;
            color: #3b82f6;
            font-weight: 500;
        }

        /* Dropdown selector */
        .sender-dropdown {
            display: flex;
            flex-direction: column;
            gap: 1rem;
        }

        .sender-dropdown__empty {
            display: flex;
            flex-direction: column;
            align-items: center;
            gap: 0.75rem;
            padding: 2rem 1rem;
            text-align: center;
        }

        .sender-dropdown__empty p {
            margin: 0;
            color: var(--text-primary);
            font-weight: 500;
        }

        .sender-dropdown__hint {
            font-size: 0.875rem;
            color: var(--text-muted) !important;
            font-weight: 400 !important;
        }

        /* Find button */
        .sender-find {
            display: flex;
            flex-direction: column;
            align-items: center;
            gap: 0.75rem;
            padding: 1.5rem 1rem;
        }

        .sender-find__hint {
            margin: 0;
            font-size: 0.875rem;
            color: var(--text-muted);
            text-align: center;
        }

        /* Responsive */
        @media (max-width: 768px) {
            .sender-selector {
                padding: 1rem;
            }

            .sender-auto-resolved__value {
                font-size: 1rem;
            }
        }
    `]
})
export class GlassSenderSelectorComponent {
    @Input() set senderOptions(value: SenderOption[]) {
        this._senderOptions.set(value || []);
    }
    @Input() set selectedSenderId(value: number | null) {
        this.selectedId.set(value);
    }
    @Input() set selectedSenderName(value: string | null) {
        this.selectedName.set(value);
    }
    @Input() set senderAutoResolved(value: boolean) {
        this.autoResolved.set(value);
    }
    @Input() set senderLookupLoading(value: boolean) {
        this.loading.set(value);
    }
    @Input() set senderFallback(value: boolean) {
        this.fallback.set(value);
    }
    @Input() set canFindSender(value: boolean) {
        this.canFind.set(value);
    }
    @Input() set showFindSenderButton(value: boolean) {
        this.showFindButton.set(value);
    }

    @Output() senderSelected = new EventEmitter<number | null>();
    @Output() findSender = new EventEmitter<void>();

    // Internal state
    private _senderOptions = signal<SenderOption[]>([]);
    selectedId = signal<number | null>(null);
    selectedName = signal<string | null>(null);
    autoResolved = signal(false);
    loading = signal(false);
    fallback = signal(false);
    canFind = signal(true);
    showFindButton = signal(true);
    showDropdown = signal(false);

    // Computed values - convert SenderOption[] to GlassOption[] for the select component
    senderOptionsForSelect = computed(() => {
        return this._senderOptions().map(opt => ({
            value: opt.idSender ?? opt.id,
            label: opt.name || `Sender #${opt.idSender ?? opt.id}`
        } as GlassOption));
    });

    // Expose options for template
    options() {
        return this._senderOptions();
    }

    onSenderChange(senderId: number | null) {
        this.selectedId.set(senderId);
        this.senderSelected.emit(senderId);
        this.showDropdown.set(false);
    }
}
