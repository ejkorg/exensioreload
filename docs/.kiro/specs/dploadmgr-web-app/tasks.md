# Implementation Plan: DpLoadMgr Web Application

## Overview

This implementation creates a full-stack web application for managing data load processes across multiple Unix servers. The system wraps existing Perl CLI tools via SSH, parses their output, and provides a modern Angular frontend with Java Spring Boot backend. The implementation reuses authentication, authorization, and UI patterns from the existing dtp-resender-fullstack application.

## Tasks

- [ ] 1. Backend: Configuration and Properties
- [ ] 1.1 Create DpLoadMgrProperties configuration class
  - Add @ConfigurationProperties annotation with prefix "dploadmgr"
  - Add fields: servers, sshUsername, sshPassword, sshPrivateKeyPath, sshPort, sshConnectionTimeout, sshCommandTimeout, dploadPath, mgrFile, statusPollInterval, auditRetentionDays
  - Add validation annotations (@NotEmpty, @NotBlank, @Min, @Max)
  - Add buildCommand() method to construct CLI commands
  - _Requirements: 12.1, 12.2, 12.3, 12.4, 12.5, 12.6_

- [ ] 1.2 Create application.yml configuration
  - Add dploadmgr configuration section
  - Set default values for all properties
  - Document each property with comments
  - _Requirements: 12.1, 12.7_

- [ ] 1.3 Add JSch dependency to pom.xml
  - Add com.github.mwiede:jsch dependency (version 0.2.x)
  - _Requirements: 1.1_

- [ ] 2. Backend: Data Models and DTOs
- [ ] 2.1 Create ProcessState enum
  - Add values: RUNNING, STOPPED, FAILED, UNKNOWN
  - _Requirements: 2.6_

- [ ] 2.2 Create ProcessorStatus record
  - Add fields: number, name, path, state, commandLine, logPath
  - _Requirements: 2.5, 2.6, 2.7_

- [ ] 2.3 Create ProcessorItem record
  - Add fields: number, name, displayText
  - _Requirements: 2.3, 2.4_

- [ ] 2.4 Create ProcessorList record
  - Add fields: selectionMode, items
  - _Requirements: 6.1, 6.2_

- [ ] 2.5 Create OperationResult record
  - Add fields: success, message, affectedProcessors
  - _Requirements: 3.4, 3.5, 4.4, 4.5, 5.4, 5.5_

- [ ] 2.6 Create MultiServerResponse record
  - Add fields: results (Map), errors (Map), allSuccessful
  - Add generic type parameter T
  - _Requirements: 1.3_

- [ ] 2.7 Create ProcessorOperationRequest record
  - Add fields: servers, selectionMode, processorNumbers
  - _Requirements: 3.2, 4.2, 5.2_

- [ ] 2.8 Create CommandResult record
  - Add fields: success, output, exitCode, errorMessage
  - _Requirements: 1.4, 10.1, 10.2_

- [ ] 2.9 Create ParseResult record
  - Add fields: success, value, errorMessage
  - Add generic type parameter T
  - Add static factory methods: success(T), failure(String)
  - _Requirements: 10.4, 13.5_

- [ ] 3. Backend: SSH Client Service
- [ ] 3.1 Create SshClientService class
  - Add @Service annotation
  - Inject DpLoadMgrProperties
  - Create ExecutorService for parallel execution
  - _Requirements: 1.1, 1.2_

- [ ] 3.2 Implement createSession() method
  - Create JSch instance
  - Configure SSH key or password authentication
  - Set connection timeout
  - Set StrictHostKeyChecking to "no"
  - Handle JSchException
  - _Requirements: 1.4, 1.5, 12.4, 12.5_

- [ ] 3.3 Implement executeCommand() method
  - Accept server and command parameters
  - Return CompletableFuture<CommandResult>
  - Create SSH session using createSession()
  - Open exec channel and set command
  - Read output from channel
  - Get exit status
  - Return CommandResult with success/failure
  - Handle connection timeout
  - Handle command timeout
  - _Requirements: 1.1, 1.5, 1.6, 10.2, 10.3_

