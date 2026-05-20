# Design Document: DpLoadMgr Web Application

## Overview

The DpLoadMgr Web Application is a full-stack web interface for managing data load processes across multiple Unix servers. The system wraps existing Perl-based CLI tools (`DpLoadMgr.pl`) and provides a modern Angular frontend with a Java Spring Boot backend. The architecture follows a command-wrapper pattern where the backend executes remote SSH commands, parses their output, and exposes structured REST APIs for the frontend.

**Key Design Principles:**
- **Wrapper Pattern**: Reuse existing Perl scripts rather than reimplementing business logic
- **Parallel Execution**: Execute commands on multiple servers concurrently
- **Robust Parsing**: Handle variations in CLI output format gracefully
- **Consistent UX**: Match exensioreload styling and patterns
- **Security First**: Reuse existing authentication and implement proper authorization

## Architecture

### High-Level Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                     Angular Frontend                         │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐     │
│  │  Dashboard   │  │  Auth Guard  │  │  HTTP Client │     │
│  │  Component   │  │              │  │              │     │
│  └──────────────┘  └──────────────┘  └──────────────┘     │
└────────────────────────────┬────────────────────────────────┘
                             │ HTTPS/REST
┌────────────────────────────┴────────────────────────────────┐
│                  Spring Boot Backend                         │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐     │
│  │  REST        │  │  Service     │  │  SSH Client  │     │
│  │  Controllers │  │  Layer       │  │  Service     │     │
│  └──────────────┘  └──────────────┘  └──────────────┘     │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐     │
│  │  Output      │  │  Audit       │  │  Config      │     │
│  │  Parser      │  │  Service     │  │  Properties  │     │
│  └──────────────┘  └──────────────┘  └──────────────┘     │
└────────────────────────────┬────────────────────────────────┘
                             │ SSH (Port 22)
┌────────────────────────────┴────────────────────────────────┐
│              Remote Unix Servers                             │
│  ┌──────────────────────┐  ┌──────────────────────┐        │
│  │  usaz15ls082         │  │  usaz15ls083         │        │
│  │  DpLoadMgr.pl        │  │  DpLoadMgr.pl        │        │
│  │  fcs_all_load.mgr    │  │  fcs_all_load.mgr    │        │
│  └──────────────────────┘  └──────────────────────┘        │
└─────────────────────────────────────────────────────────────┘
```

### Technology Stack

| Layer | Technology | Version | Justification |
|-------|-----------|---------|---------------|
| Frontend | Angular | 17+ | Matches exensioreload |
| Frontend UI | Angular Material | 17+ | Consistent with existing app |
| Backend | Spring Boot | 3.x | Matches exensioreload |
| Backend Language | Java | 17+ | Matches exensioreload |
| SSH Client | JSch | 0.2.x | Mature, well-tested SSH library |
| Database | Oracle | 19c+ | Reuse existing infrastructure |
| Auth | Spring Security + JWT | 6.x | Reuse existing auth mechanism |

## Components and Interfaces

### Backend Components

#### 1. DpLoadMgrController (REST Controller)

**Responsibility**: Expose REST endpoints for frontend operations

**Endpoints**:
```java
@RestController
@RequestMapping("/api/dploadmgr")
public class DpLoadMgrController {
    
    @GetMapping("/status")
    public ResponseEntity<MultiServerResponse<ProcessorStatus>> getStatus(
        @RequestParam(required = false) String server,
        @RequestParam(defaultValue = "1") int selectionMode
    );
    
    @PostMapping("/start")
    public ResponseEntity<MultiServerResponse<OperationResult>> startProcessors(
        @RequestBody ProcessorOperationRequest request
    );
    
    @PostMapping("/stop")
    public ResponseEntity<MultiServerResponse<OperationResult>> stopProcessors(
        @RequestBody ProcessorOperationRequest request
    );
    
    @PostMapping("/kill")
    public ResponseEntity<MultiServerResponse<OperationResult>> killProcessors(
        @RequestBody ProcessorOperationRequest request
    );
    
    @GetMapping("/processors")
    public ResponseEntity<MultiServerResponse<ProcessorList>> listProcessors(
        @RequestParam(required = false) String server,
        @RequestParam(defaultValue = "1") int selectionMode
    );
}
```

**DTOs**:
```java
public record ProcessorOperationRequest(
    List<String> servers,
    int selectionMode,
    List<Integer> processorNumbers
) {}

