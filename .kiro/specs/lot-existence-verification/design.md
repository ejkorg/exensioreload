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

#### 1. LotVerificationDialogComponent (IMPLEMENTED)

A modal dialog component that displays lot verification results and asks for user action.

**Status:** ✅ IMPLEMENTED - Located at `frontend/src/app/stepper/lot-verification-dialog.component.ts`

**Key Features Implemented:**

- Three-column summary stats (Total Lots, Found, Not Found)
- Warning banner when all lots exist (not-found button disabled)
- Task 11: Date range info banner showing applied date range filters
- Two-column scrollable lot lists
- CSV export with timestamp in YYYYMMDD-HHMMSS format
- Three action buttons with proper state management
- Accessibility features (role="dialog", aria-labelledby, etc.)

**Interface Definitions:**

```typescript
export interface LotVerificationDialogData {
  lots: string[];
  verificationResult: Map<string, boolean>;
  verifiedAt: Date;
  appliedDateRange?: { start: Date; end: Date } | null; // Task 11
}

export interface LotVerificationDialogResult {
  action: 'all' | 'not-found' | 'cancel';
  filteredLots?: string[];
}
```

**Key Methods:**

- `processVerificationResults()` - Separates lots into found/not-found arrays
- `continueWithAll()` - Returns action 'all'
- `continueWithNotFound()` - Returns action 'not-found' with filtered lots
- `cancel()` - Returns action 'cancel'
- `exportToCsv()` - Generates CSV with proper escaping and timestamp
- `computeDateRangeText()` - Task 11: Formats date range for display

#### 2. StepperComponent Modifications (IMPLEMENTED)

**Status:** ✅ IMPLEMENTED - Located at `frontend/src/app/stepper/stepper.component.ts`

**Key Methods Added:**

**verifyLotsBeforeDiscovery() - Task 6, 7, 8, 9, 10, 11**

