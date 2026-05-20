# SSE Monitoring Connection Fix

## Problem

The frontend Monitor Dispatch view was stuck on "Connecting to monitoring stream..." even though the backend successfully staged payloads and sent ROW_UPDATE events.

### Root Causes

1. **Authentication Issue**: EventSource (used for SSE connections) doesn't support custom HTTP headers, including the `Authorization: Bearer <token>` header. The SSE connection was being rejected by Spring Security.

2. **Data Mapping Issue**: The stepper component was checking the old `MonitoringService` for data (`monitoring.stats()`, `monitoring.files()`), but the actual data was being populated in the new `StagingSessionService` (`stagingSession.currentSession()`, `stagingSession.sessionFiles()`).

## Solution

### Part 1: SSE Authentication Fix

Modified both frontend and backend to pass JWT token as a query parameter for SSE endpoints, since EventSource cannot send custom headers.

#### Frontend Changes

**File: `new_frontend/src/app/shared/services/staging-session.service.ts`**

Updated `connectSse()` method to include token as query parameter:

```typescript
private connectSse(sessionId: string): void {
  // EventSource doesn't support custom headers, so we need to pass the token as a query parameter
  const token = localStorage.getItem('token') || '';
  const url = `/exensioreload/api/stage/sessions/${encodeURIComponent(sessionId)}/monitor?token=${encodeURIComponent(token)}`;
  this.eventSource = new EventSource(url);
  // ... rest of the method
}
```

#### Backend Changes

**1. StageController.java** - Updated both SSE endpoints to accept optional token parameter:

```java
@GetMapping("/monitor")
public SseEmitter monitor(@RequestParam String requestId,
                          @RequestParam(required = false) String token) {
    // Token parameter is for EventSource compatibility
    return monitorService.createEmitter(requestId);
}

@GetMapping("/sessions/{sessionId}/monitor")
public SseEmitter monitorSession(@PathVariable String sessionId,
                                 @RequestParam(required = false) String token) {
    // Token parameter is for EventSource compatibility
    return monitorService.subscribe(sessionId);
}
```

**2. JwtAuthenticationFilter.java** - Enhanced to extract token from query parameters for SSE endpoints:

```java
@Override
protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) {
    String token = null;
    
    // First, try to get token from Authorization header
    String authHeader = request.getHeader("Authorization");
    if (authHeader != null && authHeader.startsWith("Bearer ")) {
        token = authHeader.substring(7);
    }
    
    // For SSE endpoints, also check query parameter (EventSource can't send custom headers)
    if (token == null && request.getRequestURI().contains("/monitor")) {
        String queryToken = request.getParameter("token");
        if (queryToken != null && !queryToken.isEmpty()) {
            token = queryToken;
        }
    }
    
    // Validate and set authentication context
    if (token != null) {
        if (jwtUtil.validateToken(token)) {
            // ... set authentication
        }
    }
    
    filterChain.doFilter(request, response);
}
```

### Part 2: Data Mapping Fix

The stepper component was using the old `MonitoringService` to check for data, but the new `StagingSessionService` was actually populating the data. Fixed by:

1. **Updated `hasMonitoringData()` computed signal** to check `stagingSession` instead of `monitoring`
2. **Added computed signals** to map `stagingSession` data to the format expected by monitoring components
3. **Updated template** to use the new computed signals

#### Frontend Changes

**File: `new_frontend/src/app/stepper/stepper.component.ts`**

Added three new computed signals:

```typescript
// Map stagingSession data to MonitoringStats format
monitoringStats = computed(() => {
    const session = this.stagingSession.currentSession();
    const total = session?.totalFiles || 0;
    const ready = session?.filesStaged || 0;
    const enqueued = session?.filesEnqueued || 0;
    const completed = session?.filesDone || 0;
    const failed = session?.filesFailed || 0;
    // ... calculate progress, throughput, ETA, etc.
    return { total, ready, enqueued, processing, completed, failed, progress, throughput, eta, successRate, startTime, elapsedTime };
});

// Map stagingSession files to MonitoringFile format
monitoringFiles = computed(() => {
    return this.stagingSession.sessionFiles().map((file: StageRecordView) => ({
        id: file.id,
        metadataId: file.metadataId,
        dataId: file.dataId,
        filename: file.filename || '',
        lot: file.lot || '',
        wafer: file.wafer || '',
        status: this.mapBackendStatus(file.status),
        message: file.status || '',
        errorMessage: file.errorMessage,
        updatedAt: file.updated
    }));
});

// Updated hasMonitoringData to check stagingSession
hasMonitoringData = computed(() => {
    const session = this.stagingSession.currentSession();
    if ((session?.totalFiles || 0) > 0) return true;
    if (this.stagingSession.sessionFiles().length > 0) return true;
    return this.stagingSession.activities().length > 0;
});
```

Added helper methods:

```typescript
private formatETA(minutes: number): string { /* ... */ }
private formatDuration(ms: number): string { /* ... */ }
```

**File: `new_frontend/src/app/stepper/stepper.component.html`**

Updated template to use new computed signals:

```html
<app-monitoring-stats [stats]="monitoringStats()"></app-monitoring-stats>
<app-monitoring-file-list [files]="monitoringFiles()"></app-monitoring-file-list>
```

## How It Works Now

1. Frontend creates a session and gets a `sessionId`
2. Frontend stages payloads with `requestId: sessionId` in the request body
3. Frontend connects to SSE endpoint with token in query: `/stage/sessions/{sessionId}/monitor?token=<jwt>`
4. Backend JWT filter extracts token from query parameter for `/monitor` endpoints
5. Spring Security authenticates the request
6. SSE connection is established successfully
7. Backend broadcasts ROW_UPDATE events to the connected client
8. Frontend receives events via `stagingSession.sessionFiles()` signal
9. Computed signals map the data to monitoring component formats
10. UI displays files with status indicators, stats, and activity feed in real-time

## UI Display (According to STAGING_MONITORING_DESIGN.md)

The Monitor Dispatch view now shows:

1. **Summary Metrics Card** - Total files, staged, enqueued, completed, failed with progress bar
2. **Lot/Wafer Progress Tree** - Collapsible lots showing wafer-level progress
3. **File List Table** - Paginated list of files with status indicators:
   - **Staged** (Blue) - NEW status
   - **Queued** (Yellow) - ENQUEUED status
   - **Processing** (Orange) - ENQUEUED but not in external queue
   - **Completed** (Green) - DONE status
   - **Failed** (Red) - FAILED status
4. **Activity Feed** - Real-time stream of events (file staged, queued, completed, etc.)
5. **Action Buttons** - Stop Monitoring, Reconnect Live, Refresh, Export CSV, Finish

## Testing

To verify the fix works:

1. Rebuild backend: `mvn clean package` (in `backend/` directory)
2. Restart backend server
3. Rebuild frontend: `npm run build` (in `new_frontend/` directory)
4. Navigate to the stepper and stage a payload
5. Monitor Dispatch view should now show:
   - Summary metrics with file counts and progress
   - File list table with status indicators
   - Activity feed showing real-time events
   - Lot/wafer progress (if applicable)

## Security Considerations

- Token is passed in query parameter only for SSE endpoints (URLs containing `/monitor`)
- Token is still validated using the same JWT validation logic
- Spring Security's `@PreAuthorize` annotations still enforce role-based access
- Query parameter approach is standard for SSE authentication when custom headers aren't supported
- Consider using short-lived tokens or implementing token refresh for long-running SSE connections

## Files Modified

### Backend
- `backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/controller/StageController.java`
- `backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/config/JwtAuthenticationFilter.java`

### Frontend
- `new_frontend/src/app/shared/services/staging-session.service.ts`
- `new_frontend/src/app/stepper/stepper.component.ts`
- `new_frontend/src/app/stepper/stepper.component.html`

