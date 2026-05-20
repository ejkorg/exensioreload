# Design Document: Staging Monitoring System Enhancements

## Overview

This design document describes enhancements to the existing Staging Monitoring System. The core infrastructure (StageSessionService, StageMonitorService, session persistence, SSE, and MySessionsComponent) is already implemented. This document focuses on the remaining gaps and improvements needed for a complete monitoring solution.

### Current State (Already Implemented ✅)

1. **Session Persistence**: staging_session table with Liquibase migrations
2. **StageSessionService**: Full CRUD, counter management, lot/wafer aggregation
3. **StageMonitorService**: Basic SSE with multi-emitter support and heartbeat
4. **Frontend StagingSessionService**: SSE connection with polling fallback
5. **MySessionsComponent**: Session list and detail view
6. **API Endpoints**: All session endpoints implemented in StageController
7. **Integration**: SenderDispatchService and SenderQueueMonitor integrated with sessions

### Remaining Gaps to Address

| Gap | Current State | Enhancement Needed |
|-----|--------------|-------------------|
| **G2: SSE Events** | Basic STATS, ROW_UPDATE, COMPLETE events | Add FILE_UPDATE, LOT_UPDATE, SESSION_STATUS events with richer payloads |
| **G3: Latency** | SenderQueueMonitor runs at default interval | Reduce to 30s, add paging for large sessions |
| **G4: Queue Awareness** | Backend checks external queue | Expose "Queued" vs "Processing" status in UI |
| **G8: Lot/Wafer UI** | Backend aggregation exists | Create collapsible lot/wafer tree component |
| **Event Batching** | Individual events per file | Batch FILE_UPDATE events (500ms buffer, max 50 files) |
| **Activity Feed** | Not implemented | Add activity feed component with event history |
| **Throughput/ETA** | Not calculated | Add throughput and ETA calculations to STATS events |

### Key Design Goals

1. **Enhanced SSE Events**: Richer event payloads with FILE_UPDATE, LOT_UPDATE, SESSION_STATUS
2. **Reduced Latency**: 30-second monitor cycle with complete pagination
3. **Queue Status Visibility**: Distinguish "Queued" (in external queue) vs "Processing" (consumed)
4. **Lot/Wafer UI Component**: Collapsible tree view for hierarchical progress
5. **Event Batching**: Reduce SSE traffic for rapid status changes
6. **Activity Feed**: Chronological event log for user visibility
7. **Performance Metrics**: Throughput (files/min) and ETA calculations

## Architecture

### System Components

```
┌─────────────────────── Frontend (Angular 21) ───────────────────────┐
│                                                                       │
│  StagingSessionService (NEW)                                         │
│  ├─ Signals: currentSession, sessionFiles, lotProgress, activities   │
│  ├─ SSE connection management with auto-reconnect                    │
│  ├─ HTTP fallback polling (3s interval)                              │
│  └─ Session lifecycle: create → connect → monitor → disconnect       │
│                                                                       │
│  StepperComponent (MODIFIED)                                         │
│  └─ Step 3: Real-time monitoring UI with lot/wafer tree              │
│                                                                       │
│  MySessionsComponent (REBUILT)                                       │
│  └─ Session list + detail drill-down with reconnection               │
│                                                                       │
└───────────────────────────────────────────────────────────────────────┘
                                    │
                                    │ HTTP + SSE
                                    ▼
┌─────────────────────── Backend (Spring Boot) ────────────────────────┐
│                                                                       │
│  StageSessionService (NEW)                                           │
│  ├─ Session CRUD operations                                          │
│  ├─ Counter recalculation from SENDER_STAGE                          │
│  ├─ Lot/wafer aggregation queries                                    │
│  └─ External queue status refresh                                    │
│                                                                       │
│  StageMonitorService (ENHANCED)                                      │
│  ├─ Multi-emitter SSE per session (ConcurrentHashMap)                │
│  ├─ Event types: STATS, FILE_UPDATE, LOT_UPDATE, SESSION_STATUS      │
│  ├─ Heartbeat @Scheduled every 15s                                   │
│  └─ Batched events (buffer 500ms, max 50 files per event)            │
│                                                                       │
│  SenderQueueMonitor (ENHANCED)                                       │
│  ├─ @Scheduled every 30s (down from 120s)                            │
│  ├─ Pages through ALL ENQUEUED records (500 per page)                │
│  ├─ Broadcasts SSE events on status changes                          │
│  └─ Updates staging_session counters                                 │
│                                                                       │
│  StageController (ENHANCED)                                          │
│  └─ New endpoints: /sessions, /sessions/{id}/*, /sessions/{id}/monitor │
│                                                                       │
└───────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────── Database (Oracle RefDB) ──────────────────────┐
│                                                                       │
│  staging_session (NEW TABLE)                                         │
│  ├─ id (UUID PK), username, site, sender_id, sender_name, environment│
│  ├─ total_files, files_staged, files_enqueued, files_done, files_failed│
│  ├─ status (STAGING | DISPATCHING | MONITORING | COMPLETED | ...)    │
│  └─ created_at, updated_at, completed_at, last_checked_at            │
│                                                                       │
│  SENDER_STAGE (EXISTING, ENHANCED)                                   │
│  └─ request_id = staging_session.id (UUID foreign key)               │
│                                                                       │
│  NEW INDEX: (request_id, status) on SENDER_STAGE                     │
│                                                                       │
└───────────────────────────────────────────────────────────────────────┘
```


### Data Flow

```
1. User initiates staging
   ↓
2. Frontend: POST /api/stage/sessions → { sessionId: UUID }
   ↓
3. Frontend: POST /senders/{id}/stage (body includes requestId: sessionId)
   ↓
4. Backend: INSERT INTO SENDER_STAGE (request_id = sessionId, status = NEW)
   ↓
5. Frontend: GET /api/stage/sessions/{sessionId}/monitor (SSE stream)
   ↓
6. Backend: SenderDispatchService @Scheduled(60s)
   - SELECT * FROM SENDER_STAGE WHERE status = NEW
   - INSERT INTO DTP_SENDER_QUEUE_ITEM (external Oracle)
   - UPDATE SENDER_STAGE SET status = ENQUEUED
   - Broadcast FILE_UPDATE + STATS events via SSE
   ↓
7. External Processor consumes from DTP_SENDER_QUEUE_ITEM (deletes row)
   ↓
8. Backend: SenderQueueMonitor @Scheduled(30s)
   - SELECT id_metadata, id_data FROM DTP_SENDER_QUEUE_ITEM
   - Compare with SENDER_STAGE WHERE status = ENQUEUED
   - UPDATE SENDER_STAGE SET status = DONE (for missing items)
   - Broadcast FILE_UPDATE + STATS events via SSE
   ↓
9. When all files terminal → Broadcast SESSION_STATUS(COMPLETED) → Close SSE
```

### Status Lifecycle

**File Status (SENDER_STAGE.status):**
```
NEW → ENQUEUED → DONE
 │         │        │
 │         └─ FAILED (dispatch error)
 └─ CANCELLED (user cancellation)
```

**Session Status (staging_session.status):**
```
STAGING → DISPATCHING → MONITORING → COMPLETED
   │           │            │            │
   │           │            │            └─ All files DONE
   │           │            └─ PARTIALLY_FAILED (some DONE, some FAILED)
   │           └─ CANCELLED (user cancelled)
   └─ Initial state
```

## Components and Interfaces

### Backend Components

