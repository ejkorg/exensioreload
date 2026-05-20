# Requirements Document: Exensio Loading API Batch & Parallel Processing Optimization

## Introduction

This feature optimizes the Exensio Loading API integration to handle high-volume lot/wafer lookups efficiently. The current implementation processes records sequentially (one API call per record), which takes ~67 minutes for 20k records. This optimization introduces batch processing and parallel execution to reduce processing time from 67 minutes to ~5-7 minutes while maintaining data integrity and error handling.

## Glossary

- **Batch Processing**: Combining multiple lot/wafer lookups into a single API request
- **Parallel Processing**: Processing multiple batches concurrently using thread pools
- **Batch Size**: Number of lot/wafer combinations included in a single API request
- **Thread Pool**: Fixed-size pool of worker threads for concurrent processing
- **Circuit Breaker**: Pattern to prevent cascading failures when API is unavailable
- **Backpressure**: Mechanism to limit concurrent requests to prevent overwhelming the API
- **Idempotency**: Ensuring duplicate processing of the same record produces the same result

## Requirements

### Requirement 1: Batch Processing

**User Story:** As a system operator, I want the Exensio monitor to batch multiple lot/wafer lookups into single API requests, so that processing time is reduced from 67 minutes to under 10 minutes for 20k records.

#### Acceptance Criteria

1. THE Exensio Monitor SHALL group records into batches based on the configured batch size
2. WHEN a batch is created, THE Exensio Monitor SHALL include all lot IDs and wafer IDs in a single API request
3. THE Exensio Monitor SHALL parse batch responses and update each record individually based on the response
4. WHERE the batch size is configured to 1, THE Exensio Monitor SHALL process records individually (backward compatible)
5. THE Exensio Monitor SHALL handle partial batch failures by marking only failed records as ERROR
6. WHEN a batch API call fails, THE Exensio Monitor SHALL retry individual records from that batch
7. THE Exensio Monitor SHALL log batch processing metrics (batch size, processing time, success rate)

### Requirement 2: Parallel Processing

**User Story:** As a system operator, I want the Exensio monitor to process multiple batches concurrently, so that processing time is further reduced through parallel execution.

#### Acceptance Criteria

1. THE Exensio Monitor SHALL use a fixed-size thread pool for concurrent batch processing
2. THE thread pool size SHALL be configurable via application properties
3. WHERE the thread pool size is configured to 1, THE Exensio Monitor SHALL process batches sequentially (backward compatible)
4. THE Exensio Monitor SHALL wait for all threads to complete before finishing the poll cycle
5. THE Exensio Monitor SHALL handle thread pool shutdown gracefully on application shutdown
6. THE Exensio Monitor SHALL log thread pool metrics (active threads, queue size, completed tasks)
7. THE Exensio Monitor SHALL limit concurrent API requests to prevent overwhelming the Exensio server

### Requirement 3: Configuration

**User Story:** As a system administrator, I want to configure batch size and thread pool size independently, so that I can tune performance based on Exensio server capacity and network conditions.

#### Acceptance Criteria

1. THE application SHALL provide a configuration property for batch size (default: 50)
2. THE application SHALL provide a configuration property for thread pool size (default: 5)
3. THE application SHALL provide a configuration property for maximum concurrent requests (default: 10)
4. THE application SHALL validate that batch size is between 1 and 100
5. THE application SHALL validate that thread pool size is between 1 and 20
6. THE application SHALL validate that maximum concurrent requests is between 1 and 50
7. THE application SHALL log configuration values on startup

### Requirement 4: Error Handling and Resilience

**User Story:** As a system operator, I want the Exensio monitor to handle API failures gracefully, so that temporary outages do not cause permanent record failures.

#### Acceptance Criteria

1. WHEN a batch API call fails with HTTP 5xx, THE Exensio Monitor SHALL retry individual records from that batch
2. WHEN a batch API call fails with HTTP 429 (rate limit), THE Exensio Monitor SHALL back off and retry after a delay
3. WHEN the Exensio API is unreachable, THE Exensio Monitor SHALL skip the poll cycle and retry on the next cycle
4. THE Exensio Monitor SHALL implement a circuit breaker to prevent cascading failures
5. WHEN the circuit breaker is open, THE Exensio Monitor SHALL skip API calls and log a warning
6. THE circuit breaker SHALL close automatically after a configured recovery period
7. THE Exensio Monitor SHALL track and log error rates per poll cycle

### Requirement 5: Database Optimization

**User Story:** As a system operator, I want the Exensio monitor to minimize database queries and updates, so that database load is reduced during high-volume processing.

