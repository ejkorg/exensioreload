# ExensioReload — Project Intelligence

> Single up-to-date reference for the current codebase. Last updated: 2026-08-12.
> Structure: `backend/` (Spring Boot), `frontend/` (Angular), `docs/` (this file).

## Project Overview

ExensioReload is a fullstack application for managing semiconductor test-data resend operations across 20+ manufacturing sites. It discovers metadata from external Oracle databases, stages payloads into an internal database, dispatches them to external sender queues, monitors CP/Exensio completion, and notifies operators — all through a glassmorphism Angular UI.

## Architecture

```
┌──────────────────────────────────────────────────────────────┐
│ frontend (Angular 21.2.12, 100% standalone components)        │
│ Port 4200 (dev) → proxy → backend :8004 (/exensioreload)      │
├──────────────────────────────────────────────────────────────┤
│ backend (Spring Boot 3.2.0, Java 21)                          │
│ Port 8004, context-path /exensioreload                         │
│ JWT auth + optional Microsoft Entra OIDC, Liquibase, HikariCP  │
├──────────────────────────────────────────────────────────────┤
│ Internal DB (profile-selected)   │  20+ Site Oracle DBs        │
│   onsemi-oracle → Oracle RefDB   │  (external metadata source, │
│   onsemi-postgresql → PostgreSQL │   dbconnections.yml)        │
│   pg-local → local Docker PG     │                             │
└──────────────────────────────────────────────────────────────┘
```

## Running the App

```bash
# Backend — Oracle (default, unchanged)
cd backend
mvn spring-boot:run -Dspring-boot.run.arguments="--spring.profiles.active=onsemi-oracle"

# Backend — PostgreSQL (internal DB only; set REFDB_PASSWORD)
mvn spring-boot:run -Dspring-boot.run.arguments="--spring.profiles.active=onsemi-postgresql"

# Backend — local ephemeral PostgreSQL (docker compose -f scripts/docker-compose-pg.yml up -d)
mvn spring-boot:run -Dspring-boot.run.arguments="--spring.profiles.active=pg-local"

# Frontend
cd frontend
npm install
npm start          # dev server on :4200, proxies /exensioreload/api → :8004

# Builds
mvn clean package -DskipTests          # backend jar
npm run build:prod:deploy              # frontend dist
```

## Backend — Spring Boot 3.2.0 / Java 21

### Tech Stack

| Layer | Technology |
|---|---|
| Language / Framework | Java 21, Spring Boot 3.2.0 |
| Build | Maven (`com.onsemi.cim.apps.exensio.exensioreload:exensio-reload:1.0.0-SNAPSHOT`) |
| ORM | Spring Data JPA / Hibernate |
| Primary DB (Oracle profile) | Oracle via `ojdbc11` 23.3.0 |
| Primary DB (PG profiles) | PostgreSQL via `org.postgresql:postgresql` (runtime) |
| Migrations | Liquibase (`db/changelog/db.changelog-1.0.xml` master) |
| Auth | JWT (jjwt) + optional Microsoft Entra OIDC |
| Connection Pool | HikariCP (per-site dynamic pools for external DBs) |
| HTTP | Spring Web, `java.net.http.HttpClient` for ES/Exensio |
| Caching | Caffeine |
| Metrics | Micrometer + Prometheus |

### Package Structure

```
com.onsemi.cim.apps.exensio.exensioreload
├── ExensioreloadApplication.java   # @SpringBootApplication, @EnableScheduling, @EnableAsync
├── config/     # Security, JWT, DataSource, RefDb/PpLog/Exensio/ES props, SSO
├── controller/ # REST controllers + StageRecordMapper + ProbeStrategy
├── service/    # Business logic (staging, dispatch, monitors, AI services)
├── entity/     # JPA entities (users, audit_log, load_session, SENDER_STAGE is JDBC, ...)
├── repository/ # JPA repos + JdbcExternalMetadataRepository (dynamic Oracle SQL)
├── dto/        # Request/response records
└── stage/      # StageRecord, PipelineStatus, StageStatus, monitors, state machine
```

### Profiles & Configuration

