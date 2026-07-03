# Design Document: Lot Existence Verification

## Overview

This design implements a pre-flight lot verification feature by **adapting the existing ExensioPreCheckService from xfcs-reloader**. When users click "Run Discovery Preview", the system intercepts the request, verifies which lots exist in Exensio using the proven Snowflake-first approach (with raw-SQL fallback), displays verification results in a modal dialog, and then proceeds with discovery based on user choice.

The implementation copies and adapts the ExensioPreCheckService, adds a new verification dialog component, and modifies the existing discovery flow to integrate the pre-flight check seamlessly.

## Architecture

### Frontend Architecture

```
StepperComponent
  ├─ verifyLotsBeforeDiscovery() [NEW]
  │   ├─ Extracts unique lots from lotWaferPairs
  │   ├─ Calls BackendService.verifyLotsExistence()
  │   └─ Opens LotVerificationDialogComponent
  │
  ├─ LotVerificationDialogComponent [NEW]
  │   ├─ Displays verification results
  │   ├─ Shows three action buttons
  │   ├─ Handles CSV export
  │   └─ Returns user choice to caller
  │
  └─ loadPreview() [MODIFIED]
      ├─ Calls verifyLotsBeforeDiscovery() first
      ├─ Waits for user choice
      └─ Proceeds with discovery using filtered lots
```

### Backend Architecture

**Note:** This design leverages the existing `ExensioPreCheckService` from xfcs-reloader, which already implements lot verification via raw-SQL with Snowflake fallback.

```
SenderController
  └─ POST /api/senders/{id}/verify-lots [NEW]
      ├─ Validates request
      ├─ Calls ExensioPreCheckService.check()
      └─ Transforms ExensioPreCheckResponse to verification response

ExensioPreCheckService [EXISTING - REUSED]
  ├─ check(request) [PRIMARY]
  │   ├─ Tries Snowflake JDBC first (faster, more reliable)
  │   └─ Falls back to Exensio HTTP raw-SQL on failure
  │
  ├─ checkViaSnowflake() [Snowflake path]
  │   ├─ Queries ANALYTICSPRD.MFG.EXENSIO_PROD_OPLOG_METADATA
  │   ├─ Uses INSERT_TIME filter if year/month provided
  │   └─ Returns lots found with schema name
  │
  └─ checkViaExensioHttp() [Fallback path]
      ├─ Constructs Oracle SQL with lot IN clause
      ├─ Calls Exensio raw-SQL endpoint
      └─ Handles 401 with token refresh
```

## Components and Interfaces

### Frontend Components

#### 1. LotVerificationDialogComponent (NEW)

A modal dialog component that displays lot verification results and asks for user action.

**Template Structure:**

```html
<div class="verification-dialog">
  <!-- Header with title and export button -->
  <div class="dialog-header">
    <h2>Lot Verification Results</h2>
    <button (click)="exportToCsv()">Export to CSV</button>
    <button (click)="close()">×</button>
  </div>

  <!-- Summary stats -->
  <div class="verification-summary">
    <div class="stat">
      <span class="count">{{ totalLots }}</span>
      <span class="label">Total Lots</span>
    </div>
    <div class="stat success">
      <span class="count">{{ foundCount }}</span>
      <span class="label">Found in Exensio</span>
    </div>
    <div class="stat error">
      <span class="count">{{ notFoundCount }}</span>
      <span class="label">Not Found</span>
    </div>
  </div>

  <!-- Warning banner if all lots exist -->
  <div *ngIf="notFoundCount === 0" class="warning-banner">
    All lots already exist in Exensio. Discovery may return files that have already been loaded.
  </div>

  <!-- Lot lists in two columns -->
  <div class="lot-lists">
    <div class="lot-section">
      <h3><icon>check_circle</icon> Found in Exensio ({{ foundCount }})</h3>
      <div class="lot-scroll">
        <div *ngFor="let lot of foundLots" class="lot-item">{{ lot }}</div>
      </div>
    </div>
    <div class="lot-section">
      <h3><icon>error</icon> Not Found ({{ notFoundCount }})</h3>
      <div class="lot-scroll">
        <div *ngFor="let lot of notFoundLots" class="lot-item">{{ lot }}</div>
      </div>
    </div>
  </div>

  <!-- Action buttons -->
  <div class="dialog-actions">
    <button (click)="cancel()">Cancel</button>
    <button (click)="continueWithAll()">Continue with All</button>
    <button (click)="continueWithNotFound()" [disabled]="notFoundCount === 0" [class.recommended]="notFoundCount > 0">
      Continue with Lots Not in Exensio
    </button>
  </div>
</div>
```

