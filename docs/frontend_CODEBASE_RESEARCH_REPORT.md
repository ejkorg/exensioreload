# New Frontend — Comprehensive Codebase Research Report

## 1. Angular Version & Key Dependencies

| Package | Version | Purpose |
|---------|---------|---------|
| **@angular/core** | ^21.2.12 | Core framework |
| **@angular/material** | ^21.2.12 | Material Design components |
| **@angular/cdk** | ^21.2.12 | Component Dev Kit (overlays, scrolling, a11y) |
| **TypeScript** | ~5.9.2 | Language |
| **RxJS** | ~7.8.0 | Reactive extensions |
| **zone.js** | ~0.15.0 | Change detection |
| **apexcharts / ng-apexcharts** | ^5.3.2 / ^1.3.0 | Charting |
| **echarts / ngx-echarts** | ^6.0.0 / ^19.0.2 | Charting |

**Project**: `exensio-reload` v2.1.0  
**Build**: `@angular/build:application` with SCSS, output to `dist/exensio-reload`  
**Strict mode**: `strict: true`, `noImplicitAny`, `noImplicitReturns`, `strictTemplates`  
**Target**: ES2022, bundler module resolution  
**Budget**: 1 MB warning / 2 MB error  

---

## 2. Full Component Tree

```
App (root shell)
├── Header: Brand, nav links, theme toggle, user info, logout
├── ToastContainerComponent (global notifications)
├── <router-outlet>
│
├── LoginComponent              (auth)
├── RegisterComponent           (auth)
├── VerifyComponent             (auth)
├── RequestResetComponent       (auth)
├── ResetPasswordComponent      (auth)
│
├── DashboardComponent          (AuthGuard)
│   └── Displays DashboardSnapshot metrics + sites grid
│
├── StepperComponent            (AuthGuard) — core workflow, 1319 lines
│   ├── GlassStepperComponent   (3-step wizard header)
│   ├── GlassSenderSelectorComponent (auto-resolve / manual dropdown)
│   ├── GlassDateRangeComponent (date range with calendar overlay)
│   ├── GlassLoadingOverlayComponent
│   ├── GlassSelectComponent × N (cascading filter dropdowns)
│   ├── GlassInputComponent × N
│   ├── GlassButtonComponent × N
│   ├── GlassCheckboxComponent
│   ├── GlassPaginationComponent (preview table pagination)
│   ├── GlassTooltipDirective
│   ├── DuplicateWarningDialogComponent (custom glass dialog)
│   ├── MonitoringStatsComponent (step 3)
│   ├── MonitoringFileListComponent (step 3, virtual scroll)
│   └── MonitoringActivityComponent (step 3, activity feed)
│
├── MySessionsComponent         (AuthGuard)
│   └── MatTable + status badges
│
└── UserListComponent           (AuthGuard + SuperAdmin)
    ├── MatTable, MatPaginator, MatSort
    ├── UserFormDialogComponent (MatDialog, CRUD form)
    └── ConfirmDialogComponent  (MatDialog, confirm/cancel)
```

### Shared Component Inventory (17 items)

