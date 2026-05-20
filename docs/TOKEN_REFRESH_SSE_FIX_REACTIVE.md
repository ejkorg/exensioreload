# Token Refresh SSE Reconnection Fix (Reactive Solution)

## Problem

Users were being logged out while monitoring because JWT tokens expired while the SSE connection was still using the old token.

## Solution: Reactive Observable Pattern

Instead of polling localStorage every 10 seconds, we use RxJS observables to react immediately when the token changes.

### Files Modified

1. **`new_frontend/src/app/auth/auth.service.ts`** - Added token$ observable
2. **`new_frontend/src/app/shared/services/staging-session.service.ts`** - Subscribe to token changes

### Benefits Over Polling

| Aspect | Polling | Reactive (New) |
|--------|---------|----------------|
| Reconnection Delay | 0-10 seconds | < 100ms |
| CPU Usage | Continuous | Zero (event-driven) |
| Code Pattern | Imperative | Reactive (Angular idiomatic) |
| Testability | Hard | Easy (mock observable) |

## Implementation

### AuthService: Expose Token Observable

```typescript
private tokenSubject = new BehaviorSubject<string | null>(null);
token$ = this.tokenSubject.asObservable();

private setSession(token: string | null) {
    // ... existing code
    if (token) {
        this.tokenSubject.next(token); // Emit new token
    } else {
        this.tokenSubject.next(null); // Emit logout
    }
}
```

### StagingSessionService: Subscribe to Token Changes

```typescript
import { AuthService } from '../../auth/auth.service';
import { filter, distinctUntilChanged, skip } from 'rxjs/operators';

private tokenSubscription?: Subscription;
private authService = inject(AuthService);

constructor(private backend: BackendService, private zone: NgZone) {
  this.subscribeToTokenChanges();
}

private subscribeToTokenChanges(): void {
  this.tokenSubscription = this.authService.token$.pipe(
    skip(1),                          // Skip initial value
    filter(token => token !== null),  // Only new tokens (not logout)
    distinctUntilChanged()            // Only when actually changes
  ).subscribe(() => {
    if (this.currentSessionId && this.eventSource) {
      console.log('[StagingSession] Token refreshed, reconnecting SSE...');
      const sessionId = this.currentSessionId;
      this.disconnectSession();
      this.connectSse(sessionId);
    }
  });
}
```

## Why This Is Better

1. **Immediate**: Reconnects within milliseconds, not 0-10 seconds
2. **Zero Overhead**: No continuous polling, only reacts when needed
3. **Angular Idiomatic**: Uses RxJS observables (the Angular way)
4. **Extensible**: Other services can subscribe to token$ if needed
5. **Testable**: Easy to mock AuthService.token$ in unit tests

## Testing

Set JWT TTL to 60 seconds: `jwt.ttl=60`

Expected behavior:
- At 30s: Token refreshes
- Within 100ms: Console shows "Token refreshed, reconnecting SSE..."
- SSE reconnects immediately
- No logout, no missed events
