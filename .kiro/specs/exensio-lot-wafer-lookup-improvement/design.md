# Design Document

## Overview

This design document describes the implementation approach for improving the Exensio lot/wafer lookup query to handle cases where filtering by `lot_id` returns empty results. The solution implements a fallback query strategy that removes overly restrictive `lot_id` filters and relies on timestamp-based record selection to identify the correct record.

The core insight is that when `lot_id` filtering fails (possibly due to case sensitivity issues, data quality problems, or timing of database writes), we can still identify the correct record by:

1. Broadening the query to remove `lot_id` constraints
2. Applying compensating filters (pgc_key, filename patterns, wafer_id, time windows)
3. Ordering results by INSERT_TIME/END_TIME and selecting the most appropriate match

## Architecture

### Current Architecture

The current `ExensioClient` implementation uses a multi-tier lookup strategy:

1. **Raw SQL Query**: Attempts direct SQL query with `lot_id IN (upper, lower)` filter
2. **Lot-Wafer Lookup Endpoint**: Falls back to REST endpoint with lot/wafer IDs
3. **Schema Fallback**: Tries PRODUCTION schema first, then SANDBOX schema

```
StageRecord → ExensioClient.lotWaferLookup()
                ├─→ doRawSqlLookupSingle() [lot_id filter]
                │     ├─→ PRODUCTION schema
                │     └─→ SANDBOX schema (if PRODUCTION fails)
                ├─→ doLotWaferLookupForSchema() [REST endpoint]
                │     ├─→ PRODUCTION schema
                │     └─→ SANDBOX schema (if PRODUCTION fails)
                └─→ Return NotFound/Found/Error
```

### Enhanced Architecture

The enhanced design adds a fallback query tier within the Raw SQL Query step:

```
StageRecord → ExensioClient.lotWaferLookup()
                ├─→ doRawSqlLookupSingle()
                │     ├─→ Primary Query [lot_id filter]
                │     │     ├─→ PRODUCTION schema
                │     │     └─→ SANDBOX schema
                │     └─→ Fallback Query [no lot_id, time-ordered] ← NEW
                │           ├─→ PRODUCTION schema
                │           └─→ SANDBOX schema
                ├─→ doLotWaferLookupForSchema() [REST endpoint]
                │     ├─→ PRODUCTION schema
                │     └─→ SANDBOX schema
                └─→ Return NotFound/Found/Error
```

## Components and Interfaces

### 1. ExensioClient Enhancement

**Modified Methods:**

- `doRawSqlLookupSingle()`: Add fallback query execution after primary query fails
- `buildSingleRawSql()`: Extract reusable query building logic
- `buildFallbackRawSql()`: New method to build fallback query without lot_id filter

**New Methods:**

- `buildFallbackRawSql(int pgcKey, String wafer, Set<String> identifiers, Instant targetEndTime)`: Builds SQL query without lot_id constraint
- `selectBestRecordByTimestamp(JsonNode rows, Instant targetEndTime)`: Enhanced record selection with END_TIME matching

### 2. SQL Query Structure

**Primary Query (Existing):**

```sql
WHERE ol.pgc_key = ?
  AND l.lot_id IN ('UPPER', 'lower')
  AND (wafer matching clause)
  AND (file matching clause)
ORDER BY end_time DESC
```

**Fallback Query (New):**

```sql
WHERE ol.pgc_key = ?
  AND ol.insert_time >= (targetEndTime - 48 hours)  -- Time window constraint
  AND ol.insert_time <= (NOW + timeout_minutes)
  AND (wafer matching clause)
  AND (file matching clause)
ORDER BY insert_time DESC, end_time DESC
```

### 3. Record Selection Logic

The `selectBestRecordByTimestamp()` method will implement intelligent record selection:

**Algorithm:**

```
IF targetEndTime is provided THEN
    FOR each row in results DO
        Calculate timeDelta = |row.end_time - targetEndTime|
        Track row with minimum timeDelta
    END FOR
    RETURN row with minimum timeDelta
ELSE
    RETURN first row (already ordered by insert_time DESC, end_time DESC)
END IF
```

### 4. Configuration Parameters

Leverage existing configuration properties:

- `exensio.raw-sql-timeout-seconds`: Timeout for fallback queries
- `exensio.raw-sql-row-limit`: ROWNUM limit (default 200)
- `exensio.dbschema`: Primary schema (PRODUCTION)
- `exensio.dbschema-fallback`: Fallback schema (SANDBOX)

Add new configuration property:

- `exensio.fallback-query-time-window-hours`: Time window for fallback queries (default 48)

## Data Models

### Input Models

**StageRecord** (existing):

```java
record StageRecord(
    Long id,
    String lot,
    String wafer,
    String dataType,
    String filename,
    String metadataId,
    String dataId,
    Instant createdAt,
    ...
)
```

### Query Result Models

**Raw SQL Result Row**:

```java
{
    "LOT_ID": "IR77289.1F",
    "WAFER_ID": "18",
    "LOT_KEY": 12345,
    "WAFER_KEY": 67890,
    "PG_KEY": 111,
    "PPID": "TEST_PROGRAM_WS",
    "FILE_NAME": "datafile.txt",
    "END_TIME": "2024-09-14T06:35:00Z",
    "SCHEMA_NAME": "PRODUCTION"
}
```

**ExensioLotWaferResult** (existing):

```java
sealed interface ExensioLotWaferResult {
    record Found(long lotKey, long waferKey, long pgKey,
                 String ppid, String lotId, String waferId,
                 String fileName, String schemaName)
        implements ExensioLotWaferResult {}
    record NotFound() implements ExensioLotWaferResult {}
    record Error(String message) implements ExensioLotWaferResult {}
}
```

## Correctness Properties

_A property is a characteristic or behavior that should hold true across all valid executions of a system—essentially, a formal statement about what the system should do. Properties serve as the bridge between human-readable specifications and machine-verifiable correctness guarantees._

### Property 1: Fallback Activation on Primary Failure

_For any_ valid StageRecord with lot/wafer information, if the primary lot_id query returns empty results, then the fallback query (without lot_id filter) should be executed before returning NotFound.

**Validates: Requirements 1.1, 2.1**

### Property 2: Timestamp-Based Record Selection

_For any_ set of matching records, the selected record should satisfy the following priority:

1. If a target END_TIME is provided, select the record with minimum |END_TIME - targetEndTime| delta
2. If no target END_TIME is provided, select the record with maximum INSERT_TIME
3. If INSERT_TIME values are identical, select the record with maximum END_TIME
4. If both timestamps are NULL, select the record with maximum LOT_KEY

**Validates: Requirements 1.2, 1.3, 3.1, 3.2, 3.3, 3.4**

### Property 3: Primary Query Precedence

_For any_ StageRecord lookup, if the primary lot_id query returns non-empty results, then the fallback query should not be executed.

**Validates: Requirements 2.5, 6.1**

### Property 4: Fallback Query Construction

_For any_ fallback query execution, the generated SQL should include:

1. pgc_key filter matching the data type
2. Time window constraint on INSERT_TIME (if target END_TIME provided)
3. Wafer matching clause (if wafer is non-blank)
4. File identifier matching clause (if identifiers provided)
5. ROWNUM or FETCH FIRST limit matching the configured raw_sql_row_limit

**Validates: Requirements 2.2, 3.7, 4.1, 4.2**

### Property 5: Result Format Consistency

_For any_ successful lookup (primary or fallback), the returned `ExensioLotWaferResult.Found` record should contain valid values: lotKey > 0, waferKey > 0, and pgKey > 0.

**Validates: Requirements 6.2**

### Property 6: Timeout Enforcement

_For any_ fallback query execution, if the query exceeds the configured `raw_sql_timeout_seconds`, then an Error result should be returned (not NotFound or Found).

**Validates: Requirements 4.5**

### Property 7: Schema Fallback Preservation

_For any_ query execution (primary or fallback), if the PRODUCTION schema returns empty results, then the SANDBOX schema should be queried before returning NotFound.

**Validates: Requirements 6.5**

### Property 8: Configuration-Based Fallback Control

_For any_ StageRecord lookup, if fallback queries are disabled via configuration, then only the primary lot_id query should be executed (no fallback attempt).

**Validates: Requirements 7.3**

## Error Handling

### Error Scenarios

