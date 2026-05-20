# Phase 4 Dashboard Component Code Examples

**Last Updated:** April 16, 2026  
**Purpose:** Detailed code snippets for integrating Phase 4 components

---

## Dashboard Component Signals Update

Add these signals to track bulk operations and selection:

```typescript
// Phase 4: Bulk Operations (4.2)
selectedSenderIds = signal(new Set<number>());
selectedSiteNames = signal(new Set<string>());

// Computed: All selectable items for bulk operations
allSelectableItems = computed(() => {
    const items: SelectableItem[] = [];
    const snap = this.snapshot();
    
    if (!snap?.sites) return items;
    
    snap.sites.forEach(site => {
        // Add each sender as a selectable item
        (site.senders || []).forEach(sender => {
            items.push({
                id: sender.senderId,
                type: 'sender',
                label: `${sender.senderLabel} (${site.site})`
            });
        });
    });
    
    return items;
});

// Computed: Check if any items are selected
hasSelection = computed(() => {
    return this.selectedSenderIds().size > 0 || this.selectedSiteNames().size > 0;
});

// Computed: Total selected count
selectionCount = computed(() => {
    return this.selectedSenderIds().size + this.selectedSiteNames().size;
});
```

---

## Dashboard Component Methods Update

Add these methods for bulk operations interaction:

```typescript
/**
 * Handle bulk selection changes from bulk-actions component
 * @param selected Set of selected sender IDs
 */
onBulkSelectionChanged(selected: Set<number>): void {
    this.selectedSenderIds.set(new Set(selected));
}

/**
 * Toggle selection of a specific sender
 * @param senderId Sender ID to toggle
 */
toggleSenderSelection(senderId: number): void {
    const current = new Set(this.selectedSenderIds());
    if (current.has(senderId)) {
        current.delete(senderId);
    } else {
        current.add(senderId);
    }
    this.selectedSenderIds.set(current);
}

/**
 * Check if a sender is currently selected
 * @param senderId Sender ID to check
 */
isSenderSelected(senderId: number): boolean {
    return this.selectedSenderIds().has(senderId);
}

/**
 * Clear all selections
 */
clearAllSelections(): void {
    this.selectedSenderIds.set(new Set());
    this.selectedSiteNames.set(new Set());
}
```

---

## HTML Template Updates

### Add Bulk Actions Component

At the **bottom of dashboard-container**, before the closing `</div>`:

```html
<!-- Phase 4.2: Bulk Actions Floating Bar -->
@if (hasSelection() && !loading() && !error()) {
    <app-bulk-actions 
        #bulkActionsRef
        [allItems]="allSelectableItems()"
        (selectionChanged)="onBulkSelectionChanged($event)"
        [@fadeIn]>
    </app-bulk-actions>
}
```

### Add Checkbox to Sender Cards

Find the sender card loop and wrap with selection:

```html
<!-- Before: Original sender card div -->
<!-- After: Add wrapper with checkbox -->

<div class="sender-card-item" [class.selected]="isSenderSelected(sender.senderId)">
    <!-- Selection Checkbox -->
    <div class="sender-selection">
        <mat-checkbox 
            [checked]="isSenderSelected(sender.senderId)"
            (change)="toggleSenderSelection(sender.senderId)"
            [attr.aria-label]="'Select sender: ' + sender.senderLabel"
            class="sender-checkbox">
        </mat-checkbox>
    </div>

    <!-- Original Sender Card -->
    <div class="sender-card" 
         [class.top-sender]="index === 0"
         [class.critical]="sender.isCritical"
         [class.updated]="changedMetrics().has(`sender-${sender.senderId}`)">
        <!-- ... existing sender card content ... -->
    </div>
</div>
```

### Add Selection Summary

Add to dashboard header, next to existing header actions:

```html
<!-- Selection Summary Display -->
@if (hasSelection()) {
    <div class="selection-summary" [@slideDown]>
        <mat-icon>info</mat-icon>
        <span class="summary-text">
            {{ selectionCount() }} item(s) selected
        </span>
        <button mat-icon-button 
                (click)="clearAllSelections()"
                matTooltip="Clear selection"
                aria-label="Clear all selections">
            <mat-icon>close</mat-icon>
        </button>
    </div>
}
```

---

## SCSS Styling Updates

Add these styles to dashboard.component.scss:

```scss
// Phase 4: Bulk Operations Styling

.sender-card-item {
    display: flex;
    gap: 1rem;
    align-items: flex-start;
    margin-bottom: 1rem;
    transition: all 0.3s ease;

    &.selected {
        .sender-card {
            box-shadow: 0 0 0 2px #10b981, 0 4px 20px rgba(16, 185, 129, 0.2);
            border-color: #10b981;
        }
    }
}

.sender-selection {
    display: flex;
    align-items: center;
    padding-top: 0.5rem;
    flex-shrink: 0;

    .sender-checkbox {
        margin: 0;
    }
}

.selection-summary {
    display: flex;
    align-items: center;
    gap: 1rem;
    padding: 0.75rem 1.5rem;
    background: linear-gradient(135deg, rgba(16, 185, 129, 0.1), rgba(52, 211, 153, 0.1));
    border-left: 3px solid #10b981;
    border-radius: 8px;
    margin: 1rem 0;
    animation: slideDown 0.3s ease-out;

    mat-icon {
        color: #10b981;
    }

    .summary-text {
        flex: 1;
        font-weight: 500;
        color: #047857;
    }

    button {
        color: #10b981;

        &:hover {
            background: rgba(16, 185, 129, 0.1);
        }
    }
}

// Responsive: Hide checkboxes on very small screens
@media (max-width: 480px) {
    .sender-card-item {
        flex-direction: column;
        gap: 0.5rem;
    }

    .sender-selection {
        padding-top: 0;
        order: -1; // Move checkbox before card
    }
}
```

---

## Module Imports Update

Update dashboard.component.ts imports:

```typescript
import { Component, OnInit, OnDestroy, signal, computed, ChangeDetectionStrategy, ViewChild } from '@angular/core';
import { CommonModule } from '@angular/common';
import { BackendService, DashboardSnapshot, DashboardSiteSnapshot, DashboardSenderSnapshot, StagingSessionDetail } from '../api/backend.service';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatCheckboxModule } from '@angular/material/checkbox'; // NEW
import { Subscription, timer } from 'rxjs';
import { Router, RouterModule } from '@angular/router';
import { GlassDialogService } from '../shared/services/glass-dialog.service';
import { SiteDetailModalComponent } from './site-detail-modal.component';
import { SparklineComponent } from '../shared/components/sparkline.component';
import { BulkActionsComponent, SelectableItem } from './bulk-actions.component'; // NEW
import { trigger, transition, style, animate } from '@angular/animations'; // NEW

@Component({
    selector: 'app-dashboard',
    standalone: true,
    imports: [
        CommonModule,
        MatIconModule,
        MatButtonModule,
        MatTooltipModule,
        MatProgressBarModule,
        MatCheckboxModule, // NEW
        RouterModule,
        SparklineComponent,
        BulkActionsComponent // NEW
    ],
    templateUrl: './dashboard.component.html',
    styleUrls: ['./dashboard.component.scss'],
    changeDetection: ChangeDetectionStrategy.OnPush,
    animations: [
        trigger('fadeIn', [
            transition(':enter', [
                style({ opacity: 0 }),
                animate('300ms ease-out', style({ opacity: 1 }))
            ])
        ]),
        trigger('slideDown', [
            transition(':enter', [
                style({ transform: 'translateY(-10px)', opacity: 0 }),
                animate('300ms ease-out', style({ transform: 'translateY(0)', opacity: 1 }))
            ])
        ])
    ]
})
export class DashboardComponent implements OnInit, OnDestroy {
    // ... existing code ...
    
    // Phase 4: Bulk Operations
    selectedSenderIds = signal(new Set<number>());
    selectedSiteNames = signal(new Set<string>());
    
    // ... rest of component ...
}
```

---

## Add Navigation Links

Update your main navigation component:

```typescript
// In your app shell or navigation component
import { Router } from '@angular/router';

export class NavigationComponent {
    navItems = [
        { label: 'Dashboard', route: '/exensioreload/dashboard', icon: 'dashboard' },
        { label: 'Analytics', route: '/exensioreload/analytics', icon: 'insights' },
        { label: 'New Request', route: '/exensioreload/new', icon: 'add' },
        { label: 'Sessions', route: '/exensioreload/my-sessions', icon: 'history' },
        { separator: true },
        { label: 'Alerts', route: '/exensioreload/alerts', icon: 'notifications_active' }, // NEW
        { label: 'Reports', route: '/exensioreload/export-reports', icon: 'description' }, // NEW
        { separator: true },
        { label: 'Admin', route: '/exensioreload/admin', icon: 'admin_panel_settings' }
    ];
}
```

HTML for navigation:

```html
<!-- Add to main navigation menu -->
<nav class="main-nav">
    @for (item of navItems; track item.route) {
        @if (item.separator) {
            <mat-divider></mat-divider>
        } @else {
            <a [routerLink]="item.route" 
               routerLinkActive="active"
               class="nav-item">
                <mat-icon>{{ item.icon }}</mat-icon>
                <span>{{ item.label }}</span>
            </a>
        }
    }
</nav>
```

---

## Keyboard Shortcuts

Add to dashboard component for enhanced UX:

