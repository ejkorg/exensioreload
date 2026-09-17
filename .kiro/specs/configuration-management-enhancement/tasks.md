# Implementation Plan: Configuration Management Enhancement

## Overview

This implementation plan breaks down the configuration management enhancement feature into discrete, incremental tasks. The feature migrates ETL configuration from static YAML files to database-backed storage with YAML fallback, adds administrative CRUD UI, enhances audit logging, and supports historical pipeline configurations.

## MVP vs Optional Tasks

This plan supports an **MVP-first approach**:

- **Core MVP Tasks**: Unmarked tasks that deliver essential functionality
- **Optional Tasks**: Marked with `*` - can be implemented later for enhanced quality/features

### MVP Scope (Faster Delivery)

The MVP includes:

- ✅ Database schema and entities
- ✅ Basic CRUD operations for pipelines, servers, and connections
- ✅ YAML fallback mechanism
- ✅ Password encryption
- ✅ Basic validation
- ✅ REST API endpoints
- ✅ Admin UI panels (basic CRUD)
- ✅ Enhanced audit logging (basic tracking)
- ✅ Step 1 UI integration with historical mode
- ✅ Configuration migration utility

The MVP **skips** (optional tasks marked with `*`):

- ⏭️ Property-based tests (12 tests) - can add later for higher confidence
- ⏭️ Advanced validation edge cases
- ⏭️ Comprehensive integration tests

### How to Use This Plan

1. **For MVP**: Implement all tasks WITHOUT the `*` marker
2. **For Full Implementation**: Implement all tasks including those marked with `*`
3. **After MVP**: Come back and implement `*` tasks for production hardening

## Tasks

- [x] 1. Database Schema and Entities
  - Create JPA entities and database tables for configuration storage
  - _Requirements: 1.1, 1.4, 16.1, 16.2_

- [x] 1.1 Create ConfigPipeline entity with historical mode support
  - Define `ConfigPipeline` JPA entity with fields: id, pipelineKey, site, server, socketPort, configName, senderId, rerunPeriodMinutes, environment, enabled, historicalModeEnabled, createdAt, updatedAt, createdBy, updatedBy
  - Add `@OneToMany` relationship to stages
  - Include validation annotations (@NotNull, @Size, etc.)
  - _Requirements: 1.1, 16.1_

- [x] 1.2 Create ConfigPipelineStage entity
  - Define `ConfigPipelineStage` JPA entity with fields: id, pipeline, name, type, timeoutMinutes, executionOrder
  - Add `@ElementCollection` for dependsOn stage names
  - Create enum `StageType` with values: CP, PPLOG, EXENSIO
  - _Requirements: 1.1_

- [x] 1.3 Create ConfigEtlServer entity with historical sender flag
  - Define `ConfigEtlServer` JPA entity with fields: id, serverKey, host, sshPort, socketPort, user, encryptedPassword, timeoutMs, environment, enabled, isHistoricalSender, createdAt, updatedAt, createdBy, updatedBy
  - Add password encryption field (will be encrypted before storage)
  - _Requirements: 1.1, 16.6_

- [x] 1.4 Create ConfigDbConnection entity
  - Define `ConfigDbConnection` JPA entity with fields: id, connectionKey, dbType, schema, host, user, encryptedPassword, connectionTimeoutMs, maximumPoolSize, minimumIdle, downloadUrl, environment, enabled, createdAt, updatedAt, createdBy, updatedBy
  - Create enum `DbType` with values: ORACLE, POSTGRESQL
  - _Requirements: 1.1_

- [x] 1.5 Enhance AuditLog entity with new action types
  - Add new action constants: PIPELINE_CREATED, PIPELINE_UPDATED, PIPELINE_DELETED, ETL_SERVER_CREATED, ETL_SERVER_UPDATED, ETL_SERVER_DELETED, DB_CONNECTION_CREATED, DB_CONNECTION_UPDATED, DB_CONNECTION_DELETED, SESSION_CREATED, SESSION_COMPLETED, SESSION_FAILED, ETL_TRIGGERED, CONFIG_MIGRATED
  - Add new resource type constants: PIPELINE, ETL_SERVER, DB_CONNECTION
  - Add status and errorMessage fields if not present
  - _Requirements: 10.2, 10.3, 10.4, 10.5_

