# Requirements Document

## Introduction

This specification defines the Configuration Management Enhancement feature for the Exensio Reload application. The system currently relies on static YAML configuration files (etljobs.yml, etlservers.yml, dbconnections.yml) for managing ETL pipelines, server connections, and database configurations. This enhancement migrates configuration data to an internal database with profile-aware loading, YAML fallback support, and provides administrative UI for CRUD operations. Additionally, the audit logging system will be enhanced to comprehensively track all system activities including configuration changes.

## Glossary

- **Configuration_System**: The system responsible for loading, storing, and managing ETL pipeline, server, and database configuration data
- **Internal_DB**: The application's PostgreSQL or Oracle database instance used for storing configuration metadata
- **YAML_Fallback**: The mechanism that loads configuration from YAML files when database entries are unavailable or disabled
- **Profile**: Spring Boot application profile (e.g., postgresql, oracle) that determines which database type the application uses for its internal operations
- **Admin_Panel**: The administrative user interface for managing configuration entities
- **Audit_System**: The system responsible for recording and tracking all user actions and system events
- **Sender**: ETL queue destination identified by senderId, port, and name
- **Pipeline**: ETL workflow definition linking a site to server and queue configuration
- **ETL_Server**: Remote SSH server hosting DataPort Command Processor
- **DB_Connection**: Database connection configuration for a specific site and environment
- **Step_1_UI**: The configuration step in the stepper component where users select environment, site, and sender

## Requirements

### Requirement 1: Database-Backed Configuration Storage

**User Story:** As a system administrator, I want configuration data stored in the internal database, so that I can manage configurations programmatically without manual YAML file edits.

#### Acceptance Criteria

1. THE Configuration_System SHALL create database tables for ETL pipelines, servers, and database connections on application startup
2. WHEN the application starts, THE Configuration_System SHALL load configuration data from the Internal_DB based on the active Profile
3. WHERE database configuration is unavailable or incomplete, THE Configuration_System SHALL fall back to loading from YAML files
4. THE Configuration_System SHALL support both PostgreSQL and Oracle database schemas for configuration storage
5. WHEN configuration is loaded from database, THE Configuration_System SHALL cache the data in memory for performance
6. THE Configuration_System SHALL provide transaction support for configuration CRUD operations

### Requirement 2: Environment-Independent Configuration Loading

**User Story:** As a developer, I want all configuration data (PROD, QA, and all sites) loaded regardless of the active Spring profile, so that administrators can manage any environment's configuration from a single instance.

#### Acceptance Criteria

1. WHEN the application starts, THE Configuration_System SHALL load all pipeline, server, and database connection configurations from the Internal_DB regardless of active Spring profile
2. THE Configuration_System SHALL store environment indicator (PROD/QA) as a field within each configuration entity
3. WHEN Step_1_UI environment filter is set to "PROD", THE Configuration_System SHALL filter and display only PROD-tagged configurations
4. WHEN Step_1_UI environment filter is set to "QA", THE Configuration_System SHALL filter and display only QA-tagged configurations
5. THE Configuration_System SHALL support the same database type (PostgreSQL or Oracle) for storing configuration data as determined by the active Spring profile

### Requirement 3: YAML Fallback Mechanism

**User Story:** As a system operator, I want the system to fall back to YAML configuration files when database entries are unavailable, so that the application remains operational during database issues.

#### Acceptance Criteria

1. WHEN database configuration tables are empty, THE Configuration_System SHALL load all configuration from YAML files
2. WHEN a specific configuration entry is missing from database, THE Configuration_System SHALL load that entry from YAML files
3. WHEN database connection fails during configuration load, THE Configuration_System SHALL fall back to YAML files and log a warning
4. THE Configuration_System SHALL merge database and YAML configurations, with database taking precedence when both exist
5. THE Configuration_System SHALL expose a health indicator showing configuration source (database, YAML, or hybrid)

### Requirement 4: Step 1 UI Enhancement - Sender Auto-Selection

**User Story:** As an end user, I want the sender dropdown to auto-populate based on selected site and environment, so that I don't need to manually search for sender configurations.

#### Acceptance Criteria