| Component | Type | Lines | Key Feature |
|-----------|------|-------|-------------|
| `GlassStepperComponent` | UI | 183 | Step header with indicators/connectors, signal-based selectedIndex |
| `GlassInputComponent` | Form (CVA) | 243 | Floating label, prefix/suffix icon, glassmorphism |
| `GlassSelectComponent` | Form (CVA) | 503 | CDK Overlay dropdown, keyboard navigation, letter search |
| `GlassButtonComponent` | UI | ~150 | 5 variants (primary/secondary/tertiary/danger/icon), loading spinner |
| `GlassCheckboxComponent` | Form (CVA) | ~150 | Custom SVG check/indeterminate, animation |
| `GlassIconComponent` | UI | 209 | 28+ named SVG icons |
| `GlassPaginationComponent` | UI | 318 | Signal-based, page size selector |
| `GlassDateRangeComponent` | Form (CVA) | 938 | Start/end date with calendar overlay, time support |
| `GlassDatepickerComponent` | Form (CVA) | ~200 | Single date/time picker |
| `GlassCalendarComponent` | UI | 343 | Keyboard-navigable date grid, range highlighting |
| `GlassSenderSelectorComponent` | UI | ~280 | Auto-resolve display, loading state, fallback dropdown |
| `GlassLoadingOverlayComponent` | UI | ~130 | Full-screen overlay with spinner, message, subtext |
| `MonitoringStatsComponent` | UI | 367 | Stat cards, progress bar with shimmer, status distribution |
| `MonitoringFileListComponent` | UI | 513 | CDK virtual scroll, status filters, search, CSV export |
| `MonitoringActivityComponent` | UI | ~250 | Real-time activity feed, auto-scroll, relative timestamps |
| `ToastContainerComponent` | UI | ~170 | Fixed position, slide-in animation, 4 types |
| `ConfirmDialogComponent` | Dialog | ~50 | MatDialog-based confirm/cancel |
| `DuplicateWarningDialogComponent` | Dialog | 294 | Custom glass dialog, shows duplicate payloads |

### Shared Directive

| Directive | Lines | Feature |
|-----------|-------|---------|
| `GlassTooltipDirective` | ~120 | 4 positions (top/bottom/left/right), delayed show, DOM-appended |

---

## 3. Routing Structure

All routes defined in `app.routes.ts`. Auth pages are unguarded; all app pages require `AuthGuard`.

| Path | Component | Guard | Lazy |
|------|-----------|-------|------|
| `/` | Redirect → `/exensioreload` | — | — |
| `/exensioreload` | `DashboardComponent` | `AuthGuard` | Yes |
| `/exensioreload/new` | `StepperComponent` | `AuthGuard` | Yes |
| `/exensioreload/edit/:id` | `StepperComponent` | `AuthGuard` | Yes |
| `/my-sessions` | `MySessionsComponent` | `AuthGuard` | Yes |
| `/admin/users` | `UserListComponent` | `AuthGuard` + `isSuperAdmin()` | Yes |
| `/login` | `LoginComponent` | — | Yes |
| `/register` | `RegisterComponent` | — | Yes |
| `/verify` | `VerifyComponent` | — | Yes |
| `/request-reset` | `RequestResetComponent` | — | Yes |
| `/reset-password` | `ResetPasswordComponent` | — | Yes |

All routes use `loadComponent` for lazy loading.

---

## 4. Service Layer

### AuthService (211 lines)
JWT-based authentication with dual state management.

| Method | Description |
|--------|-------------|
| `login(username, password)` | POST `/auth/login`, stores tokens, schedules refresh |
| `logout()` | POST `/auth/logout`, clears state, navigates to `/login` |
| `register(username, email?, password)` | POST `/auth/register` |
| `verify(token)` | POST `/auth/verify` |
| `requestPasswordReset(email)` | POST `/auth/request-reset` |
| `resetPassword(token, password)` | POST `/auth/reset-password` |
| `refreshToken()` | POST `/auth/refresh`, re-schedules next refresh |
| `isAuthenticated()` | Checks BehaviorSubject for current user |
| `getToken()` | Returns JWT from sessionStorage/localStorage |
| `isAdmin()` / `isSuperAdmin()` | Role-based checks |

**Token refresh**: Auto-refreshes 30 seconds before JWT expiry using `setTimeout`. Parses `exp` claim from JWT payload.

### BackendService (598 lines)
Central API communication layer.