- [x] 1.6 Create database migration scripts for PostgreSQL
  - Write Flyway/Liquibase migration for config_pipeline table with indexes
  - Write migration for config_pipeline_stage table
  - Write migration for config_stage_dependency table
  - Write migration for config_etl_server table with indexes
  - Write migration for config_db_connection table with indexes
  - Add indexes: idx_pipeline_site, idx_pipeline_environment, idx_pipeline_sender, idx_pipeline_site_historical (site, historicalModeEnabled)
  - _Requirements: 1.1_

- [x] 1.7 Create database migration scripts for Oracle
  - Convert PostgreSQL migrations to Oracle syntax (NUMBER, VARCHAR2, sequences)
  - Test migrations against Oracle database
  - _Requirements: 1.1_

- [x] 2. JPA Repositories
  - Create Spring Data JPA repositories for configuration entities
  - _Requirements: 1.1, 2.3, 2.4_

- [x] 2.1 Create ConfigPipelineRepository
  - Create interface extending JpaRepository<ConfigPipeline, Long>
  - Add query methods: findByPipelineKey, findByEnvironment, findBySiteAndEnvironmentAndHistoricalModeEnabled, existsByPipelineKey
  - _Requirements: 1.1, 16.4, 16.5_

- [x] 2.2 Create ConfigEtlServerRepository
  - Create interface extending JpaRepository<ConfigEtlServer, Long>
  - Add query methods: findByServerKey, findByEnvironment, findByEnvironmentAndIsHistoricalSenderTrue, existsByServerKey
  - _Requirements: 1.1, 16.6_

- [x] 2.3 Create ConfigDbConnectionRepository
  - Create interface extending JpaRepository<ConfigDbConnection, Long>
  - Add query methods: findByConnectionKey, findByEnvironment, existsByConnectionKey
  - _Requirements: 1.1_

- [x] 2.4 Enhance AuditLogRepository with new query methods
  - Add query method: findByResourceTypeAndCreatedAtBetween for filtering by resource type and date range
  - Add query method: countByResourceTypeAndAction for statistics
  - _Requirements: 11.4, 12.2_

- [-] 3. Password Encryption Service
  - Implement encryption/decryption for sensitive credentials
  - _Requirements: 8.5, 9.5_

- [x] 3.1 Implement PasswordEncryptionService
  - Create service with encrypt(String plaintext) and decrypt(String encrypted) methods
  - Use Spring Security's Encryptors.text() with AES-256
  - Read encryption key from application properties (security.encryption.key)
  - Add @PostConstruct to initialize encryptor
  - _Requirements: 8.5, 9.5_

- [ ]\* 3.2 Write property test for password encryption round trip
  - **Property 2: Password Encryption Round Trip**
  - Generate random passwords (8-64 chars, alphanumeric + special chars)
  - Test encrypt then decrypt produces original value
  - Run 100+ iterations
  - **Validates: Requirements 8.5, 9.5**

- [x] 4. YAML Configuration Loader
  - Implement YAML file parsing for fallback mechanism
  - _Requirements: 3.1, 3.2, 3.4_

- [x] 4.1 Implement YamlConfigLoader for pipelines
  - Create component to load etljobs.yml using Jackson YAML
  - Implement loadPipelineFromYaml(String pipelineKey) method
  - Implement loadAllPipelinesFromYaml() method
  - Parse stages array and build ConfigPipelineStage entities
  - Infer environment from site name (e.g., "CEBU-PROD" → "PROD")
  - Auto-detect historical pipelines (pipeline key contains "HIST" or "HISTORICAL")
  - _Requirements: 3.1, 3.2, 16.7_

- [x] 4.2 Implement YamlConfigLoader for ETL servers
  - Implement loadServerFromYaml(String serverKey) method
  - Implement loadAllServersFromYaml() method
  - Auto-detect historical senders (server key or config name contains "HIST")
  - _Requirements: 3.1, 3.2, 16.7_

