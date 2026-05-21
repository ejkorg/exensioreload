# ExensioReload Fullstack — Project Intelligence

## Project Overview

ExensioReload is a fullstack application for managing semiconductor test data resend operations across 20+ manufacturing sites. It discovers metadata from external Oracle databases, stages payloads, dispatches them to sender queues, and monitors completion — all through a modern glassmorphism UI.

---

## Architecture Summary

```
┌─────────────────────────────────────────────────────┐
│  frontend (Angular 21.2.12, standalone components)  │
│  Port: 4200 (dev) → proxied to backend               │
│  Glassmorphism design system, Signals + RxJS        │
├─────────────────────────────────────────────────────┤
│  backend (Spring Boot 3.2.0, Java 21)               │
│  Port: 8004, Context: /exensioreload                     │
│  JWT auth + optional Microsoft Entra OIDC, Liquibase, HikariCP pool per site │
├─────────────────────────────────────────────────────┤
│  Oracle RefDB (staging)  │  20+ Site Oracle DBs     │
│  H2 (dev/test)           │  (metadata sources)      │
└─────────────────────────────────────────────────────┘
```

**Important**: Use `frontend/` — this is the active Angular app. There is no separate `new_frontend/` workspace in the current repository.

### Execution Constraints (Current Workspace)

- This workspace may not allow installing or executing Java/Maven or Node/npm tooling.
- For verification, code changes can be prepared in this environment, then built/tested on a local machine with required permissions/toolchains.
- When local execution is blocked, prioritize static correctness, strict typing, and minimal-risk changes before handoff.

---

## Backend — Java / Spring Boot

### Tech Stack
| Layer | Technology | Version |
|-------|-----------|---------|
| Language | Java | 21 |
| Framework | Spring Boot | 4.0.6 |
| Build | Maven | — |
| ORM | Spring Data JPA / Hibernate | — |
| Primary DB | Oracle (ojdbc11) | 23.3.0 |
| Test DB | H2 | runtime |
| Migrations | Liquibase | via starter |
| Auth | JWT (jjwt 0.11.5) + Microsoft Entra OIDC | HS256 / OIDC code flow |
| Security | Spring Security | — |
| Connection Pool | HikariCP | 5.0.1 |
| Caching | Caffeine | 3.1.8 |
| Mail | Spring Boot Mail | — |
| Metrics | Micrometer + Prometheus | — |

### Maven Coordinates
`com.onsemi.cim.apps.exensio.exensioreload:exensio-reload:1.0.0-SNAPSHOT`

### Package Structure
```
com.onsemi.cim.apps.exensio.exensioreload
├── ResenderApplication.java  # @SpringBootApplication, @EnableScheduling, @EnableAsync
├── config/          # Security, JWT, DB, mail, discovery config (11 classes)
├── controller/      # REST endpoints + support classes (20 classes)
├── service/         # Business logic (19 classes)
├── entity/          # JPA entities (12 classes)
├── repository/      # Data access (20 interfaces/classes)
├── dto/             # Request/response records (40 records/classes)
└── stage/           # Staging domain (7 records + StageMonitorService)
```

**148 Java source files total** (including 19 controllers, 33 services, 12 entities, 20+ repositories, 40+ DTOs)

### Key Configuration
| Property | Value | Purpose |
|----------|-------|---------|
| `server.port` | 8004 | Server port |
| `server.servlet.context-path` | /exensioreload | API base path |
| `jwt.ttl` | 900 | JWT expiry (seconds) |
| `reloader.refresh.cookie-max-age` | 604800 | Refresh cookie Max-Age in seconds (7 days); 0 = session cookie |
| `refdb.dispatch.interval-ms` | 60000 | Dispatch polling interval |
| `app.preview.fetch-cap` | 2000 (default) | Server fetch window for preview-with-duplicates / UI full-batch fetch |
| `app.preview.max-rows-cap` | 2000 (default) | Max preview rows when `bypassCap=false` |
| `app.stage.max-rows-cap` | 10000 | Max stage-all rows |
| `external-db.allow-writes` | false | Gate for external DB writes |

### SSO Registration
Information for the Windows/SSO team can be found in [docs/SSO_ONBOARDING_DETAILS.md](docs/SSO_ONBOARDING_DETAILS.md).

### CP Elasticsearch & Exensio API
Step-by-step enablement, environment variables, log/API requirements, verification, and troubleshooting: **[docs/INTEGRATION_ES_EXENSIO.md](docs/INTEGRATION_ES_EXENSIO.md)**.

### Configuration Files
- `application.yml` — Default config, profiles, Liquibase, CORS
- `application-onsemi-oracle.yml` — Production Oracle/SMTP/CORS config
- `dbconnections.yml` — ~385 lines defining 20+ manufacturing site Oracle connections (PROD/QA)

### REST API Endpoints