1. WHEN a user selects a site in Step_1_UI, THE Configuration_System SHALL query available senders for that site from database/YAML
2. WHEN sender options are loaded, THE Step_1_UI SHALL display sender ID, port, and name in a readable format
3. WHEN only one sender matches the site criteria, THE Step_1_UI SHALL automatically select that sender
4. WHEN multiple senders match, THE Step_1_UI SHALL present all options sorted by sender ID
5. THE Step_1_UI SHALL display sender source indicator (database or YAML) for administrative visibility

### Requirement 5: Step 1 UI Enhancement - Site Dropdown Population

**User Story:** As an end user, I want the site dropdown to populate from database/YAML configuration, so that I can only select valid configured sites.

#### Acceptance Criteria

1. WHEN Step_1_UI loads, THE Configuration_System SHALL provide all available sites for the selected environment
2. WHEN environment changes from PROD to QA, THE Step_1_UI SHALL reload site options filtered by QA environment
3. THE Step_1_UI SHALL display site names formatted consistently (e.g., "CEBU-PROD", "CNK-ONSC-PROD")
4. WHEN no sites are configured for an environment, THE Step_1_UI SHALL display an informative message
5. THE Step_1_UI SHALL sort site options alphabetically for user convenience

### Requirement 6: Admin Navigation Structure

**User Story:** As a system administrator, I want a dedicated Admin section in navigation, so that I can access administrative functions in an organized manner.

#### Acceptance Criteria

1. THE Navigation_System SHALL create a new "Admin" navigation menu item
2. WHEN Admin menu is expanded, THE Navigation_System SHALL display "User Management" as a sub-item
3. WHEN Admin menu is expanded, THE Navigation_System SHALL display "Pipeline Configuration" as a sub-item
4. WHEN Admin menu is expanded, THE Navigation_System SHALL display "ETL Servers" as a sub-item
5. WHEN Admin menu is expanded, THE Navigation_System SHALL display "Database Connections" as a sub-item
6. WHEN Admin menu is expanded, THE Navigation_System SHALL display "Audit Logs" as a sub-item
7. THE Navigation_System SHALL restrict Admin menu visibility to users with ADMIN or SUPER_ADMIN roles

### Requirement 7: Pipeline Configuration CRUD Interface

**User Story:** As a system administrator, I want a UI to create, read, update, and delete pipeline configurations, so that I can manage ETL pipelines without editing YAML files.

#### Acceptance Criteria

1. THE Admin_Panel SHALL display a paginated table of all pipeline configurations
2. WHEN viewing pipeline list, THE Admin_Panel SHALL show pipelineKey, site, server, senderId, and socketPort columns
3. WHEN administrator clicks "Add Pipeline", THE Admin_Panel SHALL open a form for creating new pipeline configuration
4. WHEN administrator edits a pipeline, THE Admin_Panel SHALL pre-populate the form with existing values
5. WHEN administrator saves pipeline changes, THE Admin_Panel SHALL validate required fields and save to database
6. WHEN administrator deletes a pipeline, THE Admin_Panel SHALL prompt for confirmation before deletion
7. THE Admin_Panel SHALL support searching and filtering pipelines by site, server, or sender ID
8. WHEN pipeline configuration is saved, THE Audit_System SHALL record the change with user, timestamp, and details

### Requirement 8: ETL Server Configuration CRUD Interface

**User Story:** As a system administrator, I want a UI to manage ETL server configurations, so that I can add, update, or remove SSH connection details securely.

#### Acceptance Criteria

1. THE Admin_Panel SHALL display a paginated table of all ETL server configurations
2. WHEN viewing server list, THE Admin_Panel SHALL show server key, host, sshPort, socketPort, and user columns
3. WHEN viewing server list, THE Admin_Panel SHALL mask password fields for security
4. WHEN administrator adds or edits a server, THE Admin_Panel SHALL provide form validation for host, port, and credentials
5. WHEN administrator saves server configuration, THE Admin_Panel SHALL encrypt password before storing in database
6. WHEN administrator deletes a server, THE Admin_Panel SHALL check for dependent pipelines and warn if in use
7. THE Admin_Panel SHALL support testing SSH connectivity before saving server configuration
8. WHEN server configuration is modified, THE Audit_System SHALL record the change without logging sensitive credentials

