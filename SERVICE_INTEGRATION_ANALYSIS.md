# Service Integration Analysis: Lot Verification Feature

**Purpose:** Analyze how ExensioPreCheckService, ExensioLoadMonitor, and ExensioRawSqlService work together and identify integration points/conflicts with lot existence verification feature.

**Date:** July 21, 2026  
**Status:** COMPREHENSIVE ANALYSIS

---

## Current Architecture Overview

```
┌─────────────────────────────────────────────────────────────────┐
│                    Discovery Flow (Pre-Flight)                   │
├─────────────────────────────────────────────────────────────────┤
│  StepperComponent.loadPreview()                                  │
│         ↓                                                         │
│  SenderController.verifyLots() [NEW ENDPOINT]                   │
│         ↓                                                         │
│  ExensioPreCheckService.check()                                 │
│    ├─ checkViaExensioHttp() [PRIMARY]                           │
│    │    └─ Exensio raw-sql endpoint → Oracle                    │
│    └─ checkViaSnowflake() [FALLBACK]                            │
│         └─ Snowflake JDBC → EXENSIO_PROD_OPLOG_METADATA        │
│         ↓                                                         │
│  LotVerificationDialog shows results                            │
│         ↓                                                         │
│  Discovery proceeds with filtered lots                          │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│               Monitoring Flow (Post-Staging)                     │
├─────────────────────────────────────────────────────────────────┤
│  ExensioLoadMonitor.monitorExensioLoading() [SCHEDULED]         │
│    ├─ Load EXENSIO_LOADING records                              │
│    ├─ Partition into batches                                    │
│    ├─ ExensioClient.lotWaferLookupBatch()                       │
│    │    ├─ ExensioRawSqlService.queryLotMetadata() [NEW]        │
│    │    │    └─ Exensio raw-sql endpoint → Oracle               │
│    │    └─ Fallback to /lot-wafer-lookup endpoint               │
│    └─ Update database with wafer_key, pg_key                    │
│         ↓                                                         │
│  ExensioClient retries individual records on batch failure      │
│         ↓                                                         │
│  Database updated with final status (DONE, FAILED, TIMEOUT)     │
└─────────────────────────────────────────────────────────────────┘
```

---

## Service Dependencies

### ExensioPreCheckService

**Purpose:** Pre-flight verification of lot existence before discovery

**Dependencies:**

- ✅ ExensioAuthService (token management)
- ✅ ObjectMapper (JSON parsing)
- ✅ HttpClient (HTTP requests)
- ✅ DataSource[Snowflake] (fallback queries)
- ✅ ExensioProperties (configuration)

**Key Methods:**

- `check(request)` - Main orchestration
- `checkViaExensioHttp(request)` - HTTP raw-sql path
- `checkViaSnowflake(request)` - Snowflake JDBC fallback
- `resolvePgcKey(dataType)` - PGC_KEY resolution (STATIC - shared)
- `partitionResults(rows, lotIds)` - Result partitioning

**Returns:** ExensioPreCheckResponse(lotsFound, lotsNotFound, rows, error)

---

### ExensioRawSqlService

**Purpose:** Unified raw-SQL query service for both preflight and monitoring

**Dependencies:**

- ✅ ExensioAuthService (token management)
- ✅ ObjectMapper (JSON parsing)
- ✅ HttpClient (HTTP requests)
- ✅ ExensioProperties (configuration)

**Key Methods:**

- `queryLotMetadata(lots, wafers, blocks, dataType, environment)` - Query execution
- Returns: List<ExensioLotRow> with wafer_key and pg_key
- Calls `ExensioPreCheckService.resolvePgcKey()` (STATIC)

**Returns:** List<ExensioLotRow> with lot_id, end_time, ppid, wafer_id, wafer_key, pg_key

---

### ExensioLoadMonitor

**Purpose:** Scheduled polling of EXENSIO_LOADING records and batch processing

**Dependencies:**

- ✅ ExensioClient (batch lookups)
- ✅ RefDbService (database operations)
- ✅ IntegrationStatusService (status tracking)
- ✅ StageMonitorService (SSE events)
- ✅ ExensioProperties (configuration)

