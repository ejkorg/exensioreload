# Dashboard System — Design Document

## 1. Current State Analysis

### 1.1 What the Dashboard Does Today

The dashboard provides an aggregated operational view of all staging activity across 20+ manufacturing sites. It shows:

- **Global metric cards**: Active Senders, Backlog, Ready, Enqueued, Completed
- **Per-site panels**: Each site shows sender count, backlog badge, top 4 senders
- **Auto-polling**: Refreshes every 10 seconds via `timer(10000, 10000)`

### 1.2 Backend Data Path

```
DashboardController.snapshot()
    ├─ resolveStatuses(auth)
    │   ├─ Admin → refDbService.fetchStatuses(null)         // ALL records in SENDER_STAGE
    │   └─ User  → refDbService.fetchStatusesForUser(...)   // scoped to user
    │
    ├─ fetchStatuses() SQL:
    │   SELECT site, sender_id, MAX(sender_name), COUNT(*),
    │     SUM(CASE WHEN status='NEW' THEN 1 ELSE 0 END),
    │     SUM(CASE WHEN status='ENQUEUED' THEN 1 ELSE 0 END),
    │     SUM(CASE WHEN status='FAILED' THEN 1 ELSE 0 END),
    │     SUM(CASE WHEN status='DONE' THEN 1 ELSE 0 END)
    │   FROM SENDER_STAGE GROUP BY site, sender_id
    │
    ├─ fetchUserBreakdown() — sub-query per site+sender
    │   GROUP BY site, sender_id, LOWER(COALESCE(last_requested_by, staged_by))
    │
    └─ Assembles: DashboardSnapshot { generatedAt, global metrics, List<SiteSnapshot> }
```

### 1.3 Available but UNUSED Backend Endpoints

The backend already has well-designed drill-down endpoints that the **frontend doesn't use at all**:

| Endpoint | What it provides | Frontend usage |
|----------|-----------------|----------------|
| `GET /snapshot` | Global + site + sender metrics | **Used** (only endpoint consumed) |
| `GET /sites/{site}/senders/{senderId}/records` (JSON) | Paginated records for sender | **NOT used** |
| `GET /sites/{site}/senders/{senderId}/records` (CSV) | CSV export with GZIP | **NOT used** |
| `GET /sites/{site}/senders/{senderId}/lot-breakdown` | Lot/wafer aggregation | **NOT used** |
| `GET /sites/{site}/senders/{senderId}/date-breakdown` | Time bucket aggregation | **NOT used** |

### 1.4 Frontend Implementation Summary

- **70 lines total** in `dashboard.component.ts` — very thin
- **1 signal**: `snapshot: signal<DashboardSnapshot | null>(null)`
- **1 API call**: `getDashboardSnapshot()` — that's the entire data source
- **No drill-down**: "View Detailed Metrics" button exists in HTML but has **no click handler** and does nothing
- **No sender selection**: Site panels show 4 senders, rest hidden behind "+ N more" with no way to see them
- **No records view**: Cannot see individual staged records from the dashboard
- **No lot/wafer breakdown**: Backend has the endpoint, frontend doesn't call it
- **No date breakdown**: Backend has the endpoint, frontend doesn't call it
- **No filtering**: No status filter, no date range, no search
- **No charts/graphs**: echarts and apexcharts are in `package.json` but never imported in the dashboard

---

## 2. Identified Gaps