#### Auth (`/api/auth`) — Public
| Method | Path | Description |
|--------|------|-------------|
| POST | `/api/auth/login` | Login → JWT + refresh token |
| POST | `/api/auth/refresh` | Refresh token → new JWT |
| POST | `/api/auth/register` | User registration |
| GET | `/api/auth/verify` | Email verification |
| POST | `/api/auth/request-reset` | Password reset request |
| POST | `/api/auth/reset-password` | Reset password with token |

#### Senders (`/api/senders`) — Authenticated
| Method | Path | Description |
|--------|------|-------------|
| POST | `/{id}/discover/preview` | Preview metadata with pagination/filters |
| POST | `/{id}/discover/preview-with-duplicates` | Preview + duplicate detection |
| POST | `/{id}/discover/historical-summary` | Aggregated summary |
| POST | `/{id}/discover/stage-all` | Stage all matching results |
| POST | `/{id}/discover/preview/csv` | Export preview as CSV |
| POST | `/{id}/preview/duplicates` | Check specific items for duplicates |
| POST | `/{id}/stage` | Stage specific payloads |
| POST | `/{id}/dispatch` | Trigger dispatch for a sender |
| GET | `/lookup` | Sender lookup by filters |
| GET | `/historical/senders` | Historical sender lookup |
| GET | `/external/locations` | Distinct location values |
| GET | `/external/dataTypes` | Distinct dataType values |
| GET | `/external/testerTypes` | Distinct testerType values |
| GET | `/external/dataTypeExts` | Distinct data_type_ext values |
| GET | `/external/testPhases` | Distinct test_phase values |
| GET | `/external/senders` | All external senders |

#### Stage (`/api/stage`) — Authenticated
| Method | Path | Description |
|--------|------|-------------|
| GET | `/records` | Staged records (paginated, filterable) |
| GET | `/stats` | Aggregated stage stats |
| GET | `/monitor/{requestId}` | SSE stream for real-time progress |
| GET | `/csv` | Export staged records as CSV |
| GET | `/active-sessions` | Active staging sessions |

#### Dashboard (`/api/dashboard`) — Authenticated
| Method | Path | Description |
|--------|------|-------------|
| GET | `/snapshot` | Aggregated dashboard metrics |
| GET | `/sites/{site}/records` | Records for specific site |
| GET | `/sites/{site}/records/csv` | Export site records as CSV |
| GET | `/senders/{senderId}/records` | Records for specific sender |

#### User Admin (`/api/admin/users`) — SUPER_ADMIN only
| Method | Path | Description |
|--------|------|-------------|
| GET/POST | `/` | List / Create users |
| GET/PUT/DELETE | `/{id}` | Get / Update / Delete user |
| POST | `/{id}/toggle-status` | Toggle ACTIVE/INACTIVE |
| PUT | `/{id}/roles` | Update roles |
| POST | `/{id}/reset-password` | Admin password reset |
| GET | `/audit-logs` | Filtered audit logs |
| GET | `/stats` | User statistics |

#### External/Environments (`/api/external`, `/api/environments`)
| Method | Path | Description |
|--------|------|-------------|
| GET | `/external/instances` | DB instances for environment |
| GET | `/environments` | All environments |
| GET | `/environments/{env}/locations` | Locations for environment |
| GET | `/environments/sites` | All distinct sites |
| POST | `/environments/import-csv` | Bulk import locations |

#### Config (`/api/config`)
| Method | Path | Description |
|--------|------|-------------|
| GET | `/limits` | Preview/stage limit thresholds |

#### Internal (`/internal`) — Admin only
| Method | Path | Description |
|--------|------|-------------|
| GET | `/pools` | HikariCP pool stats |
| DELETE | `/pools/{site}` | Evict a pool |
| GET | `/metrics` | Custom metrics |
| POST | `/metadata/forceAllView` | Toggle metadata view |
| GET/POST | `/sessions/{id}/*` | Session management |

#### Diagnostic/Test (`/api/diagnostic`, `/api/test`) — Dev/Debug
| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/diagnostic/beans` | List Spring beans |
| GET | `/api/diagnostic/auth` | Current authentication info |
| GET | `/api/diagnostic/endpoints` | Registered request mappings |
| GET | `/api/test/hello` | Health check |
| GET | `/api/test/admin` | Admin access test |

#### Simplified User Endpoints (dev/debug)
| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/simple/users` | Simplified user listing (SUPER_ADMIN) |
| GET | `/api/basic/users` | Ultra-simple user listing (debug) |
| GET/POST/PUT/DELETE | `/api/admin/simple-users/*` | Simplified admin CRUD |

### Database Schema (Liquibase-managed, 15 changelogs)

