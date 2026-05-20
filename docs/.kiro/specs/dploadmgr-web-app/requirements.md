# Requirements Document: DpLoadMgr Web Application

## Introduction

The DpLoadMgr Web Application provides a modern web-based interface for managing data load processes across multiple servers (usaz15ls082 and usaz15ls083). The system wraps existing Perl-based command-line tools (`DpLoadMgr.pl`) and exposes their functionality through a user-friendly Angular frontend with a Java Spring Boot backend. The application reuses authentication, authorization, and UI/UX patterns from the existing exensioreload application.

## Glossary

- **DpLoadMgr**: Data Processing Load Manager - the existing Perl script that manages load processes
- **Processor**: A data load process configuration defined in a .cfg file
- **Group**: A logical grouping of processors defined in the .mgr file
- **Server**: Remote Unix server (usaz15ls082 or usaz15ls083) where DpLoadMgr.pl executes
- **SSH_Client**: Service component that executes remote commands via SSH
- **Output_Parser**: Service component that parses CLI output into structured data
- **Process_Status**: The current state of a processor (Running, Stopped, etc.)
- **Selection_Mode**: User choice between viewing processors by default list (1) or by groups (2)
- **Backend_API**: Java Spring Boot REST API layer
- **Frontend_UI**: Angular-based user interface

## Requirements

### Requirement 1: Multi-Server Command Execution

**User Story:** As a system operator, I want to execute DpLoadMgr commands on both servers simultaneously, so that I can manage all load processes from a single interface.

#### Acceptance Criteria

1. WHEN a user initiates a command, THE Backend_API SHALL execute the command on both usaz15ls082 and usaz15ls083 via SSH
2. WHEN executing commands on multiple servers, THE SSH_Client SHALL execute them in parallel to minimize latency
3. WHEN a server is unreachable, THE Backend_API SHALL return a partial result with error details for the failed server
4. WHEN SSH authentication fails, THE Backend_API SHALL return an authentication error with the server hostname
5. THE Backend_API SHALL support configurable SSH connection timeout (default 30 seconds)
6. THE Backend_API SHALL support configurable SSH command execution timeout (default 60 seconds)

### Requirement 2: Process Status Retrieval

**User Story:** As a system operator, I want to view the status of all data load processors, so that I can monitor which processes are running or stopped.

#### Acceptance Criteria

1. WHEN a user requests process status, THE Backend_API SHALL execute `dpstatus` command on configured servers
2. WHEN the status command returns output, THE Output_Parser SHALL parse the selection mode prompt
3. WHEN selection mode 1 (default) is chosen, THE Output_Parser SHALL parse the numbered list of .cfg file paths
4. WHEN selection mode 2 (groups) is chosen, THE Output_Parser SHALL parse the numbered list of group names
5. WHEN a processor is selected, THE Output_Parser SHALL parse the status line containing process state and command details
6. THE Output_Parser SHALL extract process state (Running, Stopped, Failed) from status output
7. THE Output_Parser SHALL extract the full command line from status output
8. THE Frontend_UI SHALL display processor status with visual indicators (green for Running, gray for Stopped, red for Failed)

### Requirement 3: Process Start Operation

**User Story:** As a system operator, I want to start stopped data load processors, so that I can resume data processing operations.

#### Acceptance Criteria

1. WHEN a user initiates a start command, THE Backend_API SHALL execute `dpstart` command on configured servers
2. WHEN the start command requires selection mode, THE Backend_API SHALL provide the user's selection mode choice
3. WHEN the start command requires processor selection, THE Backend_API SHALL provide the user's processor selection
4. WHEN a processor starts successfully, THE Backend_API SHALL return success status with processor details
5. WHEN a processor fails to start, THE Backend_API SHALL return error status with failure reason
6. THE Frontend_UI SHALL display start operation progress and results

### Requirement 4: Process Stop Operation

**User Story:** As a system operator, I want to gracefully stop running data load processors, so that I can perform maintenance or troubleshooting.

#### Acceptance Criteria

