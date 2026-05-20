# Staging Monitoring System — Design Document

## 1. Problem Statement

### Frontend Standards for This Workstream

All frontend changes in this design must follow project-wide `new_frontend/` standards:

- Use current Angular app version and modern Angular patterns/features supported by the codebase.
- Keep UI fully aligned with the glassmorphism design system.
- Ensure dark and light theme compatibility for every new/updated monitoring UI element.
- Apply frontend best practices (typed contracts, signal/observable usage consistency, and reusable component/service design).

The current monitoring system in the DTP Resender application has **10 critical gaps** that prevent it from accurately tracking the lifecycle of staged files from stepper step 2 through external processor consumption.

### Current Data Flow (What Exists)

```
User selects files in Step 2
    ↓ POST /senders/{id}/stage
SENDER_STAGE table (status = NEW, requestId = ?)
    ↓ @Scheduled every 60s (SenderDispatchService)
DTP_SENDER_QUEUE_ITEM (external Oracle table)
    + SENDER_STAGE status → ENQUEUED
    ↓ External Processor consumes item (deletes from DTP_SENDER_QUEUE_ITEM)
    ↓ @Scheduled every 120s (SenderQueueMonitor)
SENDER_STAGE status → DONE
```

### Identified Gaps

| # | Gap | Root Cause | Impact |
|---|-----|-----------|--------|
| **G1** | requestId is generated client-side AFTER staging completes | `startMonitoring()` creates `req-${Date.now()}-...` but `stagePayloads()` may have already been called without it | Files staged without requestId are invisible to monitoring queries |
| **G2** | SSE infrastructure exists but is not connected | `MonitoringService.connectSSE()` exists but stepper calls `startPolling()` instead | No real-time push; 3s polling wastes bandwidth and adds latency |
| **G3** | Completion detection has 120s+ latency | `SenderQueueMonitor` runs every 120s, checks only 200 records per cycle | Users see ENQUEUED for minutes after external consumption |
| **G4** | No queue presence awareness | UI shows ENQUEUED but cannot distinguish "in queue" vs "consumed by Processor" | No visibility into external processing progress |
| **G5** | Sessions not persisted | No `staging_session` entity; requestId exists but no metadata (user, site, sender, timestamps, total count) | Cannot retrieve session history after browser close |
| **G6** | `stopStagingSession()` is a no-op | Returns `{"success": true}` without doing anything | Stop button is broken |
| **G7** | Monitoring caps at 100 records | `getStageRecordsList(..., 0, 100)` hardcoded limit | Large sessions (1000+ files) only see 100 in the monitoring UI |
| **G8** | No lot/wafer hierarchy | Flat file list with no aggregation | Cannot see "Lot X: 50/100 wafers done" at a glance |
| **G9** | Status mapping mismatch | Backend writes `DONE`, frontend expects `COMPLETED` | Broken status badge display |
| **G10** | My Sessions page is a stub | 58-line component hitting dead endpoint `/staging/history` | Non-functional session history |

---

## 2. Solution Architecture

### 2.1 High-Level Architecture

