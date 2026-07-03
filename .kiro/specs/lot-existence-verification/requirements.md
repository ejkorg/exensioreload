# Requirements Document

## Introduction

This specification defines a pre-flight lot verification feature that checks lot existence in Exensio **before** executing discovery queries. When users input lots (manually or via bulk input) and click "Run Discovery Preview", the system will first verify which lots exist in Exensio, then present a dialog showing the verification results and asking the user how to proceed: continue with all lots, continue with only non-existent lots, or cancel. This prevents wasted time discovering files for lots that already exist in Exensio and gives users control over which lots to discover.

## Glossary

- **Lot**: A manufacturing batch identifier used to group semiconductor wafers
- **Exensio**: The target system where data is loaded; contains the authoritative record of existing lots
- **Discovery**: The process of searching for files to reload based on filters (site, location, data type, date range, lot/wafer pairs)
- **Discovery_Preview**: The paginated table display showing discovered files matching the user's criteria
- **Raw_SQL_Endpoint**: Exensio API endpoint (`/v1/key/raw-sql`) that accepts SQL queries for efficient bulk lookups
- **Pre_Flight_Verification**: The lot existence check that occurs before discovery queries execute
- **Verification_Dialog**: A modal dialog showing which lots exist/don't exist and asking how to proceed
- **Stepper**: The multi-step wizard component guiding users through Configuration → Discovery → Monitor
- **Bulk_Lot_Input**: Feature allowing users to paste or upload multiple lot identifiers at once

## Requirements

### Requirement 1: Pre-Flight Lot Verification Trigger

**User Story:** As a user preparing to run discovery, I want the system to verify my lots before querying, so that I can make informed decisions about which lots to discover.

#### Acceptance Criteria

1. WHEN a user clicks "Run Discovery Preview" with lot/wafer pairs input, THE System SHALL intercept the request and verify lots first
2. WHEN a user uses bulk lot input, THE System SHALL verify the lots before proceeding to discovery
3. WHEN verification completes successfully, THE System SHALL display a verification dialog showing results
4. WHEN the user chooses an action in the verification dialog, THE System SHALL execute discovery accordingly
5. IF no lots are provided (date range only query), THEN THE System SHALL skip verification and run discovery immediately
6. THE System SHALL use the selected data type to determine the correct PGC_KEY for verification queries

### Requirement 2: Efficient Batch Lot Verification

**User Story:** As a system, I need to verify multiple lots efficiently, so that pre-flight checks don't slow down the user workflow.

#### Acceptance Criteria

1. THE System SHALL use the raw-SQL endpoint to query Exensio for lot existence
2. WHEN verifying lots, THE System SHALL construct SQL query with PGC_KEY filter based on selected data type
3. THE System SHALL map data types to PGC_KEY values: Probe=1, FT/Final Test=2, PCM=5, Defect=14, Map/Binmap/WXML/UPM=4
4. THE System SHALL batch up to 500 lots per query to stay within query size limits
5. WHEN more than 500 lots need verification, THE System SHALL execute multiple batches sequentially
6. THE System SHALL complete verification within 3 seconds for up to 100 lots

### Requirement 3: Verification Results Dialog

**User Story:** As a user who just triggered discovery, I want to see which lots exist in Exensio, so that I can decide how to proceed.

#### Acceptance Criteria

1. WHEN lot verification completes, THE System SHALL display a modal dialog with verification results
2. THE Verification_Dialog SHALL show three counts: total lots input, lots found in Exensio, lots not found in Exensio
3. THE Verification_Dialog SHALL list lot identifiers in two sections: "Found in Exensio" and "Not Found in Exensio"
4. WHEN all lots are found in Exensio, THE System SHALL display a warning message: "All lots already exist in Exensio. Discovery may return files that have already been loaded."
5. WHEN some lots are not found, THE System SHALL highlight the "Continue with lots not in Exensio" option as recommended

### Requirement 4: User Choice Actions

**User Story:** As a user reviewing verification results, I want to choose which lots to discover, so that I can control the discovery scope.

#### Acceptance Criteria

1. THE Verification_Dialog SHALL provide three action buttons: "Continue with All", "Continue with Lots Not in Exensio", and "Cancel"
2. WHEN the user clicks "Continue with All", THE System SHALL run discovery with all originally input lots
3. WHEN the user clicks "Continue with Lots Not in Exensio", THE System SHALL run discovery with only non-existent lots
4. WHEN the user clicks "Cancel", THE System SHALL close the dialog and return to the configuration step without running discovery
5. WHEN the user selects "Continue with Lots Not in Exensio" but all lots exist, THE System SHALL disable this button and show tooltip: "No lots to discover"

### Requirement 5: Bulk Lot Input Integration

**User Story:** As a user adding lots via bulk input, I want verification to happen after parsing, so that I know which lots are valid before discovery.

#### Acceptance Criteria

1. WHEN a user completes bulk lot input and clicks "Add Lots", THE System SHALL add the lots to the lot/wafer pairs
2. WHEN the user then clicks "Run Discovery Preview", THE System SHALL verify all lots including bulk-added ones
3. THE System SHALL deduplicate lot identifiers before verification to avoid redundant queries
4. WHEN verification finds lots already in Exensio, THE System SHALL show them in the verification dialog
5. THE Verification_Dialog SHALL indicate which lots came from bulk input vs manual input (if distinguishable)

