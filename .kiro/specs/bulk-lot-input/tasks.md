# Implementation Plan: Bulk Lot Input

## Overview

This implementation plan breaks down the bulk lot input feature into discrete coding tasks. The feature adds a modal dialog for pasting or uploading multiple lot identifiers at once in Step 1 of the discovery stepper. Implementation follows the existing Angular standalone component pattern and integrates with the current signal-based state management.

## Tasks

- [x] 1. Create BulkLotInputDialogComponent with basic structure
  - Create new file `frontend/src/app/stepper/bulk-lot-input-dialog.component.ts`
  - Implement standalone component with imports for CommonModule, FormsModule, GlassButtonComponent, GlassIconComponent
  - Define component template with modal structure (header, textarea, actions)
  - Add basic SCSS styling for glass-modal appearance
  - _Requirements: 1.2, 1.3, 1.6_

- [x] 2. Implement input parsing logic
  - [x] 2.1 Create parsing function for delimiter detection and splitting
    - Implement `parseLotInput(input: string): ParsedLotInput` function
    - Handle comma, newline, semicolon, and mixed delimiter cases
    - Trim whitespace from each lot identifier
    - Filter out empty entries
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5, 2.6, 2.7_

  - [ ]\* 2.2 Write property test for delimiter parsing
    - **Property 1: Delimiter Parsing**
    - **Validates: Requirements 2.1, 2.2, 2.3, 2.4**

  - [ ]\* 2.3 Write property test for whitespace trimming
    - **Property 2: Whitespace Trimming**
    - **Validates: Requirements 2.5**

  - [ ]\* 2.4 Write property test for empty entry filtering
    - **Property 3: Empty Entry Filtering**
    - **Validates: Requirements 2.6**

  - [ ]\* 2.5 Write property test for case preservation
    - **Property 4: Case Preservation**
    - **Validates: Requirements 2.7**

- [x] 3. Implement validation logic
  - [x] 3.1 Create validation function
    - Implement `validateLots(parsedInput: ParsedLotInput, existingLots: string[]): ValidationResult` function
    - Detect invalid lots (empty after trimming)
    - Detect duplicates within input
    - Detect duplicates with existing lots
    - Generate warning messages
    - Apply 1000 lot maximum limit with truncation
    - _Requirements: 4.1, 4.2, 4.3, 4.4, 8.1, 8.2_

  - [ ]\* 3.2 Write property test for validation correctness
    - **Property 6: Validation Correctness**
    - **Validates: Requirements 4.2, 4.4**

  - [ ]\* 3.3 Write property test for duplicate detection
    - **Property 7: Duplicate Detection**
    - **Validates: Requirements 4.3, 4.4**

  - [ ]\* 3.4 Write edge case test for maximum limit enforcement
    - **Property 13: Maximum Limit Enforcement**
    - **Validates: Requirements 8.2**

- [x] 4. Implement file upload functionality
  - [x] 4.1 Add file input element and upload button to template
    - Add hidden file input with accept=".txt,.csv"
    - Add upload button with icon that triggers file input
    - Implement `triggerFileUpload()` method
    - _Requirements: 1.4, 3.1, 3.2_

  - [x] 4.2 Implement file reading logic
    - Implement `onFileSelected(event: Event)` method using FileReader API
    - Read file as text and populate textarea
    - Handle file read errors with error toast
    - Trigger validation after file load
    - _Requirements: 3.3, 3.4, 3.5_

  - [ ]\* 4.3 Write property test for file content parsing equivalence
    - **Property 5: File Content Parsing Equivalence**
    - **Validates: Requirements 3.4**

- [x] 5. Implement validation UI feedback
  - [x] 5.1 Add validation stats display to template
    - Display valid count, invalid count, duplicate count
    - Bind to signal values from validation result
    - _Requirements: 1.5, 4.4_

  - [x] 5.2 Add warnings section to template
    - Display validation warnings from ValidationResult
    - Style warnings appropriately (info/warning)
    - _Requirements: 4.1, 4.3, 4.5_

  - [x] 5.3 Wire up input change handler
    - Implement `onInputChange()` method
    - Call validation on every input change
    - Update signal values for stats and warnings
    - Debounce validation for large inputs (optional performance enhancement)
    - _Requirements: 4.1, 4.3, 4.4_