**Component Class:**

```typescript
export interface LotVerificationDialogData {
  lots: string[];
  verificationResult: Map<string, boolean>;
  verifiedAt: Date;
}

export interface LotVerificationDialogResult {
  action: 'all' | 'not-found' | 'cancel';
  filteredLots?: string[];
}

@Component({
  selector: 'app-lot-verification-dialog',
  standalone: true,
  imports: [CommonModule, GlassButtonComponent, GlassIconComponent],
  templateUrl: './lot-verification-dialog.component.html',
  styleUrls: ['./lot-verification-dialog.component.scss'],
})
export class LotVerificationDialogComponent {
  foundLots: string[] = [];
  notFoundLots: string[] = [];
  totalLots = 0;
  foundCount = 0;
  notFoundCount = 0;
  verifiedAt: Date;

  constructor(
    @Inject(GLASS_DIALOG_DATA) public data: LotVerificationDialogData,
    public dialogRef: GlassDialogRef<LotVerificationDialogComponent, LotVerificationDialogResult>,
  ) {
    this.processVerificationResults();
  }

  private processVerificationResults(): void {
    this.data.lots.forEach((lot) => {
      if (this.data.verificationResult.get(lot)) {
        this.foundLots.push(lot);
      } else {
        this.notFoundLots.push(lot);
      }
    });
    this.totalLots = this.data.lots.length;
    this.foundCount = this.foundLots.length;
    this.notFoundCount = this.notFoundLots.length;
    this.verifiedAt = this.data.verifiedAt;
  }

  continueWithAll(): void {
    this.dialogRef.close({ action: 'all' });
  }

  continueWithNotFound(): void {
    this.dialogRef.close({
      action: 'not-found',
      filteredLots: this.notFoundLots,
    });
  }

  cancel(): void {
    this.dialogRef.close({ action: 'cancel' });
  }

  exportToCsv(): void {
    const timestamp = new Date().toISOString().replace(/[:.]/g, '-').slice(0, 19);
    const filename = `lot-verification-${timestamp}.csv`;

    let csv = 'Lot ID,Status,Verified At\n';
    this.foundLots.forEach((lot) => {
      csv += `"${lot}","Found in Exensio","${this.verifiedAt.toISOString()}"\n`;
    });
    this.notFoundLots.forEach((lot) => {
      csv += `"${lot}","Not Found in Exensio","${this.verifiedAt.toISOString()}"\n`;
    });

    const blob = new Blob([csv], { type: 'text/csv;charset=utf-8;' });
    const link = document.createElement('a');
    link.href = URL.createObjectURL(blob);
    link.download = filename;
    link.click();
    URL.revokeObjectURL(link.href);
  }

  close(): void {
    this.dialogRef.close({ action: 'cancel' });
  }
}
```

#### 2. StepperComponent Modifications

**Add verification method:**

```typescript
private async verifyLotsBeforeDiscovery(): Promise<string[] | null> {
  // Extract unique lots from lotWaferPairs
  const lots = Array.from(
    new Set(
      this.lotWaferPairs()
        .map(pair => pair.lot?.trim())
        .filter(lot => lot && lot.length > 0)
    )
  );

  if (lots.length === 0) {
    // No lots to verify - proceed immediately (date range only query)
    return [];
  }

  // Get sender ID for the API call
  const senderId = this.getSenderIdForRequest();
  if (!senderId) {
    this.toast.error('Sender is required for verification');
    return null;
  }

  // Show loading overlay
  this.previewLoading.set(true);

  try {
    // Call backend to verify lots
    const result = await firstValueFrom(
      this.backend.verifyLotsExistence(senderId, lots)
    );

    this.previewLoading.set(false);

    // Open verification dialog
    const dialogRef = this.dialogService.open(LotVerificationDialogComponent, {
      data: {
        lots,
        verificationResult: new Map(Object.entries(result.lotExists)),
        verifiedAt: new Date()
      },
      width: '800px'
    });

    const dialogResult = await firstValueFrom(dialogRef.afterClosed());

    if (!dialogResult || dialogResult.action === 'cancel') {
      return null; // User cancelled
    }

    if (dialogResult.action === 'all') {
      return lots; // Continue with all lots
    }

    if (dialogResult.action === 'not-found') {
      return dialogResult.filteredLots || []; // Continue with only non-existent lots
    }

    return null;
  } catch (error) {
    this.previewLoading.set(false);
    console.error('Lot verification failed:', error);

    // Show error with option to skip verification
    const proceed = await this.confirmSkipVerification(error);
    return proceed ? lots : null;
  }
}

private async confirmSkipVerification(error: any): Promise<boolean> {
  const errorMsg = error?.error?.message || error?.statusText || 'Verification failed';
  const message = `Lot verification failed: ${errorMsg}\n\nWould you like to skip verification and continue with discovery?`;

  return window.confirm(message);
}
```