### Requirement 9: Database Connection Configuration CRUD Interface

**User Story:** As a system administrator, I want a UI to manage database connection configurations, so that I can update connection strings and credentials without application restarts.

#### Acceptance Criteria

1. THE Admin_Panel SHALL display a paginated table of all database connection configurations
2. WHEN viewing connection list, THE Admin_Panel SHALL show connection key, dbType, host, schema, and user columns
3. WHEN viewing connection list, THE Admin_Panel SHALL mask password fields for security
4. WHEN administrator edits a connection, THE Admin_Panel SHALL provide validation for connection string format
5. WHEN administrator saves connection configuration, THE Admin_Panel SHALL encrypt sensitive fields before database storage
6. WHEN administrator saves connection configuration, THE Admin_Panel SHALL support testing database connectivity
7. THE Admin_Panel SHALL display Hikari connection pool settings (connectionTimeoutMs, maximumPoolSize, minimumIdle)
8. WHEN database connection is modified, THE Audit_System SHALL record the change without exposing passwords

### Requirement 10: Comprehensive Audit Logging

**User Story:** As a security auditor, I want comprehensive audit logs of all system actions, so that I can track user activities and investigate incidents.

#### Acceptance Criteria

1. THE Audit_System SHALL record all user login and logout events with timestamp and IP address
2. THE Audit_System SHALL record all configuration CRUD operations (pipelines, servers, database connections)
3. THE Audit_System SHALL record all ETL trigger executions with requestId, site, sender, and outcome
4. THE Audit_System SHALL record all staging session events (created, completed, failed, cancelled)
5. THE Audit_System SHALL record user management actions (create, update, delete, role change, password change)
6. THE Audit_System SHALL capture userId, action type, resource type, resource ID, and detailed change JSON
7. THE Audit_System SHALL record client IP address and user agent for all audited actions
8. THE Audit_System SHALL store audit logs with immutable timestamps using database-native timestamp types

### Requirement 11: Enhanced Audit Log UI

**User Story:** As a system administrator, I want a comprehensive audit log viewer, so that I can search, filter, and analyze system activities.

#### Acceptance Criteria

1. THE Audit_Log_UI SHALL display a paginated table of all audit log entries sorted by timestamp descending
2. WHEN viewing audit logs, THE Audit_Log_UI SHALL show timestamp, user, action, resource type, resource ID, and status columns
3. THE Audit_Log_UI SHALL provide search functionality for user, action type, and resource ID
4. THE Audit_Log_UI SHALL provide filter dropdowns for action type, resource type, and date range
5. WHEN administrator clicks on an audit entry, THE Audit_Log_UI SHALL display detailed change information in JSON format
6. THE Audit_Log_UI SHALL support exporting audit logs to CSV for compliance reporting
7. THE Audit_Log_UI SHALL display audit log source (ETL trigger, configuration change, user management, session event)
8. THE Audit_Log_UI SHALL support real-time updates for new audit entries using polling or SSE

### Requirement 12: Audit Log API Endpoints

**User Story:** As a developer, I want RESTful API endpoints for audit logs, so that I can integrate audit data with external monitoring tools.

#### Acceptance Criteria

1. THE Audit_API SHALL provide GET /api/audit-logs endpoint with pagination, sorting, and filtering support
2. WHEN querying audit logs, THE Audit_API SHALL accept parameters: page, size, userId, action, resourceType, startDate, endDate
3. THE Audit_API SHALL provide GET /api/audit-logs/{id} endpoint for retrieving individual audit log details
4. THE Audit_API SHALL provide GET /api/audit-logs/statistics endpoint for aggregate audit metrics
5. THE Audit_API SHALL restrict audit log access to users with ADMIN or SUPER_ADMIN roles
6. THE Audit_API SHALL return audit logs in JSON format with consistent timestamp formatting (ISO-8601)
7. THE Audit_API SHALL support filtering audit logs by multiple action types or resource types simultaneously

### Requirement 13: Configuration Data Migration

**User Story:** As a system administrator, I want a migration utility to import existing YAML configurations into the database, so that I can transition smoothly to database-backed configuration.

#### Acceptance Criteria