**Core Tables:**
- `users` — User accounts (username, email, password_hash, status, timestamps)
- `user_roles` — Role assignments (SUPER_ADMIN, ADMIN, REGULAR_USER, USER)
- `SENDER_STAGE` — Core staging table on Oracle RefDB (site, sender_id, metadata_id, data_id, lot, wafer, filename, end_time, status, timestamps, request_id, **cp_output_path**, **cp_output_target**, **exensio_wafer_key**, **exensio_pg_key**)
- `sender_queue` — Local processing queue (status: NEW → QUEUED → PROCESSING → SENT/FAILED)
- `load_session` — Batch sessions (status: CREATED → DISCOVERING → ENQUEUED_LOCAL → PUSHING_REMOTE → COMPLETED/FAILED)
- `load_session_payload` — Individual items within sessions

**Auth Tables:**
- `refresh_tokens`, `verification_tokens`, `password_reset_tokens`, `password_history`

**Session Tracking:**
- `user_sessions` — Active user session tracking (UserSession entity)

**Audit & Config:**
- `audit_log` — Action audit trail (userId, action, resourceType, details CLOB, IP, UserAgent)
- `external_environment` — Environment definitions (QA, PROD)
- `external_location` — Per-environment DB connection references

### Authentication & Authorization
- **Local JWT auth**: Username/password login issues an HS256 access token plus a refresh token stored in DB.
- **Persistent refresh cookie**: `refresh_token` HTTP-only cookie is set with `Max-Age=604800` (7 days) so it survives browser restarts. On next open the frontend silently calls `/api/auth/refresh` and the user lands directly in the app without a login prompt. Configurable via `reloader.refresh.cookie-max-age` (set to `0` to revert to session-only behavior).
- **Microsoft Entra SSO/OIDC**: Optional OAuth2 client registration is created when `reloader.sso.enabled=true`. The app uses the authorization-code flow with registration id `onsemi`, `redirectUri={baseUrl}/login/oauth2/code/onsemi`, and Microsoft endpoints derived from the configured tenant id.
- **SSO bridge flow**: `GET /api/auth/sso/initiate` and `GET /api/auth/sso/silent` start interactive or prompt=none logins, `SsoAuthenticationSuccessHandler` provisions/loads the user, issues the same JWT + refresh cookie, then redirects to `/sso-callback`.
- **Role mapping**: Default role is `USER`; Entra groups map to local roles using `onsemi-exensioreload-admins -> ADMIN` and `onsemi-exensioreload-superadmins -> SUPER_ADMIN`. The default group claim is `groups`.
- **Filter chain**: `JwtAuthenticationFilter` (OncePerRequestFilter) → extracts Bearer token → sets SecurityContext
- **CSRF disabled** (stateless API), **sessions stateless**
- **CORS**: Configured via `app.cors.allowed-origins`
- **Role hierarchy**: SUPER_ADMIN > ADMIN > REGULAR_USER/USER
- **Method security**: `@PreAuthorize` on controllers

### SSO Onboarding Form

The codebase is OIDC-based, not SAML-based. If the vendor form requires SAML fields, mark them as not used for this implementation.

#### Onsemi Information
- Team Name: Active Directory Level 2 Team
- E-Mail address: it-gis-esd-activedirectory@onsemi.com
- Domain: onsemi.com
- SSO Platform: Microsoft Entra SSO
- Login URL (SAML): https://login.microsoftonline.com/04e1674b-7af5-4d13-a082-64fc6e42384c/saml2
- MS Entra ID Identifier (SAML): https://sts.windows.net/04e1674b-7af5-4d13-a082-64fc6e42384c/
- Logout URL (SAML): https://login.microsoftonline.com/04e1674b-7af5-4d13-a082-64fc6e42384c/saml2
- App Federation Metadata (SAML): Provided after form submission
- Federation metadata document (OpenID): https://login.microsoftonline.com/04e1674b-7af5-4d13-a082-64fc6e42384c/federationmetadata/2007-06/federationmetadata.xml

#### Customer Information
- Company Name: onsemi
- Technical contact name: TBD by vendor/app owner
- Technical contact phone: TBD by vendor/app owner
- Technical contact e-mail: TBD by vendor/app owner

#### Application Information
- onsemi Application Owner (required): TBD by application owner
- Application Name: ExensioReload
- Application Description: Internal semiconductor test-data resend application for staging discovery results, dispatching to sender queues, monitoring completion, and managing related admin workflows. Supports local JWT auth plus optional Microsoft Entra OIDC SSO.
- On-prem/Cloud: On-prem / internal enterprise deployment
- Requirements for external user access (Optional): Not intended for external users; access should remain limited to onsemi corporate identities and assigned app roles.
- User onboarding process (Optional): Users are provisioned on first successful Entra login. Entra group membership maps to local roles; local login/registration remains available for non-SSO users if enabled.
- Custom Attributes and Claims (Optional): `email`, `sub`, `groups` claim (default claim name is `groups`); mapped roles via `onsemi-exensioreload-admins` and `onsemi-exensioreload-superadmins` groups.