```
┌────────────────────────── BROWSER ──────────────────────────┐
│                                                               │
│  Stepper Step 3 (Live)           My Sessions (Historical)    │
│  ┌─────────────────────┐        ┌─────────────────────────┐  │
│  │ Session Header       │        │ Session List Table       │  │
│  │ • site/sender/user   │  SSE   │ • status, progress bar  │  │
│  │ • progress ring      │◄─────  │ • timestamps            │  │
│  │                      │        │ • click → drill-down     │  │
│  │ Summary Metrics      │        │                          │  │
│  │ • total/queued/done  │        │ Session Detail View      │  │
│  │ • throughput/ETA     │        │ • same as Step 3 but     │  │
│  │                      │        │   for any past session   │  │
│  │ Lot/Wafer Tree       │        │ • re-checks external     │  │
│  │ • collapsible lots   │        │   queue on demand        │  │
│  │ • wafer progress     │        └─────────────────────────┘  │
│  │ • file-level detail  │                                     │
│  │                      │  HTTP reconnect if SSE drops        │
│  │ Activity Feed        │◄──────────────────────────────────  │
│  └─────────────────────┘                                      │
└───────────────────────────────────────────────────────────────┘
              │ SSE /api/stage/monitor/{sessionId}
              │ HTTP /api/stage/sessions/*
              ▼
┌────────────────────────── BACKEND ────────────────────────────┐
│                                                                │
│  NEW: staging_session table (Liquibase)                        │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │ id (UUID PK)                                              │  │
│  │ user_id, username                                         │  │
│  │ site, sender_id, sender_name, environment                 │  │
│  │ total_files                                                │  │
│  │ status: STAGING | DISPATCHING | MONITORING |               │  │
│  │         COMPLETED | PARTIALLY_FAILED | CANCELLED           │  │
│  │ files_staged, files_enqueued, files_done, files_failed     │  │
│  │ created_at, updated_at, completed_at                       │  │
│  │ last_checked_at (external queue check timestamp)           │  │
│  └──────────────────────────────────────────────────────────┘  │
│                                                                │
│  SENDER_STAGE table (existing, enhanced)                       │
│  └─ request_id = staging_session.id (UUID)                    │
│                                                                │
│  StageSessionService (NEW)                                     │
│  ├─ createSession() → UUID                                    │
│  ├─ getSession(id) → session + live counts from SENDER_STAGE  │
│  ├─ getUserSessions(username) → list with progress            │
│  ├─ refreshExternalStatus(id) → on-demand queue check         │
│  ├─ completeSession(id) / cancelSession(id)                   │
│  └─ aggregateLotWaferProgress(id) → lot/wafer tree data       │
│                                                                │
│  StageMonitorService (enhanced SSE)                            │
│  ├─ per-session ConcurrentHashMap<UUID, Set<SseEmitter>>      │
│  ├─ heartbeat @Scheduled every 15s                            │
│  ├─ events: STATS, FILE_UPDATE, LOT_UPDATE, COMPLETE, HEARTBEAT │
│  └─ auto-cleanup on emitter completion/timeout/error          │
│                                                                │
│  SenderQueueMonitor (enhanced)                                 │
│  ├─ @Scheduled every 30s (down from 120s)                     │
│  ├─ Processes ALL ENQUEUED records (paged, not just 200)      │
│  ├─ On completion → fires SSE FILE_UPDATE + STATS events      │
│  ├─ On all-done → fires SSE SESSION_COMPLETE event            │
│  └─ Updates staging_session counters                          │
│                                                                │
│  StageController (enhanced)                                    │
│  ├─ POST /sessions → create session                           │
│  ├─ GET  /sessions → list user sessions                       │
│  ├─ GET  /sessions/{id} → session detail + live counts        │
│  ├─ GET  /sessions/{id}/files → paginated files with filter   │
│  ├─ GET  /sessions/{id}/lots → lot/wafer aggregation          │
│  ├─ POST /sessions/{id}/refresh → on-demand external check    │
│  ├─ POST /sessions/{id}/cancel → cancel staging session       │
│  ├─ GET  /sessions/{id}/monitor → SSE stream                  │
│  └─ GET  /sessions/{id}/export → CSV export                   │
└────────────────────────────────────────────────────────────────┘
```

### 2.2 Status Lifecycle

```
                          SENDER_STAGE row lifecycle
                          ─────────────────────────
                          NEW ──► ENQUEUED ──► DONE
                           │         │          │
                           │         │          └─ item consumed by external Processor
                           │         └─ inserted into DTP_SENDER_QUEUE_ITEM
                           └─ staged by user

                          staging_session lifecycle
                          ─────────────────────────
                          STAGING ──► DISPATCHING ──► MONITORING ──► COMPLETED
                            │             │              │              │
                            │             │              │              └─ all files DONE or FAILED
                            │             │              └─ dispatch complete, waiting for Processors
                            │             └─ SenderDispatchService picking up NEW → ENQUEUED
                            └─ user staging files (INSERT into SENDER_STAGE)
                                                                  ──► PARTIALLY_FAILED
                                                                        └─ some files FAILED, rest DONE
                                                                  ──► CANCELLED
                                                                        └─ user cancelled
```