**Key Methods:**

- `monitorExensioLoading()` [SCHEDULED] - Main poll loop
- `processBatch(batch)` - Processes single batch
- `retryIndividualRecords(records, traceId)` - Individual retry fallback

**Flow:**

1. Load EXENSIO_LOADING records
2. Partition into batches
3. ExensioClient.lotWaferLookupBatch() per batch
4. ExensioClient.lotWaferLookup() for individual retries
5. Update database with status (DONE, FAILED, TIMEOUT)

---

## Code Reuse & Shared Components

### Shared: resolvePgcKey()

**Location:** ExensioPreCheckService.java (static method)

**Used By:**

1. **ExensioPreCheckService.buildSql()** - Pre-flight verification SQL
2. **ExensioPreCheckService.checkViaSnowflake()** - Snowflake query setup
3. **ExensioRawSqlService.buildSql()** - Monitoring raw-sql query
4. **ExensioRawSqlService.queryLotMetadata()** - Via buildSql()
5. **ExensioLoadMonitor.retryIndividualRecords()** - Individual record retry (via DataTypePgcKeyMapper)

**Issue Found:**

- ExensioLoadMonitor uses `DataTypePgcKeyMapper.resolve()` instead of `ExensioPreCheckService.resolvePgcKey()`
- **Potential Inconsistency:** Two different implementations of dataType → PGC_KEY mapping

### Shared: Oracle SQL Building

**Pattern:** Both services build similar Oracle SQL queries

- ExensioPreCheckService: Checks lot existence (SELECT lot_id, end_time, ppid, wafer_id)
- ExensioRawSqlService: Queries lot metadata (SELECT lot_id, end_time, ppid, wafer_id, wafer_key, pg_key)
- ExensioClient: Raw SQL for identifier matching (SELECT lot_id, wafer_id, lot_key, wafer_key, pg_key, ppid, file_name, end_time)

**Code Duplication:**

- yearOnlyClause() - defined in both ExensioPreCheckService and ExensioRawSqlService
- yearMonthClause() - defined in both
- escapeSql() - defined in both
- isWaferLevelClass() - defined in both

---

## Query Comparison Matrix

| Aspect                    | ExensioPreCheckService             | ExensioRawSqlService                                | ExensioClient Raw SQL                                                   |
| ------------------------- | ---------------------------------- | --------------------------------------------------- | ----------------------------------------------------------------------- |
| **Purpose**               | Pre-flight lot verification        | Metadata extraction for monitoring                  | Identifier matching for lookup                                          |
| **Primary Data Source**   | Exensio HTTP raw-sql               | Exensio HTTP raw-sql                                | Exensio HTTP raw-sql                                                    |
| **Fallback Path**         | Snowflake JDBC                     | None                                                | /lot-wafer-lookup endpoint                                              |
| **Fields Returned**       | lot_id, schema_name                | lot_id, wafer_id, wafer_key, pg_key, end_time, ppid | lot_id, wafer_id, lot_key, wafer_key, pg_key, ppid, file_name, end_time |
| **PGC_KEY Resolution**    | resolvePgcKey(dataType)            | resolvePgcKey(dataType)                             | DataTypePgcKeyMapper.resolve()                                          |
| **Date Range Support**    | ✅ Yes (INSERT_TIME for Snowflake) | ✅ Yes (end_time for Oracle)                        | ✅ Yes (end_time for Oracle)                                            |
| **Wafer-Level Support**   | ✅ Yes                             | ✅ Yes                                              | ✅ Yes                                                                  |
| **Identifier Matching**   | ❌ No                              | ❌ No                                               | ✅ Yes (file_name LIKE)                                                 |
| **Test Phase Validation** | ❌ No                              | ❌ No                                               | ✅ Yes (PPID suffix)                                                    |

---

## Integration Points & Potential Conflicts

### 1. PGC_KEY Resolution Inconsistency

**Issue:** Three different PGC_KEY resolution implementations

