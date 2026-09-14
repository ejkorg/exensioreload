# Requirements Document

## Introduction

This document specifies the requirements for improving the Exensio API lot/wafer lookup query to correctly match records when filtering by lot_id returns empty results. The current implementation filters by exact lot_id matches (case-insensitive) but fails to find records in certain cases, resulting in "NotFound" responses even when the data exists in Exensio.

Based on log analysis, the current query filters by `lot_id IN ('IR77289.1F', 'ir77289.1f')` but returns empty results. The issue appears to be related to how lot identification works in the Exensio database schema, where additional matching strategies may be needed.

## Glossary

- **Exensio**: PDF Solutions' yield analysis and data management platform
- **OP_LOG**: Operation log table containing test operation records
- **LOT**: Lot master table containing lot identification records
- **WAFER**: Wafer master table containing wafer identification records
- **WF_LOG**: Wafer log table linking wafer operations to operation logs
- **DF_EXPORT**: Data file export table containing file metadata
- **PGC_KEY**: Program group category key (typically 1 for wafer-level, 2 for lot-level data)
- **INSERT_TIME**: Timestamp when a record was inserted into the database
- **END_TIME**: Timestamp when an operation completed
- **Raw_SQL_Query**: Direct SQL query execution endpoint in Exensio API
- **Lot_Wafer_Lookup_Endpoint**: REST endpoint for looking up lot/wafer key mappings
- **StageRecord**: Internal record representing a staged file awaiting Exensio enrichment

## Requirements

### Requirement 1: Query Strategy Enhancement

**User Story:** As a system administrator, I want the Exensio lot/wafer lookup to use improved matching strategies, so that records are found even when simple lot_id filtering fails.

#### Acceptance Criteria

1. WHEN the current lot_id filtering returns no results, THE System SHALL attempt alternative matching strategies
2. WHEN selecting from multiple matching records, THE System SHALL prefer records with the most recent INSERT_TIME
3. WHEN multiple records have identical INSERT_TIME, THE System SHALL prefer records with the most recent END_TIME
4. THE System SHALL maintain backward compatibility with existing successful lookups
5. WHEN all matching strategies fail, THE System SHALL return a NotFound result without errors

### Requirement 2: Lot Identification Fallback

**User Story:** As a data integration engineer, I want the system to handle variations in lot identification, so that lookups succeed despite minor data quality issues.

#### Acceptance Criteria

1. WHEN filtering by lot_id with case variations returns empty results, THE System SHALL attempt filtering without lot_id constraints
2. WHEN filtering without lot_id, THE System SHALL apply additional filters (pgc_key, filename patterns, wafer_id) to narrow results
3. WHEN multiple lots match the broader query, THE System SHALL select the record with the most recent timestamp
4. THE System SHALL log when fallback strategies are used for monitoring purposes
5. THE System SHALL NOT apply fallback strategies when primary lot_id query succeeds

### Requirement 3: Timestamp-Based Record Selection

**User Story:** As a developer, I want the system to use timestamp-based selection when multiple records match, so that the correct record matching discovery metadata is returned.

#### Acceptance Criteria

1. WHEN a target END_TIME is provided from discovery metadata, THE System SHALL prefer records with END_TIME matching the target
2. WHEN multiple records match all filter criteria and no target END_TIME is provided, THE System SHALL order results by INSERT_TIME descending
3. WHEN INSERT_TIME values are identical and no target END_TIME is provided, THE System SHALL order by END_TIME descending as a tiebreaker
4. WHEN selecting the best matching record with a target END_TIME, THE System SHALL calculate time deltas and prefer the closest match
5. WHEN both INSERT_TIME and END_TIME are NULL, THE System SHALL order by LOT_KEY descending
6. THE System SHALL select the first record from the ordered result set
7. THE System SHALL maintain ROWNUM or FETCH FIRST limits to prevent excessive result sets

### Requirement 4: Query Performance Optimization

**User Story:** As a system administrator, I want the fallback query strategies to perform efficiently, so that lookup operations complete within acceptable timeframes.

#### Acceptance Criteria

1. WHEN executing fallback queries, THE System SHALL maintain the existing ROWNUM limit (default 200)
2. WHEN removing lot_id filters, THE System SHALL add compensating filters (pgc_key, time windows) to constrain results
3. WHEN building the WHERE clause, THE System SHALL place the most selective filters first
4. THE System SHALL use indexed columns (pgc_key, insert_time, lot_key) in filter predicates
5. THE System SHALL timeout fallback queries after the configured raw_sql_timeout_seconds

### Requirement 5: Logging and Diagnostics

**User Story:** As a developer, I want comprehensive logging of query execution paths, so that I can diagnose lookup failures.

#### Acceptance Criteria

1. WHEN executing the primary lot_id query, THE System SHALL log the SQL statement and traceId
2. WHEN the primary query returns empty results, THE System SHALL log the decision to use fallback strategies
3. WHEN a fallback query succeeds, THE System SHALL log which strategy was successful
4. WHEN all strategies fail, THE System SHALL log the complete execution path for debugging
5. THE System SHALL include record counts and execution times in all query logs

### Requirement 6: Backwards Compatibility

**User Story:** As a system maintainer, I want the improved query logic to maintain compatibility with existing behavior, so that working lookups continue to function.

#### Acceptance Criteria

1. WHEN the current lot_id filtering succeeds, THE System SHALL return results using the existing code path
2. WHEN fallback strategies are executed, THE System SHALL return results in the same format as primary queries
3. WHEN integrating with existing endpoints (lot-wafer-lookup, raw-sql), THE System SHALL not modify request/response formats
4. THE System SHALL pass all existing unit tests without modification
5. THE System SHALL maintain the existing schema fallback behavior (PRODUCTION → SANDBOX)

### Requirement 7: Configuration and Tunability

**User Story:** As a system administrator, I want configurable parameters for fallback query behavior, so that I can tune performance for my environment.

#### Acceptance Criteria

1. THE System SHALL read the existing raw_sql_timeout_seconds configuration for fallback query timeouts
2. THE System SHALL read the existing raw_sql_row_limit configuration for ROWNUM constraints
3. WHEN fallback queries are disabled via configuration, THE System SHALL skip fallback strategies
4. THE System SHALL document all configuration parameters affecting fallback behavior
5. THE System SHALL provide sensible default values for all configuration parameters
