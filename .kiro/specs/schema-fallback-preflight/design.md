# Design Document: Schema Fallback in Preflight Check

## Overview

This design implements robust schema fallback logic in the preflight lot verification system. The system will:

1. **Primary Path:** Query Exensio via HTTP raw-SQL endpoint, checking PRODUCTION schema first, then SANDBOX schema if needed
2. **Secondary Fallback:** If HTTP returns no results after checking both configured schemas, optionally query Snowflake as alternative data source
3. **User Control:** UI checkbox allows operators to enable/disable Snowflake secondary fallback

### Key Design Principles

1. **HTTP-First Approach**: Exensio HTTP raw-SQL is the authoritative source
2. **Schema Fallback Within Path**: Query schemas sequentially (PRODUCTION → SANDBOX)
3. **Cross-Path Fallback**: Switch to Snowflake JDBC if HTTP exhausted
4. **User Control**: Optional UI checkbox for Snowflake fallback enabling
5. **Efficiency**: When using Snowflake, query both schemas in single UNION
6. **Robustness**: Automatic fallback without caller intervention
7. **Best Practice**: Configuration-driven behavior with sensible defaults

---

## Architecture

### HTTP-First Strategy (Revised from Requirements)

```
ExensioPreCheckService.check(request)
        ↓
    [STEP 1: Parse Configuration]
    ├─ schema-fallback-enabled: true/false
    ├─ schema-fallback-priority-list: [PRODUCTION, SANDBOX] (default)
    ├─ enable-snowflake-secondary: true/false (from config + UI)
    └─ max-schema-attempts: 3 (default)
        ↓
    [STEP 2: Execute HTTP raw-SQL (PRIMARY PATH)]
    ├─ For each schema in priority list:
    │  ├─ Query via Exensio HTTP endpoint
    │  └─ If found: return success, skip remaining schemas
    ├─ If no results after all schemas exhausted:
    │  └─ Proceed to STEP 3 (Snowflake secondary fallback)
    └─ If HTTP fails (transient/permanent):
        └─ Try next schema if available, else proceed to STEP 3
        ↓
    [STEP 3: Execute Snowflake (SECONDARY FALLBACK)]
    ├─ Only if:
    │  ├─ HTTP returned no results (not error)
    │  ├─ Snowflake secondary fallback enabled
    │  └─ Snowflake DataSource available
    │
    ├─ Query both schemas in single UNION (efficiency)
    ├─ Rank PRODUCTION first via ROW_NUMBER
    └─ Return Snowflake results if found
        ↓
    [STEP 4: Return Response]
    ├─ If found: ExensioPreCheckResponse with lotsFound
    ├─ If not found anywhere: lotsNotFound with schemaName="NOT_FOUND"
    └─ If all failed: soft error response
```

---

## Components and Interfaces

### ExensioPreCheckService (Enhanced)

**New Configuration Methods:**

```java
/**
 * Determines whether Snowflake secondary fallback is enabled.
 * Can be set via: config property + runtime flag from caller.
 *
 * @return true if Snowflake fallback should be used when HTTP finds nothing
 */
public boolean isSnowflakeSecondaryFallbackEnabled()

/**
 * Resolves schema priority list from configuration.
 * Returns default [PRODUCTION, SANDBOX] if not configured.
 *
 * @return List of schema names in priority order
 */
public List<String> resolveSchemaPriorityList()

/**
 * Executes HTTP raw-SQL path for configured schemas.
 * Queries schemas sequentially in priority order.
 * Returns null to signal that Snowflake should be attempted.
 *
 * @param request PreCheckRequest
 * @param schemas List of schemas to query in order
 * @return ExensioPreCheckResponse or null for secondary fallback
 */
private ExensioPreCheckResponse checkViaExensioHttpMultiSchema(
    ExensioPreCheckRequest request,
    List<String> schemas)

/**
 * Executes Snowflake JDBC path as secondary fallback.
 * Queries both schemas in single UNION for efficiency.
 * Only called if HTTP found nothing (not errors).
 *
 * @param request PreCheckRequest
 * @param schemas List of schemas to query
 * @return ExensioPreCheckResponse with Snowflake results or null on failure
 */
private ExensioPreCheckResponse checkViaSnowflakeMultiSchema(
    ExensioPreCheckRequest request,
    List<String> schemas)
```

### Configuration Properties (application.yml)