1. THE Migration_Utility SHALL provide a command-line tool or admin UI option to trigger YAML-to-database import
2. WHEN migration runs, THE Migration_Utility SHALL parse all three YAML files (etljobs.yml, etlservers.yml, dbconnections.yml)
3. WHEN migration encounters existing database entries, THE Migration_Utility SHALL prompt whether to overwrite or skip
4. WHEN migration completes, THE Migration_Utility SHALL generate a summary report showing imported, skipped, and failed entries
5. THE Migration_Utility SHALL validate all imported configuration data against schema constraints before committing
6. WHEN migration fails for any entry, THE Migration_Utility SHALL log the error and continue with remaining entries
7. THE Audit_System SHALL record the migration event with summary statistics and initiating user

### Requirement 14: Configuration Validation

**User Story:** As a system administrator, I want configuration validation on save, so that I can catch errors before they cause runtime failures.

#### Acceptance Criteria

1. WHEN saving pipeline configuration, THE Configuration_System SHALL validate that referenced site exists in database connections
2. WHEN saving pipeline configuration, THE Configuration_System SHALL validate that referenced server exists in ETL servers
3. WHEN saving ETL server configuration, THE Configuration_System SHALL validate that host is a valid hostname or IP address
4. WHEN saving ETL server configuration, THE Configuration_System SHALL validate that ports are in valid range (1-65535)
5. WHEN saving database connection, THE Configuration_System SHALL validate connection string format for dbType (Oracle, PostgreSQL)
6. WHEN saving database connection, THE Configuration_System SHALL validate Hikari pool settings (positive integers, maximumPoolSize >= minimumIdle)
7. WHEN validation fails, THE Configuration_System SHALL return clear error messages indicating which fields are invalid

### Requirement 15: Configuration Change Notifications

**User Story:** As a developer, I want configuration changes to be propagated to running components, so that the application adapts to configuration updates without restart.

#### Acceptance Criteria

1. WHEN pipeline configuration is modified, THE Configuration_System SHALL invalidate cached pipeline data
2. WHEN ETL server configuration is modified, THE Configuration_System SHALL update SSH connection pool for that server
3. WHEN database connection is modified, THE Configuration_System SHALL notify connection pool manager to refresh connections
4. THE Configuration_System SHALL provide Spring application events for configuration change notifications
5. WHEN configuration change event is published, THE Configuration_System SHALL include change type, entity key, and modified fields
6. THE Configuration_System SHALL support graceful handling of configuration changes without interrupting active operations

### Requirement 16: Historical Mode Pipeline Configuration

**User Story:** As a system administrator, I want to configure separate historical mode pipelines with distinct sender IDs, ports, and config names, so that historical data queries use dedicated ETL infrastructure.

#### Acceptance Criteria

1. THE Configuration_System SHALL support multiple pipeline configurations for the same site with different pipeline keys
2. WHEN a pipeline is marked as historical (historicalModeEnabled = true), THE Configuration_System SHALL validate that it has a distinct senderId from non-historical pipelines for the same site
3. THE Admin_Panel SHALL allow administrators to create historical pipelines with unique socketPort and configName values
4. WHEN Step_1_UI historical mode toggle is enabled, THE Configuration_System SHALL query only historical-enabled pipelines for the selected site
5. WHEN Step_1_UI historical mode toggle is disabled, THE Configuration_System SHALL query only non-historical pipelines for the selected site
6. THE Configuration_System SHALL support storing HIST pattern senders separately with isHistoricalSender flag for auto-resolution
7. WHEN a sender name contains "HIST" (case-insensitive), THE Configuration_System SHALL automatically mark it as a historical sender during migration
8. THE Admin_Panel SHALL display historical mode indicator badge for pipelines in list views
9. THE Step_1_UI SHALL auto-select the appropriate pipeline (historical or normal) based on the historical mode toggle state

## Notes

- All database schema changes must support both PostgreSQL and Oracle databases
- Sensitive fields (passwords, credentials) must be encrypted at rest using Spring Security's encryption utilities
- YAML files remain the source of truth for initial deployments and disaster recovery scenarios
- Configuration UI must follow existing glass panel design system for consistency
- Audit logs should be retained for minimum 90 days per compliance requirements
- Real-time configuration updates should use optimistic locking to prevent concurrent modification conflicts
