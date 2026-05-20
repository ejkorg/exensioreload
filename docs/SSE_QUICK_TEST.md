# SSE Connection - Quick Test Guide

## Rebuild & Restart

```bash
# Backend
cd backend
mvn clean package -DskipTests
# Then restart your Spring Boot app

# Frontend
cd new_frontend
npm run build
# Or if using dev server: npm start
```

## What to Check

### 1. Browser Network Tab (F12 → Network)
Look for: `GET /exensioreload/api/stage/sessions/{sessionId}/monitor?token=...`

**Good Signs:**
- Status: `200` or `(pending)`
- Type: `eventsource`
- Headers include: `Content-Type: text/event-stream`

**Bad Signs:**
- Status: `401` → Token issue
- Status: `403` → Permission issue
- Status: `404` → Endpoint not found
- Type: `xhr` or `fetch` → Not recognized as SSE

### 2. Browser Console
**Good Signs:**
```
[StagingSession] Connecting SSE to: /exensioreload/api/stage/sessions/xxx/monitor?token=...
[StagingSession] SSE connection opened successfully
[StagingSession] EventSource readyState: 1
[StagingSession] HEARTBEAT received: {...}
[StagingSession] Files loaded: 1 files
```

**Bad Signs:**
```
[StagingSession] SSE connection error
[StagingSession] EventSource readyState: 2  ← Connection closed
[StagingSession] Connection closed by server or failed to connect
```

### 3. Backend Logs
**Good Signs:**
```
SSE monitor endpoint called for sessionId: xxx, token present: true
Creating SSE emitter for requestId: xxx
Sending SSE connection comment for requestId: xxx
Sending initial HEARTBEAT for requestId: xxx
SSE emitter created successfully for requestId: xxx, total emitters: 1
```

**Bad Signs:**
```
Invalid or expired token
Failed to send initial comment
Failed to send initial HEARTBEAT
```

## Quick Diagnosis

| Symptom | Likely Cause | Fix |
|---------|--------------|-----|
| No "SSE connection opened" log | Connection not establishing | Check Network tab status code |
| readyState: 0 for >3 seconds | Buffering or proxy issue | Check proxy config, headers |
| readyState: 2 immediately | Server rejecting connection | Check backend logs for errors |
| Opens but no HEARTBEAT | Event not being sent/received | Check backend logs, event names |
| HEARTBEAT received but no files | Files endpoint failing | Check `/sessions/{id}/files` in Network tab |

## Most Likely Issues

1. **Token expired** → Refresh page to get new token
2. **Backend not restarted** → Restart Spring Boot app
3. **Frontend not rebuilt** → Run `npm run build` or restart dev server
4. **Proxy buffering** → Check if `X-Accel-Buffering: no` header present
5. **CORS issue** → Check browser console for CORS errors

## Success Criteria

✅ Network tab shows `eventsource` connection with status `200` or `(pending)`
✅ Console shows "SSE connection opened successfully"
✅ Console shows "HEARTBEAT received"
✅ Console shows "Files loaded: X files"
✅ Monitoring UI shows file status and progress
