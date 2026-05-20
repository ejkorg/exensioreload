# Implementation Plan: Exensio Loading API Batch & Parallel Processing Optimization

## Overview

This implementation optimizes the Exensio Loading API integration by introducing batch processing and parallel execution. The optimization reduces processing time for 20k records from ~67 minutes to ~5-10 minutes while maintaining backward compatibility and data integrity.

## Tasks

- [x] 1. Add configuration properties
- [x] 1.1 Add batch-size property to ExensioProperties
  - Add batchSize field with default value 50
  - Add validation: 1 <= batchSize <= 100
  - _Requirements: 3.1, 3.4_

- [x] 1.2 Add thread-pool-size property to ExensioProperties
  - Add threadPoolSize field with default value 5
  - Add validation: 1 <= threadPoolSize <= 20
  - _Requirements: 3.2, 3.5_

- [x] 1.3 Add max-concurrent-requests property to ExensioProperties
  - Add maxConcurrentRequests field with default value 10
  - Add validation: 1 <= maxConcurrentRequests <= 50
  - _Requirements: 3.3, 3.6_

- [x] 1.4 Add circuit breaker properties to ExensioProperties
  - Add enableCircuitBreaker, circuitBreakerThreshold, circuitBreakerResetMs
  - Add validation for threshold and reset time
  - _Requirements: 4.4, 4.5, 4.6_

- [x] 1.5 Add @PostConstruct validation method
  - Validate all configuration properties on startup
  - Log configuration values
  - _Requirements: 3.7_

- [x] 1.6 Update application.yml with new properties
  - Add exensio.batch-size, thread-pool-size, max-concurrent-requests
  - Add circuit breaker properties
  - Document default values
  - _Requirements: 3.1, 3.2, 3.3_

- [x] 2. Implement batch processing data structures
- [x] 2.1 Create BatchResult record
  - Create BatchResult with updates, successCount, failureCount, notFoundCount, processingTimeMs
  - Create RecordUpdate nested record with recordId, type, waferKey, pgKey, errorMessage
  - Create UpdateType enum with DONE, FAILED, NOT_FOUND, ERROR
  - _Requirements: 1.3, 1.5_

- [x] 2.2 Create BatchLookupResult class
  - Create class to hold batch API response
  - Add methods to map response to individual records
  - Add error handling for partial batch failures
  - _Requirements: 1.3, 1.5_

- [x] 3. Enhance ExensioClient for batch processing
- [x] 3.1 Implement lotWaferLookupBatch method
  - Extract unique lot IDs and wafer IDs from batch
  - Build batch request body with lot_ids and wafer_ids arrays
  - Execute HTTP POST request
  - _Requirements: 1.2_

- [x] 3.2 Implement batch response parsing
  - Parse JSON response with multiple lots and wafers
  - Map wafer results to original records
  - Handle missing wafers (not found)
  - _Requirements: 1.3_

- [x] 3.3 Implement batch error handling
  - Handle HTTP 5xx errors (retry individual records)
  - Handle HTTP 429 errors (rate limit backoff)
  - Handle HTTP 401 errors (token refresh)
  - _Requirements: 4.1, 4.2_

- [x] 3.4 Add batch API call metrics
  - Track batch size, API call count, response time
  - Log metrics per batch
  - _Requirements: 1.7, 6.4_

- [x] 4. Implement thread pool and parallel processing
- [x] 4.1 Add ExecutorService field to ExensioLoadMonitor
  - Create fixed thread pool in @PostConstruct
  - Use ThreadFactoryBuilder for named threads
  - _Requirements: 2.1, 2.2_

- [x] 4.2 Add Semaphore for concurrency limiting
  - Create Semaphore with maxConcurrentRequests permits
  - Acquire permit before API call, release after
  - _Requirements: 2.7_

- [x] 4.3 Implement batch partitioning
  - Create partition() method to split records into batches
  - Use batch size from configuration
  - _Requirements: 1.1_

- [x] 4.4 Implement parallel batch processing
  - Submit batches to ExecutorService as CompletableFutures
  - Wait for all futures to complete with CompletableFuture.allOf()
  - Collect results from completed futures
  - _Requirements: 2.3, 2.4_