#### 1. StageSessionService (Already Implemented ✅)

**Current Implementation**: Fully functional with all core methods

**Enhancements Needed**:
- Add throughput calculation (files completed per minute over last 5 minutes)
- Add ETA calculation (remaining files / throughput)
- Return throughput and ETA in StagingSessionDetail

**New Methods to Add**:
```java
public class StageSessionService {
    // NEW: Calculate throughput and ETA
    SessionMetrics calculateMetrics(String sessionId);
}

public record SessionMetrics(
    double throughput,  // files per minute
    int eta,            // minutes remaining
    double successRate  // done / (done + failed)
) {}
```


#### 2. StageMonitorService (Enhanced)

**Responsibility**: Manage SSE connections and broadcast events

**Key Methods**:
```java
@Service
public class StageMonitorService {
    private final Map<String, Set<SseEmitter>> emitters = new ConcurrentHashMap<>();
    
    SseEmitter subscribe(String sessionId);
    void sendEvent(String sessionId, String type, Object data);
    void sendHeartbeat();  // @Scheduled every 15s
    void completeSession(String sessionId);
    
    // Event types
    void broadcastStats(String sessionId, SessionStats stats);
    void broadcastFileUpdate(String sessionId, FileUpdate update);
    void broadcastLotUpdate(String sessionId, LotUpdate update);
    void broadcastSessionStatus(String sessionId, String status);
}
```

**Event Batching**:
- Buffer FILE_UPDATE events for 500ms
- Send batched events as FILE_UPDATES array (max 50 files per event)
- Reduces SSE traffic for rapid status changes

**Emitter Management**:
- Auto-cleanup on emitter completion/timeout/error
- Separate emitter set per session ID
- Thread-safe concurrent access

#### 3. SenderQueueMonitor (Enhanced)

**Responsibility**: Detect file completion by comparing SENDER_STAGE with external queue

**Key Changes**:
```java
@Scheduled(fixedDelayString = "${refdb.dispatch.monitor-interval-ms:30000}")
public void monitorQueue() {
    // 1. Get all active sessions (status IN ('DISPATCHING', 'MONITORING'))
    List<String> activeSessions = stageSessionService.getActiveSessions();
    
    // 2. For each session:
    for (String sessionId : activeSessions) {
        // a. Page through ENQUEUED files (500 per page)
        int page = 0;
        while (true) {
            List<StageRecord> enqueuedFiles = getEnqueuedFiles(sessionId, page, 500);
            if (enqueuedFiles.isEmpty()) break;
            
            // b. Query external queue for these files
            Set<String> queueKeys = queryExternalQueue(enqueuedFiles);
            
            // c. Mark missing files as DONE
            List<StageRecord> completed = enqueuedFiles.stream()
                .filter(f -> !queueKeys.contains(f.getKey()))
                .collect(Collectors.toList());
            
            markAsDone(completed);
            
            // d. Broadcast events
            for (StageRecord file : completed) {
                stageMonitorService.broadcastFileUpdate(sessionId, toFileUpdate(file));
            }
            
            page++;
        }
        
        // e. Refresh session counters and broadcast STATS
        stageSessionService.refreshCounters(sessionId);
        stageSessionService.updateSessionStatus(sessionId);
        
        StagingSessionDetail session = stageSessionService.getSession(sessionId);
        stageMonitorService.broadcastStats(sessionId, toStats(session));
        
        // f. If terminal status → broadcast SESSION_STATUS and close emitters
        if (isTerminal(session.getStatus())) {
            stageMonitorService.broadcastSessionStatus(sessionId, session.getStatus());
            stageMonitorService.completeSession(sessionId);
        }
    }
}
```


#### 4. StageController (Enhanced)

**New REST Endpoints**:

```java
@RestController
@RequestMapping("/api/stage")
public class StageController {
    
    @PostMapping("/sessions")
    public ResponseEntity<CreateSessionResponse> createSession(
        @RequestBody CreateSessionRequest request) {
        String sessionId = stageSessionService.createSession(
            getCurrentUsername(), 
            request.site(), 
            request.senderId(),
            request.senderName(), 
            request.environment()
        );
        return ResponseEntity.ok(new CreateSessionResponse(sessionId));
    }
    
    @GetMapping("/sessions")
    public ResponseEntity<StagingSessionPage> getUserSessions(
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size) {
        List<StagingSessionSummary> sessions = 
            stageSessionService.getUserSessions(getCurrentUsername(), page, size);
        return ResponseEntity.ok(new StagingSessionPage(sessions, page, size));
    }
    
    @GetMapping("/sessions/{id}")
    public ResponseEntity<StagingSessionDetail> getSession(@PathVariable String id) {
        return ResponseEntity.ok(stageSessionService.getSession(id));
    }
    
    @GetMapping("/sessions/{id}/files")
    public ResponseEntity<StageRecordPage> getSessionFiles(
        @PathVariable String id,
        @RequestParam(required = false) String status,
        @RequestParam(required = false) String search,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "100") int size) {
        return ResponseEntity.ok(
            stageSessionService.getSessionFiles(id, status, search, page, size)
        );
    }
    
    @GetMapping("/sessions/{id}/lots")
    public ResponseEntity<List<LotWaferProgress>> getSessionLotProgress(
        @PathVariable String id) {
        return ResponseEntity.ok(stageSessionService.getSessionLotWaferProgress(id));
    }
    
    @PostMapping("/sessions/{id}/refresh")
    public ResponseEntity<StagingSessionDetail> refreshExternalStatus(
        @PathVariable String id) {
        return ResponseEntity.ok(stageSessionService.refreshExternalStatus(id));
    }
    
    @PostMapping("/sessions/{id}/cancel")
    public ResponseEntity<Void> cancelSession(@PathVariable String id) {
        stageSessionService.cancelSession(id);
        return ResponseEntity.ok().build();
    }
    
    @GetMapping("/sessions/{id}/monitor")
    public SseEmitter monitorSession(@PathVariable String id) {
        SseEmitter emitter = stageMonitorService.subscribe(id);
        
        // Send initial snapshot
        StagingSessionDetail session = stageSessionService.getSession(id);
        stageMonitorService.sendEvent(id, "STATS", toStats(session));
        
        return emitter;
    }
    
    @GetMapping("/sessions/{id}/export")
    public ResponseEntity<StreamingResponseBody> exportSessionCsv(
        @PathVariable String id) {
        return ResponseEntity.ok()
            .header("Content-Type", "text/csv")
            .header("Content-Disposition", "attachment; filename=session-" + id + ".csv")
            .body(outputStream -> {
                stageSessionService.streamSessionFilesCsv(id, outputStream);
            });
    }
}
```


### Frontend Components

#### 1. StagingSessionService (NEW)

**Responsibility**: Manage session state and SSE connections