### 2.3 Unified Status in UI

The frontend displays a translated status per file:

| SENDER_STAGE.status | External Queue | UI Display | Color |
|---------------------|---------------|------------|-------|
| `NEW` | — | **Staged** | Blue |
| `ENQUEUED` | Item EXISTS in DTP_SENDER_QUEUE_ITEM | **Queued** | Yellow |
| `ENQUEUED` | Item NOT in DTP_SENDER_QUEUE_ITEM | **Processing** | Orange |
| `DONE` | — | **Completed** | Green |
| `FAILED` | — | **Failed** | Red |

The "Processing" state is inferred: if a file is `ENQUEUED` in SENDER_STAGE but no longer present in `DTP_SENDER_QUEUE_ITEM`, the external Processor has picked it up. The `SenderQueueMonitor` detects this and marks it `DONE`, but the on-demand refresh endpoint can detect it instantly for the UI.

---

## 3. Backend Design

### 3.1 New Database Table: `staging_session`

**Liquibase changelog**: `db.changelog-9.0-staging-session.xml`

```sql
CREATE TABLE staging_session (
    id              VARCHAR(36)   PRIMARY KEY,   -- UUID
    username        VARCHAR(120)  NOT NULL,
    site            VARCHAR(60)   NOT NULL,
    sender_id       NUMBER(10)    NOT NULL,
    sender_name     VARCHAR(200),
    environment     VARCHAR(20),
    
    total_files     NUMBER(10)    DEFAULT 0,
    files_staged    NUMBER(10)    DEFAULT 0,     -- status = NEW (redundant but fast)
    files_enqueued  NUMBER(10)    DEFAULT 0,     -- status = ENQUEUED 
    files_done      NUMBER(10)    DEFAULT 0,     -- status = DONE
    files_failed    NUMBER(10)    DEFAULT 0,     -- status = FAILED
    
    status          VARCHAR(30)   DEFAULT 'STAGING' NOT NULL,
    -- STAGING, DISPATCHING, MONITORING, COMPLETED, PARTIALLY_FAILED, CANCELLED
    
    created_at      TIMESTAMP     DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP     DEFAULT CURRENT_TIMESTAMP,
    completed_at    TIMESTAMP,
    last_checked_at TIMESTAMP,    -- last external queue check
    
    CONSTRAINT staging_session_status_chk 
        CHECK (status IN ('STAGING','DISPATCHING','MONITORING','COMPLETED','PARTIALLY_FAILED','CANCELLED'))
);

CREATE INDEX idx_staging_session_user ON staging_session(username);
CREATE INDEX idx_staging_session_status ON staging_session(status);
```

This table lives on the same **RefDB** as `SENDER_STAGE` (same HikariDataSource). No JPA entity needed — raw JDBC like the rest of `RefDbService`.

### 3.2 New Service: `StageSessionService`