- [x] 4.3 Implement YamlConfigLoader for database connections
  - Implement loadConnectionFromYaml(String connectionKey) method
  - Implement loadAllConnectionsFromYaml() method
  - Parse Hikari connection pool settings
  - _Requirements: 3.1, 3.2_

- [ ]\* 4.4 Write property test for YAML fallback consistency
  - **Property 1: Configuration Loading Fallback Consistency**
  - Generate random pipeline keys, test loading from YAML when DB is empty
  - Verify loaded entities have valid structure (non-null site, valid port range, valid environment)
  - **Validates: Requirements 1.3, 3.1, 3.2, 3.4**

- [x] 5. Configuration Service Layer
  - Implement core business logic for configuration management
  - _Requirements: 1.2, 1.3, 1.5, 2.3, 2.4, 3.4, 14.1-14.7_

- [x] 5.1 Implement ConfigurationService interface
  - Define interface with methods for CRUD operations on all three config types
  - Add methods: getAllPipelines, getPipelinesBySite, getPipelineByKey, savePipeline, deletePipeline
  - Add methods: getAllEtlServers, getHistoricalEtlServers, getEtlServerByKey, saveEtlServer, deleteEtlServer
  - Add methods: getAllDbConnections, getDbConnectionByKey, saveDbConnection, deleteDbConnection
  - Add methods: getSitesByEnvironment, getSendersBySite (with historicalMode param)
  - Add validation methods and cache management methods
  - _Requirements: 1.2, 2.3, 2.4, 16.5, 16.6_

- [x] 5.2 Implement ConfigurationServiceImpl with caching
  - Inject repositories, YAML loader, encryption service, audit service
  - Initialize Caffeine caches for pipelines, servers, connections (1 hour TTL, max 1000 entries)
  - Implement getPipelineByKey with cache-first, DB-second, YAML-fallback strategy
  - Implement similar fallback strategy for servers and connections
  - Log warnings when falling back to YAML
  - _Requirements: 1.2, 1.3, 1.5, 3.1, 3.2, 3.4_

- [x] 5.3 Implement pipeline validation logic
  - Validate referenced site exists in database connections
  - Validate referenced server exists in ETL servers
  - Validate socket port is in range 1-65535
  - Validate stage dependencies (no circular dependencies, all deps exist)
  - For historical pipelines, validate distinct senderId from non-historical pipelines for same site
  - _Requirements: 14.1, 14.2, 14.4, 14.7, 16.2_

- [ ]\* 5.4 Write property test for referential integrity validation
  - **Property 3: Configuration Validation Referential Integrity**
  - Generate random pipeline with invalid site reference
  - Verify validation throws ValidationException
  - **Validates: Requirements 14.1, 14.2**

- [ ]\* 5.5 Write property test for port range validation
  - **Property 10: Port Range Validation**
  - Generate random port values including out-of-range (-1000 to 70000)
  - Verify validation rejects ports < 1 or > 65535
  - **Validates: Requirements 14.4**

- [ ]\* 5.6 Write property test for distinct sender ID validation
  - **Property 15: Distinct Sender ID Validation**
  - Create two pipelines for same site, one historical and one normal, with same senderId
  - Verify validation rejects the configuration
  - **Validates: Requirements 16.2**

- [x] 5.7 Implement ETL server validation logic
  - Validate host is valid hostname or IP address
  - Validate SSH port and socket port are in range 1-65535
  - Validate user and password are not empty
  - _Requirements: 14.3, 14.4_

- [x] 5.8 Implement database connection validation logic
  - Validate connection string format for Oracle and PostgreSQL
  - Validate Hikari pool settings (positive integers, maximumPoolSize >= minimumIdle)
  - _Requirements: 14.5, 14.6_

- [x] 5.9 Implement save operations with encryption and auditing
  - In savePipeline: encrypt passwords if needed, validate, save to DB, invalidate cache, create audit log
  - In saveEtlServer: encrypt password, validate, save to DB, invalidate cache, create audit log
  - In saveDbConnection: encrypt password, validate, save to DB, invalidate cache, create audit log
  - Capture old state before save for audit log details (before/after JSON)
  - _Requirements: 8.5, 9.5, 10.2, 15.1_

