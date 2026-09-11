# Nginx SSE Deployment Guide

## Current Issue

The SSE monitor endpoint returns 404 because the current nginx configuration is buffering the streaming response.

**Current Error:**

```
Request URL: https://usaz15ls088:8080/api/stage/sessions/{sessionId}/monitor?token=...
Status Code: 404 Not Found
```

## Root Cause

Your current nginx doesn't have the SSE-specific location block with buffering disabled. The general `/exensio-reload/api/` block is buffering the response, which breaks the streaming protocol.

## Solution: Deploy Updated Config

### Step 1: Backup Current Config

```bash
# SSH to your server
ssh dpower@usaz15ls088

# Backup current nginx.conf
cp /export/home/dpower/nginx/conf/nginx.conf /export/home/dpower/nginx/conf/nginx.conf.backup.$(date +%Y%m%d_%H%M%S)
```

### Step 2: Update Nginx Config

Replace the content of `/export/home/dpower/nginx/conf/nginx.conf` with the updated version from `docs/current_nginx_conf.md`.

**The key change:**

- Added new location block **before** the general API proxy:
  ```nginx
  location ~ /exensio-reload/api/stage/sessions/.*/monitor {
    proxy_buffering off;
    proxy_request_buffering off;
    # ... SSE-specific config
  }
  ```

### Step 3: Validate Config Syntax

```bash
# Test nginx config for syntax errors
/export/home/dpower/nginx/sbin/nginx -t -c /export/home/dpower/nginx/conf/nginx.conf

# Expected output:
# nginx: the configuration file /export/home/dpower/nginx/conf/nginx.conf syntax is ok
# nginx: configuration file /export/home/dpower/nginx/conf/nginx.conf test is successful
```

### Step 4: Reload Nginx (No Downtime)

```bash
# Gracefully reload nginx without dropping connections
/export/home/dpower/nginx/sbin/nginx -s reload -c /export/home/dpower/nginx/conf/nginx.conf

# Or if using systemd:
sudo systemctl reload nginx
```

### Step 5: Verify Nginx is Running

```bash
# Check if nginx is running
ps aux | grep nginx

# Check nginx error log for any issues
tail -50 /export/home/dpower/nginx/logs/error.log

# Check access log for monitor requests
tail -20 /export/home/dpower/nginx/logs/access.log | grep monitor
```

## Step 6: Test the SSE Endpoint

### Test 1: Health Check (should return 200)

```bash
curl -i -H "Authorization: Bearer YOUR_TOKEN" \
  https://usaz15ls088:8080/api/stage/sessions/292caccd-3f00-4e51-812e-05ff928a1647/monitor-health
```

**Expected Response:**

```
HTTP/1.1 200 OK
Content-Type: application/json

{
  "sessionId": "292caccd-3f00-4e51-812e-05ff928a1647",
  "status": "ok",
  "endpoint": "/api/stage/sessions/292caccd-3f00-4e51-812e-05ff928a1647/monitor",
  "sse_supported": true
}
```

### Test 2: Polling Status (should return 200)

```bash
curl -i -H "Authorization: Bearer YOUR_TOKEN" \
  https://usaz15ls088:8080/api/stage/sessions/292caccd-3f00-4e51-812e-05ff928a1647/status
```

**Expected Response:**

```
HTTP/1.1 200 OK
Content-Type: application/json

{
  "sessionId": "292caccd-3f00-4e51-812e-05ff928a1647",
  "site": "CEBU-PROD",
  "status": "IN_PROGRESS",
  "totalFiles": 100,
  "filesStaged": 75
}
```

### Test 3: SSE Endpoint (should establish connection)

```bash
# This will keep the connection open and stream events
curl -i -N \
  -H "Authorization: Bearer YOUR_TOKEN" \
  https://usaz15ls088:8080/api/stage/sessions/292caccd-3f00-4e51-812e-05ff928a1647/monitor

# Expected response (if events exist):
# HTTP/1.1 200 OK
# Content-Type: text/event-stream
# Transfer-Encoding: chunked
#
# : keepalive
# data: {"...event data..."}
#
# (connection stays open for streaming)
```

**Stop with:** Ctrl+C

## Troubleshooting

### Still getting 404?

1. **Check nginx was actually reloaded:**

   ```bash
   curl https://usaz15ls088:8080/api/stage/sessions/test/monitor -v 2>&1 | head -20
   # Look for response headers - if 404, nginx config didn't reload
   ```

2. **Check nginx error log:**

   ```bash
   tail -100 /export/home/dpower/nginx/logs/error.log | grep -i "monitor\|sse\|buffer"
   ```

3. **Verify location block order:**
   - The SSE location MUST come before the general `/exensio-reload/api/` block
   - Nginx matches locations in this order: exact > regex > prefix
   - Check: `grep -n "location.*monitor\|location.*api/" /export/home/dpower/nginx/conf/nginx.conf`

4. **Check backend is responding:**
   ```bash
   # Bypass nginx, hit backend directly
   curl -i http://usaz15ls088:8004/api/stage/sessions/test/monitor-health
   # Should return 200, not 404
   ```

### Getting 502 Bad Gateway?

```bash
# Check backend service is running
ps aux | grep 8004

# Check backend error logs
tail -50 /var/log/exensio-reload/error.log
# or wherever your backend logs are
```

### Still getting connection timeout on UI?

Even if nginx is fixed, the UI needs to be updated to:

1. Try SSE first
2. Fall back to polling if SSE times out
3. Use the polling endpoint every 5 seconds

See `MONITOR_SSE_404_TROUBLESHOOTING.md` for frontend implementation.

## Verification Checklist

- [ ] Backed up current nginx.conf
- [ ] Updated nginx.conf with new SSE location block
- [ ] Ran `nginx -t` and verified syntax OK
- [ ] Reloaded nginx with `nginx -s reload`
- [ ] Tested health check endpoint (200 OK)
- [ ] Tested polling status endpoint (200 OK)
- [ ] Tested SSE endpoint (200 OK, streaming)
- [ ] Browser DevTools shows monitor endpoint working
- [ ] Monitor UI displays real-time updates

## Rollback (if needed)

If something goes wrong:

```bash
# Restore backup
cp /export/home/dpower/nginx/conf/nginx.conf.backup.* /export/home/dpower/nginx/conf/nginx.conf

# Reload
/export/home/dpower/nginx/sbin/nginx -s reload -c /export/home/dpower/nginx/conf/nginx.conf
```

## Next Steps

After nginx is deployed:

1. **Backend deployment** - restart backend service with pipeline-aware code
2. **Frontend update** - implement polling fallback in UI JavaScript
3. **Test full flow** - stage records → verify pipeline execution → check monitor updates
