# Elasticsearch Timestamp Mismatch - Root Cause & Solution

## Problem Summary

Elasticsearch queries are not matching records even when data exists. The issue is a **timezone mismatch** between:

1. Oracle database timestamps (stored without timezone, likely local time)
2. Elasticsearch @timestamp field (stored in UTC)
3. Java application timestamp conversion (currently undefined, defaults to JVM timezone)

## Root Cause Analysis

### Current Flow

1. **Database stores timestamps**: `enrichment_started_at` column in Oracle `TIMESTAMP` type (no timezone)
2. **Application reads timestamp**: Uses `ResultSet.getTimestamp()` → `Timestamp.toInstant()`
3. **Query built**: `since` parameter becomes `@timestamp >= "2026-08-26T02:00:40Z"` (UTC string)
4. **Elasticsearch filters**: Compares UTC @timestamp against the converted value
5. **Mismatch occurs**: If Oracle stored local time (e.g., GMT+8), the query looks 8 hours ahead

### Example Mismatch

```
Database stored:     2026-08-26 10:00:00  (local time, but no TZ info)
Java reads as:       2026-08-26T10:00:00Z (assumes UTC)
ES query filters:    @timestamp >= "2026-08-26T10:00:00Z"
Actual ES logs:      @timestamp = "2026-08-26T02:00:00Z"  (8 hours earlier in UTC)
Result:              NO MATCH ❌
```

### Code Evidence

**ElasticsearchLogService.java:340-348**

```java
// Oracle stores TIMESTAMP columns without timezone info using the DB server's local time.
// Use Timestamp.toInstant() which correctly uses the millisecond epoch value from the JDBC driver,
// provided the JDBC connection timezone matches the DB server timezone (configured via
// refdb.connection-timezone in application.yml, defaulting to UTC for backward compatibility).
return timestamp.toInstant();
```

**Problem**: `refdb.connection-timezone` is NOT configured in application.yml, and the comment says it "defaults to UTC" but there's no code enforcing this.

## Solution: 3-Layer Fix

### 1. **Enforce UTC at JVM Level** (CRITICAL)

Force Java to treat all timestamps as UTC by setting JVM timezone.

**Option A: systemd service file** (RECOMMENDED for prod)

```ini
[Service]
Environment="JAVA_OPTS=-Duser.timezone=UTC"
ExecStart=/usr/bin/java $JAVA_OPTS -jar /path/to/exensioreload.jar
```

**Option B: Application startup**

```bash
java -Duser.timezone=UTC -jar exensioreload.jar
```

**Option C: Docker Compose**

```yaml
services:
  backend:
    environment:
      - JAVA_OPTS=-Duser.timezone=UTC
      - TZ=UTC
```

### 2. **Add Explicit JDBC Session Timezone** (DEFENSE IN DEPTH)

Configure Oracle JDBC connection to explicitly use UTC session timezone.

**Add to application.yml**:

```yaml
refdb:
  staging-table: SENDER_STAGE
  connection-timezone: UTC # NEW: Explicit JDBC session timezone
  pool:
    max-size: 5
    min-idle: 1
```

**Update RefDbService.java datasource initialization**:

```java
// In DataSource configuration or HikariCP setup
config.addDataSourceProperty("oracle.jdbc.timezoneAsRegion", "false");
config.addDataSourceProperty("oracle.sessionTimeZone", "UTC");
```

Or via JDBC URL parameter:

```yaml
spring:
  datasource:
    url: jdbc:oracle:thin:@//host:port/service?oracle.jdbc.timezoneAsRegion=false
```

### 3. **Validate Elasticsearch Query Range** (SAFETY NET)

Add a lookback buffer to account for potential drift.

**Update CpLogMonitor.java:147**:

```java
// Current: 2-minute lookback buffer
Instant esLookbackTime = lookbackTime.minusSeconds(120);

// Recommended: 15-minute lookback to account for timezone drift + processing delay
Instant esLookbackTime = lookbackTime.minusSeconds(900);  // 15 minutes
```

**Or make it configurable**:

```yaml
cp:
  elasticsearch:
    lookback-buffer-seconds: 900 # 15 minutes default
```

### 4. **Add Diagnostic Logging** (TROUBLESHOOTING)

```java
// In ElasticsearchLogService.buildQuery():
if (log.isDebugEnabled()) {
    log.debug("ES query @timestamp range: gte={}, since={}, systemTZ={}, sinceEpochMs={}",
        sinceStr, since, TimeZone.getDefault().getID(), since.toEpochMilli());
}
```

## Verification Steps

### 1. Check Current JVM Timezone

```java
System.out.println("JVM Timezone: " + TimeZone.getDefault().getID());
// Should print: UTC
```

### 2. Verify Oracle Session Timezone

```sql
SELECT SESSIONTIMEZONE, DBTIMEZONE FROM DUAL;
```

### 3. Compare Timestamps

```sql
-- Check a known enrichment record
SELECT
    id,
    data_id,
    enrichment_started_at,
    TO_CHAR(enrichment_started_at, 'YYYY-MM-DD"T"HH24:MI:SS"Z"') as utc_format
FROM SENDER_STAGE
WHERE id = <your_record_id>;
```

Then check Elasticsearch:

```json
GET /logs*dataport*/_search
{
  "query": {
    "bool": {
      "must": [
        {"term": {"idData": "your_data_id"}},
        {"range": {"@timestamp": {"gte": "2026-08-26T02:00:00Z"}}}
      ]
    }
  }
}
```

### 4. Test the Fix

```bash
# Restart with UTC timezone
systemctl restart exensioreload

# Check logs for successful ES matches
tail -f /var/log/exensioreload/exensioreload.log | grep "ES query RESULT: Success"
```

## Rollout Plan

### Phase 1: Immediate Fix (JVM Timezone)

1. Update systemd service file or Docker Compose with `-Duser.timezone=UTC`
2. Restart application
3. Monitor ES query success rate

### Phase 2: JDBC Configuration (Next Deployment)

1. Add `refdb.connection-timezone: UTC` to application.yml
2. Update Oracle datasource properties
3. Deploy with rolling restart

### Phase 3: Buffer Tuning (After Validation)

1. If still seeing misses, increase lookback buffer to 15-30 minutes
2. Add configurable property
3. Monitor for false positives (duplicate matches)

## Expected Outcome

- ✅ Timestamps stored in Oracle are interpreted as UTC
- ✅ Elasticsearch queries use correct UTC range
- ✅ Records match successfully within 2-15 minute lookback window
- ✅ No more "NotFound" for existing enrichment logs
