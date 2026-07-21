# Requirements Document: Schema Fallback in Preflight Check

## Introduction

This feature adds robust schema fallback logic to the preflight check system. When querying lot existence in Exensio, the system should first attempt to find lots in the **PRODUCTION** schema, and if not found, automatically fall back to query the **SANDBOX** schema. This ensures comprehensive lot verification without manual intervention, improving robustness and user experience during discovery and staging workflows.

---

## Glossary

- **PrefrontCheck_Service**: The ExensioPreCheckService that verifies lot existence before discovery/staging
- **Exensio_HTTP_Endpoint**: The `/v1/key/raw-sql` HTTP API endpoint for querying Exensio
- **Snowflake_Database**: Alternative query path using JDBC connection to EXENSIO_PROD_OPLOG_METADATA
- **PRODUCTION_Schema**: Primary schema used for lot lookups (default in Exensio configuration)
- **SANDBOX_Schema**: Fallback schema used when lots are not found in PRODUCTION
- **SchemaName_Field**: The SCHEMANAME field in Snowflake EXENSIO_PROD_OPLOG_METADATA table
- **Lot_Verification**: The process of checking whether a lot ID exists in the Exensio system
- **HTTP_Fallback_Path**: The Oracle raw-sql execution when Snowflake is unavailable
- **Snowflake_Fallback_Path**: The JDBC connection to Snowflake when Exensio HTTP is unavailable
- **Transient_Error**: Temporary network, timeout, or service availability issues (429, 5xx, connection timeout)
- **ExensioPreCheckResponse**: Data transfer object containing lotsFound, lotsNotFound, rows, and optional error
- **Orchestration_Logic**: The decision logic that determines which schema and path to use

---

## Requirements

### Requirement 1: Schema Fallback Orchestration

**User Story:** As a staging operator, I want the preflight check to automatically search both PRODUCTION and SANDBOX schemas, so that I can verify lot existence without manually specifying which schema to use.

#### Acceptance Criteria

1. WHEN a lot is not found in the PRODUCTION schema, THE PrefrontCheck_Service SHALL automatically retry the same query using the SANDBOX schema
2. WHEN a lot is found in either PRODUCTION or SANDBOX schema, THE PrefrontCheck_Service SHALL return success and mark which schema the lot was found in
3. WHEN a lot is not found in either PRODUCTION or SANDBOX schema, THE PrefrontCheck_Service SHALL return the lot in the lotsNotFound list with schemaName="NOT_FOUND"
4. WHEN querying PRODUCTION schema fails due to transient error, THE PrefrontCheck_Service SHALL fall back to SANDBOX schema
5. WHEN querying SANDBOX schema also fails due to transient error, THE PrefrontCheck_Service SHALL return a soft-failure response with error message describing the failure

---

### Requirement 2: Efficient Schema Fallback Implementation

**User Story:** As a system architect, I want the schema fallback to be efficient and avoid redundant queries, so that preflight checks remain performant even with dual-schema verification.

#### Acceptance Criteria

1. WHEN executing a batch lot verification, THE PrefrontCheck_Service SHALL perform only one JDBC query to Snowflake containing both PRODUCTION and SANDBOX schema data
2. WHERE configurable, THE system SHALL use Snowflake-based fallback as primary strategy when available (lower latency than HTTP retry)
3. WHEN using HTTP raw-sql path, THE PrefrontCheck_Service SHALL minimize the number of HTTP requests by bundling the PRODUCTION query and reusing connection/auth tokens
4. THE PrefrontCheck_Service SHALL log which schema was queried and when a fallback occurred for observability

---

### Requirement 3: Schema-Aware Result Handling

**User Story:** As a discovery administrator, I want to see which schema each lot was found in, so that I can understand data distribution and troubleshoot lookup issues.

#### Acceptance Criteria

1. WHEN a lot is found via Snowflake query, THE ExensioPreCheckResponse SHALL include the SchemaName_Field value (e.g., "PRODUCTION", "SANDBOX", "DEV")
2. WHEN a lot is not found in either schema, THE ExensioPreCheckResponse SHALL explicitly mark it with schemaName="NOT_FOUND"
3. WHEN a lot is found via HTTP fallback path, THE ExensioPreCheckResponse SHALL indicate schemaName="FOUND" (HTTP endpoint does not expose schema information)
4. THE PrefrontCheck_Service SHALL prioritize PRODUCTION schema results over SANDBOX when the same lot appears in both schemas (PRODUCTION_Schema first)

---

### Requirement 4: Backward Compatibility

**User Story:** As a developer, I want the schema fallback feature to integrate seamlessly with existing code, so that I don't need to modify calling code.

#### Acceptance Criteria

1. THE PrefrontCheck_Service public API (check() method) SHALL remain unchanged in signature
2. WHEN the environment-configuration specifies a schema via exensio.dbschema property, THE PrefrontCheck_Service SHALL respect that setting while still supporting fallback
3. WHEN calling existing code that queries only PRODUCTION, THE schema fallback logic SHALL activate transparently without requiring caller changes
4. THE ExensioPreCheckResponse DTO SHALL remain backward compatible with existing response parsing logic

---

### Requirement 5: Configuration and Control

**User Story:** As a deployment engineer, I want fine-grained control over schema fallback behavior, so that I can configure the system for different deployment scenarios.

#### Acceptance Criteria

1. WHERE schema-fallback is enabled, THE system SHALL provide a configuration flag (exensio.schema-fallback-enabled) to control whether fallback occurs
2. WHERE schema-fallback is enabled, THE system SHALL provide an optional list of schemas to query in priority order (exensio.schema-fallback-priority-list)
3. WHEN schema-fallback-priority-list is configured, THE PrefrontCheck_Service SHALL query schemas in that order
4. WHEN schema-fallback-priority-list is not configured, THE PrefrontCheck_Service SHALL use default priority: [PRODUCTION, SANDBOX]
5. THE system SHALL log the current schema configuration at service startup for audit trail