```java
@Service
public class StageSessionService {
    
    // === Session Lifecycle ===
    
    /** Create a new staging session BEFORE staging files. Returns UUID. */
    String createSession(String username, String site, int senderId, 
                         String senderName, String environment);
    
    /** Update file counters by recounting from SENDER_STAGE WHERE request_id = sessionId */
    void refreshCounters(String sessionId);
    
    /** Transition session status based on file counters */
    void updateSessionStatus(String sessionId);
    
    /** Cancel: mark remaining NEW files as CANCELLED, update session status */
    void cancelSession(String sessionId);
    
    // === Queries ===
    
    /** Get single session with live counters (recount from SENDER_STAGE) */
    StagingSessionDetail getSession(String sessionId);
    
    /** List all sessions for a user, ordered by created_at DESC */
    List<StagingSessionSummary> getUserSessions(String username, int page, int size);
    
    /** Get paginated files for a session with optional status/search filter */
    StageRecordPage getSessionFiles(String sessionId, String statusFilter, 
                                     String search, int page, int size);
    
    /** Get lot/wafer aggregation for a session */
    List<LotWaferProgress> getSessionLotWaferProgress(String sessionId);
    
    // === External Queue Check ===
    
    /** On-demand: check external DTP_SENDER_QUEUE_ITEM for this session's 
     *  ENQUEUED files, mark completed ones as DONE, return updated counts */
    StagingSessionDetail refreshExternalStatus(String sessionId);
}
```

### 3.3 Enhanced `StageMonitorService`

```java
@Service
public class StageMonitorService {
    // Support MULTIPLE emitters per session (multiple browser tabs)
    private final Map<String, Set<SseEmitter>> emitters = new ConcurrentHashMap<>();
    
    SseEmitter subscribe(String sessionId);    // add emitter to set
    void sendEvent(String sessionId, String type, Object data);  // broadcast to all
    void sendHeartbeat();                      // @Scheduled every 15s, all sessions
    void completeSession(String sessionId);    // send COMPLETE, close all emitters
    
    // Event types:
    //   STATS          - { total, staged, enqueued, done, failed, progress%, throughput, eta }
    //   FILE_UPDATE    - { id, metadataId, dataId, lot, wafer, filename, status, message }
    //   LOT_UPDATE     - { lot, wafer, total, done, failed }
    //   SESSION_STATUS - { status: "MONITORING" | "COMPLETED" | ... }
    //   HEARTBEAT      - { timestamp }
}
```

### 3.4 Enhanced `SenderQueueMonitor`

```java
@Scheduled(fixedDelayString = "${refdb.dispatch.monitor-interval-ms:30000}")
public void monitorQueue() {
    // 1. Page through ALL ENQUEUED records (not just 200)
    // 2. For each completed record:
    //    a. Mark DONE in SENDER_STAGE
    //    b. Fire SSE FILE_UPDATE event via StageMonitorService
    //    c. Increment session counter
    // 3. After processing each session's files:
    //    a. Recount and broadcast STATS
    //    b. If all terminal → mark session COMPLETED, fire SESSION_COMPLETE
}
```

### 3.5 New REST Endpoints

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| POST | `/api/stage/sessions` | USER | Create staging session → returns `{ sessionId: UUID }` |
| GET | `/api/stage/sessions` | USER | List current user's sessions (paginated) |
| GET | `/api/stage/sessions/{id}` | USER | Session detail with live counters |
| GET | `/api/stage/sessions/{id}/files` | USER | Paginated file list with status/search filter |
| GET | `/api/stage/sessions/{id}/lots` | USER | Lot/wafer aggregated progress |
| POST | `/api/stage/sessions/{id}/refresh` | USER | On-demand external queue check → returns updated detail |
| POST | `/api/stage/sessions/{id}/cancel` | USER | Cancel session (mark remaining NEW as CANCELLED) |
| GET | `/api/stage/sessions/{id}/monitor` | USER | SSE event stream |
| GET | `/api/stage/sessions/{id}/export` | USER | CSV export of all files |

### 3.6 Modified Staging Flow

**Before (broken):**
```
Frontend: stagePayloads(senderId, body)  // requestId may or may not be in body
Frontend: startMonitoring()              // generates requestId AFTER staging
Frontend: polls /stage/stats?requestId=  // may miss files staged without this requestId
```

**After (fixed):**
```
Frontend: POST /api/stage/sessions       // → { sessionId: "uuid-..." }
          body: { site, senderId, senderName, environment }

Frontend: POST /senders/{id}/stage       // → stages files
          body: { ..., requestId: sessionId }  // sessionId known BEFORE staging

Frontend: GET /api/stage/sessions/{sessionId}/monitor  // SSE stream
          + initial: GET /api/stage/sessions/{sessionId}  // snapshot for reconnect
```

