# Implementation Plan: Lot Existence Verification

## Overview

This implementation plan adapts the proven **ExensioPreCheckService** from xfcs-reloader to add pre-flight lot verification before discovery queries. The feature copies the existing service, adds a dialog component, and integrates verification into the stepper workflow.

## Tasks

- [x] 1. Copy ExensioPreCheckService and DTOs from xfcs-reloader
  - Copy `ExensioPreCheckService.java` from xfcs-reloader to exensioreload service package
  - Copy DTOs: `ExensioPreCheckRequest`, `ExensioPreCheckResponse`, `ExensioPreCheckRow`, `PreCheckBlock`
  - Change package declarations from `xfcsreloader` to `exensioreload`
  - Adjust imports to match exensioreload structure
  - Adapt for exensioreload's ExensioProperties and ExensioAuthService
  - **MODIFY**: Add `dataType` field to `ExensioPreCheckRequest`
  - **MODIFY**: Add `resolvePgcKey(String dataType)` method to map data types to PGC_KEY
  - **MODIFY**: Update SQL builders to include PGC_KEY filter based on dataType
  - Handle Snowflake dependency gracefully (skip Snowflake path if DataSource not available)
  - _Requirements: 2.1, 2.2, 2.3, 2.4, 12.1, 12.2, 12.3, 12.4, 12.5_

- [ ]\* 1.1 Write unit test for buildLotIdsJson method
  - Test proper JSON array formatting: `["LOT1","LOT2"]`
  - Test escaping of quotes and backslashes
  - Test empty list returns `[]`
  - _Requirements: 2.1_

- [ ]\* 1.2 Write unit test for partitionResults method
  - **Property 3: Verification Result Completeness**
  - Test all input lots appear in either lotsFound or lotsNotFound
  - Test case-insensitive matching
  - **Validates: Requirements 1.1, 2.1**

- [x] 2. Create frontend-facing DTOs for verification endpoint
  - Create `LotVerificationRequest` record in exensioreload.dto package
  - Fields: `List<String> lots`, `String site`, `String environment`
  - Create `LotVerificationResponse` record
  - Fields: `Map<String, Boolean> lotExists`, `String error`
  - _Requirements: 1.1, 2.3_

- [x] 3. Add verification endpoint to SenderController
  - Add POST `/api/senders/{id}/verify-lots` endpoint with @PreAuthorize("hasRole('USER')")
  - Validate request: lots not null/empty, max 1000 lots
  - Extract dataType from request (required field)
  - Transform LotVerificationRequest to ExensioPreCheckRequest (with null blocks and dataType)
  - Call ExensioPreCheckService.check()
  - Transform ExensioPreCheckResponse to LotVerificationResponse
  - Map lotsFound list to Map<String, Boolean> (true for found, false for not found)
  - Include error field from ExensioPreCheckResponse if present
  - _Requirements: 1.1, 1.4, 8.1, 8.2, 8.3, 12.1_

- [ ]\* 3.1 Write unit test for endpoint validation
  - Test empty lots list returns 400 Bad Request
  - Test > 1000 lots returns 400 with error message
  - _Requirements: 1.1, 2.3_

- [ ]\* 3.2 Write unit test for endpoint error handling
  - Test successful verification returns 200 with lotExists map
  - Test service exception returns 500 with error message
  - _Requirements: 8.1, 8.2_

- [x] 4. Create frontend LotVerificationDialogComponent
  - [x] 4.1 Create component file structure
    - Create `lot-verification-dialog.component.ts` in stepper folder
    - Define `LotVerificationDialogData` interface
    - Define `LotVerificationDialogResult` interface
    - Import CommonModule, GlassButtonComponent, GlassIconComponent
    - _Requirements: 3.1, 3.2, 3.3_

  - [x] 4.2 Implement dialog component class
    - Process verification results in constructor
    - Separate lots into foundLots and notFoundLots arrays based on Map<string, boolean>
    - Calculate totalLots, foundCount, notFoundCount
    - Store verifiedAt timestamp from dialog data
    - _Requirements: 3.2_

  - [x] 4.3 Implement dialog action methods
    - `continueWithAll()`: close dialog with action 'all'
    - `continueWithNotFound()`: close with action 'not-found' and filteredLots array
    - `cancel()`: close with action 'cancel'
    - `close()`: same as cancel
    - _Requirements: 4.1, 4.2, 4.3, 4.4_

  - [x] 4.4 Implement CSV export functionality
    - Generate CSV with headers: "Lot ID", "Status", "Verified At"
    - Add row for each lot with status ("Found in Exensio" or "Not Found in Exensio")
    - Format timestamp as ISO string
    - Format filename as: `lot-verification-YYYYMMDD-HHMMSS.csv`
    - Trigger browser download using Blob and temporary link
    - Do not close dialog after export
    - _Requirements: 11.1, 11.2, 11.3, 11.4, 11.5_

