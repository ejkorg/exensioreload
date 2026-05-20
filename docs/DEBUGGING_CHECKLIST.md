# Monitoring Issue - Debugging Checklist

**Quick References:**
- [SSE Connection Debug Improvements](SSE_CONNECTION_DEBUG_IMPROVEMENTS.md) - Latest fixes and detailed explanation
- [SSE Quick Test Guide](SSE_QUICK_TEST.md) - Fast testing checklist

## Current Status
UI stuck on "Connecting to monitoring stream..." even though backend successfully stages files.

## Debugging Steps

### 1. Check Browser Console (F12)

Look for these console logs:

**Expected logs when staging:**
```
[STAGING] Creating session...
[STAGING] Session created: <sessionId>
[STAGING] Staging payloads...
[STAGING] Staging response received
[STEPPER] startMonitoring called, sessionId: <sessionId>
[STEPPER] Starting monitoring for session: <sessionId>
[StagingSession] connectToSession called with sessionId: <sessionId>
[StagingSession] loadSnapshot called: <sessionId>
[StagingSession] loadFiles called: { sessionId, page: 0, size: 100 }
[StagingSession] Connecting SSE to: /exensioreload/api/stage/sessions/<sessionId>/monitor?token=...
[StagingSession] Token length: <number>
[StagingSession] SSE connection opened successfully
[StagingSession] Session snapshot loaded: { ... }
[StagingSession] Files loaded: <number> files
```

**Check for errors:**
- Red error messages
- "SSE connection error"
- "Failed to load files"
- "Failed to load snapshot"
- 401 Unauthorized
- 403 Forbidden

### 2. Check Network Tab (F12 → Network)

**Filter by "monitor":**
- Should see: `GET /exensioreload/api/stage/sessions/<sessionId>/monitor?token=...`
- Status should be: `200 OK` (or pending for SSE)
- Type should be: `eventsource`

**If request is missing:**
- `startMonitoring()` not being called
- Check if `currentStep() === 2`

**If request fails (401/403):**
- Token is invalid or missing
- Check token in URL query parameter
- Check backend JWT filter logs

**If request succeeds but no data:**
- SSE connection established but no events
- Check backend logs for "Sending ROW_UPDATE event"

### 3. Check Backend Logs

**Expected logs when SSE connects:**
```
SSE monitor endpoint called for sessionId: <sessionId>, token present: true
Creating SSE emitter for requestId: <sessionId>
Sending initial HEARTBEAT for requestId: <sessionId>
SSE emitter created successfully for requestId: <sessionId>, total emitters: 1
```

**Expected logs when staging:**
```
stagePayloads called site=... senderId=... requestedBy=... payloadsCount=...
Sending ROW_UPDATE event for metadataId=... dataId=...
ROW_UPDATE event sent successfully
```

**Check for errors:**
- "Failed to send initial HEARTBEAT"
- "SSE emitter error"
- Authentication errors
- No emitters for requestId

### 4. Verify Session ID

**In browser console, check:**
```javascript
// Should show the session ID
console.log('Session ID:', this.requestId());
```

**Session ID should:**
- Be a UUID format (e.g., `550e8400-e29b-41d4-a716-446655440000`)
- Match between frontend and backend logs
- Be the same in SSE URL and staging request

### 5. Verify Token

**In browser console, check:**
```javascript
// Should show the token
console.log('Token:', localStorage.getItem('auth_token'));
console.log('Token:', sessionStorage.getItem('accessToken'));
```

**Token should:**
- Exist (not null or empty)
- Be a JWT format (three parts separated by dots)
- Not be expired

### 6. Check Data Loading

**In browser console after staging:**
```javascript
// Should show session data
console.log('Current session:', this.stagingSession.currentSession());
console.log('Session files:', this.stagingSession.sessionFiles());
console.log('Is connected:', this.stagingSession.isConnected());
console.log('Stream status:', this.stagingSession.streamStatus());
```

**Expected values:**
- `currentSession`: Object with totalFiles, filesStaged, etc.
- `sessionFiles`: Array with staged files
- `isConnected`: true (if SSE connected)
- `streamStatus`: 'live' (if SSE connected) or 'polling' (if fallback)

### 7. Check hasMonitoringData()

**In browser console:**
```javascript
// Should return true if data exists
console.log('Has monitoring data:', this.hasMonitoringData());
console.log('Monitor UI state:', this.monitorUiState());
```

**If hasMonitoringData() is false:**
- Session data not loaded
- Files not loaded
- Check HTTP requests in Network tab

## Common Issues

### Issue 1: SSE Connection Fails (401/403)
**Cause**: Token not being sent or invalid
**Fix**: Check token retrieval in `connectSse()`

### Issue 2: SSE Connection Succeeds but No Data
**Cause**: Backend not broadcasting to correct sessionId
**Fix**: Verify sessionId matches between frontend and backend

### Issue 3: Data Loaded but UI Shows "Connecting..."
**Cause**: `hasMonitoringData()` returning false
**Fix**: Check computed signal logic

### Issue 4: startMonitoring() Not Called
**Cause**: Step change not triggering monitoring
**Fix**: Check `onStepChange()` logic

### Issue 5: Session ID Mismatch
**Cause**: Different sessionId used for staging vs monitoring
**Fix**: Ensure `requestId` is set correctly after session creation

## Quick Fixes to Try

1. **Hard refresh**: Ctrl+Shift+R (clear cache)
2. **Check step**: Manually navigate to step 3
3. **Manual trigger**: In console: `this.startMonitoring()`
4. **Check auth**: Logout and login again
5. **Restart backend**: Ensure latest code is running

## Files to Check

### Frontend
- `new_frontend/src/app/stepper/stepper.component.ts` - startMonitoring()
- `new_frontend/src/app/shared/services/staging-session.service.ts` - connectSse()
- `new_frontend/src/app/auth/auth.service.ts` - getToken()

### Backend
- `backend/.../controller/StageController.java` - SSE endpoint
- `backend/.../stage/StageMonitorService.java` - SSE emitter
- `backend/.../config/JwtAuthenticationFilter.java` - Token extraction
- `backend/.../service/RefDbService.java` - Event broadcasting
