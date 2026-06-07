# Implementation Tasks for Elasticsearch and Exensio Monitoring Improvements

This document lists the specific tasks required to implement the monitoring improvements outlined in the requirements and design documents.

## 1. Elasticsearch Monitoring Improvements

### 1.1 ElasticsearchLogService Updates
- [x] Add connection pooling configuration (max connections, timeouts) to `ElasticsearchLogService`.
- [x] Implement request tracing ID generation and inclusion in ES queries.
- [x] Add circuit breaker pattern around ES search operations.
- [ ] Ensure `logRequestPayloads` defaults to false in production profiles.
- [ ] Add unit tests for new functionality (connection pooling, tracing, circuit breaker).
- [x] Update `application.yml` documentation for new ES properties.

### 1.2 CpLogMonitor Updates
- [x] No direct changes needed if service handles improvements; verify integration.
- [x] Add logging for circuit breaker state changes (if applicable).
- [x] Ensure proper error handling and state transitions on circuit breaker open.
- [x] Add traceId to all status messages emitted to IntegrationStatusService for UI display.

### 1.3 Configuration and Validation
- [ ] Add new ES configuration properties to `CpElasticsearchProperties`:
    - connection-timeout-ms
    - socket-timeout-ms
    - max-connections
    - max-connections-per-route
    - connection-time-to-live-seconds
    - enable-circuit-breaker
    - circuit-breaker-threshold
    - circuit-breaker-reset-ms
- [ ] Add validation in `@PostConstruct` to check ES URL when enabled.
- [ ] Update documentation in `docs/INTEGRATION_ES_EXENSIO.md` for new properties.

## 2. Exensio Monitoring Improvements

### 2.1 ExensioClient and AuthService Updates
- [x] Implement HTTP connection pooling in `ExensioClient` (using PoolingHttpClientConnectionManager).
- [x] Add thread-safe token caching with exponential backoff on refresh failure.
- [x] Add circuit breaker pattern for login and lookup API calls.
- [x] Implement batch lookup method (`lookupWaferBatch`) that accepts multiple wafer IDs.
- [x] Add structured logging for API calls (traceId, lot, waferIds, duration, status).
- [x] Add retry with exponential backoff for transient failures (network, timeout, 5xx).
- [ ] Add unit tests for connection pooling, token caching, circuit breaker, batch lookup, and retry logic.

### 2.2 ExensioLoadMonitor Updates
- [x] Modify monitor to batch EXENSIO_LOADING records by lot (and PGC key if varies).
- [x] Implement caching layer (Caffeine) for recent successful lot-wafer lookups.
- [x] Add retry with exponential backoff for transient failures (network, timeout, 5xx).
- [x] Implement dead letter queue mechanism after configurable failure threshold.
- [x] Add adaptive polling logic (stretch goal) based on load and failure rates. (Note: implemented as configuration foundation)
- [x] Ensure proper cleanup of HTTP client and connection manager on shutdown.
- [x] Add traceId to all status messages emitted to IntegrationStatusService for UI display.
- [ ] Add unit tests for batching, caching, retry, and dead letter queue.

### 2.3 Configuration and Validation
- [x] Add new Exensio configuration properties to `ExensioProperties`:
    - connection-timeout-ms
    - socket-timeout-ms
    - max-connections
    - max-connections-per-route
    - enable-circuit-breaker
    - circuit-breaker-threshold
    - circuit-breaker-reset-ms
    - adaptive-polling-enabled (stretch)
    - min-poll-interval-ms (stretch)
    - max-poll-interval-ms (stretch)
    - cache-enabled
    - cache-maximum-size
    - cache-expire-after-write-minutes
    - retry-max-attempts
    - retry-base-delay-ms
    - dead-letter-queue-threshold
- [x] Add validation in `@PostConstruct` to check required properties when enabled.
- [x] Update documentation in `docs/INTEGRATION_ES_EXENSIO.md` for new properties.

## 3. Shared Improvements

### 3.1 Observability
- [x] Add Micrometer timers and counters for ES and Exensio operations (via JMX metrics).
- [x] Implement Spring Boot Actuator health indicators for ES and Exensio.
- [x] Ensure structured logging uses appropriate levels and includes correlation IDs.
- [x] Add metrics for cache hit ratio (Exensio) and circuit breaker state.

### 3.2 Resource Management
- [x] Add `@PreDestroy` methods to close Elasticsearch RestClient and Exensio HttpClient.
- [x] Ensure no resource leaks in error paths (try-with-resources or finally blocks).

### 3.3 Documentation and Runbooks
- [ ] Update `docs/INTEGRATION_ES_EXENSIO.md` with new configuration properties and their descriptions.
- [ ] Create a troubleshooting section for common issues (circuit breaker open, connection pool exhaustion, etc.).
- [ ] Add a runbook for monitoring and alerting on the new health endpoints and metrics.

## 4. Testing
- [ ] Write unit tests for all new and modified classes (aim for >80% coverage).
- [ ] Write integration tests that simulate success, timeout, and failure scenarios.
- [ ] Perform load testing to verify improved efficiency under peak load.
- [ ] Test chaos scenarios (network partitions, service downtime) to verify robustness.

## 5. Deployment and Rollout
- [ ] Ensure backward compatibility: existing behavior preserved when new features are disabled via configuration.
- [ ] Prepare release notes detailing new configuration options and behavior changes.
- [ ] Verify that default configuration values are sensible and documented.

---
*Note: Tasks marked as [stretch] are optional and can be deferred based on time constraints.*
