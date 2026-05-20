# Design Document: ETL SSH Trigger

## Overview

This feature adds an SSH-based trigger that executes CP cron commands on ETL servers after each staging request. The app will connect to configured ETL servers via SSH, extract uncommented crontab jobs, match the CP sender port from Elasticsearch sender config, and execute the corresponding command. The trigger is non-blocking, single-attempt, idempotent by requestId, and provides audit visibility restricted to administrators. The design ensures staging operations never fail due to trigger errors while maintaining comprehensive audit trails.

## Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                        Frontend Layer                               │
│  ┌──────────────────┐  ┌──────────────────┐  ┌──────────────────┐   │
│  │  Staging UI      │  │  Audit UI (Admin)│  │  Toast Service   │   │
│  └────────┬─────────┘  └──────────────────┘  └──────────────────┘   │
│           │                                                           │
└───────────┼───────────────────────────────────────────────────────────┘
            │ HTTP/REST
            │
┌─────────────────────────────────────────────────────────────────────┐
│                        Backend Layer                                │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │              Staging Service (hook)                          │   │
│  │  - Calls EtlSshTriggerService after staging completes       │   │
│  │  - Returns {requestId, status, message} in response         │   │
│  └───────────────┬──────────────────────────────────────────────┘   │
│                  │                                                   │
│  ┌───────────────▼──────────────────────────────────────────────┐   │
│  │           EtlSshTriggerService                               │   │
│  │  - Connects to ETL servers via SSH                          │   │
│  │  - Extracts crontab jobs from each ETL server               │   │
│  │  - Matches sender port to crontab job                       │   │
│  │  - Executes matching command                                │   │
│  │  - Checks idempotency by requestId                          │   │
│  │  - Logs audit entries                                       │   │
│  └───────────────┬──────────────────────────────────────────────┘   │
│                  │                                                   │
│  ┌───────────────▼──────────────────────────────────────────────┐   │
│  │           EtlServerConfigLoader                              │   │
│  │  - Loads etlservers.yml on startup                          │   │
│  │  - Provides list of ETL server configurations               │   │
│  └───────────────┬──────────────────────────────────────────────┘   │
│                  │                                                   │
│  ┌───────────────▼──────────────────────────────────────────────┐   │
│  │           CrontabExtractor                                   │   │
│  │  - Executes 'crontab -l' via SSH                            │   │
│  │  - Filters out commented lines                              │   │
│  │  - Parses schedule and command from each line               │   │
│  └───────────────┬──────────────────────────────────────────────┘   │
│                  │                                                   │
│  ┌───────────────▼──────────────────────────────────────────────┐   │
│  │           SenderPortExtractor                                │   │
│  │  - Queries Elasticsearch for sender config                  │   │
│  │  - Extracts port number from config name                    │   │
│  └───────────────┬──────────────────────────────────────────────┘   │
│                  │                                                   │
│  ┌───────────────▼──────────────────────────────────────────────┐   │
│  │           CrontabJobMatcher                                  │   │
│  │  - Matches sender port to crontab job                       │   │
│  │  - Returns matching command                                 │   │
│  └───────────────┬──────────────────────────────────────────────┘   │
│                  │                                                   │
│  ┌───────────────▼──────────────────────────────────────────────┐   │
│  │           Audit Service                                      │   │
│  │  - Persists audit logs to database                          │   │
│  │  - Exposes admin-only API endpoint                          │   │
│  └──────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────┘
            │
            │ SSH (JSch)
            ▼
┌─────────────────────────────────────────────────────────────────────┐
│                        ETL Server                                   │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │  SSH User (executes crontab -l)                             │   │
│  └──────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────┘
```

## Components and Interfaces

### EtlServerConfig

Configuration for a single ETL server.

```java
public class EtlServerConfig {
    private String name;
    private String host;
    private Integer port = 22;
    private String user;
    private String password;
    private Integer timeoutMs = 30000;
}
```

### EtlServerConfigLoader

Loads ETL server configurations from etlservers.yml.

```java
@Service
public class EtlServerConfigLoader {
    private List<EtlServerConfig> configs;
    