public record MultiServerResponse<T>(
    Map<String, T> results,
    Map<String, String> errors,
    boolean allSuccessful
) {}

public record ProcessorStatus(
    int number,
    String name,
    String path,
    ProcessState state,
    String commandLine,
    String logPath
) {}

public enum ProcessState {
    RUNNING, STOPPED, FAILED, UNKNOWN
}

public record OperationResult(
    boolean success,
    String message,
    List<ProcessorStatus> affectedProcessors
) {}

public record ProcessorList(
    int selectionMode,
    List<ProcessorItem> items
) {}

public record ProcessorItem(
    int number,
    String name,
    String displayText
) {}
```

#### 2. DpLoadMgrService (Service Layer)

**Responsibility**: Orchestrate command execution across multiple servers

```java
@Service
public class DpLoadMgrService {
    
    private final SshClientService sshClientService;
    private final OutputParserService outputParserService;
    private final AuditService auditService;
    private final DpLoadMgrProperties properties;
    
    public MultiServerResponse<ProcessorStatus> getStatus(
        List<String> servers, 
        int selectionMode
    ) {
        // Execute status command on all servers in parallel
        // Parse results
        // Aggregate responses
        // Log audit trail
    }
    
    public MultiServerResponse<OperationResult> executeOperation(
        String operation,
        List<String> servers,
        int selectionMode,
        List<Integer> processorNumbers
    ) {
        // Execute operation command on all servers in parallel
        // Parse results
        // Aggregate responses
        // Log audit trail
    }
    
    public MultiServerResponse<ProcessorList> listProcessors(
        List<String> servers,
        int selectionMode
    ) {
        // Execute status command to get processor list
        // Parse processor list
        // Return structured list
    }
}
```

#### 3. SshClientService (SSH Execution)

**Responsibility**: Execute commands on remote servers via SSH

```java
@Service
public class SshClientService {
    
    private final DpLoadMgrProperties properties;
    private final ExecutorService executorService;
    
    public CompletableFuture<CommandResult> executeCommand(
        String server,
        String command
    ) {
        return CompletableFuture.supplyAsync(() -> {
            try (Session session = createSession(server)) {
                session.connect(properties.getSshConnectionTimeout());
                ChannelExec channel = (ChannelExec) session.openChannel("exec");
                channel.setCommand(command);
                
                String output = readOutput(channel);
                int exitCode = channel.getExitStatus();
                
                return new CommandResult(true, output, exitCode, null);
            } catch (JSchException e) {
                return new CommandResult(false, null, -1, e.getMessage());
            }
        }, executorService);
    }
    
    public Map<String, CompletableFuture<CommandResult>> executeOnAllServers(
        String command
    ) {
        Map<String, CompletableFuture<CommandResult>> futures = new HashMap<>();
        for (String server : properties.getServers()) {
            futures.put(server, executeCommand(server, command));
        }
        return futures;
    }
    
    private Session createSession(String server) throws JSchException {
        JSch jsch = new JSch();
        if (properties.getSshPrivateKeyPath() != null) {
            jsch.addIdentity(properties.getSshPrivateKeyPath());
        }
        Session session = jsch.getSession(
            properties.getSshUsername(),
            server,
            properties.getSshPort()
        );
        if (properties.getSshPassword() != null) {
            session.setPassword(properties.getSshPassword());
        }
        session.setConfig("StrictHostKeyChecking", "no");
        return session;
    }
}

public record CommandResult(
    boolean success,
    String output,
    int exitCode,
    String errorMessage
) {}
```

#### 4. OutputParserService (Output Parsing)

**Responsibility**: Parse CLI output into structured data

```java
@Service
public class OutputParserService {
    
    private static final Pattern SELECTION_MODE_PATTERN = 
        Pattern.compile("Enter the selection mode.*\\[([12])\\]");
    
    private static final Pattern NUMBERED_ITEM_PATTERN = 
        Pattern.compile("^\\s*(\\d+)\\. (.+)$");
    
    private static final Pattern STATUS_PATTERN = 
        Pattern.compile("\\[(Running|Stopped|Failed)\\]\\s+\\((.+)\\)");
    
    public ParseResult<Integer> parseSelectionMode(String output) {
        Matcher matcher = SELECTION_MODE_PATTERN.matcher(output);
        if (matcher.find()) {
            return ParseResult.success(Integer.parseInt(matcher.group(1)));
        }
        return ParseResult.failure("Selection mode prompt not found");
    }
    
