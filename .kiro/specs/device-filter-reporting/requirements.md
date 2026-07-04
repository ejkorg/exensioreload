# Requirements Document

## Introduction

This feature extends the Exensio Reload application to support device-based filtering across all reporting interfaces (Analytics, My Sessions, Dashboard). Currently, device information is available during the discovery/preview phase but is not persisted to the staging tables, preventing device-based filtering in reports and session views. This enhancement will capture device information during staging and enable filtering by device across the application.

## Glossary

- **Device**: A semiconductor test device identifier (e.g., "IR71939") that is part of the metadata captured during discovery from external data sources
- **Staging_Table**: The `load_session_payload` table that stores individual payloads for a staging session
- **Session**: A `load_session` record representing a batch staging operation
- **Analytics_Page**: The reporting interface showing historical session analytics and metrics
- **My_Sessions_Page**: The user-specific view of staging sessions they initiated
- **Dashboard**: The main monitoring interface showing real-time and historical data
- **Discovery_Preview**: The phase where users preview metadata before staging
- **Metadata_Repository**: The external data source queried during discovery (e.g., Oracle, Snowflake)

## Requirements

### Requirement 1: Device Persistence in Staging Table

**User Story:** As a data engineer, I want device information captured during staging, so that I can filter and analyze data by device in reports.

#### Acceptance Criteria

1. WHEN a payload is staged, THE Staging_Table SHALL store the device identifier
2. WHEN device information is unavailable from the metadata source, THE Staging_Table SHALL store NULL for the device field
3. THE Staging_Table SHALL have a device column of type VARCHAR with sufficient length to accommodate device identifiers
4. WHEN the staging table is queried, THE System SHALL return device information for each payload

### Requirement 2: Device Filter in Analytics Page

**User Story:** As a quality engineer, I want to filter analytics reports by device, so that I can analyze device-specific trends and metrics.

#### Acceptance Criteria

1. WHEN a user accesses the Analytics page, THE System SHALL display a device filter control
2. WHEN a user selects one or more devices, THE Analytics_Page SHALL filter results to show only payloads matching those devices
3. WHEN no device filter is applied, THE Analytics_Page SHALL show all payloads regardless of device
4. WHEN device filter values are applied, THE System SHALL persist the filter selection in the UI state during the session
5. THE System SHALL populate device filter options from distinct device values in the staging table

### Requirement 3: Device Filter in My Sessions Page

**User Story:** As a user, I want to filter my staging sessions by device, so that I can quickly locate sessions related to specific devices.

#### Acceptance Criteria

1. WHEN a user accesses the My Sessions page, THE System SHALL display a device filter control
2. WHEN a user filters by device, THE My_Sessions_Page SHALL show only sessions containing payloads for the selected devices
3. WHEN viewing session details, THE System SHALL display device information for each payload
4. WHEN no device filter is applied, THE My_Sessions_Page SHALL show all user sessions
5. THE System SHALL allow filtering by multiple devices simultaneously

### Requirement 4: Device Filter in Dashboard

**User Story:** As an operations manager, I want to filter dashboard metrics by device, so that I can monitor device-specific performance and issues.

#### Acceptance Criteria

1. WHEN a user accesses the Dashboard, THE System SHALL display a device filter control
2. WHEN a user applies a device filter, THE Dashboard SHALL update all metric cards to reflect only the selected devices
3. WHEN device filters are active, THE System SHALL apply them to real-time monitoring updates
4. WHEN device filters are cleared, THE Dashboard SHALL return to showing all devices
5. THE System SHALL persist device filter state across dashboard page refreshes during the session

### Requirement 5: Device Discovery and Capture

**User Story:** As a system, I need to extract device information during discovery, so that it can be persisted during staging.

#### Acceptance Criteria

1. WHEN querying the Metadata_Repository during discovery, THE System SHALL retrieve device identifiers
2. WHEN displaying the Discovery_Preview, THE System SHALL show device information for each row
3. WHEN displaying the Discovery_Preview, THE System SHALL show metadata identifiers (metadataId and dataId) for each row
4. WHEN a user stages payloads from the preview, THE System SHALL capture device information for persistence
5. WHEN the external metadata lacks device information, THE System SHALL handle the absence gracefully without errors
6. THE System SHALL support device filtering during the discovery preview phase

### Requirement 6: Database Migration

**User Story:** As a database administrator, I want a safe migration to add the device column, so that existing data integrity is maintained.

#### Acceptance Criteria

1. THE System SHALL provide a Liquibase changelog to add the device column to the staging table
2. WHEN the migration is executed, THE System SHALL add a nullable device column without requiring downtime
3. WHEN the migration completes, THE System SHALL leave existing records with NULL device values
4. THE System SHALL create an index on the device column for query performance
5. WHEN rolling back the migration, THE System SHALL remove the device column and index safely

### Requirement 7: API Extensions

**User Story:** As a frontend developer, I want APIs to support device filtering, so that I can implement device filters in the UI.

#### Acceptance Criteria

1. WHEN querying sessions via API, THE System SHALL accept device filter parameters
2. WHEN device filters are provided, THE System SHALL return only sessions/payloads matching the filter
3. WHEN requesting distinct device values, THE System SHALL return a list of unique devices from the staging table
4. THE System SHALL support device filtering in paginated API responses
5. WHEN device filters are combined with other filters, THE System SHALL apply all filters correctly

### Requirement 8: Backward Compatibility

**User Story:** As a system maintainer, I want the device filter feature to be backward compatible, so that existing functionality continues to work.

#### Acceptance Criteria

1. WHEN device column is NULL for legacy records, THE System SHALL handle queries without errors
2. WHEN device filters are not applied, THE System SHALL behave identically to pre-feature behavior
3. WHEN APIs receive requests without device parameters, THE System SHALL process them normally
4. THE System SHALL support mixed data where some payloads have device values and others do not
5. WHEN displaying records without device information, THE System SHALL show an appropriate indicator (e.g., "N/A")

### Requirement 9: Performance Considerations

**User Story:** As a system administrator, I want device filtering to perform efficiently, so that reports load quickly even with large datasets.

#### Acceptance Criteria

1. WHEN filtering by device on large datasets, THE System SHALL return results within 2 seconds
2. THE System SHALL use database indexes to optimize device filter queries
3. WHEN multiple filters are applied including device, THE System SHALL execute efficient combined queries
4. THE System SHALL cache distinct device values for filter dropdowns to minimize database queries
5. WHEN the staging table grows beyond 1 million records, THE System SHALL maintain query performance with device filters