**Modify loadPreview() method:**

```typescript
async loadPreview() {
  if (!this.canProceedToPreview()) {
    this.toast.warning(
      'Please select all required filters (Site, Location, Data Type) and at least one additional filter'
    );
    return;
  }

  const senderId = this.getSenderIdForRequest();
  if (!senderId) {
    this.toast.error('Sender is required for preview');
    return;
  }

  // === NEW: Pre-flight lot verification ===
  const lotsToDiscover = await this.verifyLotsBeforeDiscovery();
  if (lotsToDiscover === null) {
    // User cancelled or verification failed
    return;
  }

  // Update lot/wafer pairs with filtered lots
  if (lotsToDiscover.length > 0) {
    const filteredPairs = this.lotWaferPairs().filter(pair =>
      lotsToDiscover.includes(pair.lot?.trim() || '')
    );
    this.lotWaferPairs.set(filteredPairs);
  }
  // === END: Pre-flight lot verification ===

  this.previewLoading.set(true);
  const built = this.buildDiscoveryPreviewParams();
  // ... rest of existing loadPreview() logic
}
```

### Backend Components

### Backend Components

#### 1. ExensioPreCheckService (COPIED from xfcs-reloader)

**Copy from:** `com.onsemi.cim.apps.exensio.xfcsreloader.service.ExensioPreCheckService`
**Copy to:** `com.onsemi.cim.apps.exensio.exensioreload.service.ExensioPreCheckService`

**Adaptation needed:**

- Change package from `xfcsreloader` to `exensioreload`
- Adjust imports to match exensioreload structure
- Ensure compatibility with exensioreload's ExensioProperties, ExensioAuthService
- Remove Snowflake dependency if not available (gracefully fall back to HTTP only)

**Key methods to preserve:**

```java
// Main orchestration - tries Snowflake first, falls back to HTTP
public ExensioPreCheckResponse check(ExensioPreCheckRequest request)

// Snowflake JDBC path (primary) - may be null if Snowflake not configured
ExensioPreCheckResponse checkViaSnowflake(ExensioPreCheckRequest request)

// Exensio HTTP raw-SQL path (fallback)
ExensioPreCheckResponse checkViaExensioHttp(ExensioPreCheckRequest request)

// SQL builders
public String buildSql(List<String> lotIds, List<PreCheckBlock> blocks)
public static String buildLotIdsJson(List<String> lotIds)

// Result partitioning
public static ExensioPreCheckResponse partitionResults(
    List<ExensioPreCheckRow> rows,
    List<String> submittedLotIds)
```

#### 2. DTOs (COPIED from xfcs-reloader)

**Copy these DTOs to exensioreload.dto package:**

```java
public record ExensioPreCheckRequest(
    List<String> lotIds,
    List<PreCheckBlock> blocks,  // null if no date filtering needed
    String environment
) {}

public record ExensioPreCheckResponse(
    List<String> lotsFound,
    List<String> lotsNotFound,
    List<ExensioPreCheckRow> rows,
    String error  // null if success, error message if failed
) {}

public record ExensioPreCheckRow(
    String lotId,
    String schemaName  // "NOT FOUND", "PROD", "QA", etc.
) {}

public record PreCheckBlock(
    Integer year,
    Integer month
) {}
```

#### 3. SenderController - New Endpoint

**Add verification endpoint:**

