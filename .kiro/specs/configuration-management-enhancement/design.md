# Design Document

## Overview

This design document describes the architecture and implementation approach for migrating ETL configuration management from static YAML files to a database-backed system with administrative UI and comprehensive audit logging. The system maintains backward compatibility with YAML files as a fallback mechanism while providing modern CRUD interfaces for configuration management.

The design follows a layered architecture pattern:

- **Persistence Layer**: JPA entities and repositories for configuration and audit data
- **Service Layer**: Business logic for configuration loading, validation, and fallback
- **API Layer**: RESTful endpoints for configuration and audit management
- **UI Layer**: Angular components for admin panels and enhanced stepper UI

## Architecture

### High-Level Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                         Frontend (Angular)                       │
│  ┌──────────────┐  ┌──────────────┐  ┌─────────────────────┐  │
│  │ Stepper UI   │  │ Admin Panel  │  │ Audit Log Viewer    │  │
│  │ (Enhanced)   │  │ (CRUD)       │  │                     │  │
│  └──────┬───────┘  └──────┬───────┘  └──────────┬──────────┘  │
│         │                  │                     │              │
└─────────┼──────────────────┼─────────────────────┼──────────────┘
          │                  │                     │
          ▼                  ▼                     ▼
┌─────────────────────────────────────────────────────────────────┐
│                      REST API Layer                              │
│  ┌────────────────┐  ┌────────────────┐  ┌──────────────────┐ │
│  │ Config API     │  │ Admin API      │  │ Audit API        │ │
│  │ (Read/Query)   │  │ (CRUD)         │  │ (Read/Query)     │ │
│  └────────┬───────┘  └────────┬───────┘  └────────┬─────────┘ │
│           │                    │                    │           │
└───────────┼────────────────────┼────────────────────┼───────────┘
            │                    │                    │
            ▼                    ▼                    ▼
┌─────────────────────────────────────────────────────────────────┐
│                       Service Layer                              │
│  ┌────────────────────────────────────────────────────────────┐ │
│  │ ConfigurationService                                        │ │
│  │ - Load from DB with YAML fallback                          │ │
│  │ - Cache management & invalidation                          │ │
│  │ - Validation & referential integrity                       │ │
│  └────────────────────────────────────────────────────────────┘ │
│  ┌────────────────────────────────────────────────────────────┐ │
│  │ AuditService                                                │ │
│  │ - Record all actions (login, config changes, ETL triggers) │ │
│  │ - Query & filter audit logs                                │ │
│  └────────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────┘
            │                    │                    │
            ▼                    ▼                    ▼
┌─────────────────────────────────────────────────────────────────┐
│                    Persistence Layer                             │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────────────┐ │
│  │ JPA Entities │  │ Repositories │  │ YAML Loader          │ │
│  │ - Pipeline   │  │              │  │ (Fallback)           │ │
│  │ - EtlServer  │  │              │  │                      │ │
│  │ - DbConn     │  │              │  │                      │ │
│  │ - AuditLog   │  │              │  │                      │ │
│  └──────┬───────┘  └──────┬───────┘  └──────────┬───────────┘ │
│         │                  │                     │              │
└─────────┼──────────────────┼─────────────────────┼──────────────┘
          │                  │                     │
          ▼                  ▼                     ▼
┌─────────────────────────────────────────────────────────────────┐
│              Database (PostgreSQL / Oracle)                      │
│  - config_pipeline                                               │
│  - config_etl_server                                            │
│  - config_db_connection                                         │
│  - audit_log (enhanced)                                         │
└─────────────────────────────────────────────────────────────────┘
          │
          │ Fallback
          ▼
┌─────────────────────────────────────────────────────────────────┐
│                    YAML Configuration Files                      │
│  - etljobs.yml                                                   │
│  - etlservers.yml                                               │
│  - dbconnections.yml                                            │
└─────────────────────────────────────────────────────────────────┘
```

### Configuration Loading Strategy

The system implements a **database-first with YAML fallback** strategy:

1. **Startup**: Attempt to load all configurations from database
2. **Missing Entity**: Fall back to YAML for specific missing entries
3. **Database Unavailable**: Fall back to YAML completely with warning log
4. **Caching**: Cache loaded configurations in memory (Caffeine cache)
5. **Refresh**: Invalidate cache on CRUD operations

### Security Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                    Security Layers                           │
├─────────────────────────────────────────────────────────────┤
│ 1. Authentication (Spring Security + JWT)                   │
│    - User login creates JWT token                           │
│    - Token validated on each request                        │
├─────────────────────────────────────────────────────────────┤
│ 2. Authorization (Role-Based Access Control)                │
│    - USER: Read access to Step 1 UI                         │
│    - ADMIN: Full access to Admin panel + audit logs         │
│    - SUPER_ADMIN: All permissions + user management         │
├─────────────────────────────────────────────────────────────┤
│ 3. Encryption (At-Rest)                                     │
│    - Passwords encrypted using Spring Security crypto       │
│    - AES-256 encryption for sensitive credentials           │
│    - Encryption keys managed via Spring Boot properties     │
├─────────────────────────────────────────────────────────────┤
│ 4. Audit Logging (All Actions)                             │
│    - Every config change logged with user + timestamp      │
│    - Passwords excluded from audit log details             │
│    - Immutable audit records (no updates/deletes)          │
└─────────────────────────────────────────────────────────────┘
```

## Components and Interfaces

### Backend Components

#### 1. JPA Entities

**ConfigPipeline Entity**

```java
@Entity
@Table(name = "config_pipeline")
public class ConfigPipeline {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 100)
    private String pipelineKey;

    @Column(nullable = false, length = 50)
    private String site;

    @Column(nullable = false, length = 50)
    private String server;

    @Column(nullable = false)
    private Integer socketPort;

    @Column(length = 255)
    private String configName;

    @Column(nullable = false)
    private Integer senderId;

    @Column(nullable = false)
    private Integer rerunPeriodMinutes;

    @Column(nullable = false, length = 10)
    private String environment; // PROD or QA

    @Column(nullable = false)
    private Boolean enabled = true;

    @Column(name = "historical_mode_enabled", nullable = false)
    private Boolean historicalModeEnabled = false;

    @OneToMany(mappedBy = "pipeline", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ConfigPipelineStage> stages;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "updated_by")
    private Long updatedBy;
}
```

**ConfigPipelineStage Entity**

```java
@Entity
@Table(name = "config_pipeline_stage")
public class ConfigPipelineStage {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "pipeline_id", nullable = false)
    private ConfigPipeline pipeline;

    @Column(nullable = false, length = 50)
    private String name;

    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private StageType type; // CP, PPLOG, EXENSIO

    @Column(nullable = false)
    private Integer timeoutMinutes;

    @ElementCollection
    @CollectionTable(name = "config_stage_dependency",
                     joinColumns = @JoinColumn(name = "stage_id"))
    @Column(name = "depends_on")
    private Set<String> dependsOn;

    @Column(nullable = false)
    private Integer executionOrder;
}
```

**ConfigEtlServer Entity**

```java
@Entity
@Table(name = "config_etl_server")
public class ConfigEtlServer {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 100)
    private String serverKey;

    @Column(nullable = false, length = 255)
    private String host;

    @Column(nullable = false)
    private Integer sshPort;

    @Column(nullable = false)
    private Integer socketPort;

    @Column(nullable = false, length = 100)
    private String user;

    @Column(nullable = false, length = 500)
    private String encryptedPassword; // AES-256 encrypted

    @Column(nullable = false)
    private Integer timeoutMs;

    @Column(nullable = false, length = 10)
    private String environment; // PROD or QA

    @Column(nullable = false)
    private Boolean enabled = true;

    @Column(name = "is_historical_sender", nullable = false)
    private Boolean isHistoricalSender = false;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "updated_by")
    private Long updatedBy;
}
```

