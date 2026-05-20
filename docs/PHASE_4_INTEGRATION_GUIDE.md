# Phase 4 Quick Integration Guide

**Last Updated:** April 16, 2026

This guide explains how to integrate the newly implemented Phase 4 components into your dashboard application.

---

## 🚀 Quick Start

### 1. Update `app.routes.ts`

Add routes for alerts and export/reporting:

```typescript
import { AlertConfigurationComponent } from './shared/components/alert-configuration.component';
import { ExportReportingComponent } from './shared/components/export-reporting.component';

export const routes: Routes = [
    // ... existing routes ...
    
    {
        path: 'exensioreload',
        component: AppComponent,
        children: [
            // ... existing dashboard, stepper, etc ...
            
            {
                path: 'alerts',
                component: AlertConfigurationComponent,
                data: { title: 'Alert Configuration' }
            },
            {
                path: 'export-reports',
                component: ExportReportingComponent,
                data: { title: 'Export & Reports' }
            }
        ]
    }
];
```

### 2. Update Dashboard Component

**File:** `dashboard.component.ts`

```typescript
import { BulkActionsComponent, SelectableItem } from './bulk-actions.component';

export class DashboardComponent implements OnInit, OnDestroy {
    // ... existing signals ...
    
    // Add for bulk actions
    selectedSenderIds = signal(new Set<number>());
    
    allSelectableItems = computed(() => {
        const items: SelectableItem[] = [];
        const snap = this.snapshot();
        
        if (!snap) return items;
        
        // Add each sender as selectable
        snap.sites.forEach(site => {
            site.senders.forEach(sender => {
                items.push({
                    id: sender.senderId,
                    type: 'sender',
                    label: `${sender.senderLabel} (${site.site})`
                });
            });
        });
        
        return items;
    });
    
    onBulkSelectionChanged(selected: Set<number>): void {
        this.selectedSenderIds.set(selected);
    }
}
```

**File:** `dashboard.component.html`

Add bulk actions component before closing div:

```html
<div class="dashboard-container" [class.loading]="loading()">
    <!-- ... existing content ... -->
    
    <!-- Bulk Actions Floating Bar -->
    <app-bulk-actions 
        [allItems]="allSelectableItems()"
        (selectionChanged)="onBulkSelectionChanged($event)">
    </app-bulk-actions>
</div>
```

Add checkbox to sender cards:

```html
<!-- In sender card section, add checkbox before card content -->
<div class="sender-card-wrapper">
    <mat-checkbox 
        class="sender-checkbox"
        [checked]="selectedSenderIds().has(sender.senderId)"
        (change)="$event.checked ? 
            bulkActions?.toggleItem(sender.senderId) : 
            bulkActions?.toggleItem(sender.senderId)"
        aria-label="Select {{ sender.senderLabel }}">
    </mat-checkbox>
    
    <div class="sender-card">
        <!-- ... existing card content ... -->
    </div>
</div>
```

### 3. Add Navigation Links

Add to your main navigation/header:

```html
<!-- In navigation menu -->
<div class="admin-menu">
    <a routerLink="/exensioreload/alerts" routerLinkActive="active">
        <mat-icon>notifications_active</mat-icon>
        <span>Alert Configuration</span>
    </a>
    
    <a routerLink="/exensioreload/export-reports" routerLinkActive="active">
        <mat-icon>description</mat-icon>
        <span>Export & Reports</span>
    </a>
</div>
```

### 4. Update Dashboard Imports

**File:** `dashboard.component.ts`

```typescript
import { BulkActionsComponent } from './bulk-actions.component';

@Component({
    selector: 'app-dashboard',
    standalone: true,
    imports: [
        CommonModule,
        // ... existing imports ...
        BulkActionsComponent,
        MatCheckboxModule  // Add for checkboxes
    ],
    // ...
})
```

---

## 🔧 Backend Integration

### Required Endpoints

Implement these endpoints in your backend:

#### Bulk Operations
```
POST /api/dashboard/bulk/resume
Body: { senderIds: number[] }
Response: { success: number, failed: number, message: string }

POST /api/dashboard/bulk/pause
Body: { senderIds: number[] }
Response: { success: number, failed: number, message: string }

POST /api/dashboard/bulk/export?format=csv|excel
Body: { senderIds: number[] }
Response: Binary blob (CSV or Excel file)

POST /api/dashboard/bulk/delete
Body: { senderIds: number[] }
Response: { success: number, failed: number, message: string }
```

#### Alerts
```
GET /api/alerts/configuration
Response: AlertConfiguration

PUT /api/alerts/configuration
Body: AlertConfiguration
Response: AlertConfiguration

GET /api/alerts/sender/{senderId}/thresholds
Response: AlertThreshold

PUT /api/alerts/sender/{senderId}/thresholds
Body: AlertThreshold
Response: AlertThreshold

GET /api/alerts/sender/{senderId}
Response: SenderAlert[]
```

#### Export & Reports
```
GET /api/dashboard/export/csv
Response: Binary CSV blob

GET /api/dashboard/export/excel
Response: Binary Excel blob

POST /api/reports/scheduled
Body: ScheduledReport
Response: ScheduledReport

GET /api/reports/scheduled
Response: ScheduledReport[]

PUT /api/reports/scheduled/{reportId}
Body: ScheduledReport
Response: ScheduledReport

DELETE /api/reports/scheduled/{reportId}
Response: { success: boolean }
```

### Example Java Implementation (Spring Boot)

