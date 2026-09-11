# Implementation Plan: Pipeline Dependency Orchestration (MVP)

## Overview

This MVP implementation focuses on core pipeline orchestration functionality. All optional test tasks are removed for faster delivery. The essential tasks cover data models, configuration loading, stage handlers, status tracking, orchestrator logic, and integration with existing monitors.

## Tasks

- [x] 1. Create core data models and configuration schema
  - Create `PipelineConfig`, `StageDefinition`, `StageType` enum, and `StageStatus` enum records
  - Add pipeline state columns to SENDER_STAGE table via Liquibase changeset
  - Create database migration for `current_pipeline_stage`, `completed_pipeline_stages`, `stage_metadata`, `pipeline_started_at`, `last_stage_check_at`
  - _Requirements: 1.1, 1.2_

- [x] 2. Implement PipelineConfigLoader with YAML parsing
  - [x] 2.1 Create PipelineConfigLoader service with loadPipelineConfig() method
    - Parse dbconnections.yml to extract pipeline section per site
    - Build StageDefinition list from YAML stages array
    - Create stagesByName lookup map for fast access
    - _Requirements: 1.1, 1.2, 1.4_

  - [x] 2.2 Implement dependency graph validation
    - Check for circular dependencies using topological sort
    - Verify all dependsOn references point to existing stage names
    - Throw PipelineConfigException with clear error message on invalid config
    - _Requirements: 1.5_

- [x] 3. Implement PipelineConfigCache for performance
  - Create cache using Caffeine with site key
  - Set expiration to 5 minutes (configurable via application.yml)
  - Implement getConfig(site) method that loads on cache miss
  - Add invalidateAll() method for configuration reloads
  - _Requirements: 12.2_

- [x] 4. Create StageHandler interface and registry
  - [x] 4.1 Define StageHandler interface with checkCompletion() method
    - Accept StageRecord and stageConfig Map as parameters
    - Return StageResult record with status, traceId, errorMessage, metadata
    - Define StageResult factory methods: completed(), notFound(), error(), timeout()
    - _Requirements: 8.1, 8.2, 8.3, 8.4_

  - [x] 4.2 Implement StageHandlerRegistry component
    - Maintain Map<StageType, StageHandler> registry
    - Auto-register handlers annotated with @Component during startup
    - Provide getHandler(StageType) method
    - Throw exception if handler not found for required stage type
    - _Requirements: 8.1, 8.2_

- [x] 5. Implement PipelineStatusTracker for state management
  - [x] 5.1 Create PipelineState record with current/completed stages
    - Define PipelineState(recordId, currentStage, completedStages, stageCompletionTimes, stageMetadata)
    - Implement isStageComplete(stageName) check method
    - Implement getStageAge(stageName) duration calculation
    - _Requirements: 6.1_

  - [x] 5.2 Implement getState() method using SENDER_STAGE queries
    - Parse completed_pipeline_stages JSON array column
    - Parse stage_metadata CLOB as JSON object
    - Build stageCompletionTimes map from metadata
    - Return PipelineState record
    - _Requirements: 6.2_

  - [x] 5.3 Implement markStageComplete() database update
    - Add stage name to completed_pipeline_stages JSON array
    - Store metadata in stage_metadata JSON object
    - Update last_stage_check_at timestamp
    - Use RefDbService.executeBatchUpdate() for atomicity
    - _Requirements: 6.1, 10.2_

  - [x] 5.4 Implement isStageTimedOut() timeout detection
    - Calculate duration from pipeline_started_at or enrichmentStartedAt
    - Compare against stage timeout from config or global default
    - Return true if exceeded
    - _Requirements: 5.3, 9.2_

- [x] 6. Implement CpCompletionHandler
  - [x] 6.1 Create CpCompletionHandler implementing StageHandler
    - Inject ElasticsearchLogService and CpElasticsearchProperties
    - Implement getStageType() returning StageType.CP
    - _Requirements: 3.1, 8.2_

  - [x] 6.2 Implement checkCompletion() for CP stage
    - Extract indexPattern, lookbackBufferSeconds from stageConfig
    - Calculate searchStart = record.endTime - lookbackBufferSeconds
    - Call elasticsearchLogService.findCpLog() with lot, wafer, metadataId, searchStart, site
    - Map CpLogResult.Success â†’ StageResult.completed() with metadata
    - Map CpLogResult.NotFound â†’ StageResult.notFound()
    - Map CpLogResult.Failure â†’ StageResult.error()
    - _Requirements: 3.2, 3.3, 3.4, 7.1, 7.4_