**Implementation**:
```typescript
@Injectable({ providedIn: 'root' })
export class StagingSessionService {
  private eventSource: EventSource | null = null;
  private reconnectAttempts = 0;
  private readonly maxReconnectDelay = 30000;
  
  // Signals
  currentSession = signal<StagingSessionDetail | null>(null);
  sessionFiles = signal<MonitoringFile[]>([]);
  lotProgress = signal<LotWaferProgress[]>([]);
  activities = signal<ActivityEvent[]>([]);
  isConnected = signal(false);
  isReconnecting = signal(false);
  
  // Computed
  progress = computed(() => {
    const s = this.currentSession();
    if (!s || s.totalFiles === 0) return 0;
    return ((s.filesDone + s.filesFailed) / s.totalFiles) * 100;
  });
  
  isComplete = computed(() => {
    const s = this.currentSession();
    return s?.status === 'COMPLETED' || 
           s?.status === 'PARTIALLY_FAILED' || 
           s?.status === 'CANCELLED';
  });
  
  // Methods
  createSession(site: string, senderId: number, senderName: string, 
                env: string): Observable<{ sessionId: string }> {
    return this.http.post<{ sessionId: string }>('/api/stage/sessions', {
      site, senderId, senderName, environment: env
    });
  }
  
  connectToSession(sessionId: string): void {
    // 1. Fetch initial snapshot
    this.http.get<StagingSessionDetail>(`/api/stage/sessions/${sessionId}`)
      .subscribe(session => {
        this.currentSession.set(session);
        this.loadFiles(sessionId, 0, 100);
        this.loadLotProgress(sessionId);
      });
    
    // 2. Establish SSE connection
    this.connectSSE(sessionId);
  }
  
  private connectSSE(sessionId: string): void {
    this.eventSource = new EventSource(`/api/stage/sessions/${sessionId}/monitor`);
    this.isConnected.set(true);
    this.isReconnecting.set(false);
    this.reconnectAttempts = 0;
    
    this.eventSource.addEventListener('STATS', (event) => {
      const stats = JSON.parse(event.data);
      this.updateSessionStats(stats);
    });
    
    this.eventSource.addEventListener('FILE_UPDATE', (event) => {
      const update = JSON.parse(event.data);
      this.updateFile(update);
      this.addActivity('file', update);
    });
    
    this.eventSource.addEventListener('FILE_UPDATES', (event) => {
      const updates = JSON.parse(event.data);
      updates.forEach(update => {
        this.updateFile(update);
        this.addActivity('file', update);
      });
    });
    
    this.eventSource.addEventListener('LOT_UPDATE', (event) => {
      const update = JSON.parse(event.data);
      this.updateLotProgress(update);
    });
    
    this.eventSource.addEventListener('SESSION_STATUS', (event) => {
      const { status } = JSON.parse(event.data);
      this.currentSession.update(s => s ? { ...s, status } : null);
      this.addActivity('session', { status });
      
      if (this.isComplete()) {
        this.disconnectSession();
      }
    });
    
    this.eventSource.addEventListener('HEARTBEAT', () => {
      // Connection alive
    });
    
    this.eventSource.onerror = () => {
      this.isConnected.set(false);
      this.eventSource?.close();
      this.reconnectSSE(sessionId);
    };
  }
  
  private reconnectSSE(sessionId: string): void {
    if (this.isComplete()) return;
    
    this.isReconnecting.set(true);
    const delay = Math.min(
      1000 * Math.pow(2, this.reconnectAttempts),
      this.maxReconnectDelay
    );
    
    setTimeout(() => {
      this.reconnectAttempts++;
      
      // Fetch snapshot to recover missed events
      this.http.get<StagingSessionDetail>(`/api/stage/sessions/${sessionId}`)
        .subscribe({
          next: (session) => {
            this.currentSession.set(session);
            this.connectSSE(sessionId);
          },
          error: () => {
            this.reconnectSSE(sessionId);
          }
        });
    }, delay);
  }
  
  refreshSession(sessionId: string): void {
    this.http.post<StagingSessionDetail>(`/api/stage/sessions/${sessionId}/refresh`, {})
      .subscribe(session => {
        this.currentSession.set(session);
      });
  }
  
  cancelSession(sessionId: string): void {
    this.http.post(`/api/stage/sessions/${sessionId}/cancel`, {})
      .subscribe(() => {
        // Session status will update via SSE
      });
  }
  
  disconnectSession(): void {
    this.eventSource?.close();
    this.eventSource = null;
    this.isConnected.set(false);
  }
  
  loadFiles(sessionId: string, page: number, size: number, 
            statusFilter?: string, search?: string): void {
    const params = new HttpParams()
      .set('page', page.toString())
      .set('size', size.toString())
      .set('status', statusFilter || '')
      .set('search', search || '');
    
    this.http.get<StageRecordPage>(`/api/stage/sessions/${sessionId}/files`, { params })
      .subscribe(result => {
        this.sessionFiles.set(result.records);
      });
  }
  
  loadLotProgress(sessionId: string): void {
    this.http.get<LotWaferProgress[]>(`/api/stage/sessions/${sessionId}/lots`)
      .subscribe(lots => {
        this.lotProgress.set(lots);
      });
  }
  
  private updateSessionStats(stats: any): void {
    this.currentSession.update(s => s ? { ...s, ...stats } : null);
  }
  
  private updateFile(update: any): void {
    this.sessionFiles.update(files => 
      files.map(f => f.id === update.id ? { ...f, ...update } : f)
    );
  }
  
  private updateLotProgress(update: any): void {
    this.lotProgress.update(lots =>
      lots.map(lot => lot.lot === update.lot ? { ...lot, ...update } : lot)
    );
  }
  
  private addActivity(type: string, data: any): void {
    const activity: ActivityEvent = {
      type,
      timestamp: new Date(),
      message: this.formatActivityMessage(type, data)
    };
    
    this.activities.update(acts => {
      const updated = [activity, ...acts];
      return updated.slice(0, 100); // Keep only 100 most recent
    });
  }
  
  private formatActivityMessage(type: string, data: any): string {
    if (type === 'file') {
      return `File ${data.filename} → ${data.status}`;
    } else if (type === 'session') {
      return `Session → ${data.status}`;
    }
    return '';
  }
}
```


#### 2. StepperComponent (Modified)

**Changes to Step 2 → Step 3 Transition**:

```typescript
// OLD (broken):
async stageSelected() {
  await this.backendService.stagePayloads(this.senderId, payloads);
  this.startMonitoring(); // generates requestId AFTER staging
  this.currentStep.set(3);
}

// NEW (fixed):
async stageSelected() {
  // 1. Create session FIRST
  const { sessionId } = await firstValueFrom(
    this.stagingSessionService.createSession(
      this.site,
      this.senderId,
      this.senderName,
      this.environment
    )
  );
  
  // 2. Stage files with sessionId
  await this.backendService.stagePayloads(this.senderId, {
    ...payloads,
    requestId: sessionId
  });
  
  // 3. Connect to session monitoring
  this.stagingSessionService.connectToSession(sessionId);
  
  // 4. Navigate to Step 3
  this.currentStep.set(3);
}
```

**Step 3 Template Changes**:

