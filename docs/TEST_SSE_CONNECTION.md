# SSE Connection Test - Complete Fix

## Critical Changes Made

### 1. Backend - StageController.java
Added comprehensive SSE headers:
```java
response.setContentType("text/event-stream");
response.setCharacterEncoding("UTF-8");
response.setHeader("Cache-Control", "no-cache, no-store, must-revalidate");
response.setHeader("Pragma", "no-cache");
response.setHeader("Expires", "0");
response.setHeader("Connection", "keep-alive");
response.setHeader("X-Accel-Buffering", "no");
response.setHeader("Access-Control-Allow-Origin", "*");
response.setHeader("Access-Control-Allow-Credentials", "true");
```

### 2. Backend - StageMonitorService.java
Send initial events asynchronously after emitter is returned:
```java
CompletableFuture.runAsync(() -> {
    Thread.sleep(50); // Ensure response is committed
    emitter.send(SseEmitter.event().comment("SSE connection established"));
    emitter.send(SseEmitter.event().name("HEARTBEAT").data(...));
});
```

## Rebuild & Test

```bash
# 1. Rebuild backend
cd backend
mvn clean package -DskipTests

# 2. Restart backend application
# Stop and start your Spring Boot app

# 3. Clear browser cache
# Press Ctrl+Shift+Delete, clear cached images and files

# 4. Test
```

## Manual SSE Test (Without Frontend)

Open browser console and run:

```javascript
const token = localStorage.getItem('token');
const sessionId = 'test-session-id'; // Replace with actual session ID
const url = `/exensioreload/api/stage/sessions/${sessionId}/monitor?token=${token}`;

const eventSource = new EventSource(url);

eventSource.onopen = () => {
    console.log('✅ SSE Connection OPENED');
};

eventSource.onerror = (error) => {
    console.error('❌ SSE Connection ERROR:', error);
    console.error('ReadyState:', eventSource.readyState);
};

eventSource.addEventListener('HEARTBEAT', (event) => {
    console.log('💓 HEARTBEAT received:', event.data);
});

eventSource.addEventListener('FILE_UPDATE', (event) => {
    console.log('📄 FILE_UPDATE received:', event.data);
});

// To close:
// eventSource.close();
```

## What Should Happen

### Backend Logs (in order):
```
SSE monitor endpoint called for sessionId: xxx, token present: true
SSE headers set, creating emitter for sessionId: xxx
Creating SSE emitter for requestId: xxx
SSE emitter created successfully for requestId: xxx, total emitters: 1
Sending SSE connection comment for requestId: xxx
Sending initial HEARTBEAT for requestId: xxx
Initial SSE events sent successfully for requestId: xxx
```

### Browser Console (in order):
```
[StagingSession] Connecting SSE to: /exensioreload/api/stage/sessions/xxx/monitor?token=...
[StagingSession] Token length: 203
[StagingSession] SSE connection opened successfully
[StagingSession] EventSource readyState: 1
[StagingSession] HEARTBEAT received: {"timestamp":"...","requestId":"xxx"}
[StagingSession] Files loaded: 1 files
```

### Browser Network Tab:
- Request: `GET /exensioreload/api/stage/sessions/{id}/monitor?token=...`
- Status: `200` (or `pending` if still connected)
- Type: `eventsource`
- Response Headers:
  ```
  Content-Type: text/event-stream;charset=UTF-8
  Cache-Control: no-cache, no-store, must-revalidate
  Connection: keep-alive
  X-Accel-Buffering: no
  Access-Control-Allow-Origin: *
  ```

## If Still Not Working

### Test 1: Check if endpoint is reachable
```bash
curl -H "Authorization: Bearer YOUR_TOKEN" \
  http://localhost:8004/exensioreload/api/stage/sessions/test/monitor
```

Expected: Connection hangs (SSE keeps connection open)

### Test 2: Check response headers
```bash
curl -v -H "Authorization: Bearer YOUR_TOKEN" \
  http://localhost:8004/exensioreload/api/stage/sessions/test/monitor 2>&1 | grep -i "content-type"
```

Expected: `Content-Type: text/event-stream`

### Test 3: Check if events are sent
```bash
curl -N -H "Authorization: Bearer YOUR_TOKEN" \
  http://localhost:8004/exensioreload/api/stage/sessions/test/monitor
```

Expected: See `:SSE connection established` comment and `event: HEARTBEAT` data

### Test 4: Direct backend connection (bypass proxy)
In frontend, temporarily change the URL to:
```typescript
const url = `http://localhost:8004/exensioreload/api/stage/sessions/${sessionId}/monitor?token=${token}`;
```

If this works, the proxy is the issue.

## Common Issues & Solutions

| Issue | Cause | Solution |
|-------|-------|----------|
| Status 401 | Token invalid/expired | Refresh page to get new token |
| Status 403 | Missing ROLE_USER | Check user roles in JWT |
| Status 404 | Endpoint not found | Backend not restarted after rebuild |
| Type shows `xhr` not `eventsource` | Headers not set | Check response headers in Network tab |
| Connection closes immediately | Authentication failure | Check backend logs for auth errors |
| No `onopen` event | Response not flushed | Check if async events are being sent |
| CORS error in console | CORS headers missing | Check Access-Control-Allow-Origin header |

## Nuclear Option: Simplest Possible SSE Test

Create a test endpoint that doesn't require authentication:

```java
@GetMapping("/test-sse")
public SseEmitter testSse() {
    SseEmitter emitter = new SseEmitter();
    CompletableFuture.runAsync(() -> {
        try {
            Thread.sleep(100);
            emitter.send(SseEmitter.event().name("TEST").data("Hello SSE"));
            emitter.complete();
        } catch (Exception e) {
            emitter.completeWithError(e);
        }
    });
    return emitter;
}
```

Test in browser console:
```javascript
const es = new EventSource('/exensioreload/api/test-sse');
es.onopen = () => console.log('OPENED');
es.addEventListener('TEST', (e) => console.log('Received:', e.data));
```

If this works, the issue is with authentication or the specific endpoint configuration.