**ConfigDbConnection Entity**

```java
@Entity
@Table(name = "config_db_connection")
public class ConfigDbConnection {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 100)
    private String connectionKey;

    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private DbType dbType; // ORACLE, POSTGRESQL

    @Column(nullable = false, length = 100)
    private String schema;

    @Column(nullable = false, length = 255)
    private String host;

    @Column(nullable = false, length = 100)
    private String user;

    @Column(nullable = false, length = 500)
    private String encryptedPassword; // AES-256 encrypted

    @Column(nullable = false)
    private Integer connectionTimeoutMs;

    @Column(nullable = false)
    private Integer maximumPoolSize;

    @Column(nullable = false)
    private Integer minimumIdle;

    @Column(length = 500)
    private String downloadUrl;

    @Column(nullable = false, length = 10)
    private String environment; // PROD or QA

    @Column(nullable = false)
    private Boolean enabled = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "updated_by")
    private Long updatedBy;
}
```

**Enhanced AuditLog Entity**

```java
@Entity
@Table(name = "audit_log", indexes = {
    @Index(name = "idx_audit_user_created", columnList = "user_id,created_at"),
    @Index(name = "idx_audit_resource", columnList = "resource_type,resource_id"),
    @Index(name = "idx_audit_action", columnList = "action"),
    @Index(name = "idx_audit_created", columnList = "created_at")
})
public class AuditLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id")
    private Long userId;

    @Column(nullable = false, length = 100)
    private String action;

    @Column(name = "resource_type", nullable = false, length = 50)
    private String resourceType;

    @Column(name = "resource_id", length = 100)
    private String resourceId;

    @Lob
    @Column(columnDefinition = "CLOB")
    private String details; // JSON string with before/after values

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "user_agent", length = 500)
    private String userAgent;

    @Column(name = "created_at", nullable = false)
    @JdbcTypeCode(SqlTypes.TIMESTAMP_WITH_TIMEZONE)
    private Instant createdAt;

    @Column(length = 20)
    private String status; // SUCCESS, FAILURE, PARTIAL

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    // New action constants for configuration management
    public static final class Actions {
        // Existing
        public static final String USER_CREATED = "USER_CREATED";
        public static final String USER_UPDATED = "USER_UPDATED";
        public static final String USER_DELETED = "USER_DELETED";
        public static final String USER_LOGIN = "USER_LOGIN";
        public static final String USER_LOGOUT = "USER_LOGOUT";

        // Configuration management
        public static final String PIPELINE_CREATED = "PIPELINE_CREATED";
        public static final String PIPELINE_UPDATED = "PIPELINE_UPDATED";
        public static final String PIPELINE_DELETED = "PIPELINE_DELETED";
        public static final String ETL_SERVER_CREATED = "ETL_SERVER_CREATED";
        public static final String ETL_SERVER_UPDATED = "ETL_SERVER_UPDATED";
        public static final String ETL_SERVER_DELETED = "ETL_SERVER_DELETED";
        public static final String DB_CONNECTION_CREATED = "DB_CONNECTION_CREATED";
        public static final String DB_CONNECTION_UPDATED = "DB_CONNECTION_UPDATED";
        public static final String DB_CONNECTION_DELETED = "DB_CONNECTION_DELETED";

        // Session and ETL events
        public static final String SESSION_CREATED = "SESSION_CREATED";
        public static final String SESSION_COMPLETED = "SESSION_COMPLETED";
        public static final String SESSION_FAILED = "SESSION_FAILED";
        public static final String ETL_TRIGGERED = "ETL_TRIGGERED";
        public static final String CONFIG_MIGRATED = "CONFIG_MIGRATED";
    }

    // Resource type constants
    public static final class ResourceTypes {
        public static final String USER = "USER";
        public static final String PIPELINE = "PIPELINE";
        public static final String ETL_SERVER = "ETL_SERVER";
        public static final String DB_CONNECTION = "DB_CONNECTION";
        public static final String SESSION = "SESSION";
        public static final String SYSTEM = "SYSTEM";
    }
}
```

#### 2. Service Layer

**ConfigurationService Interface**

```java
public interface ConfigurationService {
    // Pipeline operations
    List<ConfigPipeline> getAllPipelines(String environment);
    List<ConfigPipeline> getPipelinesBySite(String site, String environment, Boolean historicalMode);
    Optional<ConfigPipeline> getPipelineByKey(String pipelineKey);
    ConfigPipeline savePipeline(ConfigPipeline pipeline);
    void deletePipeline(String pipelineKey);

    // ETL Server operations
    List<ConfigEtlServer> getAllEtlServers(String environment);
    List<ConfigEtlServer> getHistoricalEtlServers(String environment);
    Optional<ConfigEtlServer> getEtlServerByKey(String serverKey);
    ConfigEtlServer saveEtlServer(ConfigEtlServer server);
    void deleteEtlServer(String serverKey);

    // DB Connection operations
    List<ConfigDbConnection> getAllDbConnections(String environment);
    Optional<ConfigDbConnection> getDbConnectionByKey(String connectionKey);
    ConfigDbConnection saveDbConnection(ConfigDbConnection connection);
    void deleteDbConnection(String connectionKey);

    // Query operations for Step 1 UI
    List<String> getSitesByEnvironment(String environment);
    List<SenderOption> getSendersBySite(String site, String environment, Boolean historicalMode);

    // Validation
    void validatePipeline(ConfigPipeline pipeline);
    void validateEtlServer(ConfigEtlServer server);
    void validateDbConnection(ConfigDbConnection connection);

    // Cache management
    void invalidateCache();
    void invalidateCacheForPipeline(String pipelineKey);
    void invalidateCacheForServer(String serverKey);
    void invalidateCacheForConnection(String connectionKey);

    // Health check
    ConfigurationSourceHealth getHealthStatus();
}
```

**ConfigurationServiceImpl**