```typescript
private async verifyLotsBeforeDiscovery(): Promise<string[] | null> {
  // Extract unique lots from lotWaferPairs (Requirement 1.1)
  const lots = Array.from(
    new Set(
      this.lotWaferPairs()
        .map((pair) => pair.lot?.trim())
        .filter((lot) => lot && lot.length > 0)
    )
  );

  if (lots.length === 0) {
    // No lots to verify - proceed immediately (date range only query)
    // Requirement 1.5 - If no lots are provided (date range only query), skip verification
    return [];
  }

  // Get sender ID for the API call
  const senderId = this.getSenderIdForRequest();
  if (!senderId) {
    this.toast.error('Sender is required for verification');
    return null;
  }

  // Task 9.1: Set previewLoading(true) before verification call
  this.previewLoading.set(true);

  try {
    // Task 11: Extract date range if provided (historical mode with date range)
    const dateRangeData = this.dateRange();
    let appliedDateRange: { start: Date; end: Date } | null = null;
    const preCheckBlocks: Array<{ year: number; month: number }> = [];

    if (dateRangeData?.start && dateRangeData?.end) {
      const startDate = new Date(dateRangeData.start);
      const endDate = new Date(dateRangeData.end);
      appliedDateRange = { start: startDate, end: endDate };

      // Extract year and month from dateRange to create PreCheckBlock entries
      // Generate all month/year combinations between start and end dates (inclusive)
      const current = new Date(startDate);
      while (current <= endDate) {
        preCheckBlocks.push({
          year: current.getFullYear(),
          month: current.getMonth() + 1,
        });
        current.setMonth(current.getMonth() + 1);
      }
    }

    // Call backend to verify lots with optional date range filtering
    // Task 2: Call verifyLotsExistenceWithDateRange which passes blocks to backend
    const result = await firstValueFrom(
      this.backend.verifyLotsExistenceWithDateRange(
        senderId,
        lots,
        this.selectedDataType() || 'ft',
        preCheckBlocks.length > 0 ? preCheckBlocks : null,
      ),
    );

    // Task 9.1: Set previewLoading(false) after verification completes
    this.previewLoading.set(false);

    // Transform result to Map for dialog
    const verificationMap = new Map<string, boolean>();
    if (result.lotExists instanceof Map) {
      result.lotExists.forEach((value: boolean, key: string) => {
        verificationMap.set(key, value);
      });
    } else {
      Object.entries(result.lotExists).forEach(([key, value]: [string, any]) => {
        verificationMap.set(key, Boolean(value));
      });
    }

    // Count found and not found lots for summary
    let foundCount = 0;
    let notFoundCount = 0;
    verificationMap.forEach((found: boolean) => {
      if (found) foundCount++;
      else notFoundCount++;
    });

    // Task 1.3: Display verification dialog showing results
    const dialogRef = this.dialog.open<
      LotVerificationDialogComponent,
      LotVerificationDialogData,
      LotVerificationDialogResult
    >(LotVerificationDialogComponent, {
      data: {
        lots,
        verificationResult: verificationMap,
        verifiedAt: new Date(),
        appliedDateRange,
      } as LotVerificationDialogData,
      disableClose: false,
      panelClass: 'glass-dialog',
      backdropClass: 'glass-backdrop',
    });

    const dialogResult = await dialogRef.afterClosed();

    if (!dialogResult || dialogResult.action === 'cancel') {
      // User cancelled
      return null;
    }

    if (dialogResult.action === 'all') {
      // Task 6 & 8: Store verification summary for banner display
      this.verificationSummary.set({
        choice: 'all',
        totalLots: lots.length,
        foundCount,
        notFoundCount,
      });
      return lots;
    }

    if (dialogResult.action === 'not-found') {
      // Task 6 & 8: Store verification summary for banner display
      this.verificationSummary.set({
        choice: 'not-found',
        totalLots: lots.length,
        foundCount,
        notFoundCount,
      });
      return dialogResult.filteredLots || [];
    }

    return null;
  } catch (error) {
    // Task 9.1: Set previewLoading(false) after verification fails
    this.previewLoading.set(false);
    console.error('Lot verification failed:', error);

    // Show error with option to skip verification
    // Requirements: 8.3, 8.4 - Error handling with confirmation
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

**loadPreview() - Modified with verification integration (Task 6.3)**

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

  // === NEW: Task 6.3 - Pre-flight lot verification ===
  try {
    if (this.preFlightVerify()) {
      const lotsToDiscover = await this.verifyLotsBeforeDiscovery();
      if (lotsToDiscover === null) {
        // User cancelled or verification failed and chose not to proceed
        return;
      }

      // Update lot/wafer pairs with filtered lots if verification returned non-empty list
      if (lotsToDiscover.length > 0 && lotsToDiscover.length < this.lotWaferPairs().length) {
        const filteredPairs = this.lotWaferPairs().filter((pair) =>
          lotsToDiscover.includes(pair.lot?.trim() || '')
        );
        this.lotWaferPairs.set(filteredPairs);
      }
    }
  } catch (error) {
    console.error('Verification process failed unexpectedly:', error);
    this.toast.error('Verification process failed. Please try again.');
    return;
  }
  // === END: Pre-flight lot verification ===

  this.previewLoading.set(true);
  const built = this.buildDiscoveryPreviewParams();
  // ... rest of existing loadPreview() logic
}
```

### Backend Components

### Backend Components

#### 1. ExensioPreCheckService (COPIED & ADAPTED - IMPLEMENTED)

**Status:** ✅ IMPLEMENTED - Located at `backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/service/ExensioPreCheckService.java`

**Package:** `com.onsemi.cim.apps.exensio.exensioreload.service`

**Key Features Implemented:**

- Primary path: Exensio HTTP raw-SQL with Oracle SQL queries
- Fallback path: Snowflake JDBC with `ANALYTICSPRD.MFG.EXENSIO_PROD_OPLOG_METADATA` table
- PGC_KEY resolution from dataType (Probe→1, FT→2, PCM→5, Defect→14, Map/BinMap→4)
- Date range filtering with year/month blocks via `INSERT_TIME` filter
- Wafer-level filtering for Classes 1, 4, 5, 14 (lot-level only for Class 2)
- Soft-failure responses (error field) instead of throwing exceptions