| Method | HTTP | Endpoint | Returns |
|--------|------|----------|---------|
| `getDashboardSnapshot()` | GET | `/dashboard/snapshot` | `DashboardSnapshot` |
| `getEnvironments()` | GET | `/environments` | `string[]` |
| `getAllSites()` | GET | `/environments/sites` | `SiteResponse[]` |
| `getLocations(env)` | GET | `/environments/{env}/locations` | `string[]` |
| `smartSenderLookup(params)` | GET | `/senders/lookup` | `SmartSenderLookupResult` |
| `getExternalSenders(siteId)` | GET | `/senders/external/senders` | `SenderOption[]` |
| `getHistoricalSenders(params)` | GET | `/senders/historical/senders` | `any[]` |
| `getExternalLocations(siteId)` | GET | `/senders/external/locations` | `string[]` |
| `getExternalDataTypes(siteId)` | GET | `/senders/external/dataTypes` | `string[]` |
| `getExternalTesterTypes(siteId)` | GET | `/senders/external/testerTypes` | `string[]` |
| `getExternalTestPhases(siteId)` | GET | `/senders/external/testPhases` | `string[]` |
| `getExternalDataTypeExts(siteId)` | GET | `/senders/external/dataTypeExts` | `string[]` |
| `getSenderQueueCount(id)` | GET | `/sender/{id}/queue-count` | `number` |
| `getSenderStageStatus(id)` | GET | `/senders/{id}/stage-status` | `any` |
| `discoverPreview(id, body)` | POST | `/senders/{id}/discover/preview` | `DiscoverRow[]` |
| `discoverPreviewWithDuplicates(id, body)` | POST | `/senders/{id}/discover/preview-with-duplicates` | `{rows, duplicates}` |
| `historicalSummary(id, body)` | POST | `/senders/{id}/discover/historical-summary` | `any` |
| `stagePayloads(id, body)` | POST | `/senders/{id}/stage` | `StageResult` |
| `stageAll(id, body)` | POST | `/senders/{id}/discover/stage-all` | `StageResult` |
| `getStageRecords(params)` | GET | `/staging/history` | `StageRecordView[]` |
| `dispatch(body)` | POST | `/dispatch` | `any` |
| `enqueue(body)` | POST | `/enqueue` | `any` |
| `monitoringStats()` | GET | `/stage/stats` | `any` |
| `monitoringRecords()` | GET | `/stage/records` | `any[]` |
| `getActiveSessions()` | GET | `/stage/sessions/active` | `any[]` |
| `getSessionDetails()` | GET | `/stage/sessions/details` | `any` |
| `stopSession(id)` | POST | `/stage/sessions/{id}/stop` | `any` |

### UserService (~100 lines)
Admin user management with reactive BehaviorSubject state.

| Method | HTTP | Endpoint |
|--------|------|----------|
| `getUsers(page, size, search?, role?, status?)` | GET | `/admin/users` |
| `getUserById(id)` | GET | `/admin/users/{id}` |
| `createUser(user)` | POST | `/admin/users` |
| `updateUser(id, user)` | PUT | `/admin/users/{id}` |
| `deleteUser(id)` | DELETE | `/admin/users/{id}` |
| `getStatistics()` | GET | `/admin/users/statistics` |
| `getRoles()` | GET | `/admin/users/roles` |

### ThemeService (~60 lines)
Signal-based theme management.

| Method/Property | Description |
|-----------------|-------------|
| `theme: WritableSignal<ThemeMode>` | `'dark'` or `'light'` |
| `toggleTheme()` | Switches between dark/light |
| `effect()` | Applies CSS class to `<body>`, persists to localStorage |

### ToastService (52 lines)
Signal-based notification system.

| Method | Description |
|--------|-------------|
| `success(message, duration?)` | Green toast |
| `error(message, duration?)` | Red toast |
| `info(message, duration?)` | Blue toast |
| `warning(message, duration?)` | Amber toast |
| `dismiss(id)` | Remove specific toast |
| `clear()` | Remove all toasts |

### MonitoringService (372 lines)
Real-time dispatch monitoring via SSE + polling fallback.