```java
@Service
@Slf4j
public class ConfigurationServiceImpl implements ConfigurationService {

    private final ConfigPipelineRepository pipelineRepo;
    private final ConfigEtlServerRepository serverRepo;
    private final ConfigDbConnectionRepository connectionRepo;
    private final YamlConfigLoader yamlLoader;
    private final PasswordEncryptionService encryptionService;
    private final AuditService auditService;

    // Caffeine cache for loaded configurations
    private final Cache<String, ConfigPipeline> pipelineCache;
    private final Cache<String, ConfigEtlServer> serverCache;
    private final Cache<String, ConfigDbConnection> connectionCache;

    @PostConstruct
    public void init() {
        pipelineCache = Caffeine.newBuilder()
            .maximumSize(1000)
            .expireAfterWrite(Duration.ofHours(1))
            .build();

        serverCache = Caffeine.newBuilder()
            .maximumSize(500)
            .expireAfterWrite(Duration.ofHours(1))
            .build();

        connectionCache = Caffeine.newBuilder()
            .maximumSize(500)
            .expireAfterWrite(Duration.ofHours(1))
            .build();
    }

    @Override
    public Optional<ConfigPipeline> getPipelineByKey(String pipelineKey) {
        // Try cache first
        ConfigPipeline cached = pipelineCache.getIfPresent(pipelineKey);
        if (cached != null) {
            return Optional.of(cached);
        }

        // Try database
        Optional<ConfigPipeline> dbResult = pipelineRepo.findByPipelineKey(pipelineKey);
        if (dbResult.isPresent()) {
            pipelineCache.put(pipelineKey, dbResult.get());
            return dbResult;
        }

        // Fall back to YAML
        log.warn("Pipeline '{}' not found in database, falling back to YAML", pipelineKey);
        Optional<ConfigPipeline> yamlResult = yamlLoader.loadPipelineFromYaml(pipelineKey);
        yamlResult.ifPresent(p -> pipelineCache.put(pipelineKey, p));
        return yamlResult;
    }

    @Override
    @Transactional
    public ConfigPipeline savePipeline(ConfigPipeline pipeline) {
        // Validate before save
        validatePipeline(pipeline);

        // Encrypt sensitive fields if needed
        // (pipelines don't currently have passwords, but future-proof)

        // Capture old state for audit
        Optional<ConfigPipeline> oldState = pipelineRepo.findByPipelineKey(pipeline.getPipelineKey());

        // Save
        ConfigPipeline saved = pipelineRepo.save(pipeline);

        // Invalidate cache
        invalidateCacheForPipeline(saved.getPipelineKey());

        // Audit log
        String action = oldState.isPresent() ?
            AuditLog.Actions.PIPELINE_UPDATED : AuditLog.Actions.PIPELINE_CREATED;
        auditService.logConfigChange(
            action,
            AuditLog.ResourceTypes.PIPELINE,
            saved.getPipelineKey(),
            createAuditDetails(oldState.orElse(null), saved)
        );

        return saved;
    }

    @Override
    public void validatePipeline(ConfigPipeline pipeline) {
        // Check site exists
        if (!connectionRepo.existsByConnectionKey(pipeline.getSite())) {
            throw new ValidationException("Site '" + pipeline.getSite() + "' not found in database connections");
        }

        // Check server exists
        if (!serverRepo.existsByServerKey(pipeline.getServer())) {
            throw new ValidationException("Server '" + pipeline.getServer() + "' not found in ETL servers");
        }

        // Validate port range
        if (pipeline.getSocketPort() < 1 || pipeline.getSocketPort() > 65535) {
            throw new ValidationException("Socket port must be between 1 and 65535");
        }

        // Validate stage dependencies
        validateStageDependencies(pipeline.getStages());
    }

    private void validateStageDependencies(List<ConfigPipelineStage> stages) {
        Set<String> stageNames = stages.stream()
            .map(ConfigPipelineStage::getName)
            .collect(Collectors.toSet());

        for (ConfigPipelineStage stage : stages) {
            for (String dependency : stage.getDependsOn()) {
                if (!stageNames.contains(dependency)) {
                    throw new ValidationException(
                        "Stage '" + stage.getName() + "' depends on non-existent stage '" + dependency + "'"
                    );
                }
            }
        }
    }
}
```

**PasswordEncryptionService**

```java
@Service
public class PasswordEncryptionService {

    @Value("${security.encryption.key}")
    private String encryptionKey;

    private Encryptors encryptor;

    @PostConstruct
    public void init() {
        // Initialize Spring Security's encryption
        this.encryptor = Encryptors.text(encryptionKey, KeyGenerators.string().generateKey());
    }

    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) {
            return plaintext;
        }
        return encryptor.encrypt(plaintext);
    }

    public String decrypt(String encrypted) {
        if (encrypted == null || encrypted.isEmpty()) {
            return encrypted;
        }
        return encryptor.decrypt(encrypted);
    }
}
```

**YamlConfigLoader**

```java
@Component
@Slf4j
public class YamlConfigLoader {

    @Value("${config.yaml.etljobs:classpath:etljobs.yml}")
    private Resource etlJobsYaml;

    @Value("${config.yaml.etlservers:classpath:etlservers.yml}")
    private Resource etlServersYaml;

    @Value("${config.yaml.dbconnections:classpath:dbconnections.yml}")
    private Resource dbConnectionsYaml;

    private final ObjectMapper yamlMapper;

    public YamlConfigLoader() {
        this.yamlMapper = new ObjectMapper(new YAMLFactory());
        this.yamlMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    public Optional<ConfigPipeline> loadPipelineFromYaml(String pipelineKey) {
        try {
            JsonNode root = yamlMapper.readTree(etlJobsYaml.getInputStream());
            JsonNode pipelines = root.get("pipelines");

            if (pipelines != null && pipelines.isArray()) {
                for (JsonNode node : pipelines) {
                    String key = node.get("pipelineKey").asText();
                    if (key.equals(pipelineKey)) {
                        return Optional.of(parsePipelineNode(node));
                    }
                }
            }
        } catch (IOException e) {
            log.error("Failed to load pipeline '{}' from YAML", pipelineKey, e);
        }
        return Optional.empty();
    }

    public List<ConfigPipeline> loadAllPipelinesFromYaml() {
        List<ConfigPipeline> pipelines = new ArrayList<>();
        try {
            JsonNode root = yamlMapper.readTree(etlJobsYaml.getInputStream());
            JsonNode pipelinesNode = root.get("pipelines");

            if (pipelinesNode != null && pipelinesNode.isArray()) {
                for (JsonNode node : pipelinesNode) {
                    pipelines.add(parsePipelineNode(node));
                }
            }
        } catch (IOException e) {
            log.error("Failed to load pipelines from YAML", e);
        }
        return pipelines;
    }

    private ConfigPipeline parsePipelineNode(JsonNode node) {
        ConfigPipeline pipeline = new ConfigPipeline();
        pipeline.setPipelineKey(node.get("pipelineKey").asText());
        pipeline.setSite(node.get("site").asText());
        pipeline.setServer(node.get("server").asText());
        pipeline.setSocketPort(node.get("socketPort").asInt());
        pipeline.setConfigName(node.has("configName") ? node.get("configName").asText() : null);
        pipeline.setSenderId(node.get("senderId").asInt());
        pipeline.setRerunPeriodMinutes(node.get("rerunPeriodMinutes").asInt());

        // Infer environment from site name (e.g., "CEBU-PROD" -> "PROD")
        String environment = inferEnvironment(pipeline.getSite());
        pipeline.setEnvironment(environment);

        // Parse stages
        List<ConfigPipelineStage> stages = new ArrayList<>();
        JsonNode stagesNode = node.get("stages");
        if (stagesNode != null && stagesNode.isArray()) {
            int order = 0;
            for (JsonNode stageNode : stagesNode) {
                ConfigPipelineStage stage = new ConfigPipelineStage();
                stage.setPipeline(pipeline);
                stage.setName(stageNode.get("name").asText());
                stage.setType(StageType.valueOf(stageNode.get("type").asText()));
                stage.setTimeoutMinutes(stageNode.get("timeoutMinutes").asInt());
                stage.setExecutionOrder(order++);

                // Parse dependsOn
                Set<String> dependsOn = new HashSet<>();
                if (stageNode.has("dependsOn") && stageNode.get("dependsOn").isArray()) {
                    for (JsonNode dep : stageNode.get("dependsOn")) {
                        dependsOn.add(dep.asText());
                    }
                }
                stage.setDependsOn(dependsOn);

                stages.add(stage);
            }
        }
        pipeline.setStages(stages);

        return pipeline;
    }

    private String inferEnvironment(String site) {
        if (site.endsWith("-PROD")) {
            return "PROD";
        } else if (site.endsWith("-QA")) {
            return "QA";
        }
        return "PROD"; // default
    }

    // Similar methods for ETL servers and DB connections
    public Optional<ConfigEtlServer> loadServerFromYaml(String serverKey) { /* ... */ }
    public List<ConfigEtlServer> loadAllServersFromYaml() { /* ... */ }
    public Optional<ConfigDbConnection> loadConnectionFromYaml(String connectionKey) { /* ... */ }
    public List<ConfigDbConnection> loadAllConnectionsFromYaml() { /* ... */ }
}
```