```html
<!-- OLD: MonitoringService polling -->
<div class="monitoring-stats">
  <div>Total: {{ monitoringService.stats().total }}</div>
  <div>Done: {{ monitoringService.stats().done }}</div>
</div>

<!-- NEW: StagingSessionService with signals -->
<div class="monitoring-stats">
  @if (stagingSessionService.currentSession(); as session) {
    <div class="session-header">
      <h3>{{ session.site }} - {{ session.senderName }}</h3>
      <div class="progress-ring" [style.--progress]="stagingSessionService.progress()">
        {{ stagingSessionService.progress() | number:'1.0-0' }}%
      </div>
    </div>
    
    <div class="stats-grid">
      <div class="stat-card">
        <span class="label">Total Files</span>
        <span class="value">{{ session.totalFiles }}</span>
      </div>
      <div class="stat-card">
        <span class="label">Staged</span>
        <span class="value">{{ session.filesStaged }}</span>
      </div>
      <div class="stat-card">
        <span class="label">Queued</span>
        <span class="value">{{ session.filesEnqueued }}</span>
      </div>
      <div class="stat-card">
        <span class="label">Completed</span>
        <span class="value">{{ session.filesDone }}</span>
      </div>
      <div class="stat-card">
        <span class="label">Failed</span>
        <span class="value">{{ session.filesFailed }}</span>
      </div>
    </div>
    
    @if (session.throughput > 0) {
      <div class="throughput">
        <span>{{ session.throughput | number:'1.1-1' }} files/min</span>
        <span>ETA: {{ session.eta }} min</span>
      </div>
    }
    
    @if (stagingSessionService.isReconnecting()) {
      <div class="reconnecting-indicator">
        <glass-icon name="refresh" class="spin"></glass-icon>
        Reconnecting...
      </div>
    }
  }
</div>

<!-- Lot/Wafer Progress Tree -->
<div class="lot-wafer-tree">
  <h4>Lot/Wafer Progress</h4>
  @for (lot of stagingSessionService.lotProgress(); track lot.lot) {
    <div class="lot-group" [class.expanded]="expandedLots.has(lot.lot)">
      <div class="lot-header" (click)="toggleLot(lot.lot)">
        <glass-icon [name]="expandedLots.has(lot.lot) ? 'chevron-down' : 'chevron-right'"></glass-icon>
        <span class="lot-name">{{ lot.lot }}</span>
        <span class="lot-progress">{{ lot.completedWafers }}/{{ lot.totalWafers }} wafers</span>
        <div class="progress-bar">
          <div class="fill" [style.width.%]="(lot.completedWafers / lot.totalWafers) * 100"></div>
        </div>
      </div>
      
      @if (expandedLots.has(lot.lot)) {
        <div class="wafer-list">
          @for (wafer of lot.wafers; track wafer.wafer) {
            <div class="wafer-item">
              <span class="wafer-name">Wafer {{ wafer.wafer }}</span>
              <span class="wafer-status" [class]="wafer.status">{{ wafer.status }}</span>
              <div class="progress-bar small">
                <div class="fill" [style.width.%]="wafer.progress"></div>
              </div>
            </div>
          }
        </div>
      }
    </div>
  }
</div>

<!-- File List with Virtual Scroll -->
<div class="file-list">
  <div class="file-list-controls">
    <glass-select [(ngModel)]="statusFilter" (ngModelChange)="filterFiles()">
      <option value="">All Statuses</option>
      <option value="NEW">Staged</option>
      <option value="ENQUEUED">Queued</option>
      <option value="DONE">Completed</option>
      <option value="FAILED">Failed</option>
    </glass-select>
    
    <glass-input [(ngModel)]="searchTerm" (ngModelChange)="searchFiles()" 
                 placeholder="Search lot, wafer, filename..."></glass-input>
  </div>
  
  <cdk-virtual-scroll-viewport itemSize="48" class="file-viewport">
    @for (file of stagingSessionService.sessionFiles(); track file.id) {
      <div class="file-row">
        <span class="lot">{{ file.lot }}</span>
        <span class="wafer">{{ file.wafer }}</span>
        <span class="filename">{{ file.filename }}</span>
        <span class="status" [class]="mapStatus(file.status)">
          {{ mapStatus(file.status) }}
        </span>
      </div>
    }
  </cdk-virtual-scroll-viewport>
  
  <glass-pagination [page]="currentPage" [size]="pageSize" [total]="totalFiles"
                    (pageChange)="loadPage($event)"></glass-pagination>
</div>

<!-- Activity Feed -->
<div class="activity-feed">
  <h4>Activity</h4>
  <div class="activity-list">
    @for (activity of stagingSessionService.activities(); track activity.timestamp) {
      <div class="activity-item">
        <span class="timestamp">{{ activity.timestamp | date:'HH:mm:ss' }}</span>
        <span class="message">{{ activity.message }}</span>
      </div>
    }
  </div>
</div>

<!-- Actions -->
<div class="actions">
  <glass-button variant="secondary" (click)="refreshNow()">
    <glass-icon name="refresh"></glass-icon>
    Refresh Now
  </glass-button>
  
  @if (!stagingSessionService.isComplete()) {
    <glass-button variant="danger" (click)="cancelSession()">
      Cancel Session
    </glass-button>
  }
  
  <glass-button variant="tertiary" (click)="exportCsv()">
    <glass-icon name="download"></glass-icon>
    Export CSV
  </glass-button>
  
  <glass-button variant="primary" (click)="finish()" 
                [disabled]="!stagingSessionService.isComplete()">
    Finish
  </glass-button>
</div>
```


#### 3. MySessionsComponent (Rebuilt)

**Implementation**:

```typescript
@Component({
  selector: 'app-my-sessions',
  standalone: true,
  imports: [CommonModule, GlassButtonComponent, GlassInputComponent, 
            GlassSelectComponent, GlassPaginationComponent],
  template: `
    <div class="my-sessions-container">
      <h2>My Staging Sessions</h2>
      
      <div class="filters">
        <glass-select [(ngModel)]="statusFilter" (ngModelChange)="loadSessions()">
          <option value="">All Sessions</option>
          <option value="STAGING,DISPATCHING,MONITORING">Active</option>
          <option value="COMPLETED">Completed</option>
          <option value="PARTIALLY_FAILED">Partially Failed</option>
          <option value="CANCELLED">Cancelled</option>
        </glass-select>
        
        <glass-input [(ngModel)]="searchTerm" (ngModelChange)="loadSessions()"
                     placeholder="Search by site, sender..."></glass-input>
      </div>
      
      <div class="sessions-table">
        <table>
          <thead>
            <tr>
              <th>Session ID</th>
              <th>Site</th>
              <th>Sender</th>
              <th>Files</th>
              <th>Progress</th>
              <th>Status</th>
              <th>Created</th>
              <th>Actions</th>
            </tr>
          </thead>
          <tbody>
            @for (session of sessions(); track session.id) {
              <tr (click)="viewSession(session.id)" class="clickable">
                <td>{{ session.id.substring(0, 8) }}...</td>
                <td>{{ session.site }}</td>
                <td>{{ session.senderName }}</td>
                <td>{{ session.totalFiles }}</td>
                <td>
                  <div class="progress-bar">
                    <div class="fill" [style.width.%]="session.progress"></div>
                  </div>
                  <span>{{ session.progress | number:'1.0-0' }}%</span>
                </td>
                <td>
                  <span class="status-badge" [class]="session.status">
                    {{ session.status }}
                  </span>
                </td>
                <td>{{ session.createdAt | date:'short' }}</td>
                <td>
                  <glass-button variant="icon" (click)="viewSession(session.id); $event.stopPropagation()">
                    <glass-icon name="eye"></glass-icon>
                  </glass-button>
                </td>
              </tr>
            }
          </tbody>
        </table>
      </div>
      
      <glass-pagination [page]="currentPage()" [size]="pageSize" 
                        [total]="totalSessions()"
                        (pageChange)="loadPage($event)"></glass-pagination>
    </div>
    
    <!-- Session Detail Modal (shown when session selected) -->
    @if (selectedSession()) {
      <div class="session-detail-overlay" (click)="closeDetail()">
        <div class="session-detail-panel" (click)="$event.stopPropagation()">
          <div class="panel-header">
            <h3>Session Detail</h3>
            <glass-button variant="icon" (click)="closeDetail()">
              <glass-icon name="close"></glass-icon>
            </glass-button>
          </div>
          
          <!-- Reuse Step 3 monitoring components -->
          <app-monitoring-stats [session]="selectedSession()"></app-monitoring-stats>
          <app-lot-wafer-progress [sessionId]="selectedSession().id"></app-lot-wafer-progress>
          <app-monitoring-file-list [sessionId]="selectedSession().id"></app-monitoring-file-list>
          <app-monitoring-activity [activities]="stagingSessionService.activities()"></app-monitoring-activity>
        </div>
      </div>
    }
  `
})
export class MySessionsComponent implements OnInit, OnDestroy {
  private http = inject(HttpClient);
  protected stagingSessionService = inject(StagingSessionService);
  
  sessions = signal<StagingSessionSummary[]>([]);
  selectedSession = signal<StagingSessionDetail | null>(null);
  currentPage = signal(0);
  totalSessions = signal(0);
  pageSize = 20;
  statusFilter = '';
  searchTerm = '';
  
  ngOnInit() {
    this.loadSessions();
  }
  
  ngOnDestroy() {
    this.stagingSessionService.disconnectSession();
  }
  
  loadSessions() {
    const params = new HttpParams()
      .set('page', this.currentPage().toString())
      .set('size', this.pageSize.toString())
      .set('status', this.statusFilter)
      .set('search', this.searchTerm);
    
    this.http.get<StagingSessionPage>('/api/stage/sessions', { params })
      .subscribe(result => {
        this.sessions.set(result.sessions);
        this.totalSessions.set(result.total);
      });
  }
  
  loadPage(page: number) {
    this.currentPage.set(page);
    this.loadSessions();
  }
  
  viewSession(sessionId: string) {
    this.http.get<StagingSessionDetail>(`/api/stage/sessions/${sessionId}`)
      .subscribe(session => {
        this.selectedSession.set(session);
        
        // If session is active, connect SSE for live updates
        if (session.status === 'STAGING' || 
            session.status === 'DISPATCHING' || 
            session.status === 'MONITORING') {
          this.stagingSessionService.connectToSession(sessionId);
        }
      });
  }
  
  closeDetail() {
    this.stagingSessionService.disconnectSession();
    this.selectedSession.set(null);
  }
}
```


