# Implementation Plan: ETL SSH Trigger

## Overview

This implementation adds an SSH-based trigger that executes CP cron commands on ETL servers after each staging request. The app will connect to configured ETL servers via SSH, extract uncommented crontab jobs, match the CP sender port from Elasticsearch sender config, and execute the corresponding command. The trigger is non-blocking, single-attempt, idempotent by requestId, and provides audit visibility restricted to administrators.

## Tasks

- [x] 1. Set up project structure and configuration
- [x] 1.1 Create EtlServerConfig class
  - Create EtlServerConfig.java with name, host, port, user, password, timeoutMs fields
  - _Requirements: 2.2_

- [x] 1.2 Create EtlServerConfigLoader
  - Create EtlServerConfigLoader.java to load etlservers.yml
  - Add @PostConstruct method to load YAML on startup
  - _Requirements: 2.1, 2.3_

- [x] 1.3 Create CrontabJob class
  - Create CrontabJob.java with schedule and command fields
  - _Requirements: 3.3_

- [x] 1.4 Create EtlAuditLog entity
  - Create EtlAuditLog.java JPA entity
  - Add fields: requestId, userId, site, location, etlServerName, senderPort, status, message, timestamp, remoteIp
  - _Requirements: 6.1, 6.2_

- [x] 1.5 Create IdempotencyRecord entity
  - Create IdempotencyRecord.java JPA entity
  - Add fields: requestId, status, message, createdAt
  - _Requirements: 5.1, 5.2, 5.3_

- [x] 1.6 Create repository interfaces
  - Create EtlAuditLogRepository.java
  - Create IdempotencyRepository.java
  - _Requirements: 6.1, 6.2, 5.1, 5.2, 5.3_

- [x] 1.7 Create etlservers.yml configuration file
  - Create etlservers.yml in src/main/resources
  - Add sample ETL server configurations (similar to dbconnections.yml format)
  - _Requirements: 2.1, 2.2, 2.3, 2.4_

- [x] 1.8 Update application.yml with ETL trigger configuration
  - Add etl.trigger.enabled property
  - Add sample environment variable mappings
  - _Requirements: 1.9_

- [ ]* 1.9 Write unit tests for configuration classes
  - Test property binding
  - Test default values
  - _Requirements: 2.1, 2.2, 2.3, 2.4_

- [x] 2. Implement CrontabExtractor
- [x] 2.1 Implement SSH connection to ETL server
  - Use JSch for SSH connection
  - _Requirements: 1.1, 3.1_

- [x] 2.2 Implement crontab -l execution
  - Execute 'crontab -l' command via SSH
  - _Requirements: 3.1_

- [x] 2.3 Implement comment filtering
  - Filter out lines starting with #
  - _Requirements: 3.2_

- [x] 2.4 Implement crontab parsing
  - Parse schedule and command from each uncommented line
  - _Requirements: 3.3_

- [ ]* 2.5 Write property test for crontab extraction
  - **Property 2: Crontab extraction**
  - **Validates: Requirements 1.2, 3.1, 3.2**

- [ ]* 2.6 Write property test for comment filtering
  - **Property 12: Comment filtering**
  - **Validates: Requirement 3.2**

- [ ]* 2.7 Write property test for crontab parsing
  - **Property 13: Crontab parsing**
  - **Validates: Requirement 3.3**

- [x] 3. Implement SenderPortExtractor
- [x] 3.1 Implement sender config name parsing
  - Extract port number from config name (e.g., "sender-8080" -> 8080)
  - _Requirements: 4.1, 4.2_

- [x] 3.2 Write property test for port extraction
  - **Property 15: Port extraction from config name**
  - **Validates: Requirements 4.1, 4.2**

- [x] 4. Implement CrontabJobMatcher
- [x] 4.1 Implement port matching logic
  - Find crontab job containing the sender port in the command
  - _Requirements: 4.3, 4.4_

- [ ]* 4.2 Write property test for port matching
  - **Property 4: Crontab job matching**
  - **Validates: Requirements 4.3, 4.4**

- [x] 5. Implement EtlSshTriggerService
- [x] 5.1 Implement execute method with kill switch check
  - Check if ETL servers are configured
  - Return notConfigured if no configs
  - _Requirements: 1.9, 2.4_

- [x] 5.2 Implement idempotency check
  - Check IdempotencyStore for existing requestId
  - Return cached result if found
  - _Requirements: 5.1, 5.2_

- [x] 5.3 Implement ETL server iteration
  - Iterate through all configured ETL servers
  - _Requirements: 1.1_

- [x] 5.4 Implement crontab extraction for each server
  - Call CrontabExtractor.extract() for each server
  - _Requirements: 1.2, 3.1, 3.2, 3.3, 3.4_