    @PostConstruct
    public void load() {
        // Load from etlservers.yml
    }
    
    public List<EtlServerConfig> getConfigs() {
        return configs;
    }
}
```

### CrontabJob

Represents a parsed crontab job.

```java
public class CrontabJob {
    private String schedule;
    private String command;
}
```

### CrontabExtractor

Extracts crontab jobs from an ETL server via SSH.

```java
@Service
public class CrontabExtractor {
    public List<CrontabJob> extract(EtlServerConfig config) throws Exception {
        // SSH connection
        // Execute 'crontab -l'
        // Filter out commented lines
        // Parse schedule and command
    }
}
```

### SenderPortExtractor

Extracts the sender port from Elasticsearch sender configuration.

```java
@Service
public class SenderPortExtractor {
    public Integer extractPort(String senderConfigName) {
        // Parse port from config name (e.g., "sender-8080" -> 8080)
    }
}
```

### CrontabJobMatcher

Matches a sender port to a crontab job.

```java
@Service
public class CrontabJobMatcher {
    public CrontabJob match(List<CrontabJob> jobs, Integer senderPort) {
        // Find job containing the sender port in the command
    }
}
```

### EtlSshTriggerService

Main service that orchestrates the ETL trigger process.

```java
@Service
public class EtlSshTriggerService {
    private final EtlServerConfigLoader configLoader;
    private final CrontabExtractor crontabExtractor;
    private final SenderPortExtractor senderPortExtractor;
    private final CrontabJobMatcher jobMatcher;
    private final AuditService auditService;
    private final IdempotencyStore idempotencyStore;
    
    public TriggerResult execute(String requestId, String userId, 
                                  String site, String location) {
        // Check if enabled
        if (!configLoader.hasConfigs()) {
            return TriggerResult.notConfigured();
        }
        
        // Check idempotency
        Optional<TriggerResult> cached = idempotencyStore.get(requestId);
        if (cached.isPresent()) {
            return cached.get();
        }
        
        // Get sender port from Elasticsearch
        Integer senderPort = senderPortExtractor.extractPort(senderConfigName);
        
        // Process each ETL server
        List<TriggerResult> results = new ArrayList<>();
        for (EtlServerConfig config : configLoader.getConfigs()) {
            try {
                // Extract crontab jobs
                List<CrontabJob> jobs = crontabExtractor.extract(config);
                
                // Match sender port to crontab job
                CrontabJob matchedJob = jobMatcher.match(jobs, senderPort);
                if (matchedJob == null) {
                    results.add(TriggerResult.notConfigured());
                    continue;
                }
                
                // Execute the command
                executeSshCommand(config, matchedJob.getCommand());
                results.add(TriggerResult.success());
            } catch (Exception e) {
                results.add(TriggerResult.failure(e.getMessage()));
            }
        }
        
        // Determine overall status
        TriggerResult result = determineOverallStatus(results);
        
        // Log audit
        auditService.log(requestId, userId, site, location, 
                        result.getStatus(), result.getMessage(), remoteIp);
        
        // Store idempotency
        idempotencyStore.put(requestId, result);
        
        return result;
    }
    
    private void executeSshCommand(EtlServerConfig config, String command) throws Exception {
        // SSH connection and command execution
    }
    
    private TriggerResult determineOverallStatus(List<TriggerResult> results) {
        // If any failed, return failure
        // If all not configured, return not configured
        // Otherwise return success
    }
}
```

### TriggerResult

Result of a trigger attempt.

```java
public class TriggerResult {
    private final String status;  // "success", "failure", "not_configured"
    private final String message;
    
    public static TriggerResult success() { ... }
    public static TriggerResult failure(String message) { ... }
    public static TriggerResult notConfigured() { ... }
}
```

### IdempotencyStore

Persists requestId records to prevent duplicate executions.

```java
@Service
public class IdempotencyStore {
    private final IdempotencyRepository repository;
    
