# Design for Elasticsearch and Exensio Monitoring Improvements

## 1. Overview
This document describes the technical design for enhancing the Elasticsearch (ES) and Exensio monitoring components in ExensioReload to improve robustness, efficiency, and adherence to best practices.

## 2. Goals
- Improve fault tolerance via circuit breakers, retries with exponential backoff, and dead letter queues.
- Increase efficiency through connection pooling, batching, caching, and adaptive polling.
- Enhance observability with structured logging, health checks, and metrics.
- Ensure clean resource management and graceful degradation.
- Maintain backward compatibility and follow existing code patterns.

## 3. Architecture Overview
The monitoring system consists of:
1. `SenderQueueMonitor` – detects queue consumption and transitions records.
2. `CpLogMonitor` – polls Elasticsearch for CP enrichment verification.
3. `ExensioLoadMonitor` – polls Exensio API for lot/wafer verification.
4. Supporting services: `ElasticsearchLogService`, `ExensioAuthService`, `ExensioClient`.

Changes will focus on:
- `CpLogMonitor` and `ElasticsearchLogService` (ES side)
- `ExensioLoadMonitor`, `ExensioAuthService`, and `ExensioClient` (Exensio side)
- Shared utilities for circuit breaker, retry, caching, and connection management.

## 4. Component Designs

### 4.1 ElasticsearchLogService Enhancements
#### Responsibilities
- Build and execute Elasticsearch search queries.
- Parse responses into success/failure/not_found outcomes.
- Interface used by `CpLogMonitor`.

#### Changes
1. **Connection Pooling & Timeouts**
   - Use `RestClient.Builder` to configure:
     - Connection and socket timeouts (configurable).
     - Max connections total and per route.
     - Connection time-to-live.

2. **Request Tracing**
   - Generate a UUID for each search request.
   - Include it in the request body under `extraSource` or as a custom header if supported.
   - Log the traceId with query execution.

3. **Circuit Breaker**
   - Wrap the search operation with a circuit breaker (e.g., resilience4j or Spring Retry).
   - On failure beyond threshold, open circuit and return fallback (empty result, treated as not_found).
   - After reset timeout, allow trial requests.

4. **Bulk Query Support (Optional)**
   - Add method `searchMultiple(List<SearchCriteria>)` that builds a `_msearch` request.
   - Used by scheduler if monitoring many records in one cycle (to be evaluated for complexity).

5. **Payload Logging Control**
   - Respect `logRequestPayloads` flag; default false in production profiles.

#### Interface (unchanged)
```java
public Optional<ElasticsearchResult> searchLogs(SearchCriteria criteria);
```

### 4.2 Exensio Client & Auth Enhancements
#### Responsibilities
- Handle authentication token acquisition and renewal.
- Execute lot-wafer lookup API calls.
- Interface used by `ExensioLoadMonitor`.

#### Changes
1. **HTTP Connection Pooling**
   - Use Apache HttpClient's `PoolingHttpClientConnectionManager`.
   - Configure max total connections, max per route, connection and socket timeouts.
   - Evict idle connections periodically.

2. **Token Caching with Thread Safety**
   - Cache token and expiry time.
   - Use `ReadWriteLock` or `AtomicReference` for thread-safe updates.
   - On 401, attempt refresh once per thread (avoid thundering herd).

3. **Circuit Breaker**
   - Apply circuit breaker around API calls (login and lookup).
   - Separate breaker for login and lookup if desired.
   - Fallback: treat service as unavailable, records stay in EXENSIO_LOADING until breaker half-open.

4. **Batch Optimization**
   - Modify `ExensioLoadMonitor` to collect records in EXENSIO_LOADING.
   - Group by lot (since PGC key is often 1) and build batch requests with multiple wafer IDs.
   - Respect `batch-size` and `max-concurrent-requests` (if parallelizing batches).