- [x] 6. Checkpoint - Ensure dialog component works standalone
  - Ensure all tests pass, ask the user if questions arise.

- [x] 7. Implement dialog actions and integration
  - [x] 7.1 Implement Add Lots action
    - Implement `onAddLots()` method
    - Close dialog with validated lots as result
    - Disable button when valid count is zero
    - _Requirements: 5.1, 5.2_

  - [x] 7.2 Implement Cancel action
    - Implement `onCancel()` method
    - Close dialog without returning data
    - _Requirements: 1.6_

  - [x] 7.3 Set focus to textarea on modal open
    - Implement ngOnInit to set initial focus
    - _Requirements: 7.3_

- [x] 8. Integrate with StepperComponent
  - [x] 8.1 Add "Bulk Add Lots" button to stepper template
    - Add button in lot-wafer-section before pairs-list-compact
    - Add icon and styling consistent with glass design system
    - _Requirements: 1.1_

  - [x] 8.2 Implement onBulkAddLotsClick method
    - Import and inject GlassDialogService
    - Open BulkLotInputDialogComponent with existing lots data
    - Handle dialog result in afterClosed subscription
    - Call addBulkLots with validated lot list
    - _Requirements: 1.2, 5.1_

  - [x] 8.3 Implement addBulkLots private method
    - Map lots to { lot, wafer: '' } pairs
    - Update lotWaferPairs signal with appended entries
    - Display success toast with count
    - Expand lot/wafer section if collapsed
    - _Requirements: 5.1, 5.2, 5.3, 5.4, 6.1, 6.2, 6.3_

  - [ ]\* 8.4 Write property test for append preserves existing
    - **Property 8: Append Preserves Existing**
    - **Validates: Requirements 5.1, 5.3**

  - [ ]\* 8.5 Write property test for wafer field initialization
    - **Property 9: Wafer Field Initialization**
    - **Validates: Requirements 5.2**

  - [ ]\* 8.6 Write property test for duplicate lot addition allowed
    - **Property 10: Duplicate Lot Addition Allowed**
    - **Validates: Requirements 5.4**

  - [ ]\* 8.7 Write property test for toast count accuracy
    - **Property 11: Toast Count Accuracy**
    - **Validates: Requirements 6.1**

  - [ ]\* 8.8 Write property test for UI state synchronization
    - **Property 12: UI State Synchronization**
    - **Validates: Requirements 6.2**

- [x] 9. Add accessibility enhancements
  - [x] 9.1 Add ARIA labels and roles
    - Add aria-label to upload button
    - Add role="dialog" and aria-labelledby to modal
    - Add aria-describedby for validation messages
    - _Requirements: 7.5_

  - [x] 9.2 Test keyboard navigation
    - Verify tab order through dialog elements
    - Test Escape key dismissal (provided by dialog service)
    - Verify focus management
    - _Requirements: 7.1, 7.2, 7.4_

- [x] 10. Final checkpoint and polish
  - [x] 10.1 Test end-to-end flow
    - Test paste functionality with various delimiters
    - Test file upload with .txt and .csv files
    - Test validation feedback with edge cases
    - Test integration with existing lot/wafer pairs
    - Verify toast notifications appear correctly

  - [x] 10.2 Add loading indicator for large inputs (optional)
    - Add loading state signal
    - Show spinner during parsing/validation of >500 lots
    - _Requirements: 8.3_

  - [x] 10.3 Final review and cleanup
    - Ensure all tests pass, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional and can be skipped for faster MVP
- Each task references specific requirements for traceability
- Checkpoints ensure incremental validation
- Property tests validate universal correctness properties
- The feature integrates with existing Angular signals and GlassDialogService
- File uploads are handled client-side using the FileReader API
