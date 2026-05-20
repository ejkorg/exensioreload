# SSE Connection - Final Fix

## What Was Changed

### 1. StageController.java - Comprehensive SSE Headers
- Set `Content-Type: text/event-stream` explicitly
- Added all cache-control headers
- Added CORS headers
- Added test endpoint `/api/stage/test-sse` for debugging

### 2. StageMonitorService.java - Async Event Sending
- Events now sent asynchronously AFTER emitter is returned
- 50ms delay ensures response is committed before sending data
- Uses CompletableFuture for proper async handling

## Step-by-Step Fix

### Step 1: Rebuild Backend
```bash
cd backend
mvn clean package -DskipTests
```

### Step 2: Restart Backend
Stop and restart your Spring Boot application.

### Step 3: Test SSE is Working (Simple Test)

Open browser console and run:
```javascript
const es = new EventSource('/resender/api/stage/test-sse');
es.onopen = () => console.log('✅ SSE WORKS!');
es.onerror = (e) => console.error('❌ SSE FAILED:', e, 'ReadyState:', es.readyState);
es.addEventListener('TEST', (e) => console.log('📨 Received:', e.data));
```

**Expected Output:**
```
✅ SSE WORKS!
📨 Received: {"message":"SSE is working!","timestamp":"..."}
```

**If this fails:**
- Check backend logs for "Test SSE endpoint called"
- Check Network tab for `/test-sse` request
- Check response headers include `Content-Type: text/event-stream`

### Step 4: Test Authenticated SSE

After confirming Step 3 works, test the actual monitoring endpoint:

```javascript
const token = localStorage.getItem('token');
const sessionId = '0c44de62-a6be-49bc-9f6e-a54b3a38adfe'; // Use your actual session ID
const url = `/resender/api/stage/sessions/${sessionId}/monitor?token=${token}`;

const es = new EventSource(url);
es.onopen = () => console.log('✅ Monitoring SSE OPENED');
es.onerror = (e) => console.error('❌ Monitoring SSE ERROR:', e, 'ReadyState:', es.readyState);
es.addEventListener('HEARTBEAT', (e) => console.log('💓 HEARTBEAT:', e.data));
es.addEventListener('FILE_UPDATE', (e) => console.log('📄 FILE_UPDATE:', e.data));
```

### Step 5: Use the Application

1. Clear browser cache (Ctrl+Shift+Delete)
2. Refresh the page
3. Stage a file
4. Monitor should connect automatically

## Debugging Checklist

### ✅ Backend Logs Should Show:
```
Test SSE endpoint called
Sending test SSE event
Test SSE event sent successfully
---
SSE monitor endpoint called for sessionId: xxx, token present: true
SSE headers set, creating emitter for sessionId: xxx
Creating SSE emitter for requestId: xxx
SSE emitter created successfully for requestId: xxx, total emitters: 1
Sending SSE connection comment for requestId: xxx
Sending initial HEARTBEAT for requestId: xxx
Initial SSE events sent successfully for requestId: xxx
```

### ✅ Browser Console Should Show:
```
[StagingSession] Connecting SSE to: /resender/api/stage/sessions/xxx/monitor?token=...
[StagingSession] SSE connection opened successfully
[StagingSession] EventSource readyState: 1
[StagingSession] HEARTBEAT received: {...}
[StagingSession] Files loaded: 1 files
```

### ✅ Browser Network Tab Should Show:
- Request: `GET /resender/api/stage/sessions/{id}/monitor?token=...`
- Status: `200` or `(pending)`
- Type: `eventsource`
- Response Headers:
  - `Content-Type: text/event-stream;charset=UTF-8`
  - `Cache-Control: no-cache, no-store, must-revalidate`
  - `Connection: keep-alive`

## If Test SSE Works But Monitoring Doesn't

This means SSE is working, but there's an issue with authentication or the specific endpoint.

### Check 1: Token Valid?
```javascript
const token = localStorage.getItem('token');
console.log('Token:', token);
console.log('Token length:', token?.length);
```

### Check 2: Session ID Valid?
```javascript
// After staging, check the session ID
console.log('Session ID:', sessionId);
```

### Check 3: Backend Receiving Request?
Check backend logs for:
```
SSE monitor endpoint called for sessionId: xxx, token present: true
```

If this line doesn't appear, the request isn't reaching the backend.

### Check 4: Authentication Working?
If you see `401` or `403` in Network tab, authentication is failing.

Check JWT filter is extracting token from query param:
```java
if (token == null && request.getRequestURI().contains("/monitor")) {
    String queryToken = request.getParameter("token");
    if (queryToken != null && !queryToken.isEmpty()) {
        token = queryToken;
    }
}
```

## Root Cause Analysis

The issue was likely:
1. **Missing Content-Type header** - Browser didn't recognize response as SSE
2. **Events sent before response committed** - Data sent before connection established
3. **Buffering** - Proxy or server buffering the response

The fix:
1. **Explicit headers** - Set all required SSE headers explicitly
2. **Async event sending** - Send events after emitter is returned and response is committed
3. **Anti-buffering headers** - `X-Accel-Buffering: no` prevents nginx buffering

## Success Criteria

✅ Test SSE endpoint works (`/api/stage/test-sse`)
✅ Monitoring SSE connects (`onopen` event fires)
✅ HEARTBEAT events received every 15 seconds
✅ File updates appear in real-time
✅ No "Connecting to monitoring stream..." spinner stuck
✅ Files list populates immediately

## Still Not Working?

If after all this it still doesn't work, the issue is likely:

1. **Proxy configuration** - Angular dev proxy or nginx is interfering
2. **Browser issue** - Try different browser or incognito mode
3. **Network issue** - Firewall or antivirus blocking SSE
4. **Spring Boot version issue** - Some versions have SSE bugs

Try direct backend connection (bypass proxy):
```typescript
// In staging-session.service.ts, temporarily change:
const url = `http://localhost:8004/resender/api/stage/sessions/${sessionId}/monitor?token=${token}`;
```

If this works, the proxy is the problem.