### Requirement 6: Verification Result Summary Banner

**User Story:** As a user who proceeded with discovery, I want to see a summary of what was verified, so that I remember my choice during staging.

#### Acceptance Criteria

1. WHEN discovery completes after verification, THE System SHALL display a summary banner above the preview table
2. THE Summary_Banner SHALL show the verification choice made: "Showing results for all lots" or "Showing results for lots not in Exensio"
3. THE Summary_Banner SHALL show counts: X lots verified, Y found in Exensio, Z not found
4. THE Summary_Banner SHALL be dismissible with an X button
5. WHEN the user returns to step 1 and modifies lots, THE System SHALL clear the summary banner

### Requirement 7: SQL Query Construction for Verification

**User Story:** As a system, I need to construct efficient SQL queries for lot verification, so that Exensio database performance is optimized.

#### Acceptance Criteria

1. THE System SHALL construct SQL queries using the format: `SELECT DISTINCT lot_id FROM dw_base_lot WHERE lot_id IN ('LOT1', 'LOT2', ...)`
2. WHEN querying multiple lots, THE System SHALL use quoted lot identifiers in the IN clause
3. THE System SHALL escape single quotes in lot identifiers by doubling them (SQL standard escaping)
4. THE System SHALL use the configured database schema from Exensio properties
5. THE System SHALL add ROWNUM limit for Oracle compatibility: `WHERE ROWNUM <= 500`

### Requirement 8: Verification Error Handling

**User Story:** As a user experiencing verification failures, I want clear error messages and recovery options, so that I can still use the discovery feature.

#### Acceptance Criteria

1. WHEN Exensio returns HTTP 401 (authentication failure), THE System SHALL display "Authentication failed. Please refresh and try again."
2. WHEN Exensio returns HTTP 500 or timeout, THE System SHALL display "Exensio is temporarily unavailable. Try again or skip verification."
3. WHEN SQL query is malformed, THE System SHALL log the error and display "Verification query failed. Discovery will proceed without verification."
4. WHEN verification fails, THE Verification_Dialog SHALL provide a fourth option: "Skip Verification and Continue"
5. IF the user chooses to skip verification, THE System SHALL proceed with discovery using all input lots

### Requirement 9: Loading States and Feedback

**User Story:** As a user waiting for verification, I want to see progress indicators, so that I know the system is working.

#### Acceptance Criteria

1. WHEN verification starts, THE System SHALL display a loading overlay with message: "Verifying lots in Exensio..."
2. WHEN verifying more than 500 lots, THE System SHALL show progress: "Verifying lots... (batch 2 of 3)"
3. WHEN verification completes successfully, THE System SHALL display the verification dialog within 1 second
4. WHEN verification takes longer than 5 seconds, THE System SHALL show an abort button
5. IF the user aborts verification, THE System SHALL offer to continue without verification or cancel

### Requirement 10: Historical Mode Compatibility

**User Story:** As a user using historical mode with date ranges, I want verification to work seamlessly, so that I can validate lot existence regardless of query mode.

#### Acceptance Criteria

1. WHEN historical mode is enabled with date range, THE System SHALL still verify lots before discovery
2. WHEN historical mode query has no lots specified, THE System SHALL skip verification (date range only)
3. WHEN historical mode has both date range and lots, THE System SHALL verify lots FILTERED BY the date range (matching end_time in Exensio), then include the same date range in discovery
4. WHEN the user proceeds with "Lots Not in Exensio", THE System SHALL apply date range filters to the filtered lot list
5. THE Verification_Dialog SHALL indicate that date range filters will be applied: "Date range: MM/DD/YYYY - MM/DD/YYYY"
6. THE System SHALL extract year and month from the date range and pass them to the verification service as PreCheckBlock for date filtering

### Requirement 11: Export Verification Results to CSV

**User Story:** As a user reviewing verification results, I want to export the lot verification status to CSV, so that I can share the results with my team or keep them for records.

#### Acceptance Criteria

1. THE Verification_Dialog SHALL provide an "Export to CSV" button in the dialog header
2. WHEN the user clicks "Export to CSV", THE System SHALL generate a CSV file with columns: "Lot ID", "Status", "Verified At"
3. THE CSV file SHALL contain one row per lot with status values: "Found in Exensio" or "Not Found in Exensio"
4. THE CSV file SHALL be named: "lot-verification-YYYYMMDD-HHMMSS.csv" using the current timestamp
5. THE System SHALL trigger a browser download of the CSV file without closing the verification dialog

### Requirement 12: Data Type to PGC_KEY Mapping

**User Story:** As a system, I need to query Exensio with the correct PGC_KEY for the selected data type, so that verification results are accurate for the intended data category.

#### Acceptance Criteria

1. THE System SHALL pass the selected data type from discovery filters to the verification service
2. THE System SHALL map data types to PGC_KEY values: "Probe"→1, "FT"/"Final Test"→2, "PCM"→5, "Defect"→14, "Map"/"Binmap"/"WXML"/"UPM"→4
3. WHEN data type is not recognized, THE System SHALL default to PGC_KEY=2 (FT)
4. THE System SHALL include PGC_KEY in both Snowflake and raw-SQL queries
5. THE System SHALL log the data type and resolved PGC_KEY for debugging purposes