#### Provide app integration instructions (SAML)
- Identifier (Entity ID) URL: N/A for current codebase; leave blank or mark not applicable.
- Reply URL: N/A for current codebase; leave blank or mark not applicable.
- Sign on URL (optional): N/A for current codebase; leave blank or mark not applicable.
- Relay State (optional): N/A for current codebase; leave blank or mark not applicable.
- Logout URL (optional): N/A for current codebase; leave blank or mark not applicable.

#### Provide app integration instructions (OpenID)
- Redirect URL’s: https://<deployment-host>/exensioreload/login/oauth2/code/onsemi
- Use this OpenID/OIDC section for the application; do not use the SAML fields above for this implementation.

#### Notes for the form
- The backend client registration id is `onsemi` (application label: `ExensioReload`).
- The silent SSO endpoint is `/api/auth/sso/silent?returnUrl=...`.
- The interactive SSO endpoint is `/api/auth/sso/initiate?returnUrl=...`.
- The Angular callback route is `/sso-callback`.

### Key Design Patterns — Backend
| Pattern | Usage |
|---------|-------|
| Immutable DTOs | Java `record` types for all request/response objects |
| Repository + Custom JDBC | JPA repos + raw JDBC for external Oracle queries (`JdbcExternalMetadataRepository`: 1464 LOC) |
| Thin controllers | Business logic in `@Service` classes |
| Scheduled tasks | `@Scheduled` for dispatch, queue processing, completion notifications |
| SSE (Server-Sent Events) | `StageMonitorService` for real-time stage progress |
| Caffeine caching | Preview metadata cache (200 entries, 30s TTL) |
| Dynamic DataSource pool | `ConcurrentHashMap<String, HikariDataSource>` created on-demand per site |
| Claim-based batch processing | SELECT → conditional UPDATE → load pattern for concurrent-safe payload claiming |
| Audit trail | Automatic IP/UserAgent detection via `RequestContextHolder` |
| Graceful mail degradation | MIME → simple mail fallback → silent skip |

### Background Tasks
| Service | Schedule | Purpose |
|---------|----------|---------|
| `SenderDispatchService` | fixedDelay 60s | Push `NEW` records into `DTP_SENDER_QUEUE_ITEM`; marks them `ENRICHMENT` immediately |
| `SenderQueueMonitor` | fixedDelay 10s | Detects CP queue consumption; drives `ENRICHMENT → DONE` (no ES) or stays `ENRICHMENT` (with ES) |
| `SenderService` | cron | Process local sender_queue entries |
| `CompletionNotificationService` | cron (5min) | Check completions → email notifications |
| `DiscoveryScheduler` | cron (configurable) | Automated metadata discovery (disabled by default) |
| `CpLogMonitor` | fixedDelay 60s (configurable) | Poll Elasticsearch for CP enrichment outcomes; drives `ENRICHMENT → EXENSIO_LOADING / FAILED`. No-op when ES not configured. |
| `ExensioLoadMonitor` | fixedDelay 60s (configurable) | Poll Exensio API for load confirmation; drives `EXENSIO_LOADING → DONE / FAILED`. No-op when Exensio not configured. |

### Additional Backend Services (not in background tasks)
| Service | Purpose |
|---------|---------|
| `AuthTokenService` | JWT + refresh token orchestration |
| `ExternalDbResolverService` | Resolves site → DataSource from `ExternalDbConfig` |
| `SenderQueueMonitor` | Monitors queue; transitions consumed records to `ENRICHMENT` (not `DONE`) |
| `ElasticsearchLogService` | Queries CP Elasticsearch logs via JDK HttpClient; returns `CpLogResult` (Success/Failure/NotFound) |
| `ExensioAuthService` | Manages Exensio API session token (login/logout/cache/401 retry) |
| `ExensioClient` | Calls `POST /v1/key/lot-wafer-lookup`; returns `ExensioLotWaferResult` (Found/NotFound/Error) |
| `MetricsService` | Custom Micrometer counters for external operations |

---

## Frontend — Angular 21

### Tech Stack
| Layer | Technology | Version |
|-------|-----------|---------|
| Framework | Angular | 21.2.12 |
| Language | TypeScript | 5.9.2 |
| UI Library | Angular Material + CDK | 21.2.12 |
| Reactivity | RxJS | 7.8.0 |
| Charts | echarts 6 + ngx-echarts 21, apexcharts 5 + ng-apexcharts 2 |
| Zone | zone.js | 0.15.0 |

**App name**: `ExensioReload` v1.0 (Frontend login displays "1.0", tab title shows "ExensioReload")

### Key Architectural Decisions