| Profile | Internal DB | Notes |
|---|---|---|
| (none/default) | H2 fallback (test) | `application.yml` |
| `onsemi-oracle` | Oracle `exnqa-db.onsemi.com:1740/EXNQA.onsemi.com` | Production default; unchanged |
| `onsemi-postgresql` | PG `10.253.112.87:5432/exnr` | New; password via `REFDB_PASSWORD` env |
| `pg-local` | PG `localhost:5432/exnr` | Local Docker validation (`scripts/docker-compose-pg.yml`) |

Key config files:
- `application.yml` — defaults, Liquibase on, logging, CP/Exensio/Snowflake/AI/ETL sections
- `application-onsemi-oracle.yml` — Oracle profile (dialect OracleDialect, `jdbc:oracle:thin`)
- `application-onsemi-postgresql.yml` — PG profile (`PostgreSQLDialect`, `jdbc:postgresql://`, `refdb.db-type: postgresql`)
- `application-pg-local.yml` — local PG override (in-memory-ish, ephemeral)
- `dbconnections.yml` — ~20+ manufacturing site Oracle connections (PROD/QA); **out of scope for PG migration**
- `etlservers.yml` / `etljobs.yml` — optional SSH trigger config

Key properties:
- `refdb.*` — internal staging DB (host, port, database/service/sid, user, password, pool.*, dispatch.*)
- `refdb.db-type` — `oracle` (default) or `postgresql`; drives `RefDbProperties.buildJdbcUrl()` and `RefDbService` driver selection
- `refdb.pplog.*` — separate pp_log connection (points to PRODUCTION Oracle)
- `cp.elasticsearch.*` — CP enrichment polling (URL, api-key, index-pattern `logs*dataport*`, enrichment-timeout-minutes)
- `exensio.*` — Exensio lot-wafer lookup (enabled, QA/PROD URLs, schema-fallback, batch/thread tuning, snowflake secondary)
- `reloader.sso.*` / `reloader.refresh.*` — SSO + refresh cookie
- `ai.*` — AI gateway (preset gemini/anthropic/ollama/groq, api-key, rate limits)
- `snowflake.*` — Snowflake JDBC for lot pre-flight verification (read-only secondary)

### Internal DB Bootstrap (important)

The staging tables (`SENDER_STAGE`, sequences, indexes) are **not** created by Liquibase — `RefDbService.initialize()` (`@PostConstruct`) calls `ensureStageTable()`, which creates the table + `_SEQ` sequence + status index if missing, and adds columns if the table already exists. All bootstrap helpers are DB-aware (`isOracle` / `isPostgres` branches). Liquibase handles the JPA/auth tables and migrations 4.0–10.0.

### Key Background Tasks

| Service | Schedule | Purpose |
|---|---|---|
| `SenderDispatchService` | fixedDelay 60s | Push `STAGED` records into external `DTP_SENDER_QUEUE_ITEM` → `QUEUED_FOR_CP` |
| `SenderQueueMonitor` | fixedDelay 10s | Detect CP queue consumption → route via `StagePipelinePolicy` |
| `CpLogMonitor` | fixedDelay 60s | Poll ES for enrichment outcome; `ELASTICSEARCH_MONITORING → EXENSIO_MONITORING / COMPLETED / CP_FAILED / CP_TIMEOUT`; pp_log fallback |
| `ExensioLoadMonitor` | fixedDelay 60s | Poll Exensio API for load confirmation; stores `exensio_wafer_key` / `exensio_pg_key` |
| `CompletionNotificationService` | cron 5min | Session complete → email |
| `DiscoveryScheduler` | cron (off by default) | Automated discovery |
| `DataIntegrityJob` | scheduled | Validate stage records, detect stuck/invalid states |
| `EtlSshTriggerService` | on-demand | Optional SSH remote crontab kick |

### Pipeline Status Machine (v3.0, `PipelineStatus` enum)

```
DISCOVERED → STAGED → QUEUED_FOR_CP → CP_CONSUMED → ELASTICSEARCH_MONITORING
                                                          ├─ failure → CP_FAILED
                                                          ├─ log found → EXENSIO_MONITORING
                                                          └─ no log > timeout → CP_TIMEOUT → EXENSIO_MONITORING
EXENSIO_MONITORING
    ├─ completed → COMPLETED
    ├─ failure → LOAD_FAILED
    └─ not found > timeout → COMPLETED_MANUAL_VERIFICATION_REQUIRED
```