- [x] 7. Implement PpLogCompletionHandler
  - [x] 7.1 Create PpLogCompletionHandler implementing StageHandler
    - Inject RefDbService and PpLogDbProperties
    - Implement getStageType() returning StageType.PPLOG
    - _Requirements: 4.1, 8.2_

  - [x] 7.2 Implement checkCompletion() for PP_LOG stage
    - Check ppLogProperties.isPpLogAvailable() early exit if not configured
    - Extract lookbackBufferSeconds from stageConfig (default 900)
    - Calculate searchStart = record.endTime - lookbackBufferSeconds
    - Call refDbService.queryPpLog(lot, searchStart, site)
    - Return StageResult.completed() if entry found
    - Return StageResult.notFound() if entry is null/blank
    - Return StageResult.error() on exception with error message
    - _Requirements: 4.1, 4.2, 4.3, 4.4, 4.5_

- [x] 8. Implement ExensioVerificationHandler as delegator
  - Create ExensioVerificationHandler implementing StageHandler
  - Implement checkCompletion() that returns StageResult.notFound() (delegates to ExensioLoadMonitor scheduled job)
  - Implement getStageType() returning StageType.EXENSIO
  - Add comment explaining this is a no-op because ExensioLoadMonitor handles the verification
  - _Requirements: 8.2, 10.3_

- [x] 9. Implement core PipelineOrchestrator logic
  - [x] 9.1 Create PipelineOrchestrator service with dependency injection
    - Inject PipelineConfigCache, StageHandlerRegistry, PipelineStatusTracker
    - Inject StageMonitorService, IntegrationStatusService
    - _Requirements: 2.1, 10.4, 10.5_

  - [x] 9.2 Implement determineNextAction() method
    - Get pipeline config from cache using record.site()
    - Return PipelineAction.useLegacyPath() if config not present
    - Get current PipelineState from statusTracker
    - Determine current stage from state or first stage in config
    - Check if current stage dependencies are satisfied via areDependenciesSatisfied()
    - Return PipelineAction.waitForDependencies() if not satisfied
    - Get handler from registry for current stage type
    - Return PipelineAction.executeStage() with stage and handler
    - _Requirements: 2.2, 2.3, 2.4, 11.1, 11.2_

  - [x] 9.3 Implement areDependenciesSatisfied() private method
    - For each dependency name in stage.dependsOn()
    - Check state.isStageComplete(depName)
    - Return false if any dependency is not complete
    - Return true if all dependencies complete
    - _Requirements: 2.3_

  - [x] 9.4 Implement executeStage() method
    - Call handler.checkCompletion(record, stage.config())
    - Switch on result.status()
    - COMPLETED â†’ call progressToNextStage()
    - NOT_FOUND â†’ call keepInCurrentStage()
    - ERROR â†’ call handleStageError()
    - TIMEOUT â†’ call handleStageTimeout()
    - _Requirements: 2.3, 2.4, 2.5_

  - [x] 9.5 Implement progressToNextStage() method
    - Call statusTracker.markStageComplete() with stage name and result metadata
    - Determine next stage from pipeline config
    - Update record status to next stage monitoring status (e.g., CP_MONITORING â†’ PPLOG_MONITORING)
    - If no next stage, update record status to DONE
    - Emit SSE event via stageMonitorService.sendEvent()
    - Update integration status via integrationStatusService
    - _Requirements: 2.5, 6.1, 10.4, 10.5_

  - [x] 9.6 Implement keepInCurrentStage() method
    - Update last_stage_check_at timestamp
    - Check if stage has timed out via statusTracker.isStageTimedOut()
    - If timed out, call handleStageTimeout()
    - Otherwise, log debug message and exit (retry next poll)
    - _Requirements: 5.3, 9.1_

  - [x] 9.7 Implement handleStageError() method
    - Log error with record ID, stage name, error message
    - Update integration status to "error"
    - Keep record in current monitoring status for retry
    - Check error count - if exceeds threshold, mark FAILED
    - _Requirements: 9.1, 9.3_

  - [x] 9.8 Implement handleStageTimeout() method
    - Build diagnostic message with stage details, dependencies, duration
    - Update record status to stage timeout status (CP_TIMEOUT, PPLOG_TIMEOUT)
    - Update integration status to "timeout"
    - Emit SSE event with timeout diagnostic
    - Log warning with full diagnostic information
    - _Requirements: 5.3, 9.2_

- [x] 10. Integrate orchestrator with CpLogMonitor
  - [x] 10.1 Refactor CpLogMonitor.monitorEnrichmentRecords() to use orchestrator
    - For each record in ELASTICSEARCH_MONITORING status
    - Call orchestrator.determineNextAction(record)
    - If PipelineAction.useLegacyPath(), continue with existing logic
    - If PipelineAction.waitForDependencies(), skip record and log waiting message
    - If PipelineAction.executeStage(), call orchestrator.executeStage()
    - _Requirements: 10.1, 11.2, 11.3_

  - [x] 10.2 Update status names to use pipeline-aware names
    - Change ELASTICSEARCH_MONITORING to CP_MONITORING where pipeline is used
    - Keep ELASTICSEARCH_MONITORING for legacy sites
    - Add logic to choose status name based on pipeline config presence
    - _Requirements: 11.1, 11.4_