- [ ]\* 5.10 Write property test for cache invalidation
  - **Property 5: Cache Invalidation on Modification**
  - Load entity into cache, modify it, verify cache is invalidated and next read fetches fresh data
  - **Validates: Requirements 15.1, 15.2, 15.3**

- [x] 6. Enhanced Audit Service
  - Extend audit logging to cover configuration changes and ETL events
  - _Requirements: 10.1-10.8, 11.8, 12.6_

- [x] 6.1 Enhance AuditService with configuration change logging
  - Add method: logConfigChange(action, resourceType, resourceId, detailsJson)
  - Add method: logSessionEvent(action, sessionId, sessionDetailsMap)
  - Add method: logEtlTrigger(requestId, site, senderId, status, message)
  - Serialize details to JSON, exclude passwords from audit log details
  - Capture IP address and user agent from HttpServletRequest
  - _Requirements: 10.2, 10.3, 10.4, 10.6, 10.7_

- [ ]\* 6.2 Write property test for audit log immutability
  - **Property 4: Audit Log Immutability**
  - Create audit log entry, attempt to update or delete
  - Verify operation is rejected (audit logs are append-only)
  - **Validates: Requirements 10.8**

- [ ]\* 6.3 Write property test for audit log action recording
  - **Property 9: Audit Log Action Recording**
  - Perform config CRUD operation, verify audit log created with correct action, resource type, resource ID, user ID
  - **Validates: Requirements 10.2, 10.6**

- [x] 6.4 Implement audit log search and filtering
  - Implement searchAuditLogs(criteria, pageable) using AuditLogRepository
  - Support filtering by userId, action, resourceType, startDate, endDate
  - Return paginated results sorted by createdAt descending
  - _Requirements: 11.4, 12.2_

- [x] 6.5 Implement audit log CSV export
  - Add method: exportToCSV(criteria) that generates CSV byte array
  - Include columns: timestamp, userId, username, action, resourceType, resourceId, status, ipAddress
  - Exclude sensitive details from CSV export
  - _Requirements: 11.6_

- [x] 7. REST API Controllers
  - Create RESTful endpoints for configuration and audit management
  - _Requirements: 4.1-4.5, 5.1-5.5, 7.1, 8.1-8.7, 9.1-9.7, 11.1-11.7, 12.1-12.7_

- [x] 7.1 Implement ConfigurationController for pipelines
  - Create @RestController with @RequestMapping("/api/configuration")
  - Add GET /pipelines?environment={env}&historicalMode={bool} endpoint
  - Add GET /pipelines/{key} endpoint
  - Add POST /pipelines endpoint with @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
  - Add PUT /pipelines/{key} endpoint with admin authorization
  - Add DELETE /pipelines/{key} endpoint with admin authorization
  - Return appropriate HTTP status codes (200, 201, 404, 400, 403)
  - _Requirements: 7.1, 7.2, 7.3, 7.4, 7.5, 7.6, 7.7_

- [x] 7.2 Implement ConfigurationController for ETL servers
  - Add GET /etl-servers?environment={env}&historicalOnly={bool} endpoint
  - Add GET /etl-servers/{key} endpoint
  - Add POST /etl-servers endpoint with admin authorization
  - Add PUT /etl-servers/{key} endpoint with admin authorization
  - Add DELETE /etl-servers/{key} endpoint with admin authorization
  - Mask password field in response DTOs (return "\*\*\*" instead of actual password)
  - _Requirements: 8.1, 8.2, 8.3, 8.4, 8.5, 8.6, 8.7_

- [x] 7.3 Implement ConfigurationController for database connections
  - Add GET /db-connections?environment={env} endpoint
  - Add GET /db-connections/{key} endpoint
  - Add POST /db-connections endpoint with admin authorization
  - Add PUT /db-connections/{key} endpoint with admin authorization
  - Add DELETE /db-connections/{key} endpoint with admin authorization
  - Mask password field in response DTOs
  - _Requirements: 9.1, 9.2, 9.3, 9.4, 9.5, 9.6, 9.7_

