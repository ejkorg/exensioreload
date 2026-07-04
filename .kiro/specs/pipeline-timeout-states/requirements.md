# Requirements Document: Pipeline Timeout States

## Introduction

This specification defines requirements for implementing two new timeout states in the wafer monitoring pipeline to provide honest accounting of records where enrichment or Exensio verification status is uncertain after timeout periods. Currently, the system conflates uncertain states with success (DONE) or failure (FAILED), leading to misleading metrics and poor operator visibility into records requiring manual verification.

## Glossary

- **Pipeline**: The data processing flow from staging through enrichment to Exensio verification
- **ES**: Elasticsearch - primary enrichment data source
- **pp_log**: PostgreSQL historical log database - secondary enrichment data source
- **Exensio**: External system for wafer verification and final loading
- **StageRecord**: Database entity representing a single wafer in the staging pipeline
- **CpLogMonitor**: Service component that monitors enrichment from ES and pp_log
- **ExensioLoadMonitor**: Service component that monitors wafer verification in Exensio
- **RefDbService**: Service component that manages stage record status updates
- **StateAccountingService**: Service component that aggregates pipeline state metrics
- **Enrichment**: Process of retrieving additional wafer metadata from ES and pp_log
- **Manual_Verify**: Flag indicating a record requires operator review

## Requirements

### Requirement 1: Enrichment Timeout State

**User Story:** As a pipeline operator, I want to see which records have uncertain enrichment status after timeout, so that I can distinguish them from successfully enriched records and prioritize manual verification.

#### Acceptance Criteria

1. WHEN CpLogMonitor detects a record has timed out (15 minutes default) with ES NotFound AND pp_log NotFound AND Exensio direct lookup returns NotFound, THEN the System SHALL transition the record to ENRICHMENT_TIMEOUT status
2. WHEN a record transitions to ENRICHMENT_TIMEOUT status, THEN the System SHALL record a diagnostic message indicating all enrichment sources returned NotFound
3. WHEN a record is in ENRICHMENT_TIMEOUT status, THEN the System SHALL emit an SSE state change event from ENRICHMENT to ENRICHMENT_TIMEOUT
4. WHEN querying pipeline metrics, THEN the System SHALL report ENRICHMENT_TIMEOUT records separately from DONE records
5. WHEN displaying ENRICHMENT_TIMEOUT records, THEN the System SHALL indicate they require manual verification or retry

### Requirement 2: Exensio Timeout State

**User Story:** As a pipeline operator, I want to see which records have uncertain Exensio verification status after timeout, so that I can distinguish them from verified failures and determine if wafers may appear later.

#### Acceptance Criteria

1. WHEN ExensioLoadMonitor detects a record has timed out (60 minutes default) with Exensio NotFound, THEN the System SHALL transition the record to EXENSIO_TIMEOUT status
2. WHEN a record transitions to EXENSIO_TIMEOUT status, THEN the System SHALL record a message indicating the wafer was not found after the timeout period
3. WHEN a record is in EXENSIO_TIMEOUT status, THEN the System SHALL emit an SSE state change event from EXENSIO_LOADING to EXENSIO_TIMEOUT
4. WHEN querying pipeline metrics, THEN the System SHALL report EXENSIO_TIMEOUT records separately from FAILED records
5. WHEN displaying EXENSIO_TIMEOUT records, THEN the System SHALL indicate the wafer may appear later or may not exist

### Requirement 3: Database Schema Support

**User Story:** As a system administrator, I want the database schema to support the new timeout states, so that all pipeline status values are properly validated and enforced.

#### Acceptance Criteria

1. WHEN the database migration is applied, THEN the SENDER_STAGE table status column SHALL accept ENRICHMENT_TIMEOUT as a valid value
2. WHEN the database migration is applied, THEN the SENDER_STAGE table status column SHALL accept EXENSIO_TIMEOUT as a valid value
3. IF a status constraint exists on the SENDER_STAGE table, THEN the constraint SHALL include both new timeout states in the allowed values
4. WHEN a record is updated to ENRICHMENT_TIMEOUT or EXENSIO_TIMEOUT status, THEN the database SHALL accept the update without constraint violations

### Requirement 4: State Accounting Integration

**User Story:** As a data analyst, I want pipeline metrics to include the new timeout states in accounting calculations, so that state totals remain balanced and all records are accounted for.

#### Acceptance Criteria

1. WHEN StateAccountingService queries pipeline status counts, THEN the query SHALL include counts for ENRICHMENT_TIMEOUT records
2. WHEN StateAccountingService queries pipeline status counts, THEN the query SHALL include counts for EXENSIO_TIMEOUT records
3. WHEN validating accounting balance, THEN the calculation SHALL include enrichmentTimeout + exensioTimeout in the total count
4. WHEN calculating the accounting balance for any session, THEN the sum (ready + queued + enriching + enrichmentTimeout + exensioLoading + exensioTimeout + failed + completed + cancelled) SHALL equal the total record count

### Requirement 5: Frontend Dashboard Visualization

**User Story:** As a pipeline operator, I want to see dedicated metric cards for timeout states on the dashboard, so that I can quickly identify and prioritize records requiring attention.

