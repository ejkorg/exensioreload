# Requirements Document

## Introduction

This specification defines a configuration-driven dependency orchestration system for ExensioReload to eliminate timing issues and race conditions in multi-stage data processing pipelines. The system replaces hard-coded enrichment logic with a flexible, declarative pipeline configuration that supports CP (Command Processor), PP_LOG (Parametric Program Logging), and future enrichment stages.

## Glossary

- **Pipeline**: A sequence of dependent enrichment stages that must execute in order for a data processing job
- **Stage**: A single enrichment operation (e.g., CP processing, PP_LOG processing, Exensio verification)
- **Dependency**: A relationship where one stage must complete before another stage can begin
- **CP**: Command Processor - ETL process (typically Perl) that transforms raw data files into standardized format and logs events to Elasticsearch
- **PP_LOG**: Parametric Program Log ETL process (Python/Perl) that processes data and logs events/info to the `refdb.pp_log` Oracle table; can be an additional stage or replacement for CP processing
- **Elasticsearch**: Search engine used to detect CP completion events via log entries
- **RefDB**: Reference database - main application database containing `SENDER_STAGE` table (processing state) and `pp_log` table (PP_LOG event logging)
- **Orchestrator**: Component that manages pipeline execution based on dependency configuration
- **Site**: A manufacturing location configured in dbconnections.yml (e.g., CEBU-PROD, JND-AIZU-PROD)

## Requirements

### Requirement 1: Configuration Schema

**User Story:** As a system administrator, I want to declare pipeline dependencies in dbconnections.yml, so that I can control enrichment behavior per site without code changes.

#### Acceptance Criteria

1. WHEN a site entry is defined in dbconnections.yml, THE System SHALL support an optional `pipeline` section containing stage dependencies
2. WHEN a site's `pipeline` section includes a `stages` array, THE System SHALL parse each stage entry containing `name`, `type`, and optional `dependsOn` field
3. WHEN a stage specifies `dependsOn` with another stage name, THE System SHALL enforce that the dependent stage completes before the current stage begins
4. WHERE a site has no `pipeline` section defined, THE System SHALL use legacy behavior (immediate processing with no dependency checks)
5. WHEN multiple stages are defined, THE System SHALL validate that dependency references point to existing stage names in the same pipeline

### Requirement 2: Pipeline Orchestrator

**User Story:** As a developer, I want a centralized orchestrator service, so that all pipeline execution logic is managed in one component.

#### Acceptance Criteria

1. THE System SHALL provide a `PipelineOrchestrator` service that reads pipeline configuration at startup
2. WHEN a record transitions to a new status, THE System SHALL invoke the orchestrator to determine the next stage
3. WHEN determining the next stage, THE System SHALL check if all dependencies are satisfied before allowing progression
4. WHEN dependencies are not satisfied, THE System SHALL keep the record in a waiting state with appropriate status
5. WHEN all dependencies are satisfied, THE System SHALL transition the record to the next stage status

### Requirement 3: CP Completion Detection

**User Story:** As a system operator, I want dependent stages to wait for CP completion, so that race conditions are eliminated when CP is required.

#### Acceptance Criteria

1. WHEN a pipeline defines a stage with `dependsOn: ["cp"]`, THE System SHALL not begin that stage until CP completion is confirmed
2. WHEN checking CP completion, THE System SHALL query Elasticsearch using the configured index pattern for CP log entries
3. WHEN Elasticsearch returns a CP log entry matching the record's identifiers (lot, wafer, metadata ID, data ID), THE System SHALL mark the CP stage as complete
4. IF Elasticsearch query fails with a transient error, THE System SHALL retry on the next poll cycle
5. WHEN CP completion is detected, THE System SHALL transition the record status from `CP_MONITORING` to the next stage defined in the pipeline

### Requirement 4: PP_LOG Completion Detection with Dependency Check

**User Story:** As a data engineer, I want stages to verify PP_LOG completion by querying the refdb.pp_log table, so that dependent stages don't execute prematurely.

#### Acceptance Criteria

1. WHEN a pipeline defines a stage with `dependsOn: ["pplog"]`, THE System SHALL not begin that stage until PP_LOG completion is confirmed
2. WHEN checking PP_LOG completion, THE System SHALL query the `refdb.pp_log` Oracle table for records matching lot ID and end time
3. WHEN the pp_log query returns a matching record, THE System SHALL mark the PP_LOG stage as complete
4. WHEN the pp_log query returns no records, THE System SHALL keep the record in `PPLOG_MONITORING` status for retry
5. WHEN PP_LOG query fails with a database error, THE System SHALL mark the record with appropriate error status and message

### Requirement 5: Polling and Timeout Configuration

**User Story:** As a system administrator, I want configurable polling intervals and timeouts per stage, so that I can tune performance and reliability.

#### Acceptance Criteria