```java
@org.springframework.security.access.prepost.PreAuthorize("hasRole('USER')")
@PostMapping("/{id}/verify-lots")
public ResponseEntity<LotVerificationResponse> verifyLots(
        @PathVariable("id") Integer id,
        @RequestBody LotVerificationRequest request) {

    if (request == null || request.lots() == null || request.lots().isEmpty()) {
        return ResponseEntity.badRequest().build();
    }

    List<String> lots = request.lots();
    if (lots.size() > 1000) {
        return ResponseEntity.badRequest()
            .body(new LotVerificationResponse(
                Map.of(),
                "Too many lots. Maximum 1000 lots per request."
            ));
    }

    try {
        // Transform to ExensioPreCheckRequest format
        ExensioPreCheckRequest preCheckRequest = new ExensioPreCheckRequest(
            lots,
            null, // blocks - not needed for simple lot check
            request.environment()
        );

        // Call the copied ExensioPreCheckService
        ExensioPreCheckResponse preCheckResponse = exensioPreCheckService.check(preCheckRequest);

        // Transform response to frontend format
        Map<String, Boolean> lotExists = new HashMap<>();
        for (String lot : lots) {
            boolean found = preCheckResponse.lotsFound().contains(lot);
            lotExists.put(lot, found);
        }

        String error = preCheckResponse.error();
        return ResponseEntity.ok(new LotVerificationResponse(lotExists, error));

    } catch (Exception e) {
        log.error("Lot verification failed for sender {}: {}", id, e.getMessage(), e);
        return ResponseEntity.status(500)
            .body(new LotVerificationResponse(
                Map.of(),
                "Verification failed: " + e.getMessage()
            ));
    }
}
```

#### 4. Frontend-Facing DTOs

```java
public record LotVerificationRequest(
    List<String> lots,
    String site,
    String environment
) {}

public record LotVerificationResponse(
    Map<String, Boolean> lotExists,
    String error
) {}
```

### Backend Service

**BackendService additions:**

```typescript
verifyLotsExistence(senderId: number, lots: string[]): Observable<LotVerificationResponse> {
  return this.http.post<LotVerificationResponse>(
    `${this.baseUrl}/senders/${senderId}/verify-lots`,
    { lots, site: 'default', environment: 'qa' }
  );
}
```

## Data Models

### Verification Result Model

```typescript
interface LotVerificationResult {
  lot: string;
  existsInExensio: boolean;
  verifiedAt: Date;
}
```

### CSV Export Format

```
Lot ID,Status,Verified At
"LOT123","Found in Exensio","2026-07-03T14:30:00.000Z"
"LOT456","Not Found in Exensio","2026-07-03T14:30:00.000Z"
"LOT789","Found in Exensio","2026-07-03T14:30:00.000Z"
```

## Correctness Properties

_A property is a characteristic or behavior that should hold true across all valid executions of a system—essentially, a formal statement about what the system should do. Properties serve as the bridge between human-readable specifications and machine-verifiable correctness guarantees._

### Property 1: SQL Query Construction Safety

_For any_ list of lot identifiers, the constructed SQL query should properly escape single quotes by doubling them and wrap each identifier in quotes

**Validates: Requirements 7.3**

### Property 2: Batch Size Limit

_For any_ list of lots greater than 500, the verification process should split them into batches where each batch contains at most 500 lots

**Validates: Requirements 2.3, 2.4**

### Property 3: Verification Result Completeness

_For any_ list of input lots, the verification result map should contain an entry for every lot in the input list

**Validates: Requirements 1.1, 2.1**

### Property 4: Dialog Action Consistency

_For any_ verification dialog result with action 'not-found', the filteredLots list should contain only lots that were marked as not existing in Exensio

**Validates: Requirements 4.3**

### Property 5: CSV Export Completeness

_For any_ verification result, the exported CSV should contain exactly one row per input lot

**Validates: Requirements 11.2, 11.3**

### Property 6: Discovery Filter Preservation

_For any_ discovery query where user selects 'Continue with Lots Not in Exensio', all other filters (date range, data type, location) should remain unchanged

**Validates: Requirements 10.4**

### Property 7: Empty Lot List Bypass

_For any_ discovery request with zero lots (date range only), the verification step should be skipped and discovery should proceed immediately

**Validates: Requirements 1.5**

### Property 8: Verification Timeout Handling

_For any_ verification request that exceeds 5 seconds, the system should provide an abort option and handle the timeout gracefully