1. **Primary Query Returns Empty**:
   - Log: "Primary lot_id query returned empty, attempting fallback"
   - Action: Execute fallback query

2. **Fallback Query Returns Empty**:
   - Log: "Fallback query also returned empty"
   - Return: `ExensioLotWaferResult.NotFound()`

3. **Fallback Query Timeout**:
   - Log: "Fallback query timed out after {timeout}s"
   - Return: `ExensioLotWaferResult.Error("Timeout")`

4. **Invalid SQL Construction**:
   - Log: "Failed to build fallback SQL: {error}"
   - Return: `ExensioLotWaferResult.Error("SQL construction failed")`

5. **Target END_TIME Parsing Failure**:
   - Log: "Failed to parse target END_TIME: {value}"
   - Action: Fall back to INSERT_TIME ordering

6. **Multiple Records with Identical Timestamps**:
   - Log: "Multiple records with identical timestamps, selecting first by LOT_KEY"
   - Action: Use LOT_KEY as final tiebreaker

### Logging Strategy

**Debug Level:**

- Fallback SQL query text
- Individual record evaluation during selection
- Time delta calculations

**Info Level:**

- Fallback query activation
- Fallback query success/failure
- Selected record summary

**Warn Level:**

- Timeout during fallback query
- All strategies exhausted (returning NotFound)
- Unexpected data format in results

## Testing Strategy

### Unit Tests

Unit tests should focus on specific examples and edge cases that demonstrate correct behavior:

1. **Primary Query Success**: Verify primary query executes and fallback is skipped
2. **Primary Empty, Fallback Success**: Verify fallback executes when primary returns empty
3. **Both Empty**: Verify NotFound returned when both queries return empty
4. **Timeout Handling**: Verify timeout propagates correctly as an Error result
5. **END_TIME Matching with Target**: Verify closest END_TIME record is selected when target provided
6. **INSERT_TIME Ordering**: Verify records ordered by INSERT_TIME when no target END_TIME
7. **NULL Timestamp Handling**: Verify LOT_KEY tiebreaker when timestamps are NULL
8. **Schema Fallback**: Verify PRODUCTION → SANDBOX fallback works with new query
9. **Logging Verification**: Verify appropriate log messages are emitted at each decision point

### Property-Based Tests

Each property-based test should run with a minimum of 100 iterations to thoroughly exercise the random input space.

#### Property Test 1: Fallback Activation

_For any_ randomly generated StageRecord and simulated empty primary query result, verify the fallback query is constructed and executed.

**Tag: Feature: exensio-lot-wafer-lookup-improvement, Property 1: Fallback Activation on Primary Failure**

**Validates: Requirements 1.1, 2.1**

**Test Strategy**: Generate random StageRecords with varying lot/wafer/dataType combinations. Mock the primary query to return empty. Verify that fallback query construction method is invoked with correct parameters.

#### Property Test 2: Timestamp Selection Priority

_For any_ randomly generated list of result rows with varying timestamps and an optional target END_TIME, verify the selected record follows the documented priority rules (END_TIME delta, then INSERT_TIME, then END_TIME, then LOT_KEY).

**Tag: Feature: exensio-lot-wafer-lookup-improvement, Property 2: Timestamp-Based Record Selection**

**Validates: Requirements 1.2, 1.3, 3.1, 3.2, 3.3, 3.4**

**Test Strategy**: Generate random lists of 1-20 records with random timestamps. For each list, generate random target END_TIME (50% chance of null). Verify selected record matches expected based on priority rules.

#### Property Test 3: Primary Query Precedence

_For any_ random StageRecord, if a mock primary query returns non-empty results, verify the fallback query method is never invoked.

**Tag: Feature: exensio-lot-wafer-lookup-improvement, Property 3: Primary Query Precedence**

**Validates: Requirements 2.5, 6.1**

**Test Strategy**: Generate random StageRecords. Mock primary query to return non-empty results 70% of the time. Verify fallback query method invocation count is zero when primary succeeds.

#### Property Test 4: Fallback Query SQL Structure

_For any_ random StageRecord with varying field values, verify the generated fallback SQL contains all required clauses (pgc_key, time window, wafer matching, file matching, ROWNUM limit).

**Tag: Feature: exensio-lot-wafer-lookup-improvement, Property 4: Fallback Query Construction**