- [x] 7.4 Implement ConfigurationController query endpoints for Step 1 UI
  - Add GET /sites?environment={env} endpoint returning list of site keys
  - Add GET /senders?site={site}&environment={env}&historicalMode={bool} endpoint returning SenderOption list
  - Add GET /health endpoint returning ConfigurationSourceHealth status
  - _Requirements: 4.1, 4.2, 4.3, 4.4, 4.5, 5.1, 5.2, 5.3, 5.4, 5.5, 16.4, 16.5, 16.6_

- [ ]\* 7.5 Write property test for environment filter consistency
  - **Property 6: Environment Filter Consistency**
  - Create test data with mixed PROD/QA environments
  - Query with environment filter, verify all returned entities match the filter
  - **Validates: Requirements 2.3, 2.4**

- [ ]\* 7.6 Write property test for historical pipeline separation
  - **Property 13: Historical Pipeline Separation**
  - Create site with both historical and normal pipelines
  - Query with historicalMode=false, verify only non-historical returned
  - Query with historicalMode=true, verify only historical returned
  - **Validates: Requirements 16.4, 16.5**

- [x] 7.7 Implement AuditLogController
  - Create @RestController with @RequestMapping("/api/audit-logs")
  - Add @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')") on class level
  - Add GET /audit-logs endpoint with pagination and filtering params
  - Add GET /audit-logs/{id} endpoint
  - Add GET /audit-logs/statistics endpoint
  - Add GET /audit-logs/export endpoint returning CSV file
  - _Requirements: 11.1, 11.2, 11.3, 11.4, 11.5, 11.6, 12.1, 12.2, 12.3, 12.4, 12.5, 12.6, 12.7_

- [x] 8. Configuration Migration Utility
  - Create utility to import YAML configurations into database
  - _Requirements: 13.1-13.7_

- [x] 8.1 Implement ConfigMigrationService
  - Create service with method: migrateYamlToDatabase(boolean overwriteExisting)
  - Load all pipelines, servers, connections from YAML
  - For each entity, check if exists in DB
  - If exists and overwriteExisting=false, skip and log
  - If exists and overwriteExisting=true, update DB entry
  - If not exists, create new DB entry
  - Auto-detect historical entities (name contains "HIST")
  - Encrypt passwords before saving to DB
  - _Requirements: 13.1, 13.2, 13.3, 13.5, 13.6, 16.7_

- [ ]\* 8.2 Write property test for HIST sender auto-detection
  - **Property 14: Historical Sender Auto-Detection**
  - Generate sender configs with names containing "HIST" (various casings)
  - Verify isHistoricalSender flag is automatically set to true
  - **Validates: Requirements 16.7**

- [x] 8.3 Implement migration summary report
  - Track counts: total, imported, skipped, failed
  - Generate summary with lists of successful and failed entries
  - Log summary at INFO level
  - Create audit log entry with migration summary as details
  - _Requirements: 13.4, 13.7_

- [x] 8.4 Create migration CLI command or admin endpoint
  - Option A: Create Spring Boot CommandLineRunner for CLI execution
  - Option B: Create admin REST endpoint POST /api/admin/migrate-config
  - Require SUPER_ADMIN role for endpoint
  - _Requirements: 13.1_

- [ ] 9. Checkpoint - Backend Core Complete
  - **STOP: Push code to remote repository**
  - **Execute remote tests**: `mvn test -f backend/pom.xml`
  - Verify all backend services, repositories, and controllers implemented
  - Verify property-based tests passing (if implemented)
  - Verify database migrations applied successfully on remote test database
  - Verify YAML fallback works when DB is empty
  - **Do NOT proceed to frontend until backend tests pass remotely**
  - _Note: All testing must occur on remote node with Java/Maven installed_

- [ ] 10. Frontend Admin Panel - Pipeline Configuration
  - Create Angular component for pipeline CRUD operations
  - _Requirements: 7.1-7.8_

- [ ] 10.1 Create AdminPipelineConfigComponent
  - Create standalone component with imports: CommonModule, ReactiveFormsModule, GlassTableComponent, GlassDialogService
  - Add template with glass panel table displaying pipelines
  - Add columns: pipelineKey, site, server, senderId, socketPort, environment, historical badge, status badge, actions
  - Add pagination using GlassPaginationComponent
  - Add environment filter dropdown (ALL, PROD, QA)
  - Add search input for filtering by key/site/server
  - Add "Add Pipeline" button opening create dialog
  - _Requirements: 7.1, 7.2, 7.3_