- [ ] 3.4 Implement executeOnAllServers() method
  - Accept command parameter
  - Create map of server -> CompletableFuture<CommandResult>
  - Execute command on all configured servers in parallel
  - Return map of futures
  - _Requirements: 1.1, 1.2_

- [ ] 3.5 Add @PreDestroy method to shutdown ExecutorService
  - Shutdown executor gracefully
  - Wait for in-flight tasks to complete
  - _Requirements: 1.2_

- [ ]* 3.6 Write unit tests for SshClientService
  - Test createSession() with key authentication
  - Test createSession() with password authentication
  - Test executeCommand() success case
  - Test executeCommand() connection timeout
  - Test executeCommand() authentication failure
  - Test executeOnAllServers() parallel execution
  - _Requirements: 1.1, 1.2, 1.4, 1.5_

- [ ] 4. Backend: Output Parser Service
- [ ] 4.1 Create OutputParserService class
  - Add @Service annotation
  - Define regex patterns for parsing
  - _Requirements: 13.1, 13.2, 13.3_

- [ ] 4.2 Implement parseSelectionMode() method
  - Accept CLI output string
  - Use regex to extract selection mode prompt
  - Extract default value (1 or 2)
  - Return ParseResult<Integer>
  - Handle missing prompt
  - _Requirements: 2.2, 13.1_

- [ ] 4.3 Implement parseProcessorList() method
  - Accept CLI output string
  - Use regex to extract numbered items
  - Parse each line matching pattern "\\d+\\. .+"
  - Extract number and text
  - Extract processor name from path or group name
  - Return ParseResult<List<ProcessorItem>>
  - Handle empty list
  - _Requirements: 2.3, 2.4, 13.2_

- [ ] 4.4 Implement parseProcessorStatus() method
  - Accept CLI output, processor number, and name
  - Use regex to extract status line "[State] (command)"
  - Extract process state (Running/Stopped/Failed)
  - Extract full command line
  - Extract log path from command using "-log" flag
  - Extract config path from command (first argument)
  - Return ParseResult<ProcessorStatus>
  - Handle missing status pattern
  - _Requirements: 2.5, 2.6, 2.7, 13.3_

- [ ] 4.5 Implement extractProcessorName() helper method
  - Extract name from .cfg file path (last segment without extension)
  - Extract name from group name (as-is)
  - _Requirements: 2.3, 2.4_

- [ ] 4.6 Implement extractLogPath() helper method
  - Use regex to find "-log <path>" in command line
  - Return path or null if not found
  - _Requirements: 2.7_

- [ ] 4.7 Implement extractConfigPath() helper method
  - Split command line by whitespace
  - Return first argument (config file path)
  - _Requirements: 2.5_

- [ ] 4.8 Add case-insensitive state matching
  - Convert state string to uppercase before parsing
  - _Requirements: 13.7_

- [ ] 4.9 Add whitespace normalization
  - Trim lines before parsing
  - Handle various line endings (\\n, \\r\\n)
  - _Requirements: 13.6_

- [ ]* 4.10 Write property tests for OutputParserService
  - **Property 3**: Selection mode parsing
  - **Property 4**: Processor list parsing (default mode)
  - **Property 5**: Processor list parsing (groups mode)
  - **Property 6**: Status line parsing
  - **Property 13**: Output parsing error handling
  - **Property 14**: Case-insensitive state matching
  - _Requirements: 2.2, 2.3, 2.4, 2.5, 2.6, 2.7, 13.5, 13.7_

- [ ] 5. Backend: Audit Service and Repository
- [ ] 5.1 Create AuditLog entity
  - Add @Entity and @Table annotations
  - Add fields: id, timestamp, username, operation, server, processorNumbers, success, details
  - Add JPA annotations
  - _Requirements: 14.1, 14.2_

- [ ] 5.2 Create AuditLogRepository interface
  - Extend JpaRepository<AuditLog, Long>
  - Add query methods for filtering by date, user, server, operation
  - _Requirements: 14.3, 14.5_

- [ ] 5.3 Create AuditService class
  - Add @Service annotation
  - Inject AuditLogRepository
  - _Requirements: 14.1_

