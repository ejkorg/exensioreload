# SSE Token Key Fix

## Problem

The SSE connection was failing because it was looking for the wrong token key in localStorage.

**Symptom**: UI stuck on "Connecting to monitoring stream..." even though backend successfully staged files and broadcast ROW_UPDATE events.

## Root Cause

```typescript
// staging-session.service.ts (WRONG)
const token = localStorage.getItem('token') || '';

// But AuthService stores token as:
localStorage.setItem('auth_token', token);
sessionStorage.setItem('accessToken', token);
```

The SSE connection was passing an empty string as the token, causing authentication to fail.

## Solution

Use `AuthService.getToken()` method instead of directly accessing localStorage:

```typescript
// staging-session.service.ts (CORRECT)
const token = this.authService.getToken() || '';
```

This ensures we get the token from the same source that AuthService uses.

## File Modified

- `new_frontend/src/app/shared/services/staging-session.service.ts`

## Why This Happened

The token key mismatch occurred because:
1. Different parts of the codebase used different key names
2. Direct localStorage access bypassed the centralized AuthService
3. No type safety for localStorage keys

## Best Practice

**Always use AuthService.getToken()** instead of directly accessing localStorage for tokens. This:
- ✅ Ensures consistency across the app
- ✅ Centralizes token management
- ✅ Makes it easier to change storage strategy later
- ✅ Provides a single source of truth

## Testing

After this fix:
1. Stage a file
2. SSE connection should establish immediately
3. Console should show: "SSE connection established"
4. Files should appear in the monitoring UI with status badges
5. No "Connecting to monitoring stream..." stuck state
