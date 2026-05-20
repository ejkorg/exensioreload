# SSE Connection Debug Improvements

## Problem
Frontend SSE connection attempts but never receives `onopen` event or any data from backend, despite backend logs showing emitter created and HEARTBEAT sent.

## Changes Made

### Backend Changes

#### 1. StageController.java - Added SSE Headers
**File**: `backend/src/main/java/com/onsemi/cim/apps/exensio/resender/controller/StageController.java`

Added proper SSE headers to the monitor endpoint:
- `produces = "text/event-stream"` - Explicitly declares SSE content type
- `Cache-Control: no-cache` - Prevents caching of SSE stream
- `Connection: keep-alive` - Keeps connection open
- `X-Accel-Buffering: no` - Disables nginx buffering (important for proxied SSE)

```java
@GetMapping(path = "/sessions/{sessionId}/monitor", produces = "text/event-stream")
public SseEmitter monitorSession(@PathVariable String sessionId,
                                 @RequestParam(required = false) String token,
                                 jakarta.servlet.http.HttpServletResponse response) {
    response.setHeader("Cache-Control", "no-cache");
    response.setHeader("Connection", "keep-alive");
    response.setHeader("X-Accel-Buffering", "no");
    return monitorService.subscribe(sessionId);
}
```

#### 2. StageMonitorService.java - Improved Connection Establishment
**File**: `backend/src/main/java/com/onsemi/cim/apps/exensio/resender/stage/StageMonitorService.java`

Reordered operations to establish connection properly:
1. Create emitter
2. Register callbacks (onCompletion, onTimeout, onError)
3. Send initial comment (establishes SSE connection)
4. Send HEARTBEAT event

The initial comment is crucial - it flushes the response and establishes the SSE connection before sending actual events.

### Frontend Changes

#### 3. staging-session.service.ts - Enhanced Error Logging
**File**: `new_frontend/src/app/shared/services/staging-session.service.ts`

Added detailed logging in error handler:
- Log EventSource readyState (0=CONNECTING, 1=OPEN, 2=CLOSED)
- Log EventSource URL
- Interpret readyState to understand connection status
- Clear timeout on successful connection

Also added readyState logging in onopen handler.

## Testing Steps

### 1. Rebuild Backend
```bash
cd backend
mvn clean package -DskipTests
```

### 2. Restart Backend
Stop and restart the Spring Boot application to load new code.

### 3. Rebuild Frontend
```bash
cd new_frontend
npm run build
```

### 4. Test SSE Connection

1. Open browser DevTools (F12)
2. Go to Network tab
3. Stage a file
4. Look for the SSE connection request: `GET /resender/api/stage/sessions/{sessionId}/monitor?token=...`

**Check these details:**
- Status should be `200` (or `pending` if still connected)
- Type should be `eventsource`
- Response Headers should include:
  - `Content-Type: text/event-stream`
  - `Cache-Control: no-cache`
  - `Connection: keep-alive`

5. Check Console logs for:
```
[StagingSession] SSE connection opened successfully
[StagingSession] EventSource readyState: 1
[StagingSession] HEARTBEAT received: {...}
```

6. Check Backend logs for:
```
SSE monitor endpoint called for sessionId: xxx
Creating SSE emitter for requestId: xxx
Sending SSE connection comment for requestId: xxx
Sending initial HEARTBEAT for requestId: xxx
SSE emitter created successfully for requestId: xxx
```

## Expected Behavior After Fix

1. Frontend connects to SSE endpoint
2. Backend sends initial comment (connection established)
3. Frontend receives `onopen` event
4. Backend sends HEARTBEAT
5. Frontend receives HEARTBEAT event
6. Files are loaded via polling (every 2 seconds)
7. Real-time updates received via SSE when files change

## Common Issues

### Issue: Connection shows 401 Unauthorized
**Cause**: Token not being extracted from query parameter
**Check**: JWT filter logs, verify token is valid

### Issue: Connection shows 403 Forbidden
**Cause**: User doesn't have ROLE_USER permission
**Check**: User roles in JWT token

### Issue: Connection closes immediately (readyState=2)
**Cause**: Server rejecting connection or proxy issue
**Check**: 
- Backend logs for errors
- Proxy configuration (nginx/Angular proxy)
- CORS settings

### Issue: Connection stuck in CONNECTING (readyState=0)
**Cause**: Response not being flushed, buffering issue
**Check**:
- `X-Accel-Buffering: no` header present
- Proxy not buffering responses
- Initial comment being sent

### Issue: onopen fires but no events received
**Cause**: Event name mismatch or JSON parsing error
**Check**:
- Event names match (HEARTBEAT, FILE_UPDATE, etc.)
- Event data is valid JSON
- Event listeners registered before events sent

## Why These Changes Matter

### SSE Headers
Without proper headers, proxies (nginx, Angular dev proxy) may buffer the response, preventing the EventSource from establishing. The `text/event-stream` content type is required by the SSE spec.

### Initial Comment
The initial comment forces the response to flush immediately, establishing the connection. Without it, the response may be buffered until enough data accumulates.

### Callback Order
Registering callbacks before sending data ensures errors during initial send are properly handled.

### Enhanced Logging
Detailed readyState logging helps diagnose exactly where the connection fails:
- 0 (CONNECTING): Connection attempt in progress
- 1 (OPEN): Connection established successfully
- 2 (CLOSED): Connection failed or closed

## Next Steps If Still Not Working

1. Check Network tab for actual HTTP status code
2. Check Response Headers in Network tab
3. Check if proxy is interfering (test direct backend connection)
4. Check browser console for CORS errors
5. Verify token is valid and not expired
6. Check backend logs for authentication errors