All 12 statuses are enforced by the `chk_sender_stage_status` CHECK constraint (Oracle via 9.x changelogs; PostgreSQL via the `9.12-postgresql-status-constraint` changeset).

### REST API (context path `/exensioreload/api`)

Key groups: `/auth` (login/refresh/register/verify/reset, SSO initiate/silent), `/senders/{id}/discover/*` (preview, stage-all, historical-summary, CSV), `/stage` (records, stats, monitor SSE, active-sessions), `/dashboard` (snapshot, site/sender drill-down), `/admin/users` (SUPER_ADMIN CRUD, audit-logs), `/external` + `/environments` (DB instances, locations, sites), `/config/limits`, `/internal` (pools, metrics), `/api/ai/*` (chat, triage, cost, forecast, RCA, etc.), diagnostic/test endpoints. See controllers for the full map.

## Frontend — Angular 21.2.12

### Tech Stack

| Layer | Technology |
|---|---|
| Framework | Angular 21.2.12 (standalone components, no NgModules) |
| Language | TypeScript 5.9.2 |
| UI | Angular Material + CDK (sparingly) + custom glassmorphism design system |
| Reactivity | Signals (UI state) + RxJS 7.8 (HTTP/SSE streams) |
| Charts | echarts 6 + ngx-echarts 21, apexcharts 5 + ng-apexcharts 2 |
| Zone | zone.js 0.15 |

### Architecture Decisions

1. 100% standalone components, lazy-loaded routes (`loadComponent()`)
2. Functional interceptors/guards (`HttpInterceptorFn`, `CanActivateFn`)
3. Signals for UI state; RxJS for HTTP + SSE
4. Custom `glass-*` components (backdrop-filter, gradient borders, dark/light themes)
5. `GlassDialogService` (imperative DOM) replaces Material Dialog except `ConfirmDialogComponent`
6. Custom form controls implement `ControlValueAccessor`
7. Default change detection (no OnPush)

### Routing

`/exensioreload` (Dashboard), `/exensioreload/new` + `/edit/:id` (Stepper), `/my-sessions`, `/admin/users`, `/login`, `/register`, `/verify`, `/request-reset`, `/reset-password`, `/sso-callback`. All feature routes guarded by `AuthGuard` (+ `isSuperAdmin` for admin).

### Key Components

- `StepperComponent` — 3-step wizard (Configure → Preview → Monitor), largest component
- `DashboardComponent` — auto-polling snapshot, metric cards, site/sender drill-down
- `MySessionsComponent` — session detail + ECharts analytics (daily trend, status pie)
- `UserListComponent` — SUPER_ADMIN user CRUD
- `RealtimeMonitoringFileListComponent` — per-file CP/Exensio integration status
- `Glass*Component` family — inputs, select, datepicker, calendar, checkbox, button, icon, stepper, pagination, loading overlay, tooltip
- `ToastService` / `MonitoringService` / `AuthService` (dual BehaviorSubject+signal) / `ThemeService` / `BackendService`

### Dev Proxy

`/exensioreload/api` → `http://127.0.0.1:8004` (WebSocket enabled).

## Database

### Liquibase Changelogs (`backend/src/main/resources/db/changelog/`)

- `db.changelog-1.0.xml` — master; creates sender_queue, refresh_tokens, external_*; Oracle sequences/triggers (gated `dbms="oracle"`); includes all 4.0–10.0 changesets
- 4.x — users/auth tables, stage columns
- 6.x — queue wafers, unique constraints, request_id
- 7.0 — super-admin: audit_log, password_history, user_sessions (Oracle timestamp changesets gated)
- 8.0 — auth modernization (merge legacy users)
- 9.0–9.13 — staging_session, status pipeline evolution, enrichment columns, device column, performance indexes, status renames
- `9.2`/`9.6` — Oracle PL/SQL gated to `dbms="oracle"`; PG equivalents added (`DROP CONSTRAINT IF EXISTS`, `ALTER COLUMN ... SET DEFAULT`)
- `9.12` — added `9.12-postgresql-status-constraint` (PG-only) for the final 12-state CHECK
- `10.0-etl-trigger-tables.xml` — `etl_trigger_idempotency` + `etl_trigger_audit_log` (JPA tables previously created manually)

### Tables