1. WHEN a user initiates a stop command, THE Backend_API SHALL execute `dpstop` command on configured servers
2. WHEN the stop command requires selection mode, THE Backend_API SHALL provide the user's selection mode choice
3. WHEN the stop command requires processor selection, THE Backend_API SHALL provide the user's processor selection
4. WHEN a processor stops successfully, THE Backend_API SHALL return success status with processor details
5. WHEN a processor fails to stop gracefully, THE Backend_API SHALL return error status with failure reason
6. THE Frontend_UI SHALL display stop operation progress and results

### Requirement 5: Process Kill Operation

**User Story:** As a system operator, I want to forcefully terminate unresponsive data load processors, so that I can recover from hung processes.

#### Acceptance Criteria

1. WHEN a user initiates a kill command, THE Backend_API SHALL execute `dpkill` command on configured servers
2. WHEN the kill command requires selection mode, THE Backend_API SHALL provide the user's selection mode choice
3. WHEN the kill command requires processor selection, THE Backend_API SHALL provide the user's processor selection
4. WHEN a processor is killed successfully, THE Backend_API SHALL return success status with processor details
5. WHEN a processor fails to be killed, THE Backend_API SHALL return error status with failure reason
6. THE Frontend_UI SHALL display a confirmation dialog before executing kill operations
7. THE Frontend_UI SHALL display kill operation progress and results

### Requirement 6: Selection Mode Support

**User Story:** As a system operator, I want to view processors either by individual configuration files or by logical groups, so that I can organize my view based on my workflow.

#### Acceptance Criteria

1. WHEN a user views the processor list, THE Frontend_UI SHALL provide options for selection mode 1 (default) and mode 2 (groups)
2. WHEN selection mode 1 is chosen, THE Frontend_UI SHALL display processors as individual .cfg file paths
3. WHEN selection mode 2 is chosen, THE Frontend_UI SHALL display processors grouped by logical group names
4. WHEN a user switches selection modes, THE Frontend_UI SHALL refresh the processor list
5. THE Backend_API SHALL support both selection modes in all command operations

### Requirement 7: Multi-Processor Selection

**User Story:** As a system operator, I want to select multiple processors for batch operations, so that I can efficiently manage related processes together.

#### Acceptance Criteria

1. WHEN viewing the processor list, THE Frontend_UI SHALL provide checkboxes for multi-selection
2. WHEN processors are selected, THE Frontend_UI SHALL display the count of selected processors
3. WHEN a batch operation is initiated, THE Backend_API SHALL execute the command for all selected processors
4. WHEN batch operations complete, THE Frontend_UI SHALL display individual results for each processor
5. THE Frontend_UI SHALL support "Select All" and "Clear All" actions

### Requirement 8: Real-Time Status Updates

**User Story:** As a system operator, I want to see real-time updates of processor status, so that I can monitor changes without manual refresh.

#### Acceptance Criteria

1. WHEN the dashboard is active, THE Frontend_UI SHALL poll for status updates every 10 seconds
2. WHEN status changes are detected, THE Frontend_UI SHALL update the display with visual indicators
3. WHEN a processor transitions from Stopped to Running, THE Frontend_UI SHALL highlight the change
4. WHEN a processor transitions from Running to Stopped, THE Frontend_UI SHALL highlight the change
5. THE Frontend_UI SHALL display the last update timestamp
6. THE Frontend_UI SHALL provide a manual refresh button

### Requirement 9: Authentication and Authorization

**User Story:** As a system administrator, I want to control access to the DpLoadMgr web application, so that only authorized users can manage load processes.

#### Acceptance Criteria

1. THE Backend_API SHALL reuse the authentication mechanism from exensioreload application
2. THE Backend_API SHALL reuse the authorization mechanism from exensioreload application
3. WHEN an unauthenticated user accesses the application, THE Frontend_UI SHALL redirect to the login page
4. WHEN an authenticated user lacks permissions, THE Backend_API SHALL return HTTP 403 Forbidden
5. THE Backend_API SHALL support role-based access control (RBAC) for operations
6. THE Backend_API SHALL log all command executions with user identity and timestamp

### Requirement 10: Error Handling and Logging

**User Story:** As a system operator, I want clear error messages and detailed logs, so that I can troubleshoot issues effectively.

#### Acceptance Criteria

