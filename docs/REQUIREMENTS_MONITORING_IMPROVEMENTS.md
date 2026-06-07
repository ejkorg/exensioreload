# Requirements for Elasticsearch and Exensio Monitoring Improvements

## 1. Introduction
This document outlines the requirements for enhancing the robustness, efficiency, and adherence to best practices of the Elasticsearch (ES) and Exensio monitoring components in ExensioReload.

## 2. Functional Requirements

### 2.1 Elasticsearch Monitoring
1. **Connection Management**
   - SHALL use HTTP connection pooling with configurable max connections.
   - SHALL support connection and socket timeouts.
   - SHALL validate ES URL configuration at startup.

2. **Query Optimization**
   - SHALL support bulk queries for multiple records when beneficial.
   - SHALL include request tracing IDs in ES queries for debugging.
   - SHALL avoid logging full request payloads in production.

3. **Error Handling**
   - SHALL implement circuit breaker pattern for ES failures.
   - SHALL differentiate between transient (network, timeout) and permanent failures.
   - SHALL implement exponential backoff for retries on transient failures.
   - SHALL move records to a dead letter queue after exceeding failure threshold.

4. **Logging & Observability**
   - SHALL log query execution with structured fields (queryId, recordId, duration, hitsFound, status).
   - SHALL expose health check endpoint for ES connectivity and response times.
   - SHALL collect metrics (query duration, success/failure counts, circuit breaker state).

### 2.2 Exensio Monitoring
1. **Connection Management**
   - SHALL use HTTP connection pooling with configurable limits.
   - SHALL support connection and socket timeouts.
   - SHALL validate Exensio URL and credentials at startup.

2. **Batch Processing Optimization**
   - SHALL group lookup requests by lot to minimize API calls.
   - SHALL respect configured batch-size and max-concurrent-requests limits.
   - SHALL support adaptive polling intervals based on load.

3. **Error Handling**
   - SHALL implement circuit breaker pattern for Exensio API failures.
   - SHALL implement exponential backoff for retries on transient failures.
   - SHALL distinguish between missing data (immediate failure) and service issues (retryable).
   - SHALL move records to dead letter queue after exceeding failure threshold.

4. **Logging & Observability**
   - SHALL log API calls with structured fields (callId, lot, waferIds, duration, status, retryCount).
   - SHALL expose health check endpoint for Exensio connectivity and API response times.
   - SHALL collect metrics (API call duration, success/failure counts, circuit breaker state, cache hit ratio).

### 2.3 Shared Requirements
1. **Configuration**
   - SHALL support environment-specific overrides via profile YAML files.
   - SHALL provide clear documentation for all configuration properties.
   - SHALL validate required properties at application startup.

2. **Resource Management**
   - SHALL properly close HTTP clients and Elasticsearch RestClients on application shutdown.
   - SHALL avoid resource leaks in error paths.

3. **Operational Excellence**
   - SHALL provide runbooks for common failure scenarios.
   - SHALL include troubleshooting guides in documentation.
   - SHALL support graceful degradation when one monitoring service is unavailable.

## 3. Non-Functional Requirements

### 3.1 Performance
- The monitoring system SHALL add no more than 50ms overhead per record under normal load.
- ES query latency SHALL not exceed configured socket timeout (default 30s).
- Exensio API call latency SHALL not exceed configured socket timeout (default 30s).
- The system SHALL support processing of at least 1000 records per minute with current hardware.

### 3.2 Scalability
- Connection pools SHALL be sized to handle peak concurrent loads without exhaustion.
- The monitoring services SHALL be stateless except for configured caches and circuit-breaker states.
- Vertical scaling SHALL improve throughput linearly up to hardware limits.

### 3.3 Reliability
- The system SHALL achieve 99.9% uptime for monitoring services when dependencies are healthy.
- Failed records SHALL not cause system instability or memory leaks.
- Circuit breakers SHALL prevent cascading failures and allow fast recovery.

### 3.4 Maintainability
- Code SHALL follow existing code style and patterns in the ExensioReload codebase.
- All new code SHALL include unit tests covering happy paths, error cases, and edge cases.
- Configuration changes SHALL be possible without application restart where feasible (for non-critical properties).
- Logging SHALL use appropriate levels (DEBUG, INFO, WARN, ERROR) and avoid excessive verbosity in production.

## 4. Acceptance Criteria
1. All new functionality SHALL be covered by unit tests with ≥80% code coverage.
2. Manual testing SHALL verify:
   - Correct state transitions for success, timeout, and failure scenarios.
   - Proper circuit breaker operation under simulated failure conditions.
   - Effective batching and caching reducing external service calls.
   - Graceful degradation when one service is unavailable.
3. Performance testing SHALL confirm the system meets throughput and latency requirements.
4. Documentation SHALL be updated to reflect new configuration options and operational procedures.
5. No regressions SHALL be introduced in existing monitoring functionality.

## 5. Dependencies
- None beyond existing ExensioReload dependencies (Spring Boot, Elasticsearch REST client, Apache HttpClient, etc.)

## 6. Open Questions
1. Should we implement a shared caching layer between ES and Exensio lookups?
2. What should be the default values for new timeout and pool size configurations?
3. Should we expose additional Prometheus metrics endpoints or rely on Spring Boot Actuator?