```
ExensioPreCheckService.resolvePgcKey(String dataType)
    ↓ maps: probe→1, ft→2, pcm→5, defect→14, map→4

ExensioRawSqlService.buildSql()
    ↓ calls ExensioPreCheckService.resolvePgcKey()

ExensioLoadMonitor.retryIndividualRecords()
    ↓ calls DataTypePgcKeyMapper.resolve(record.dataType(), waferBlank)
    ↓ uses WAFER PRESENCE FALLBACK if pgcKey is null
```

**Problem:**

- ExensioPreCheckService returns fixed PGC_KEY from dataType
- DataTypePgcKeyMapper uses wafer presence fallback (pgcKey = wafer ? 1 : 2)
- **These will return different values for same dataType!**

**Example:**

- dataType="FT" → ExensioPreCheckService returns 2
- Same dataType in ExensioLoadMonitor → DataTypePgcKeyMapper checks wafer presence
  - If wafer blank → returns 2 ✅ (matches)
  - If wafer present → returns 1 ❌ (MISMATCH!)

**Impact:** Monitoring might lookup with wrong PGC_KEY, missing lot data

---

### 2. Code Duplication

**Utility Methods Duplicated:**

- yearOnlyClause()
- yearMonthClause()
- escapeSql()
- isWaferLevelClass()

**Maintenance Risk:** Changes must be synchronized across 2-3 files

---

### 3. HttpClient Instances

**Current State:**

- ExensioPreCheckService: Creates its own HttpClient
- ExensioRawSqlService: Creates its own HttpClient
- ExensioClient: Uses shared elasticsearchHttpClient (injected)

**Problem:** Multiple HttpClient instances means:

- Separate connection pools per service
- No connection reuse between services
- Resource inefficiency

**Better:** Share single HttpClient (via dependency injection)

---

### 4. Error Handling Divergence

**ExensioPreCheckService:**

```java
// Returns soft-failure with error field
return softError("Both Exensio and Snowflake pre-check paths failed");
// On auth failure: return null → fall through to next path
// On HTTP error: throw RuntimeException → caught as soft error
```

**ExensioRawSqlService:**

```java
// Returns null on any failure
// Caller must handle null
return null;
```

**ExensioLoadMonitor:**

```java
// Treats failures as BatchResult with error message
// Retries individual records on batch failure
// No soft-failure concept
```

**Problem:** Inconsistent error semantics

---

### 5. Caching Behavior

**ExensioLoadMonitor:**

- Has lookupCache: `Cache<String, ExensioCacheValue>`
- Key: lot + "|" + wafer
- TTL: configurable (props.getCacheExpireAfterWriteMinutes())

**ExensioPreCheckService:**

- No caching

**ExensioRawSqlService:**

- No caching

**Impact:** Pre-flight verification results NOT cached, but monitoring results ARE

---

## Data Flow for Lot Verification Feature

### Flow 1: Pre-Flight Verification (Discovery)

```
User clicks "Run Discovery Preview"
    ↓
StepperComponent.loadPreview()
    ↓
verifyLotsBeforeDiscovery() [NEW]
    ├─ Extract unique lots from lotWaferPairs
    ├─ Call BackendService.verifyLotsExistenceWithDateRange(senderId, lots, dataType, blocks)
    │   ↓
    │   SenderController.verifyLots(id, request)
    │   ├─ Validate request (lots, dataType)
    │   ├─ Transform to ExensioPreCheckRequest
    │   ├─ Call ExensioPreCheckService.check(request)
    │   │   ├─ checkViaExensioHttp() [PRIMARY]
    │   │   │   ├─ buildSql(lots, wafers, blocks, dataType)
    │   │   │   │   └─ resolvePgcKey(dataType) ✅ CONSISTENT
    │   │   │   └─ callRawSql() → Exensio HTTP endpoint
    │   │   └─ checkViaSnowflake() [FALLBACK if HTTP fails]
    │   │       ├─ buildLotIdsJson(lots)
    │   │       ├─ deriveEarliestYearMonth(blocks)
    │   │       └─ Execute Snowflake JDBC query
    │   └─ Transform ExensioPreCheckResponse to LotVerificationResponse
    │       └─ Map: lotsFound → lotExists[lot]=true
    │           lotsNotFound → lotExists[lot]=false
    │
    ├─ Open LotVerificationDialogComponent with results
    └─ User selects action:
       ├─ "All" → return all lots
       ├─ "Not Found" → return filtered lots (not in Exensio)
       └─ "Cancel" → abort discovery
```