**Validates: Requirements 2.2, 3.7, 4.1, 4.2**

**Test Strategy**: Generate random StageRecords with random combinations of present/absent fields (wafer, filename, metadataId, targetEndTime). Build fallback SQL for each. Parse SQL and verify presence/absence of expected clauses.

#### Property Test 5: Result Validity

_For any_ successful fallback query result (mocked), verify all returned Found records have lotKey > 0, waferKey > 0, and pgKey > 0.

**Tag: Feature: exensio-lot-wafer-lookup-improvement, Property 5: Result Format Consistency**

**Validates: Requirements 6.2**

**Test Strategy**: Generate random JSON response payloads with varying field values. Parse into ExensioLotWaferResult. For Found results, verify all key fields are positive.

#### Property Test 6: Timeout Handling

_For any_ fallback query execution that exceeds the timeout, verify an Error result is returned (not NotFound).

**Tag: Feature: exensio-lot-wafer-lookup-improvement, Property 6: Timeout Enforcement**

**Validates: Requirements 4.5**

**Test Strategy**: Mock HTTP client to simulate random delays between 0-60 seconds. Configure random timeout values between 5-30 seconds. Verify Error result when delay > timeout.

#### Property Test 7: Schema Fallback Behavior

_For any_ query execution (primary or fallback), if mocked PRODUCTION schema returns empty, verify SANDBOX schema is queried.

**Tag: Feature: exensio-lot-wafer-lookup-improvement, Property 7: Schema Fallback Preservation**

**Validates: Requirements 6.5**

**Test Strategy**: Generate random StageRecords. Mock PRODUCTION schema to return empty 50% of the time. Verify SANDBOX schema token is requested and query is executed when PRODUCTION is empty.

#### Property Test 8: Configuration-Based Control

_For any_ StageRecord lookup with fallback disabled via configuration, verify only primary query is executed.

**Tag: Feature: exensio-lot-wafer-lookup-improvement, Property 8: Configuration-Based Fallback Control**

**Validates: Requirements 7.3**

**Test Strategy**: Generate random StageRecords and random configuration (fallback enabled/disabled). When disabled, verify fallback query method is never invoked regardless of primary result.

### Integration Tests

1. **End-to-End with Mock Exensio API**: Test complete flow with mocked HTTP responses simulating various failure scenarios
2. **Schema Fallback Integration**: Verify fallback works across PRODUCTION/SANDBOX schemas with real token exchange
3. **Batch Query Integration**: Verify batch queries leverage fallback strategy correctly
4. **Configuration Integration**: Test with various ExensioProperties configurations

### Manual Testing

1. Test with the specific failing case from logs: lot=IR77289.1F, wafer=18
2. Test with various timestamp mismatches between discovery and Exensio
3. Test with records spread across PRODUCTION/SANDBOX schemas
4. Test with configuration parameter variations (timeouts, row limits)
5. Monitor logs during manual testing to verify decision path logging

## Implementation Notes

### Query Construction Strategy

The fallback query should:

1. Remove `lot_id` filter entirely
2. Add strict time window around `targetEndTime` to compensate for loss of selectivity
3. Maintain all other filters (pgc_key, wafer matching, file matching)
4. Use indexed columns (pgc_key, insert_time, ol.lg_key) for performance

### Time Window Calculation

```java
Instant windowStart = targetEndTime != null
    ? targetEndTime.minus(Duration.ofHours(fallbackTimeWindowHours))
    : Instant.now().minus(Duration.ofHours(fallbackTimeWindowHours));

Instant windowEnd = Instant.now().plus(Duration.ofMinutes(timeoutMinutes));
```

### Performance Considerations

1. **Index Usage**: Ensure `ol.pgc_key` and `ol.insert_time` are indexed
2. **Row Limit**: Maintain ROWNUM limit to prevent full table scans
3. **Time Window**: Keep window narrow enough to use indexes effectively
4. **Schema Fallback**: Only execute SANDBOX query if PRODUCTION returns empty

### Backward Compatibility

- Existing successful lookups continue to use primary query path
- No changes to public method signatures
- No changes to result format or error handling contracts
- Fallback is purely additive—adds new success cases without changing existing behavior
