# Implementation Plan: Exensio Lot/Wafer Lookup Improvement

## Overview

This implementation plan breaks down the Exensio lot/wafer lookup improvement into incremental, testable tasks. The approach is to:

1. Add fallback query construction logic
2. Enhance timestamp-based record selection
3. Integrate fallback into existing lookup flow
4. Add comprehensive testing

## Tasks

- [x] 1. Create fallback SQL query builder method
  - Implement `buildFallbackRawSql()` method in `ExensioClient.java`
  - Accept parameters: pgcKey, wafer, identifiers, targetEndTime, timeWindowHours
  - Generate SQL WITHOUT `lot_id` filter
  - Include pgc_key filter, time window on INSERT_TIME, wafer matching, file matching
  - Include ROWNUM/FETCH FIRST limit from configuration
  - Return SQL string with both PRODUCTION and SANDBOX UNIONs
  - _Requirements: 2.1, 2.2, 4.2_

- [ ]\* 1.1 Write property test for fallback SQL construction
  - **Property 4: Fallback Query Construction**
  - **Validates: Requirements 2.2, 3.7, 4.1, 4.2**
  - Generate random StageRecords with varying field combinations
  - Build fallback SQL for each
  - Parse and verify SQL contains expected clauses (pgc_key, time window, wafer, file, ROWNUM)
  - Verify clauses are conditionally present based on input (e.g., wafer clause only if wafer non-blank)

- [x] 2. Enhance timestamp-based record selection
  - Modify `selectBestRecordByTimestamp()` method in `ExensioClient.java`
  - Implement priority logic: END_TIME delta → INSERT_TIME → END_TIME → LOT_KEY
  - When targetEndTime provided, calculate |row.END_TIME - targetEndTime| for each row
  - Select row with minimum time delta
  - When targetEndTime is null, order by INSERT_TIME DESC, END_TIME DESC, LOT_KEY DESC
  - Handle NULL timestamp values gracefully
  - _Requirements: 1.2, 1.3, 3.1, 3.2, 3.3, 3.4_

- [ ]\* 2.1 Write property test for timestamp selection
  - **Property 2: Timestamp-Based Record Selection**
  - **Validates: Requirements 1.2, 1.3, 3.1, 3.2, 3.3, 3.4**
  - Generate random lists of 1-20 result rows with varying timestamps
  - Include rows with NULL timestamps as edge cases
  - Generate random target END_TIME (50% null, 50% random Instant)
  - Verify selected record matches expected priority rules
  - Test with identical timestamps to verify tiebreaker logic

- [x] 3. Integrate fallback query into single lookup flow
  - Modify `doRawSqlLookupSingle()` method in `ExensioClient.java`
  - After primary query returns empty, log "Primary lot_id query returned empty, attempting fallback"
  - Execute `buildFallbackRawSql()` with appropriate parameters
  - Execute fallback SQL via `executeRawSql()` for both PRODUCTION and SANDBOX schemas
  - If fallback returns results, apply `selectBestRecordByTimestamp()` for record selection
  - If fallback also returns empty, log "Fallback query also returned empty"
  - Return appropriate ExensioLotWaferResult (Found/NotFound/Error)
  - _Requirements: 1.1, 2.1, 2.5_

- [ ]\* 3.1 Write property test for fallback activation
  - **Property 1: Fallback Activation on Primary Failure**
  - **Validates: Requirements 1.1, 2.1**
  - Generate random StageRecords
  - Mock primary query to return empty
  - Verify fallback query builder is invoked
  - Verify fallback SQL is executed via executeRawSql

- [ ]\* 3.2 Write property test for primary precedence
  - **Property 3: Primary Query Precedence**
  - **Validates: Requirements 2.5, 6.1**
  - Generate random StageRecords
  - Mock primary query to return non-empty 70% of time
  - Verify fallback builder is NOT invoked when primary succeeds
  - Verify fallback builder IS invoked when primary returns empty

- [x] 4. Add configuration property for time window
  - Add `fallback-query-time-window-hours` property to `ExensioProperties.java`
  - Default value: 48 hours
  - Add getter method `getFallbackQueryTimeWindowHours()`
  - Document property in application.yml comments
  - _Requirements: 4.2, 7.1, 7.2_

- [x] 5. Update batch lookup to use fallback strategy
  - Modify `doRawSqlLookupBatch()` method in `ExensioClient.java`
  - Apply same fallback logic as single lookup
  - After primary batch query returns empty/partial results, attempt fallback for unresolved records
  - Use fallback SQL construction for unresolved lot/wafer pairs
  - Merge results from primary and fallback queries
  - _Requirements: 1.1, 2.1_

- [ ] 6. Checkpoint - Ensure all tests pass
  - Run all unit tests: `mvn test`
  - Run integration tests if available
  - Verify no regressions in existing behavior
  - Ask the user if questions arise

- [ ]\* 7. Write property test for result format consistency
  - **Property 5: Result Format Consistency**
  - **Validates: Requirements 6.2**
  - Generate random JSON response payloads simulating Exensio API responses
  - Parse into ExensioLotWaferResult
  - For Found results, verify lotKey > 0, waferKey > 0, pgKey > 0
  - Test with both primary and fallback query response formats

- [ ]\* 8. Write property test for timeout enforcement
  - **Property 6: Timeout Enforcement**
  - **Validates: Requirements 4.5**
  - Mock HttpClient to simulate random delays (0-60 seconds)
  - Configure random timeout values (5-30 seconds)
  - Verify Error result (not NotFound) when delay exceeds timeout
  - Verify Found result when delay is within timeout

- [ ]\* 9. Write property test for schema fallback preservation
  - **Property 7: Schema Fallback Preservation**
  - **Validates: Requirements 6.5**
  - Generate random StageRecords
  - Mock PRODUCTION schema to return empty 50% of time
  - Mock SANDBOX schema to return results
  - Verify SANDBOX token is requested when PRODUCTION returns empty
  - Verify SANDBOX query is executed with same parameters

- [ ]\* 10. Write unit tests for edge cases
  - Test: Primary succeeds → no fallback
  - Test: Primary empty, fallback succeeds → Found result
  - Test: Both empty → NotFound result
  - Test: Fallback timeout → Error result
  - Test: NULL timestamps → LOT_KEY tiebreaker
  - Test: Identical INSERT_TIME → END_TIME tiebreaker
  - Test: Target END_TIME matching → minimum delta selection
  - _Requirements: 1.4, 1.5, 3.5_

- [x] 11. Add comprehensive logging
  - Log primary query execution with traceId
  - Log decision to execute fallback with reason
  - Log fallback query execution with SQL (at debug level)
  - Log fallback success/failure with record count
  - Log final selected record details (lot, wafer, keys, timestamps)
  - Include execution times for both primary and fallback queries
  - _Requirements: 5.1, 5.2, 5.3, 5.4, 5.5_

- [ ] 12. Final checkpoint - Integration testing
  - Test with the real failing case: lot=IR77289.1F, wafer=18
  - Verify fallback successfully finds the record
  - Test with various timestamp scenarios
  - Test across PRODUCTION/SANDBOX schemas
  - Verify all logs are emitted correctly
  - Ensure all tests pass, ask the user if questions arise

## Notes

- Tasks marked with `*` are optional testing tasks that can be skipped for faster MVP
- Each task references specific requirements for traceability
- Property tests should run with minimum 100 iterations each
- Checkpoints ensure incremental validation
- The implementation maintains backward compatibility - existing working lookups continue unchanged
- Fallback queries are purely additive, not modifying existing success paths