1. **100% standalone components** — zero NgModules in the entire app
2. **Lazy-loaded routes** — all features use `loadComponent()`
3. **Functional interceptors/guards** — Angular 15+ `HttpInterceptorFn` and `CanActivateFn`
4. **Hybrid signals + observables** — Signals for component/service state, RxJS for HTTP and cross-component streams
5. **Custom glassmorphism design system** — NOT Material themed; custom glass-* components with backdrop-filter blur
6. **Custom dialog system** — `GlassDialogService` uses imperative DOM creation, bypasses Material Dialog
7. **CVA form controls** — 5 custom form components implement `ControlValueAccessor`
8. **Default change detection** — OnPush is NOT used anywhere
9. **No NgModules, no shared modules** — everything imported directly

### Frontend Implementation Standards (Non-Negotiable)

These rules apply to all new work in `frontend/`:

1. **Always use the latest Angular version used by this app** (currently Angular 21) and prefer modern Angular APIs/patterns.
2. **Prefer modern Angular features** where appropriate: standalone components, functional guards/interceptors, signals/computed/effect, modern template control flow (`@for`, `@if`, `@switch`) when consistent with surrounding code.
3. **Signals-first for UI state**: use `signal()`/`computed()`/`effect()` for component-local and UI-oriented state.
4. **RxJS where streams are the right abstraction**: HTTP calls, SSE/EventSource streams, debouncing, cancellation, and multi-source async composition.
5. **Do not force RxJS for simple local state** that is better expressed with Signals.
6. **Preserve the glassmorphism design system for all UI work** — do not introduce conflicting visual styles.
7. **Support both dark and light themes** for any new UI surface; implementations must work with `body.dark-theme` and `body.light-theme`.
8. **Follow frontend best practices**: typed APIs/models, minimal side effects, reusable components/services, and consistency with existing architecture in `frontend/`.

**📖 See [RXJS_VS_SIGNALS_GUIDE.md](RXJS_VS_SIGNALS_GUIDE.md) for detailed guidance on when to use RxJS vs Signals, with real examples from this project.**

### Routing
| Path | Component | Guard |
|------|-----------|-------|
| `/` | → `/exensioreload` redirect | — |
| `/exensioreload` | `DashboardComponent` | AuthGuard |
| `/exensioreload/new` | `StepperComponent` | AuthGuard |
| `/exensioreload/edit/:id` | `StepperComponent` | AuthGuard |
| `/my-sessions` | `MySessionsComponent` | AuthGuard |
| `/admin/users` | `UserListComponent` | AuthGuard + isSuperAdmin |
| `/login` | `LoginComponent` | — |
| `/register` | `RegisterComponent` | — |
| `/verify` | `VerifyComponent` | — |
| `/request-reset` | `RequestResetComponent` | — |
| `/reset-password` | `ResetPasswordComponent` | — |

### App Config (Providers)
```typescript
provideZoneChangeDetection({ eventCoalescing: true })
provideRouter(routes)
provideAnimationsAsync()
provideHttpClient(withFetch(), withInterceptors([AuthInterceptor]))
```

### Component Architecture

#### Feature Components
| Component | Lines | State | Key Pattern |
|-----------|-------|-------|-------------|
| `StepperComponent` | 2098 | 30+ signals, computed, Subject | 3-step wizard (Configure → Preview → Monitor), comprehensive form logic with real-time validation |
| `DashboardComponent` | Medium | Signals | Auto-polling (10s), metric cards, site/sender breakdowns |
| `MySessionsComponent` | 1279 | Signals, computed | Session detail modal, analytics, collapsible sections, date range filters |
| `UserListComponent` | Medium | Signals + Material Table | SUPER_ADMIN only, CRUD, pagination, search |
| `LoginComponent` | Small | Reactive forms | JWT auth flow |
| `RegisterComponent` | Small | Reactive forms | User registration |

#### Shared Glass Components (Custom Design System)
| Component | CVA | Signals | Purpose |
|-----------|-----|---------|---------|
| `GlassInputComponent` | Yes | Yes | Text input with floating label |
| `GlassSelectComponent` | Yes | Yes | Dropdown with CDK Overlay positioning |
| `GlassDateRangeComponent` | Yes | Yes | Custom calendar overlay (no Material Datepicker) |
| `GlassDatepickerComponent` | Yes | Yes | Single date picker |
| `GlassCalendarComponent` | No | Yes | Calendar grid with keyboard navigation, ARIA |
| `GlassCheckboxComponent` | Yes | No | Checkbox with indeterminate state |
| `GlassButtonComponent` | No | No | 5 variants (primary/secondary/tertiary/danger/icon), 3 sizes, loading spinner |
| `GlassIconComponent` | No | No | 20+ SVG icons via ngSwitch |
| `GlassStepperComponent` | No | Yes | Step headers with connectors |
| `GlassPaginationComponent` | No | Yes | Table pagination |
| `GlassSenderSelectorComponent` | No | Yes | Auto-resolution + manual fallback |
| `GlassLoadingOverlayComponent` | No | No | Fullscreen loading spinner |
| `ToastContainerComponent` | No | No | Angular 17+ `@for`/`@switch` control flow |
| `MonitoringStatsComponent` | No | No | Stats grid with progress bars |
| `MonitoringFileListComponent` | No | Yes | CDK virtual scrolling, CSV export |
| `MonitoringActivityComponent` | No | Yes | Auto-scrolling activity feed |
| `ConfirmDialogComponent` | No | No | Only component using Material Dialog |
| `DuplicateWarningDialogComponent` | No | No | Stepper: warns about duplicate staged items |
| `UserFormDialogComponent` | No | No | Admin: create/edit user form dialog |