| Method/Property | Description |
|-----------------|-------------|
| `stats: WritableSignal<MonitoringStats>` | Aggregate stats |
| `files: WritableSignal<MonitoringFile[]>` | Tracked file list |
| `activities: WritableSignal<ActivityEvent[]>` | Activity feed events |
| `isConnected: WritableSignal<boolean>` | Connection state |
| `startSSE(sessionId)` | Opens EventSource for real-time updates |
| `startPolling(sessionId, interval)` | Polling fallback |
| `stopMonitoring()` | Closes SSE, clears polling |
| `reset()` | Clears all state |

SSE event types: `STATS`, `ROW_UPDATE`, `COMPLETE`, `ERROR`

### GlassDialogService (~120 lines)
Custom dialog system (NOT Angular Material).

| Method/Property | Description |
|-----------------|-------------|
| `open<T>(component, data?)` | Dynamically creates component, returns `GlassDialogRef` |
| `GlassDialogRef.close(result)` | Closes with optional data |
| `GlassDialogRef.afterClosed()` | Returns `Promise<any>` |
| `GLASS_DIALOG_DATA` | InjectionToken for passing data |

---

## 5. Authentication Flow

```
┌─────────┐     POST /auth/login      ┌─────────┐
│  Login   │ ─────────────────────────→│ Backend │
│Component │←─────────────────────────│         │
└─────────┘   { accessToken, user }   └─────────┘
      │
      ▼
┌──────────────────────────────────────────────────┐
│ AuthService                                       │
│  1. Parse JWT → extract exp claim                 │
│  2. Store token in sessionStorage + localStorage  │
│  3. Set currentUser BehaviorSubject + signal      │
│  4. Schedule auto-refresh = (exp - 30s) from now  │
│  5. Navigate to /exensioreload                         │
└──────────────────────────────────────────────────┘
      │
      ▼
┌──────────────────────────────────────────────────┐
│ AuthGuard (CanActivateFn)                         │
│  1. Check isAuthenticated() → BehaviorSubject     │
│  2. Fallback: check stored token exists           │
│  3. If neither → redirect to /login               │
└──────────────────────────────────────────────────┘
      │
      ▼
┌──────────────────────────────────────────────────┐
│ AuthInterceptor (HttpInterceptorFn)               │
│  1. Attach Authorization: Bearer <token> header   │
│  2. On 401 response → auth.logout()               │
└──────────────────────────────────────────────────┘
      │
      ▼
┌──────────────────────────────────────────────────┐
│ Token Refresh (automatic)                         │
│  setTimeout at (exp - 30s)                        │
│  POST /auth/refresh → new token → reschedule      │
└──────────────────────────────────────────────────┘
```

**Registration flow**: Register → receive token → redirect to `/verify?token=...` → VerifyComponent calls `auth.verify(token)`

**Password reset flow**: RequestReset (email) → user receives link → `/reset-password?token=...` → ResetPasswordComponent with password + confirm

**Role model**: Roles stored with `ROLE_` prefix in backend, stripped on frontend. Methods: `isAdmin()` checks for `ADMIN` or `SUPER_ADMIN`; `isSuperAdmin()` checks for `SUPER_ADMIN` only.

---

## 6. UI/UX Design System

### Glassmorphism Foundation
Every panel and card uses the **glassmorphism** aesthetic:
- `backdrop-filter: blur(12–20px)` (+ `-webkit-` prefix)
- Semi-transparent backgrounds: `rgba(255, 255, 255, 0.02–0.08)`
- Subtle borders: `rgba(255, 255, 255, 0.05–0.15)`
- Large border-radius: 12–20px
- Layered box-shadows with transparency

### Color System (CSS Custom Properties)

| Token | Dark Theme | Light Theme |
|-------|------------|-------------|
| `--bg-color` | `#0f172a` | `#f1f5f9` |
| `--text-main` | `#f1f5f9` | `#1e293b` |
| `--text-muted` | `#94a3b8` | `#64748b` |
| `--card-bg` | `rgba(255,255,255,0.03)` | `rgba(255,255,255,0.85)` |
| `--card-border` | `rgba(255,255,255,0.06)` | `rgba(0,0,0,0.08)` |
| `--accent-color` | `#818cf8` (indigo) | `#4f46e5` (deeper indigo) |