- [x] 5.5 Implement sender port extraction
  - Call SenderPortExtractor.extractPort()
  - _Requirements: 1.3, 4.1, 4.2_

- [x] 5.6 Implement job matching
  - Call CrontabJobMatcher.match()
  - _Requirements: 1.4, 4.3, 4.4_

- [x] 5.7 Implement command execution
  - Execute matched command via SSH
  - Single attempt only (no retries)
  - _Requirements: 1.5, 1.8_

- [x] 5.8 Implement overall status determination
  - Determine success/failure/notConfigured based on all server results
  - _Requirements: 1.6, 1.7_

- [x] 5.9 Implement audit logging
  - Call AuditService.log() with all required fields
  - _Requirements: 6.1, 6.2_

- [x] 5.10 Implement idempotency storage
  - Store result in IdempotencyStore
  - _Requirements: 5.1, 5.2, 5.3_

- [ ]* 5.11 Write property test for trigger execution
  - **Property 1: ETL server connection**
  - **Validates: Requirements 1.1**

- [ ]* 5.12 Write property test for error handling
  - **Property 6: Staging never fails due to trigger errors**
  - **Validates: Requirements 1.6, 1.7**

- [ ]* 5.13 Write property test for single attempt
  - **Property 7: Single attempt, no retries**
  - **Validates: Requirement 1.8**

- [ ]* 5.14 Write property test for kill switch
  - **Property 8: Kill switch disables all triggers**
  - **Validates: Requirement 1.9**

- [-] 6. Implement AuditService
- [x] 6.1 Implement log method
  - Create EtlAuditLog entity with all fields
  - Save to database
  - _Requirements: 6.1, 6.2_

- [x] 6.2 Implement findAllForAdmin method
  - Return all audit logs
  - _Requirements: 6.3, 6.4_

- [ ]* 6.3 Write property test for audit logging
  - **Property 19: Audit log completeness**
  - **Validates: Requirement 6.1**

- [ ]* 6.4 Write property test for audit persistence
  - **Property 20: Audit log persistence**
  - **Validates: Requirement 6.2**

- [ ]* 6.5 Write property test for admin-only access
  - **Property 21: Admin-only audit access**
  - **Validates: Requirements 6.3, 6.4**

- [x] 7. Implement REST API endpoints
- [x] 7.1 Create EtlTriggerController
  - Add POST endpoint for trigger execution
  - Return TriggerResult
  - _Requirements: 1.1, 1.6, 1.7_

- [x] 7.2 Create AuditController
  - Add GET endpoint for audit logs
  - Enforce ADMIN role
  - _Requirements: 6.3, 6.4_

- [x] 8. Integrate with staging service
- [x] 8.1 Modify staging service to call trigger
  - Call EtlSshTriggerService after staging completes
  - Include result in response
  - _Requirements: 1.1, 1.6, 1.7_

- [x] 8.2 Update staging response
  - Add requestId, status, message fields
  - _Requirements: 1.1, 1.6, 1.7_

- [x] 9. Implement frontend UX
- [x] 9.1 Add toast notification service
  - Create toast service for notifications
  - _Requirements: 7.1, 7.2, 7.3, 7.4_

- [x] 9.2 Implement staging response handler
  - Show appropriate toast based on trigger status
  - _Requirements: 7.1, 7.2, 7.3, 7.4_

- [x] 9.3 Disable button during in-flight trigger
  - Track in-flight requests
  - Disable staging button while in-flight
  - _Requirements: 7.5_

- [x] 10. Implement admin audit UI
- [x] 10.1 Create audit log table component
  - Display audit logs in table format
  - _Requirements: 6.3_

- [x] 10.2 Add admin-only route guard
  - Hide audit UI from non-admin users
  - _Requirements: 6.3, 6.4_

- [x] 10.3 Add filters for audit logs
  - Filter by site, location, status
  - _Requirements: 6.3_

- [x] 11. Security hardening
- [x] 11.1 Implement password sanitization in logs
  - Ensure password never appears in logs
  - _Requirements: 8.1_

- [x] 11.2 Implement password sanitization in responses
  - Ensure password never appears in error responses
  - _Requirements: 8.2_

- [x] 11.3 Write property test for password sanitization in logs

  - **Property 23: Password not in logs**
  - **Validates: Requirement 8.1**

- [x] 11.4 Write property test for password sanitization in responses
  - **Property 24: Password not in responses**
  - **Validates: Requirement 8.2**

- [ ]* 11.5 Write property test for audit access authorization
  - **Property 25: Audit access authorization**
  - **Validates: Requirement 8.3**

- [x] 12. Checkpoint - Ensure all tests pass
- Ensure all tests pass, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional and can be skipped for faster MVP
- Each task references specific requirements for traceability
- Checkpoints ensure incremental validation
- Property tests validate universal correctness properties
- Unit tests validate specific examples and edge cases