**Enhanced AuditService**

```java
@Service
@Slf4j
public class AuditService {

    private final AuditLogRepository auditLogRepository;
    private final AuthService authService;
    private final HttpServletRequest request;
    private final ObjectMapper objectMapper;

    public void logConfigChange(String action, String resourceType, String resourceId, String details) {
        Long userId = authService.getCurrentUserId();
        String ipAddress = getClientIpAddress();
        String userAgent = getUserAgent();

        AuditLog auditLog = new AuditLog(
            userId,
            action,
            resourceType,
            resourceId,
            details,
            ipAddress,
            userAgent
        );
        auditLog.setStatus("SUCCESS");

        auditLogRepository.save(auditLog);
    }

    public void logSessionEvent(String action, String sessionId, Map<String, Object> sessionDetails) {
        String details = serializeDetails(sessionDetails);
        logConfigChange(action, AuditLog.ResourceTypes.SESSION, sessionId, details);
    }

    public void logEtlTrigger(String requestId, String site, Integer senderId, String status, String message) {
        Map<String, Object> details = new HashMap<>();
        details.put("site", site);
        details.put("senderId", senderId);
        details.put("status", status);
        details.put("message", message);

        logConfigChange(
            AuditLog.Actions.ETL_TRIGGERED,
            "ETL_TRIGGER",
            requestId,
            serializeDetails(details)
        );
    }

    private String serializeDetails(Map<String, Object> details) {
        try {
            return objectMapper.writeValueAsString(details);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize audit details", e);
            return "{}";
        }
    }

    private String getClientIpAddress() {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            return xForwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private String getUserAgent() {
        return request.getHeader("User-Agent");
    }

    public Page<AuditLog> searchAuditLogs(AuditLogSearchCriteria criteria, Pageable pageable) {
        return auditLogRepository.findWithFilters(
            criteria.getUserId(),
            criteria.getAction(),
            criteria.getResourceType(),
            criteria.getStartDate(),
            criteria.getEndDate(),
            pageable
        );
    }
}
```

#### 3. REST API Controllers

**ConfigurationController**

```java
@RestController
@RequestMapping("/api/configuration")
@Slf4j
public class ConfigurationController {

    private final ConfigurationService configService;

    // Pipeline endpoints
    @GetMapping("/pipelines")
    public ResponseEntity<List<ConfigPipeline>> getAllPipelines(
            @RequestParam(required = false) String environment) {
        return ResponseEntity.ok(configService.getAllPipelines(environment));
    }

    @GetMapping("/pipelines/{key}")
    public ResponseEntity<ConfigPipeline> getPipeline(@PathVariable String key) {
        return configService.getPipelineByKey(key)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/pipelines")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<ConfigPipeline> createPipeline(@Valid @RequestBody ConfigPipeline pipeline) {
        ConfigPipeline saved = configService.savePipeline(pipeline);
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    @PutMapping("/pipelines/{key}")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<ConfigPipeline> updatePipeline(
            @PathVariable String key,
            @Valid @RequestBody ConfigPipeline pipeline) {
        pipeline.setPipelineKey(key);
        ConfigPipeline saved = configService.savePipeline(pipeline);
        return ResponseEntity.ok(saved);
    }

    @DeleteMapping("/pipelines/{key}")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<Void> deletePipeline(@PathVariable String key) {
        configService.deletePipeline(key);
        return ResponseEntity.noContent().build();
    }

    // ETL Server endpoints
    @GetMapping("/etl-servers")
    public ResponseEntity<List<ConfigEtlServer>> getAllEtlServers(
            @RequestParam(required = false) String environment) {
        return ResponseEntity.ok(configService.getAllEtlServers(environment));
    }

    @PostMapping("/etl-servers")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<ConfigEtlServer> createEtlServer(@Valid @RequestBody ConfigEtlServer server) {
        ConfigEtlServer saved = configService.saveEtlServer(server);
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    // DB Connection endpoints
    @GetMapping("/db-connections")
    public ResponseEntity<List<ConfigDbConnection>> getAllDbConnections(
            @RequestParam(required = false) String environment) {
        return ResponseEntity.ok(configService.getAllDbConnections(environment));
    }

    @PostMapping("/db-connections")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<ConfigDbConnection> createDbConnection(@Valid @RequestBody ConfigDbConnection connection) {
        ConfigDbConnection saved = configService.saveDbConnection(connection);
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    // Query endpoints for Step 1 UI
    @GetMapping("/sites")
    public ResponseEntity<List<String>> getSites(@RequestParam String environment) {
        return ResponseEntity.ok(configService.getSitesByEnvironment(environment));
    }

    @GetMapping("/senders")
    public ResponseEntity<List<SenderOption>> getSenders(
            @RequestParam String site,
            @RequestParam String environment) {
        return ResponseEntity.ok(configService.getSendersBySite(site, environment));
    }

    // Health endpoint
    @GetMapping("/health")
    public ResponseEntity<ConfigurationSourceHealth> getHealth() {
        return ResponseEntity.ok(configService.getHealthStatus());
    }
}
```

**AuditLogController**

```java
@RestController
@RequestMapping("/api/audit-logs")
@PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
@Slf4j
public class AuditLogController {

    private final AuditService auditService;

    @GetMapping
    public ResponseEntity<Page<AuditLog>> getAuditLogs(
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String resourceType,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant endDate,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "createdAt,desc") String sort) {

        AuditLogSearchCriteria criteria = AuditLogSearchCriteria.builder()
            .userId(userId)
            .action(action)
            .resourceType(resourceType)
            .startDate(startDate)
            .endDate(endDate)
            .build();

        Pageable pageable = PageRequest.of(page, size, Sort.by(sort.split(",")));
        Page<AuditLog> result = auditService.searchAuditLogs(criteria, pageable);

        return ResponseEntity.ok(result);
    }

    @GetMapping("/{id}")
    public ResponseEntity<AuditLog> getAuditLogById(@PathVariable Long id) {
        return auditService.getAuditLogById(id)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/statistics")
    public ResponseEntity<Map<String, Object>> getStatistics(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant since) {
        Instant sinceDate = (since != null) ? since : Instant.now().minus(Duration.ofDays(30));
        Map<String, Object> stats = auditService.getStatisticsSince(sinceDate);
        return ResponseEntity.ok(stats);
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> exportAuditLogs(
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String resourceType,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant endDate) {

        AuditLogSearchCriteria criteria = AuditLogSearchCriteria.builder()
            .userId(userId)
            .action(action)
            .resourceType(resourceType)
            .startDate(startDate)
            .endDate(endDate)
            .build();

        byte[] csvBytes = auditService.exportToCSV(criteria);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("text/csv"));
        headers.setContentDispositionFormData("attachment", "audit-logs-" + Instant.now().toString() + ".csv");

        return ResponseEntity.ok()
            .headers(headers)
            .body(csvBytes);
    }
}
```