### Semantic Colors
- **Success**: `#10b981` (emerald)
- **Error**: `#ef4444` (red)
- **Warning**: `#f59e0b` (amber)
- **Info**: `#3b82f6` (blue)

### Background Effects
- Gradient background: `linear-gradient(135deg, #0f172a → #1e1b4b → #0f172a)`
- Animated decorative glow orbs (floating blurred circles with indigo/purple)
- Body pseudo-element with floating animated blurred orbs

### Typography
- **Font family**: Inter (system UI fallback stack)
- **Heading**: Gradient text `background: linear-gradient → background-clip: text`
- **Labels**: 0.6875–0.75rem, uppercase, letter-spacing: 0.05em

### Custom Component Library
All 17+ custom `glass-*` components follow consistent patterns:
- Standalone components
- ControlValueAccessor for form integration
- `:host-context(body.light-theme)` CSS overrides
- Signal-based internal state
- Consistent 0.3s CSS transitions
- Responsive breakpoints at 480px, 768px, 1024px

### Animation Patterns
- `slideIn` (toast notifications): translateX(400px) → 0
- `pop-in` (calendar): scale(0.96) → 1
- `shimmer` (progress bars): translateX(-100%) → 100%
- `fadeInUp` (components): translateY(10px) → 0
- `spin` (loading spinners): rotate(0 → 360deg)

---

## 7. State Management Approach

### Pattern: Hybrid Signals + RxJS

The codebase uses a **dual state management** approach:

#### Angular Signals (primary, component-local)
```typescript
// Component-level reactive state
selectedStep = signal(0);
isLoading = signal(false);
previewRows = signal<DiscoverRow[]>([]);

// Computed derived state
filteredFiles = computed(() => {
  let files = this._files();
  // ...filter logic
  return files;
});

// Effects for side effects
effect(() => {
  const theme = this.theme();
  document.body.classList.toggle('dark-theme', theme === 'dark');
});
```

**Used in**: All shared components, StepperComponent, DashboardComponent, MonitoringService, ToastService, ThemeService

#### RxJS BehaviorSubject (cross-component / auth)
```typescript
// AuthService: cross-component user state
private currentUser = new BehaviorSubject<UserInfo | null>(null);
currentUser$ = this.currentUser.asObservable();

// UserService: reactive user list
private usersSubject = new BehaviorSubject<User[]>([]);
users$ = this.usersSubject.asObservable();
```

**Used in**: AuthService (user state shared via BehaviorSubject AND signal), UserService

#### RxJS Observables (HTTP, debounced lookups)
- All HTTP calls return `Observable` via `HttpClient`
- Debounced sender lookup via `Subject` + `debounceTime(400)` + `switchMap`
- Polling via `interval()` + `switchMap`

### Data Flow Summary
| Layer | Mechanism |
|-------|-----------|
| Global auth state | BehaviorSubject + signal (dual) |
| Service-to-component data | HTTP Observables → signal storage |
| Component internal state | Signals |
| Derived/filtered views | `computed()` |
| Side effects | `effect()` |
| Cross-component events | EventEmitter (Input/Output) |
| Debounced async | RxJS Subject + operators |

---

## 8. Key Design Patterns

### 1. Standalone Components (100%)
Every component, directive, and pipe uses `standalone: true`. No NgModules exist. Imports are explicitly declared per-component.

### 2. Functional Guards & Interceptors
```typescript
// auth.guard.ts — CanActivateFn (not class-based)
export const AuthGuard: CanActivateFn = (route, state) => { ... };

// auth.interceptor.ts — HttpInterceptorFn
export const AuthInterceptor: HttpInterceptorFn = (req, next) => { ... };
```