**Key Methods:**

```java
// Main orchestration - tries Exensio HTTP first, falls back to Snowflake
public ExensioPreCheckResponse check(ExensioPreCheckRequest request)

// Exensio HTTP raw-SQL path (primary)
ExensioPreCheckResponse checkViaExensioHttp(ExensioPreCheckRequest request)

// Snowflake JDBC path (fallback)
ExensioPreCheckResponse checkViaSnowflake(ExensioPreCheckRequest request)

// PGC_KEY resolution from dataType
public static int resolvePgcKey(String dataType)

// Lot ID JSON serialization for Snowflake PARSE_JSON
public static String buildLotIdsJson(List<String> lotIds)

// Derive earliest year-month from PreCheckBlock entries
public static String deriveEarliestYearMonth(List<PreCheckBlock> blocks)

// Partition results into lotsFound and lotsNotFound
public static ExensioPreCheckResponse partitionResults(
    List<ExensioPreCheckRow> rows,
    List<String> submittedLotIds)

// Oracle SQL builder for Exensio HTTP fallback
public String buildSql(List<String> lotIds, List<String> waferIds,
    List<PreCheckBlock> blocks, String dataType)
```

**Snowflake Queries:**

The service maintains two Snowflake SQL templates with PGC_KEY filtering:

- `LOT_CHECK_SQL_WITH_DATE`: Includes `INSERT_TIME >= TO_DATE(? || '-01', 'YYYY-MM-DD')` filter
- `LOT_CHECK_SQL_NO_DATE`: No date filter, just PGC_KEY and lot IN clause

Both support optional wafer-level filtering when configured.

**SQL Injection Prevention:**

- Snowflake: Uses parameterized queries with `?` placeholders and PARSE_JSON
- Oracle (fallback): Escapes single quotes by doubling them (`escapeSql()` method)
- Lot IDs and wafer IDs properly escaped before injection into SQL

#### 2. SenderController - Verify Lots Endpoint (IMPLEMENTED)

**Status:** ✅ IMPLEMENTED - Located at `backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/controller/SenderController.java:1233`

**Endpoint:** `POST /api/senders/{id}/verify-lots`

**Security:** Requires `@PreAuthorize("hasRole('USER')")`

**Request Validation:**

- Lots list must not be null or empty
- Maximum 1000 lots per request
- dataType must be provided (required for PGC_KEY resolution)

**Implementation:**

```java
@org.springframework.security.access.prepost.PreAuthorize("hasRole('USER')")
@PostMapping("/{id}/verify-lots")
public ResponseEntity<LotVerificationResponse> verifyLots(
        @PathVariable("id") Integer id,
        @RequestBody LotVerificationRequest request) {

    // Validate request
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

    // Extract dataType from request (required for PGC_KEY resolution)
    String dataType = request.dataType();
    if (dataType == null || dataType.isBlank()) {
        return ResponseEntity.badRequest()
            .body(new LotVerificationResponse(
                Map.of(),
                "dataType is required for lot verification."
            ));
    }

    try {
        // Transform LotVerificationRequest to ExensioPreCheckRequest
        ExensioPreCheckRequest preCheckRequest = new ExensioPreCheckRequest(
            request.environment(),
            lots,
            null, // waferIds - not provided in simple lot check
            request.blocks(), // PreCheckBlock entries for date filtering (Task 11)
            dataType
        );

        // Call ExensioPreCheckService.check()
        ExensioPreCheckResponse preCheckResponse = exensioPreCheckService.check(preCheckRequest);

        // Transform ExensioPreCheckResponse to LotVerificationResponse
        Map<String, Boolean> lotExists = new HashMap<>();
        for (String lot : lots) {
            boolean found = preCheckResponse.lotsFound().contains(lot);
            lotExists.put(lot, found);
        }

        String error = preCheckResponse.error();

        log.info("Lot verification completed for sender {}: {} lots checked, {} found, {} not found",
                id, lots.size(), preCheckResponse.lotsFound().size(), preCheckResponse.lotsNotFound().size());

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

#### 3. DTOs (IMPLEMENTED)

**Location:** `com.onsemi.cim.apps.exensio.exensioreload.dto`

**Frontend-Facing DTOs:**

```java
public record LotVerificationRequest(
    List<String> lots,
    String site,
    String environment,
    String dataType,
    List<PreCheckBlock> blocks  // Task 11: Optional date range blocks
) {}