### Frontend Components

#### 1. Admin Panel Components

**Pipeline Configuration Component**

```typescript
@Component({
  selector: 'app-admin-pipeline-config',
  standalone: true,
  imports: [CommonModule, ReactiveFormsModule, GlassTableComponent, GlassDialogComponent],
  template: `
    <div class="admin-container">
      <header class="admin-header">
        <h1>Pipeline <span class="accent">Configuration</span></h1>
        <button class="glass-btn primary" (click)="openCreateDialog()">
          <app-glass-icon name="add"></app-glass-icon>
          Add Pipeline
        </button>
      </header>

      <div class="filter-bar glass-panel">
        <app-glass-select label="Environment" [formControl]="environmentFilter" [options]="['ALL', 'PROD', 'QA']">
        </app-glass-select>

        <app-glass-input placeholder="Search by key, site, or server..." [formControl]="searchControl">
        </app-glass-input>
      </div>

      <div class="content-area glass-panel">
        <table class="exensioreload-table">
          <thead>
            <tr>
              <th>Pipeline Key</th>
              <th>Site</th>
              <th>Server</th>
              <th>Sender ID</th>
              <th>Socket Port</th>
              <th>Environment</th>
              <th>Status</th>
              <th>Actions</th>
            </tr>
          </thead>
          <tbody>
            <tr *ngFor="let pipeline of filteredPipelines()">
              <td>
                <code>{{ pipeline.pipelineKey }}</code>
              </td>
              <td>{{ pipeline.site }}</td>
              <td>{{ pipeline.server }}</td>
              <td>{{ pipeline.senderId }}</td>
              <td>{{ pipeline.socketPort }}</td>
              <td>
                <span class="env-badge" [attr.data-env]="pipeline.environment">{{ pipeline.environment }}</span>
              </td>
              <td>
                <span class="status-badge" [attr.data-enabled]="pipeline.enabled">{{
                  pipeline.enabled ? 'Enabled' : 'Disabled'
                }}</span>
              </td>
              <td>
                <button class="icon-btn" (click)="openEditDialog(pipeline)" [glassTooltip]="'Edit'">
                  <app-glass-icon name="edit"></app-glass-icon>
                </button>
                <button class="icon-btn danger" (click)="deletePipeline(pipeline)" [glassTooltip]="'Delete'">
                  <app-glass-icon name="delete"></app-glass-icon>
                </button>
              </td>
            </tr>
          </tbody>
        </table>

        <app-glass-pagination
          [length]="totalElements()"
          [pageSize]="pageSize"
          [pageIndex]="pageIndex"
          (page)="onPageChange($event)"
        >
        </app-glass-pagination>
      </div>
    </div>
  `,
})
export class AdminPipelineConfigComponent implements OnInit {
  environmentFilter = new FormControl('ALL');
  searchControl = new FormControl('');

  pipelines = signal<ConfigPipeline[]>([]);
  filteredPipelines = computed(() => this.applyFilters());
  totalElements = signal(0);
  pageSize = 20;
  pageIndex = 0;

  constructor(
    private configService: ConfigurationService,
    private dialog: GlassDialogService,
    private toast: ToastService,
  ) {}

  ngOnInit(): void {
    this.loadPipelines();

    // React to filter changes
    this.environmentFilter.valueChanges.subscribe(() => this.loadPipelines());
    this.searchControl.valueChanges
      .pipe(debounceTime(300), distinctUntilChanged())
      .subscribe(() => this.loadPipelines());
  }

  loadPipelines(): void {
    const env = this.environmentFilter.value === 'ALL' ? undefined : this.environmentFilter.value;
    const search = this.searchControl.value || undefined;

    this.configService.getPipelines(env, search, this.pageIndex, this.pageSize).subscribe({
      next: (response) => {
        this.pipelines.set(response.content);
        this.totalElements.set(response.totalElements);
      },
      error: (err) => {
        this.toast.error('Failed to load pipelines');
        console.error(err);
      },
    });
  }

  openCreateDialog(): void {
    const dialogRef = this.dialog.open<PipelineFormDialogComponent, any, ConfigPipeline>(PipelineFormDialogComponent, {
      data: { mode: 'create' },
    });

    dialogRef.afterClosed().subscribe((result) => {
      if (result) {
        this.configService.createPipeline(result).subscribe({
          next: () => {
            this.toast.success('Pipeline created successfully');
            this.loadPipelines();
          },
          error: (err) => {
            this.toast.error('Failed to create pipeline: ' + err.message);
          },
        });
      }
    });
  }

  openEditDialog(pipeline: ConfigPipeline): void {
    const dialogRef = this.dialog.open<PipelineFormDialogComponent, any, ConfigPipeline>(PipelineFormDialogComponent, {
      data: { mode: 'edit', pipeline },
    });

    dialogRef.afterClosed().subscribe((result) => {
      if (result) {
        this.configService.updatePipeline(pipeline.pipelineKey, result).subscribe({
          next: () => {
            this.toast.success('Pipeline updated successfully');
            this.loadPipelines();
          },
          error: (err) => {
            this.toast.error('Failed to update pipeline: ' + err.message);
          },
        });
      }
    });
  }

  deletePipeline(pipeline: ConfigPipeline): void {
    const dialogRef = this.dialog.confirm({
      title: 'Delete Pipeline',
      message: `Are you sure you want to delete pipeline "${pipeline.pipelineKey}"? This action cannot be undone.`,
      confirmText: 'Delete',
      cancelText: 'Cancel',
      danger: true,
    });

    dialogRef.afterClosed().subscribe((confirmed) => {
      if (confirmed) {
        this.configService.deletePipeline(pipeline.pipelineKey).subscribe({
          next: () => {
            this.toast.success('Pipeline deleted successfully');
            this.loadPipelines();
          },
          error: (err) => {
            this.toast.error('Failed to delete pipeline: ' + err.message);
          },
        });
      }
    });
  }

  private applyFilters(): ConfigPipeline[] {
    let filtered = this.pipelines();

    const search = this.searchControl.value?.toLowerCase();
    if (search) {
      filtered = filtered.filter(
        (p) =>
          p.pipelineKey.toLowerCase().includes(search) ||
          p.site.toLowerCase().includes(search) ||
          p.server.toLowerCase().includes(search),
      );
    }

    return filtered;
  }
}
```

#### 2. Enhanced Stepper Component

**Updated Stepper Service Integration**

```typescript
// Add to stepper.component.ts

export class StepperComponent implements OnInit {
  // ... existing fields ...

  // Add configuration service injection
  constructor(
    // ... existing injections ...
    private configService: ConfigurationService,
  ) {}

  ngOnInit(): void {
    // ... existing initialization ...

    // Load sites from database/YAML when environment changes
    effect(() => {
      const env = this.selectedEnv();
      if (env) {
        this.loadSitesForEnvironment(env);
      }
    });

    // Load senders when site changes
    effect(() => {
      const site = this.selectedSite();
      const env = this.selectedEnv();
      if (site && env) {
        this.loadSendersForSite(site, env);
      }
    });
  }

  private loadSitesForEnvironment(environment: string): void {
    this.configService.getSites(environment).subscribe({
      next: (sites) => {
        this.siteOptions.set(sites.map((s) => ({ value: s, label: formatSiteName(s) })));
      },
      error: (err) => {
        console.error('Failed to load sites', err);
        this.toast.error('Failed to load sites');
      },
    });
  }

  private loadSendersForSite(site: string, environment: string): void {
    this.senderLookupLoading.set(true);
    this.configService.getSenders(site, environment).subscribe({
      next: (senders) => {
        this.senderOptions.set(senders);

        // Auto-select if only one sender
        if (senders.length === 1) {
          this.selectedSenderId.set(senders[0].senderId);
          this.selectedSenderPort.set(senders[0].port);
          this.selectedSenderName.set(senders[0].name);
          this.senderAutoResolved.set(true);
        } else {
          this.senderAutoResolved.set(false);
        }

        this.senderLookupLoading.set(false);
      },
      error: (err) => {
        console.error('Failed to load senders', err);
        this.toast.error('Failed to load senders');
        this.senderLookupLoading.set(false);
      },
    });
  }
}
```

