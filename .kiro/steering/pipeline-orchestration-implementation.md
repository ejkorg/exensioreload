---
inclusion: manual
---

# Pipeline Dependency Orchestration - Implementation Steering Guide

This guide provides essential context and patterns for implementing the pipeline dependency orchestration feature without requiring local build tools.

## Architecture Overview

The pipeline orchestration system eliminates race conditions in multi-stage data processing by:

1. **Configuration-driven approach**: Pipeline definitions live in `dbconnections.yml` (per-site)
2. **Dependency tracking**: Each stage declares what it depends on via `dependsOn` array
3. **Pluggable handlers**: Stage types (CP, PP_LOG, Exensio, future) use handler pattern
4. **State persistence**: Pipeline progress tracked in SENDER_STAGE table via new columns

### Key Components

| Component                  | Location           | Responsibility                                                   |
| -------------------------- | ------------------ | ---------------------------------------------------------------- |
| PipelineConfig (record)    | pipeline/          | Immutable config for a site's pipeline                           |
| StageDefinition (record)   | pipeline/          | Definition of a single stage                                     |
| StageType (enum)           | pipeline/          | CP, PPLOG, EXENSIO, SCRIBE, SHIP_SCRIBE, LOT_GENEALOGY, REFTABLE |
| StageStatus (enum)         | pipeline/          | COMPLETED, NOT_FOUND, ERROR, TIMEOUT                             |
| PipelineConfigLoader       | pipeline/          | Parses dbconnections.yml, validates DAG                          |
| PipelineOrchestrator       | pipeline/          | Main orchestration logic                                         |
| StageHandler (interface)   | pipeline/          | Handler for specific stage type                                  |
| PipelineStatusTracker      | pipeline/          | Tracks record state through pipeline                             |
| CpCompletionHandler        | pipeline/handlers/ | Checks CP completion via Elasticsearch                           |
| PpLogCompletionHandler     | pipeline/handlers/ | Checks PP_LOG completion via Oracle                              |
| ExensioVerificationHandler | pipeline/handlers/ | Delegates to ExensioLoadMonitor                                  |

## Task Breakdown & Implementation Patterns

### Task 1: Core Data Models ✅ COMPLETED

**Files Created:**

- `backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/pipeline/PipelineConfig.java`
- `backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/pipeline/StageDefinition.java`
- `backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/pipeline/StageType.java`
- `backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/pipeline/StageStatus.java`
- `backend/src/main/resources/db/changelog/db.changelog-13.0-pipeline-orchestration.xml`

**Database Changes:**

- Added 5 columns to SENDER_STAGE: `current_pipeline_stage`, `completed_pipeline_stages`, `stage_metadata`, `pipeline_started_at`, `last_stage_check_at`
- Created index: `idx_sender_stage_pipeline` on (site, current_pipeline_stage, pipeline_started_at)

**Liquibase Integration:**

- Changeset included in `db.changelog-1.0.xml` with `<include>` tag

---

### Task 2: PipelineConfigLoader Implementation

**Location:** `backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/pipeline/PipelineConfigLoader.java`

**Pattern for YAML Parsing:**

```java
@Component
public class PipelineConfigLoader {
    private final ObjectMapper yamlMapper;

    public PipelineConfigLoader() {
        this.yamlMapper = new ObjectMapper(new YAMLFactory());
    }

    public Optional<PipelineConfig> loadPipelineConfig(String site) throws PipelineConfigException {
        // Load dbconnections.yml
        // Extract site entry
        // Get "pipeline" section if present
        // Parse stages array
        // Build stagesByName map
        // Return PipelineConfig or Optional.empty()
    }
}
```

**Key Methods:**

- `loadPipelineConfig(String site)`: Returns Optional<PipelineConfig>
- `validatePipelineConfig(PipelineConfig config)`: Validates via topological sort
- Private: `detectCircularDependencies()`: Uses DFS to detect cycles

**Dependency Graph Validation:**