---

## 4. Frontend Design

### 4.1 New/Modified Services

#### `StagingSessionService` (NEW — `shared/services/staging-session.service.ts`)

```typescript
@Injectable({ providedIn: 'root' })
export class StagingSessionService {
  // Signals
  currentSession = signal<StagingSessionDetail | null>(null);
  sessionFiles   = signal<MonitoringFile[]>([]);
  lotProgress    = signal<LotWaferProgress[]>([]);
  activities     = signal<ActivityEvent[]>([]);
  isConnected    = signal(false);
  
  // Computed
  progress = computed(() => {
    const s = this.currentSession();
    if (!s || s.totalFiles === 0) return 0;
    return ((s.filesDone + s.filesFailed) / s.totalFiles) * 100;
  });
  
  isComplete = computed(() => {
    const s = this.currentSession();
    return s?.status === 'COMPLETED' || s?.status === 'PARTIALLY_FAILED';
  });

  // Methods
  createSession(site, senderId, senderName, env): Observable<{ sessionId: string }>;
  connectToSession(sessionId: string): void;   // SSE + initial HTTP snapshot
  refreshSession(sessionId: string): void;      // on-demand external check
  cancelSession(sessionId: string): void;
  disconnectSession(): void;
  
  // File pagination (for large sessions)
  loadFiles(sessionId, page, size, statusFilter?, search?): void;
  loadLotProgress(sessionId): void;
  
  // Reconnection logic
  private reconnectSSE(sessionId: string, retryCount: number): void;
}
```

#### `MonitoringService` — DEPRECATED (replaced by `StagingSessionService`)

### 4.2 Modified Stepper Flow

```
Step 1: Configure Request (unchanged)
Step 2: Discovery Preview
  └─ User clicks "Stage Selected" or "Stage All"
     ├─ 1. Call createSession(site, senderId, senderName, env) → sessionId
     ├─ 2. Call stagePayloads(senderId, { ...payloads, requestId: sessionId })
     ├─ 3. On success → navigate to Step 3
     └─ 4. connectToSession(sessionId) → SSE stream + initial snapshot

Step 3: Monitor Dispatch
  ├─ Live metrics: total, staged, enqueued, done, failed, progress%, throughput, ETA
  ├─ Lot/Wafer aggregation tree (collapsible)
  ├─ File-level table with virtual scroll, status filter, search
  ├─ Activity feed (SSE-driven)
  ├─ "Refresh Now" button → on-demand external queue check
  ├─ "Cancel" button → cancel remaining unstaged files
  └─ "Finish" → navigates to dashboard (session persists in My Sessions)
```

### 4.3 Rebuilt My Sessions Page

```
┌──────────────────────────────────────────────────────────────────┐
│  My Staging Sessions                                              │
│  ┌──────────────────────────────────────────────────────────────┐ │
│  │ Filters: [All] [Active] [Completed] [Failed]    Search: [  ]│ │
│  ├──────────────────────────────────────────────────────────────┤ │
│  │ Session ID  │ Site   │ Sender │ Files │ Progress │ Status   │ │
│  │ abc-123...  │ BE2    │ 1234   │  500  │ ████░ 80%│ MONITORING│ │
│  │ def-456...  │ CEBU   │ 5678   │ 1200  │ █████100%│ COMPLETED│ │
│  │ ghi-789...  │ MYD    │ 9012   │  300  │ ███░░ 60%│ PART_FAIL│ │
│  ├──────────────────────────────────────────────────────────────┤ │
│  │ Pagination: < 1 2 3 ... 10 >                                │ │
│  └──────────────────────────────────────────────────────────────┘ │
│                                                                    │
│  Click row → Session Detail (same monitoring UI as Step 3):       │
│  • If session is active → reconnects SSE for live updates         │
│  • If session is complete → shows final state with all file data   │
│  • "Refresh" button checks external queue for any ENQUEUED files  │
└──────────────────────────────────────────────────────────────────┘
```