- [ ] 10.2 Implement pipeline list loading with filters
  - Inject ConfigurationService
  - Load pipelines on component init and when filters change
  - Implement debounced search (300ms delay)
  - Display loading spinner during fetch
  - Handle errors with toast notifications
  - _Requirements: 7.2, 7.7_

- [ ] 10.3 Create PipelineFormDialogComponent
  - Create dialog component for create/edit pipeline
  - Add form fields: pipelineKey, site (dropdown), server (dropdown), socketPort, configName, senderId, rerunPeriodMinutes, environment (radio), historicalModeEnabled (checkbox), enabled (checkbox)
  - Add dynamic stages form array with add/remove stage buttons
  - Add stage fields: name, type (dropdown), timeoutMinutes, dependsOn (multi-select)
  - Implement form validation (required fields, port range)
  - _Requirements: 7.3, 7.4_

- [ ] 10.4 Implement pipeline create/update/delete operations
  - Wire create dialog to call ConfigurationService.createPipeline
  - Wire edit dialog to call ConfigurationService.updatePipeline
  - Wire delete button to show confirmation dialog then call ConfigurationService.deletePipeline
  - Show success/error toasts after operations
  - Reload pipeline list after successful operation
  - _Requirements: 7.3, 7.4, 7.5, 7.6_

- [ ] 10.5 Add historical mode badge display
  - Add CSS for historical badge styling (gold/amber color)
  - Display "HISTORICAL" badge when historicalModeEnabled is true
  - Display tooltip explaining historical mode when hovering badge
  - _Requirements: 7.8, 16.8_

- [ ] 11. Frontend Admin Panel - ETL Server Configuration
  - Create Angular component for ETL server CRUD operations
  - _Requirements: 8.1-8.8_

- [ ] 11.1 Create AdminEtlServerConfigComponent
  - Create component similar structure to AdminPipelineConfigComponent
  - Add columns: serverKey, host, sshPort, socketPort, user, password (masked), environment, historical badge, status, actions
  - Add filters for environment and historical-only toggle
  - _Requirements: 8.1, 8.2, 8.3_

- [ ] 11.2 Create EtlServerFormDialogComponent
  - Add form fields: serverKey, host, sshPort, socketPort, user, password (password input), timeoutMs, environment, enabled, isHistoricalSender
  - Show password strength indicator
  - Implement "Show password" toggle button
  - Add "Test Connection" button to verify SSH connectivity
  - _Requirements: 8.4, 8.5, 8.7_

- [ ] 11.3 Implement ETL server CRUD operations
  - Wire create/update/delete to ConfigurationService
  - Mask password in display (show "\*\*\*" instead of real password)
  - Show success/error toasts
  - _Requirements: 8.4, 8.5, 8.6, 8.8_

- [ ] 12. Frontend Admin Panel - Database Connection Configuration
  - Create Angular component for database connection CRUD operations
  - _Requirements: 9.1-9.8_

- [ ] 12.1 Create AdminDbConnectionConfigComponent
  - Add columns: connectionKey, dbType, host, schema, user, password (masked), environment, status, actions
  - Add filter for environment
  - _Requirements: 9.1, 9.2_

- [ ] 12.2 Create DbConnectionFormDialogComponent
  - Add form fields: connectionKey, dbType (dropdown: Oracle/PostgreSQL), schema, host, user, password, connectionTimeoutMs, maximumPoolSize, minimumIdle, downloadUrl, environment, enabled
  - Show Hikari pool settings section
  - Add "Test Connection" button to verify DB connectivity
  - _Requirements: 9.3, 9.4, 9.5, 9.6, 9.7_

- [ ] 12.3 Implement database connection CRUD operations
  - Wire create/update/delete to ConfigurationService
  - Mask password in display
  - Show success/error toasts
  - _Requirements: 9.4, 9.5, 9.6, 9.8_