**Validates: Requirements 9.4, 9.5**

## Date Range Filtering in Verification

When a user provides both lots AND a date range (in historical mode or via super-admin date filters), the verification should filter lots by the date range to match what discovery will actually return. This is done by:

1. **Extract date range**: Get `startDate` and `endDate` from the `dateRange` signal
2. **Convert to year/month**: Extract year and month from both dates to create `PreCheckBlock` entries
3. **Pass to verification service**: Include the blocks in `ExensioPreCheckRequest` so Exensio queries filter by `end_time`
4. **Display in dialog**: Show the applied date range in the verification results dialog

**Example transformation:**

- Date range: `2025-01-15` to `2025-03-20`
- PreCheckBlocks: `[{ year: 2025, month: 1 }, { year: 2025, month: 2 }, { year: 2025, month: 3 }]`
- Result: Only lots with data matching those months are marked as "found"

This ensures verification results reflect what discovery will actually return, avoiding false positives for lots with no data in the selected date range.

## Error Handling

### Frontend Error Handling

**Authentication Failure (HTTP 401):**

```typescript
if (error.status === 401) {
  this.toast.error('Authentication failed. Please refresh and try again.');
  return this.confirmSkipVerification(error);
}
```

**Timeout (HTTP 504 or timeout):**

```typescript
if (error.status === 504 || error.name === 'TimeoutError') {
  this.toast.error('Exensio is temporarily unavailable. Try again or skip verification.');
  return this.confirmSkipVerification(error);
}
```

**Server Error (HTTP 500):**

```typescript
if (error.status === 500) {
  this.toast.error('Verification query failed. Discovery will proceed without verification.');
  return this.confirmSkipVerification(error);
}
```

### Backend Error Handling

**SQL Exception:**

```java
catch (SQLException e) {
    log.error("SQL error during lot verification: {}", e.getMessage(), e);
    throw new RuntimeException("Verification query failed", e);
}
```

**Exensio API Error:**

```java
catch (IllegalStateException e) {
    if (e.getMessage().contains("401")) {
        throw new RuntimeException("Authentication failed", e);
    }
    throw new RuntimeException("Exensio API error: " + e.getMessage(), e);
}
```

## Testing Strategy

### Unit Tests

**Frontend Unit Tests:**

1. LotVerificationDialogComponent CSV export generates correct format
2. verifyLotsBeforeDiscovery() extracts unique lots from pairs
3. Dialog returns correct action and filtered lots
4. loadPreview() correctly filters lot/wafer pairs after verification

**Backend Unit Tests:**

1. buildLotVerificationSql() properly escapes single quotes
2. verifyLotsExistence() batches lots correctly when count > 500
3. verifyLotsExistence() initializes all lots as false
4. verifyLotsExistence() marks found lots as true

### Property-Based Tests

**Property Test 1: SQL Injection Prevention**

- Generate random lot identifiers including special characters
- Verify SQL query doesn't break with malformed input
- **Feature: lot-existence-verification, Property 1: SQL Query Construction Safety**

**Property Test 2: Batch Processing Correctness**

- Generate random lot counts from 1 to 2000
- Verify all lots are processed exactly once
- **Feature: lot-existence-verification, Property 2: Batch Size Limit**

**Property Test 3: Result Completeness**

- Generate random lot lists
- Verify result map has same size as input
- **Feature: lot-existence-verification, Property 3: Verification Result Completeness**

**Property Test 4: CSV Export Integrity**

- Generate random verification results
- Verify CSV has correct number of rows
- **Feature: lot-existence-verification, Property 5: CSV Export Completeness**

### Integration Tests

1. Full workflow: Input lots → Verify → Choose "not-found" → Discovery runs with filtered lots
2. Error recovery: Verification fails → User skips → Discovery runs with all lots
3. CSV export: Verify → Export → Validate CSV format
4. Historical mode: Date range + lots → Verify → Discovery preserves date range

### Manual Testing Scenarios

1. Verify 1 lot that exists in Exensio
2. Verify 10 lots where 5 exist
3. Verify 600 lots (test batching)
4. Verify lots with special characters (quotes, commas)
5. Test when Exensio is unavailable
6. Test CSV export with 100 lots
7. Test "Continue with Lots Not in Exensio" when all lots exist (button disabled)