### 4.4 Lot/Wafer Aggregation View (New Component)

```
┌────────────────────────────────────────────────┐
│  Lot/Wafer Progress                             │
│  ┌────────────────────────────────────────────┐ │
│  │ ▼ Lot: ABC123  (3/5 wafers done)           │ │
│  │   ├─ Wafer 01  ████████████  COMPLETED     │ │
│  │   ├─ Wafer 02  ████████████  COMPLETED     │ │
│  │   ├─ Wafer 03  ████████████  COMPLETED     │ │
│  │   ├─ Wafer 04  ██████░░░░░░  QUEUED        │ │
│  │   └─ Wafer 05  ░░░░░░░░░░░░  STAGED        │ │
│  │                                              │ │
│  │ ▶ Lot: DEF456  (10/10 wafers done) ✓       │ │
│  │ ▶ Lot: GHI789  (0/8 wafers done)  ⏳       │ │
│  └────────────────────────────────────────────┘ │
└────────────────────────────────────────────────┘
```

---

## 5. SSE Event Protocol

### 5.1 Event Types

| Event Name | Payload | When Fired |
|------------|---------|------------|
| `STATS` | `{ total, staged, enqueued, done, failed, progress, throughput, eta, successRate }` | After any status change batch, every 15s heartbeat if client connected |
| `FILE_UPDATE` | `{ id, metadataId, dataId, lot, wafer, filename, status, message, updatedAt }` | When a file's status changes (NEW→ENQUEUED, ENQUEUED→DONE, etc.) |
| `LOT_UPDATE` | `{ lot, totalWafers, completedWafers, failedWafers }` | When a lot's aggregate progress changes |
| `SESSION_STATUS` | `{ status, completedAt?, message }` | When session transitions (STAGING→DISPATCHING→MONITORING→COMPLETED) |
| `HEARTBEAT` | `{ timestamp, uptime }` | Every 15s to keep connection alive and confirm liveness |

### 5.2 Reconnection Protocol

1. Client opens SSE: `GET /api/stage/sessions/{sessionId}/monitor`
2. If connection drops:
   a. Wait 1s, retry
   b. Exponential backoff: 1s, 2s, 4s, 8s, max 30s
   c. On reconnect: also fetch `GET /api/stage/sessions/{id}` for full snapshot (SSE may have missed events)
3. If session is COMPLETED/PARTIALLY_FAILED/CANCELLED:
   a. Server sends `SESSION_STATUS` with terminal state
   b. Server sends final `STATS`
   c. Server closes the emitter (client sees `EventSource.CLOSED`)

---

## 6. Performance Considerations

### 6.1 Large File Sets (1,000–10,000 files)

| Concern | Solution |
|---------|----------|
| SSE event flood | Batch FILE_UPDATE events: buffer for 500ms, send as array `FILE_UPDATES` with up to 50 files per event |
| File list pagination | Backend paginated endpoint (`/sessions/{id}/files?page=0&size=100`); frontend uses virtual scroll |
| Counter accuracy | `staging_session` counters updated atomically in SQL; live counts use COUNT from SENDER_STAGE |
| Queue check volume | `SenderQueueMonitor` paginates ENQUEUED records (500 per page); external queue keys fetched once per site/sender |

### 6.2 Multiple Concurrent Sessions

- Each session has its own SSE emitter set
- `SenderQueueMonitor` processes all sessions in each cycle (grouped by site/sender for efficient external queries)
- Heartbeat iterates all sessions (bounded by active emitter count)

### 6.3 Database Load