```java
@RestController
@RequestMapping("/api/dashboard/bulk")
public class BulkOperationsController {
    
    @PostMapping("/resume")
    public ResponseEntity<BulkOperationResult> bulkResume(@RequestBody BulkOperationRequest request) {
        // Implementation...
    }
    
    @PostMapping("/export")
    public ResponseEntity<byte[]> bulkExport(
        @RequestBody BulkOperationRequest request,
        @RequestParam String format) {
        // Implementation...
    }
}

@RestController
@RequestMapping("/api/alerts")
public class AlertController {
    
    @GetMapping("/configuration")
    public ResponseEntity<AlertConfiguration> getConfiguration() {
        // Implementation...
    }
    
    @PutMapping("/configuration")
    public ResponseEntity<AlertConfiguration> updateConfiguration(
        @RequestBody AlertConfiguration config) {
        // Implementation...
    }
}
```

---

## 📊 UI Styling

### Sender Checkbox Styling

```scss
.sender-card-wrapper {
    display: flex;
    align-items: flex-start;
    gap: 1rem;
    margin-bottom: 1rem;
}

.sender-checkbox {
    margin-top: 0.5rem;
    flex-shrink: 0;
}

.sender-card {
    flex: 1;
}
```

### Responsive Adjustments

```scss
@media (max-width: 768px) {
    .sender-card-wrapper {
        flex-direction: column;
    }
    
    .sender-checkbox {
        margin-top: 0;
    }
}
```

---

## 🧪 Testing

### Unit Test Example

```typescript
// bulk-actions.component.spec.ts
describe('BulkActionsComponent', () => {
    let component: BulkActionsComponent;
    let fixture: ComponentFixture<BulkActionsComponent>;
    
    beforeEach(async () => {
        await TestBed.configureTestingModule({
            imports: [BulkActionsComponent],
            providers: [BackendService]
        }).compileComponents();
        
        fixture = TestBed.createComponent(BulkActionsComponent);
        component = fixture.componentInstance;
    });
    
    it('should toggle item selection', () => {
        const itemId = 123;
        component.toggleItem(itemId);
        expect(component.selectedItems().has(itemId)).toBeTrue();
    });
    
    it('should emit selectionChanged event', (done) => {
        component.selectionChanged.subscribe((selected) => {
            expect(selected.size).toBeGreaterThan(0);
            done();
        });
        
        component.toggleItem(456);
    });
});
```

### E2E Test Example

```typescript
// dashboard-bulk.e2e.ts
describe('Dashboard Bulk Operations E2E', () => {
    beforeEach(() => {
        cy.visit('/exensioreload/dashboard');
    });
    
    it('should bulk resume multiple senders', () => {
        // Select first sender
        cy.get('.sender-checkbox').first().click();
        cy.get('.sender-checkbox').eq(1).click();
        
        // Click resume button
        cy.get('.bulk-action-buttons button').contains('play_circle').click();
        
        // Verify success message
        cy.contains('Resumed 2 sender(s)').should('be.visible');
    });
});
```

---

## 🔐 Security Checklist

- [ ] Validate sender IDs on backend
- [ ] Verify user has permission for bulk operations
- [ ] Sanitize CSV output (prevent injection)
- [ ] Rate-limit bulk operations
- [ ] Log all bulk deletions
- [ ] Encrypt webhook/email configuration
- [ ] Validate email recipients format
- [ ] Sanitize webhook URLs

---

## 📈 Performance Tips

1. **Bulk Operations**
   - Batch up to 1000 operations per request
   - Use POST body instead of query params
   - Return partial results on partial failure

2. **Alerts**
   - Cache alert configuration (TTL: 5 minutes)
   - Paginate active alerts (20 per page)
   - Use database indexes on sender_id

3. **Export/Reporting**
   - Stream large exports (don't load into memory)
   - Queue scheduled reports asynchronously
   - Compress exports before email delivery

---

## 🐛 Troubleshooting

### Bulk operations not working
1. Check that BackendService endpoints are correctly implemented
2. Verify sender IDs are valid numbers
3. Check browser console for HTTP errors
4. Ensure user has required permissions

### Alerts not triggering
1. Verify backend alert service is running
2. Check that thresholds are saved correctly
3. Review alert configuration in database
4. Check notification delivery logs

### Exports empty
1. Verify data freshness (recent poll)
2. Check export format is compatible
3. Try alternative format (CSV vs Excel)
4. Check file size limits

---

## 📚 Related Documentation

- [Phase 4 Implementation Summary](PHASE_4_IMPLEMENTATION_SUMMARY.md)
- [Dashboard UI/UX Implementation Plan - All Phases](docs/DASHBOARD_UI_UX_IMPLEMENTATION_PLAN_ALL_PHASES.md)
- [API Documentation](./API_DOCS.md)

---

## ✅ Verification Checklist

After integration, verify:

- [ ] Routes load without errors
- [ ] Bulk actions component appears on dashboard
- [ ] Checkbox selection works on sender cards
- [ ] Floating bar appears when items selected
- [ ] Resume/Pause operations call backend
- [ ] Export creates downloadable file
- [ ] Delete shows confirmation
- [ ] Alert configuration page loads
- [ ] Export/reports page loads
- [ ] No console errors
- [ ] Mobile responsive
- [ ] Accessibility (keyboard navigation, screen readers)

---

## 🎯 Next Steps

1. **Implement Backend Endpoints** (3-4 days)
   - All bulk operation endpoints
   - All alert management endpoints
   - All export/reporting endpoints

2. **Integration Testing** (2 days)
   - Test each component
   - Test workflows
   - Test error handling

3. **UAT & Deployment** (2 days)
   - User acceptance testing
   - Performance validation
   - Production deployment

4. **Future: Phase 4.3** (deferred)
   - Site deep-links & dashboard
   - Site-specific analytics

---

**Questions?** Refer to the inline code comments or the implementation summary document.