```yaml
exensio:
  # Existing properties
  enabled: ${EXENSIO_ENABLED:false}
  env: ${EXENSIO_ENV:QA}
  qa-url: ${EXENSIO_QA_URL:}
  prod-url: ${EXENSIO_PROD_URL:}
  dbname: ${EXENSIO_DBNAME:}
  dbschema: ${EXENSIO_DBSCHEMA:PRODUCTION}

  # NEW: Schema Fallback Configuration
  schema-fallback-enabled: ${EXENSIO_SCHEMA_FALLBACK_ENABLED:true}
  # Enable/disable schema fallback feature entirely

  schema-fallback-priority-list: ${EXENSIO_SCHEMA_FALLBACK_PRIORITY_LIST:PRODUCTION,SANDBOX}
  # Comma-separated schema names in priority order for fallback
  # Example: "PRODUCTION,SANDBOX" or "SANDBOX,PRODUCTION"

  # NEW: Snowflake Secondary Fallback
  enable-snowflake-secondary: ${EXENSIO_ENABLE_SNOWFLAKE_SECONDARY:true}
  # Enable/disable Snowflake as fallback when HTTP finds nothing
  # Can be toggled at runtime via UI checkbox
```

### UI Component (SenderController / Discovery UI)

**New Request Parameter (ExensioPreCheckRequest):**

```java
record ExensioPreCheckRequest(
    List<String> lotIds,
    List<String> waferIds,
    String dataType,
    List<PreCheckBlock> blocks,
    String environment,
    Boolean enableSnowflakeFallback  // NEW: runtime override from UI checkbox
)
```

**New UI Checkbox:**

```
┌─ Preflight Lot Verification
│  ├─ [✓] Enable preflight check
│  ├─ [✓] Also search Snowflake if Exensio returns nothing  ← NEW
│  └─ [Query Button]
```

When checkbox is **checked**: `enableSnowflakeFallback=true` (use Snowflake as fallback)
When checkbox is **unchecked**: `enableSnowflakeFallback=false` (HTTP only)

---

## Correctness Properties

A property is a characteristic or behavior that should hold true across all valid executions of a system—essentially, a formal statement about what the system should do. Properties serve as the bridge between human-readable specifications and machine-verifiable correctness guarantees.

### Correctness Properties (Revised for HTTP-First Strategy)

#### Core Fallback Behavior

**Property 1: HTTP PRODUCTION to SANDBOX fallback**

_For any_ request with HTTP configured and PRODUCTION schema query returning no results, the service SHALL automatically query SANDBOX schema via HTTP.

**Validates: Requirements 1.1, 1.4**

---

**Property 2: Snowflake secondary fallback only on HTTP empty**

_For any_ request where Snowflake secondary fallback is enabled, Snowflake SHALL be queried only if: (1) HTTP exhausted all configured schemas, (2) HTTP returned empty results (not error), (3) Snowflake DataSource is available.

**Validates: Requirements 1.1, 2.1**

---

**Property 3: Lot schema attribution accuracy**

_For any_ request that returns found lots, the ExensioPreCheckResponse SHALL include each lot in lotsFound with schemaName matching the schema where it was found ("PRODUCTION", "SANDBOX", or other).

**Validates: Requirements 1.2, 3.1**

---

**Property 4: Not found sentinel on all exhausted**

_For any_ lot that doesn't exist in any queried schema (HTTP PRODUCTION, HTTP SANDBOX, or Snowflake if enabled), that lot SHALL appear in lotsNotFound with schemaName="NOT_FOUND".

**Validates: Requirements 1.3**

---

**Property 5: HTTP errors trigger schema fallback**

_For any_ request where HTTP query to primary schema fails with transient error (429, 5xx, timeout), the next configured schema SHALL be queried via HTTP before attempting Snowflake fallback.

**Validates: Requirements 1.4, 6.1**

---

**Property 6: Soft failure on all paths exhausted**

_For any_ request where all configured paths (HTTP all schemas, Snowflake if enabled) fail, the method SHALL return ExensioPreCheckResponse with non-null error field (not throw exception).

**Validates: Requirements 1.5, 6.2**

---

#### Efficiency Properties

**Property 7: Single Snowflake query for dual-schema**

_For any_ request executing Snowflake secondary fallback with multiple schemas configured, exactly one PreparedStatement SHALL be executed containing both schemas in UNION or WHERE clause.

**Validates: Requirements 2.1, 7.1**