    public ParseResult<List<ProcessorItem>> parseProcessorList(String output) {
        List<ProcessorItem> items = new ArrayList<>();
        String[] lines = output.split("\\n");
        
        for (String line : lines) {
            Matcher matcher = NUMBERED_ITEM_PATTERN.matcher(line);
            if (matcher.matches()) {
                int number = Integer.parseInt(matcher.group(1));
                String text = matcher.group(2).trim();
                String name = extractProcessorName(text);
                items.add(new ProcessorItem(number, name, text));
            }
        }
        
        if (items.isEmpty()) {
            return ParseResult.failure("No processors found in output");
        }
        return ParseResult.success(items);
    }
    
    public ParseResult<ProcessorStatus> parseProcessorStatus(
        String output,
        int processorNumber,
        String processorName
    ) {
        Matcher matcher = STATUS_PATTERN.matcher(output);
        if (matcher.find()) {
            ProcessState state = ProcessState.valueOf(matcher.group(1).toUpperCase());
            String commandLine = matcher.group(2);
            String logPath = extractLogPath(commandLine);
            
            return ParseResult.success(new ProcessorStatus(
                processorNumber,
                processorName,
                extractConfigPath(commandLine),
                state,
                commandLine,
                logPath
            ));
        }
        return ParseResult.failure("Status pattern not found in output");
    }
    
    private String extractProcessorName(String text) {
        // Extract name from path or group name
        if (text.contains("/")) {
            String[] parts = text.split("/");
            String filename = parts[parts.length - 1];
            return filename.replace(".cfg", "");
        }
        return text;
    }
    
    private String extractLogPath(String commandLine) {
        Pattern logPattern = Pattern.compile("-log\\s+(\\S+)");
        Matcher matcher = logPattern.matcher(commandLine);
        return matcher.find() ? matcher.group(1) : null;
    }
    
    private String extractConfigPath(String commandLine) {
        // Extract first argument (config file path)
        String[] parts = commandLine.split("\\s+");
        return parts.length > 1 ? parts[1] : null;
    }
}

public record ParseResult<T>(
    boolean success,
    T value,
    String errorMessage
) {
    public static <T> ParseResult<T> success(T value) {
        return new ParseResult<>(true, value, null);
    }
    
    public static <T> ParseResult<T> failure(String errorMessage) {
        return new ParseResult<>(false, null, errorMessage);
    }
}
```

#### 5. AuditService (Audit Logging)

**Responsibility**: Log all operations for compliance and troubleshooting

```java
@Service
public class AuditService {
    
    private final AuditLogRepository auditLogRepository;
    
    public void logOperation(
        String username,
        String operation,
        String server,
        List<Integer> processorNumbers,
        boolean success,
        String details
    ) {
        AuditLog log = new AuditLog();
        log.setTimestamp(Instant.now());
        log.setUsername(username);
        log.setOperation(operation);
        log.setServer(server);
        log.setProcessorNumbers(processorNumbers.toString());
        log.setSuccess(success);
        log.setDetails(details);
        
        auditLogRepository.save(log);
    }
}

@Entity
@Table(name = "DPLOADMGR_AUDIT_LOG")
public class AuditLog {
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "audit_seq")
    private Long id;
    
    @Column(nullable = false)
    private Instant timestamp;
    
    @Column(nullable = false)
    private String username;
    
    @Column(nullable = false)
    private String operation;
    
    @Column(nullable = false)
    private String server;
    
    private String processorNumbers;
    
    @Column(nullable = false)
    private Boolean success;
    
    @Column(length = 4000)
    private String details;
    
    // Getters and setters
}
```

#### 6. DpLoadMgrProperties (Configuration)

**Responsibility**: Externalize configuration

```java
@ConfigurationProperties(prefix = "dploadmgr")
@Validated
public class DpLoadMgrProperties {
    
    @NotEmpty
    private List<String> servers = List.of("usaz15ls082", "usaz15ls083");
    
    @NotBlank
    private String sshUsername;
    
    private String sshPassword;
    
    private String sshPrivateKeyPath;
    
    @Min(1)
    @Max(65535)
    private int sshPort = 22;
    
    @Min(1000)
    private int sshConnectionTimeout = 30000; // 30 seconds
    
    @Min(1000)
    private int sshCommandTimeout = 60000; // 60 seconds
    