### Services

| Service | Location | State Pattern | Key Methods |
|---------|----------|--------------|-------------|
| `BackendService` | `api/` | Pure Observables (598 LOC) | 30+ methods covering all API endpoints |
| `AuthService` | `auth/` | Dual: BehaviorSubject + Signal | login, logout, register, verify, refresh (auto-scheduled 30s before expiry) |
| `ThemeService` | `core/` | Pure Signals + effect() | toggleTheme(), persists to localStorage, respects prefers-color-scheme |
| `ToastService` | `shared/services/` | Pure Signals | success, error, info, warning, dismiss, clear |
| `MonitoringService` | `shared/services/` | Signals + RxJS Subject (372 LOC) | SSE via EventSource + polling fallback, stats/files/activities |
| `GlassDialogService` | `shared/services/` | Signal in DialogRef | Imperative component creation via createComponent() + ApplicationRef |
| `UserService` | `admin/` | Observables | User CRUD for admin panel |

### Styling System

**Design philosophy**: Custom glassmorphism over Material theming

### Charts & Analytics Features

**Charting Library**: ECharts 6.0.0 with direct TypeScript imports (ngx-echarts 21.0.0 available but not actively used)

#### My Sessions Analytics Dashboard (`MySessionsComponent`)
Comprehensive visualization of session staging progress with real-time status tracking:

**Charts Implemented:**
1. **Daily Status Trend Chart** — Stacked area/bar chart
   - X-axis: Days within session date range
   - Y-axis: File counts (cumulative)
   - Series: DONE, ENQUEUED, FAILED, CANCELLED, STAGED (color-coded)
   - Tooltip: Custom-formatted hover showing status breakdown per day
   - Legend: Positioned at bottom with 16px item gap

2. **Status Distribution Pie Chart** — Overall session breakdown
   - Shows total counts: DONE, ENQUEUED, FAILED, CANCELLED, STAGED
   - Hover labels display status name + count + percentage
   - Donut style with center label

**Advanced Features:**
- **Responsive design**: ResizeObserver-driven auto-resize on container dimension changes
- **Deferred rendering**: Charts render via `setTimeout(..., 0)` to prevent NG02100 Angular errors
- **Date range filtering**: Custom start/end date inputs with "Apply Range" button
- **Collapsible panel**: "Session Analytics" toggle button (Show/Hide state)
- **Dark theme integration**: Gradient backgrounds, accent-colored axes/legends, glass-styled tooltips
- **Live data updates**: Charts re-render when:
  - Session is selected
  - Analytics date range is applied
  - Real-time updates from SSE stream arrive

**Data Flow:**
```
Backend: SessionAnalyticsResponse
  ├── dailyStatus[] (daily breakdown by status)
  ├── lotWaferPairs[] (lot/wafer summary)
  └── statusSummary (overall counts)
    ↓ (computed signals)
dailyStatusRows() → Trend chart
statusCounts() → Pie chart
    ↓ (ResizeObserver)
ECharts instances adapt to container width/height
```

**Technical Implementation:**
| Aspect | Details |
|--------|---------|
| Rendering | `renderTrendChart()`, `renderStatusChart()` private methods |
| Instances | Retrieved via `echarts.getInstanceByDom()` for ResizeObserver |
| Data transform | API response → chart series (manual mapping, type-safe) |
| State | Signals: `chartsExpanded`, `analyticsStartDate`, `analyticsEndDate` |
| Performance | Cleanup via ResizeObserver disconnect on component destroy |

**Styling**:
- Text color: `#cbd5e1` (light slate for dark theme)
- Axis lines: `rgba(167, 139, 250, 0.2)` (accent with transparency)
- Tooltip bg: `rgba(15, 23, 42, 0.92)` (glass effect)
- Legend: 12px font, bottom positioning
- Grid: 3% padding on all sides with 15% bottom for legend

### Design System

**Design philosophy**: Custom glassmorphism over Material theming

- **CSS custom properties** in `:root` + `body.dark-theme` / `body.light-theme`
- **Glass panels**: `backdrop-filter: blur()`, semi-transparent backgrounds, gradient borders, hover glow
- **Font**: Inter (system font stack fallback)
- **Animated background**: Pseudo-elements with `blur(100px)` + float keyframes
- **Theme switching**: `ThemeService` signal → `effect()` → toggle CSS class on `<body>`
- **Component theming**: `:host-context(body.light-theme)` for per-component light variants
- **Responsive breakpoints**: 480px / 768px / 1024px
- **Custom scrollbars**: Styled `::-webkit-scrollbar`