- JPA (Liquibase): `users`, `user_roles`, `refresh_tokens`, `verification_tokens`, `password_reset_tokens`, `password_history`, `user_sessions`, `audit_log`, `etl_trigger_idempotency`, `etl_trigger_audit_log`, `load_session`, `load_session_payload`, `external_environment`, `external_location`, `staging_session`, `sender_queue_wafers`
- Bootstrapped by `RefDbService` (not Liquibase): `SENDER_STAGE`, `SENDER_STAGE_SEQ`, `SENDER_STAGE_STATUS_IDX`
- External (out of scope): `DTP_SENDER_QUEUE_ITEM` per site, site metadata views, Exensio/ES/Snowflake

## Business Flow

1. **Configure** — environment → site → sender; filters: lots, wafers, date range (admin), device (admin), tester type, data type, test phase, location
2. **Preview / Stage** — external Oracle metadata query → paginated preview + duplicate detection → stage selected/all into `SENDER_STAGE` (status `STAGED`)
3. **Dispatch** — `SenderDispatchService` pushes to external queue → `QUEUED_FOR_CP`
4. **Monitor** — CP consumption → ES enrichment check → Exensio load check → `COMPLETED` / failures; per-file integration status streamed via SSE
5. **Notify** — completion emails

### External Integration Notes