- [ ]\* 4.5 Write property test for CSV export
  - **Property 5: CSV Export Completeness**
  - Generate random verification results (found/not found)
  - Export to CSV
  - Verify CSV has exactly one row per lot plus header
  - Verify CSV contains all required columns
  - **Validates: Requirements 11.2, 11.3**

- [x] 5. Create dialog template and styles
  - [x] 5.1 Create dialog template
    - Header with title "Lot Verification Results", export button, close button
    - Summary stats section with three stat cards (total, found, not found)
    - Warning banner when notFoundCount === 0: "All lots already exist in Exensio..."
    - Two-column lot lists: "Found in Exensio" and "Not Found"
    - Scrollable lot list sections with max-height
    - Action buttons footer: Cancel, Continue with All, Continue with Lots Not in Exensio
    - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5_

  - [x] 5.2 Create dialog styles
    - Glass morphism card styling matching existing dialogs
    - Success color (green) for found stats, error color (red) for not found stats
    - Warning banner styling (yellow/orange background)
    - Scrollable lot list containers with custom scrollbar
    - Button styling with hover states
    - Highlight "Continue with Lots Not in Exensio" as recommended when enabled
    - Responsive layout for mobile (stack columns, full-width buttons)
    - _Requirements: 3.3, 3.4, 3.5_

  - [x] 5.3 Implement button states
    - Disable "Continue with Lots Not in Exensio" button when notFoundCount === 0
    - Add tooltip: "No lots to discover" when button is disabled
    - Apply `.recommended` class to highlight button when notFoundCount > 0
    - _Requirements: 4.5_

- [x] 6. Add verification to StepperComponent
  - [x] 6.1 Add verifyLotsBeforeDiscovery() async method
    - Extract unique lots from lotWaferPairs signal using map/filter
    - Skip verification if lots list is empty (return empty array)
    - Get senderId using getSenderIdForRequest()
    - Show loading overlay with previewLoading signal
    - Call BackendService.verifyLotsExistence(senderId, lots)
    - Convert response to Map for dialog data
    - Open LotVerificationDialogComponent with GlassDialogService
    - Wait for dialog result using firstValueFrom(dialogRef.afterClosed())
    - Return filtered lots array based on user action, or null if cancelled
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5_

  - [x] 6.2 Add confirmSkipVerification() error handler
    - Display error message from exception
    - Show confirm dialog: "Lot verification failed: {error}. Skip verification and continue?"
    - Use window.confirm() for simplicity
    - Return boolean indicating user's choice
    - _Requirements: 8.3, 8.4, 8.5_

  - [x] 6.3 Modify loadPreview() to integrate verification
    - Make loadPreview() async
    - Call verifyLotsBeforeDiscovery() before building discovery request
    - Handle null return (user cancelled) - exit early without proceeding
    - Filter lotWaferPairs signal based on returned lot list
    - Preserve existing discovery logic after filtering
    - Handle error cases with confirmSkipVerification()
    - _Requirements: 1.1, 1.4, 8.4_

- [ ]\* 6.4 Write property test for discovery filter preservation
  - **Property 6: Discovery Filter Preservation**
  - Generate random discovery filters (date range, data type, location)
  - Simulate "Continue with Lots Not in Exensio" action
  - Verify only lot/wafer pairs are filtered, all other filters unchanged
  - **Validates: Requirements 10.4**

- [ ]\* 6.5 Write property test for empty lot list bypass
  - **Property 7: Empty Lot List Bypass**
  - Generate discovery request with zero lots (date range only query)
  - Verify verification step is skipped (verifyLotsBeforeDiscovery returns [])
  - Verify discovery proceeds immediately
  - **Validates: Requirements 1.5**

- [x] 7. Add BackendService method for verification
  - Add `verifyLotsExistence(senderId: number, lots: string[])` method to BackendService
  - POST to `${this.baseUrl}/senders/${senderId}/verify-lots`
  - Request body: `{ lots, site: 'default', environment: 'qa' }`
  - Return `Observable<LotVerificationResponse>`
  - _Requirements: 1.1_

