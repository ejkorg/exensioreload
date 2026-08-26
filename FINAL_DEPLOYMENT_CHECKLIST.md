# Final Deployment Checklist - Timezone Fix

## What We Fixed

✅ **PostgreSQL Database Timezone**: Changed from `Europe/Brussels` to `UTC`
✅ **JDBC URL**: Added `?TimeZone=UTC` parameter
✅ **Lookback Buffer**: Configured to 900 seconds (15 minutes)
✅ **Code**: Enhanced logging and configurable buffer

## What's Left to Do

### 1. Build and Deploy Updated Code

```bash
# Build the application with updated configuration
cd /path/to/exensioreload
mvn clean package -DskipTests

# Or if using pre-built jar, just copy the updated YAML files to:
# - backend/src/main/resources/application-onsemi-postgresql.yml
# - backend/src/main/resources/application-pg-local.yml
```

### 2. Update Startup Configuration

**Find your current startup method** (one of these):

#### Option A: systemd Service

```bash
sudo systemctl status exensioreload
# Note the service file location

sudo nano /etc/systemd/system/exensioreload.service
```

Add this line in `[Service]` section:

```ini
Environment="JAVA_OPTS=-Duser.timezone=UTC -Xmx2g"
ExecStart=/usr/bin/java $JAVA_OPTS -jar /opt/exensioreload/exensioreload.jar --spring.profiles.active=onsemi-postgresql
```

Then:

```bash
sudo systemctl daemon-reload
sudo systemctl restart exensioreload
```

#### Option B: Shell Script

Edit your startup script and add `-Duser.timezone=UTC`:

```bash
java -Duser.timezone=UTC -jar exensioreload.jar --spring.profiles.active=onsemi-postgresql
```

#### Option C: Direct Command

```bash
# Stop current process
pkill -f exensioreload.jar

# Start with UTC timezone
cd /opt/exensioreload
nohup java -Duser.timezone=UTC -jar exensioreload.jar --spring.profiles.active=onsemi-postgresql > /tmp/exensio.log 2>&1 &
```

### 3. Verify After Restart

```bash
# 1. Check JVM timezone in logs
tail -f /var/log/exensioreload/exensioreload.log | grep systemTZ
# Should see: systemTZ=UTC

# 2. Check ES query results
tail -f /var/log/exensioreload/exensioreload.log | grep "ES query RESULT"
# Should see: Success instead of NotFound

# 3. Verify JDBC connection timezone
tail -100 /var/log/exensioreload/exensioreload.log | grep -i timezone
```

## Summary of Changes

### Configuration Files

1. `application-onsemi-postgresql.yml`:
   - JDBC URL: `jdbc:postgresql://...?TimeZone=UTC`
2. `application.yml`:
   - Added: `refdb.connection-timezone: UTC`
   - Added: `cp.elasticsearch.lookback-buffer-seconds: 900`

3. Database:
   - `ALTER DATABASE exnr SET timezone TO 'UTC';`

### Code Files

1. `CpElasticsearchProperties.java`:
   - Added `lookbackBufferSeconds` property

2. `CpLogMonitor.java`:
   - Changed hardcoded 120s to configurable buffer

3. `ElasticsearchLogService.java`:
   - Enhanced debug logging with systemTZ

### Startup Configuration

- Add: `-Duser.timezone=UTC` to Java startup command

## Expected Results

### Before Fix

- ES queries return "NotFound" even when logs exist
- systemTZ shows "America/Phoenix" or "Europe/Brussels"
- Timestamp mismatches of 7-9 hours

### After Fix

- ES queries return "Success" for existing logs
- systemTZ shows "UTC"
- Timestamps align across DB, Java, and Elasticsearch
- Records process automatically without manual intervention

## Rollback Plan

If issues occur:

1. Revert database timezone (not recommended):

   ```sql
   ALTER DATABASE exnr SET timezone TO 'Europe/Brussels';
   ```

2. Remove JVM timezone flag from startup

3. Restart application

Note: Only rollback if the fix introduces NEW problems. The original timezone mismatch will return.

## Monitoring After Deployment

Watch these metrics for 24 hours:

```bash
# ES success rate
grep "ES query RESULT: Success" /var/log/exensioreload/exensioreload.log | wc -l

# ES not found rate
grep "ES query RESULT: NotFound" /var/log/exensioreload/exensioreload.log | wc -l

# System timezone confirmations
grep "systemTZ=UTC" /var/log/exensioreload/exensioreload.log | wc -l
```

## Tuning the Lookback Buffer

After 24 hours of stable operation:

**If no misses**: Reduce buffer to 300s (5 minutes)

```yaml
cp:
  elasticsearch:
    lookback-buffer-seconds: 300
```

**If still seeing misses**: Increase to 1800s (30 minutes)

```yaml
cp:
  elasticsearch:
    lookback-buffer-seconds: 1800
```

## Contact

If issues persist after deployment:

1. Check all three timezones are UTC (DB, JVM, logs)
2. Verify `systemTZ=UTC` appears in application logs
3. Compare ES @timestamp with database enrichment_started_at for the same record