| # | Gap | Impact | Severity |
|---|-----|--------|----------|
| **D1** | Frontend `DashboardMetricTotals` is missing `total` and `failed` fields | Backend sends 8 fields, frontend type declares 5 → no `failed` count shown, no `total` count shown | High |
| **D2** | "View Detailed Metrics" button is dead | No click handler → users see a button that does nothing | High |
| **D3** | No sender drill-down at all | Cannot see records for a specific site/sender — defeats the purpose of the dashboard | Critical |
| **D4** | Lot/wafer breakdown endpoint unused | Backend `GET /lot-breakdown` works but frontend never calls it | High |
| **D5** | Date breakdown endpoint unused | Backend `GET /date-breakdown` works but frontend never calls it | High |
| **D6** | No error/failed visibility | No "Failed" metric card, no filter to see failed records | High |
| **D7** | Only 4 senders shown per site, rest hidden with no expand | Sites with 10+ senders have most hidden behind "+ N more" text with no way to view them | Medium |
| **D8** | 10s polling with no change detection | Every 10s, fetches full snapshot even if nothing changed → wasteful network + DB queries on SENDER_STAGE across all sites | Medium |
| **D9** | No charts/visualizations | Chart libraries installed but unused; no trend data, no time-series | Medium |
| **D10** | `getStageRecords()` calls dead endpoint | `BackendService.getStageRecords()` calls `/staging/history` which doesn't exist as a backend endpoint | Critical |
| **D11** | Active sessions endpoint is a hack | `StageController.getActiveStagingSessions()` uses `fetchStatuses(null)` and creates fake sessions with composite IDs like `"site:senderId"` — not real sessions | High |
| **D12** | Session details uses Map<String, Object> | `SessionDetailsResponse` wraps `Map<String, Object>` instead of a typed DTO — fragile untyped API | Medium |
| **D13** | Debug endpoints exposed in production | `/debug-auth` and `/debug-users` are available to all authenticated users | Low |
| **D14** | No real-time updates | Dashboard only polls; when staging monitoring redesign adds SSE, dashboard should also benefit | Medium |
| **D15** | No export capability from dashboard | Cannot export all records across sites; only per-sender CSV exists in backend but UI doesn't expose it | Low |

### 2.1 Impact of Staging Monitoring Redesign

When the `staging_session` table and `StageSessionService` from the monitoring redesign are implemented, the dashboard is **directly affected**:

| Monitoring Change | Dashboard Impact |
|-------------------|-----------------|
| New `staging_session` table | Dashboard can show recent sessions, not just raw SENDER_STAGE aggregates |
| `requestId` properly links to sessions | Active sessions endpoint can return real sessions instead of fake composite IDs |
| `StageSessionService.getUserSessions()` | My Sessions page becomes functional, overlaps with dashboard drill-down |
| SSE infrastructure enhanced | Dashboard can optionally subscribe to SSE for live updates instead of polling |
| Status standardized to DONE (not COMPLETED) | Dashboard status display must align |
| Session status lifecycle (STAGING→MONITORING→COMPLETED) | Dashboard can show session-level progress, not just file-level counts |

---

## 3. Solution Architecture

### 3.1 Dashboard Levels

The redesigned dashboard operates at **three levels of detail**, each progressively deeper:

```
Level 0: Global Overview (current snapshot endpoint, enhanced)
┌─────────────────────────────────────────────────────────────────┐
│  Metric Cards: Total | Staged | Enqueued | Completed | Failed   │
│  Active Sessions | Active Senders | Active Users                │
│                                                                  │
│  Trend Sparkline (24h) — files completed per hour               │
└─────────────────────────────────────────────────────────────────┘

Level 1: Site/Sender Grid (existing, enhanced)
┌──────────────────────────────────────────────────────────────────┐
│  Site Cards — expandable                                         │
│  ┌──────────────────────────────────────────────────┐            │
│  │ BE2          backlog: 450                         │ ← click   │
│  │ Sender 1234: ████████░░ 80%   50 ready 10 enq    │            │
│  │ Sender 5678: ██████████ 100%  0 ready             │            │
│  │ Sender 9012: ████░░░░░░ 40%   30 ready 20 enq    │            │
│  │ [Show all 8 senders]                              │            │
│  └──────────────────────────────────────────────────┘            │
│                                                                   │
│  + Recent Sessions (from staging_session table)                  │
│  ┌─────────────────────────────────────────────────────────────┐ │
│  │ req-abc... │ BE2 │ 1234 │ 500 files │ ████░ 80% │ MONITORING│ │
│  │ req-def... │ CEBU│ 5678 │ 200 files │ █████ 100%│ COMPLETED │ │
│  └─────────────────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────────────┘

Level 2: Sender Detail (NEW — replaces dead "View Detailed Metrics" button)
┌──────────────────────────────────────────────────────────────────┐
│  Site: BE2 → Sender: 1234 (Auto Sender)                         │
│                                                                   │
│  ┌─ Summary Metrics ──────────────────────────────────────────┐  │
│  │ Total: 500 │ Ready: 50 │ Enqueued: 100 │ Done: 340 │ Fail:10│ │
│  └────────────────────────────────────────────────────────────┘  │
│                                                                   │
│  ┌─ Tabs ─────────────────────────────────────────────────────┐  │
│  │ [Records] [Lot Breakdown] [Date Breakdown] [Sessions]       │  │
│  │                                                              │  │
│  │ Records Tab:                                                 │  │
│  │ ┌─────────────────────────────────────────────────────────┐  │  │
│  │ │ Filters: [Status ▼] [Search: ___________] [Export CSV]  │  │  │
│  │ │                                                          │  │  │
│  │ │ ID │ Lot │ Wafer │ File │ Status │ Staged By │ Updated   │  │  │
│  │ │ .. │ ... │ ..... │ .... │ ...... │ ......... │ .......   │  │  │
│  │ │ Pagination: < 1 2 3 ... > │ Total: 500                  │  │  │
│  │ └─────────────────────────────────────────────────────────┘  │  │
│  │                                                              │  │
│  │ Lot Breakdown Tab:                                           │  │
│  │ ┌─────────────────────────────────────────────────────────┐  │  │
│  │ │ ▼ Lot ABC123: ████████░░ (8/10 wafers)                  │  │  │
│  │ │   Wafer 01: DONE │ Wafer 02: DONE │ Wafer 03: ENQUEUED │  │  │
│  │ │ ▶ Lot DEF456: ██████████ (5/5 wafers) ✓                │  │  │
│  │ └─────────────────────────────────────────────────────────┘  │  │
│  │                                                              │  │
│  │ Date Breakdown Tab:                                          │  │
│  │ ┌─────────────────────────────────────────────────────────┐  │  │
│  │ │ [Bar chart — files per day by status over last 30 days] │  │  │
│  │ └─────────────────────────────────────────────────────────┘  │  │
│  └──────────────────────────────────────────────────────────────┘ │
└───────────────────────────────────────────────────────────────────┘
```

### 3.2 How Levels Connect

```
Dashboard page (Level 0 + Level 1)
  │
  ├─ Click site card → expands to show ALL senders (fixes D7)
  │
  ├─ Click sender row → navigates to Sender Detail (Level 2)
  │   Route: /exensioreload/dashboard/sites/:site/senders/:senderId
  │   OR: slide-over panel (stays on dashboard)
  │
  └─ Click session row → navigates to Session Monitor
      Route: /my-sessions/:sessionId (reuses monitoring redesign)
```

---

## 4. Backend Changes

### 4.1 Enhanced `/snapshot` Response

The existing snapshot endpoint already works well. Minor additions:

```java
// ADD to DashboardSnapshot:
public record DashboardSnapshot(
    Instant generatedAt,
    DashboardMetricTotals global,
    List<DashboardSiteSnapshot> sites,
    // NEW fields:
    int activeSessions,          // COUNT(*) FROM staging_session WHERE status NOT IN ('COMPLETED','CANCELLED')
    Instant lastActivityAt       // MAX(updated_at) FROM SENDER_STAGE — for conditional polling
) {}
```

The `lastActivityAt` field enables **conditional polling**: frontend can send `If-Modified-Since` or compare timestamps to skip re-rendering if nothing changed (fixes D8).

### 4.2 New Endpoint: Recent Sessions

```
GET /api/dashboard/recent-sessions?limit=10
```

Returns recent `staging_session` rows (from the monitoring redesign). This gives the dashboard a session-aware view.

```java
// Uses StageSessionService from monitoring redesign
List<StagingSessionSummary> sessions = stageSessionService.getRecentSessions(limit);
```

**Depends on**: Monitoring redesign Phase 1 (`staging_session` table + `StageSessionService`).

### 4.3 Existing Endpoints — No Changes Needed

These backend endpoints are already well-designed and just need the frontend to call them:

| Endpoint | Status |
|----------|--------|
| `GET /sites/{site}/senders/{senderId}/records` (JSON) | Ready — has pagination, status filter, search |
| `GET /sites/{site}/senders/{senderId}/records` (CSV) | Ready — has GZIP streaming |
| `GET /sites/{site}/senders/{senderId}/lot-breakdown` | Ready — has lot/wafer/file aggregation |
| `GET /sites/{site}/senders/{senderId}/date-breakdown` | Ready — has time bucket aggregation |

### 4.4 Remove Debug Endpoints

Remove or gate behind `SUPER_ADMIN`:
- `GET /api/dashboard/debug-auth` → move to `/api/diagnostic/auth` (already exists there)
- `GET /api/dashboard/debug-users` → remove (duplicate of `/api/diagnostic/auth`)

### 4.5 Cleanup: Active Sessions Endpoint

The current `GET /api/stage/sessions/active` is a hack that creates fake sessions from `fetchStatuses()`. Once `staging_session` table exists:

**Replace with:**
```java
@GetMapping("/sessions/active")
public ResponseEntity<List<StagingSessionSummary>> getActiveStagingSessions() {
    String username = getCurrentUsername();
    boolean isAdmin = isCurrentUserAdmin();
    List<StagingSessionSummary> sessions = isAdmin
        ? stageSessionService.getActiveSessions()
        : stageSessionService.getUserActiveSessions(username);
    return ResponseEntity.ok(sessions);
}
```

### 4.6 Cleanup: Session Details Endpoint

Replace the `Map<String, Object>` hack:

**Current (`SessionDetailsResponse`):**
```java
public record SessionDetailsResponse(
    Map<String, Object> session,        // ← untyped
    List<SessionActivity> recentActivity,
    Map<String, Object> performance     // ← untyped
) {}
```

**New (from monitoring redesign):**
```java
public record SessionDetailsResponse(
    StagingSessionDetail session,        // ← typed
    List<SessionActivity> recentActivity,
    SessionPerformance performance       // ← typed
) {}
```

---

## 5. Frontend Changes

### 5.1 Fix `DashboardMetricTotals` Interface

```typescript
// BEFORE (missing total and failed):
export interface DashboardMetricTotals {
    ready: number;
    enqueued: number;
    completed: number;
    backlog: number;
    activeSenders: number;
    activeUsers?: number;
}

// AFTER (matches backend's 8 fields):
export interface DashboardMetricTotals {
    total: number;          // ADD
    ready: number;
    enqueued: number;
    failed: number;         // ADD
    completed: number;
    backlog: number;
    activeSenders: number;
    activeUsers?: number;
}
```

### 5.2 Enhanced Metric Cards

```typescript
get metricCards() {
    const s = this.snapshot();
    if (!s) return [];
    return [
        { label: 'Total Staged',    value: s.global.total,         icon: 'inventory_2',      color: 'slate'   },
        { label: 'Ready',           value: s.global.ready,         icon: 'play_arrow',       color: 'blue'    },
        { label: 'Enqueued',        value: s.global.enqueued,      icon: 'schedule',         color: 'indigo'  },
        { label: 'Completed',       value: s.global.completed,     icon: 'check_circle',     color: 'emerald' },
        { label: 'Failed',          value: s.global.failed,        icon: 'error',            color: 'red', alert: s.global.failed > 0 },
        { label: 'Active Senders',  value: s.global.activeSenders, icon: 'groups',           color: 'purple'  },
    ];
}
```

### 5.3 New `BackendService` Methods