```typescript
@HostListener('window:keydown', ['$event'])
handleKeyboardEvent(event: KeyboardEvent): void {
    // Ctrl+R: Resume selected senders
    if (event.ctrlKey && event.key === 'r') {
        event.preventDefault();
        // Bulk actions component will handle this
    }
    
    // Escape: Clear selection
    if (event.key === 'Escape') {
        this.clearAllSelections();
    }
    
    // Ctrl+A: Select all visible senders (when in dashboard)
    if (event.ctrlKey && event.key === 'a' && !this.isInputFocused()) {
        event.preventDefault();
        const allIds = this.allSelectableItems().map(item => item.id);
        this.selectedSenderIds.set(new Set(allIds));
    }
}

private isInputFocused(): boolean {
    const activeElement = document.activeElement;
    return activeElement?.tagName === 'INPUT' || activeElement?.tagName === 'TEXTAREA';
}
```

---

## Error Handling

Add to dashboard component:

```typescript
/**
 * Handle errors from bulk operations gracefully
 */
handleBulkOperationError(error: any): void {
    console.error('Bulk operation failed:', error);
    this.error.set('Bulk operation failed. Please try again or contact support.');
    
    // Clear selection on error
    setTimeout(() => {
        this.clearAllSelections();
    }, 3000);
}

/**
 * Validate selection before operations
 */
validateSelection(): boolean {
    const selected = this.selectedSenderIds();
    if (selected.size === 0) {
        this.error.set('Please select at least one sender');
        return false;
    }
    return true;
}
```

---

## Performance Optimization

Add computed properties for performance:

```typescript
// Memoize sender lookup for fast operations
senderMap = computed(() => {
    const map = new Map<number, DashboardSenderSnapshot>();
    const snap = this.snapshot();
    
    snap?.sites.forEach(site => {
        site.senders.forEach(sender => {
            map.set(sender.senderId, sender);
        });
    });
    
    return map;
});

// Get sender details without iteration
getSenderById(senderId: number): DashboardSenderSnapshot | undefined {
    return this.senderMap().get(senderId);
}

// Check if sender exists
senderExists(senderId: number): boolean {
    return this.senderMap().has(senderId);
}
```

---

## Accessibility Enhancements

```html
<!-- Make bulk actions accessible -->
<div class="bulk-actions-section" 
     role="region" 
     aria-label="Bulk operations toolbar"
     aria-live="polite">
    <app-bulk-actions 
        [allItems]="allSelectableItems()"
        (selectionChanged)="onBulkSelectionChanged($event)"
        aria-label="Bulk actions for selected items">
    </app-bulk-actions>
</div>

<!-- Selection summary with accessibility -->
<div class="selection-info" 
     role="status" 
     aria-live="polite"
     aria-atomic="true">
    @if (hasSelection()) {
        <span>{{ selectionCount() }} item(s) selected. 
            Use keyboard shortcuts: Ctrl+R to resume, Esc to clear</span>
    }
</div>
```

---

## Testing Setup

Example test file structure:

```typescript
// dashboard-bulk-operations.spec.ts
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { DashboardComponent } from './dashboard.component';
import { BackendService } from '../api/backend.service';
import { of } from 'rxjs';

describe('DashboardComponent - Bulk Operations', () => {
    let component: DashboardComponent;
    let fixture: ComponentFixture<DashboardComponent>;
    let backendService: jasmine.SpyObj<BackendService>;

    beforeEach(async () => {
        const backendSpy = jasmine.createSpyObj('BackendService', [
            'getDashboardSnapshot',
            'bulkResumeMonitoring',
            'bulkPauseMonitoring',
            'bulkExportData',
            'bulkDeleteData'
        ]);

        await TestBed.configureTestingModule({
            imports: [DashboardComponent],
            providers: [
                { provide: BackendService, useValue: backendSpy }
            ]
        }).compileComponents();

        fixture = TestBed.createComponent(DashboardComponent);
        component = fixture.componentInstance;
        backendService = TestBed.inject(BackendService) as jasmine.SpyObj<BackendService>;
    });

    it('should toggle sender selection', () => {
        component.toggleSenderSelection(123);
        expect(component.isSenderSelected(123)).toBeTrue();
        
        component.toggleSenderSelection(123);
        expect(component.isSenderSelected(123)).toBeFalse();
    });

    it('should handle bulk selection changes', () => {
        const selected = new Set([123, 456, 789]);
        component.onBulkSelectionChanged(selected);
        expect(component.selectedSenderIds()).toEqual(selected);
    });

    it('should clear all selections', () => {
        component.selectedSenderIds.set(new Set([123, 456]));
        component.clearAllSelections();
        expect(component.selectedSenderIds().size).toBe(0);
    });
});
```

---

## Summary

These code examples provide:
1. ✅ Signals for tracking selections
2. ✅ Computed properties for derived state
3. ✅ Component methods for operations
4. ✅ HTML template updates with checkboxes
5. ✅ SCSS styling for UI
6. ✅ Module imports
7. ✅ Navigation integration
8. ✅ Keyboard shortcuts
9. ✅ Error handling
10. ✅ Performance optimization
11. ✅ Accessibility features
12. ✅ Testing setup

**Next:** Follow [Phase 4 Integration Guide](PHASE_4_INTEGRATION_GUIDE.md) for complete implementation steps.