- [ ] 5.4 Implement logOperation() method
  - Accept username, operation, server, processorNumbers, success, details
  - Create AuditLog entity
  - Set timestamp to current instant
  - Save to repository
  - _Requirements: 14.1, 14.2_

- [ ] 5.5 Add database migration script
  - Create DPLOADMGR_AUDIT_LOG table
  - Add indexes on timestamp, username, server
  - _Requirements: 14.3_

- [ ]* 5.6 Write unit tests for AuditService
  - Test logOperation() creates audit log
  - Test audit log contains all required fields
  - _Requirements: 14.1, 14.2_

- [ ] 6. Backend: DpLoadMgr Service Layer
- [ ] 6.1 Create DpLoadMgrService class
  - Add @Service annotation
  - Inject SshClientService, OutputParserService, AuditService, DpLoadMgrProperties
  - _Requirements: 1.1, 2.1_

- [ ] 6.2 Implement getStatus() method
  - Accept servers list and selection mode
  - Build status command using properties.buildCommand("status")
  - Execute command on all servers using sshClientService.executeOnAllServers()
  - Wait for all futures to complete
  - Parse results using outputParserService
  - Aggregate results into MultiServerResponse
  - Log operation using auditService
  - Handle partial failures
  - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5, 2.6, 2.7, 14.1_

- [ ] 6.3 Implement listProcessors() method
  - Accept servers list and selection mode
  - Execute status command to get processor list
  - Parse processor list using outputParserService.parseProcessorList()
  - Return MultiServerResponse<ProcessorList>
  - _Requirements: 2.3, 2.4, 6.1, 6.2_

- [ ] 6.4 Implement executeOperation() method
  - Accept operation, servers, selection mode, processor numbers
  - Build command using properties.buildCommand(operation)
  - Execute command on all servers
  - Parse results
  - Aggregate responses
  - Log operation using auditService
  - Return MultiServerResponse<OperationResult>
  - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5, 4.1, 4.2, 4.3, 4.4, 4.5, 5.1, 5.2, 5.3, 5.4, 5.5, 14.1_

- [ ] 6.5 Add error handling for SSH failures
  - Catch SSH exceptions
  - Return error details in MultiServerResponse.errors map
  - Set allSuccessful to false
  - _Requirements: 1.3, 10.1, 10.2_

- [ ] 6.6 Add error handling for parsing failures
  - Catch parsing exceptions
  - Log raw output for debugging
  - Return parsing error in response
  - _Requirements: 10.4, 13.5_

- [ ]* 6.7 Write property tests for DpLoadMgrService
  - **Property 1**: Multi-server parallel execution
  - **Property 2**: Partial failure handling
  - **Property 7**: Command construction
  - **Property 8**: Batch operation execution
  - _Requirements: 1.1, 1.2, 1.3, 7.3, 7.4_

- [ ] 7. Backend: REST Controllers
- [ ] 7.1 Create DpLoadMgrController class
  - Add @RestController and @RequestMapping("/api/dploadmgr") annotations
  - Inject DpLoadMgrService
  - Inject AuthenticationFacade for getting current user
  - _Requirements: 9.1, 9.2_

- [ ] 7.2 Implement getStatus() endpoint
  - Add @GetMapping("/status")
  - Accept server and selectionMode query parameters
  - Call dpLoadMgrService.getStatus()
  - Return ResponseEntity<MultiServerResponse<ProcessorStatus>>
  - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5, 2.6, 2.7_

- [ ] 7.3 Implement listProcessors() endpoint
  - Add @GetMapping("/processors")
  - Accept server and selectionMode query parameters
  - Call dpLoadMgrService.listProcessors()
  - Return ResponseEntity<MultiServerResponse<ProcessorList>>
  - _Requirements: 6.1, 6.2, 6.3, 6.4_

- [ ] 7.4 Implement startProcessors() endpoint
  - Add @PostMapping("/start")
  - Accept ProcessorOperationRequest body
  - Call dpLoadMgrService.executeOperation("start", ...)
  - Return ResponseEntity<MultiServerResponse<OperationResult>>
  - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5_