```typescript
// Dashboard drill-down (calling existing but unused backend endpoints)
getSenderRecords(site: string, senderId: number, params: {
    status?: string; q?: string; page?: number; size?: number;
}): Observable<StageRecordPage> {
    return this.http.get<StageRecordPage>(
        `${this.apiUrl}/dashboard/sites/${site}/senders/${senderId}/records`,
        { params: this.toParams(params) }
    );
}

getSenderRecordsCsv(site: string, senderId: number, params?: any): Observable<Blob> {
    return this.http.get(
        `${this.apiUrl}/dashboard/sites/${site}/senders/${senderId}/records`,
        { params: this.toParams(params || {}), responseType: 'blob',
          headers: { Accept: 'text/csv' } }
    );
}

getLotBreakdown(site: string, senderId: number, params?: {
    startDate?: string; endDate?: string; q?: string; limit?: number;
}): Observable<LotBreakdown[]> {
    return this.http.get<LotBreakdown[]>(
        `${this.apiUrl}/dashboard/sites/${site}/senders/${senderId}/lot-breakdown`,
        { params: this.toParams(params || {}) }
    );
}

getDateBreakdown(site: string, senderId: number, params?: {
    startDate?: string; endDate?: string; limit?: number;
}): Observable<DateBucket[]> {
    return this.http.get<DateBucket[]>(
        `${this.apiUrl}/dashboard/sites/${site}/senders/${senderId}/date-breakdown`,
        { params: this.toParams(params || {}) }
    );
}

// New TypeScript interfaces
export interface LotBreakdown {
    lot: string;
    totals: BucketTotals;
    wafers: WaferBreakdown[];
}

export interface WaferBreakdown {
    wafer: string;
    totals: BucketTotals;
    filename: string;
}

export interface DateBucket {
    bucketStart: string;
    label: string;
    totals: BucketTotals;
}

export interface BucketTotals {
    total: number;
    ready: number;
    enqueued: number;
    failed: number;
    completed: number;
    backlog: number;
}
```

### 5.4 Expandable Site Cards

Replace the fixed 4-sender view with a collapsible panel:

```typescript
// New signals in DashboardComponent
expandedSites = signal<Set<string>>(new Set());

toggleSite(site: string) {
    const current = new Set(this.expandedSites());
    current.has(site) ? current.delete(site) : current.add(site);
    this.expandedSites.set(current);
}

isSiteExpanded(site: string): boolean {
    return this.expandedSites().has(site);
}
```

```html
<!-- Sender list in site card -->
<div *ngFor="let sender of (isSiteExpanded(site.site)
    ? site.senders
    : site.senders.slice(0, 4))" class="sender-row"
    (click)="openSenderDetail(site.site, sender)">
    <!-- ... sender row content ... -->
</div>
<button *ngIf="site.senders.length > 4" 
    (click)="toggleSite(site.site)" class="expand-btn">
    {{ isSiteExpanded(site.site) ? 'Show less' : '+ ' + (site.senders.length - 4) + ' more senders' }}
</button>
```

### 5.5 New Component: Sender Detail Panel

```
File: new_frontend/src/app/dashboard/sender-detail-panel.component.ts
```

This is the Level 2 drill-down. Opens as a slide-over or modal when clicking a sender row.

```typescript
@Component({
    selector: 'app-sender-detail-panel',
    standalone: true,
    imports: [CommonModule, MatTabsModule, MatTableModule, MatPaginatorModule, ...],
    template: `...`
})
export class SenderDetailPanelComponent {
    // Inputs
    site = input.required<string>();
    senderId = input.required<number>();
    senderName = input<string>();

    // State signals
    activeTab = signal<'records' | 'lots' | 'dates' | 'sessions'>('records');
    records = signal<StageRecordPage | null>(null);
    lotBreakdown = signal<LotBreakdown[]>([]);
    dateBuckets = signal<DateBucket[]>([]);
    loading = signal(false);

    // Records tab
    statusFilter = signal<string | null>(null);
    searchQuery = signal('');
    currentPage = signal(0);
    pageSize = signal(50);

    loadRecords() { /* calls getSenderRecords() */ }
    loadLotBreakdown() { /* calls getLotBreakdown() */ }
    loadDateBreakdown() { /* calls getDateBreakdown() */ }
    exportCsv() { /* calls getSenderRecordsCsv() → download */ }
}
```

### 5.6 Date Breakdown Chart (using existing echarts dependency)

