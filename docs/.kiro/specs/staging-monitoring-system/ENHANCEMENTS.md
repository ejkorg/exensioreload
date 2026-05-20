# Staging Monitoring System - Remaining Enhancements

## Current State Summary

✅ **Already Implemented:**
- Session persistence (staging_session table + Liquibase)
- StageSessionService (full CRUD, counters, lot/wafer aggregation)
- StageMonitorService (SSE infrastructure, multi-emitter, heartbeat)
- Frontend StagingSessionService (SSE + polling fallback)
- MySessionsComponent (session list + detail)
- All API endpoints in StageController
- Integration with SenderDispatchService and SenderQueueMonitor

## Remaining Gaps & Enhancements

### 1. Enhanced SSE Events

**Current**: Basic STATS, ROW_UPDATE, COMPLETE events
**Needed**: Richer event payloads

```java
// NEW Event Types to Add
public record FileUpdateEvent(
    long id, String metadataId, String dataId,
    String lot, String wafer, String filename,
    String status, String displayStatus,  // "Queued" vs "Processing"
    String message, String updatedAt
) {}

public record LotUpdateEvent(
    String lot, int totalWafers, int completedWafers,
    int failedWafers, double progress
) {}

public record SessionStatusEvent(
    String status, String completedAt, String message
) {}

public record SessionStatsEvent(
    int total, int staged, int enqueued, int done, int failed,
    double progress, double throughput, int eta, double successRate
) {}
```

### 2. Event Batching

**Current**: Individual events per file
**Needed**: Batch FILE_UPDATE events
- Buffer for 500ms
- Send as FILE_UPDATES array (max 50 files per event)
- Reduces SSE traffic during rapid status changes

### 3. Queue Status Visibility

**Current**: Backend checks external queue, but UI doesn't distinguish states
**Needed**: Display status mapping

| SENDER_STAGE.status | External Queue | Display Status |
|---------------------|---------------|----------------|
| NEW | — | "Staged" |
| ENQUEUED | EXISTS in DTP_SENDER_QUEUE_ITEM | "Queued" |
| ENQUEUED | NOT in DTP_SENDER_QUEUE_ITEM | "Processing" |
| DONE | — | "Completed" |
| FAILED | — | "Failed" |

### 4. Reduced Monitor Latency

**Current**: SenderQueueMonitor runs at default interval
**Needed**: 
- Reduce to 30-second interval
- Page through ALL ENQUEUED records (not just first batch)
- Broadcast FILE_UPDATE events on each status change

### 5. Lot/Wafer Tree Component

**Current**: Backend aggregation exists, no UI component
**Needed**: Create `LotWaferProgressComponent`

```typescript
@Component({
  selector: 'app-lot-wafer-progress',
  template: `
    <div class="lot-wafer-tree">
      @for (lot of lots(); track lot.lot) {
        <div class="lot-group" [class.expanded]="expandedLots.has(lot.lot)">
          <div class="lot-header" (click)="toggleLot(lot.lot)">
            <glass-icon [name]="expandedLots.has(lot.lot) ? 'chevron-down' : 'chevron-right'"></glass-icon>
            <span>{{ lot.lot }}</span>
            <span>{{ lot.completedWafers }}/{{ lot.totalWafers }} wafers</span>
            <div class="progress-bar">
              <div class="fill" [style.width.%]="(lot.completedWafers / lot.totalWafers) * 100"></div>
            </div>
          </div>
          
          @if (expandedLots.has(lot.lot)) {
            <div class="wafer-list">
              @for (wafer of getWafersForLot(lot.lot); track wafer.wafer) {
                <div class="wafer-item">
                  <span>Wafer {{ wafer.wafer }}</span>
                  <span [class]="wafer.status">{{ wafer.status }}</span>
                </div>
              }
            </div>
          }
        </div>
      }
    </div>
  `
})
export class LotWaferProgressComponent {
  @Input() sessionId!: string;
  lots = signal<LotWaferProgress[]>([]);
  expandedLots = new Set<string>();
  
  // Load lots from backend, subscribe to LOT_UPDATE events
}
```

### 6. Activity Feed Component

**Current**: Not implemented
**Needed**: Chronological event log

```typescript
@Component({
  selector: 'app-activity-feed',
  template: `
    <div class="activity-feed">
      <h4>Activity</h4>
      <div class="activity-list">
        @for (activity of activities(); track activity.timestamp) {
          <div class="activity-item">
            <span class="timestamp">{{ activity.timestamp | date:'HH:mm:ss' }}</span>
            <span class="message">{{ activity.message }}</span>
          </div>
        }
      </div>
    </div>
  `
})
export class ActivityFeedComponent {
  @Input() activities = signal<ActivityEvent[]>([]);
}
```

### 7. Throughput & ETA Calculations

**Current**: Not calculated
**Needed**: Add to StageSessionService

```java
public SessionMetrics calculateMetrics(String sessionId) {
    // Query completion timestamps from last 5 minutes
    // Calculate files completed per minute
    // Calculate ETA = remaining / throughput
    // Calculate success rate = done / (done + failed)
}
```

## Implementation Priority

1. **High Priority** (Core functionality gaps):
   - Enhanced SSE events (FILE_UPDATE, LOT_UPDATE, SESSION_STATUS)
   - Queue status visibility ("Queued" vs "Processing")
   - Reduced monitor latency (30s interval)

2. **Medium Priority** (UX improvements):
   - Lot/wafer tree component
   - Activity feed component
   - Event batching

3. **Low Priority** (Nice-to-have):
   - Throughput & ETA calculations
   - Advanced filtering in MySessionsComponent

## Testing Focus

- Property tests for status mapping logic
- Property tests for event batching (no events lost)
- Property tests for throughput/ETA calculations
- Integration tests for SSE event delivery
- UI tests for lot/wafer tree interactions
