# RxJS vs Signals: When to Use Each in Angular

## Overview

Angular now offers two reactive programming paradigms:
- **Signals** (Angular 16+): Synchronous, fine-grained reactivity
- **RxJS Observables**: Asynchronous, stream-based reactivity

Both are powerful, but each excels in different scenarios.

## Quick Decision Matrix

```
┌─────────────────────────────────────────────────────┐
│  Use SIGNALS for:                                   │
│  ✓ Component UI state                               │
│  ✓ Computed/derived values                          │
│  ✓ Template bindings                                │
│  ✓ Synchronous state changes                        │
│  ✓ Simple reactivity                                │
└─────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────┐
│  Use RxJS OBSERVABLES for:                          │
│  ✓ Cross-service communication                      │
│  ✓ Async operations (HTTP, timers, WebSocket, SSE)  │
│  ✓ Complex transformations (operators)              │
│  ✓ Multiple independent subscribers                 │
│  ✓ Event streams over time                          │
│  ✓ Cancellation (unsubscribe)                       │
└─────────────────────────────────────────────────────┘
```

## Real-World Examples from This Project

### Example 1: Token Refresh (RxJS) ✅

**Scenario**: AuthService needs to notify other services when JWT token refreshes.

**Why RxJS?**
- Cross-service communication (AuthService → StagingSessionService)
- Multiple services may need to subscribe
- Need operators: `skip()`, `filter()`, `distinctUntilChanged()`
- Async event that happens over time

```typescript
// AuthService
private tokenSubject = new BehaviorSubject<string | null>(null);
token$ = this.tokenSubject.asObservable();

private setSession(token: string | null) {
    this.tokenSubject.next(token); // Emit to all subscribers
}

// StagingSessionService
this.authService.token$.pipe(
    skip(1),                          // Skip initial value
    filter(token => token !== null),  // Only new tokens
    distinctUntilChanged()            // Only when changes
).subscribe(() => {
    this.reconnectSSE();
});
```

**If we used Signals instead:**
```typescript
// ❌ Problems with Signal approach:
effect(() => {
    const token = this.authService.token();
    // Can't skip initial value easily
    // Can't filter null values cleanly
    // Runs on EVERY change (harder to control)
    // Less suitable for cross-service events
});
```

### Example 2: Component UI State (Signals) ✅

**Scenario**: Stepper component tracks current step and form state.

**Why Signals?**
- Component-local state
- Synchronous updates
- Direct template binding
- Computed derived state

```typescript
// Stepper Component
currentStep = signal(0);
selectedSite = signal<string | null>(null);
previewLoading = signal(false);

// Computed derived state
canProceedToPreview = computed(() => 
    !!this.selectedSite() && 
    !this.previewLoading() &&
    this.lotWaferPairs().length > 0
);

// Template binding (no async pipe needed)
<button [disabled]="!canProceedToPreview()">
    Preview Payloads
</button>
```

**If we used RxJS instead:**
```typescript
// ❌ More verbose with RxJS:
private currentStepSubject = new BehaviorSubject(0);
currentStep$ = this.currentStepSubject.asObservable();

canProceedToPreview$ = combineLatest([
    this.selectedSite$,
    this.previewLoading$,
    this.lotWaferPairs$
]).pipe(
    map(([site, loading, pairs]) => 
        !!site && !loading && pairs.length > 0
    )
);

// Template needs async pipe
<button [disabled]="!(canProceedToPreview$ | async)">
```

### Example 3: HTTP Requests (RxJS) ✅

**Scenario**: Fetching data from backend API.

**Why RxJS?**
- Async operation
- Need operators: `map()`, `catchError()`, `retry()`
- Cancellation support
- Standard Angular HttpClient returns Observables

```typescript
// BackendService
getSession(sessionId: string): Observable<SessionDetail> {
    return this.http.get<SessionDetail>(`/api/sessions/${sessionId}`).pipe(
        retry(2),
        catchError(err => {
            console.error('Failed to fetch session', err);
            return throwError(() => err);
        })
    );
}

// Component
loadSession(id: string) {
    this.backend.getSession(id).subscribe({
        next: (session) => this.currentSession.set(session),
        error: (err) => this.toast.error('Failed to load session')
    });
}
```

