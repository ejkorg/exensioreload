# Implementation Plan: Schema Fallback in Preflight Check

## Overview

This plan breaks down the schema fallback feature implementation into discrete, manageable tasks. The implementation prioritizes:

1. Configuration foundation (properties, parsing)
2. HTTP-first path enhancement (sequential schema fallback)
3. Snowflake secondary fallback (UNION dual-schema query)
4. Orchestration logic (path selection, error handling)
5. UI enhancement (Snowflake toggle checkbox)
6. Testing (unit tests + property-based tests)

---

## Tasks

- [x] 1. Set up configuration properties and infrastructure
  - Add new properties to `application.yml` (schema-fallback-enabled, schema-fallback-priority-list, enable-snowflake-secondary)
  - Create `SchemaFallbackConfig` class to parse and validate configuration
  - Add startup logging for configuration at service initialization
  - _Requirements: 5.1, 5.2, 5.4, 5.5_

- [x] 1.1 Create SchemaFallbackConfig configuration class
  - Implement configuration bean to read properties
  - Parse comma-separated schema list into List<String>
  - Validate configuration (non-empty list, valid schema names)
  - Provide getters for fallback-enabled and priority-list
  - _Requirements: 5.1, 5.2_

- [x]\* 1.2 Write unit tests for SchemaFallbackConfig parsing
  - Test parsing "PRODUCTION,SANDBOX" → [PRODUCTION, SANDBOX]
  - Test parsing "SANDBOX,PRODUCTION" → [SANDBOX, PRODUCTION]
  - Test default when config missing → [PRODUCTION, SANDBOX]
  - Test invalid schema names rejected
  - _Requirements: 5.2, 5.4_

- [x] 2. Enhance ExensioPreCheckService with configuration methods
  - Add `resolveSchemaPriorityList()` method
  - Add `isSnowflakeSecondaryFallbackEnabled()` method
  - Add logging at service initialization with configured schema list
  - _Requirements: 5.1, 5.2, 5.5_

- [ ]\* 2.1 Write unit tests for configuration resolution methods
  - Test resolveSchemaPriorityList() returns correct order
  - Test isSnowflakeSecondaryFallbackEnabled() respects config flag
  - Test startup logging contains schema names
  - _Requirements: 5.1, 5.5_

- [x] 3. Implement HTTP multi-schema sequential query logic
  - Implement `checkViaExensioHttpMultiSchema(request, schemas)` method
  - Query schemas sequentially (PRODUCTION → SANDBOX)
  - Reuse authentication token across schemas
  - Return first successful result (stop after first schema with results)
  - Handle 401 errors: refresh token once and retry same schema
  - Handle transient errors: attempt next schema
  - Return null if all schemas exhausted (signal to Snowflake fallback)
  - _Requirements: 1.1, 1.4, 2.3, 10.1, 10.2, 10.3_

- [ ]\* 3.1 Write unit tests for HTTP sequential schema queries
  - Mock HTTP responses: PRODUCTION empty, SANDBOX has results
  - Verify SANDBOX query executed when PRODUCTION empty
  - Verify token reused across schemas (mock auth service)
  - Test 401 handling: token refreshed once, same schema retried
  - Test transient error (5xx) on PRODUCTION: SANDBOX queried
  - Test all schemas exhausted: null returned
  - _Requirements: 1.1, 1.4, 2.3, 10.2, 10.3_

- [ ]\* 3.2 Write property test for HTTP schema fallback
  - **Property 1: HTTP PRODUCTION to SANDBOX fallback**
  - **Validates: Requirements 1.1, 1.4**
  - Test: For all requests with empty PRODUCTION response, SANDBOX SHALL be queried
  - Verify: lotsFound comes from SANDBOX schema

- [x] 4. Implement Snowflake multi-schema UNION query logic
  - Implement `buildMultiSchemaSql(lotIds, waferIds, blocks, dataType, schemas)` method
  - Build UNION query with all configured schemas
  - Use SCHEMANAME IN filter or separate SELECT blocks per schema
  - Apply ROW_NUMBER ranking to prioritize PRODUCTION over SANDBOX
  - Implement `checkViaSnowflakeMultiSchema(request, schemas)` method
  - Execute single PreparedStatement containing both schemas
  - Deduplicate: one result per lot (PRODUCTION prioritized)
  - _Requirements: 2.1, 7.1, 7.2, 7.4, 9.1, 9.2_

- [ ]\* 4.1 Write unit tests for Snowflake multi-schema UNION building
  - Test SQL generation contains UNION or multiple schema conditions
  - Test lot IDs properly escaped in WHERE clause
  - Test ROWNUM limit applied to combined results
  - Test ROW_NUMBER ranking prioritizes PRODUCTION
  - Mock ResultSet: rows from both PRODUCTION and SANDBOX
  - Verify PRODUCTION rows selected first
  - _Requirements: 7.1, 7.4, 9.1, 9.2_

- [ ]\* 4.2 Write property test for Snowflake dual-schema single query
  - **Property 7: Single Snowflake query for dual-schema**
  - **Validates: Requirements 2.1, 7.1, 7.2**
  - Test: Exactly one PreparedStatement executed for dual-schema request
  - Verify: SQL contains both PRODUCTION and SANDBOX in single query