- [ ] 7.5 Implement stopProcessors() endpoint
  - Add @PostMapping("/stop")
  - Accept ProcessorOperationRequest body
  - Call dpLoadMgrService.executeOperation("stop", ...)
  - Return ResponseEntity<MultiServerResponse<OperationResult>>
  - _Requirements: 4.1, 4.2, 4.3, 4.4, 4.5_

- [ ] 7.6 Implement killProcessors() endpoint
  - Add @PostMapping("/kill")
  - Accept ProcessorOperationRequest body
  - Call dpLoadMgrService.executeOperation("kill", ...)
  - Return ResponseEntity<MultiServerResponse<OperationResult>>
  - _Requirements: 5.1, 5.2, 5.3, 5.4, 5.5_

- [ ] 7.7 Add @PreAuthorize annotations for authorization
  - Add role-based access control
  - Require ROLE_DPLOADMGR_USER for read operations
  - Require ROLE_DPLOADMGR_ADMIN for write operations (start, stop, kill)
  - _Requirements: 9.2, 9.4, 9.5_

- [ ] 7.8 Add global exception handler
  - Create @ControllerAdvice class
  - Handle SSH exceptions
  - Handle parsing exceptions
  - Handle authentication exceptions
  - Return structured error responses
  - _Requirements: 10.1, 10.2, 10.3, 10.4_

- [ ]* 7.9 Write integration tests for REST endpoints
  - Test getStatus() endpoint
  - Test listProcessors() endpoint
  - Test startProcessors() endpoint with authorization
  - Test stopProcessors() endpoint with authorization
  - Test killProcessors() endpoint with authorization
  - Test authentication enforcement
  - Test authorization enforcement
  - _Requirements: 9.1, 9.3, 9.4_

- [ ] 8. Frontend: Models and Interfaces
- [ ] 8.1 Create ProcessState enum
  - Add values: RUNNING, STOPPED, FAILED, UNKNOWN
  - _Requirements: 2.6_

- [ ] 8.2 Create ProcessorStatus interface
  - Add fields matching backend ProcessorStatus
  - _Requirements: 2.5, 2.6, 2.7_

- [ ] 8.3 Create ProcessorItem interface
  - Add fields matching backend ProcessorItem
  - _Requirements: 2.3, 2.4_

- [ ] 8.4 Create ProcessorList interface
  - Add fields matching backend ProcessorList
  - _Requirements: 6.1, 6.2_

- [ ] 8.5 Create OperationResult interface
  - Add fields matching backend OperationResult
  - _Requirements: 3.4, 3.5_

- [ ] 8.6 Create MultiServerResponse interface
  - Add generic type parameter T
  - Add fields matching backend MultiServerResponse
  - _Requirements: 1.3_

- [ ] 8.7 Create ProcessorOperationRequest interface
  - Add fields matching backend ProcessorOperationRequest
  - _Requirements: 3.2, 4.2, 5.2_

- [ ] 9. Frontend: DpLoadMgr Service
- [ ] 9.1 Create DpLoadMgrService class
  - Add @Injectable({ providedIn: 'root' })
  - Inject HttpClient
  - Define baseUrl = '/api/dploadmgr'
  - _Requirements: 2.1_

- [ ] 9.2 Implement getStatus() method
  - Accept servers and selectionMode parameters
  - Build HTTP GET request to /status
  - Add query parameters
  - Return Observable<MultiServerResponse<ProcessorStatus>>
  - _Requirements: 2.1, 2.2_

- [ ] 9.3 Implement listProcessors() method
  - Accept servers and selectionMode parameters
  - Build HTTP GET request to /processors
  - Return Observable<MultiServerResponse<ProcessorList>>
  - _Requirements: 6.1, 6.2_

- [ ] 9.4 Implement startProcessors() method
  - Accept servers, selectionMode, processorNumbers parameters
  - Build HTTP POST request to /start
  - Return Observable<MultiServerResponse<OperationResult>>
  - _Requirements: 3.1, 3.2_

- [ ] 9.5 Implement stopProcessors() method
  - Accept servers, selectionMode, processorNumbers parameters
  - Build HTTP POST request to /stop
  - Return Observable<MultiServerResponse<OperationResult>>
  - _Requirements: 4.1, 4.2_

