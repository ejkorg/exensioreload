# Elasticsearch Timezone Fix - Deployment Guide

## Quick Summary

The Elasticsearch query mismatch is caused by timezone inconsistency. This fix enforces UTC across the entire stack.

## What Changed

### 1. Configuration Files

- **application.yml**: Added `refdb.connection-timezone: UTC` and `cp.elasticsearch.lookback-buffer-seconds: 900`
- **CpElasticsearchProperties.java**: Added `lookbackBufferSeconds` property with getter/setter
- **CpLogMonitor.java**: Changed hardcoded 120s buffer to use configurable property
- **ElasticsearchLogService.java**: Enhanced timestamp debug logging

### 2. JVM Timezone (CRITICAL - Manual Step Required)

You MUST configure the JVM to use UTC timezone at startup.

## Deployment Steps

### Step 1: Update Code

```bash
# Already done via the changes above
git add backend/src/main/resources/application.yml
git add backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/config/CpElasticsearchProperties.java
git add backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/service/CpLogMonitor.java
git add backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/service/ElasticsearchLogService.java
git commit -m "Fix Elasticsearch timestamp mismatch by enforcing UTC"
```

### Step 2: Configure JVM Timezone

#### Option A: systemd Service File (RECOMMENDED for Linux)

Edit `/etc/systemd/system/exensioreload.service`:

```ini
[Unit]
Description=Exensio Reload Service
After=network.target

[Service]
Type=simple
User=exensio
WorkingDirectory=/opt/exensioreload
Environment="JAVA_OPTS=-Duser.timezone=UTC -Xmx2g -Xms512m"
ExecStart=/usr/bin/java $JAVA_OPTS -jar /opt/exensioreload/exensioreload.jar
Restart=on-failure
RestartSec=10

[Install]
WantedBy=multi-user.target
```

Then reload and restart:

```bash
sudo systemctl daemon-reload
sudo systemctl restart exensioreload
sudo systemctl status exensioreload
```

#### Option B: Docker Compose

Edit `docker-compose.yml`:

```yaml
services:
  backend:
    image: exensioreload:latest
    environment:
      - JAVA_OPTS=-Duser.timezone=UTC
      - TZ=UTC
      # ... other env vars
    ports:
      - '8004:8004'
```

Then restart:

```bash
docker-compose down
docker-compose up -d
```

#### Option C: Manual Startup Script

Edit your startup script:

```bash
#!/bin/bash
export JAVA_OPTS="-Duser.timezone=UTC -Xmx2g"
java $JAVA_OPTS -jar /opt/exensioreload/exensioreload.jar
```

### Step 3: Verify Configuration

#### 3.1 Check Application Logs

```bash
tail -f /var/log/exensioreload/exensioreload.log | grep "systemTZ"
```

You should see:

```
ES query @timestamp range: gte=2026-08-26T02:00:00Z, since=2026-08-26T02:00:00Z, sinceEpochMs=1787896800000, systemTZ=UTC
```

**If systemTZ is NOT UTC**, the JVM timezone was not set correctly. Go back to Step 2.

#### 3.2 Verify JDBC Connection

Enable debug logging temporarily in `application.yml`:

```yaml
logging:
  level:
    com.zaxxer.hikari: DEBUG
    oracle.jdbc: DEBUG
```

Check logs for Oracle connection timezone warnings.

#### 3.3 Test Elasticsearch Match

Monitor for successful matches:

```bash
tail -f /var/log/exensioreload/exensioreload.log | grep "ES query RESULT"
```

Expected output:

```
ES query RESULT: Success (output path found) for dataId=YOUR_DATA_ID
```

### Step 4: Tune Lookback Buffer (Optional)

If you still see mismatches after confirming UTC is enforced:

**Increase buffer to 30 minutes**:

```yaml
cp:
  elasticsearch:
    lookback-buffer-seconds: 1800 # 30 minutes
```

**After stabilization, reduce back to 2 minutes**:

```yaml
cp:
  elasticsearch:
    lookback-buffer-seconds: 120 # 2 minutes (aggressive)
```

## Verification Checklist

- [ ] Code changes deployed
- [ ] JVM timezone set to UTC (`systemTZ=UTC` in logs)
- [ ] Application restarted
- [ ] ES queries showing Success results
- [ ] No "NotFound" for records with existing ES logs
- [ ] Oracle connection using UTC session timezone
- [ ] lookback-buffer-seconds configured (900 default, tune as needed)

## Rollback Plan

If the fix causes issues:

1. **Revert JVM timezone**: Remove `-Duser.timezone=UTC` from startup
2. **Revert code**: `git revert <commit-hash>`
3. **Restart application**

Note: Reverting will bring back the original mismatch problem. Rollback should only be used if the fix introduces NEW issues.

## Expected Improvements

✅ **Before Fix**:

- ES queries return "NotFound" even when logs exist
- Timestamp mismatch of 6-8 hours (depending on local timezone)
- Manual verification required for most enrichments

✅ **After Fix**:

- ES queries match logs within 15-minute window
- Consistent UTC timestamps across all layers
- Automatic enrichment status detection

## Troubleshooting

### Issue: Still seeing NotFound after deployment

**Check**: Is `systemTZ=UTC` in the logs?

- If NO → JVM timezone not set, go back to Step 2
- If YES → Increase lookback buffer to 30 minutes

### Issue: Duplicate matches or stale logs

**Check**: Is lookback buffer too large?

- Reduce to 300 seconds (5 minutes)
- Monitor for missed matches

### Issue: Oracle connection errors after deployment

**Check**: Is Oracle JDBC driver compatible with session timezone?

- Verify driver version >= 19c
- Check for timezone-related SQLExceptions in logs

## Support

For questions or issues:

1. Check `ELASTICSEARCH_TIMESTAMP_MISMATCH_FIX.md` for detailed technical analysis
2. Review application logs with DEBUG level enabled
3. Verify timezone at OS, JVM, JDBC, and Elasticsearch layers