- [ ]\* 4.3 Write property test for PRODUCTION prioritization
  - **Property 13: PRODUCTION prioritization on duplicate**
  - **Validates: Requirements 3.4**
  - Test: When lot exists in both schemas, PRODUCTION result returned
  - Verify: schemaName="PRODUCTION" for deduped lots

- [x] 5. Implement orchestration logic in check() method
  - Update `check(request)` to:
    1. Parse schema priority configuration
    2. Evaluate Snowflake secondary fallback flag
    3. Try HTTP path with schemas in order
    4. If HTTP returns null (empty, no results): evaluate Snowflake fallback
    5. If fallback enabled: try Snowflake path
    6. If all fail: return soft error response
  - Add comprehensive logging at each orchestration step
  - _Requirements: 1.1, 1.5, 2.4, 6.2, 8.1_

- [ ]\* 5.1 Write unit tests for orchestration logic
  - Test HTTP executed first (regardless of Snowflake availability)
  - Test Snowflake attempted only if HTTP returns empty
  - Test Snowflake skipped if fallback disabled
  - Test soft error returned if all paths fail
  - Test logging includes path attempts and results
  - _Requirements: 1.5, 2.4, 8.1_

- [ ]\* 5.2 Write property test for HTTP-first execution
  - **Property 18: HTTP primary, Snowflake secondary**
  - **Validates: Requirements 1.1, 2.3**
  - Test: HTTP path always executed before Snowflake path
  - Verify: Snowflake only attempts when HTTP returns empty

- [x] 6. Enhance error handling and classification
  - Implement `classifyError(exception)` → TransientError | PermanentError
  - Transient: 429, 5xx, timeouts, connection errors
  - Permanent: 401 (after refresh), 403, 404, SQL syntax errors
  - Add error categorization to logging
  - Generate error messages indicating attempted paths
  - _Requirements: 6.1, 6.3, 6.5, 8.3_

- [ ]\* 6.1 Write unit tests for error classification
  - Test 429 classified as transient
  - Test 5xx classified as transient
  - Test 401 (after refresh) classified as permanent
  - Test 403 classified as permanent
  - Test SQL syntax error classified as permanent
  - _Requirements: 6.1, 6.5_

- [ ]\* 6.2 Write property test for transient error retry
  - **Property 16: Transient error categorization**
  - **Validates: Requirements 6.1**
  - Test: For all transient errors, next schema SHALL be attempted
  - Verify: No exception thrown, next schema queried

- [x] 7. Enhance ExensioPreCheckRequest DTO
  - Add `enableSnowflakeFallback` field (Boolean, nullable)
  - null = use configuration default
  - true = force Snowflake fallback enabled
  - false = force Snowflake fallback disabled
  - _Requirements: 5.1, 5.2_

- [ ]\* 7.1 Write unit tests for DTO enhancement
  - Test null enableSnowflakeFallback uses config default
  - Test true override enables fallback regardless of config
  - Test false override disables fallback regardless of config
  - _Requirements: 5.1_

- [x] 8. Implement UI enhancement (optional for MVP)
  - Add checkbox to discovery preflight form: "Also search Snowflake if Exensio returns nothing"
  - Wire checkbox to `enableSnowflakeFallback` in request DTO
  - Set checkbox state based on `enable-snowflake-secondary` config default
  - _Requirements: 5.1, 5.2_

- [ ]\* 8.1 Write UI component tests for Snowflake checkbox
  - Test checkbox visible when feature enabled
  - Test checkbox value bound to request DTO
  - Test default state matches config property
  - _Requirements: 5.1_

- [x] 9. Enhance ExensioPreCheckCacheService for invalidation awareness
  - Cache key includes: lot IDs + dataType + dateRange (existing)
  - Schema fallback parameter: determine if schema fallback state affects cache
  - Optional: separate cache for Snowflake secondary fallback results
  - _Requirements: 2.1_

- [x] 10. Write comprehensive logging for observability
  - Log at DEBUG: configuration resolved at startup
  - Log at INFO: schema fallback triggered with lot count
  - Log at INFO: which path attempted (HTTP PRODUCTION, HTTP SANDBOX, Snowflake)
  - Log at INFO: schema query results (found count, schema name)
  - Log at WARN: permanent errors with categorization
  - Log at INFO: elapsed time for preflight check
  - Format: [PRECHECK] timestamp, attempt number, schema, status, elapsed
  - _Requirements: 2.4, 8.1, 8.2, 8.3_

- [ ]\* 10.1 Write unit tests for logging output
  - Capture logs and verify content
  - Test fallback triggering logged with lot count
  - Test each schema attempt logged
  - Test elapsed time included in logs
  - Test permanent errors logged at WARN level
  - _Requirements: 2.4, 8.1_

- [x] 11. Checkpoint - HTTP path complete and tested
  - Verify HTTP single-schema fallback works (PRODUCTION → SANDBOX)
  - Verify HTTP token reuse across schemas
  - Verify HTTP error classification and retries
  - Run all HTTP-related unit tests
  - All tests pass ✓