### 3. ControlValueAccessor (CVA) Pattern
Form components (`glass-input`, `glass-select`, `glass-checkbox`, `glass-date-range`, `glass-datepicker`) implement `ControlValueAccessor` for seamless integration with Angular Reactive/Template forms:
```typescript
providers: [{
  provide: NG_VALUE_ACCESSOR,
  useExisting: forwardRef(() => GlassInputComponent),
  multi: true
}]
```

### 4. Custom Dialog System
Two dialog systems coexist:
- **Material Dialog** (`MatDialog`): Used by `UserListComponent` for `UserFormDialogComponent` and `ConfirmDialogComponent`
- **Custom Glass Dialog** (`GlassDialogService`): Used by `StepperComponent` for `DuplicateWarningDialogComponent`. Creates components dynamically via `ViewContainerRef`, manages its own backdrop/animation lifecycle.

### 5. Lazy Loading
All routes use `loadComponent: () => import(...)` for code-splitting.

### 6. Cascading Filter Pattern (Stepper)
Environment → Site → Location → DataType → TesterType → DataTypeExt → TestPhase  
Selection of each level triggers API call to populate the next. Auto sender resolution fires via debounced effect when sufficient filters are selected.

### 7. Virtual Scrolling for Large Lists
`MonitoringFileListComponent` uses `@angular/cdk/scrolling` (`cdk-virtual-scroll-viewport` + `*cdkVirtualFor`) for performant rendering of potentially thousands of monitoring rows.

### 8. Progressive Enhancement
- SSE (Server-Sent Events) as primary real-time transport
- Polling fallback when SSE connection fails
- Graceful degradation for all loading states

### 9. Signal-based Component Communication
Parent → Child via `@Input()` setters that write to signals:
```typescript
@Input() set files(value: MonitoringFile[]) {
  this._files.set(value);
}
private _files = signal<MonitoringFile[]>([]);
```

---

## 9. Styling Approach

### Architecture
- **Global styles**: `src/styles.scss` (358 lines) — theme variables, `.glass-panel`, scrollbar, tooltip/dialog overlay
- **Component styles**: Inline SCSS in each component (`styles: [...]`)
- **No external CSS framework** (Tailwind, Bootstrap, etc.)
- **SCSS** configured as inline style language in `angular.json`

### Theme System
Managed by `ThemeService`:
1. Signal stores current theme (`'dark' | 'light'`)
2. `effect()` toggles `body.dark-theme` / `body.light-theme` CSS classes
3. Persisted to `localStorage`
4. System preference detection via `prefers-color-scheme` media query
5. Components use `:host-context(body.light-theme)` for overrides

### Key Style Patterns
| Pattern | Usage |
|---------|-------|
| CSS custom properties | All colors, spacing via `var(--token)` |
| `:host-context()` | Theme-aware component styles |
| `backdrop-filter: blur()` | Glassmorphism panels |
| `linear-gradient()` | Backgrounds, progress bars, accent borders |
| Grid / Flexbox | All layouts |
| `@media` breakpoints | 480px, 768px, 1024px responsive breakpoints |
| CSS animations | `@keyframes` for shimmer, slide, fade, spin |
| Pseudo-elements | Decorative glows, animated backgrounds |

### Responsive Design Strategy
```
Mobile-first adjustments:
  ≤ 480px  — Hide step labels, compact layouts
  ≤ 768px  — Stack columns, full-width inputs, bottom toasts
  ≤ 1024px — Reduce grid columns, hide secondary table columns
```

---

## 10. All API Endpoints

### Authentication (`/exensioreload/api/auth/*`)
| Method | Endpoint | Purpose |
|--------|----------|---------|
| POST | `/auth/login` | Authenticate, returns `accessToken` + `user` |
| POST | `/auth/logout` | Invalidate session |
| GET | `/auth/me` | Get current user info |
| POST | `/auth/refresh` | Refresh access token |
| POST | `/auth/register` | Create account |
| POST | `/auth/verify` | Verify account with token |
| POST | `/auth/request-reset` | Request password reset email |
| POST | `/auth/reset-password` | Reset password with token |