1. THE System SHALL support a `pollIntervalMs` setting per stage in the pipeline configuration
2. THE System SHALL support a `timeoutMinutes` setting per stage in the pipeline configuration
3. WHEN a record remains in a stage status longer than its timeout, THE System SHALL mark it with a timeout status (e.g., `CP_TIMEOUT`, `PPLOG_TIMEOUT`)
4. WHEN polling for stage completion, THE System SHALL use the stage-specific poll interval if configured
5. WHERE no stage-specific settings are provided, THE System SHALL use global default values from application.yml

### Requirement 6: State Tracking and Audit Trail

**User Story:** As a support engineer, I want detailed state tracking for each pipeline stage, so that I can diagnose issues and monitor progress.

#### Acceptance Criteria

1. WHEN a record progresses through pipeline stages, THE System SHALL log each status transition with timestamp
2. WHEN a stage check is performed, THE System SHALL record the check result (success, not_found, error) in the audit log
3. WHEN a dependency is not satisfied, THE System SHALL log which dependency is blocking progression
4. THE System SHALL expose pipeline status via REST API endpoint for UI display
5. WHEN querying pipeline status, THE System SHALL return current stage, completed stages, pending stages, and any error messages

### Requirement 7: Elasticsearch Query Configuration

**User Story:** As a system administrator, I want flexible Elasticsearch query configuration, so that CP completion detection works across different log formats and indices.

#### Acceptance Criteria

1. WHEN configuring CP stage completion, THE System SHALL support specifying the Elasticsearch index pattern
2. THE System SHALL support configuring field mappings for lot ID, wafer ID, metadata ID, and data ID matching
3. WHEN Elasticsearch field names differ by site, THE System SHALL support site-specific field overrides in pipeline configuration
4. THE System SHALL apply the configured `lookback-buffer-seconds` to account for clock skew and indexing delays
5. WHERE a site has `service-country-filter` configured, THE System SHALL include it in the Elasticsearch query

### Requirement 8: Extensibility for Future Stages

**User Story:** As a developer, I want to add new enrichment stages without modifying orchestration code, so that the system scales to support future requirements.

#### Acceptance Criteria

1. WHEN a new stage type is added to pipeline configuration, THE System SHALL recognize it through a registered stage handler
2. THE System SHALL support a stage handler registry pattern where handlers are registered by stage type
3. WHEN a stage handler is invoked, THE System SHALL provide the record and stage configuration as parameters
4. WHEN a stage handler completes successfully, THE System SHALL return a result indicating completion
5. WHEN a stage handler encounters an error, THE System SHALL return a result with error details for logging

### Requirement 9: Error Handling and Recovery

**User Story:** As a system operator, I want graceful error handling at each pipeline stage, so that transient failures don't cause permanent data loss.

#### Acceptance Criteria

1. WHEN a stage check encounters a transient error, THE System SHALL retry on the next poll cycle
2. WHEN a stage times out, THE System SHALL transition to a timeout status and log diagnostic information
3. WHEN a stage fails permanently (e.g., data not found after timeout), THE System SHALL mark the record as failed with explanation
4. THE System SHALL support manual retry for timed-out records via REST API endpoint
5. WHEN retrying a timed-out record, THE System SHALL reset stage state and resume from the blocked stage

### Requirement 10: Integration with Existing Components

**User Story:** As a developer, I want the orchestrator to integrate with existing services, so that minimal refactoring is required.

#### Acceptance Criteria

1. WHEN CP completion is detected, THE System SHALL invoke `StagePipelineOrchestrator.onCpEnrichmentSuccess()` with the record
2. WHEN PP_LOG enrichment completes, THE System SHALL invoke existing database update methods in `RefDbService`
3. WHEN Exensio verification is needed, THE System SHALL delegate to existing `ExensioLoadMonitor` scheduled service
4. THE System SHALL emit SSE (Server-Sent Events) for status changes via existing `StageMonitorService`
5. WHEN updating record status, THE System SHALL use existing `IntegrationStatusService` for per-file integration tracking

### Requirement 11: Backward Compatibility

**User Story:** As a system administrator, I want existing sites without pipeline configuration to continue working, so that migration is gradual and safe.

#### Acceptance Criteria

1. WHEN a site has no `pipeline` section in dbconnections.yml, THE System SHALL use the legacy execution path
2. THE System SHALL detect pipeline configuration presence at runtime per site
3. WHERE a record's site has pipeline configuration, THE System SHALL use the orchestrator
4. WHERE a record's site lacks pipeline configuration, THE System SHALL use existing direct-processing logic
5. THE System SHALL log which execution path is chosen (orchestrator vs legacy) for observability

### Requirement 12: Performance and Scalability

**User Story:** As a system operator, I want the orchestrator to scale to thousands of concurrent records, so that system performance is maintained.

#### Acceptance Criteria

1. WHEN processing multiple records, THE System SHALL batch dependency checks by site to minimize configuration lookups
2. THE System SHALL cache parsed pipeline configurations per site to avoid repeated YAML parsing
3. WHEN checking dependencies, THE System SHALL execute stage checks in parallel where dependencies allow
4. THE System SHALL support configurable thread pool sizes for parallel stage processing
5. WHEN the orchestrator queue grows beyond a threshold, THE System SHALL log performance warnings