- [ ] 9.6 Implement killProcessors() method
  - Accept servers, selectionMode, processorNumbers parameters
  - Build HTTP POST request to /kill
  - Return Observable<MultiServerResponse<OperationResult>>
  - _Requirements: 5.1, 5.2_

- [ ] 9.7 Add error handling with HttpErrorResponse
  - Map HTTP errors to user-friendly messages
  - _Requirements: 10.7_

- [ ] 10. Frontend: Dashboard Component
- [ ] 10.1 Create DpLoadMgrDashboardComponent
  - Add @Component decorator
  - Set changeDetection to OnPush
  - Create component files (ts, html, scss)
  - _Requirements: 11.1_

- [ ] 10.2 Add signals for reactive state
  - servers: signal<string[]>
  - selectedServers: signal<string[]>
  - selectionMode: signal<number>
  - processors: signal<ProcessorStatus[]>
  - selectedProcessors: signal<Set<number>>
  - loading: signal<boolean>
  - error: signal<string | null>
  - lastUpdate: signal<Date | null>
  - _Requirements: 8.1, 8.5_

- [ ] 10.3 Add computed signals
  - runningCount: count of RUNNING processors
  - stoppedCount: count of STOPPED processors
  - failedCount: count of FAILED processors
  - _Requirements: 11.5_

- [ ] 10.4 Implement ngOnInit()
  - Call loadProcessors()
  - Start polling with interval(10000)
  - _Requirements: 8.1, 8.2_

- [ ] 10.5 Implement loadProcessors() method
  - Set loading to true
  - Call dpLoadMgrService.getStatus()
  - Update processors signal with results
  - Update lastUpdate signal
  - Set loading to false
  - Handle errors
  - _Requirements: 2.1, 8.1_

- [ ] 10.6 Implement startProcessors() method
  - Get selected processor numbers
  - Call dpLoadMgrService.startProcessors()
  - Reload processors on success
  - Handle errors
  - _Requirements: 3.1, 3.6_

- [ ] 10.7 Implement stopProcessors() method
  - Get selected processor numbers
  - Call dpLoadMgrService.stopProcessors()
  - Reload processors on success
  - Handle errors
  - _Requirements: 4.1, 4.6_

- [ ] 10.8 Implement killProcessors() method
  - Show confirmation dialog
  - Get selected processor numbers
  - Call dpLoadMgrService.killProcessors()
  - Reload processors on success
  - Handle errors
  - _Requirements: 5.1, 5.6, 5.7_

- [ ] 10.9 Implement toggleProcessor() method
  - Toggle processor selection in selectedProcessors set
  - _Requirements: 7.1, 7.2_

- [ ] 10.10 Implement selectAll() method
  - Add all processor numbers to selectedProcessors
  - _Requirements: 7.5_

- [ ] 10.11 Implement clearSelection() method
  - Clear selectedProcessors set
  - _Requirements: 7.5_

- [ ] 10.12 Add polling with automatic cleanup
  - Use interval() with takeUntilDestroyed()
  - Poll every 10 seconds
  - _Requirements: 8.1, 8.2_

- [ ] 11. Frontend: Dashboard Template
- [ ] 11.1 Create header section
  - Add server selector (multi-select dropdown)
  - Add selection mode toggle (Default/Groups)
  - Add refresh button
  - Add last update timestamp
  - _Requirements: 11.1, 11.2, 8.5_

- [ ] 11.2 Create status summary section
  - Display running count with green indicator
  - Display stopped count with gray indicator
  - Display failed count with red indicator
  - _Requirements: 11.5_

- [ ] 11.3 Create processor list section
  - Display table/grid of processors
  - Add checkbox column for selection
  - Add status indicator column (colored dot)
  - Add processor name column
  - Add processor path column
  - Add state column
  - _Requirements: 11.3, 7.1_

- [ ] 11.4 Create action buttons section
  - Add Start button (enabled when processors selected)
  - Add Stop button (enabled when processors selected)
  - Add Kill button (enabled when processors selected)
  - Add Select All button
  - Add Clear Selection button
  - _Requirements: 11.4, 7.5_

