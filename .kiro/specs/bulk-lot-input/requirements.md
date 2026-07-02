# Requirements Document

## Introduction

This document describes the requirements for adding bulk lot input functionality to Step 1 (Configuration) of the payload discovery stepper. Currently, users must manually enter lot/wafer pairs one at a time using individual input fields. This enhancement will allow users to paste or upload a list of lots in comma-delimited or newline-separated format, significantly improving efficiency when working with large numbers of lots.

## Glossary

- **Lot**: A manufacturing batch identifier (e.g., "L12345") used to filter discovery results
- **Wafer**: An optional wafer identifier within a lot (e.g., "01", "W1")
- **Discovery**: The process of querying available payload files based on filter criteria
- **Stepper**: The multi-step UI component that guides users through Configuration → Discovery → Monitor
- **Lot_Wafer_Filter**: The input section in Step 1 that accepts lot and optional wafer identifiers for filtering discovery results
- **Bulk_Input_Modal**: A dialog that allows users to paste or upload multiple lot identifiers at once
- **System**: The Exensio Reload web application

## Requirements

### Requirement 1: Bulk Lot Input UI

**User Story:** As a user, I want to paste or upload a list of lots, so that I can quickly filter discovery results without manually entering each lot individually.

#### Acceptance Criteria

1. THE System SHALL display a "Bulk Add Lots" button in the Lot/Wafer Filters section of Step 1
2. WHEN a user clicks the "Bulk Add Lots" button, THE System SHALL open a modal dialog for bulk input
3. THE Bulk_Input_Modal SHALL contain a large text area where users can paste lot identifiers
4. THE Bulk_Input_Modal SHALL contain an upload button to select a text file containing lot identifiers
5. THE Bulk_Input_Modal SHALL display a preview count showing how many lots will be added
6. THE Bulk_Input_Modal SHALL contain "Add Lots" and "Cancel" buttons

### Requirement 2: Input Format Parsing

**User Story:** As a user, I want to paste lots in flexible formats, so that I can use data from various sources without reformatting.

#### Acceptance Criteria

1. WHEN a user pastes text into the bulk input area, THE System SHALL parse lot identifiers separated by commas
2. WHEN a user pastes text into the bulk input area, THE System SHALL parse lot identifiers separated by newlines
3. WHEN a user pastes text into the bulk input area, THE System SHALL parse lot identifiers separated by semicolons
4. WHEN a user pastes text with mixed delimiters, THE System SHALL correctly parse all lot identifiers
5. WHEN parsing input, THE System SHALL trim whitespace from each lot identifier
6. WHEN parsing input, THE System SHALL ignore empty lines and empty entries
7. WHEN parsing input, THE System SHALL preserve the original lot identifier casing

### Requirement 3: File Upload Support

**User Story:** As a user, I want to upload a text file containing lot identifiers, so that I can easily reuse lot lists from previous sessions or external systems.

#### Acceptance Criteria

1. WHEN a user clicks the upload button, THE System SHALL open a file picker dialog
2. THE System SHALL accept text files with extensions .txt, .csv
3. WHEN a user selects a valid file, THE System SHALL read the file contents into the text area
4. WHEN reading a file, THE System SHALL apply the same parsing rules as pasted text
5. IF a file read fails, THEN THE System SHALL display an error message and maintain the current input state

### Requirement 4: Input Validation

**User Story:** As a user, I want to see validation feedback on my bulk input, so that I can correct any issues before adding lots to the filter.

#### Acceptance Criteria

1. WHEN the bulk input contains invalid lot identifiers, THE System SHALL highlight or list the invalid entries
2. THE System SHALL consider a lot identifier valid IF it is non-empty after trimming whitespace
3. THE System SHALL display a warning IF duplicate lot identifiers are detected in the bulk input
4. THE System SHALL display the count of valid lots, invalid lots, and duplicates
5. WHEN the user attempts to add lots with validation errors, THE System SHALL allow proceeding but display a warning

### Requirement 5: Integration with Existing Lot/Wafer Pairs

**User Story:** As a user, I want bulk-added lots to merge with my existing lot/wafer pairs, so that I can combine manual entry with bulk operations.

#### Acceptance Criteria

1. WHEN a user adds lots via bulk input, THE System SHALL append new lot entries to the existing lotWaferPairs array
2. WHEN adding bulk lots, THE System SHALL set the wafer field to empty string for each new entry
3. WHEN bulk lots are added, THE System SHALL preserve any existing lot/wafer pairs that were manually entered
4. IF a bulk-added lot matches an existing lot identifier, THEN THE System SHALL add a new entry anyway (allow duplicates at this stage)
5. WHEN bulk lots are added, THE System SHALL close the modal and return focus to the Configuration step

### Requirement 6: User Feedback and Confirmation

**User Story:** As a user, I want clear feedback when lots are added via bulk input, so that I know the operation succeeded.

#### Acceptance Criteria

1. WHEN bulk lots are successfully added, THE System SHALL display a toast notification showing the count of lots added
2. THE System SHALL update the visible lot/wafer pair list to show the newly added entries
3. IF the lot/wafer pairs section is collapsed, THEN THE System SHALL expand it after bulk lots are added
4. THE System SHALL scroll the newly added lots into view IF they extend beyond the current viewport

### Requirement 7: Accessibility and Usability

**User Story:** As a user with accessibility needs, I want the bulk input feature to be keyboard-accessible and screen-reader friendly, so that I can use it effectively.

#### Acceptance Criteria

1. THE Bulk_Add_Lots_Button SHALL be keyboard-accessible via Tab navigation
2. THE Bulk_Input_Modal SHALL be keyboard-accessible with Tab navigation through all interactive elements
3. WHEN the modal opens, THE System SHALL set focus to the text area
4. THE Bulk_Input_Modal SHALL be dismissible via Escape key
5. THE System SHALL provide appropriate ARIA labels and roles for all bulk input UI elements
6. WHEN validation errors occur, THE System SHALL announce them to screen readers

### Requirement 8: Performance and Limits

**User Story:** As a system administrator, I want reasonable limits on bulk lot input, so that the system remains performant and responsive.

#### Acceptance Criteria

1. THE System SHALL accept up to 1000 lot identifiers in a single bulk input operation
2. IF a user attempts to add more than 1000 lots, THEN THE System SHALL display a warning and truncate to the first 1000
3. WHEN parsing large input (>500 lots), THE System SHALL display a loading indicator
4. THE System SHALL parse and validate bulk input within 2 seconds for inputs up to 1000 lots
5. THE System SHALL maintain UI responsiveness during bulk input operations