## Data Models

### Backend DTOs

```java
// Session creation request
public record CreateSessionRequest(
    String site,
    int senderId,
    String senderName,
    String environment
) {}

// Session creation response
public record CreateSessionResponse(String sessionId) {}

// Session summary (for list view)
public record StagingSessionSummary(
    String id,
    String username,
    String site,
    int senderId,
    String senderName,
    String environment,
    int totalFiles,
    int filesDone,
    int filesFailed,
    String status,
    double progress,  // calculated: (done + failed) / total * 100
    LocalDateTime createdAt,
    LocalDateTime completedAt
) {}

// Session detail (for single view)
public record StagingSessionDetail(
    String id,
    String username,
    String site,
    int senderId,
    String senderName,
    String environment,
    int totalFiles,
    int filesStaged,
    int filesEnqueued,
    int filesDone,
    int filesFailed,
    String status,
    double progress,
    double throughput,  // files per minute
    int eta,            // minutes remaining
    LocalDateTime createdAt,
    LocalDateTime updatedAt,
    LocalDateTime completedAt,
    LocalDateTime lastCheckedAt
) {}

// Lot/wafer aggregation
public record LotWaferProgress(
    String lot,
    int totalWafers,
    int completedWafers,
    int failedWafers,
    List<WaferProgress> wafers
) {}

public record WaferProgress(
    String wafer,
    int totalFiles,
    int completedFiles,
    int failedFiles,
    double progress,
    String status  // "STAGED" | "QUEUED" | "PROCESSING" | "COMPLETED" | "FAILED"
) {}

// SSE event payloads
public record SessionStats(
    int total,
    int staged,
    int enqueued,
    int done,
    int failed,
    double progress,
    double throughput,
    int eta,
    double successRate
) {}

public record FileUpdate(
    long id,
    String metadataId,
    String dataId,
    String lot,
    String wafer,
    String filename,
    String status,
    String message,
    LocalDateTime updatedAt
) {}

public record LotUpdate(
    String lot,
    int totalWafers,
    int completedWafers,
    int failedWafers
) {}

public record SessionStatusEvent(
    String status,
    LocalDateTime completedAt,
    String message
) {}
```

### Frontend Interfaces

```typescript
interface StagingSessionSummary {
  id: string;
  username: string;
  site: string;
  senderId: number;
  senderName: string;
  environment: string;
  totalFiles: number;
  filesDone: number;
  filesFailed: number;
  status: SessionStatus;
  progress: number;
  createdAt: Date;
  completedAt?: Date;
}

interface StagingSessionDetail extends StagingSessionSummary {
  filesStaged: number;
  filesEnqueued: number;
  throughput: number;
  eta: number;
  updatedAt: Date;
  lastCheckedAt?: Date;
}

type SessionStatus = 
  | 'STAGING' 
  | 'DISPATCHING' 
  | 'MONITORING' 
  | 'COMPLETED' 
  | 'PARTIALLY_FAILED' 
  | 'CANCELLED';

type FileStatus = 'NEW' | 'ENQUEUED' | 'DONE' | 'FAILED' | 'CANCELLED';

type DisplayStatus = 'Staged' | 'Queued' | 'Processing' | 'Completed' | 'Failed' | 'Cancelled';

interface MonitoringFile {
  id: number;
  metadataId: string;
  dataId: string;
  lot: string;
  wafer: string;
  filename: string;
  status: FileStatus;
  displayStatus: DisplayStatus;
  message?: string;
  createdAt: Date;
  updatedAt: Date;
}

interface LotWaferProgress {
  lot: string;
  totalWafers: number;
  completedWafers: number;
  failedWafers: number;
  wafers: WaferProgress[];
}

interface WaferProgress {
  wafer: string;
  totalFiles: number;
  completedFiles: number;
  failedFiles: number;
  progress: number;
  status: DisplayStatus;
}

interface ActivityEvent {
  type: 'file' | 'session' | 'lot';
  timestamp: Date;
  message: string;
}

interface StageRecordPage {
  records: MonitoringFile[];
  page: number;
  size: number;
  total: number;
}

interface StagingSessionPage {
  sessions: StagingSessionSummary[];
  page: number;
  size: number;
  total: number;
}
```

### Database Schema

```sql
-- New table: staging_session
CREATE TABLE staging_session (
    id              VARCHAR(36)   PRIMARY KEY,   -- UUID
    username        VARCHAR(120)  NOT NULL,
    site            VARCHAR(60)   NOT NULL,
    sender_id       NUMBER(10)    NOT NULL,
    sender_name     VARCHAR(200),
    environment     VARCHAR(20),
    
    total_files     NUMBER(10)    DEFAULT 0,
    files_staged    NUMBER(10)    DEFAULT 0,
    files_enqueued  NUMBER(10)    DEFAULT 0,
    files_done      NUMBER(10)    DEFAULT 0,
    files_failed    NUMBER(10)    DEFAULT 0,
    
    status          VARCHAR(30)   DEFAULT 'STAGING' NOT NULL,
    
    created_at      TIMESTAMP     DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP     DEFAULT CURRENT_TIMESTAMP,
    completed_at    TIMESTAMP,
    last_checked_at TIMESTAMP,
    
    CONSTRAINT staging_session_status_chk 
        CHECK (status IN ('STAGING','DISPATCHING','MONITORING','COMPLETED','PARTIALLY_FAILED','CANCELLED'))
);

CREATE INDEX idx_staging_session_user ON staging_session(username);
CREATE INDEX idx_staging_session_status ON staging_session(status);
CREATE INDEX idx_staging_session_created ON staging_session(created_at DESC);

-- New index on existing SENDER_STAGE table
CREATE INDEX idx_sender_stage_request_status ON SENDER_STAGE(request_id, status);
```