- [ ] 11.5 Add loading indicators
  - Show spinner during API calls
  - Disable buttons during loading
  - _Requirements: 15.5_

- [ ] 11.6 Add error display
  - Show error banner when errors occur
  - Display user-friendly error messages
  - Add dismiss button
  - _Requirements: 10.7_

- [ ] 11.7 Add confirmation dialog for kill operation
  - Show Material Dialog with warning
  - List selected processors
  - Require explicit confirmation
  - _Requirements: 5.6_

- [ ] 12. Frontend: Styling and Theme
- [ ] 12.1 Create dashboard.component.scss
  - Use Material Design components
  - Match dtp-resender-fullstack color scheme
  - Use same typography
  - Add responsive breakpoints
  - _Requirements: 11.6, 11.7, 11.8_

- [ ] 12.2 Add status indicator styles
  - Green dot for RUNNING
  - Gray dot for STOPPED
  - Red dot for FAILED
  - _Requirements: 2.8, 11.3_

- [ ] 12.3 Add hover effects
  - Highlight rows on hover
  - Show action buttons on row hover
  - _Requirements: 11.8_

- [ ] 12.4 Add responsive layout
  - Stack sections vertically on mobile
  - Adjust table columns for tablet
  - Full layout on desktop
  - _Requirements: 11.8_

- [ ] 12.5 Reuse existing theme variables
  - Import theme from dtp-resender-fullstack
  - Use consistent spacing, colors, shadows
  - _Requirements: 11.6, 11.7_

- [ ] 13. Frontend: Routing and Navigation
- [ ] 13.1 Add route to app-routing.module.ts
  - Add route: { path: 'dploadmgr', component: DpLoadMgrDashboardComponent, canActivate: [AuthGuard] }
  - _Requirements: 9.3_

- [ ] 13.2 Add navigation link to main menu
  - Add "DpLoadMgr" menu item
  - Add icon (settings or server icon)
  - _Requirements: 11.1_

- [ ] 13.3 Add AuthGuard to route
  - Reuse existing AuthGuard from dtp-resender-fullstack
  - Redirect to login if not authenticated
  - _Requirements: 9.1, 9.3_

- [ ] 14. Testing and Quality Assurance
- [ ]* 14.1 Write frontend component unit tests
  - Test DpLoadMgrDashboardComponent with mocked service
  - Test signal updates
  - Test computed values
  - Test user interactions
  - _Requirements: 11.1, 11.2, 11.3, 11.4, 11.5_

- [ ]* 14.2 Write frontend service unit tests
  - Test DpLoadMgrService HTTP calls
  - Test error handling
  - _Requirements: 9.1, 9.2, 9.3, 9.4, 9.5, 9.6_

- [ ]* 14.3 Write E2E tests
  - Test full user flow: login → view status → start processor
  - Test error scenarios
  - _Requirements: 2.1, 3.1, 9.1_

- [ ]* 14.4 Run accessibility audit
  - Test with screen reader
  - Test keyboard navigation
  - Verify WCAG AA compliance
  - _Requirements: 11.8_

- [ ] 15. Documentation
- [ ] 15.1 Create README.md
  - Document system overview
  - Document configuration requirements
  - Document SSH setup instructions
  - Document user roles and permissions
  - _Requirements: 12.1, 12.2, 12.3, 12.4, 12.5, 12.6_

- [ ] 15.2 Create API documentation
  - Document all REST endpoints
  - Document request/response formats
  - Document error codes
  - _Requirements: 10.1_

- [ ] 15.3 Create deployment guide
  - Document deployment steps
  - Document database migration
  - Document configuration checklist
  - _Requirements: 12.7_

- [ ] 16. Checkpoint - Integration Testing
- Ensure all backend tests pass, ensure frontend builds successfully, test SSH connectivity to servers, verify authentication and authorization, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional and can be skipped for faster MVP
- Each task references specific requirements for traceability
- Backend tasks should be completed before frontend tasks
- Property-based tests validate universal correctness properties
- Integration tests require access to test servers with SSH
- Reuse existing authentication, authorization, and UI patterns from dtp-resender-fullstack
- SSH credentials should be stored securely (never in source code)