#### Acceptance Criteria

1. THE Exensio Monitor SHALL load records in a single database query per poll cycle
2. THE Exensio Monitor SHALL use batch updates to update multiple records in a single database transaction
3. THE Exensio Monitor SHALL commit database updates in batches of 100 records
4. WHEN a database update fails, THE Exensio Monitor SHALL retry the failed records individually
5. THE Exensio Monitor SHALL use prepared statements for all database operations
6. THE Exensio Monitor SHALL log database operation metrics (query time, update time, batch size)

### Requirement 6: Monitoring and Observability

**User Story:** As a system operator, I want detailed metrics and logs for Exensio processing, so that I can monitor performance and troubleshoot issues.

#### Acceptance Criteria

1. THE Exensio Monitor SHALL log the start and end of each poll cycle with record counts
2. THE Exensio Monitor SHALL log batch processing metrics (batches processed, records per batch, success rate)
3. THE Exensio Monitor SHALL log thread pool metrics (active threads, queue size, completed tasks)
4. THE Exensio Monitor SHALL log API call metrics (total calls, success rate, average response time)
5. THE Exensio Monitor SHALL log database metrics (query time, update time, batch size)
6. THE Exensio Monitor SHALL expose metrics via JMX for external monitoring tools
7. THE Exensio Monitor SHALL log warnings when processing time exceeds 80% of the poll interval

### Requirement 7: Backward Compatibility

**User Story:** As a system administrator, I want the optimization to be backward compatible, so that existing deployments continue to work without configuration changes.

#### Acceptance Criteria

1. WHERE batch size is not configured, THE Exensio Monitor SHALL default to 50
2. WHERE thread pool size is not configured, THE Exensio Monitor SHALL default to 5
3. WHERE batch size is configured to 1, THE Exensio Monitor SHALL process records individually (legacy behavior)
4. WHERE thread pool size is configured to 1, THE Exensio Monitor SHALL process batches sequentially (legacy behavior)
5. THE Exensio Monitor SHALL maintain the same database schema (no schema changes required)
6. THE Exensio Monitor SHALL maintain the same API contract with ExensioClient
7. THE Exensio Monitor SHALL maintain the same SSE event broadcasting behavior

### Requirement 8: Performance Targets

**User Story:** As a system operator, I want the optimization to meet specific performance targets, so that I can process 20k records within acceptable time limits.

#### Acceptance Criteria

1. THE Exensio Monitor SHALL process 20k records in under 10 minutes (batch size 50, thread pool 5)
2. THE Exensio Monitor SHALL process 20k records in under 7 minutes (batch size 100, thread pool 10)
3. THE Exensio Monitor SHALL maintain API success rate above 95% under normal conditions
4. THE Exensio Monitor SHALL maintain database update success rate above 99%
5. THE Exensio Monitor SHALL use less than 500MB of heap memory during processing
6. THE Exensio Monitor SHALL complete each poll cycle within the configured poll interval
7. THE Exensio Monitor SHALL handle 50k records without running out of memory

### Requirement 9: Thread Safety

**User Story:** As a system developer, I want all shared state to be thread-safe, so that concurrent processing does not cause data corruption or race conditions.

#### Acceptance Criteria

1. THE Exensio Monitor SHALL use thread-safe collections for shared state
2. THE Exensio Monitor SHALL use atomic operations for counters and metrics
3. THE Exensio Monitor SHALL use synchronized blocks or locks for critical sections
4. THE Exensio Monitor SHALL avoid shared mutable state where possible
5. THE Exensio Monitor SHALL use immutable objects for record data
6. THE Exensio Monitor SHALL handle concurrent database updates without deadlocks
7. THE Exensio Monitor SHALL pass thread safety tests with 100 concurrent threads

### Requirement 10: Graceful Degradation

**User Story:** As a system operator, I want the Exensio monitor to degrade gracefully under high load, so that the system remains stable even when processing capacity is exceeded.

#### Acceptance Criteria

1. WHEN the thread pool queue is full, THE Exensio Monitor SHALL process remaining batches in the next poll cycle
2. WHEN API response time exceeds 5 seconds, THE Exensio Monitor SHALL reduce batch size automatically
3. WHEN error rate exceeds 10%, THE Exensio Monitor SHALL reduce thread pool size automatically
4. WHEN memory usage exceeds 80%, THE Exensio Monitor SHALL reduce batch size and thread pool size
5. THE Exensio Monitor SHALL log warnings when degradation occurs
6. THE Exensio Monitor SHALL restore normal operation when conditions improve
7. THE Exensio Monitor SHALL never crash or hang under high load
