# SSE Monitor 404 Error - Troubleshooting Guide

## Problem

When clicking "Reconnect Live" in the Monitor UI, the following error occurs:

```
Request URL: https://usaz15ls088:8080/api/stage/sessions/{sessionId}/monitor?token=...
Request Method: GET
Status Code: 404 Not Found
```

## Root Causes

The 404 error for SSE endpoints typically occurs due to:

1. **Nginx/Reverse Proxy Buffering**: Nginx buffers SSE responses by default, causing 404 errors
2. **Load Balancer Configuration**: Some load balancers don't support streaming responses
3. **Firewall/WAF Rules**: Web Application Firewalls may block streaming endpoints
4. **Spring Boot Configuration**: Incorrect content-type negotiation or servlet configuration
5. **Path Matching Issues**: Spring may not be routing to the SSE endpoint correctly

## Solutions

### Solution 1: Disable Nginx Buffering (If using Nginx Reverse Proxy)

Add this location block to your Nginx configuration **BEFORE** the general `/exensio-reload/api/` location block:

```nginx
# --- SSE Monitor Endpoint (MUST come before general /api/ proxy) ---
# This handles the real-time monitor streaming endpoint with no buffering
location ~ /exensio-reload/api/stage/sessions/.*/monitor {
  proxy_pass http://exensioreload_backend;
  proxy_http_version 1.1;

  # Critical: disable all buffering for SSE streaming
  proxy_buffering off;
  proxy_request_buffering off;

  # Headers for streaming
  proxy_set_header Connection "";
  proxy_set_header Host $host;
  proxy_set_header X-Real-IP $remote_addr;
  proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
  proxy_set_header X-Forwarded-Proto $scheme;
  proxy_set_header Authorization $http_authorization;
  proxy_set_header X-Forwarded-Host $host;
  proxy_set_header X-Forwarded-Port $server_port;
  proxy_set_header X-Forwarded-Prefix /exensio-reload;

  # Disable nginx's buffering to prevent 404 errors
  proxy_set_header X-Accel-Buffering "no";

  # Long timeouts for SSE (30+ minutes)
  proxy_connect_timeout 60s;
  proxy_send_timeout 3600s;
  proxy_read_timeout 3600s;

  # Prevent nginx from caching SSE responses
  proxy_cache_bypass $http_pragma $http_authorization;
  add_header Cache-Control "no-store, no-cache, must-revalidate, max-age=0" always;
}
```

**Key Points:**

- Place this **before** the general `/exensio-reload/api/` location block
- `proxy_buffering off` prevents nginx from buffering the stream
- `proxy_set_header X-Accel-Buffering "no"` tells nginx to not buffer internally
- Long timeouts (3600s) allow SSE connections to stay open for up to 1 hour
- The regex `~ /exensio-reload/api/stage/sessions/.*/monitor` matches all monitor endpoints

**Implementation in your nginx.conf:**

Edit your nginx configuration and locate the section that looks like:

```nginx
location /exensio-reload/api/ {
    proxy_pass http://exensioreload_backend;
    # ... rest of config
}
```

Insert the SSE location block **immediately before** this general API location. The order matters because nginx matches more specific locations first.

### Solution 2: Use Polling Fallback Instead of SSE

The system now provides a polling endpoint as a workaround:

**Endpoint:** `GET /api/stage/sessions/{sessionId}/status`

**Example:**

```bash
curl -H "Authorization: Bearer TOKEN" \
  https://usaz15ls088:8080/api/stage/sessions/292caccd-3f00-4e51-812e-05ff928a1647/status
```

**Response:**

```json
{
  "sessionId": "292caccd-3f00-4e51-812e-05ff928a1647",
  "site": "CEBU-PROD",
  "senderId": 123,
  "senderName": "Sender Name",
  "status": "IN_PROGRESS",
  "totalFiles": 100,
  "filesStaged": 75,
  "filesEnqueued": 15,
  "filesDone": 10,
  "filesFailed": 0,
  "timestamp": "2025-01-15T10:00:00Z"
}
```