## Correctness Properties

A property is a characteristic or behavior that should hold true across all valid executions of a system—essentially, a formal statement about what the system should do. Properties serve as the bridge between human-readable specifications and machine-verifiable correctness guarantees.

### Property 1: Session Creation Precedes File Staging

*For any* file staging operation, a staging_session record with matching UUID must exist before any SENDER_STAGE records are created with that request_id.

**Validates: Requirements 1.1, 1.3**

### Property 2: Session Metadata Completeness

*For any* created staging_session, all required fields (username, site, sender_id, sender_name, environment, created_at) must be non-null and valid.

**Validates: Requirements 1.2**

### Property 3: File-Session Association Integrity

*For any* SENDER_STAGE record with a non-null request_id, the request_id must reference an existing staging_session.id.

**Validates: Requirements 1.3**

### Property 4: Session Status Transitions

*For any* staging_session, status transitions must follow the valid lifecycle: STAGING → DISPATCHING → MONITORING → (COMPLETED | PARTIALLY_FAILED | CANCELLED).

**Validates: Requirements 15.1, 15.2, 15.3, 15.4, 15.5**

### Property 5: Terminal Status Determination

*For any* session where all files have reached terminal status (DONE or FAILED), the session status must be COMPLETED if all files are DONE, or PARTIALLY_FAILED if any files are FAILED.

**Validates: Requirements 1.4**

### Property 6: Cancellation Affects Only NEW Files

*For any* session cancellation operation, only files with status NEW must be marked CANCELLED, and files with status ENQUEUED, DONE, or FAILED must remain unchanged.

**Validates: Requirements 8.1, 8.2**

### Property 7: SSE Connection Establishment

*For any* valid session ID, connecting to the monitor endpoint must establish an SSE connection and deliver an initial STATS event containing current session state.

**Validates: Requirements 2.1**

### Property 8: File Status Change Broadcasting

*For any* file status change in a session with active SSE connections, a FILE_UPDATE event must be broadcast to all connected clients within 1 second.

**Validates: Requirements 2.2, 4.4**

### Property 9: Session Counter Broadcasting

*For any* change to session counters (files_staged, files_enqueued, files_done, files_failed), a STATS event must be broadcast to all connected clients.

**Validates: Requirements 2.3**

### Property 10: Session Completion Broadcasting

*For any* session that transitions to a terminal status (COMPLETED, PARTIALLY_FAILED, CANCELLED), a SESSION_STATUS event must be broadcast before closing SSE connections.

**Validates: Requirements 2.4, 4.5, 8.4**

### Property 11: Heartbeat Timing

*For any* active SSE connection, HEARTBEAT events must be delivered at intervals of 15 seconds ± 2 seconds.

**Validates: Requirements 2.5**

### Property 12: Multi-Client Event Delivery

*For any* session with N active SSE connections, broadcasting an event must deliver that event to all N connections.

**Validates: Requirements 2.6, 14.2**

### Property 13: External Queue Status Detection

*For any* file with status ENQUEUED, querying external status must check DTP_SENDER_QUEUE_ITEM and return "Queued" if the record exists, or "Processing" if it does not.

**Validates: Requirements 3.1, 3.2, 3.3**

### Property 14: Completion Detection Latency

*For any* file that disappears from DTP_SENDER_QUEUE_ITEM, the SENDER_STAGE status must transition to DONE within 30 seconds.

**Validates: Requirements 3.4, 4.3**

### Property 15: On-Demand Refresh Immediacy

*For any* manual refresh request, external queue status must be checked and file statuses updated within 5 seconds, followed by a STATS event broadcast.

**Validates: Requirements 3.5, 9.1, 9.2, 9.3, 9.4, 9.5**

### Property 16: Pagination Completeness

*For any* session with more than 100 files, paginating through all pages must return every file exactly once.

**Validates: Requirements 4.2, 7.1**

### Property 17: Lot/Wafer Aggregation Accuracy

*For any* session, the sum of completed and failed wafers across all lots must equal the total number of unique (lot, wafer) combinations with terminal file statuses.

**Validates: Requirements 5.1, 5.2, 5.3**

### Property 18: Session History Ordering

*For any* username, requesting session history must return sessions ordered by created_at descending (most recent first).

**Validates: Requirements 6.1**

### Property 19: Session Detail Completeness

*For any* session detail response, all required fields (id, site, sender, totalFiles, progress, status, timestamps) must be present and non-null.

**Validates: Requirements 6.2, 6.3**

### Property 20: Completed Session SSE Behavior

*For any* session with status COMPLETED, PARTIALLY_FAILED, or CANCELLED, attempting to connect SSE must send a SESSION_STATUS event and immediately close the connection.

**Validates: Requirements 6.4, 10.4**

### Property 21: Active Session SSE Reconnection

*For any* session with status STAGING, DISPATCHING, or MONITORING, viewing from history must establish an SSE connection for live updates.

**Validates: Requirements 6.5**

### Property 22: Status Filter Accuracy

*For any* file list request with a status filter, all returned files must have the specified status, and no files with that status must be omitted.

**Validates: Requirements 7.2**

### Property 23: Text Search Coverage

*For any* file list request with a search term, all returned files must contain the search term in lot, wafer, or filename fields.

**Validates: Requirements 7.3**

### Property 24: CSV Export Completeness

*For any* session, CSV export must contain all files regardless of pagination, with all required columns present.

**Validates: Requirements 7.5, 17.1, 17.2**

### Property 25: CSV Special Character Escaping

*For any* file with commas or quotes in lot, wafer, or filename fields, the CSV export must properly escape these characters according to RFC 4180.

**Validates: Requirements 17.3**

### Property 26: Session Cancellation Status Update

*For any* session cancellation, the session status must transition to CANCELLED and a SESSION_STATUS event must be broadcast.

**Validates: Requirements 8.3, 8.4**

### Property 27: Cancelled Session Monitoring Continuation

*For any* cancelled session with files in ENQUEUED status, the queue monitor must continue processing those files until they reach terminal status.

**Validates: Requirements 8.5**

### Property 28: Counter Accuracy via Recount

*For any* session, querying session counters must recount from SENDER_STAGE WHERE request_id matches session ID, ensuring counters match actual database state.

**Validates: Requirements 11.1**

### Property 29: Atomic Counter Updates

*For any* file status change, the corresponding session counter update must be atomic (no partial updates visible to concurrent queries).

**Validates: Requirements 11.2**

### Property 30: Progress Calculation Correctness

*For any* session, the progress percentage must equal (files_done + files_failed) / total_files * 100.

**Validates: Requirements 11.3**

### Property 31: Initial Counter State

*For any* newly created session, all counters (total_files, files_staged, files_enqueued, files_done, files_failed) must be initialized to zero.

**Validates: Requirements 11.4**

### Property 32: Counter Increment on Staging

*For any* file staging operation, the session's total_files and files_staged counters must increment by the number of files staged.

**Validates: Requirements 11.5**