```typescript
// In sender detail panel, date-breakdown tab
import { NgxEchartsModule } from 'ngx-echarts';

chartOptions = computed(() => {
    const buckets = this.dateBuckets();
    return {
        tooltip: { trigger: 'axis' },
        legend: { data: ['Ready', 'Enqueued', 'Completed', 'Failed'] },
        xAxis: { type: 'category', data: buckets.map(b => b.label) },
        yAxis: { type: 'value' },
        series: [
            { name: 'Ready',     type: 'bar', stack: 'total', data: buckets.map(b => b.totals.ready) },
            { name: 'Enqueued',  type: 'bar', stack: 'total', data: buckets.map(b => b.totals.enqueued) },
            { name: 'Completed', type: 'bar', stack: 'total', data: buckets.map(b => b.totals.completed) },
            { name: 'Failed',    type: 'bar', stack: 'total', data: buckets.map(b => b.totals.failed) },
        ]
    };
});
```

### 5.7 Conditional Polling (Optimization)

```typescript
// Instead of always re-rendering on every 10s poll:
private lastGeneratedAt: string | null = null;

private loadSnapshot(showLoading: boolean) {
    if (showLoading) this.loading.set(true);
    this.backend.getDashboardSnapshot().subscribe({
        next: (snap) => {
            // Only update if data actually changed
            if (snap.generatedAt !== this.lastGeneratedAt) {
                this.snapshot.set(snap);
                this.lastGeneratedAt = snap.generatedAt ?? null;
            }
            this.loading.set(false);
            this.error.set(null);
        },
        error: (err) => { /* ... */ }
    });
}
```

### 5.8 Fix Dead `getStageRecords()` Endpoint

```typescript
// BEFORE (calls non-existent endpoint):
getStageRecords(params: any): Observable<StageRecordPage> {
    return this.http.get<StageRecordPage>(`${this.apiUrl}/staging/history`, ...);
}

// AFTER (calls actual endpoint):
getStageRecords(params: any): Observable<StageRecordPage> {
    return this.http.get<StageRecordPage>(`${this.apiUrl}/stage/records`, ...);
}
```

---

## 6. Routing Changes

### 6.1 Approach: Slide-Over Panel (Recommended)

The sender detail opens as a glass-panel overlay without route change. This keeps the user on the dashboard and allows quick back-navigation.

No routing changes needed. The `SenderDetailPanelComponent` is toggled via a signal:

```typescript
selectedSender = signal<{ site: string; senderId: number; senderName: string } | null>(null);

openSenderDetail(site: string, sender: DashboardSenderSnapshot) {
    this.selectedSender.set({ site, senderId: sender.senderId, senderName: sender.senderName || '' });
}

closeSenderDetail() {
    this.selectedSender.set(null);
}
```

### 6.2 Alternative: Dedicated Route

If screen real estate or deep-linking is preferred:

```typescript
// In app.routes.ts
{ path: 'exensioreload/sites/:site/senders/:senderId', component: SenderDetailComponent, canActivate: [authGuard] }
```

---

## 7. Dependency on Monitoring Redesign

### 7.1 Independent Changes (Can Be Done NOW)

These fixes don't depend on the monitoring redesign:

| Fix | Gap | Description |
|-----|-----|-------------|
| Fix `DashboardMetricTotals` type | D1 | Add `total` and `failed` to interface |
| Add Failed metric card | D6 | Add red card for failed count |
| Wire sender drill-down | D2, D3 | Click sender → load records/lots/dates from existing backend endpoints |
| Expandable site cards | D7 | Show all senders when expanded |
| Date breakdown chart | D5, D9 | Use echarts for time-series view |
| Lot breakdown view | D4 | Collapsible lot/wafer tree |
| Fix `getStageRecords()` endpoint | D10 | Change `/staging/history` → `/stage/records` |
| Remove debug endpoints | D13 | Gate or remove `/debug-auth`, `/debug-users` |
| CSV export from drill-down | D15 | Wire existing CSV endpoint |

### 7.2 Session-Dependent Changes (After Monitoring Redesign)

| Fix | Gap | Description |
|-----|-----|-------------|
| Recent sessions section | D11 | Show `staging_session` rows in dashboard |
| Replace fake active sessions | D11 | Use real `StageSessionService` instead of composite IDs |
| Typed session details | D12 | Replace `Map<String, Object>` with `StagingSessionDetail` |
| SSE-backed live updates | D14 | Optional: subscribe to session SSE for active dashboards |
| Session tab in sender detail | D3 | Show sessions for a specific sender from `staging_session` WHERE sender_id = ? |

---