- [x] 11. Add PP_LOG monitoring scheduler
  - [x] 11.1 Create PpLogMonitor scheduled service
    - Annotate with @Component and @Scheduled(fixedDelayString = ...)
    - Load records in PPLOG_MONITORING status
    - For each record, call orchestrator.determineNextAction()
    - Execute stage if action is executeStage
    - _Requirements: 4.1, 10.1_

  - [x] 11.2 Configure poll interval in application.yml
    - Add pplog.poll-interval-ms property (default 60000)
    - Use in @Scheduled annotation
    - Document that this is independent of CP polling
    - _Requirements: 5.1, 5.4_

- [x] 12. Add REST API endpoints for pipeline status
  - [x] 12.1 Create PipelineStatusController REST controller
    - Add @RestController annotation
    - Inject PipelineStatusTracker and PipelineConfigCache
    - _Requirements: 6.4_

  - [x] 12.2 Implement GET /api/pipeline/status/{recordId} endpoint
    - Get PipelineState from statusTracker
    - Get PipelineConfig from configCache
    - Build response with current stage, completed stages, pending stages
    - Include stage completion times and metadata
    - Return 404 if record not found
    - _Requirements: 6.4, 6.5_

  - [x] 12.3 Implement POST /api/pipeline/retry/{recordId} endpoint
    - Load record from RefDbService
    - Check if record is in timeout status
    - Reset pipeline state via statusTracker
    - Update record status to initial monitoring status
    - Return success/error response
    - _Requirements: 9.4, 9.5_

- [x] 13. Add configuration validation at startup
  - [x] 13.1 Create PipelineConfigValidator component
    - Annotate with @Component and implement InitializingBean
    - In afterPropertiesSet(), load all site configs
    - Validate each pipeline config via configLoader.validatePipelineConfig()
    - Fail fast if any config is invalid with clear error message
    - Log summary of loaded pipelines (site count, stage types used)
    - _Requirements: 1.5, 8.1_

- [x] 14. Add observability and metrics
  - [x] 14.1 Add JMX metrics to PipelineOrchestrator
    - Annotate with @ManagedResource
    - Add @ManagedAttribute for total records processed per stage type
    - Add @ManagedAttribute for average stage duration by type
    - Add @ManagedAttribute for timeout counts by stage
    - Add @ManagedAttribute for dependency wait counts
    - _Requirements: 12.5_

  - [x] 14.2 Add structured logging for pipeline events
    - Log pipeline config loading with stage count
    - Log stage transitions with stage name, status, duration
    - Log dependency waits with blocking dependency names
    - Log timeouts with diagnostic details
    - Use consistent log format with recordId, site, stage
    - _Requirements: 6.2, 6.3_

- [x] 15. Add example pipeline configurations
  - [x] 15.1 Document pipeline configuration in EXENSIO_API_DOCUMENTATION.md
    - Add section "Pipeline Dependency Orchestration"
    - Provide example YAML for common patterns
    - Document stage types and config options
    - Document how to add new stage types
    - _Requirements: 1.1, 8.1_

  - [x] 15.2 Add example configurations to dbconnections.yml comments
    - Add commented-out example for CP â†’ PP_LOG â†’ Exensio pipeline
    - Add commented-out example for CP-only pipeline
    - Add commented-out example for PP_LOG-only pipeline (CP replacement)
    - Add commented-out example for Exensio-only pipeline
    - _Requirements: 1.1, 1.4_

- [x] 16. Performance optimization
  - [x] 16.1 Implement batch dependency checking
    - [x] Group records by site before processing
    - [x] Load pipeline config once per site batch
    - [x] Process records in parallel within same site
    - [x] Create BatchRecordProcessor component
    - [x] Integrate with CpLogMonitor and PpLogMonitor
    - _Requirements: 12.1, 12.3_

  - [x] 16.2 Add thread pool configuration for stage processing
    - [x] Add pipeline.thread-pool-size property to application.yml
    - [x] Create PipelineExecutorConfig with ExecutorService bean
    - [x] Configure thread pool with configurable size from properties
    - [x] Create fixed thread pool with pipeline-specific naming
    - _Requirements: 12.4_

## Notes

- This MVP focuses on core functionality only
- All test-related subtasks have been removed for faster delivery
- Each task references specific requirements for traceability
- The implementation maintains backward compatibility - sites without pipeline config continue using legacy execution path
- Tests and metrics can be added post-MVP once core functionality is validated