For the full architecture and troubleshooting guide on Elasticsearch, Exensio API, error propagation, and UI telemetry, see [INTEGRATION_ES_EXENSIO.md](file:///c:/Users/fg8n8x/Desktop/wip/exensioreload/docs/INTEGRATION_ES_EXENSIO.md). For a line-by-line root-cause analysis of the active production log issues (ES misses, raw-SQL 503 errors, premature failures, and dropped SSE events), see [EXENSIO_ES_RUNTIME_ISSUES_ANALYSIS.md](file:///c:/Users/fg8n8x/Desktop/wip/exensioreload/docs/EXENSIO_ES_RUNTIME_ISSUES_ANALYSIS.md).

- **CP Elasticsearch**: Raw HTTP POST to `/_search` (`JDK HttpClient` + Jackson, no ES client library). Filters by `idData` (data_id), `idFile` (metadata_id), `inputFileName`, `mLot`, and `@timestamp` range with a 15-minute lookback buffer (`cp.elasticsearch.lookback-buffer-seconds: 900`) to absorb clock skew. Success indicated by "Commands flow executed successfully", `output path = ` (regex extracted), `PRODUCTION`, or `SANDBOX`. `log.level: ERROR` triggers `CP_FAILED` with structured prefix `[ES Failure]`. Circuit breaker opens after 5 consecutive failures for 60s. No-op when unconfigured.
- **pp_log**: Separate Oracle connection (PRODUCTION) polled in parallel with Elasticsearch. Authoritative source of truth: `process_code == 0` triggers CP success immediately; `process_code != 0` marks `CP_FAILED` with `[pp_log Failure]`.
- **Exensio API & DB**: 3-tier lookup sequence: (1) Direct Oracle raw SQL via `POST /v1/key/raw-sql` on `op_log`/`df_export`/`wafer`; (2) Multi-schema fallback (`PRODUCTION` → `SANDBOX`); (3) REST endpoint `POST /v1/key/lot-wafer-lookup`; (4) Failure inspection in `dp_log` + `string_holder` for loader errors (`LOAD_FAILED`). Dead letter queue trips after 3 consecutive failures. Circuit breaker opens after 5 failures.
- **UI Reporting & Telemetry**:
  - `RefDbService.fetchStatuses()` aggregates v3.0 status counts for the Dashboard snapshot.
  - Backlog is defined as `queuedForCp + elasticsearchMonitoring + cpTimeout + exensioMonitoring + completedManualVerification`.
  - Real-time SSE stream (`GET /exensioreload/api/stage/monitor/{requestId}`) emits `ROW_UPDATE`, `STATS`, and `STUCK_RECORD_ALERT`.
  - Frontend `RealtimeMonitoringFileListComponent` renders virtual-scrolled rows with dynamic multi-segment progress strings and intelligent `CP` vs `Exensio` error badges.
- **Snowflake**: Read-only lot pre-flight verification; not JPA-managed.

## Development Conventions

### Backend

- DTOs are Java `records`; controllers thin; services `@Transactional`
- Custom JDBC for external queries (`JdbcExternalMetadataRepository` builds dynamic parameterized SQL)
- DB-agnostic internal SQL: use `FETCH FIRST ? ROWS ONLY` / `OFFSET ? ROWS FETCH NEXT ? ROWS ONLY` (SQL:2008, works on Oracle 12c+ and PG). For PG-specific needs branch on `isPostgres` (e.g., `nextval('seq')`, `to_char`, `pg_indexes`, `ADD COLUMN`, `ALTER COLUMN ... TYPE`)
- JSON error responses via `RestAuthenticationEntryPoint` / `RestAccessDeniedHandler`
- Audit user-management actions with IP/UserAgent

### Frontend

- Standalone components everywhere; `inject()` over constructor injection
- Signals for local state; observables for HTTP via `BackendService`
- `glass-*` naming for custom UI; both dark and light themes required
- Newer components use `@for`/`@switch`; older use `*ngIf`/`*ngFor` — match surrounding style
- No OnPush; Material only for MatIcon/MatButton/MatTable/MatPaginator/MatSort + ConfirmDialog

## Critical Gotchas

1. **Context path** `/exensioreload` — all API calls go through `/exensioreload/api/*`
2. **Oracle-specific SQL lives in external-DB code** (`JdbcExternalMetadataRepository`, `ExensioClient`, `ExensioRawSqlService`, `ExensioPreCheckService`, `SenderDispatchService`/`SessionPushService`) — those target site/Exensio Oracle DBs and are intentionally Oracle-only. Only the internal datasource (RefDbService + JPA) was made PG-compatible.
3. **`SENDER_STAGE` is bootstrapped in code**, not Liquibase — changing its schema means updating `RefDbService.createTable()` / `ensure*` helpers.
4. **Dual auth state** — `AuthService` keeps both `BehaviorSubject` and `signal` in sync; keep both updated.
5. **`GlassDialogService`** for new dialogs; only `ConfirmDialogComponent` uses Material Dialog.
6. **`SENDER_STAGE` statuses are v3.0** (`PipelineStatus`): DISCOVERED, STAGED, QUEUED_FOR_CP, CP_CONSUMED, ELASTICSEARCH_MONITORING, CP_TIMEOUT, EXENSIO_MONITORING, COMPLETED_MANUAL_VERIFICATION_REQUIRED, COMPLETED, CP_FAILED, LOAD_FAILED, CANCELLED. Old names (ENRICHMENT, DONE, EXENSIO_LOADING, NEW) are migrated in changelogs 9.6–9.12.
7. **`EXTERNAL_DB_ALLOW_WRITES`** env must be true for external write ops.
8. **Secrets**: `REFDB_PASSWORD` (PG profile), `CP_ES_API_KEY`, `EXENSIO_PASSWORD`, AI keys via env vars — never commit values.
9. **PG validation**: no live PG in dev workspace; use `scripts/docker-compose-pg.yml` + `pg-local` profile + `scripts/pg-verify.sh` to validate migrations against ephemeral PG 16.
10. **Versions**: backend Spring Boot 3.2.0 (pom parent), frontend Angular 21.2.12, app version 1.0.0-SNAPSHOT (package.json says 2.1.0 for npm only).
11. **ES & Exensio Monitoring Discrepancies**:
    - `fetchStatuses()` and `StageStatus.accountingSum()` sum 10 statuses, excluding `DISCOVERED` and `CP_CONSUMED`. If records reside in those two states, `DataIntegrityJob` reports an accounting imbalance.
    - ES queries use a 15-minute lookback buffer to absorb clock skew (`cp.elasticsearch.lookback-buffer-seconds: 900`).
    - Exensio lookups prioritize raw SQL on `op_log`/`df_export` before falling back to `lot-wafer-lookup`, and inspect `dp_log` for raw loader errors.
    - See [INTEGRATION_ES_EXENSIO.md](file:///c:/Users/fg8n8x/Desktop/wip/exensioreload/docs/INTEGRATION_ES_EXENSIO.md) for full troubleshooting runbooks.

## Repo Hygiene

- Root should contain only `backend/`, `frontend/`, `docs/exensio.md`, and tooling config (`.vscode/`, `.github/`, etc.).
- This file is the single source of truth for onboarding/AI context — update it when behavior changes.