#### Acceptance Criteria

1. WHEN viewing the monitoring dashboard, THEN the System SHALL display an "Enrichment Timeout" metric card showing the count of ENRICHMENT_TIMEOUT records
2. WHEN viewing the monitoring dashboard, THEN the System SHALL display an "Exensio Timeout" metric card showing the count of EXENSIO_TIMEOUT records
3. WHEN rendering timeout metric cards, THEN the System SHALL use a warning color indicator (amber/orange)
4. WHEN rendering timeout metric cards, THEN the System SHALL display a schedule/clock icon to indicate time-based status
5. WHEN a user hovers over a timeout metric card, THEN the System SHALL display a description explaining what the timeout state means

### Requirement 6: State Legend Documentation

**User Story:** As a new operator, I want to understand what timeout states mean and how records can transition from them, so that I can properly interpret the dashboard and take appropriate actions.

#### Acceptance Criteria

1. WHEN viewing the state legend, THEN the System SHALL include ENRICHMENT_TIMEOUT with a description stating it means no enrichment confirmation from ES or pp_log after timeout
2. WHEN viewing the state legend, THEN the System SHALL include EXENSIO_TIMEOUT with a description stating it means wafer not found in Exensio after timeout
3. WHEN viewing ENRICHMENT_TIMEOUT legend details, THEN the System SHALL list possible transitions as: completed, failed, or back to enrichment
4. WHEN viewing EXENSIO_TIMEOUT legend details, THEN the System SHALL list possible transitions as: completed, failed, or back to exensioLoading
5. WHEN viewing timeout state descriptions, THEN the System SHALL indicate records may need manual verification or retry

### Requirement 7: Real-time Status Updates

**User Story:** As a pipeline operator, I want to see timeout state transitions in real-time on the dashboard, so that I can monitor pipeline health without manual refresh.

#### Acceptance Criteria

1. WHEN a record transitions to ENRICHMENT_TIMEOUT, THEN the System SHALL emit an SSE event containing the state change details
2. WHEN a record transitions to EXENSIO_TIMEOUT, THEN the System SHALL emit an SSE event containing the state change details
3. WHEN the frontend receives an ENRICHMENT_TIMEOUT SSE event, THEN the dashboard SHALL increment the Enrichment Timeout card count
4. WHEN the frontend receives an EXENSIO_TIMEOUT SSE event, THEN the dashboard SHALL increment the Exensio Timeout card count
5. WHEN dashboard metrics update via SSE, THEN the timeout card animations SHALL provide visual feedback of the change

### Requirement 8: Backward Compatibility

**User Story:** As a system administrator, I want the timeout states implementation to be backward compatible with existing records and frontends, so that deployment is safe and non-disruptive.

#### Acceptance Criteria

1. WHEN the new backend is deployed, THEN existing records in DONE, FAILED, or other states SHALL remain unchanged
2. WHEN the new backend is deployed with old frontend, THEN the old frontend SHALL continue to function without errors
3. WHEN old frontend receives metrics with timeout state counts, THEN it SHALL ignore unknown fields without breaking
4. WHEN a new timeout state record exists, THEN legacy queries SHALL handle it gracefully without constraint violations
5. WHEN rolling back the deployment, THEN no ENRICHMENT_TIMEOUT or EXENSIO_TIMEOUT records SHALL have been created (migration is reversible)

### Requirement 9: Stepper Component Integration

**User Story:** As a user staging wafers, I want to see timeout state counts in the stepper workflow, so that I understand the processing status of my files during active staging sessions.

#### Acceptance Criteria

1. WHEN viewing monitoring stats in the stepper component, THEN the System SHALL display the count of files in ENRICHMENT_TIMEOUT status
2. WHEN viewing monitoring stats in the stepper component, THEN the System SHALL display the count of files in EXENSIO_TIMEOUT status
3. WHEN viewing the monitor dispatch page (stepper step 3), THEN the System SHALL display both timeout state counts alongside other pipeline state metrics
4. WHEN calculating file-level status counts, THEN the System SHALL filter stage records by ENRICHMENT_TIMEOUT status correctly
5. WHEN calculating file-level status counts, THEN the System SHALL filter stage records by EXENSIO_TIMEOUT status correctly
6. WHEN displaying timeout counts in the stepper, THEN the System SHALL use consistent visual styling with the main dashboard

### Requirement 10: Diagnostic Information Preservation

**User Story:** As a system troubleshooter, I want timeout records to preserve detailed diagnostic information, so that I can understand why enrichment or verification was uncertain and what was attempted.

#### Acceptance Criteria

1. WHEN marking a record as ENRICHMENT_TIMEOUT, THEN the System SHALL record which data sources were queried (ES, pp_log, Exensio)
2. WHEN marking a record as ENRICHMENT_TIMEOUT, THEN the System SHALL record the response from each data source (NotFound, Error, etc.)
3. WHEN marking a record as EXENSIO_TIMEOUT, THEN the System SHALL record the configured timeout duration
4. WHEN marking any timeout record, THEN the System SHALL include a timestamp of when the timeout occurred
5. WHEN viewing a timeout record's error_message field, THEN the message SHALL clearly indicate it is a timeout condition and not a verified failure