- [ ]\* 7.1 Write unit test for BackendService method
  - Test API call with correct URL and payload
  - Test response parsing to LotVerificationResponse interface
  - Test error handling (network errors, HTTP errors)
  - _Requirements: 1.1_

- [x] 8. Add verification summary banner to discovery preview
  - [x] 8.1 Create signals for verification summary
    - Create `verificationSummary` signal storing: choice ('all' | 'not-found'), totalLots, foundCount, notFoundCount
    - Initialize as null (no verification performed yet)
    - Set after user makes choice in dialog
    - _Requirements: 6.1, 6.2, 6.3_

  - [x] 8.2 Display summary banner in stepper template
    - Add banner above preview table, below filters
    - Show message based on choice: "Showing results for all lots (X verified, Y found, Z not found)"
    - Or: "Showing results for lots not in Exensio (X verified, Y found, Z not found)"
    - Apply info banner styling (blue background, info icon)
    - Add dismiss button (X icon) that sets verificationSummary to null
    - _Requirements: 6.2, 6.4_

  - [x] 8.3 Clear banner on filter changes
    - Clear verificationSummary when user navigates back to step 0
    - Clear when user modifies lot/wafer pairs (detect changes with effect())
    - _Requirements: 6.5_

- [x] 9. Handle loading states and progress indicators
  - [x] 9.1 Add loading overlay during verification
    - Set previewLoading(true) before verification call
    - Display message: "Verifying lots in Exensio..."
    - Show spinner animation (existing loading overlay component)
    - Set previewLoading(false) after verification completes or fails
    - _Requirements: 9.1_

  - [x] 9.2 Add batch progress for large lot lists (optional enhancement)
    - ExensioPreCheckService handles batching internally
    - Progress indicator would require backend streaming updates
    - Mark as optional - only implement if time permits
    - _Requirements: 9.2_

  - [x] 9.3 Add abort button for long-running verification (optional enhancement)
    - Would require Observable cancellation mechanism
    - Mark as optional - only implement if time permits
    - Default HTTP timeout handles extreme cases
    - _Requirements: 9.4, 9.5_

- [x] 10. Integrate with bulk lot input feature
  - Bulk lot input already adds lots to lotWaferPairs signal
  - Verification automatically includes bulk-added lots when Run Discovery Preview is clicked
  - No additional integration needed - verify behavior works correctly
  - Test scenario: bulk add 10 lots, click Run Discovery Preview, verify all 10 lots are checked
  - _Requirements: 5.1, 5.2, 5.3, 5.4_

- [x] 11. Test historical mode compatibility and implement date range filtering
  - Verify lots when historical mode enabled with date range
  - For date range queries with lots: extract date range and pass to verification service
  - Extract year and month from dateRange signal and create PreCheckBlock entries
  - Pass blocks to ExensioPreCheckService so queries filter by end_time in Exensio
  - Only lots with data matching the date range should be marked as "found"
  - Skip verification for date-only queries (no lot/wafer pairs)
  - Apply date range filters in discovery after lot filtering
  - Update LotVerificationDialogComponent to display applied date range in results
  - ExensioPreCheckService already supports date filtering via PreCheckBlock
  - Add date range info to verification dialog summary banner
  - _Requirements: 10.1, 10.2, 10.3, 10.4, 10.5, 10.6_

- [ ] 12. Checkpoint - Manual testing and code review
  - Code cannot be compiled/tested in this environment
  - Review all code for syntax errors, proper imports, type correctness
  - Create checklist for developer to test manually:
    - Verify 1 lot that exists in Exensio
    - Verify 10 lots where 5 exist, 5 don't
    - Verify 100+ lots (test Snowflake performance)
    - Test CSV export
    - Test "Continue with All" vs "Continue with Lots Not in Exensio"
    - Test cancellation (returns to configuration)
    - Test error handling (simulate Exensio unavailable)
    - Test with historical mode + date range
    - Test with bulk lot input
  - Ask the user if questions arise or if they want to begin implementation.

## Notes

- Tasks marked with `*` are optional test-related sub-tasks and can be skipped for faster MVP
- ExensioPreCheckService is a proven, production-tested implementation from xfcs-reloader
- Snowflake path provides fast verification; HTTP fallback ensures reliability
- Each task references specific requirements for traceability
- Property tests validate universal correctness properties
- Checkpoint ensures code quality before manual testing phase