| Query | Frequency | Optimization |
|-------|-----------|-------------|
| Count by status per session | On SSE STATS | Index on `(request_id, status)` — add to SENDER_STAGE |
| List files paginated | On user scroll | Index on `(request_id, status, created_at)` |
| External queue keys | Every 30s per site/sender | Single `SELECT id_metadata, id_data` with `WHERE id_sender = ?` |
| Lot/wafer aggregation | On request | `GROUP BY lot, wafer` with `WHERE request_id = ?` |

New index needed on `SENDER_STAGE`:
```sql
CREATE INDEX idx_sender_stage_request_status ON SENDER_STAGE(request_id, status);
```

---

## 7. Implementation Plan

### Phase 1: Foundation (Backend)
1. Add Liquibase changelog for `staging_session` table + new index on SENDER_STAGE
2. Create `StageSessionService` with JDBC operations
3. Enhance `StageMonitorService` (multi-emitter, heartbeat, batched events)
4. Add new REST endpoints to `StageController`
5. Fix `stagePayloads()` to accept and propagate `requestId` properly
6. Enhance `SenderQueueMonitor` (30s interval, paged processing, SSE broadcast)
7. Wire session status transitions in `SenderDispatchService`

### Phase 2: Frontend Core
8. Create `StagingSessionService` (Angular service with signals + SSE)
9. Fix stepper staging flow: create session → stage → connect SSE
10. Rebuild Step 3 monitoring UI with new service
11. Add lot/wafer aggregation component

### Phase 3: My Sessions
12. Rebuild `MySessionsComponent` with session list, filters, pagination
13. Add session detail drill-down (reuses Step 3 components)
14. Add reconnection logic for active sessions

### Phase 4: Polish
15. Status mapping cleanup (DONE → COMPLETED in all SSE events)
16. Cancel session functionality
17. CSV export per session
18. Error handling and edge cases

---

## 8. Migration Notes

- **Backward compatible**: Existing `requestId` values in SENDER_STAGE will NOT have matching `staging_session` rows. The system should handle missing sessions gracefully (show raw data without session metadata).
- **No schema-breaking changes**: New table `staging_session` is additive. New index on SENDER_STAGE is non-blocking.
- **Frontend routes unchanged**: Step 3 still at `/exensioreload/new`, My Sessions still at `/my-sessions`.
- **Old monitoring code deprecated**: `MonitoringService` polling fallback remains available but not used when SSE is connected.

---

## 9. File Inventory (What Gets Changed)

### Backend — New Files
| File | Purpose |
|------|---------|
| `service/StageSessionService.java` | Session CRUD + external queue check + lot/wafer aggregation |
| `dto/StagingSessionDetail.java` | Full session detail record |
| `dto/StagingSessionSummary.java` | Session list item record |
| `dto/LotWaferProgress.java` | Lot/wafer aggregation record |
| `dto/CreateSessionRequest.java` | Session creation request |
| `db/changelog/db.changelog-9.0-staging-session.xml` | Table + index DDL |

### Backend — Modified Files
| File | Change |
|------|--------|
| `stage/StageMonitorService.java` | Multi-emitter per session, heartbeat, batched events |
| `service/SenderQueueMonitor.java` | 30s interval, paged processing, SSE broadcast, session status |
| `service/SenderDispatchService.java` | Update session status after dispatch |
| `controller/StageController.java` | New session endpoints |
| `service/RefDbService.java` | Minor: ensure requestId propagation in stagePayloads |

### Frontend — New Files
| File | Purpose |
|------|---------|
| `shared/services/staging-session.service.ts` | Session management + SSE + signals |
| `shared/components/lot-wafer-progress.component.ts` | Lot/wafer tree view |
| `my-sessions/session-detail.component.ts` | Session drill-down view |

### Frontend — Modified Files
| File | Change |
|------|--------|
| `stepper/stepper.component.ts` | createSession before staging, connectSSE for Step 3 |
| `stepper/stepper.component.html` | Step 3 uses new session service |
| `my-sessions/my-sessions.component.ts` | Full rebuild with session list + detail |
| `api/backend.service.ts` | New session endpoints |
| `app.routes.ts` | Add session detail route |
```