    @NotBlank
    private String dploadPath = "$DPLOAD";
    
    @NotBlank
    private String mgrFile = "fcs_all_load.mgr";
    
    @Min(1)
    private int statusPollInterval = 10; // seconds
    
    @Min(1)
    private int auditRetentionDays = 90;
    
    // Getters and setters
    
    public String buildCommand(String operation) {
        return String.format(
            "%s/DpLoadMgr.pl -f %s/%s -%s",
            dploadPath,
            dploadPath,
            mgrFile,
            operation
        );
    }
}
```

### Frontend Components

#### 1. DashboardComponent (Main UI)

**Responsibility**: Display processor status and provide operation controls

```typescript
@Component({
  selector: 'app-dploadmgr-dashboard',
  templateUrl: './dashboard.component.html',
  styleUrls: ['./dashboard.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class DpLoadMgrDashboardComponent implements OnInit, OnDestroy {
  
  // Signals for reactive state
  servers = signal<string[]>(['usaz15ls082', 'usaz15ls083']);
  selectedServers = signal<string[]>(['usaz15ls082', 'usaz15ls083']);
  selectionMode = signal<number>(1);
  processors = signal<ProcessorStatus[]>([]);
  selectedProcessors = signal<Set<number>>(new Set());
  loading = signal<boolean>(false);
  error = signal<string | null>(null);
  lastUpdate = signal<Date | null>(null);
  
  // Computed values
  runningCount = computed(() => 
    this.processors().filter(p => p.state === 'RUNNING').length
  );
  
  stoppedCount = computed(() => 
    this.processors().filter(p => p.state === 'STOPPED').length
  );
  
  failedCount = computed(() => 
    this.processors().filter(p => p.state === 'FAILED').length
  );
  
  constructor(
    private dploadMgrService: DpLoadMgrService,
    private authService: AuthService
  ) {}
  
  ngOnInit(): void {
    this.loadProcessors();
    this.startPolling();
  }
  
  loadProcessors(): void {
    this.loading.set(true);
    this.error.set(null);
    
    this.dploadMgrService.getStatus(
      this.selectedServers(),
      this.selectionMode()
    ).subscribe({
      next: (response) => {
        this.processors.set(this.aggregateProcessors(response));
        this.lastUpdate.set(new Date());
        this.loading.set(false);
      },
      error: (err) => {
        this.error.set(err.message);
        this.loading.set(false);
      }
    });
  }
  
  startProcessors(): void {
    const selected = Array.from(this.selectedProcessors());
    if (selected.length === 0) return;
    
    this.dploadMgrService.startProcessors(
      this.selectedServers(),
      this.selectionMode(),
      selected
    ).subscribe({
      next: () => this.loadProcessors(),
      error: (err) => this.error.set(err.message)
    });
  }
  
  stopProcessors(): void {
    // Similar to startProcessors
  }
  
  killProcessors(): void {
    // Show confirmation dialog first
    // Then execute kill operation
  }
  
  toggleProcessor(processorNumber: number): void {
    const selected = new Set(this.selectedProcessors());
    if (selected.has(processorNumber)) {
      selected.delete(processorNumber);
    } else {
      selected.add(processorNumber);
    }
    this.selectedProcessors.set(selected);
  }
  
  private startPolling(): void {
    interval(10000).pipe(
      takeUntilDestroyed()
    ).subscribe(() => this.loadProcessors());
  }
}
```

#### 2. DpLoadMgrService (Frontend Service)

**Responsibility**: HTTP communication with backend

```typescript
@Injectable({ providedIn: 'root' })
export class DpLoadMgrService {
  
  private readonly baseUrl = '/api/dploadmgr';
  
  constructor(private http: HttpClient) {}
  
  getStatus(
    servers: string[],
    selectionMode: number
  ): Observable<MultiServerResponse<ProcessorStatus>> {
    const params = new HttpParams()
      .set('server', servers.join(','))
      .set('selectionMode', selectionMode.toString());
    
    return this.http.get<MultiServerResponse<ProcessorStatus>>(
      `${this.baseUrl}/status`,
      { params }
    );
  }
  
  startProcessors(
    servers: string[],
    selectionMode: number,
    processorNumbers: number[]
  ): Observable<MultiServerResponse<OperationResult>> {
    return this.http.post<MultiServerResponse<OperationResult>>(
      `${this.baseUrl}/start`,
      { servers, selectionMode, processorNumbers }
    );
  }
  
  // Similar methods for stop, kill, listProcessors
}
```

## Data Models

### Backend Models

```java
// Already defined in Components section:
// - ProcessorStatus
// - ProcessorItem
// - ProcessorList
// - OperationResult
// - MultiServerResponse
// - CommandResult
// - ParseResult
// - AuditLog
```

### Frontend Models

```typescript
export interface ProcessorStatus {
  number: number;
  name: string;
  path: string;
  state: ProcessState;
  commandLine: string;
  logPath: string | null;
}

export enum ProcessState {
  RUNNING = 'RUNNING',
  STOPPED = 'STOPPED',
  FAILED = 'FAILED',
  UNKNOWN = 'UNKNOWN'
}

export interface MultiServerResponse<T> {
  results: Record<string, T>;
  errors: Record<string, string>;
  allSuccessful: boolean;
}

export interface OperationResult {
  success: boolean;
  message: string;
  affectedProcessors: ProcessorStatus[];
}
```

## Correctness Properties

*A property is a characteristic or behavior that should hold true across all valid executions of a system—essentially, a formal statement about what the system should do. Properties serve as the bridge between human-readable specifications and machine-verifiable correctness guarantees.*

### Property 1: Multi-Server Parallel Execution
*For any* list of servers and command, when executing the command, all servers should receive the command concurrently and the total execution time should be approximately equal to the slowest single server execution time (not the sum of all execution times).

**Validates: Requirements 1.1, 1.2**

### Property 2: Partial Failure Handling
*For any* multi-server command execution where at least one server fails, the response should contain both successful results from reachable servers and error details for failed servers, with the `allSuccessful` flag set to false.

**Validates: Requirements 1.3**

### Property 3: Selection Mode Parsing
*For any* valid CLI output containing a selection mode prompt, the parser should correctly extract the default selection mode value (1 or 2).

**Validates: Requirements 2.2**

### Property 4: Processor List Parsing (Default Mode)
*For any* valid CLI output in selection mode 1, the parser should extract all numbered .cfg file paths and return a list where each item has a unique number and non-empty path.

**Validates: Requirements 2.3**

### Property 5: Processor List Parsing (Groups Mode)
*For any* valid CLI output in selection mode 2, the parser should extract all numbered group names and return a list where each item has a unique number and non-empty group name.

**Validates: Requirements 2.4**

### Property 6: Status Line Parsing
*For any* valid status output line containing a process state and command, the parser should correctly extract the state (Running/Stopped/Failed) and the full command line.

**Validates: Requirements 2.5, 2.6, 2.7**

### Property 7: Command Construction
*For any* operation type (start, stop, kill, status), the constructed command string should match the pattern `$DPLOAD/DpLoadMgr.pl -f $DPLOAD/fcs_all_load.mgr -<operation>`.

**Validates: Requirements 2.1, 3.1**

### Property 8: Batch Operation Execution
*For any* list of selected processor numbers, when executing a batch operation, the command should be executed for each processor and the results should contain an entry for each processor number.

**Validates: Requirements 7.3, 7.4**

### Property 9: Authentication Enforcement
*For any* API endpoint request without valid authentication, the backend should return HTTP 401 Unauthorized.

**Validates: Requirements 9.1, 9.3**

### Property 10: Authorization Enforcement
*For any* API endpoint request with valid authentication but insufficient permissions, the backend should return HTTP 403 Forbidden.

**Validates: Requirements 9.4**

### Property 11: Audit Log Creation
*For any* command execution (regardless of success or failure), an audit log entry should be created containing timestamp, username, operation, server, and result.

**Validates: Requirements 14.1, 14.2**

### Property 12: Configuration Validation
*For any* application startup, if required configuration properties (servers, sshUsername, dploadPath) are missing or invalid, the application should fail to start with a clear error message.

**Validates: Requirements 12.1, 12.7**

### Property 13: Output Parsing Error Handling
*For any* CLI output that doesn't match expected patterns, the parser should return a failure result containing the error message and preserve the raw output for debugging.

**Validates: Requirements 13.5**

### Property 14: Case-Insensitive State Matching
*For any* status output containing process state keywords in any case (running, RUNNING, Running), the parser should correctly identify the state.

**Validates: Requirements 13.7**

### Property 15: Timeout Configuration
*For any* SSH connection or command execution, the configured timeout values should be applied and enforced, preventing indefinite hangs.

**Validates: Requirements 1.5, 1.6**

## Error Handling

### Error Categories

1. **SSH Connection Errors**
   - Connection timeout
   - Authentication failure
   - Host unreachable
   - Network errors

2. **Command Execution Errors**
   - Command timeout
   - Non-zero exit code
   - Permission denied
   - Command not found

3. **Parsing Errors**
   - Unexpected output format
   - Missing expected patterns
   - Malformed data

4. **Business Logic Errors**
   - Invalid processor selection
   - Operation not permitted
   - Concurrent modification

### Error Response Format

```json
{
  "timestamp": "2026-05-07T10:30:00Z",
  "status": 500,
  "error": "Internal Server Error",
  "message": "Failed to execute command on server usaz15ls082",
  "details": {
    "server": "usaz15ls082",
    "operation": "start",
    "cause": "SSH connection timeout after 30000ms"
  },
  "path": "/api/dploadmgr/start"
}
```

### Error Handling Strategy

1. **Graceful Degradation**: Return partial results when some servers fail
2. **Detailed Logging**: Log full stack traces and raw output for debugging
3. **User-Friendly Messages**: Translate technical errors into actionable messages
4. **Retry Logic**: Implement exponential backoff for transient failures
5. **Circuit Breaker**: Prevent cascading failures from unreachable servers

## Testing Strategy

### Unit Tests

Unit tests verify specific examples and edge cases:

- **OutputParserService**: Test parsing with various CLI output formats
- **DpLoadMgrProperties**: Test configuration validation
- **Command construction**: Test command string building
- **Error handling**: Test error response formatting

### Property-Based Tests

Property-based tests verify universal properties across all inputs:

- **Property 1-15**: Implement each correctness property as a property-based test
- Use random input generation for CLI output variations
- Run minimum 100 iterations per property test
- Tag each test with: `Feature: dploadmgr-web-app, Property N: <property_text>`

### Integration Tests

Integration tests verify end-to-end flows:

- SSH connection and command execution (requires test servers)
- Multi-server parallel execution
- Authentication and authorization flows
- Audit log creation and querying

### Frontend Tests

- Component unit tests with mocked services
- E2E tests for critical user flows
- Accessibility testing (WCAG AA compliance)

## Security Considerations

1. **SSH Key Management**: Store private keys securely, never in source code
2. **Credential Encryption**: Encrypt SSH passwords in configuration
3. **Input Validation**: Validate all user inputs to prevent command injection
4. **Authorization**: Implement role-based access control for operations
5. **Audit Trail**: Log all operations with user identity
6. **Session Management**: Reuse existing JWT-based session management
7. **HTTPS Only**: Enforce HTTPS for all API communication

## Performance Considerations

1. **Parallel Execution**: Use CompletableFuture for concurrent server operations
2. **Connection Pooling**: Reuse SSH connections where possible
3. **Caching**: Cache processor lists for 30 seconds
4. **Async Operations**: Use async/await patterns in frontend
5. **Lazy Loading**: Load processor details on demand
6. **Pagination**: Paginate audit logs and large processor lists

## Deployment Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                     Load Balancer (HTTPS)                    │
└────────────────────────────┬────────────────────────────────┘
                             │
┌────────────────────────────┴────────────────────────────────┐
│              Application Server (Spring Boot)                │
│  - Embedded Tomcat                                           │
│  - Angular static files served from /static                  │
│  - REST API at /api/*                                        │
└────────────────────────────┬────────────────────────────────┘
                             │
┌────────────────────────────┴────────────────────────────────┐
│                     Oracle Database                          │
│  - Audit logs                                                │
│  - User authentication (shared with exensioreload)            │
└──────────────────────────────────────────────────────────────┘
```

## Migration and Rollout Plan

1. **Phase 1**: Deploy backend API with read-only operations (status)
2. **Phase 2**: Add write operations (start, stop) with limited user access
3. **Phase 3**: Add kill operation with additional authorization
4. **Phase 4**: Enable audit logging and monitoring
5. **Phase 5**: Full rollout to all users

## Monitoring and Observability

1. **Metrics**: Track API response times, SSH connection success rate, command execution times
2. **Logging**: Structured logging with correlation IDs
3. **Alerts**: Alert on SSH connection failures, command timeouts, high error rates
4. **Dashboards**: Grafana dashboards for system health and usage patterns