### Environment Config
| Property | Dev | Prod |
|----------|-----|------|
| `production` | false | true |
| `apiUrl` | `/exensioreload/api` | `/exensioreload/api` |
| `apiBaseUrl` | `http://127.0.0.1:8080` | `''` |
| `useProxy` | true | false |
| `showSenderLookupSql` | true | false |
| `showPreviewDebug` | true | false |

### Dev Proxy
`/exensioreload/api` → `http://127.0.0.1:8004` (WebSocket enabled, changeOrigin, no path rewrite)

---

## Core Business Flow

### The Resend Workflow (3-Step Stepper)

1. **Configure Request** — Select environment → site → sender (auto-resolved or manual lookup from external DB). Set filters: lots, wafers, date range, tester type, data type, test phase, location.

2. **Discovery Preview** — Query external Oracle DB for matching metadata. Shows paginated results with lot/wafer/filename details. Duplicate detection against already-staged records. Option to stage selected items or stage-all.
     - **Adaptive request behavior (frontend)**:
         - Normal mode (non-historical, no super-admin date-range): requests `size=1000`, `bypassCap=false` for faster response.
         - Historical mode **or** super-admin date-range mode: requests `size=10000`, `bypassCap=true` to support high-volume discovery.

3. **Monitor Dispatch** — Real-time SSE progress tracking. Files are staged in `SENDER_STAGE` table → dispatched to external sender queues → completion notifications via email.

### Data Flow
```
External Oracle DBs (20+ sites)
    ↓ (discover/preview via JdbcExternalMetadataRepository)
SENDER_STAGE table (RefDB Oracle)
    ↓ (SenderDispatchService @Scheduled 60s)
External sender queues (DTP_SENDER_QUEUE_ITEM) → ENRICHMENT
    ↓ (SenderQueueMonitor detects row gone from queue — StagePipelinePolicy)
[ES configured]     → ENRICHMENT (CpLogMonitor polls ES)
[ES off, Exensio on] → EXENSIO_LOADING (ExensioLoadMonitor polls API)
[ES off, Exensio off] → DONE (direct)
    ↓ (CpLogMonitor when ES on)
ENRICHMENT → EXENSIO_LOADING or DONE (per Exensio config) or FAILED
    ↓ (ExensioLoadMonitor when Exensio on)
EXENSIO_LOADING → DONE (exensio_wafer_key + exensio_pg_key) or FAILED
    ↓ (CompletionNotificationService cron 5min)
All session files DONE → Email notification
```

### Dashboard
- Auto-refreshing (10s polling) snapshot of all staging activity
- Metrics: total staged, ready, enqueued, failed, completed
- Drill-down by site and sender
- **📖 See [DASHBOARD_UI_UX_IMPLEMENTATION_PLAN_ALL_PHASES.md](docs/DASHBOARD_UI_UX_IMPLEMENTATION_PLAN_ALL_PHASES.md) for the 4-phase UI/UX improvement plan** (~180h total: Phase 1 Quick Wins → Phase 2 Enhanced Interactivity → Phase 3 Visual Polish → Phase 4 Advanced Features)

---

## Development Conventions

### Backend
- **DTOs are Java records** — immutable, no setters
- **Services are transactional** — `@Transactional` on service methods
- **Controllers are thin** — delegate to services immediately
- **Custom JDBC for external queries** — `JdbcExternalMetadataRepository` builds dynamic SQL with parameterized queries
- **Error handling**: JSON error responses via `RestAuthenticationEntryPoint` and `RestAccessDeniedHandler`
- **Audit everything**: User management actions logged with IP/UserAgent

### Frontend
- **Every component is standalone** — no NgModules anywhere
- **Signals for local state** — all form state, loading flags, filter values use `signal()`
- **Observables for HTTP** — `BackendService` returns `Observable<T>`, consumed via `firstValueFrom()` or `async` pipe
- **`inject()` function** — preferred over constructor injection
- **CVA pattern** — all custom form inputs implement `NG_VALUE_ACCESSOR` with `forwardRef`
- **Glass-* prefix** — all custom UI components follow `glass-*` naming
- **Template syntax mix** — `@for`/`@switch` (Angular 17+) in newer components, `*ngIf`/`*ngFor` in older ones
- **No OnPush** — all components use default change detection strategy
- **Material used sparingly** — MatIcon, MatButton, MatTable, MatPaginator, MatSort. Custom glass components replace most Material inputs.