### Property 33: Status Display Mapping

*For any* file status, the frontend must display the correct mapped status: NEW → "Staged", ENQUEUED (in queue) → "Queued", ENQUEUED (not in queue) → "Processing", DONE → "Completed", FAILED → "Failed", CANCELLED → "Cancelled".

**Validates: Requirements 12.1, 12.2, 12.3, 12.4, 12.5**

### Property 34: Emitter Isolation

*For any* two distinct sessions, their SSE emitter sets must be separate, and events broadcast to one session must not be delivered to the other session's emitters.

**Validates: Requirements 14.1, 14.2**

### Property 35: Session Completion Emitter Cleanup

*For any* session that completes, only that session's emitters must be closed, and emitters for other active sessions must remain open.

**Validates: Requirements 14.3**

### Property 36: Heartbeat Broadcast to All Sessions

*For any* heartbeat cycle, all sessions with active emitters must receive a HEARTBEAT event.

**Validates: Requirements 14.4**

### Property 37: Emitter Failure Isolation

*For any* emitter that fails, only that emitter must be removed from its session's emitter set, and other emitters in the same session must remain active.

**Validates: Requirements 14.5, 20.1**

### Property 38: Activity Event Creation on Status Change

*For any* file status change, an activity event must be created with timestamp, file identifier, old status, and new status.

**Validates: Requirements 16.1**

### Property 39: Activity Event Ordering

*For any* activity feed, events must be ordered by timestamp descending (most recent first).

**Validates: Requirements 16.3**

### Property 40: Activity Feed Size Limit

*For any* activity feed with more than 100 events, only the 100 most recent events must be retained.

**Validates: Requirements 16.4**

### Property 41: Throughput Calculation Window

*For any* session, throughput must be calculated as files completed per minute over the last 5 minutes.

**Validates: Requirements 18.1**

### Property 42: ETA Calculation Formula

*For any* session with non-zero throughput, ETA must equal remaining_files / throughput.

**Validates: Requirements 18.2**

### Property 43: Zero Throughput Display

*For any* session with zero throughput, the ETA display must show "Calculating..." instead of a numeric value.

**Validates: Requirements 18.3**

### Property 44: Legacy File Graceful Degradation

*For any* SENDER_STAGE record with a request_id that has no matching staging_session, the file data must still be displayable with "Unknown Session" shown for session metadata.

**Validates: Requirements 19.1, 19.3**

### Property 45: Legacy File Status Updates

*For any* file without a matching session, status transitions (NEW → ENQUEUED → DONE) must still function normally.

**Validates: Requirements 19.4**

### Property 46: SSE Reconnection Exponential Backoff

*For any* SSE connection drop, reconnection attempts must use exponential backoff with delays of 1s, 2s, 4s, 8s, capped at 30s.

**Validates: Requirements 10.1**

### Property 47: Reconnection Snapshot Recovery

*For any* SSE reconnection, a full session snapshot must be fetched via HTTP before re-establishing the SSE connection.

**Validates: Requirements 10.2**

### Property 48: Terminal Session Graceful Closure

*For any* session reaching terminal status, final events (STATS, SESSION_STATUS) must be sent before closing the SSE connection.

**Validates: Requirements 10.3**

### Property 49: SSE Fallback to Polling

*For any* client where SSE connection fails after maximum retry attempts, the system must fall back to HTTP polling at 3-second intervals.

**Validates: Requirements 10.5**

### Property 50: Error Resilience - Query Retry

*For any* external queue query failure, the error must be logged and the query retried on the next monitor cycle without crashing the service.

**Validates: Requirements 20.2**

### Property 51: Error Resilience - Session Isolation

*For any* session counter update failure, the error must be logged and other sessions must continue processing normally.

**Validates: Requirements 20.3**

### Property 52: Database Reconnection Backoff

*For any* database connection loss, reconnection attempts must use exponential backoff.

**Validates: Requirements 20.4**

### Property 53: Frontend Reconnection Indicator

*For any* SSE connection loss, the frontend must display a "Reconnecting..." indicator and attempt automatic recovery.

**Validates: Requirements 20.5**


## Error Handling

### Backend Error Scenarios

| Scenario | Handling Strategy |
|----------|------------------|
| Session creation fails (DB error) | Return 500 with error message, log exception, do not proceed with staging |
| SSE emitter fails during send | Log error, remove emitter from set, continue broadcasting to other emitters |
| External queue query fails | Log error, skip this monitor cycle, retry on next cycle (30s later) |
| Session counter update fails | Log error, continue processing other sessions, counters will be recalculated on next query |
| Database connection lost | Attempt reconnection with exponential backoff (1s, 2s, 4s, 8s, 16s, 32s), fail requests during outage |
| Invalid session ID in request | Return 404 with "Session not found" message |
| Unauthorized session access | Return 403 if username doesn't match session owner (except SUPER_ADMIN) |
| CSV export memory exhaustion | Use streaming response body to write CSV incrementally, never load all files into memory |
| Lot/wafer aggregation timeout | Set query timeout to 30s, return 504 if exceeded, suggest filtering by status |

### Frontend Error Scenarios

| Scenario | Handling Strategy |
|----------|------------------|
| SSE connection fails | Display "Reconnecting..." indicator, attempt reconnection with exponential backoff, fall back to polling after 5 failed attempts |
| HTTP request fails (4xx/5xx) | Display toast error with message, log to console, allow user to retry |
| Session creation fails | Show error dialog, prevent navigation to Step 3, allow user to retry |
| Invalid session ID in URL | Redirect to My Sessions page with error toast |
| Network offline | Display offline indicator, queue operations for retry when online |
| CSV export fails | Show error toast, suggest reducing file count by filtering |
| Virtual scroll performance degradation | Limit file list to 1000 items per page, suggest using filters to narrow results |

### SSE Error Recovery

```typescript
// Frontend reconnection logic
private reconnectSSE(sessionId: string): void {
  if (this.isComplete()) return;
  
  this.isReconnecting.set(true);
  const delay = Math.min(
    1000 * Math.pow(2, this.reconnectAttempts),
    this.maxReconnectDelay
  );
  
  setTimeout(() => {
    this.reconnectAttempts++;
    
    // Fetch snapshot to recover missed events
    this.http.get<StagingSessionDetail>(`/api/stage/sessions/${sessionId}`)
      .subscribe({
        next: (session) => {
          this.currentSession.set(session);
          this.connectSSE(sessionId);
        },
        error: (err) => {
          if (this.reconnectAttempts >= 5) {
            // Fall back to polling
            this.startPolling(sessionId);
          } else {
            this.reconnectSSE(sessionId);
          }
        }
      });
  }, delay);
}

private startPolling(sessionId: string): void {
  this.isReconnecting.set(false);
  this.pollingInterval = setInterval(() => {
    this.http.get<StagingSessionDetail>(`/api/stage/sessions/${sessionId}`)
      .subscribe(session => {
        this.currentSession.set(session);
        if (this.isComplete()) {
          clearInterval(this.pollingInterval);
        }
      });
  }, 3000);
}
```

### Database Transaction Boundaries

- **Session creation**: Single transaction (INSERT staging_session)
- **File staging**: Batch transaction (INSERT multiple SENDER_STAGE rows with same request_id)
- **Counter updates**: Single transaction per session (UPDATE staging_session SET counters WHERE id = ?)
- **Status transitions**: Single transaction per file (UPDATE SENDER_STAGE SET status WHERE id = ?)
- **Cancellation**: Single transaction (UPDATE SENDER_STAGE SET status = 'CANCELLED' WHERE request_id = ? AND status = 'NEW')