1. WHEN a command fails, THE Backend_API SHALL return a structured error response with error code and message
2. WHEN SSH connection fails, THE Backend_API SHALL return error details including server hostname and connection error
3. WHEN command execution times out, THE Backend_API SHALL return a timeout error with elapsed time
4. WHEN output parsing fails, THE Backend_API SHALL log the raw output and return a parsing error
5. THE Backend_API SHALL log all command executions with timestamp, user, server, command, and result
6. THE Backend_API SHALL log all errors with stack traces for debugging
7. THE Frontend_UI SHALL display user-friendly error messages with troubleshooting hints

### Requirement 11: Dashboard UI Layout

**User Story:** As a system operator, I want a clean, intuitive dashboard interface, so that I can quickly understand system status and take actions.

#### Acceptance Criteria

1. THE Frontend_UI SHALL display a server selector showing usaz15ls082 and usaz15ls083
2. THE Frontend_UI SHALL display a selection mode toggle (Default/Groups)
3. THE Frontend_UI SHALL display a processor list with status indicators
4. THE Frontend_UI SHALL display action buttons (Start, Stop, Kill, Refresh)
5. THE Frontend_UI SHALL display a status summary showing counts of Running, Stopped, and Failed processors
6. THE Frontend_UI SHALL use Material Design components consistent with exensioreload
7. THE Frontend_UI SHALL use the same color scheme and typography as exensioreload
8. THE Frontend_UI SHALL be responsive and work on desktop and tablet devices

### Requirement 12: Configuration Management

**User Story:** As a system administrator, I want to configure server connection details, so that the application can connect to the correct servers.

#### Acceptance Criteria

1. THE Backend_API SHALL read server configuration from application.yml
2. THE Backend_API SHALL support configuration of server hostnames
3. THE Backend_API SHALL support configuration of SSH port (default 22)
4. THE Backend_API SHALL support configuration of SSH username
5. THE Backend_API SHALL support configuration of SSH private key path or password
6. THE Backend_API SHALL support configuration of DPLOAD environment variable path
7. THE Backend_API SHALL validate configuration on startup and log warnings for missing values

### Requirement 13: Command Output Parsing

**User Story:** As a developer, I want robust parsing of CLI output, so that the application correctly interprets command results.

#### Acceptance Criteria

1. WHEN parsing status output, THE Output_Parser SHALL extract the selection mode prompt
2. WHEN parsing processor lists, THE Output_Parser SHALL extract numbered items with full paths or group names
3. WHEN parsing status results, THE Output_Parser SHALL extract process state using regex pattern matching
4. WHEN parsing status results, THE Output_Parser SHALL extract the full command line
5. WHEN parsing fails due to unexpected format, THE Output_Parser SHALL return a parsing error with the raw output
6. THE Output_Parser SHALL handle variations in whitespace and line endings
7. THE Output_Parser SHALL be case-insensitive when matching status keywords (Running, Stopped, etc.)

### Requirement 14: Audit Trail

**User Story:** As a compliance officer, I want a complete audit trail of all operations, so that I can track who performed what actions and when.

#### Acceptance Criteria

1. WHEN a command is executed, THE Backend_API SHALL create an audit log entry
2. THE Audit_Log SHALL include timestamp, username, server, command type, processor selection, and result
3. THE Audit_Log SHALL be stored in a database table
4. THE Backend_API SHALL provide an API endpoint to query audit logs
5. THE Frontend_UI SHALL provide an audit log viewer with filtering by date, user, server, and command type
6. THE Audit_Log SHALL be retained for a configurable period (default 90 days)

### Requirement 15: Performance and Scalability

**User Story:** As a system operator, I want fast response times, so that I can efficiently manage processes without delays.

#### Acceptance Criteria

1. WHEN executing commands on both servers, THE Backend_API SHALL complete within 5 seconds under normal conditions
2. WHEN parsing command output, THE Output_Parser SHALL complete within 100 milliseconds
3. THE Backend_API SHALL support connection pooling for SSH connections
4. THE Backend_API SHALL cache processor lists for 30 seconds to reduce command executions
5. THE Frontend_UI SHALL display loading indicators during command execution
6. THE Frontend_UI SHALL remain responsive during background polling