1. Build adjacency list from stage dependencies
2. Use topological sort (Kahn's algorithm or DFS)
3. If cycle found → throw `PipelineConfigException`
4. If invalid stage reference → throw `PipelineConfigException`

---

### Task 3: PipelineConfigCache Implementation

**Location:** `backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/pipeline/PipelineConfigCache.java`

**Using Caffeine Cache:**

```java
@Component
public class PipelineConfigCache {
    private final Cache<String, PipelineConfig> cache;
    private final PipelineConfigLoader loader;

    public PipelineConfigCache(PipelineConfigLoader loader) {
        this.loader = loader;
        this.cache = Caffeine.newBuilder()
            .expireAfterWrite(5, TimeUnit.MINUTES)
            .build();
    }

    public Optional<PipelineConfig> getConfig(String site) {
        return cache.asMap().computeIfAbsent(site,
            s -> loader.loadPipelineConfig(s).orElse(null));
    }

    public void invalidateAll() {
        cache.invalidateAll();
    }
}
```

**Dependencies:** Add Caffeine to pom.xml if not present

```xml
<dependency>
    <groupId>com.github.ben-manes.caffeine</groupId>
    <artifactId>caffeine</artifactId>
</dependency>
```

---

### Task 4: StageHandler Interface & Registry

**Locations:**

- `backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/pipeline/StageHandler.java`
- `backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/pipeline/StageResult.java`
- `backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/pipeline/StageHandlerRegistry.java`

**StageHandler Interface:**

```java
public interface StageHandler {
    StageResult checkCompletion(StageRecord record, Map<String, Object> stageConfig);
    StageType getStageType();
}
```

**StageResult Record (factory pattern):**

```java
public record StageResult(
    StageStatus status,
    String traceId,
    String errorMessage,
    Map<String, Object> metadata
) {
    public static StageResult completed(String traceId) { ... }
    public static StageResult notFound(String traceId) { ... }
    public static StageResult error(String traceId, String message) { ... }
    public static StageResult timeout(String traceId) { ... }

    public StageResult withMetadata(String key, Object value) { ... }
}
```

**StageHandlerRegistry:**

```java
@Component
public class StageHandlerRegistry {
    private final Map<StageType, StageHandler> handlers = new EnumMap<>(StageType.class);

    public StageHandlerRegistry(List<StageHandler> handlerList) {
        // Auto-wire all @Component handlers
        handlerList.forEach(h -> handlers.put(h.getStageType(), h));
    }

    public StageHandler getHandler(StageType type) throws HandlerNotFoundException {
        StageHandler handler = handlers.get(type);
        if (handler == null) {
            throw new HandlerNotFoundException("No handler for stage type: " + type);
        }
        return handler;
    }
}
```

---

### Task 5: PipelineStatusTracker Implementation

**Location:** `backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/pipeline/PipelineStatusTracker.java`

**Key Methods:**

```java
public record PipelineState(
    long recordId,
    String currentStage,
    Set<String> completedStages,
    Map<String, Instant> stageCompletionTimes,
    Map<String, String> stageMetadata
) {
    public boolean isStageComplete(String stageName) { ... }
    public Duration getStageAge(String stageName) { ... }
}

@Component
public class PipelineStatusTracker {
    public PipelineState getState(long recordId) {
        // Query SENDER_STAGE columns:
        // - current_pipeline_stage
        // - completed_pipeline_stages (JSON array parse)
        // - stage_metadata (CLOB JSON parse)
        // - pipeline_started_at
        // Build PipelineState record
    }

    public void markStageComplete(long recordId, String stageName, Map<String, Object> metadata) {
        // Add stageName to completed_pipeline_stages JSON array
        // Add metadata entries to stage_metadata JSON object
        // Update last_stage_check_at timestamp
        // Use RefDbService.executeBatchUpdate() for atomicity
    }

    public boolean isStageTimedOut(long recordId, String stageName, Duration timeout) {
        // Get state
        // Get stageAge from state
        // Return stageAge > timeout
    }
}
```

**JSON Handling in Oracle/PostgreSQL:**

For `completed_pipeline_stages` (VARCHAR array):

- Store as: `["cp", "pplog"]`
- Parse: `Arrays.asList(String.split("[\\[\\],\"]+"))`

For `stage_metadata` (CLOB):

- Store as: `{"cp": {...}, "pplog": {...}}`
- Parse: Use Jackson ObjectMapper

---

### Task 6: CpCompletionHandler Implementation

**Location:** `backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/pipeline/handlers/CpCompletionHandler.java`

**Pattern:**

```java
@Component
public class CpCompletionHandler implements StageHandler {
    private final ElasticsearchLogService elasticsearchLogService;
    private final CpElasticsearchProperties cpProperties;

    @Override
    public StageType getStageType() {
        return StageType.CP;
    }

    @Override
    public StageResult checkCompletion(StageRecord record, Map<String, Object> stageConfig) {
        String traceId = UUID.randomUUID().toString();

        // Extract config
        String indexPattern = getStringConfig(stageConfig, "indexPattern", cpProperties.getIndexPattern());
        Integer lookbackSeconds = getIntConfig(stageConfig, "lookbackBufferSeconds", 900);

        // Calculate searchStart
        Instant searchStart = record.endTime() != null
            ? record.endTime().minusSeconds(lookbackSeconds)
            : record.createdAt().minusSeconds(lookbackSeconds);

        // Query Elasticsearch
        CpLogResult result = elasticsearchLogService.findCpLog(
            record.lot(),
            record.wafer(),
            record.metadataId(),
            searchStart,
            record.site(),
            traceId
        );

        // Map result
        return switch (result) {
            case CpLogResult.Success s -> StageResult.completed(traceId)
                .withMetadata("cpOutputTarget", s.outputTarget())
                .withMetadata("cpLogPath", s.logPath());
            case CpLogResult.NotFound nf -> StageResult.notFound(traceId);
            case CpLogResult.Failure f -> StageResult.error(traceId, f.errorMessage());
        };
    }
}
```

**Existing Dependencies to Use:**

- `ElasticsearchLogService` (already exists)
- `CpElasticsearchProperties` (already exists)
- `CpLogResult` (likely exists as sealed interface/record)

---

### Task 7: PpLogCompletionHandler Implementation

**Location:** `backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/pipeline/handlers/PpLogCompletionHandler.java`

**Pattern:**

```java
@Component
public class PpLogCompletionHandler implements StageHandler {
    private final RefDbService refDbService;
    private final PpLogDbProperties ppLogProperties;

    @Override
    public StageType getStageType() {
        return StageType.PPLOG;
    }

    @Override
    public StageResult checkCompletion(StageRecord record, Map<String, Object> stageConfig) {
        String traceId = UUID.randomUUID().toString();

        if (!ppLogProperties.isPpLogAvailable()) {
            return StageResult.error(traceId, "PP_LOG database not configured");
        }

        Integer lookbackSeconds = getIntConfig(stageConfig, "lookbackBufferSeconds", 900);
        Instant searchStart = record.endTime() != null
            ? record.endTime().minusSeconds(lookbackSeconds)
            : record.createdAt().minusSeconds(lookbackSeconds);

        try {
            String ppLogEntry = refDbService.queryPpLog(record.lot(), searchStart, record.site());

            if (ppLogEntry != null && !ppLogEntry.isBlank()) {
                return StageResult.completed(traceId)
                    .withMetadata("ppLogEntry", ppLogEntry);
            } else {
                return StageResult.notFound(traceId);
            }
        } catch (Exception e) {
            return StageResult.error(traceId, "PP_LOG query error: " + e.getMessage());
        }
    }
}
```

**Existing Dependencies to Use:**

- `RefDbService` (already exists)
- `PpLogDbProperties` (likely exists)

---

### Task 8: ExensioVerificationHandler (No-op Delegator)

**Location:** `backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/pipeline/handlers/ExensioVerificationHandler.java`

**Implementation:**

```java
@Component
public class ExensioVerificationHandler implements StageHandler {
    @Override
    public StageType getStageType() {
        return StageType.EXENSIO;
    }

    @Override
    public StageResult checkCompletion(StageRecord record, Map<String, Object> stageConfig) {
        // This is a no-op handler because ExensioLoadMonitor handles Exensio verification
        // via a separate scheduled job. The orchestrator delegates to that job.
        // We return NOT_FOUND to keep the record in EXENSIO_MONITORING status
        // until ExensioLoadMonitor marks it as verified.
        return StageResult.notFound(UUID.randomUUID().toString());
    }
}
```

---

### Task 9-10: PipelineOrchestrator Core Logic

**Location:** `backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/pipeline/PipelineOrchestrator.java`

**Key Methods:**

```java
@Component
public class PipelineOrchestrator {
    private final PipelineConfigCache configCache;
    private final StageHandlerRegistry handlerRegistry;
    private final PipelineStatusTracker statusTracker;
    private final StageMonitorService stageMonitorService;
    private final IntegrationStatusService integrationStatusService;

    public PipelineAction determineNextAction(StageRecord record) {
        Optional<PipelineConfig> config = configCache.getConfig(record.site());

        if (config.isEmpty()) {
            return PipelineAction.useLegacyPath();
        }

        PipelineState state = statusTracker.getState(record.id());
        StageDefinition currentStage = getCurrentStage(config.get(), state);

        if (!areDependenciesSatisfied(currentStage, state)) {
            return PipelineAction.waitForDependencies(currentStage.dependsOn());
        }

        StageHandler handler = handlerRegistry.getHandler(currentStage.type());
        return PipelineAction.executeStage(currentStage, handler);
    }

    public void executeStage(StageRecord record, StageDefinition stage, StageHandler handler) {
        StageResult result = handler.checkCompletion(record, stage.config());

        switch (result.status()) {
            case COMPLETED -> progressToNextStage(record, stage, result);
            case NOT_FOUND -> keepInCurrentStage(record, stage);
            case ERROR -> handleStageError(record, stage, result.errorMessage());
            case TIMEOUT -> handleStageTimeout(record, stage);
        }
    }
}
```

---

## Testing Strategy

### Unit Tests

- Configuration parsing (valid/invalid YAML)
- Circular dependency detection
- Handler registry completeness
- Timeout detection accuracy

### Property-Based Tests (jqwik)

1. **Dependency Order Enforcement**: Any pipeline respects dependency ordering
2. **Configuration Parsing Idempotence**: Parsing same YAML multiple times produces equivalent results
3. **Circular Dependency Detection**: Cyclic configs are rejected
4. **Timeout Detection Accuracy**: Records exceeding timeout are marked timed out
5. Additional properties from design document

### Integration Tests

- End-to-end pipeline: QUEUED → CP_MONITORING → PPLOG_MONITORING → DONE
- Elasticsearch integration for CP log detection
- Oracle integration for PP_LOG table queries
- SSE event emission in correct order

---

## Configuration Example (dbconnections.yml)

```yaml
CEBU-PROD:
  dbType: oracle
  schema: DATAPORT_OWNER
  host: cpyqsp-db.onsemi.com:1529:CPYQSP
  user: DATAPORT_OWNER
  password: dpownerp321

  pipeline:
    stages:
      - name: cp
        type: CP
        config:
          indexPattern: 'logs*dataport*'
          lookbackBufferSeconds: 900
          timeoutMinutes: 15

      - name: pplog
        type: PPLOG
        dependsOn: [cp]
        config:
          lookbackBufferSeconds: 300
          timeoutMinutes: 10

      - name: exensio
        type: EXENSIO
        dependsOn: [pplog]
        config:
          timeoutMinutes: 60
```

---

## Common Patterns & Utilities

### Getting Config Values with Type Safety

```java
public Integer getConfigAsInteger(Map<String, Object> config, String key, Integer defaultValue) {
    Object value = config.get(key);
    if (value instanceof Integer) {
        return (Integer) value;
    } else if (value instanceof Number) {
        return ((Number) value).intValue();
    }
    return defaultValue;
}
```

### JSON Array Parsing (completed_pipeline_stages)

```java
// Store as: ["cp", "pplog"]
Set<String> completedStages = new HashSet<>(
    Arrays.asList(completedPipelineStagesJson
        .replaceAll("[\\[\\]\"]", "")
        .split(","))
);
```

### JSON Object Parsing (stage_metadata)

```java
ObjectMapper mapper = new ObjectMapper();
Map<String, Object> metadata = mapper.readValue(
    stageMetadataClob,
    new TypeReference<Map<String, Object>>() {}
);
```

### Atomic State Updates

```java
// Use RefDbService.executeBatchUpdate() for atomicity
Map<String, Object> updates = new HashMap<>();
updates.put("current_pipeline_stage", nextStage);
updates.put("completed_pipeline_stages", newCompletedArray);
updates.put("stage_metadata", newMetadata);
updates.put("last_stage_check_at", Instant.now());

refDbService.executeBatchUpdate("SENDER_STAGE", recordId, updates);
```

---

## Troubleshooting Checklist

- [ ] All handler implementations marked with `@Component`
- [ ] Cache invalidation called when configuration changes
- [ ] Timeout calculations use correct units (milliseconds vs seconds)
- [ ] JSON serialization/deserialization uses consistent ObjectMapper
- [ ] SSE events emitted for all status transitions
- [ ] Integration status updated via IntegrationStatusService
- [ ] All database updates wrapped in transactions
- [ ] Null checks for optional fields (dependsOn, config values)
- [ ] Proper error logging with recordId, site, stageName context
- [ ] Property tests use @ForAll annotations correctly