- [ ] 13. Frontend Admin Navigation
  - Update navigation structure to include Admin section
  - _Requirements: 6.1-6.7_

- [ ] 13.1 Create Admin navigation menu
  - Add "Admin" top-level menu item with expansion
  - Add sub-items: "User Management", "Pipeline Configuration", "ETL Servers", "Database Connections", "Audit Logs"
  - Implement role-based visibility (show only to ADMIN and SUPER_ADMIN)
  - Wire sub-items to route to respective admin components
  - _Requirements: 6.1, 6.2, 6.3, 6.4, 6.5, 6.6, 6.7_

- [ ] 13.2 Move existing User Management under Admin menu
  - Update routing to move user management under /admin/users
  - Update navigation links
  - _Requirements: 6.2_

- [ ] 14. Frontend Enhanced Audit Log Viewer
  - Enhance existing AuditLogTableComponent with new features
  - _Requirements: 11.1-11.8_

- [ ] 14.1 Add resource type filter to AuditLogTableComponent
  - Add resource type dropdown filter with options: USER, PIPELINE, ETL_SERVER, DB_CONNECTION, SESSION, ETL_TRIGGER, SYSTEM
  - Wire filter to loadAuditLogs method
  - _Requirements: 11.4_

- [ ] 14.2 Add date range filter to AuditLogTableComponent
  - Add GlassDateRangeComponent for filtering by created date
  - Wire date range to loadAuditLogs method with startDate/endDate params
  - _Requirements: 11.4_

- [ ] 14.3 Implement audit log detail view dialog
  - Create dialog that displays full audit log entry with formatted JSON details
  - Show before/after comparison for update actions
  - Syntax highlight JSON details
  - _Requirements: 11.5_

- [ ] 14.4 Implement audit log CSV export
  - Add "Export" button to audit log viewer
  - Call AuditService.exportAuditLogs with current filters
  - Trigger browser download of CSV file
  - Show success/error toast
  - _Requirements: 11.6_

- [ ] 14.5 Add audit log source indicator column
  - Add column showing resource type badge (color-coded by type)
  - Use different colors for USER, PIPELINE, ETL_SERVER, SESSION, etc.
  - _Requirements: 11.7_

- [ ] 15. Frontend Step 1 UI Enhancements
  - Update stepper component to use configuration service
  - _Requirements: 4.1-4.5, 5.1-5.5, 16.4, 16.5, 16.9_

- [ ] 15.1 Update StepperComponent to load sites from configuration service
  - Replace existing site loading logic with ConfigurationService.getSites(environment)
  - Add effect to reload sites when environment changes
  - Display sites in formatted dropdown
  - Sort sites alphabetically
  - _Requirements: 5.1, 5.2, 5.3_

- [ ] 15.2 Update StepperComponent to load senders with historical mode support
  - Replace existing sender loading logic with ConfigurationService.getSenders(site, environment, historicalMode)
  - Add effect to reload senders when site OR historicalMode changes
  - Auto-select sender if only one matches
  - Display sender source indicator (database/YAML) for admin users
  - _Requirements: 4.3, 4.4, 5.4, 5.5, 16.6, 16.9_

- [ ] 15.3 Implement historical mode toggle behavior
  - When historical mode toggle changes, clear sender selection
  - Reload senders filtered by historical mode flag
  - Show informational message explaining historical mode if enabled
  - _Requirements: 16.4, 16.5, 16.9_

- [ ] 16. Integration Testing
  - Write end-to-end integration tests for critical workflows
  - _Requirements: All_

- [ ] 16.1 Write integration test for pipeline CRUD workflow
  - Test: Create pipeline via API → Verify DB persistence → Read back via API → Verify matches
  - Test: Update pipeline → Verify audit log created → Verify cache invalidated
  - Test: Delete pipeline → Verify removed from DB → Verify cannot read back

- [ ] 16.2 Write integration test for YAML fallback workflow
  - Test: Empty database → Load config by key → Verify YAML fallback used
  - Test: Load from DB when available → Verify DB takes precedence over YAML