#### 3. Enhanced Audit Log Component

The existing `AuditLogTableComponent` will be enhanced to support the new audit log types:

```typescript
// Updates to audit-log-table.component.ts

export class AuditLogTableComponent implements OnInit {
  // ... existing fields ...

  // Add new filter for resource type
  resourceTypeFilter = new FormControl('');

  // Add resource type options
  resourceTypes: string[] = ['USER', 'PIPELINE', 'ETL_SERVER', 'DB_CONNECTION', 'SESSION', 'ETL_TRIGGER', 'SYSTEM'];

  // Update loadAuditLogs to include new filters
  loadAuditLogs(): void {
    this.loading.set(true);
    this.auditService
      .getAuditLogs({
        page: this.pageIndex,
        size: this.pageSize,
        userId: this.searchControl.value || undefined,
        action: this.statusFilter.value || undefined, // rename to actionFilter
        resourceType: this.resourceTypeFilter.value || undefined,
        startDate: this.dateRangeFilter.value?.start,
        endDate: this.dateRangeFilter.value?.end,
      })
      .subscribe({
        next: (res) => {
          this.dataSource.set(res.content);
          this.totalElements.set(res.totalElements);
          this.loading.set(false);
        },
        error: () => this.loading.set(false),
      });
  }

  // Add export functionality
  exportToCSV(): void {
    this.auditService
      .exportAuditLogs({
        userId: this.searchControl.value || undefined,
        action: this.statusFilter.value || undefined,
        resourceType: this.resourceTypeFilter.value || undefined,
        startDate: this.dateRangeFilter.value?.start,
        endDate: this.dateRangeFilter.value?.end,
      })
      .subscribe({
        next: (blob) => {
          const url = window.URL.createObjectURL(blob);
          const a = document.createElement('a');
          a.href = url;
          a.download = `audit-logs-${new Date().toISOString()}.csv`;
          a.click();
          window.URL.revokeObjectURL(url);
        },
        error: () => this.toast.error('Failed to export audit logs'),
      });
  }
}
```

## Data Models

### Database Schema

**PostgreSQL Schema**

```sql
-- Configuration tables
CREATE TABLE config_pipeline (
    id BIGSERIAL PRIMARY KEY,
    pipeline_key VARCHAR(100) NOT NULL UNIQUE,
    site VARCHAR(50) NOT NULL,
    server VARCHAR(50) NOT NULL,
    socket_port INTEGER NOT NULL,
    config_name VARCHAR(255),
    sender_id INTEGER NOT NULL,
    rerun_period_minutes INTEGER NOT NULL,
    environment VARCHAR(10) NOT NULL CHECK (environment IN ('PROD', 'QA')),
    enabled BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE,
    created_by BIGINT,
    updated_by BIGINT,
    FOREIGN KEY (created_by) REFERENCES users(id),
    FOREIGN KEY (updated_by) REFERENCES users(id)
);

CREATE INDEX idx_pipeline_site ON config_pipeline(site);
CREATE INDEX idx_pipeline_environment ON config_pipeline(environment);
CREATE INDEX idx_pipeline_sender ON config_pipeline(sender_id);

CREATE TABLE config_pipeline_stage (
    id BIGSERIAL PRIMARY KEY,
    pipeline_id BIGINT NOT NULL,
    name VARCHAR(50) NOT NULL,
    type VARCHAR(20) NOT NULL CHECK (type IN ('CP', 'PPLOG', 'EXENSIO')),
    timeout_minutes INTEGER NOT NULL,
    execution_order INTEGER NOT NULL,
    FOREIGN KEY (pipeline_id) REFERENCES config_pipeline(id) ON DELETE CASCADE
);

CREATE TABLE config_stage_dependency (
    stage_id BIGINT NOT NULL,
    depends_on VARCHAR(50) NOT NULL,
    PRIMARY KEY (stage_id, depends_on),
    FOREIGN KEY (stage_id) REFERENCES config_pipeline_stage(id) ON DELETE CASCADE
);

CREATE TABLE config_etl_server (
    id BIGSERIAL PRIMARY KEY,
    server_key VARCHAR(100) NOT NULL UNIQUE,
    host VARCHAR(255) NOT NULL,
    ssh_port INTEGER NOT NULL,
    socket_port INTEGER NOT NULL,
    user_name VARCHAR(100) NOT NULL,
    encrypted_password VARCHAR(500) NOT NULL,
    timeout_ms INTEGER NOT NULL,
    environment VARCHAR(10) NOT NULL CHECK (environment IN ('PROD', 'QA')),
    enabled BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE,
    created_by BIGINT,
    updated_by BIGINT,
    FOREIGN KEY (created_by) REFERENCES users(id),
    FOREIGN KEY (updated_by) REFERENCES users(id)
);

CREATE INDEX idx_etl_server_environment ON config_etl_server(environment);

CREATE TABLE config_db_connection (
    id BIGSERIAL PRIMARY KEY,
    connection_key VARCHAR(100) NOT NULL UNIQUE,
    db_type VARCHAR(20) NOT NULL CHECK (db_type IN ('ORACLE', 'POSTGRESQL')),
    schema_name VARCHAR(100) NOT NULL,
    host VARCHAR(255) NOT NULL,
    user_name VARCHAR(100) NOT NULL,
    encrypted_password VARCHAR(500) NOT NULL,
    connection_timeout_ms INTEGER NOT NULL,
    maximum_pool_size INTEGER NOT NULL,
    minimum_idle INTEGER NOT NULL,
    download_url VARCHAR(500),
    environment VARCHAR(10) NOT NULL CHECK (environment IN ('PROD', 'QA')),
    enabled BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE,
    created_by BIGINT,
    updated_by BIGINT,
    FOREIGN KEY (created_by) REFERENCES users(id),
    FOREIGN KEY (updated_by) REFERENCES users(id)
);

CREATE INDEX idx_db_connection_environment ON config_db_connection(environment);

-- Enhanced audit_log table (add new columns to existing)
ALTER TABLE audit_log ADD COLUMN IF NOT EXISTS status VARCHAR(20);
ALTER TABLE audit_log ADD COLUMN IF NOT EXISTS error_message VARCHAR(1000);

-- Add indexes for better query performance
CREATE INDEX IF NOT EXISTS idx_audit_user_created ON audit_log(user_id, created_at);
CREATE INDEX IF NOT EXISTS idx_audit_resource ON audit_log(resource_type, resource_id);
CREATE INDEX IF NOT EXISTS idx_audit_action ON audit_log(action);
CREATE INDEX IF NOT EXISTS idx_audit_created ON audit_log(created_at);
```

**Oracle Schema**