**PGC_KEY Resolution Chain:**

1. Frontend sends dataType
2. SenderController passes to ExensioPreCheckRequest
3. ExensioPreCheckService.check() uses resolvePgcKey(dataType) ✅ CORRECT
4. ExensioPreCheckService.buildSql() uses same resolvePgcKey() ✅ CONSISTENT

---

### Flow 2: Monitoring (Post-Staging)

```
ExensioLoadMonitor.monitorExensioLoading() [SCHEDULED - every 60s]
    ├─ Load EXENSIO_LOADING records
    ├─ Partition into batches
    ├─ For each batch:
    │   ├─ processBatch(batch)
    │   ├─ Check cache hit (lookupCache)
    │   ├─ Build API records list (uncached)
    │   ├─ Call ExensioClient.lotWaferLookupBatch(apiRecords, traceId)
    │   │   └─ Call doLotWaferLookupBatch(apiRecords, token)
    │   │       ├─ Call ExensioRawSqlService.queryLotMetadata()
    │   │       │   ├─ buildSql(lots, wafers, blocks, dataType)
    │   │       │   │   └─ resolvePgcKey(dataType) ✅ CORRECT
    │   │       │   └─ callRawSql() → Exensio HTTP endpoint
    │   │       │       └─ Returns ExensioLotRow(lot_id, wafer_id, wafer_key, pg_key)
    │   │       │
    │   │       └─ If raw-sql fails → fallback to /lot-wafer-lookup endpoint
    │   │
    │   ├─ Cache successful results
    │   ├─ Handle failures:
    │   │   └─ Retry individual records via ExensioClient.lotWaferLookup()
    │   │       └─ resolvePgcKey via DataTypePgcKeyMapper ❌ INCONSISTENT
    │   │
    │   └─ Return BatchResult(updates, done, failed, notFound)
    │
    └─ Batch update database with status, wafer_key, pg_key
```

**PGC_KEY Resolution Chain (ISSUE):**

1. Batch lookup: ExensioRawSqlService → resolvePgcKey(dataType) ✅
2. Individual retry: DataTypePgcKeyMapper.resolve(dataType, waferBlank) ❌ INCONSISTENT
   - Uses wafer presence fallback
   - May return different PGC_KEY than batch query

---

## Impact Analysis

### On Pre-Flight Verification Feature

**✅ POSITIVE IMPACTS:**

- Exensio HTTP path (primary) is consistent across services
- Date range filtering integrated correctly
- Snowflake fallback provides resilience
- Soft-failure model allows graceful degradation
- No conflicts with existing monitoring

**⚠️ POTENTIAL ISSUES:**

- ExensioPreCheckService creates separate HttpClient (resource inefficiency)
- No caching of pre-flight results (repeated calls not optimized)
- Query duplication with ExensioClient (maintainability)

**✅ MITIGATION IN PLACE:**

- ExensioPreCheckService.resolvePgcKey() used consistently in HTTP path
- Snowflake fallback independent of monitoring
- No shared state conflicts

---

### On Monitoring Feature (ExensioLoadMonitor)

**⚠️ POTENTIAL ISSUES:**

1. **PGC_KEY Inconsistency in Individual Retry**
   - Batch query uses ExensioPreCheckService.resolvePgcKey() ✅
   - Individual retry uses DataTypePgcKeyMapper.resolve(dataType, waferBlank) ❌
   - **Risk:** Different records looked up with different PGC_KEYs

2. **HttpClient Resource Inefficiency**
   - Each service creates own HttpClient
   - Connection pools not shared
   - **Impact:** High memory usage, connection pool exhaustion under load

3. **SQL Query Duplication**
   - yearOnlyClause, yearMonthClause, escapeSql duplicated
   - Changes to SQL building logic require updates in 2+ places
   - **Risk:** Inconsistent behavior if not updated in sync