---

### Requirement 6: Error Handling and Resilience

**User Story:** As an operations engineer, I want robust error handling in schema fallback so that transient failures don't prevent lot verification.

#### Acceptance Criteria

1. WHEN a schema query fails with transient error (429, 5xx, timeout), THE PrefrontCheck_Service SHALL attempt the next schema in the fallback list
2. IF all schemas fail due to transient errors, THE PrefrontCheck_Service SHALL return a soft-failure response (not throw exception)
3. WHEN both Exensio HTTP and Snowflake paths fail, THE PrefrontCheck_Service SHALL return error message indicating which paths were attempted
4. THE PrefrontCheck_Service SHALL implement exponential backoff for HTTP retry attempts across schema fallbacks
5. WHEN a query to a specific schema permanently fails (e.g., invalid credentials, unknown table), THE PrefrontCheck_Service SHALL log warning and attempt next schema

---

### Requirement 7: Performance Optimization for Dual-Schema Queries

**User Story:** As a performance engineer, I want the dual-schema verification to be optimized for large batch operations, so that staging performance isn't degraded.

#### Acceptance Criteria

1. WHEN executing Snowflake JDBC query, THE system SHALL retrieve both PRODUCTION and SANDBOX results in a single query using UNION or combined WHERE clause
2. WHEN a lot is found in PRODUCTION schema, THE system SHALL NOT execute a redundant SANDBOX query for that same lot
3. WHERE results are sorted by PRODUCTION first, THE PrefrontCheck_Service SHALL apply sorting at query-level (in SQL) rather than in application code
4. THE batch size limit (precheckRowLimit) SHALL apply to the combined result set across all schemas, not per-schema

---

### Requirement 8: Observability and Logging

**User Story:** As a support engineer, I want detailed logs for schema fallback operations, so that I can troubleshoot lot verification issues efficiently.

#### Acceptance Criteria

1. WHEN schema fallback is triggered, THE PrefrontCheck_Service SHALL log: timestamp, lot IDs queried, attempted schemas, results, and elapsed time
2. WHEN a schema query succeeds, THE log entry SHALL include which schema returned the results
3. WHEN a schema query fails, THE log entry SHALL include the reason (transient vs permanent, specific error message)
4. THE logging context SHALL include: request traceId, dataType, pgcKey, schemaName, and query result counts
5. WHEN all schemas fail, THE PrefrontCheck_Service SHALL log at WARN level with actionable troubleshooting information

---

### Requirement 9: Snowflake Schema Fallback Implementation

**User Story:** As a database engineer, I want the Snowflake JDBC path to efficiently query both PRODUCTION and SANDBOX without code duplication, so that maintenance is simplified.

#### Acceptance Criteria

1. WHEN executing Snowflake JDBC query, THE system SHALL include both PRODUCTION and SANDBOX in the query via SCHEMANAME filter (e.g., SCHEMANAME IN ('PRODUCTION', 'SANDBOX'))
2. WHEN results are retrieved, THE system SHALL rank PRODUCTION results higher than SANDBOX (e.g., ROW_NUMBER for deduplication)
3. THE Snowflake SQL query logic SHALL use a single PreparedStatement with parameters for schemas list
4. WHEN deduplicating results where same lot appears in both schemas, THE system SHALL select PRODUCTION result and discard SANDBOX result

---

### Requirement 10: HTTP Raw-SQL Schema Fallback Implementation

**User Story:** As an integration engineer, I want the HTTP raw-sql path to support schema fallback with efficient retry logic, so that we have an operational fallback when Snowflake is unavailable.

#### Acceptance Criteria

1. WHEN executing HTTP raw-sql for PRODUCTION schema and receiving no results, THE PrefrontCheck_Service SHALL attempt same query with SANDBOX schema
2. WHEN executing HTTP raw-sql, THE system SHALL reuse the same authentication token across schema fallback attempts (avoid unnecessary token refreshes)
3. WHEN HTTP raw-sql returns 401 during first schema attempt, THE system SHALL refresh token once and retry the same schema, then attempt fallback schema with refreshed token
4. WHEN HTTP raw-sql queries are chained across schemas, THE system SHALL track total request count and log duplication warnings if multiple requests exceed threshold
5. THE HTTP raw-sql OR clause generation SHALL support up to N schemas without exceeding query size limits (default 10000 rows per schema)

---

## Mapping to Existing Specs

This feature enhances the lot-existence-verification feature by adding schema fallback capability:

- **Related Spec:** `.kiro/specs/lot-existence-verification/design.md`
- **Related Spec:** `.kiro/specs/lot-existence-verification/requirements.md`
- **Related Service:** `ExensioPreCheckService.java`
- **Related Service:** `ExensioPreCheckCacheService.java` (caching layer)

---

## Quality Attributes

### Performance

- Snowflake dual-schema query: Single JDBC roundtrip
- HTTP dual-schema retry: 2 HTTP requests worst-case (PRODUCTION + SANDBOX)
- Batch operation (1000 lots): < 5 seconds for dual-schema verification

### Reliability

- Automatic failover to SANDBOX if PRODUCTION unavailable
- Transient error handling with exponential backoff
- Soft-failure responses instead of exceptions for operational resilience

### Maintainability

- Clear separation between Snowflake and HTTP paths
- Centralized schema fallback logic in ExensioPreCheckService
- Comprehensive logging for troubleshooting

### Security

- No changes to authentication/authorization logic
- Respects existing token management
- Logs schema names for audit trail (no sensitive data exposure)

</content>