**Frontend Implementation (JavaScript):**

```javascript
// Poll every 5 seconds instead of using SSE
async function pollSessionStatus(sessionId, token) {
  try {
    const response = await fetch(`/api/stage/sessions/${sessionId}/status?token=${token}`, {
      method: 'GET',
      headers: {
        Authorization: `Bearer ${token}`,
        'Content-Type': 'application/json',
      },
    });

    if (!response.ok) {
      console.error('Status poll failed:', response.status);
      return null;
    }

    const status = await response.json();
    return status;
  } catch (error) {
    console.error('Poll error:', error);
    return null;
  }
}

// Call periodically
setInterval(() => pollSessionStatus(sessionId, token), 5000);
```

### Solution 3: Health Check Endpoint

Before attempting to connect to SSE, verify the endpoint is accessible:

**Endpoint:** `GET /api/stage/sessions/{sessionId}/monitor-health`

**Example:**

```bash
curl -H "Authorization: Bearer TOKEN" \
  https://usaz15ls088:8080/api/stage/sessions/292caccd-3f00-4e51-812e-05ff928a1647/monitor-health
```

**Response:**

```json
{
  "sessionId": "292caccd-3f00-4e51-812e-05ff928a1647",
  "status": "ok",
  "endpoint": "/api/stage/sessions/292caccd-3f00-4e51-812e-05ff928a1647/monitor",
  "timestamp": "2025-01-15T10:00:00Z",
  "sse_supported": true,
  "fallback_polling_endpoint": "/api/stage/sessions/292caccd-3f00-4e51-812e-05ff928a1647/status"
}
```

## Recommended Approach

1. **Check health** using `/monitor-health` endpoint
2. **Try SSE** via `/monitor` endpoint
3. **Fall back to polling** using `/status` endpoint if SSE fails

This provides a resilient system that works with or without SSE support.

## Additional Notes

### Pipeline Integration with Monitor

The recent changes to `SenderDispatchService` ensure that:

1. **Records properly route through pipelines** based on first stage configuration
2. **Dashboard accurately reflects** discovered and staged records
3. **Monitor displays correct status** through either SSE or polling

### For CEBU-PROD with PP_LOG Pipeline

Records will now:

- Be dispatched to CP (first stage) ✓
- Transition to PP_LOG monitoring when CP completes ✓
- Transition to Exensio monitoring when PP_LOG completes ✓
- Be marked as COMPLETED when Exensio verifies ✓

All status changes are emitted via SSE (if working) or can be polled via the status endpoint.

## Testing Steps

1. Stage records for CEBU-PROD site
2. Verify they appear in dashboard
3. Open monitor and click "Reconnect Live"
4. If 404 occurs, UI should automatically fall back to polling
5. If polling fails, use `/monitor-health` to diagnose the issue
6. Check server logs for detailed error messages

## Logs to Check

Enable DEBUG logging to see detailed SSE endpoint calls:

```yaml
logging:
  level:
    com.onsemi.cim.apps.exensio.exensioreload.controller.StageController: DEBUG
```

Look for messages like:

- "SSE monitor endpoint called for sessionId"
- "SSE headers set successfully"
- "Session X not found, but allowing SSE connection"

## Related Files Modified

- `backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/service/SenderDispatchService.java` - Pipeline-aware dispatch
- `backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/controller/StageController.java` - Monitor endpoints
- `backend/src/main/resources/dbconnections.yml` - Pipeline configuration for CEBU-PROD

## Deployment Notes

After deploying these changes:

1. Rebuild and restart the backend service
2. Clear browser cache to ensure new endpoint paths are registered
3. Test with the polling fallback first to verify data flow
4. Then test SSE connectivity
5. Monitor server logs during the first test