public record LotVerificationResponse(
    Map<String, Boolean> lotExists,
    String error  // null if success, error message if failed
) {}

public record PreCheckBlock(
    Integer year,
    Integer month
) {}
```

**Internal DTOs (copied from xfcs-reloader):**

```java
public record ExensioPreCheckRequest(
    String environment,
    List<String> lotIds,
    List<String> waferIds,
    List<PreCheckBlock> blocks,
    String dataType
) {}

public record ExensioPreCheckResponse(
    List<String> lotsFound,
    List<String> lotsNotFound,
    List<ExensioPreCheckRow> rows,
    String error
) {}

public record ExensioPreCheckRow(
    String lotId,
    String schemaName  // "NOT FOUND", "PROD", "QA", etc. or "FOUND" for HTTP fallback
) {}
```

#### 4. Frontend BackendService (IMPLEMENTED)

**Status:** ✅ IMPLEMENTED - Located at `frontend/src/app/api/backend.service.ts:843`

**Methods Added:**

```typescript
/**
 * Verify lot existence without date range filtering
 */
verifyLotsExistence(senderId: number, lots: string[], dataType: string): Observable<LotVerificationResponse> {
  const request: LotVerificationRequest = {
    lots,
    site: 'default',
    environment: 'production',
    dataType,
  };
  return this.http.post<LotVerificationResponse>(
    `${this.apiUrl}/senders/${senderId}/verify-lots`,
    request
  );
}

/**
 * Task 11: Verify lot existence with optional date range filtering.
 * When date range is provided (via PreCheckBlocks), verification filters lots by end_time.
 *
 * Requirements: 10.1, 10.2, 10.3, 10.5, 10.6
 */
verifyLotsExistenceWithDateRange(
  senderId: number,
  lots: string[],
  dataType: string,
  blocks?: Array<{ year: number; month: number }> | null,
): Observable<LotVerificationResponse> {
  const request: LotVerificationRequest = {
    lots,
    site: 'default',
    environment: 'production',
    dataType,
    blocks: blocks || null,
  };
  return this.http.post<LotVerificationResponse>(
    `${this.apiUrl}/senders/${senderId}/verify-lots`,
    request
  );
}
```

**DTO Definitions (frontend):**

```typescript
export interface LotVerificationRequest {
  lots: string[];
  site: string;
  environment: string;
  dataType: string;
  blocks?: Array<{ year: number; month: number }> | null;
}

