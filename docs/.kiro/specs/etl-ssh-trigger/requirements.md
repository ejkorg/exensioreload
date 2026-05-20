# Requirements Document: ETL SSH Trigger

## Introduction

This feature adds an SSH-based trigger that executes CP cron commands on ETL servers after each staging request. The app will connect to configured ETL servers via SSH, extract uncommented crontab jobs, match the CP sender port from Elasticsearch sender config, and execute the corresponding command. The trigger is non-blocking, single-attempt, idempotent by requestId, and provides audit visibility restricted to administrators. The design ensures staging operations never fail due to trigger errors while maintaining comprehensive audit trails.

## Glossary

- **ETL Trigger**: The SSH-based mechanism that executes remote CP cron commands on ETL servers after staging
- **ETL Server**: A remote server with SSH access configured in etlservers.yml containing host, credentials, and SSH connection details
- **Staging Request**: A user action to stage data files to the staging environment
- **Audit Log**: A persistent record of all ETL trigger attempts with details about the request, user, and outcome
- **Idempotency**: The property that ensures duplicate requests with the same requestId do not cause duplicate SSH executions
- **CP Sender Port**: The port number extracted from Elasticsearch sender configuration that identifies which CP cron job to execute

## Requirements

### Requirement 1: Trigger Execution

**User Story:** As a system operator, I want the ETL CP job to run automatically after each staging request, so that data processing begins immediately without manual intervention.

#### Acceptance Criteria

1. WHEN a staging request completes successfully, THE ETL Trigger SHALL attempt to connect to each configured ETL server via SSH
2. WHEN connected to an ETL server, THE ETL Trigger SHALL extract all uncommented crontab jobs
3. THE ETL Trigger SHALL extract the CP sender port from the Elasticsearch sender configuration name
4. THE ETL Trigger SHALL match the extracted sender port to a crontab job on the ETL server
5. WHEN a matching crontab job is found, THE ETL Trigger SHALL execute the command from that job
6. IF the SSH connection fails, THE ETL Trigger SHALL NOT fail the staging request and SHALL log the error for audit
7. IF the SSH command execution fails, THE ETL Trigger SHALL NOT fail the staging request and SHALL log the error for audit
8. THE ETL Trigger SHALL execute only once per staging request per ETL server (single attempt, no retries)
9. WHERE the ETL Trigger is disabled via configuration, THE ETL Trigger SHALL skip execution and return a "disabled" status

### Requirement 2: ETL Server Configuration

**User Story:** As a system administrator, I want to configure ETL servers in a YAML file similar to dbconnections.yml, so that I can easily manage multiple ETL servers for different environments.

#### Acceptance Criteria

1. WHEN the application starts, THE Configuration Loader SHALL read ETL server configurations from the etlservers.yml file
2. EACH ETL server configuration SHALL include: name, host, port, user, password, and timeout
3. THE ETL server password SHALL be stored in the configuration file or read from environment variables
4. WHEN no etlservers.yml file is present, THE ETL Trigger SHALL skip all execution and return "not configured" status

### Requirement 3: Crontab Extraction

**User Story:** As a system operator, I want the ETL Trigger to extract uncommented crontab jobs from ETL servers, so that it can identify available CP commands.

#### Acceptance Criteria