---

**Property 8: HTTP schema-specific queries (no bundling)**

_For any_ HTTP raw-SQL execution, each schema query SHALL be a separate HTTP request (not bundled) with separate PreparedStatements, executing in priority order.

**Validates: Requirements 2.3**

---

**Property 9: Fallback trigger observability**

_For any_ request where Snowflake secondary fallback is triggered (HTTP found nothing), the logging output SHALL include: timestamp, lot count, HTTP schema attempts, Snowflake execution, and combined elapsed time.

**Validates: Requirements 2.4, 8.1**

---

#### Configuration and Control Properties

**Property 10: Snowflake fallback disable behavior**

_For any_ request when exensio.enable-snowflake-secondary=false OR UI checkbox is unchecked, Snowflake SHALL NOT be queried even if HTTP returns empty results.

**Validates: Requirements 5.1**

---

**Property 11: HTTP-first execution order**

_For any_ request, HTTP raw-SQL path SHALL be executed first, regardless of Snowflake availability. Snowflake SHALL only execute after HTTP exhausts all configured schemas without results.

**Validates: Requirements 2.3**

---

**Property 12: HTTP auth token reuse**

_For any_ HTTP raw-SQL execution across multiple schemas, authentication token requests SHALL be minimized: one login per schema sequence, token reused across schema fallbacks unless 401 forces refresh.

**Validates: Requirements 2.3, 10.2**

---

#### Result Ranking Properties

**Property 13: PRODUCTION prioritization on duplicate**

_For any_ lot found in both PRODUCTION and SANDBOX (if queried), the result returned SHALL have schemaName="PRODUCTION" with SANDBOX result discarded.

**Validates: Requirements 3.4**

---

**Property 14: Snowflake PRODUCTION deduplication**

_For any_ Snowflake query where same lot exists in both schemas, ROW_NUMBER ranking SHALL select PRODUCTION row first via ORDER BY clause prioritizing PRODUCTION.

**Validates: Requirements 3.4, 9.2**

---

#### Error Handling Properties

**Property 15: HTTP 401 token refresh and retry**

_For any_ HTTP raw-SQL request receiving 401, exactly one token refresh SHALL occur, the same schema SHALL be retried with new token, then next schema attempted if still no results.

**Validates: Requirements 6.1, 10.3**

---

**Property 16: Transient error categorization**

_For any_ query failure classified as transient (429, 5xx, timeout, connection errors), the next schema in priority list SHALL be attempted automatically without returning error.

**Validates: Requirements 6.1**

---

**Property 17: Permanent error handling**

_For any_ query failure classified as permanent (401 after refresh, 403, 404, SQL syntax error), a warning SHALL be logged, next schema SHALL be attempted, and if all fail, soft error returned.

**Validates: Requirements 6.5**

---

#### HTTP-Specific Properties

**Property 18: HTTP primary, Snowflake secondary**

_For any_ request, HTTP raw-SQL SHALL be executed first for all configured schemas, and only if HTTP returns completely empty (lotsFound empty AND lotsNotFound populated), Snowflake secondary fallback SHALL be evaluated.

**Validates: Requirements 1.1, 2.3**

---

**Property 19: Multi-path failure error message**

_For any_ request where both HTTP exhausts all schemas and Snowflake fails, the error message SHALL explicitly mention both "HTTP" and "Snowflake" as attempted paths with specific failure reasons.

**Validates: Requirements 6.3**

---

## Error Handling Strategy

### Error Classification

**Transient Errors:** Automatically trigger next schema attempt

- HTTP 429 (Too Many Requests)
- HTTP 5xx (Server errors)
- Connection timeout
- Network socket timeout
- Temporary unavailable

**Permanent Errors:** Log warning, attempt next schema/path

- HTTP 401 (Auth failed after token refresh)
- HTTP 403 (Forbidden - user/role issue)
- HTTP 404 (Schema/endpoint not found)
- SQL syntax error
- Connection refused (after retries)
- Invalid credentials
- Unknown table/schema

**Processing:**

1. First transient error on schema N → Attempt schema N+1 (same path)
2. All schemas fail or empty → If Snowflake enabled and not yet tried → Attempt Snowflake
3. All paths fail/empty → Return soft-failure with error message

### Soft Failure Response Format