### Example 4: Monitoring Stats (Signals) ✅

**Scenario**: Display aggregate statistics computed from session data.

**Why Signals?**
- Synchronous computation
- Derived from other signals
- Template binding
- No async operations

```typescript
// Stepper Component
monitoringStats = computed(() => {
    const session = this.stagingSession.currentSession();
    const total = session?.totalFiles || 0;
    const completed = session?.filesDone || 0;
    const failed = session?.filesFailed || 0;
    
    const progress = total > 0 ? (completed + failed) / total * 100 : 0;
    
    return { total, completed, failed, progress };
});

// Template
<div class="stats">
    <span>{{ monitoringStats().total }} files</span>
    <span>{{ monitoringStats().progress }}% complete</span>
</div>
```

## Detailed Comparison

### Signals

**Strengths:**
- ✅ **Simpler syntax**: Less boilerplate than RxJS
- ✅ **Automatic cleanup**: No need to unsubscribe
- ✅ **Fine-grained updates**: Only affected parts re-render
- ✅ **Synchronous**: Immediate updates, no async timing issues
- ✅ **Type-safe**: Full TypeScript inference
- ✅ **Template-friendly**: No `async` pipe needed
- ✅ **Computed values**: `computed()` for derived state
- ✅ **Effects**: `effect()` for side effects

**Limitations:**
- ❌ **No operators**: Can't use `map`, `filter`, `debounce`, etc.
- ❌ **Synchronous only**: Not ideal for async operations
- ❌ **Limited control**: `effect()` runs on every change
- ❌ **Cross-service**: Less idiomatic for service-to-service communication
- ❌ **No cancellation**: Can't "unsubscribe" from effects easily

**Best For:**
- Component state management
- UI-driven reactivity
- Computed/derived values
- Template bindings

### RxJS Observables

**Strengths:**
- ✅ **Rich operators**: 100+ operators for transformation
- ✅ **Async-first**: Built for async operations
- ✅ **Cancellation**: `unsubscribe()` to stop streams
- ✅ **Multiple subscribers**: Many consumers of same stream
- ✅ **Time-based**: `debounceTime`, `throttle`, `delay`
- ✅ **Error handling**: `catchError`, `retry`, `retryWhen`
- ✅ **Combination**: `combineLatest`, `merge`, `zip`
- ✅ **Cross-service**: Standard pattern for service events

**Limitations:**
- ❌ **More boilerplate**: Subjects, subscriptions, unsubscribe
- ❌ **Memory leaks**: Must remember to unsubscribe
- ❌ **Async pipe**: Needed in templates (or manual subscribe)
- ❌ **Learning curve**: Operators can be complex
- ❌ **Timing issues**: Async nature can cause race conditions

**Best For:**
- HTTP requests
- WebSocket/SSE streams
- Cross-service communication
- Complex async workflows
- Event streams

## Hybrid Approach: Best of Both Worlds

You can convert between them:

### Observable → Signal

```typescript
import { toSignal } from '@angular/core/rxjs-interop';

// Service exposes Observable
token$ = this.tokenSubject.asObservable();

// Component converts to Signal
tokenSignal = toSignal(this.authService.token$, { initialValue: null });

// Use in computed
isAuthenticated = computed(() => !!this.tokenSignal());
```

### Signal → Observable

```typescript
import { toObservable } from '@angular/core/rxjs-interop';

// Component has Signal
currentStep = signal(0);

// Convert to Observable for RxJS operators
currentStep$ = toObservable(this.currentStep);

// Use with operators
currentStep$.pipe(
    debounceTime(300),
    distinctUntilChanged()
).subscribe(step => console.log('Step changed:', step));
```

## Migration Strategy

### When Migrating from RxJS to Signals

**Good candidates:**
- Component-local state (form values, UI flags)
- Computed values (derived from other state)
- Template bindings (remove `async` pipe)

**Keep as RxJS:**
- HTTP requests
- Cross-service communication
- Complex async workflows
- Anything using operators