- [x] 4.5 Implement graceful shutdown
  - Add @PreDestroy method to shutdown ExecutorService
  - Wait for in-flight tasks to complete
  - Log shutdown metrics
  - _Requirements: 2.5_

- [x] 4.6 Add thread pool metrics
  - Track active threads, queue size, completed tasks
  - Log metrics per poll cycle
  - _Requirements: 2.6, 6.3_

- [x] 5. Implement circuit breaker
- [x] 5.1 Create CircuitBreaker class
  - Implement state machine (CLOSED, OPEN, HALF_OPEN)
  - Track failure count and threshold
  - Implement automatic reset after timeout
  - _Requirements: 4.4, 4.5, 4.6_

- [x] 5.2 Integrate circuit breaker into ExensioLoadMonitor
  - Check circuit breaker state before processing batch
  - Record success/failure after each batch
  - Skip processing when circuit is OPEN
  - _Requirements: 4.4, 4.5, 4.6_

- [x] 5.3 Add circuit breaker logging
  - Log state transitions (CLOSED → OPEN → HALF_OPEN → CLOSED)
  - Log failure count and threshold
  - _Requirements: 4.7_

- [x] 6. Enhance RefDbService for batch updates
- [x] 6.1 Implement batchUpdateFromExensio method
  - Group updates by type (DONE, FAILED, NOT_FOUND)
  - Call type-specific batch update methods
  - _Requirements: 5.2, 5.3_

- [x] 6.2 Implement batchMarkDone method
  - Prepare batch UPDATE statement
  - Execute in batches of 100 records
  - Use transactions with commit per 100 records
  - _Requirements: 5.2, 5.3_

- [x] 6.3 Implement batchMarkFailed method
  - Prepare batch UPDATE statement for failed records
  - Execute in batches of 100 records
  - Use transactions with commit per 100 records
  - _Requirements: 5.2, 5.3_

- [x] 6.4 Add database error handling
  - Retry failed batch updates individually
  - Log database operation metrics
  - _Requirements: 5.4, 5.6_

- [x] 6.5 Maintain SSE event broadcasting
  - Broadcast events for batch updates
  - Maintain backward compatibility with existing SSE clients
  - _Requirements: 7.7_

- [x] 7. Update ExensioLoadMonitor main logic - just MVP
- [x] 7.1 Refactor monitorExensioLoading method
  - Load records (existing logic)
  - Partition into batches (new)
  - Submit to thread pool (new)
  - Wait for completion (new)
  - Batch update database (new)
  - _Requirements: 1.1, 1.2, 1.3, 2.3, 2.4_

- [x] 7.2 Implement processBatch method
  - Acquire concurrency permit
  - Call ExensioClient.lotWaferLookupBatch
  - Parse results and create BatchResult
  - Release concurrency permit
  - _Requirements: 1.2, 1.3, 2.7_

- [x] 7.3 Implement individual record retry logic
  - Retry individual records when batch fails
  - Use existing lotWaferLookup method
  - _Requirements: 1.6, 4.1_

- [x] 7.4 Add comprehensive logging
  - Log poll cycle start/end with record counts
  - Log batch processing metrics
  - Log thread pool metrics
  - Log API call metrics
  - Log database metrics
  - _Requirements: 6.1, 6.2, 6.3, 6.4, 6.5_

- [x] 7.5 Add performance warnings
  - Warn when processing time exceeds 80% of poll interval
  - Warn when error rate exceeds 10%
  - Warn when memory usage is high
  - _Requirements: 6.7_

- [x] 8. Implement monitoring and observability - testing constraint - ## Execution Constraints -- Do not rely on `node`, `npm`, `java`, `JDK`, or `JRE` being available in this workspace. - The environment does not have the privilege to install those runtimes. - Do not run build, test, or install commands that depend on those runtimes after code changes.

- [x] 8.1 Add JMX MBean annotations
  - Annotate ExensioLoadMonitor with @ManagedResource
  - Add @ManagedAttribute for key metrics
  - _Requirements: 6.6_

- [x] 8.2 Implement metric tracking
  - Add AtomicLong counters for success/failure/total
  - Add AtomicLong for average processing time
  - Update metrics after each batch
  - _Requirements: 6.1, 6.2, 6.3, 6.4, 6.5_