### File Organization
```
frontend/src/app/                # 44 .ts files, 5 .html, 5 .scss (54 total)
├── api/                         # BackendService (HTTP layer)
├── auth/                        # 8 files: Auth components + service + guard + interceptor
├── core/                        # ThemeService
├── dashboard/                   # 3 files: component + html + scss
├── stepper/                     # 4 files: component + html + scss + duplicate-warning-dialog
├── my-sessions/                 # 1 file: inline template component
├── admin/                       # 5 files: user-list (html+scss), user-form-dialog, user.service
└── shared/
    ├── confirm-dialog.component.ts  # Material Dialog (only Material dialog in app)
    ├── components/              # 16 reusable glass-* / monitoring-* / toast components
    ├── directives/              # GlassTooltipDirective
    └── services/                # Toast, Monitoring, GlassDialog services
```

**Note**: Most components use inline templates/styles (single `.ts` file). Feature components with separate `.html`/`.scss`: `DashboardComponent`, `StepperComponent`, `UserListComponent`, `MySessionsComponent`. Total: 46 TypeScript files. Zero `.spec.ts` test files. No custom pipes. Interfaces defined inline in services.

---

## Critical Gotchas

1. **Use `frontend/`** — this is the active Angular app
2. **Context path `/exensioreload`** — all API calls go through `/exensioreload/api/*`
3. **Dev proxy required** — Frontend at :4200 proxies to backend at :8004
4. **Oracle-specific SQL** — `JdbcExternalMetadataRepository` uses Oracle syntax (ROWNUM, NVL, TO_DATE)
5. **External DB writes gated** — `EXTERNAL_DB_ALLOW_WRITES` env var must be true for write operations
6. **Dual auth state** — `AuthService` maintains both `BehaviorSubject` (for templates with `async`) and `signal` (for signal-based code). Keep both in sync.
7. **GlassDialogService vs Material Dialog** — Use `GlassDialogService` for new dialogs. Only `ConfirmDialogComponent` still uses Material Dialog.
8. **StepperComponent is large** (2098 LOC) — comprehensive form logic, state management, and real-time validation
9. **SenderController** (~1644 LOC) and **RefDbService** (~2334 LOC) — largest backend files; **JdbcExternalMetadataRepository** (~1464 LOC) for dynamic SQL construction
10. **Caffeine cache** — 30s TTL on preview metadata (200 entries max). Stale data possible during rapid re-queries.
11. **Frontend Hub link** — Added navigation to `/exensio-integration-hub/` landing page in app header.
12. **Browser favicon** — SVG data-URI embedded Resender icon (account_tree); no separate favicon file needed.
13. **Versions aligned** — Frontend login displays "1.0", tab title shows "ExensioReload", backend version "1.0.0-SNAPSHOT".
14. **Discovery preview sizing is mode-aware** — Stepper uses an adaptive preview window: `1000` rows for normal mode, `10000` rows with `bypassCap=true` for historical/super-admin date-range queries.
15. **`SENDER_STAGE` status machine** — Active statuses: `NEW → ENRICHMENT → EXENSIO_LOADING → DONE / FAILED / CANCELLED`. `ENQUEUED` is dead code — never written; `SenderDispatchService` transitions directly `NEW → ENRICHMENT` on successful push to `DTP_SENDER_QUEUE_ITEM`. `StatusMapper` uses `inExternalQueue` flag to distinguish "In Queue (pending CP)" from "Enrichment / Translation" for the same `ENRICHMENT` DB status. See `docs/STATUS_PIPELINE.md` for full pipeline detail.
16. **CP Elasticsearch polling** — `CpLogMonitor` is safe to deploy with ES unconfigured (`cp.elasticsearch.url` blank = no-op). Configure via `application.yml` under `cp.elasticsearch.*`. Uses JDK `java.net.http.HttpClient` — no extra Maven dependencies.
17. **No ES client library** — ES queries are raw HTTP POST to `/{index}/_search` using `java.net.http.HttpClient` + Jackson. Auth header (API key or Basic) is computed in `ElasticsearchLogService` constructor from `CpElasticsearchProperties`.
18. **Exensio Loading API integration** — `ExensioLoadMonitor` polls `EXENSIO_LOADING` records and calls `POST /v1/key/lot-wafer-lookup` (lot + wafer). Safe to deploy unconfigured (`exensio.enabled=false` = no-op). Enable via env vars: `EXENSIO_ENABLED=true`, `EXENSIO_QA_URL`, `EXENSIO_USERNAME`, `EXENSIO_PASSWORD`. On success stores `exensio_wafer_key` + `exensio_pg_key` on the record for future results queries.
19. **Capability-based completion** — `StagePipelinePolicy` routes after CP queue consumption: ES → `CpLogMonitor`; else Exensio → `EXENSIO_LOADING` + `ExensioLoadMonitor`; else `DONE`. After ES success, Exensio is used only when configured; otherwise `DONE` with CP output metadata.
20. **`AuthGuard` is async** — on new tab / browser restart, the guard calls `restoreSession()` which hits `/api/auth/refresh` using the persistent cookie before deciding to redirect to login. `APP_INITIALIZER` also awaits the refresh before Angular activates any route.