1. WHEN connected to an ETL server, THE ETL Trigger SHALL execute `crontab -l` to list crontab entries
2. THE ETL Trigger SHALL filter out commented lines (lines starting with #) from the crontab output
3. THE ETL Trigger SHALL parse each uncommented crontab line to extract the schedule and command
4. IF crontab extraction fails, THE ETL Trigger SHALL log the error and continue to the next ETL server

### Requirement 4: Sender Port Matching

**User Story:** As a system operator, I want the ETL Trigger to match the CP sender port from Elasticsearch to a crontab job, so that the correct CP command is executed for each sender.

#### Acceptance Criteria

1. WHEN extracting the CP sender port, THE ETL Trigger SHALL query Elasticsearch for the sender configuration
2. THE ETL Trigger SHALL extract the port number from the sender configuration name (e.g., "sender-8080" -> port 8080)
3. WHEN matching crontab jobs, THE ETL Trigger SHALL look for a job containing the sender port number
4. IF no matching crontab job is found for a sender port, THE ETL Trigger SHALL log a warning and continue

### Requirement 5: Idempotency

**User Story:** As a system operator, I want duplicate staging requests with the same requestId to not trigger multiple SSH executions, so that I can safely retry failed requests without causing duplicate work.

#### Acceptance Criteria

1. WHEN a staging request with a requestId is processed, THE Trigger Service SHALL check if the requestId has been seen before
2. IF the requestId has been seen before, THE Trigger Service SHALL skip execution and return the previous result
3. THE Idempotency Store SHALL persist requestId records to prevent duplicates across application restarts

### Requirement 6: Audit Logging

**User Story:** As an administrator, I want to view all ETL trigger attempts with full context, so that I can troubleshoot issues and verify job execution.

#### Acceptance Criteria

1. WHEN an ETL trigger attempt is made, THE Audit Logger SHALL record: requestId, userId, site, location, etlServerName, senderPort, status, message, timestamp, and remote IP
2. THE Audit Log SHALL be persisted to the database
3. WHERE the user has ADMIN role, THE Audit API SHALL return all audit log entries
4. WHERE the user does not have ADMIN role, THE Audit API SHALL return an empty list

### Requirement 7: Frontend UX

**User Story:** As a user, I want to see immediate feedback about the ETL trigger status after staging, so that I know whether the job was triggered or if there were issues.

#### Acceptance Criteria

1. WHEN a staging request completes, THE Frontend SHALL display a toast notification with the trigger status
2. IF the trigger executed successfully on all ETL servers, THE toast SHALL show "SSH trigger sent. Audit logged."
3. IF the trigger failed on any ETL server, THE toast SHALL show "SSH trigger failed. See audit for details."
4. IF no ETL servers are configured, THE toast SHALL show "SSH trigger not configured."
5. WHILE the trigger is in-flight, THE Frontend SHALL disable the staging button to prevent duplicate requests

### Requirement 8: Security

**User Story:** As a security administrator, I want to ensure SSH credentials are never exposed in logs or configuration files, so that the system maintains security compliance.

#### Acceptance Criteria

1. WHEN the application logs any error, THE SSH password SHALL NOT appear in the log output
2. WHEN the application returns error messages to the user, THE SSH password SHALL NOT appear in the response
3. ALL audit log access SHALL be restricted to users with ADMIN role

## Acceptance Criteria Testing Prework

1.1 WHEN a staging request completes successfully, THE ETL Trigger SHALL attempt to connect to each configured ETL server via SSH
Thoughts: This is testing the core trigger functionality. We can generate staging requests with valid ETL server configs, mock SSH connections, and verify connections are attempted. This is a universal property.
Testable: yes - property

1.2 WHEN connected to an ETL server, THE ETL Trigger SHALL extract all uncommented crontab jobs
Thoughts: This is testing crontab extraction. We can simulate SSH connections that return crontab output and verify uncommented lines are extracted. This is a universal property.
Testable: yes - property

1.3 THE ETL Trigger SHALL extract the CP sender port from the Elasticsearch sender configuration name
Thoughts: This is testing sender port extraction. We can generate sender config names with different port formats and verify the port is extracted correctly. This is a universal property.
Testable: yes - property

1.4 THE ETL Trigger SHALL match the extracted sender port to a crontab job on the ETL server
Thoughts: This is testing port matching. We can generate crontab jobs with different port numbers and verify the correct job is matched. This is a universal property.
Testable: yes - property

1.5 WHEN a matching crontab job is found, THE ETL Trigger SHALL execute the command from that job
Thoughts: This is testing command execution. We can generate matching crontab jobs and verify the command is executed. This is a universal property.
Testable: yes - property

1.6 IF the SSH connection fails, THE ETL Trigger SHALL NOT fail the staging request and SHALL log the error for audit
Thoughts: This is testing error handling. We can simulate SSH connection failures and verify staging succeeds while the error is logged. This is a universal property about error handling.
Testable: yes - property

1.7 IF the SSH command execution fails, THE ETL Trigger SHALL NOT fail the staging request and SHALL log the error for audit
Thoughts: This is testing command execution error handling. We can simulate command failures and verify staging succeeds while the error is logged. This is a universal property about error handling.
Testable: yes - property

1.8 THE ETL Trigger SHALL execute only once per staging request per ETL server (single attempt, no retries)
Thoughts: This is testing the single-attempt behavior. We can generate staging requests and verify the trigger service only attempts execution once per ETL server. This is a universal property about execution behavior.
Testable: yes - property

1.9 WHERE the ETL Trigger is disabled via configuration, THE ETL Trigger SHALL skip execution and return a "disabled" status
Thoughts: This is testing the kill switch. We can set the enabled flag to false and verify no SSH attempts are made. This is a universal property about configuration.
Testable: yes - property

2.1 WHEN the application starts, THE Configuration Loader SHALL read ETL server configurations from the etlservers.yml file
Thoughts: This is testing YAML loading. We can create a YAML file with ETL server configurations and verify they are loaded on startup. This is a universal property about configuration loading.
Testable: yes - property

2.2 EACH ETL server configuration SHALL include: name, host, port, user, password, and timeout
Thoughts: This is testing configuration structure. We can generate ETL server configs with all required fields and verify they are parsed correctly. This is a universal property about configuration structure.
Testable: yes - property

2.3 THE ETL server password SHALL be stored in the configuration file or read from environment variables
Thoughts: This is testing password storage. We can test both file-based and environment-based password loading. This is a universal property about configuration.
Testable: yes - property

2.4 WHEN no etlservers.yml file is present, THE ETL Trigger SHALL skip all execution and return "not configured" status
Thoughts: This is testing the no-configuration case. We can verify that without the YAML file, no SSH attempts are made. This is a universal property about configuration.
Testable: yes - property

3.1 WHEN connected to an ETL server, THE ETL Trigger SHALL execute `crontab -l` to list crontab entries
Thoughts: This is testing crontab listing. We can simulate SSH connections and verify the crontab -l command is executed. This is a universal property about SSH commands.
Testable: yes - property

3.2 THE ETL Trigger SHALL filter out commented lines (lines starting with #) from the crontab output
Thoughts: This is testing comment filtering. We can generate crontab output with comments and verify only uncommented lines are extracted. This is a universal property about parsing.
Testable: yes - property

3.3 THE ETL Trigger SHALL parse each uncommented crontab line to extract the schedule and command
Thoughts: This is testing crontab parsing. We can generate various crontab line formats and verify the schedule and command are extracted correctly. This is a universal property about parsing.
Testable: yes - property

3.4 IF crontab extraction fails, THE ETL Trigger SHALL log the error and continue to the next ETL server
Thoughts: This is testing error handling for crontab extraction. We can simulate crontab extraction failures and verify the error is logged and processing continues. This is a universal property about error handling.
Testable: yes - property

4.1 WHEN extracting the CP sender port, THE ETL Trigger SHALL query Elasticsearch for the sender configuration
Thoughts: This is testing Elasticsearch query. We can simulate Elasticsearch responses with sender configs and verify the port is extracted. This is a universal property about data extraction.
Testable: yes - property

4.2 THE ETL Trigger SHALL extract the port number from the sender configuration name (e.g., "sender-8080" -> port 8080)
Thoughts: This is testing port extraction from name. We can generate sender config names with different formats and verify the port is extracted correctly. This is a universal property about parsing.
Testable: yes - property

4.3 WHEN matching crontab jobs, THE ETL Trigger SHALL look for a job containing the sender port number
Thoughts: This is testing port matching in crontab. We can generate crontab jobs with different port numbers and verify the correct job is matched. This is a universal property about matching.
Testable: yes - property

4.4 IF no matching crontab job is found for a sender port, THE ETL Trigger SHALL log a warning and continue
Thoughts: This is testing the no-match case. We can generate sender ports with no matching crontab jobs and verify a warning is logged. This is a universal property about error handling.
Testable: yes - property

5.1 WHEN a staging request with a requestId is processed, THE Trigger Service SHALL check if the requestId has been seen before
Thoughts: This is testing idempotency check. We can generate staging requests with duplicate requestIds and verify the service detects duplicates. This is a universal property about idempotency.
Testable: yes - property

5.2 IF the requestId has been seen before, THE Trigger Service SHALL skip execution and return the previous result
Thoughts: This is testing duplicate handling. We can generate duplicate requests and verify the service returns cached results. This is a universal property about idempotency.
Testable: yes - property

5.3 THE Idempotency Store SHALL persist requestId records to prevent duplicates across application restarts
Thoughts: This is testing persistence. We can store a requestId, restart the application, and verify the duplicate is still detected. This is a universal property about persistence.
Testable: yes - property

6.1 WHEN an ETL trigger attempt is made, THE Audit Logger SHALL record: requestId, userId, site, location, etlServerName, senderPort, status, message, timestamp, and remote IP
Thoughts: This is testing audit logging. We can generate trigger attempts and verify all required fields are recorded. This is a universal property about audit logging.
Testable: yes - property

6.2 THE Audit Log SHALL be persisted to the database
Thoughts: This is testing database persistence. We can generate trigger attempts, query the database directly, and verify records are stored. This is a universal property about persistence.
Testable: yes - property

6.3 WHERE the user has ADMIN role, THE Audit API SHALL return all audit log entries
Thoughts: This is testing admin access. We can generate audit entries and verify admin users can retrieve them. This is a universal property about authorization.
Testable: yes - property

6.4 WHERE the user does not have ADMIN role, THE Audit API SHALL return an empty list
Thoughts: This is testing non-admin access. We can generate audit entries and verify non-admin users cannot retrieve them. This is a universal property about authorization.
Testable: yes - property

7.1 WHEN a staging request completes, THE Frontend SHALL display a toast notification with the trigger status
Thoughts: This is testing toast display. We can generate staging responses and verify the correct toast is shown. This is a universal property about UI feedback.
Testable: yes - property

7.2 IF the trigger executed successfully on all ETL servers, THE toast SHALL show "SSH trigger sent. Audit logged."
Thoughts: This is testing success message. We can simulate successful trigger execution and verify the correct message is shown. This is a universal property about UI feedback.
Testable: yes - property

7.3 IF the trigger failed on any ETL server, THE toast SHALL show "SSH trigger failed. See audit for details."
Thoughts: This is testing failure message. We can simulate trigger failures and verify the correct message is shown. This is a universal property about UI feedback.
Testable: yes - property

7.4 IF no ETL servers are configured, THE toast SHALL show "SSH trigger not configured."
Thoughts: This is testing not-configured message. We can simulate no ETL servers and verify the correct message is shown. This is a universal property about UI feedback.
Testable: yes - property

7.5 WHILE the trigger is in-flight, THE Frontend SHALL disable the staging button to prevent duplicate requests
Thoughts: This is testing UI state management. We can simulate in-flight triggers and verify the button is disabled. This is a universal property about UI state.
Testable: yes - property

8.1 WHEN the application logs any error, THE SSH password SHALL NOT appear in the log output
Thoughts: This is testing log sanitization. We can generate error logs and verify passwords are redacted. This is a universal property about security.
Testable: yes - property

8.2 WHEN the application returns error messages to the user, THE SSH password SHALL NOT appear in the response
Thoughts: This is testing response sanitization. We can generate error responses and verify passwords are not included. This is a universal property about security.
Testable: yes - property

8.3 ALL audit log access SHALL be restricted to users with ADMIN role
Thoughts: This is testing authorization. We can generate audit access attempts from admin and non-admin users and verify access control. This is a universal property about authorization.
Testable: yes - property