```sql
-- Configuration tables (Oracle syntax)
CREATE TABLE config_pipeline (
    id NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    pipeline_key VARCHAR2(100) NOT NULL UNIQUE,
    site VARCHAR2(50) NOT NULL,
    server VARCHAR2(50) NOT NULL,
    socket_port NUMBER NOT NULL,
    config_name VARCHAR2(255),
    sender_id NUMBER NOT NULL,
    rerun_period_minutes NUMBER NOT NULL,
    environment VARCHAR2(10) NOT NULL CHECK (environment IN ('PROD', 'QA')),
    enabled NUMBER(1) DEFAULT 1 NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE,
    created_by NUMBER,
    updated_by NUMBER,
    CONSTRAINT fk_pipeline_created_by FOREIGN KEY (created_by) REFERENCES users(id),
    CONSTRAINT fk_pipeline_updated_by FOREIGN KEY (updated_by) REFERENCES users(id)
);

-- Similar DDL for other tables with Oracle syntax...
```

## Correctness Properties

_A property is a characteristic or behavior that should hold true across all valid executions of a system—essentially, a formal statement about what the system should do. Properties serve as the bridge between human-readable specifications and machine-verifiable correctness guarantees._

### Property 1: Configuration Loading Fallback Consistency

_For any_ configuration entity (pipeline, server, or connection), when the entity is missing from the database, loading that entity from YAML should produce a valid configuration object with the same structure and constraints as database-loaded entities.

**Validates: Requirements 1.3, 3.1, 3.2, 3.4**

### Property 2: Password Encryption Round Trip

_For any_ plaintext password string, encrypting then decrypting should produce the original plaintext value.

**Validates: Requirements 8.5, 9.5**

### Property 3: Configuration Validation Referential Integrity

_For any_ pipeline configuration being saved, if the pipeline references a site key that does not exist in database connections, validation should reject the save operation with a clear error message.

**Validates: Requirements 14.1, 14.2**

### Property 4: Audit Log Immutability

_For any_ audit log entry created, attempting to update or delete that entry should be rejected by the system (audit logs are append-only).

**Validates: Requirements 10.8**

### Property 5: Cache Invalidation on Modification

_For any_ configuration entity, when that entity is created, updated, or deleted, the cached version of that entity should be removed from memory, and the next read should fetch fresh data from the database.

**Validates: Requirements 15.1, 15.2, 15.3**

### Property 6: Environment Filter Consistency

_For any_ environment filter value (PROD or QA), querying configurations with that filter should return only entities where the environment field matches the filter value.

**Validates: Requirements 2.3, 2.4**

### Property 7: Stage Dependency Validation

_For any_ pipeline configuration with stages, if a stage declares a dependency on another stage name, that dependency name must exist in the same pipeline's stage list, otherwise validation should reject the configuration.

**Validates: Requirements 14.7**

### Property 8: Sender Auto-Selection

_For any_ site and environment combination, when only one sender matches the criteria, the sender should be automatically selected in Step 1 UI without user interaction.

**Validates: Requirements 4.3**

### Property 9: Audit Log Action Recording

_For any_ configuration CRUD operation (create, update, delete), an audit log entry should be created with the correct action type, resource type, resource ID, and user ID.

**Validates: Requirements 10.2, 10.6**

### Property 10: Port Range Validation

_For any_ pipeline or ETL server configuration, if the socket port or SSH port value is outside the range 1-65535, validation should reject the configuration with a clear error message.

**Validates: Requirements 14.4**

### Property 11: YAML Merge Precedence

_For any_ configuration entity that exists in both database and YAML, the database version should take precedence and be returned when loading that entity.

**Validates: Requirements 3.4**

### Property 12: Configuration Source Health Status

_For any_ system state, the health status endpoint should correctly report whether configurations are being loaded from database, YAML, or a hybrid of both sources.

**Validates: Requirements 3.5**

## Error Handling

### Error Categories

1. **Validation Errors** (HTTP 400)
   - Invalid field values (port range, empty required fields)
   - Referential integrity violations (non-existent site/server references)
   - Stage dependency cycles
2. **Authentication/Authorization Errors** (HTTP 401/403)
   - User not authenticated
   - User lacks required role (ADMIN/SUPER_ADMIN)
3. **Resource Not Found Errors** (HTTP 404)
   - Configuration entity not found in database or YAML
   - Audit log entry not found
4. **Conflict Errors** (HTTP 409)
   - Duplicate pipeline key
   - Duplicate server key
   - Duplicate connection key
5. **Database Errors** (HTTP 500)
   - Connection pool exhausted
   - SQL constraint violation
   - Transaction rollback
6. **Encryption Errors** (HTTP 500)
   - Encryption key missing or invalid
   - Decryption failure (corrupted data)

### Error Response Format

```json
{
  "timestamp": "2026-09-17T10:30:00.000Z",
  "status": 400,
  "error": "Bad Request",
  "message": "Pipeline validation failed: Site 'INVALID-SITE' not found in database connections",
  "path": "/api/configuration/pipelines",
  "details": {
    "field": "site",
    "rejectedValue": "INVALID-SITE",
    "validationErrors": ["Site must reference an existing database connection"]
  }
}
```

### Fallback Strategy Error Handling

When database is unavailable:

1. Log warning with connection error details
2. Attempt YAML fallback for all subsequent reads
3. Update health status to "YAML_FALLBACK"
4. Retry database connection every 60 seconds
5. Return to database-first mode when connection restored

## Testing Strategy

### Unit Tests

Unit tests will focus on:

- **Service layer validation logic**: Test `validatePipeline()`, `validateEtlServer()`, `validateDbConnection()`
- **Password encryption/decryption**: Test `PasswordEncryptionService` round-trip
- **YAML parsing**: Test `YamlConfigLoader` with sample YAML files
- **Audit log formatting**: Test audit details serialization
- **Cache invalidation**: Test cache behavior on CRUD operations

### Property-Based Tests

Property-based tests will use **jqwik** (Java property testing library) to verify universal correctness properties:

**Test 1: Configuration Round Trip (Property 1)**

```java
@Property
@Label("Feature: configuration-management-enhancement, Property 1: Configuration Loading Fallback Consistency")
void configurationYamlFallbackProducesValidStructure(
    @ForAll("pipelineKeys") String pipelineKey) {

    // Arrange: Ensure database returns empty
    when(pipelineRepo.findByPipelineKey(pipelineKey)).thenReturn(Optional.empty());

    // Act: Load from YAML
    Optional<ConfigPipeline> result = configService.getPipelineByKey(pipelineKey);

    // Assert: Result should be valid if YAML has it
    result.ifPresent(pipeline -> {
        assertNotNull(pipeline.getSite());
        assertNotNull(pipeline.getServer());
        assertTrue(pipeline.getSocketPort() > 0 && pipeline.getSocketPort() <= 65535);
        assertNotNull(pipeline.getEnvironment());
        assertTrue(pipeline.getEnvironment().equals("PROD") || pipeline.getEnvironment().equals("QA"));
    });
}
```

**Test 2: Password Encryption Round Trip (Property 2)**

```java
@Property
@Label("Feature: configuration-management-enhancement, Property 2: Password Encryption Round Trip")
void passwordEncryptionRoundTrip(@ForAll("passwords") String plaintext) {
    // Act
    String encrypted = encryptionService.encrypt(plaintext);
    String decrypted = encryptionService.decrypt(encrypted);

    // Assert
    assertEquals(plaintext, decrypted);
}

@Provide
Arbitrary<String> passwords() {
    return Arbitraries.strings()
        .withCharRange('a', 'z')
        .withCharRange('A', 'Z')
        .withCharRange('0', '9')
        .withChars('!', '@', '#', '$', '%')
        .ofMinLength(8)
        .ofMaxLength(64);
}
```

