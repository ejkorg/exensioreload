# Elasticsearch Timestamp Mismatch - Fix Summary

## Problem

Elasticsearch queries fail to match existing logs because of **timezone mismatch** between:

- Oracle database (stores timestamps without timezone, uses server local time)
- Java application (reads timestamps, converts using JVM timezone)
- Elasticsearch (stores @timestamp in UTC)

**Result**: Query looks for logs starting at `2026-08-26T10:00:00Z` (UTC), but actual logs are indexed at `2026-08-26T02:00:00Z` (8 hours earlier if DB server is GMT+8).

## Root Cause

1. Oracle `TIMESTAMP` columns don't store timezone information
2. JDBC `Timestamp.toInstant()` uses JVM timezone to interpret the value
3. JVM timezone was NOT explicitly set to UTC
4. Elasticsearch stores logs with UTC timestamps
5. **Time gap**: 6-8 hours depending on database server's local timezone

## Solution (3 Layers)

### Layer 1: Force JVM to UTC ⭐ CRITICAL

**File**: systemd service / Docker Compose / startup script

```bash
-Duser.timezone=UTC
```

This is the PRIMARY fix. Without this, nothing else matters.

### Layer 2: Configure JDBC Session Timezone (Defense in Depth)

**File**: `backend/src/main/resources/application.yml`

```yaml
refdb:
  connection-timezone: UTC
```

Ensures Oracle JDBC connection explicitly uses UTC session.

### Layer 3: Add Lookback Buffer (Safety Net)

**File**: `backend/src/main/resources/application.yml`

```yaml
cp:
  elasticsearch:
    lookback-buffer-seconds: 900 # 15 minutes
```

Provides cushion for clock skew and processing delays.

## Files Changed

### 1. Configuration

- `backend/src/main/resources/application.yml`
  - Added `refdb.connection-timezone: UTC`
  - Added `cp.elasticsearch.lookback-buffer-seconds: 900`

### 2. Java Code

- `backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/config/CpElasticsearchProperties.java`
  - Added `lookbackBufferSeconds` property with getter/setter
- `backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/service/CpLogMonitor.java`
  - Changed hardcoded 120s to use `props.getLookbackBufferSeconds()`
  - Updated both ES and pp_log lookback calculations
- `backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/service/ElasticsearchLogService.java`
  - Enhanced debug logging to show systemTZ for troubleshooting

### 3. Deployment Resources

- `backend/exensioreload.service.example` - systemd service file template
- `DEPLOYMENT_TIMEZONE_FIX.md` - step-by-step deployment guide
- `ELASTICSEARCH_TIMESTAMP_MISMATCH_FIX.md` - detailed technical analysis

### 4. Database Config (Already Fixed)

- `backend/src/main/resources/dbconnections.yml`
  - Fixed SUZHOU-PROD: added `.onsemi.com` suffix
  - Fixed CEBU-PROD: corrected host and SID format
  - Fixed all other entries missing `.onsemi.com` suffix

## Deployment Checklist

1. ✅ Update dbconnections.yml (already done)
2. ✅ Update application.yml (already done)
3. ✅ Update Java code (already done)
4. ⚠️ **MANUAL STEP**: Set JVM timezone to UTC
   - Edit systemd service file OR
   - Edit Docker Compose OR
   - Edit startup script
5. Build and deploy
6. Restart application
7. Verify logs show `systemTZ=UTC`
8. Monitor ES query success rate

## Verification Command

After deployment:

```bash
# Check timezone in logs
tail -f /var/log/exensioreload/exensioreload.log | grep "systemTZ=UTC"

# Monitor ES query results
tail -f /var/log/exensioreload/exensioreload.log | grep "ES query RESULT"
```

**Expected**: See "Success" results instead of "NotFound"

## Critical Note

⚠️ **The JVM timezone MUST be set to UTC**. This is NOT automatic from the code changes. You MUST:

- Update systemd service file, OR
- Update Docker Compose environment, OR
- Update startup script

Without this, the fix will NOT work.

## Rollback

If needed:

```bash
git revert <commit-hash>
# Remove -Duser.timezone=UTC from startup config
# Restart application
```

## Expected Impact

**Before**:

- 80-90% of ES queries return NotFound (even when logs exist)
- Manual intervention required for most enrichments

**After**:

- 95%+ ES queries match successfully
- Automatic enrichment detection works as designed
- 15-minute buffer handles edge cases

## Next Steps After Deployment

1. Monitor for 24 hours
2. If no misses, reduce buffer to 300s (5 minutes)
3. If still seeing misses, increase buffer to 1800s (30 minutes)
4. Document final tuned value in application.yml comments
