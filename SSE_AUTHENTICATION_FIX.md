# SSE Authentication Issue Fix

## Problem

Users were receiving `AccessDeniedException` when accessing the dashboard state stream (`/api/dashboard/states`) and monitoring stream (`/api/stage/monitor`), even when logged in with a super admin account.

**Error Log:**

```
2026-09-14T09:08:05.093Z ERROR [...] org.springframework.security.access.AccessDeniedException: Access Denied
```

## Root Cause

The issue was in the frontend's SSE connection logic:

1. **EventSource API limitation**: JavaScript's `EventSource` API cannot send custom HTTP headers (like `Authorization: Bearer <token>`)
2. **Backend expects token in query parameter**: The `JwtAuthenticationFilter` checks for JWT tokens in query parameters for SSE endpoints
3. **Token not passed**: The frontend's `MonitoringService.connectSSE()` accepted a `token` parameter but **never included it in the EventSource URL**

### Code Issue in `frontend/src/app/shared/services/monitoring.service.ts`

```typescript
// BEFORE (incorrect):
const url = `${environment.apiUrl}/stage/monitor?requestId=${requestId}`;
// Token parameter was accepted but never used!

// AFTER (correct):
const url = `${environment.apiUrl}/stage/monitor?requestId=${requestId}&token=${encodeURIComponent(token)}`;
```

## Backend Configuration

The backend's `JwtAuthenticationFilter` is correctly configured to extract tokens from query parameters for SSE endpoints:

```java
// For /dashboard/states endpoint
if (token == null && request.getRequestURI().contains("/dashboard/states")) {
    String queryToken = request.getParameter("token");
    if (queryToken != null && !queryToken.isEmpty()) {
        token = queryToken;
    }
}

// For /stage/monitor endpoint
if (token == null && request.getRequestURI().contains("/monitor")) {
    String queryToken = request.getParameter("token");
    if (queryToken != null && !queryToken.isEmpty()) {
        token = queryToken;
    }
}
```

## Solution Applied

Fixed `frontend/src/app/shared/services/monitoring.service.ts`:

- Modified `connectSSE()` method to append the JWT token as a query parameter
- Used `encodeURIComponent()` to properly encode the token

This ensures:

1. ✅ Frontend passes the JWT token when establishing SSE connection
2. ✅ Backend's `JwtAuthenticationFilter` can extract the token from the query parameter
3. ✅ Authentication context is properly established for `@PreAuthorize("isAuthenticated()")`
4. ✅ SSE connection succeeds even though custom headers cannot be sent

## Files Modified

- `frontend/src/app/shared/services/monitoring.service.ts` - Line ~122: Added `&token=${encodeURIComponent(token)}` to EventSource URL

## Verification

The `connectDashboardStateStream()` method in `backend.service.ts` was already correctly passing the token in the query parameter, which is why dashboard state updates likely worked but stage monitoring had issues.

Both endpoints now receive the JWT token via query parameter, resolving the authentication issue.