- [ ] 16.3 Write integration test for historical pipeline workflow
  - Test: Create site with normal and historical pipelines
  - Test: Query with historicalMode=false → Verify only normal pipeline returned
  - Test: Query with historicalMode=true → Verify only historical pipeline returned
  - Test: Step 1 UI auto-selects correct sender based on historical mode toggle

- [ ] 16.4 Write integration test for audit logging workflow
  - Test: Perform config change → Verify audit log created with correct details
  - Test: Export audit logs to CSV → Verify file contains expected data
  - Test: Query audit logs with filters → Verify correct results returned

- [ ] 17. Documentation and Deployment
  - Update documentation and prepare for deployment
  - _Requirements: All_

- [ ] 17.1 Update API documentation
  - Document all new REST endpoints with request/response examples
  - Update OpenAPI/Swagger spec if used
  - Include authentication and authorization requirements

- [ ] 17.2 Update user guide
  - Document Admin panel usage for configuration management
  - Document historical mode pipeline configuration
  - Document audit log viewer features
  - Include screenshots of admin panels

- [ ] 17.3 Create deployment checklist
  - List required database migrations
  - Document encryption key setup in application properties
  - Document YAML migration procedure
  - Document rollback procedure if needed

- [ ] 17.4 Create configuration migration guide
  - Document step-by-step process for migrating YAML to database
  - Document testing procedure to verify migration success
  - Document YAML fallback behavior and when it activates

## Notes

### MVP Implementation Strategy

**Recommended MVP Workflow:**

1. **Phase 1 - Backend Foundation** (Tasks 1-6, skip `*` tasks)
   - Database schema, entities, repositories
   - Services with YAML fallback
   - Password encryption
   - Basic audit logging

2. **Phase 2 - API Layer** (Tasks 7-8, skip `*` tasks)
   - REST endpoints for config CRUD
   - Query endpoints for Step 1 UI
   - Migration utility

3. **Phase 3 - Checkpoint** (Task 9)
   - Manual testing of backend APIs
   - Verify YAML fallback works
   - Verify database operations work

4. **Phase 4 - Frontend** (Tasks 10-15, skip `*` tasks)
   - Admin panels for config management
   - Enhanced audit log viewer
   - Step 1 UI integration

5. **Phase 5 - Deployment** (Task 17, skip integration tests)
   - Documentation
   - Deployment checklist
   - User guide

**After MVP is deployed and stable**, come back to implement:

- Property-based tests (tasks marked with `*`)
- Integration tests (Task 16)
- Advanced validation scenarios

### General Notes

- Tasks marked with `*` are optional and can be skipped for MVP
- Each task references specific requirements for traceability
- Checkpoints ensure incremental validation before proceeding to frontend
- Backend tasks should be completed and tested before starting frontend tasks

### Environment Constraints (CRITICAL)

**⚠️ All compilation, testing, and builds MUST be executed on a remote development node!**

**Local machine limitations:**

- ❌ No Java, Maven, Node.js, npm, Python, Perl, or Git available locally
- ✅ Local code editing and file inspection fully supported
- ✅ Use file tools to create/modify code locally

**Development workflow:**

1. **Edit code locally** using Kiro file tools (fsWrite, strReplace, etc.)
2. **Push changes** to remote repository (manual step - ask user to push)
3. **Execute tests remotely** on development node with all tooling installed
4. **Review results** and iterate

**Remote testing commands:**

```bash
# Backend: Build without tests
mvn clean package -DskipTests -f backend/pom.xml

# Backend: Run all tests
mvn test -f backend/pom.xml

# Backend: Run specific test class
mvn test -Dtest=ConfigurationServiceTest -f backend/pom.xml

# Backend: Run with PostgreSQL profile
mvn test -Ppostgresql -f backend/pom.xml

# Property-based tests (run 100+ iterations)
mvn test -Dtest=*PropertyTest -f backend/pom.xml

# Frontend: Build
npm run build

# Frontend: Run tests
npm test -- --run
```

**Implementation approach:**

- Write all code locally using Kiro
- After completing a task or checkpoint, inform user to push and test remotely
- Do NOT attempt `mvn`, `npm`, or `git` commands locally - they will fail
- For testing tasks, provide the test code and ask user to run it remotely