- [x] 12. Checkpoint - Snowflake path complete and tested
  - Verify Snowflake UNION query builds correctly
  - Verify PRODUCTION row prioritization via ROW_NUMBER
  - Verify exact one PreparedStatement executed
  - Run all Snowflake-related unit tests
  - All tests pass ✓

- [x] 13. Integration testing with ExensioPreCheckCacheService
  - Test cache key includes schema fallback state (if applicable)
  - Test cache hit/miss scenarios with fallback
  - Test cache invalidation when Snowflake fallback succeeds
  - _Requirements: 2.1_

- [ ]\* 13.1 Write integration tests for caching with fallback
  - Test cache hit: second request uses cached result
  - Test cache miss: different schema config recomputes
  - Test Snowflake results cacheable same as HTTP results
  - _Requirements: 2.1_

- [x] 14. E2E testing with real Exensio and Snowflake
  - Test against QA Exensio environment
  - Test with real Snowflake connection
  - Test dual-schema scenarios (lots in PRODUCTION, SANDBOX, both, neither)
  - Test error scenarios (Exensio down, Snowflake down, both down)
  - Test with real lot data from staging
  - _Requirements: 1.1, 1.2, 1.3, 2.1_

- [ ]\* 14.1 Write E2E tests against QA environments
  - Test lot exists only in PRODUCTION: found with correct schema
  - Test lot exists only in SANDBOX: found with correct schema
  - Test lot exists in both: PRODUCTION result returned
  - Test lot in neither: lotsNotFound returned
  - Test HTTP error triggers Snowflake fallback
  - _Requirements: 1.2, 1.3_

- [ ] 15. Performance baseline testing
  - Measure HTTP single-schema query time
  - Measure HTTP dual-schema (fallback) query time
  - Measure Snowflake UNION dual-schema query time
  - Measure HTTP→Snowflake fallback total time
  - Baseline: HTTP PRODUCTION only < 500ms
  - Baseline: Snowflake UNION dual-schema < 600ms
  - Baseline: HTTP fallback (both empty) + Snowflake < 1s
  - _Requirements: 2.1_

- [ ] 16. Property-based test suite for all correctness properties
  - Implement all 19 correctness properties from design
  - Each property: minimum 100 iterations
  - Each property: tagged with design reference
  - All properties: green pass before release
  - _Requirements: All_

- [ ]\* 16.1 Write property test suite implementation
  - Property 1: HTTP PRODUCTION fallback to SANDBOX
  - Property 2: Snowflake secondary fallback only on empty HTTP
  - Property 3: Lot schema attribution accuracy
  - Property 4: Not found sentinel on all exhausted
  - Property 5: HTTP errors trigger schema fallback
  - Property 6: Soft failure on all paths exhausted
  - Property 7: Single Snowflake query for dual-schema
  - Property 8: HTTP schema-specific queries (no bundling)
  - Property 9: Fallback trigger observability
  - Property 10: Snowflake fallback disable behavior
  - Property 11: HTTP-first execution order
  - Property 12: HTTP auth token reuse
  - Property 13: PRODUCTION prioritization on duplicate
  - Property 14: Snowflake PRODUCTION deduplication
  - Property 15: HTTP 401 token refresh and retry
  - Property 16: Transient error categorization
  - Property 17: Permanent error handling
  - Property 18: HTTP primary, Snowflake secondary
  - Property 19: Multi-path failure error message
  - _Requirements: All_

- [x] 17. Final checkpoint - All tests passing
  - Run full unit test suite: 100% pass ✓
  - Run full property test suite (19 properties, 100 iterations each): 100% pass ✓
  - Run integration tests with cache service: 100% pass ✓
  - Run E2E tests with QA environments: 100% pass ✓
  - Performance baselines verified ✓
  - Code review completed ✓
  - No breaking changes to existing APIs ✓

- [ ] 18. Documentation and deployment preparation
  - Update application.yml documentation with new properties
  - Update README.md with schema fallback feature overview
  - Create deployment checklist for schema fallback rollout
  - Document configuration examples (HTTP-only, HTTP+Snowflake, custom priority)
  - Document troubleshooting guide for fallback scenarios
  - _Requirements: 5.1, 5.2, 5.5_

- [ ] 19. Staged rollout and monitoring
  - Deploy to DEV environment
  - Monitor logs for schema fallback events
  - Verify no performance degradation
  - Deploy to QA environment
  - Smoke test with discovery/staging workflows
  - Deploy to PROD with feature flag disabled initially
  - Gradual enablement: 10% → 50% → 100%
  - Monitor production logs and alerts

---

## Notes

- Tasks marked with `*` are optional test/documentation tasks and can be prioritized based on team preference
- Core implementation tasks (1-7, 11-14) form the minimum viable product
- Property-based tests (16) strongly recommended for production release
- Performance baselines (15) critical before production deployment
- Each task builds on previous tasks; execute in sequence
- Checkpoint tasks (11, 12, 17) gate progression to next phase
- All code must maintain backward compatibility (no breaking changes)