---

## Recommended Fixes

### Fix 1: Unify PGC_KEY Resolution (CRITICAL)

**Current Problem:**

```
ExensioLoadMonitor.retryIndividualRecords()
    ↓
DataTypePgcKeyMapper.resolve(record.dataType(), waferBlank)
    ├─ If pgcKey != null: use pgcKey
    └─ Else: use waferBlank ? 1 : 2 (FALLBACK)
```

**Solution:**
Replace DataTypePgcKeyMapper usage with ExensioPreCheckService.resolvePgcKey()

```java
// In ExensioLoadMonitor.retryIndividualRecords()

// OLD:
boolean waferBlank = record.wafer() == null || record.wafer().isBlank();
int pgcKey = DataTypePgcKeyMapper.resolve(record.dataType(), waferBlank);

// NEW:
int pgcKey = ExensioPreCheckService.resolvePgcKey(record.dataType());
```

**Impact:** Ensures batch and individual retry use same PGC_KEY logic

---

### Fix 2: Consolidate SQL Utilities (HIGH)

**Current Duplication:**

- ExensioPreCheckService: yearOnlyClause, yearMonthClause, escapeSql, isWaferLevelClass
- ExensioRawSqlService: Same 4 methods

**Solution:** Create SqlUtilService

```java
@Service
public class ExensioSqlUtilService {
    public static String yearOnlyClause(int year) { ... }
    public static String yearMonthClause(int year, int month) { ... }
    public static String escapeSql(String value) { ... }
    public static boolean isWaferLevelClass(int pgcKey) { ... }
}
```

**Usage:**

- ExensioPreCheckService → inject ExensioSqlUtilService
- ExensioRawSqlService → inject ExensioSqlUtilService
- ExensioClient → inject ExensioSqlUtilService

**Impact:** Single source of truth for SQL utilities, easier maintenance

---

### Fix 3: Share HttpClient (MEDIUM)

**Current State:**

- ExensioPreCheckService creates own HttpClient
- ExensioRawSqlService creates own HttpClient
- ExensioClient receives elasticsearchHttpClient (injected)

**Solution:** Create shared HttpClientProvider

```java
@Configuration
public class HttpClientConfig {
    @Bean
    public HttpClient exensioHttpClient() {
        return HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }
}
```

**Usage:**

```java
@Service
public class ExensioPreCheckService {
    public ExensioPreCheckService(
            ...,
            HttpClient exensioHttpClient,  // Inject shared instance
            ...) {
        this.httpClient = exensioHttpClient;
    }
}
```

**Impact:** Single connection pool, reduced memory usage, better resource sharing

---

### Fix 4: Add Pre-Flight Result Caching (MEDIUM)

**Current:** ExensioLoadMonitor caches, but ExensioPreCheckService doesn't

**Solution:** Add optional caching to ExensioPreCheckService

```java
@Service
public class ExensioPreCheckService {

    private final Cache<String, ExensioPreCheckResponse> resultCache;

    public ExensioPreCheckResponse check(ExensioPreCheckRequest request) {
        String cacheKey = buildCacheKey(request);

        // Try cache hit
        ExensioPreCheckResponse cached = resultCache.getIfPresent(cacheKey);
        if (cached != null) {
            log.debug("[ExensioPreCheck] Cache hit for {} lots", request.lotIds().size());
            return cached;
        }

        // Execute query
        ExensioPreCheckResponse result = checkViaExensioHttp(request);
        if (result == null) {
            result = checkViaSnowflake(request);
        }
        if (result == null) {
            result = softError("Both paths failed");
        }

        // Cache result (TTL: 5 minutes)
        if (result.error() == null) {  // Cache only successful results
            resultCache.put(cacheKey, result);
        }

        return result;
    }

    private String buildCacheKey(ExensioPreCheckRequest request) {
        return request.dataType() + "|" + String.join(",", request.lotIds());
    }
}
```

**Impact:** Repeated verification calls for same lots return cached results

---

## Recommended Implementation Order