```java
new ExensioPreCheckResponse(
    Collections.emptyList(),  // lotsFound (empty)
    submittedLotIds,          // lotsNotFound (all submitted)
    Collections.emptyList(),  // rows (empty)
    "Exensio HTTP searches PRODUCTION,SANDBOX returned no results. " +
    "Snowflake fallback disabled or unavailable. " +
    "Unable to verify lot existence. " +
    "To enable Snowflake fallback, check UI option or set EXENSIO_ENABLE_SNOWFLAKE_SECONDARY=true. " +
    "Details: HTTP attempt 1 (PRODUCTION) - OK but empty, " +
    "HTTP attempt 2 (SANDBOX) - OK but empty."
)
```

---

## Data Models

### ExensioPreCheckRow (Existing - No Changes)

```java
record ExensioPreCheckRow(
    String lotId,
    String schemaName  // "PRODUCTION", "SANDBOX", "NOT_FOUND", or "FOUND"
)
```

Values:

- **"PRODUCTION"** — found in Exensio PRODUCTION schema (HTTP or Snowflake)
- **"SANDBOX"** — found in Exensio SANDBOX schema (HTTP or Snowflake)
- **"NOT_FOUND"** — queried but not present in any path/schema
- **"FOUND"** — HTTP raw-sql found it (historical, for backward compatibility)

### ExensioPreCheckRequest (Enhanced)

```java
record ExensioPreCheckRequest(
    List<String> lotIds,
    List<String> waferIds,           // optional, wafer-level classes only
    String dataType,                 // resolves to PGC_KEY
    List<PreCheckBlock> blocks,      // optional, date range filtering
    String environment,              // QA or PROD
    Boolean enableSnowflakeFallback  // NEW: UI checkbox state, null = use config default
)
```

---

## Configuration Reference

### Application Properties

```yaml
exensio:
  schema-fallback-enabled: ${EXENSIO_SCHEMA_FALLBACK_ENABLED:true}
  # Master switch: disable entire feature if false

  schema-fallback-priority-list: ${EXENSIO_SCHEMA_FALLBACK_PRIORITY_LIST:PRODUCTION,SANDBOX}
  # Comma-separated schema names
  # Examples: "PRODUCTION,SANDBOX" | "SANDBOX,PRODUCTION" | "PRODUCTION,SANDBOX,DEV"

  enable-snowflake-secondary: ${EXENSIO_ENABLE_SNOWFLAKE_SECONDARY:true}
  # Enable/disable Snowflake secondary fallback when HTTP returns empty
  # Can be overridden at runtime via UI checkbox

  schema-fallback-max-attempts: ${EXENSIO_SCHEMA_FALLBACK_MAX_ATTEMPTS:3}
  # Max number of schema attempts before returning error

  schema-fallback-backoff-base-ms: ${EXENSIO_SCHEMA_FALLBACK_BACKOFF_BASE_MS:100}
  # Exponential backoff base for HTTP retries (milliseconds)

  schema-fallback-backoff-max-ms: ${EXENSIO_SCHEMA_FALLBACK_BACKOFF_MAX_MS:5000}
  # Exponential backoff maximum delay
```

### Configuration Scenarios

**Scenario 1: HTTP-Only (Snowflake disabled)**

```bash
EXENSIO_SCHEMA_FALLBACK_ENABLED=true
EXENSIO_SCHEMA_FALLBACK_PRIORITY_LIST=PRODUCTION,SANDBOX
EXENSIO_ENABLE_SNOWFLAKE_SECONDARY=false
# Queries HTTP PRODUCTION → HTTP SANDBOX → return result (or empty)
# Never attempts Snowflake
```

**Scenario 2: HTTP with Snowflake Fallback (Default)**

```bash
EXENSIO_SCHEMA_FALLBACK_ENABLED=true
EXENSIO_SCHEMA_FALLBACK_PRIORITY_LIST=PRODUCTION,SANDBOX
EXENSIO_ENABLE_SNOWFLAKE_SECONDARY=true
# Queries HTTP PRODUCTION → HTTP SANDBOX → if empty, queries Snowflake (UNION both schemas)
```

**Scenario 3: Runtime UI Control (Checkbox)**

```
User unchecks "Also search Snowflake if Exensio returns nothing":
  → Request sent with enableSnowflakeFallback=false
  → HTTP paths queried, Snowflake skipped regardless of config

User checks checkbox:
  → Request sent with enableSnowflakeFallback=true
  → HTTP paths queried, Snowflake used if needed
```