### Example Migration

**Before (RxJS):**
```typescript
private loadingSubject = new BehaviorSubject(false);
loading$ = this.loadingSubject.asObservable();

private dataSubject = new BehaviorSubject<Data[]>([]);
data$ = this.dataSubject.asObservable();

hasData$ = this.data$.pipe(
    map(data => data.length > 0)
);

// Template
<div *ngIf="loading$ | async">Loading...</div>
<div *ngIf="hasData$ | async">{{ data$ | async }}</div>
```

**After (Signals):**
```typescript
loading = signal(false);
data = signal<Data[]>([]);

hasData = computed(() => this.data().length > 0);

// Template
<div *ngIf="loading()">Loading...</div>
<div *ngIf="hasData()">{{ data() }}</div>
```

## Common Patterns

### Pattern 1: Service Event Bus (RxJS)

```typescript
@Injectable({ providedIn: 'root' })
export class EventBusService {
    private eventSubject = new Subject<AppEvent>();
    events$ = this.eventSubject.asObservable();
    
    emit(event: AppEvent) {
        this.eventSubject.next(event);
    }
}

// Multiple services can subscribe
this.eventBus.events$.pipe(
    filter(e => e.type === 'USER_LOGGED_IN')
).subscribe(event => {
    // React to event
});
```

### Pattern 2: Form State (Signals)

```typescript
export class FormComponent {
    formData = signal({
        username: '',
        email: '',
        password: ''
    });
    
    isValid = computed(() => {
        const data = this.formData();
        return data.username.length > 0 &&
               data.email.includes('@') &&
               data.password.length >= 8;
    });
    
    updateField(field: string, value: string) {
        this.formData.update(data => ({ ...data, [field]: value }));
    }
}
```

### Pattern 3: Async Data Loading (RxJS + Signals)

```typescript
export class DataComponent {
    // Signal for state
    data = signal<Data[]>([]);
    loading = signal(false);
    error = signal<string | null>(null);
    
    // Observable for HTTP
    loadData() {
        this.loading.set(true);
        this.error.set(null);
        
        this.http.get<Data[]>('/api/data').pipe(
            retry(2),
            catchError(err => {
                this.error.set(err.message);
                return of([]);
            }),
            finalize(() => this.loading.set(false))
        ).subscribe(data => this.data.set(data));
    }
}
```

## Performance Considerations

### Signals
- **Fine-grained updates**: Only affected components re-render
- **No zone.js**: Can run outside Angular zones
- **Synchronous**: No async overhead
- **Memory**: Lightweight, automatic cleanup

### RxJS
- **Zone.js overhead**: Triggers change detection
- **Subscription memory**: Must unsubscribe to prevent leaks
- **Async overhead**: Microtask queue, timing
- **Operator chains**: Can be expensive for large streams

## Testing

### Testing Signals

```typescript
it('should compute stats correctly', () => {
    const component = new StepperComponent();
    component.stagingSession.currentSession.set({
        totalFiles: 100,
        filesDone: 50,
        filesFailed: 10
    });
    
    const stats = component.monitoringStats();
    expect(stats.progress).toBe(60);
});
```

### Testing RxJS

```typescript
it('should reconnect on token change', () => {
    const authService = TestBed.inject(AuthService);
    const stagingService = TestBed.inject(StagingSessionService);
    
    spyOn(stagingService, 'reconnectSSE');
    
    authService.token$.next('new-token');
    
    expect(stagingService.reconnectSSE).toHaveBeenCalled();
});
```

## Conclusion

**Use both, but use each for what it's best at:**

- **Signals**: Component state, UI reactivity, computed values
- **RxJS**: Async operations, cross-service events, complex transformations

The future of Angular is **Signals for state, RxJS for streams**.

## References

- [Angular Signals Documentation](https://angular.io/guide/signals)
- [RxJS Documentation](https://rxjs.dev/)
- [Angular RxJS Interop](https://angular.io/guide/rxjs-interop)
- [Signals vs Observables (Angular Blog)](https://blog.angular.io/angular-v16-is-here-4d7a28ec680d)