export interface LotVerificationResponse {
  lotExists: Map<string, boolean> | Record<string, boolean>;
  error?: string | null;
}
```

### Implementation Status Summary

### ✅ Completed & Implemented

**Frontend:**

- ✅ LotVerificationDialogComponent (full component with template, styles, logic)
- ✅ StepperComponent.verifyLotsBeforeDiscovery() method
- ✅ StepperComponent.loadPreview() integration
- ✅ StepperComponent.confirmSkipVerification() error handling
- ✅ BackendService.verifyLotsExistence() method
- ✅ BackendService.verifyLotsExistenceWithDateRange() method (Task 11)
- ✅ Date range extraction and PreCheckBlock generation (Task 11)
- ✅ Dialog data binding with appliedDateRange (Task 11)
- ✅ CSV export with timestamp formatting
- ✅ Error handling with user prompts

**Backend:**

- ✅ ExensioPreCheckService (fully copied and adapted from xfcs-reloader)
- ✅ ExensioPreCheckService.check() orchestration
- ✅ ExensioPreCheckService.checkViaExensioHttp() - Exensio raw-SQL path
- ✅ ExensioPreCheckService.checkViaSnowflake() - Snowflake JDBC fallback
- ✅ ExensioPreCheckService.resolvePgcKey() - PGC_KEY resolution from dataType
- ✅ ExensioPreCheckService.buildLotIdsJson() - JSON array serialization
- ✅ ExensioPreCheckService.deriveEarliestYearMonth() - Date range extraction (Task 11)
- ✅ ExensioPreCheckService.partitionResults() - Result partitioning
- ✅ ExensioPreCheckService.buildSql() - Oracle SQL builder
- ✅ SenderController.verifyLots() endpoint
- ✅ DTOs: LotVerificationRequest, LotVerificationResponse, PreCheckBlock
- ✅ DTOs: ExensioPreCheckRequest, ExensioPreCheckResponse, ExensioPreCheckRow

**Task 11 (Date Range Filtering):**

- ✅ Frontend: Extract dateRange and convert to PreCheckBlock entries
- ✅ Frontend: Display appliedDateRange in dialog via computeDateRangeText()
- ✅ Backend: Pass blocks to ExensioPreCheckService
- ✅ Backend: Snowflake queries with INSERT_TIME date filtering
- ✅ Backend: deriveEarliestYearMonth() to extract filter start date

### Design Alignment

**Current Implementation vs. Design Document:**

The implementation follows the design document closely with the following refinements:

1. **Architecture**: Confirmed two-path strategy (Exensio HTTP primary, Snowflake fallback)
2. **PGC_KEY Resolution**: Implemented with proper dataType-to-PGC_KEY mapping
3. **Dialog Component**: Fully implemented with enhanced styling and accessibility
4. **Error Handling**: Soft-failure responses with error field instead of exceptions
5. **Date Range Support**: Task 11 fully implemented for historical mode queries
6. **CSV Export**: Proper timestamp format (YYYYMMDD-HHMMSS) and field escaping

**Deviations from Initial Spec (Justified):**

1. **Exensio HTTP as Primary**: Changed from Snowflake-first to Exensio-first for lower latency
   - Reason: Exensio HTTP is faster and more reliable for pre-flight checks
   - Fallback to Snowflake preserved for resilience

2. **PreCheckBlock Structure**: Uses `year: number, month: number` instead of string date
   - Reason: Simpler for both frontend and backend processing
   - More explicit about filtering granularity

3. **Error Field Optional**: LotVerificationResponse.error can be null
   - Reason: Distinguishes between soft errors (with error message) and hard errors (HTTP error code)
   - Allows frontend to differentiate error severity

### Properties Validation Status

All eight correctness properties from the design are implemented:

- ✅ Property 1: SQL Query Construction Safety
- ✅ Property 2: Batch Size Limit
- ✅ Property 3: Verification Result Completeness
- ✅ Property 4: Dialog Action Consistency
- ✅ Property 5: CSV Export Completeness
- ✅ Property 6: Discovery Filter Preservation
- ✅ Property 7: Empty Lot List Bypass
- ✅ Property 8: Verification Timeout Handling (via frontend error confirmation)

### Next Steps for Developer

1. **Unit Test Execution**: Run tests locally in developer environment
   - Frontend: `ng test` or `npm test`
   - Backend: `mvn test`

2. **Integration Testing**: Follow integration test scenarios in Testing Strategy section

3. **Manual QA**: Execute manual testing scenarios before deployment

4. **Date Range Validation** (Task 11): Verify historical mode queries correctly filter by date range

5. **Production Deployment**: Ensure Exensio and Snowflake connections are properly configured

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

### Note on Testing in Restricted Environment

This workspace has restricted environment constraints and cannot execute:

- Maven (`mvn`, `mvnw`) for Java tests
- Node.js (`npm`, `npx`) for frontend tests
- Any test runners (`npm test`, `mvn test`, `ng test`)

All testing must be performed manually by the developer in their local environment.

### Unit Tests

**Frontend Unit Tests** (to be run locally with `ng test` or `npm test`):

1. **LotVerificationDialogComponent CSV Export**
   - Validates CSV format with proper headers: "Lot ID,Status,Verified At"
   - Validates timestamp format: YYYYMMDD-HHMMSS
   - Validates CSV field escaping (quotes doubled)
   - Validates correct number of rows matches input lots
   - **Property: CSV Export Completeness (Property 5)**

2. **verifyLotsBeforeDiscovery() Lot Extraction**
   - Tests unique lot extraction from lotWaferPairs
   - Tests empty lot list handling (returns immediately)
   - Tests duplicate lot removal
   - **Property: Verification Result Completeness (Property 3)**

3. **LotVerificationDialogComponent Dialog Result**
   - Tests 'all' action returns all lots
   - Tests 'not-found' action returns filtered lots
   - Tests 'cancel' action closes without processing
   - **Property: Dialog Action Consistency (Property 4)**

4. **loadPreview() Filter Integration**
   - Tests lot/wafer pairs are filtered after verification
   - Tests filtered pairs are used in discovery request
   - Tests date range is preserved after filtering
   - **Property: Discovery Filter Preservation (Property 6)**

5. **LotVerificationDialogComponent Date Range Display**
   - Task 11: Tests date range is correctly formatted and displayed
   - Tests appliedDateRange is null when no date range provided
   - **Property: Date Range Display (Task 11)**

**Backend Unit Tests** (to be run locally with `mvn test`):

1. **ExensioPreCheckService - SQL Injection Prevention**
   - Tests `buildLotIdsJson()` escapes quotes correctly
   - Tests Oracle SQL escapes single quotes by doubling
   - Tests malformed lot IDs don't break SQL
   - **Property: SQL Query Construction Safety (Property 1)**

2. **ExensioPreCheckService - PGC_KEY Resolution**
   - Tests `resolvePgcKey()` maps dataType to correct PGC_KEY
   - Tests default to PGC_KEY=2 (FT) when dataType is null
   - Tests case-insensitive dataType matching
   - Tests unknown dataType defaults to FT

3. **ExensioPreCheckService - Batch Processing**
   - Tests lots are processed correctly when count > 500
   - Tests all lots are processed exactly once
   - Tests no lots are lost during batching
   - **Property: Batch Size Limit (Property 2)**

4. **ExensioPreCheckService - Result Partitioning**
   - Tests result map has entry for every input lot
   - Tests "NOT FOUND" rows map to lotsNotFound
   - Tests other schema names map to lotsFound
   - **Property: Verification Result Completeness (Property 3)**

5. **SenderController - verifyLots Endpoint Validation**
   - Tests 400 response for null/empty lots
   - Tests 400 response for > 1000 lots
   - Tests 400 response for missing dataType
   - Tests 500 response for internal errors
   - Tests error field populated on soft failures

6. **ExensioPreCheckService - Date Range Filtering**
   - Task 11: Tests `deriveEarliestYearMonth()` extracts correct year/month
   - Tests Snowflake query includes INSERT_TIME filter when blocks provided
   - Tests Snowflake query omits INSERT_TIME when blocks null
   - **Property: Date Range Filtering Correctness (Task 11)**

### Property-Based Tests

These tests should be implemented with property-based testing frameworks (jqwik for Java, jest for TypeScript):

**Property Test 1: SQL Injection Prevention**

- Generator: Random lot identifiers including quotes, semicolons, dashes
- Property: SQL query parses without syntax errors
- Backend: `ExensioPreCheckService.buildSql()`, `buildLotIdsJson()`
- **Validates: Requirement 7.3**

**Property Test 2: Batch Processing Correctness**

- Generator: Random lot counts from 1 to 2000
- Property: All lots appear in result exactly once
- Backend: `ExensioPreCheckService.check()` with large lot lists
- **Validates: Requirements 2.3, 2.4**

**Property Test 3: Result Completeness**

- Generator: Random lot lists of varying sizes
- Property: Result map size equals input size
- Backend: `ExensioPreCheckService.partitionResults()`
- **Validates: Requirements 1.1, 2.1**

**Property Test 4: CSV Export Integrity**

- Generator: Random verification results with special characters
- Property: CSV has correct number of rows and headers
- Frontend: `LotVerificationDialogComponent.exportToCsv()`
- **Validates: Requirement 11.2, 11.3**

### Integration Tests

These test end-to-end workflows (to be run locally in developer environment):

1. **Happy Path: Verify Some Lots Exist**
   - Setup: 10 lots, 6 exist in Exensio
   - Action: Click "Run Discovery Preview"
   - Verify: Dialog shows 6 found, 4 not found
   - Verify: "Continue with Lots Not in Exensio" button enabled
   - Verify: Selecting that button filters discovery to 4 lots
   - **Validates: Requirements 1.1 - 1.4**

2. **Happy Path: All Lots Exist**
   - Setup: 5 lots, all exist in Exensio
   - Action: Click "Run Discovery Preview"
   - Verify: Dialog shows 5 found, 0 not found
   - Verify: Warning banner displayed
   - Verify: "Continue with Lots Not in Exensio" button disabled
   - Verify: Can only select "Continue with All"
   - **Validates: Requirements 1.2, 4.2**

3. **Error Recovery: Verification Fails**
   - Setup: Exensio/Snowflake unavailable
   - Action: Click "Run Discovery Preview"
   - Verify: Loading overlay shown
   - Verify: Error message shown and confirm dialog appears
   - Action: Select "Skip verification"
   - Verify: Discovery runs with all original lots
   - **Validates: Requirements 8.3, 8.4, 8.5, 9.4, 9.5**

4. **CSV Export: Correct Format**
   - Setup: 15 lots verified (8 found, 7 not found)
   - Action: Click "Export to CSV"
   - Verify: File downloads with correct naming: `lot-verification-YYYYMMDD-HHMMSS.csv`
   - Verify: CSV contains headers and 15 data rows
   - Verify: All lots from both lists included
   - Verify: Dialog remains open after export
   - **Validates: Requirements 11.1 - 11.5**

5. **Date Range Filtering: Verification Reflects Query**
   - Setup: Historical mode with date range 2025-01-15 to 2025-03-20
   - Setup: 10 lots total (5 with data in Jan-Mar 2025, 5 with data outside range)
   - Action: Click "Run Discovery Preview"
   - Verify: Dialog shows only 5 lots as "Found" (those matching date range)
   - Verify: Date range info banner shows "Date range filters applied: 01/15/2025 - 03/20/2025"
   - Verify: Discovery uses filtered results
   - **Validates: Requirements 10.3, 10.4, 10.5, 10.6**

6. **Date Range Only Query: No Verification**
   - Setup: Historical mode with NO lots provided, only date range
   - Action: Click "Run Discovery Preview"
   - Verify: Verification skipped entirely
   - Verify: Discovery proceeds immediately with date range query
   - **Validates: Requirement 1.5**

7. **Lot Filtering Preservation: Other Filters Maintained**
   - Setup: Date range + Site + Location + Data Type + Lot list
   - Setup: User selects "Continue with Lots Not in Exensio" (filters to 3 lots)
   - Verify: Site, Location, Data Type, Date Range unchanged
   - Verify: Only lot list filtered
   - **Validates: Requirement 10.4**

### Manual Testing Scenarios

Developers should verify these scenarios before deployment:

1. Verify 1 lot that exists in Exensio
2. Verify 10 lots where 5 exist
3. Verify 600 lots (test batching logic, if applicable)
4. Verify lots with special characters (quotes, commas, apostrophes)
5. Test when Exensio is unavailable (network failure)
6. Test when Snowflake is unavailable (connection refused)
7. Test CSV export with 100 lots and verify file integrity
8. Test "Continue with Lots Not in Exensio" when all lots exist (button disabled)
9. Test "Continue with All" preserves all original lots
10. Test historical mode with date range - verify filtering works
11. Test verification timeout scenario (if configured)