**Test 3: Referential Integrity Validation (Property 3)**

```java
@Property
@Label("Feature: configuration-management-enhancement, Property 3: Configuration Validation Referential Integrity")
void pipelineValidationRejectsInvalidSiteReference(
    @ForAll("pipelineKeys") String pipelineKey,
    @ForAll("siteKeys") String invalidSite) {

    // Arrange: Site doesn't exist
    when(connectionRepo.existsByConnectionKey(invalidSite)).thenReturn(false);

    ConfigPipeline pipeline = new ConfigPipeline();
    pipeline.setPipelineKey(pipelineKey);
    pipeline.setSite(invalidSite);
    pipeline.setServer("TEST-SERVER");
    pipeline.setSocketPort(60170);
    pipeline.setSenderId(1);
    pipeline.setEnvironment("PROD");

    // Act & Assert
    assertThrows(ValidationException.class, () -> {
        configService.validatePipeline(pipeline);
    });
}
```

**Test 4: Port Range Validation (Property 10)**

```java
@Property
@Label("Feature: configuration-management-enhancement, Property 10: Port Range Validation")
void portValidationRejectsOutOfRangeValues(@ForAll @IntRange(min = -1000, max = 70000) int port) {
    // Arrange
    ConfigPipeline pipeline = createValidPipeline();
    pipeline.setSocketPort(port);

    // Act & Assert
    if (port < 1 || port > 65535) {
        assertThrows(ValidationException.class, () -> {
            configService.validatePipeline(pipeline);
        });
    } else {
        assertDoesNotThrow(() -> {
            configService.validatePipeline(pipeline);
        });
    }
}
```

**Test 5: Environment Filter Consistency (Property 6)**

```java
@Property
@Label("Feature: configuration-management-enhancement, Property 6: Environment Filter Consistency")
void environmentFilterReturnsOnlyMatchingConfigurations(
    @ForAll("environments") String environment) {

    // Arrange: Create test data with mixed environments
    List<ConfigPipeline> allPipelines = Arrays.asList(
        createPipeline("PIPE-1", "PROD"),
        createPipeline("PIPE-2", "QA"),
        createPipeline("PIPE-3", "PROD")
    );
    when(pipelineRepo.findByEnvironment(environment)).thenReturn(
        allPipelines.stream()
            .filter(p -> p.getEnvironment().equals(environment))
            .collect(Collectors.toList())
    );

    // Act
    List<ConfigPipeline> result = configService.getAllPipelines(environment);

    // Assert: All returned entities must match the filter
    for (ConfigPipeline pipeline : result) {
        assertEquals(environment, pipeline.getEnvironment());
    }
}

@Provide
Arbitrary<String> environments() {
    return Arbitraries.of("PROD", "QA");
}
```

### Integration Tests

Integration tests will verify end-to-end workflows:

- Create pipeline via API → Verify database persistence → Read back via API
- Update ETL server → Verify audit log created → Verify cache invalidated
- Delete database connection → Verify referential integrity error when pipeline references it
- Load configuration with database unavailable → Verify YAML fallback works → Verify health status

### UI Component Tests

Angular component tests will use Jasmine/Karma:

- Stepper component site dropdown populates when environment selected
- Admin panel table displays pipelines with correct columns
- Delete confirmation dialog appears when delete button clicked
- Audit log export generates CSV file with correct content

## Notes

### Historical Pipeline Architecture

Historical pipelines are **completely separate pipeline configurations** from normal pipelines, not just a flag:

**Key Design Principles:**

1. **Separate Pipeline Entries**: Each site can have multiple pipelines - one for normal mode and one (or more) for historical mode
2. **Distinct Sender IDs**: Historical pipelines use different sender IDs (typically HIST pattern senders)
3. **Different Socket Ports**: Historical pipelines may use different CP socket ports for dedicated processing
4. **Unique Config Names**: Historical pipelines reference different CP configuration XML files

**Example YAML Structure:**

```yaml
pipelines:
  # Normal pipeline for CEBU
  - pipelineKey: 'CEBU-CP-DEFAULT'
    site: 'CEBU-PROD'
    server: 'CEBU-PROD'
    socketPort: 60170
    configName: 'CPYQSP'
    senderId: 1
    rerunPeriodMinutes: 5
    historicalModeEnabled: false
    stages:
      - name: cp
        type: CP
        timeoutMinutes: 30
      - name: pplog
        type: PPLOG
        dependsOn: [cp]
        timeoutMinutes: 20
      - name: exensio
        type: EXENSIO
        dependsOn: [pplog]
        timeoutMinutes: 60

  # Historical pipeline for CEBU (separate entry with different config)
  - pipelineKey: 'CEBU-CP-HISTORICAL'
    site: 'CEBU-PROD'
    server: 'CEBU-PROD'
    socketPort: 60175 # Different port
    configName: 'CPYQSP_HIST' # Different config
    senderId: 101 # Different sender ID (HIST sender)
    rerunPeriodMinutes: 0
    historicalModeEnabled: true
    stages:
      - name: cp
        type: CP
        timeoutMinutes: 60 # Longer timeout for historical queries
      - name: pplog
        type: PPLOG
        dependsOn: [cp]
        timeoutMinutes: 45
      - name: exensio
        type: EXENSIO
        dependsOn: [pplog]
        timeoutMinutes: 120
```

**Query Behavior:**

- When `historicalMode = false` in Step 1 UI → Query pipelines WHERE `historicalModeEnabled = false` AND `site = selected_site`
- When `historicalMode = true` in Step 1 UI → Query pipelines WHERE `historicalModeEnabled = true` AND `site = selected_site`
- Result: Different pipeline, different sender, different CP configuration automatically selected

**Database Design Impact:**

- No unique constraint on `(site)` - allow multiple pipelines per site
- Unique constraint remains on `(pipelineKey)` only
- Add index on `(site, historicalModeEnabled)` for efficient queries
- Validation: Ensure distinct `senderId` values within same site for normal vs historical pipelines

### General Notes

- All database migrations must be tested against both PostgreSQL and Oracle before deployment
- Encryption keys must be rotated periodically (recommend 90-day rotation)
- Audit logs should be archived to long-term storage (S3, cold storage) after 90 days
- Configuration changes should be reviewed in staging environment before production deployment
- YAML files should be kept in version control as backup and disaster recovery mechanism
- Real-time configuration updates may require application restart for certain components (HikariCP connection pools)

### Property 13: Historical Pipeline Separation

_For any_ site with both historical and normal pipelines configured, querying pipelines with `historicalMode=false` should return only pipelines where `historicalModeEnabled=false`, and querying with `historicalMode=true` should return only pipelines where `historicalModeEnabled=true`.

**Validates: Requirements 16.4, 16.5**

### Property 14: Historical Sender Auto-Detection

_For any_ sender configuration with a name containing "HIST" (case-insensitive), the system should automatically set the `isHistoricalSender` flag to true during YAML migration or manual creation.

**Validates: Requirements 16.7**

### Property 15: Distinct Sender ID Validation

_For any_ two pipelines referencing the same site, if one has `historicalModeEnabled=true` and the other has `historicalModeEnabled=false`, they must have different `senderId` values, otherwise validation should reject the configuration.

**Validates: Requirements 16.2**