## 8. Implementation Plan

### Phase 1: Type Fixes + Metric Cards (Quick Wins)
1. Fix `DashboardMetricTotals` interface — add `total` and `failed`
2. Add Failed metric card with alert state
3. Fix `getStageRecords()` dead endpoint path
4. Remove/gate debug endpoints

### Phase 2: Sender Drill-Down (Core Feature)
5. Add `BackendService` methods for records, lot-breakdown, date-breakdown
6. Add TypeScript interfaces for `LotBreakdown`, `WaferBreakdown`, `DateBucket`, `BucketTotals`
7. Create `SenderDetailPanelComponent` with tabs: Records, Lots, Dates
8. Wire sender row click → open detail panel
9. Add records table with pagination, status filter, search
10. Add lot/wafer tree view
11. Add date breakdown stacked bar chart (echarts)
12. Add CSV export button

### Phase 3: UI Enhancements
13. Expandable site cards (show all senders)
14. Conditional polling optimization (track `generatedAt`)
15. Loading skeleton states for drill-down tabs
16. Responsive layout for detail panel

### Phase 4: Session Integration (After Monitoring Redesign)
17. Add recent sessions section to dashboard
18. Replace fake active sessions with real `StageSessionService`
19. Add sessions tab in sender detail
20. Optional: SSE live update support

---

## 9. File Inventory

### Frontend — New Files
| File | Purpose |
|------|---------|
| `dashboard/sender-detail-panel.component.ts` | Sender drill-down panel with tabs |
| `dashboard/sender-detail-panel.component.html` | Template for drill-down |
| `dashboard/sender-detail-panel.component.scss` | Glassmorphism styles for panel |

### Frontend — Modified Files
| File | Change |
|------|--------|
| `api/backend.service.ts` | Add `getSenderRecords`, `getLotBreakdown`, `getDateBreakdown`, `getSenderRecordsCsv`; add missing interfaces; fix `getStageRecords` endpoint path; add `total`, `failed` to `DashboardMetricTotals` |
| `dashboard/dashboard.component.ts` | Add expandable site signal, `selectedSender` signal, conditional polling, enhanced `metricCards` with Failed card |
| `dashboard/dashboard.component.html` | Expandable site cards, clickable sender rows, detail panel overlay, Failed metric card |
| `dashboard/dashboard.component.scss` | Styles for expanded state, sender hover, detail panel slide-over |

### Backend — Modified Files
| File | Change |
|------|--------|
| `controller/DashboardController.java` | Remove debug endpoints (or gate behind SUPER_ADMIN); add `activeSessions` and `lastActivityAt` to snapshot (minor) |
| `dto/DashboardSnapshot.java` | Add `activeSessions` (int) and `lastActivityAt` (Instant) fields |

### Backend — No New Files

All required drill-down endpoints already exist. The backend is ahead of the frontend.

---

## 10. Key Design Decisions

### 10.1 Slide-Over Panel vs Full Page Route

**Chosen: Slide-over panel**
- Users stay on dashboard, can quickly compare senders
- No route change → simpler, faster navigation
- Glass panel overlay fits the existing design system
- Can be promoted to a route later if deep-linking is needed

### 10.2 Chart Library Choice

**Chosen: echarts (already in package.json)**
- `echarts 6` and `ngx-echarts 21` are already dependencies
- Stacked bar chart for date breakdown
- Donut chart for status distribution (optional)
- Sparkline for global trend (optional)

### 10.3 Polling vs SSE for Dashboard

**Chosen: Polling (improved) for Phase 1–3, SSE optional for Phase 4**
- Dashboard shows aggregate data across ALL sites — SSE per-session doesn't map cleanly
- Conditional polling (skip if unchanged) reduces waste sufficiently
- SSE makes sense when monitoring a specific active session, not for the global view

### 10.4 Relationship to My Sessions Page

The dashboard provides a *global* operational view (all sites, all senders, admin-level). My Sessions provides a *personal* view (current user's sessions only). They share:
- `StagingSessionSummary` interface
- Session detail drill-down component (from monitoring redesign)
- Lot/wafer tree component

But serve different audiences (admin overview vs individual user tracking).