    public Optional<TriggerResult> get(String requestId) {
        return repository.findById(requestId)
            .map(record -> TriggerResult.fromRecord(record));
    }
    
    public void put(String requestId, TriggerResult result) {
        IdempotencyRecord record = new IdempotencyRecord();
        record.setRequestId(requestId);
        record.setStatus(result.getStatus());
        record.setMessage(result.getMessage());
        record.setCreatedAt(Instant.now());
        repository.save(record);
    }
}
```

### IdempotencyRecord

JPA entity for idempotency storage.

```java
@Entity
@Table(name = "etl_trigger_idempotency")
public class IdempotencyRecord {
    @Id
    private String requestId;
    
    private String status;
    private String message;
    private Instant createdAt;
}
```

### AuditService

Handles audit logging.

```java
@Service
public class AuditService {
    private final EtlAuditLogRepository repository;
    
    public void log(String requestId, String userId, String site, 
                   String location, String status, String message, 
                   String remoteIp) {
        EtlAuditLog log = new EtlAuditLog();
        log.setRequestId(requestId);
        log.setUserId(userId);
        log.setSite(site);
        log.setLocation(location);
        log.setStatus(status);
        log.setMessage(message);
        log.setRemoteIp(remoteIp);
        repository.save(log);
    }
    
    public List<EtlAuditLog> findAllForAdmin() {
        return repository.findAll();
    }
}
```

### EtlAuditLog

JPA entity for ETL trigger audit logs.

```java
@Entity
@Table(name = "etl_trigger_audit_log")
public class EtlAuditLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    private String requestId;
    private String userId;
    private String site;
    private String location;
    private String etlServerName;
    private Integer senderPort;
    private String status;
    private String message;
    private Instant timestamp;
    private String remoteIp;
}
```

## Data Models

### EtlAuditLog

| Field | Type | Description |
|-------|------|-------------|
| id | Long | Primary key |
| requestId | String | Unique request identifier |
| userId | String | User who triggered the action |
| site | String | Site name |
| location | String | Location name (optional) |
| etlServerName | String | Name of the ETL server |
| senderPort | Integer | CP sender port number |
| status | String | "success", "failure", "not_configured" |
| message | String | Error message or status message |
| timestamp | Instant | When the log was created |
| remoteIp | String | Client IP address |

### IdempotencyRecord

| Field | Type | Description |
|-------|------|-------------|
| requestId | String | Primary key (same as staging requestId) |
| status | String | Trigger result status |
| message | String | Trigger result message |
| createdAt | Instant | When the record was created |

### EtlServerConfig

| Field | Type | Description |
|-------|------|-------------|
| name | String | Unique identifier for the ETL server |
| host | String | Hostname or IP address |
| port | Integer | SSH port (default: 22) |
| user | String | SSH username |
| password | String | SSH password |
| timeoutMs | Integer | Connection timeout in milliseconds |

### CrontabJob

| Field | Type | Description |
|-------|------|-------------|
| schedule | String | Crontab schedule (e.g., "* * * * *") |
| command | String | Command to execute |

## Correctness Properties

A property is a characteristic or behavior that should hold true across all valid executions of a system-essentially, a formal statement about what the system should do. Properties serve as the bridge between human-readable specifications and machine-verifiable correctness guarantees.

### Property 1: ETL server connection

*For any* staging request that completes successfully, if ETL servers are configured, the ETL Trigger SHALL attempt to connect to each configured ETL server via SSH.

**Validates: Requirements 1.1**

### Property 2: Crontab extraction

*For any* ETL server connection, the ETL Trigger SHALL extract all uncommented crontab jobs by executing `crontab -l`.

**Validates: Requirements 1.2, 3.1, 3.2**

### Property 3: Sender port extraction

*For any* staging request, the ETL Trigger SHALL extract the CP sender port from the Elasticsearch sender configuration name.

**Validates: Requirements 1.3, 4.1, 4.2**

### Property 4: Crontab job matching

*For any* extracted crontab job list and sender port, the ETL Trigger SHALL match the sender port to a crontab job containing that port number.

**Validates: Requirements 1.4, 4.3**

### Property 5: Command execution

*For any* matched crontab job, the ETL Trigger SHALL execute the command from that job.

**Validates: Requirements 1.5**

### Property 6: Staging never fails due to trigger errors

*For any* staging request, if the SSH connection fails or the SSH command execution fails, the staging request SHALL still succeed and the error SHALL be logged for audit.

**Validates: Requirements 1.6, 1.7**

### Property 7: Single attempt, no retries

*For any* ETL trigger attempt per ETL server, the SSH command SHALL be executed exactly once, even if the execution fails.

**Validates: Requirement 1.8**

### Property 8: Kill switch disables all triggers

*For any* staging request, if the ETL Trigger is disabled via configuration, no SSH trigger SHALL be executed.

**Validates: Requirement 1.9**

### Property 9: YAML configuration loading

*For any* application startup, if the etlservers.yml file is present, the Configuration Loader SHALL load all ETL server configurations from the file.

**Validates: Requirements 2.1, 2.3**

### Property 10: Configuration structure

*For any* ETL server configuration, the configuration SHALL include all required fields: name, host, port, user, password, and timeout.

**Validates: Requirement 2.2**

### Property 11: No configuration handling

*For any* application startup, if no etlservers.yml file is present, the ETL Trigger SHALL skip all execution and return "not configured" status.

**Validates: Requirement 2.4**

### Property 12: Comment filtering

*For any* crontab output, the ETL Trigger SHALL filter out commented lines (lines starting with #).

**Validates: Requirement 3.2**

### Property 13: Crontab parsing

*For any* uncommented crontab line, the ETL Trigger SHALL parse the schedule and command correctly.

**Validates: Requirement 3.3**

### Property 14: Crontab extraction error handling

*For any* crontab extraction failure, the ETL Trigger SHALL log the error and continue to the next ETL server.

**Validates: Requirement 3.4**

### Property 15: Port extraction from config name

*For any* sender configuration name, the ETL Trigger SHALL extract the port number correctly (e.g., "sender-8080" -> 8080).

**Validates: Requirements 4.1, 4.2**

### Property 16: Port matching in crontab

*For any* sender port and crontab job list, the ETL Trigger SHALL match the port to a job containing that port number.

**Validates: Requirements 4.3, 4.4**

### Property 17: Idempotency check

*For any* staging request with a requestId, if the requestId has been seen before, the Trigger Service SHALL skip execution and return the cached result.

**Validates: Requirements 5.1, 5.2**

### Property 18: Idempotency persistence

*For any* requestId stored in the Idempotency Store, if the application restarts, the requestId SHALL still be detected as a duplicate.

**Validates: Requirement 5.3**

### Property 19: Audit log completeness

*For any* ETL trigger attempt, the Audit Logger SHALL record all required fields: requestId, userId, site, location, etlServerName, senderPort, status, message, timestamp, and remote IP.

**Validates: Requirements 6.1**

### Property 20: Audit log persistence

*For any* audit log entry created, the entry SHALL be persisted to the database.

**Validates: Requirement 6.2**

### Property 21: Admin-only audit access

*For any* audit log query, if the user has ADMIN role, the Audit API SHALL return all audit log entries; otherwise, it SHALL return an empty list.

**Validates: Requirements 6.3, 6.4**

### Property 22: Toast message accuracy

*For any* staging response, the Frontend SHALL display the correct toast message based on the trigger status (success, failure, not configured).

**Validates: Requirements 7.1-7.4**

### Property 23: Password not in logs

*For any* log message generated by the application, the SSH password SHALL NOT appear in the log output.

**Validates: Requirement 8.1**

### Property 24: Password not in responses

*For any* error response returned to the user, the SSH password SHALL NOT appear in the response.

**Validates: Requirement 8.2**

### Property 25: Audit access authorization

*For any* audit log access attempt, access SHALL be granted only to users with ADMIN role.

**Validates: Requirement 8.3**

## Error Handling

### SSH Connection Errors

- Log the error with full context
- Return failure status in TriggerResult
- Do not fail the staging request
- Audit log entry with status "failure"
- Continue to next ETL server

### Crontab Extraction Errors

- Log the error with full context
- Return failure status in TriggerResult
- Do not fail the staging request
- Audit log entry with status "failure"
- Continue to next ETL server

### Command Execution Errors

- Log the error with full context
- Return failure status in TriggerResult
- Do not fail the staging request
- Audit log entry with status "failure"

### Configuration Errors

- Log a warning on startup if YAML file is invalid
- Continue with empty config list if YAML is missing
- Return "not configured" status for all requests

### Idempotency Store Errors

- Log the error
- Continue with trigger execution (don't block)
- Don't store idempotency record if store fails

## Testing Strategy

### Unit Tests

- Test ETL server config loading
- Test crontab extraction with mock SSH
- Test crontab comment filtering
- Test crontab parsing
- Test sender port extraction from config name
- Test crontab job matching
- Test SSH command execution with mock JSch
- Test audit logging with mock repository
- Test idempotency store with mock repository
- Test error handling for SSH failures
- Test error handling for configuration errors

### Property-Based Tests

- **Property 1**: ETL server connection
  - Generate random staging requests with valid ETL server configs
  - Verify SSH connections are attempted for each server
  - Minimum 100 iterations

- **Property 2**: Crontab extraction
  - Generate random crontab output with comments
  - Verify uncommented lines are extracted correctly
  - Minimum 100 iterations

- **Property 3**: Sender port extraction
  - Generate random sender config names with different port formats
  - Verify port is extracted correctly
  - Minimum 100 iterations

- **Property 4**: Crontab job matching
  - Generate random crontab jobs with different port numbers
  - Verify correct job is matched to sender port
  - Minimum 100 iterations

- **Property 5**: Command execution
  - Generate random matched crontab jobs
  - Verify command is executed correctly
  - Minimum 100 iterations

- **Property 6**: Staging never fails due to trigger errors
  - Generate random staging requests
  - Simulate SSH connection failures
  - Verify staging succeeds and error is logged
  - Minimum 100 iterations

- **Property 7**: Single attempt, no retries
  - Generate random staging requests
  - Verify SSH command is executed exactly once per ETL server
  - Minimum 100 iterations

- **Property 8**: Kill switch disables all triggers
  - Set ETL trigger disabled
  - Generate random staging requests
  - Verify no SSH attempts are made
  - Minimum 100 iterations

- **Property 9**: YAML configuration loading
  - Create YAML file with ETL server configs
  - Verify configs are loaded on startup
  - Minimum 100 iterations

- **Property 12**: Comment filtering
  - Generate crontab output with comments
  - Verify only uncommented lines are extracted
  - Minimum 100 iterations

- **Property 13**: Crontab parsing
  - Generate various crontab line formats
  - Verify schedule and command are extracted correctly
  - Minimum 100 iterations

- **Property 15**: Port extraction from config name
  - Generate sender config names with different formats
  - Verify port is extracted correctly
  - Minimum 100 iterations

- **Property 17**: Idempotency check
  - Generate random staging requests with duplicate requestIds
  - Verify duplicate detection works correctly
  - Minimum 100 iterations

- **Property 19**: Audit log completeness
  - Generate random trigger attempts
  - Verify all required fields are recorded
  - Minimum 100 iterations

- **Property 20**: Audit log persistence
  - Generate random trigger attempts
  - Query database directly
  - Verify records are persisted
  - Minimum 100 iterations

- **Property 21**: Admin-only audit access
  - Generate random admin and non-admin users
  - Query audit API
  - Verify access control works correctly
  - Minimum 100 iterations

### Test Configuration

- Property-based tests: 100 iterations minimum
- Tag format: **Feature: etl-ssh-trigger, Property N: {property_text}**
- Use JUnit 5 for unit tests
- Use fast-check for property-based tests (TypeScript/JavaScript) or jqwik (Java)