| Priority    | Fix                              | Effort | Impact              | Risk |
| ----------- | -------------------------------- | ------ | ------------------- | ---- |
| 🔴 CRITICAL | Fix 1: Unify PGC_KEY             | 30min  | Correctness         | Low  |
| 🟡 HIGH     | Fix 2: Consolidate SQL Utilities | 2-3hr  | Maintainability     | Low  |
| 🟡 MEDIUM   | Fix 3: Share HttpClient          | 1-2hr  | Resource efficiency | Low  |
| 🟢 LOW      | Fix 4: Add Caching               | 1-2hr  | Performance         | Low  |

---

## Testing Strategy for Integration

### Unit Tests

1. **PGC_KEY Resolution**

   ```java
   @Test
   void testPgcKeyConsistency() {
       // Verify ExensioPreCheckService.resolvePgcKey() ==
       // DataTypePgcKeyMapper.resolve() for all dataTypes

       for (String dataType : ALL_DATA_TYPES) {
           int precheck = ExensioPreCheckService.resolvePgcKey(dataType);
           int mapper = DataTypePgcKeyMapper.resolve(dataType, false);
           assertEquals(precheck, mapper,
               "Mismatch for dataType=" + dataType);
       }
   }
   ```

2. **SQL Utility Consistency**
   ```java
   @Test
   void testSqlUtilitiesInSync() {
       String clause1 = ExensioPreCheckService.yearOnlyClause(2025);
       String clause2 = ExensioRawSqlService.yearOnlyClause(2025);
       assertEquals(clause1, clause2);
   }
   ```

### Integration Tests

1. **Pre-Flight Verification with Monitoring**
   - Verify lot through pre-flight check
   - Check if monitoring uses same lot correctly
   - Verify wafer_key, pg_key populated correctly

2. **Batch vs Individual Retry PGC_KEY**
   - Verify batch and individual retry use same PGC_KEY
   - Query same lot with both methods
   - Verify results consistent

---

## Summary & Recommendations

### Current State Assessment

**✅ STRENGTHS:**

- Pre-flight verification feature well-designed, independent of monitoring
- Exensio HTTP path consistent across services
- Fallback mechanisms provide resilience
- No state conflicts between pre-flight and monitoring

**⚠️ WEAKNESSES:**

- PGC_KEY resolution inconsistent between batch and individual retry
- SQL query building duplicated across services
- HttpClient instances not shared
- Pre-flight results not cached

### Recommended Actions

**Immediate (Before Production):**

1. ✅ Fix PGC_KEY inconsistency in ExensioLoadMonitor
   - Replace DataTypePgcKeyMapper with ExensioPreCheckService.resolvePgcKey()
   - Verify batch and retry use same PGC_KEY

**Near-Term (Next Sprint):** 2. ✅ Consolidate SQL utilities into shared service 3. ✅ Share HttpClient instance across services 4. ✅ Add caching to pre-flight verification

**Ongoing:** 5. ✅ Monitor metrics for resource usage (HttpClient connection pools) 6. ✅ Track pre-flight verification performance 7. ✅ Validate PGC_KEY correctness in integration tests

---

## Risk Assessment

| Risk                              | Severity | Likelihood | Mitigation               |
| --------------------------------- | -------- | ---------- | ------------------------ |
| PGC_KEY mismatch in monitoring    | HIGH     | MEDIUM     | Fix 1 (Unify PGC_KEY)    |
| Resource exhaustion (HttpClient)  | MEDIUM   | LOW        | Fix 3 (Share HttpClient) |
| SQL logic drift (duplication)     | MEDIUM   | LOW        | Fix 2 (Consolidate)      |
| Pre-flight performance (no cache) | LOW      | MEDIUM     | Fix 4 (Add Caching)      |

---

## Conclusion

The lot existence verification feature integrates cleanly with existing services with **one critical issue: PGC_KEY inconsistency in monitoring retry logic**. This should be fixed before production deployment.

Three secondary improvements (SQL consolidation, HttpClient sharing, caching) are recommended for maintainability and performance but are not blockers for the feature.

**Recommendation: PROCEED WITH FIX 1 BEFORE PRODUCTION, plan Fixes 2-4 for next iteration.**