5. **Adaptive Polling (Stretch)**
   - Monitor the number of records in EXENSIO_LOADING and recent failure rates.
   - Adjust the actual delay between monitor runs within bounds (`min-poll-interval-ms` to `max-poll-interval-ms`).
   - Implement in `ExensioLoadMonitor` scheduler using a dynamic fixed delay.

6. **Structured Logging**
   - Log each API call with: traceId (generated), lot, waferIds count, attempt, duration, status, error if any.
   - Use MDC or structured logging arguments.

#### Interface Additions (to ExensioClient)
```java
// Existing: String lookupWafer(String lot, String wafer)
// New: supports batch
public List<LookupResult> lookupWaferBatch(String lot, List<String> waferIds);
```

### 4.3 Monitoring Scheduler Enhancements
#### CpLogMonitor
- Changes are primarily in the service it calls (`ElasticsearchLogService`).
- No change to scheduling logic unless adding adaptive polling (similar to Exensio).

#### ExensioLoadMonitor
1. **Batch Processing**
   - On each trigger, fetch all EXENSIO_LOADING records.
   - Group by lot (and optionally PGC key).
   - For each group, call `lookupWaferBatch` with collected wafer IDs.
   - Match results back to individual records.

2. **Retry with Exponential Backoff per Record**
   - Maintain a map `recordId -> failureCount`.
   - On transient failure (network, timeout, 5xx), increment count and schedule retry after `base * 2^failureCount` (capped).
   - On permanent failure (400, missing data), move to FAILED immediately.
   - Clear failure count on success.

3. **Dead Letter Queue**
   - After `maxFailureCount` (configurable, default 5), move record to a DLQ table or status.
   - Log and optionally alert (e.g., via audit service).

### 4.4 Cross-Cutting Concerns

#### Circuit Breaker Implementation
- Use resilience4j-spring-boot2 or Spring Retry if already in dependencies.
- Configure per-service (ES, Exensio-login, Exensio-lookup) with settings from YAML:
  - failureThreshold, waitDurationInOpenState, permittedNumberOfCallsInHalfOpenState.

#### Retry with Exponential Backoff
- Implement a utility `RetryExecutor` that takes a callable, max attempts, base delay, max delay.
- Use in service layers where appropriate (ES search, Exensio calls).
- Distinguish between retryable and non-retryable exceptions.

#### Caching Layer (Exensio)
- Add a `LoadingCache` (from Caffeine) for recent successful lot-wafer lookups.
- Key: `lot:wafer` (or include PGC key if varies).
- Value: lookup result with timestamp.
- Expire after write (e.g., 5 minutes) to handle eventual consistency.
- Max size configurable (e.g., 1000).
- Statistics logged periodically.

#### Health Checks
- Implement Spring Boot Actuator health indicators:
  - `ElasticsearchHealthIndicator`: performs a lightweight search (e.g., count) or checks client connectivity.
  - `ExensioHealthIndicator`: attempts a token fetch (or lightweight endpoint if available) and measures response time.
- Include details: status, response time, error message if down.

#### Metrics
- Use Micrometer (already likely present via Spring Boot).
- Timers:
  - `elasticsearch.query.duration`
  - `exensio.api.call.duration`
- Counters:
  - `elasticsearch.query.success`, `elasticsearch.query.failure`
  - `exensio.api.success`, `exensio.api.failure`
  - `exensio.cache.hits`, `exensio.cache.misses`
- Circuit breaker state as gauge (if using resilience4j, it already binds metrics).

#### Resource Cleanup
- Implement `@PreDestroy` in service beans to close:
  - Elasticsearch RestClient.
  - Exensio HttpClient and connection manager.
- Ensure no leaks in error paths.

## 5. Configuration Additions
Add to `application.yml` under respective sections:

### Elasticsearch
```yaml
cp:
  elasticsearch:
    # Existing...
    connection-timeout-ms: 5000
    socket-timeout-ms: 30000
    max-connections: 20
    max-connections-per-route: 20
    connection-time-to-live-seconds: 60
    enable-circuit-breaker: true
    circuit-breaker-threshold: 3
    circuit-breaker-reset-ms: 30000
    # logRequestPayloads: false (already, but ensure default false in production)
```

### Exensio
```yaml
exensio:
  # Existing...
  connection-timeout-ms: 10000
  socket-timeout-ms: 30000
  max-connections: 40
  max-connections-per-route: 20
  enable-circuit-breaker: true
  circuit-breaker-threshold: 5
  circuit-breaker-reset-ms: 60000
  # Adaptive polling (stretch)
  adaptive-polling-enabled: false
  min-poll-interval-ms: 30000
  max-poll-interval-ms: 300000
  # Batch sizes already exist
  # Cache
  cache-enabled: true
  cache-maximum-size: 1000
  cache-expire-after-write-minutes: 5
```

### Monitoring Common
```yaml
# Optional: global retry settings
retry:
  base-delay-ms: 1000
  max-delay-ms: 30000
  max-attempts: 3
# Dead letter queue threshold
monitoring:
  dead-letter-threshold: 5
```

## 6. Data Flow Illustrations

### 6.1 Successful ES then Exensio Flow
1. File dispatched, queue consumed -> `SenderQueueMonitor` sees ES enabled -> marks ENRICHMENT.
2. `CpLogMonitor` triggers, calls `ElasticsearchLogService.searchLogs`.
   - Service builds query with tracing ID, uses pooled connection.
   - Returns success with output path.
3. `CpLogMonitor` updates record with cp_output_path, sees Exensio enabled -> transitions to EXENSIO_LOADING.
4. `ExensioLoadMonitor` triggers, batches EXENSIO_LOADING records by lot.
   - For each lot, checks cache; misses go to `ExensioClient.lookupWaferBatch`.
   - Client uses pooled HttpClient, includes auth token (cached).
   - API returns wafer keys.
5. Monitor updates records with exensio keys, transitions to DONE.

### 6.2 Failure and Retry Flow (Exensio transient error)
1. Record in EXENSIO_LOADING, monitor triggers.
2. Batch lookup attempt throws `SocketTimeoutException`.
3. Utility retries with exponential backoff (after 1s, then 2s, then 4s) up to max attempts.
4. If still failing after max attempts, increment failure count.
5. If failure count < threshold, record remains EXENSIO_LOADING; next monitor cycle will retry (with backoff applied via scheduler delay or per-record delay).
6. If failure count reaches threshold, move to dead letter queue.

## 7. Implementation Considerations
- **Backward Compatibility**: All changes are internal to services or additive configuration. Existing behavior preserved when new features disabled.
- **Thread Safety**: Connection pools, caches, and circuit breakers are thread-safe. Per-record state (failure counts) must be synchronized (use `ConcurrentHashMap`).
- **Performance**: Connection pooling reduces overhead. Batching reduces API call count. Caching avoids redundant lookups.
- **Observability**: Structured logging, health endpoints, and metrics enable rapid troubleshooting.

## 8. Open Issues
1. Whether to implement bulk Elasticsearch search (`_msearch`) – may add complexity for limited gain given typical monitor intervals.
2. Exact placement of retry logic: in service layer vs. monitor scheduler.
3. Whether to use resilience4j or Spring Retry for circuit breaker – depends on existing dependencies.
4. How to handle adaptive polling without conflicting with Spring's `@Scheduled` fixed delay; may need to use a custom scheduler or adjust delay dynamically.

## 9. Conclusion
This design introduces robustness (circuit breakers, retries, dead letter queues), efficiency (connection pooling, batching, caching, adaptive polling), and observability (structured logging, health checks, metrics) while maintaining the existing architecture and configuration style. Implementation will follow the existing codebase patterns and be guided by the requirements document.