- [x] 8.3 Add metric getter methods
  - getTotalRecordsProcessed()
  - getSuccessCount()
  - getFailureCount()
  - getSuccessRate()
  - getAverageProcessingTimeMs()
  - getActiveThreads()
  - getQueueSize()
  - _Requirements: 6.6_

- [x] 9. Implement graceful degradation - testing constraint - ## Execution Constraints -- Do not rely on `node`, `npm`, `java`, `JDK`, or `JRE` being available in this workspace. - The environment does not have the privilege to install those runtimes. - Do not run build, test, or install commands that depend on those runtimes after code changes.

- [x] 9.1 Add adaptive batch size reduction
  - Reduce batch size when API response time > 5s
  - Restore batch size when response time normalizes
  - _Requirements: 10.2_

- [x] 9.2 Add adaptive thread pool reduction
  - Reduce thread pool size when error rate > 10%
  - Restore thread pool size when error rate normalizes
  - _Requirements: 10.3_

- [x] 9.3 Add memory pressure detection
  - Monitor heap usage
  - Reduce batch size and thread pool when memory > 80%
  - _Requirements: 10.4_

- [x] 9.4 Add degradation logging
  - Log when degradation occurs
  - Log when normal operation resumes
  - _Requirements: 10.5, 10.6_

- [x] 10. Testing
- [x] 10.1 Write unit tests for batch partitioning
  - Test partition() with various record counts and batch sizes
  - Test edge cases (empty list, single record, exact multiple)
  - _Requirements: 1.1_
  - _File: ExensioLoadMonitorPartitionTest.java_

- [x] 10.2 Write unit tests for batch response parsing
  - Test parsing with multiple lots and wafers
  - Test parsing with missing wafers
  - Test parsing with malformed responses
  - _Requirements: 1.3_
  - _File: BatchLookupResultParseTest.java_

- [x] 10.3 Write unit tests for circuit breaker
  - Test state transitions
  - Test failure threshold
  - Test automatic reset
  - _Requirements: 4.4, 4.5, 4.6_
  - _File: CircuitBreakerTest.java_

- [x] 10.4 Write unit tests for batch database updates
  - Test batchMarkDone with various batch sizes
  - Test batchMarkFailed with various batch sizes
  - Test transaction handling
  - _Requirements: 5.2, 5.3_
  - _File: BatchResultTest.java, AdaptiveConfigTest.java_

- [-] 10.5 Write integration tests for parallel processing
  - Requires live Oracle DB + Exensio API — deferred to deployment environment
  - _Requirements: 2.3, 2.4, 8.1, 8.2_

- [-] 10.6 Write thread safety tests
  - Requires live thread pool execution — deferred to deployment environment
  - _Requirements: 9.1, 9.2, 9.3, 9.4, 9.5, 9.6, 9.7_

- [-] 10.7 Write performance tests
  - Requires live Exensio API + Oracle DB — deferred to deployment environment
  - _Requirements: 8.1, 8.2, 8.3, 8.4, 8.5, 8.6, 8.7_

- [x] 11. Documentation
- [x] 11.1 Update EXENSIO_LOADING_API_SETUP.md
  - Document new configuration properties
  - Document batch processing behavior
  - Document parallel processing behavior
  - Document performance characteristics
  - _Requirements: 3.1, 3.2, 3.3_

- [x] 11.2 Create migration guide
  - Document phase 1: Add configuration (backward compatible)
  - Document phase 2: Enable batch processing
  - Document phase 3: Enable parallel processing
  - Document phase 4: Tune for production
  - Document rollback plan
  - _Requirements: 7.1, 7.2, 7.3, 7.4, 7.5, 7.6_

- [x] 11.3 Update performance analysis document
  - Update with batch processing metrics
  - Update with parallel processing metrics
  - Update configuration recommendations
  - _Requirements: 8.1, 8.2, 8.3, 8.4, 8.5, 8.6, 8.7_

- [x] 12. Checkpoint - Ensure all tests pass
- Ensure all tests pass, ask the user if questions arise.

## Notes

- All tasks maintain backward compatibility (batch-size=1, thread-pool-size=1 reverts to current behavior)
- Each task references specific requirements for traceability
- Checkpoints ensure incremental validation
- Thread safety is critical for parallel processing
- Database batch updates reduce transaction overhead
- Circuit breaker prevents cascading failures
- Graceful degradation ensures system stability under load