### Concurrency Considerations

- **Multiple users staging to same sender**: Each gets unique session ID, no conflicts
- **Multiple browser tabs for same session**: Each gets separate SSE emitter, all receive same events
- **Concurrent counter updates**: Use database-level locking (SELECT FOR UPDATE) or optimistic locking with version field
- **Queue monitor processing same session**: Use claim-based pattern (UPDATE ... WHERE status = 'ENQUEUED' AND claimed_at IS NULL)


## Testing Strategy

### Dual Testing Approach

This system requires both unit tests and property-based tests to ensure comprehensive correctness:

- **Unit tests**: Verify specific examples, edge cases, and error conditions
- **Property-based tests**: Verify universal properties across all inputs
- Both are complementary and necessary for comprehensive coverage

### Unit Testing Focus

Unit tests should focus on:
- Specific examples that demonstrate correct behavior (e.g., session creation with valid inputs)
- Integration points between components (e.g., StageSessionService → StageMonitorService)
- Edge cases and error conditions (e.g., empty session, all files failed, network timeout)
- SSE emitter lifecycle (connection, disconnection, error handling)

Avoid writing too many unit tests for scenarios that property-based tests handle better (e.g., testing all possible status combinations).

### Property-Based Testing Configuration

**Framework Selection**:
- **Backend (Java)**: Use **jqwik** (https://jqwik.net/) for property-based testing
- **Frontend (TypeScript)**: Use **fast-check** (https://github.com/dubzzz/fast-check) for property-based testing

**Test Configuration**:
- Minimum 100 iterations per property test (due to randomization)
- Each property test must reference its design document property
- Tag format: `@Tag("Feature: staging-monitoring-system, Property N: [property text]")`

**Example Property Test (Backend)**:

```java
@Property
@Tag("Feature: staging-monitoring-system, Property 1: Session Creation Precedes File Staging")
void sessionMustExistBeforeFileStaging(@ForAll("validSessions") StagingSession session,
                                       @ForAll("validFiles") List<StageFile> files) {
    // Arrange: Create session
    String sessionId = stageSessionService.createSession(
        session.username(), session.site(), session.senderId(), 
        session.senderName(), session.environment()
    );
    
    // Act: Stage files with session ID
    files.forEach(file -> {
        stageFile(file, sessionId);
    });
    
    // Assert: All files have request_id matching session
    List<StageFile> stagedFiles = getStagedFiles(sessionId);
    assertThat(stagedFiles).allMatch(f -> f.requestId().equals(sessionId));
    
    // Assert: Session exists
    StagingSessionDetail retrievedSession = stageSessionService.getSession(sessionId);
    assertThat(retrievedSession).isNotNull();
    assertThat(retrievedSession.id()).isEqualTo(sessionId);
}

@Provide
Arbitrary<StagingSession> validSessions() {
    return Combinators.combine(
        Arbitraries.strings().alpha().ofLength(8),  // username
        Arbitraries.of("BE2", "CEBU", "MYD", "SPIL"),  // site
        Arbitraries.integers().between(1, 9999),  // senderId
        Arbitraries.strings().alpha().ofLength(10),  // senderName
        Arbitraries.of("PROD", "QA")  // environment
    ).as(StagingSession::new);
}

@Provide
Arbitrary<List<StageFile>> validFiles() {
    return Arbitraries.integers().between(1, 100).flatMap(count ->
        Arbitraries.of(
            Arbitraries.strings().numeric().ofLength(10),  // metadataId
            Arbitraries.strings().numeric().ofLength(10),  // dataId
            Arbitraries.strings().alpha().ofLength(6),  // lot
            Arbitraries.integers().between(1, 25).map(String::valueOf),  // wafer
            Arbitraries.strings().alpha().ofLength(20)  // filename
        ).list().ofSize(count)
    );
}
```

**Example Property Test (Frontend)**:

```typescript
import fc from 'fast-check';

describe('StagingSessionService', () => {
  // Feature: staging-monitoring-system, Property 33: Status Display Mapping
  it('should map all file statuses to correct display statuses', () => {
    fc.assert(
      fc.property(
        fc.constantFrom('NEW', 'ENQUEUED', 'DONE', 'FAILED', 'CANCELLED'),
        fc.boolean(), // inExternalQueue
        (status, inQueue) => {
          const displayStatus = mapFileStatus(status, inQueue);
          
          if (status === 'NEW') {
            expect(displayStatus).toBe('Staged');
          } else if (status === 'ENQUEUED' && inQueue) {
            expect(displayStatus).toBe('Queued');
          } else if (status === 'ENQUEUED' && !inQueue) {
            expect(displayStatus).toBe('Processing');
          } else if (status === 'DONE') {
            expect(displayStatus).toBe('Completed');
          } else if (status === 'FAILED') {
            expect(displayStatus).toBe('Failed');
          } else if (status === 'CANCELLED') {
            expect(displayStatus).toBe('Cancelled');
          }
        }
      ),
      { numRuns: 100 }
    );
  });
  
  // Feature: staging-monitoring-system, Property 30: Progress Calculation Correctness
  it('should calculate progress as (done + failed) / total * 100', () => {
    fc.assert(
      fc.property(
        fc.integer({ min: 1, max: 10000 }), // totalFiles
        fc.integer({ min: 0, max: 10000 }), // filesDone
        fc.integer({ min: 0, max: 10000 }), // filesFailed
        (total, done, failed) => {
          // Ensure done + failed <= total
          const actualDone = Math.min(done, total);
          const actualFailed = Math.min(failed, total - actualDone);
          
          const session: StagingSessionDetail = {
            totalFiles: total,
            filesDone: actualDone,
            filesFailed: actualFailed,
            // ... other fields
          };
          
          const expectedProgress = ((actualDone + actualFailed) / total) * 100;
          const actualProgress = calculateProgress(session);
          
          expect(actualProgress).toBeCloseTo(expectedProgress, 2);
        }
      ),
      { numRuns: 100 }
    );
  });
});
```

### Integration Testing

**Backend Integration Tests**:
- Test full flow: create session → stage files → dispatch → monitor → complete
- Use H2 in-memory database for fast test execution
- Mock external Oracle connections (DTP_SENDER_QUEUE_ITEM)
- Test SSE event delivery with TestSseEmitter

**Frontend Integration Tests**:
- Test stepper flow: Step 1 → Step 2 → Stage → Step 3 monitoring
- Mock HTTP responses with HttpTestingController
- Test SSE reconnection with mock EventSource
- Test My Sessions page with mock session data

### Performance Testing

**Load Testing Scenarios**:
- 1,000 files in single session (pagination, virtual scroll)
- 10,000 files in single session (CSV export streaming)
- 100 concurrent sessions (SSE emitter management)
- 1,000 concurrent SSE connections (heartbeat broadcast)

**Performance Targets**:
- Session creation: < 100ms
- File staging (100 files): < 2s
- SSE event delivery: < 100ms
- Counter recalculation: < 500ms
- Lot/wafer aggregation: < 1s
- CSV export (10,000 files): < 30s

### Test Coverage Goals

- **Backend**: 80% line coverage, 90% branch coverage for core services
- **Frontend**: 70% line coverage, 80% branch coverage for services and components
- **Property tests**: All 53 correctness properties must have corresponding property-based tests

