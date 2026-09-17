# SSE 502 Bad Gateway Fix

## Root Cause

The SSE monitoring endpoint was returning 502 Bad Gateway errors due to **duplicate `Transfer-Encoding: chunked` headers**:

1. Backend `StageController.monitorSession()` method explicitly set:

   ```java
   response.setHeader("Transfer-Encoding", "chunked");
   ```

2. Spring Boot's `SseEmitter` framework **automatically** adds this same header

3. Nginx received duplicate headers and rejected the response:
   ```
   upstream sent duplicate header line: "Transfer-Encoding: chunked",
   previous value: "Transfer-Encoding: chunked"
   ```

## Fix Applied

**File**: `backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/controller/StageController.java`

**Line**: ~489 (in `monitorSession` method)

**Change**: Removed explicit `Transfer-Encoding` header setting:

```java
// BEFORE (WRONG):
response.setHeader("Transfer-Encoding", "chunked");

// AFTER (CORRECT):
// NOTE: Do NOT set Transfer-Encoding manually - Spring's SseEmitter sets it automatically
// Setting it here causes duplicate headers that nginx rejects with 502 Bad Gateway
```

## Related Configuration

- **Nginx timeout**: Already set to 3600s (1 hour) in `/export/home/dpower/nginx/conf/nginx.conf` line 190
- **Backend SSE timeout**: Already updated to 60 minutes in `StageMonitorService.java` line 41

## Testing Steps

1. Rebuild backend on remote development node:

   ```bash
   cd backend
   mvn clean package -DskipTests
   ```

2. Restart backend service:

   ```bash
   # Stop existing service
   systemctl --user stop exensioreload

   # Start service
   systemctl --user start exensioreload

   # Check status
   systemctl --user status exensioreload
   ```

3. Test SSE connection:
   - Navigate to monitoring page for session `9ad7c25b-2825-423d-9364-3d5a037b3fa5`
   - Check browser console - should see:
     - `[StagingSession] EventSource URL: ...`
     - `[StagingSession] SSE connection established`
     - No 502 errors
   - EventSource should stay open (`readyState: 1`)
   - Should receive HEARTBEAT events every 15 seconds

4. Check nginx logs (should have no errors):

   ```bash
   tail -f /export/home/dpower/nginx/logs/error.log
   ```

5. Check backend logs:
   ```bash
   journalctl --user -u exensioreload -f
   ```

   - Should see: `SSE emitter created successfully for requestId: ...`
   - Should NOT see: `upstream sent duplicate header`

## Expected Behavior After Fix

- SSE connection establishes successfully
- QUEUED and ENRICHMENT cards on dashboard update in real-time
- No more 502 Bad Gateway errors
- Connection stays open for up to 60 minutes

## Dashboard Card Updates

Once SSE is working:

- Dashboard cards should show real-time updates via SSE STATS events
- The fallback logic added in `dashboard.component.ts` (lines 400-405) ensures cards show correct values:
  ```typescript
  value: s.global.queued ?? s.global.queuedForCp ?? 0;
  value: s.global.enriching ?? s.global.elasticsearchMonitoring ?? 0;
  ```

## Session-Specific vs Global Metrics

Note: The dashboard shows **global** counts across all sessions/sites. For session-specific monitoring:

- Use the monitoring page for the specific session
- Session counters track only records where `request_id = <sessionId>` in `SENDER_STAGE` table
- External queue `DTP_SENDER_QUEUE_ITEM` has no `request_id` field - tracked only locally in `SENDER_STAGE`