### Dashboard (`/exensioreload/api/dashboard/*`)
| Method | Endpoint | Purpose |
|--------|----------|---------|
| GET | `/dashboard/snapshot` | Global metrics snapshot |

### Environments & Sites
| Method | Endpoint | Purpose |
|--------|----------|---------|
| GET | `/environments` | List all environments |
| GET | `/environments/sites` | List all sites |
| GET | `/environments/{env}/locations` | Locations for environment |
| GET | `/sites` | List sites (legacy) |

### Senders
| Method | Endpoint | Purpose |
|--------|----------|---------|
| GET | `/senders/lookup` | Smart auto-resolution (filters → sender) |
| GET | `/senders/external/senders` | All senders by siteId |
| GET | `/senders/historical/senders` | HIST regex sender matching |
| GET | `/senders/external/locations` | Distinct locations by site |
| GET | `/senders/external/dataTypes` | Distinct data types by site |
| GET | `/senders/external/testerTypes` | Distinct tester types by site |
| GET | `/senders/external/testPhases` | Distinct test phases by site |
| GET | `/senders/external/dataTypeExts` | Distinct extensions by site |
| GET | `/sender/{id}/queue-count` | Sender queue depth |
| GET | `/senders/{id}/stage-status` | Staging status for sender |

### Discovery & Staging
| Method | Endpoint | Purpose |
|--------|----------|---------|
| POST | `/senders/{id}/discover/preview` | Preview matching payloads |
| POST | `/senders/{id}/discover/preview-with-duplicates` | Preview + duplicate detection |
| POST | `/senders/{id}/discover/historical-summary` | Historical payload summary |
| POST | `/senders/{id}/stage` | Stage selected payloads |
| POST | `/senders/{id}/discover/stage-all` | Stage all matching payloads |

### Staging History
| Method | Endpoint | Purpose |
|--------|----------|---------|
| GET | `/staging/history` | Stage record history |

### Dispatch
| Method | Endpoint | Purpose |
|--------|----------|---------|
| POST | `/dispatch` | Dispatch staged payloads |
| POST | `/enqueue` | Enqueue payloads |

### Monitoring
| Method | Endpoint | Purpose |
|--------|----------|---------|
| GET | `/stage/stats` | Monitoring statistics |
| GET | `/stage/records` | Stage records list |
| GET | `/stage/sessions/active` | Active staging sessions |
| GET | `/stage/sessions/details` | Session details |
| POST | `/stage/sessions/{id}/stop` | Stop a staging session |

### Admin (`/exensioreload/api/admin/*`)
| Method | Endpoint | Purpose |
|--------|----------|---------|
| GET | `/admin/users` | List users (paginated, filterable) |
| GET | `/admin/users/{id}` | Get user by ID |
| POST | `/admin/users` | Create user |
| PUT | `/admin/users/{id}` | Update user |
| DELETE | `/admin/users/{id}` | Delete user |
| GET | `/admin/users/statistics` | User statistics |
| GET | `/admin/users/roles` | Available roles |

**Base URL**: All endpoints prefixed with `/exensioreload/api` (via `environment.apiUrl`)  
**Dev Proxy**: `proxy.conf.json` forwards `/exensioreload/api` → `http://127.0.0.1:8004` with WebSocket support

---

## Summary Statistics

| Metric | Value |
|--------|-------|
| Total TypeScript files | 44 |
| Total HTML files | 5 (external templates) |
| Total SCSS files | 5 (external stylesheets) |
| Total lines of TypeScript | ~8,500+ |
| Components | 22 |
| Services | 6 |
| Directives | 1 |
| Routes | 11 |
| API endpoints | 38 |
| Design system components | 17 |
