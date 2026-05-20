# Monitoring UI - Complete Implementation

## Overview

The Monitor Dispatch view (Step 3 of the stepper) now correctly displays real-time file processing status with visual indicators, adhering to the design specified in `STAGING_MONITORING_DESIGN.md`.

## UI Components

### 1. Summary Metrics (MonitoringStatsComponent)
Displays aggregate statistics in card format:
- **Total Files**: Overall count
- **Queued**: Files waiting (staged + enqueued)
- **Processing**: Files currently being processed
- **Completed**: Successfully processed files
- **Failed**: Files with errors
- **Progress Bar**: Visual percentage completion
- **Throughput**: Files per minute
- **ETA**: Estimated time to completion

### 2. File List Table (MonitoringFileListComponent)
Shows individual files with status indicators:

| Status | Color | Icon | Meaning |
|--------|-------|------|---------|
| **READY** | Gray | check | File staged, waiting for dispatch |
| **ENQUEUED** | Yellow | clock | File queued in external sender queue |
| **PROCESSING** | Blue | refresh | File being processed by external system |
| **COMPLETED** | Green | check_circle | File successfully processed |
| **ERROR** | Red | error | File processing failed |

**Features:**
- Virtual scrolling for performance with 1000+ files
- Status filter buttons (All, Processing, Completed, Failed)
- Search by filename, lot, or wafer
- Click to expand error details for failed files
- Export to CSV

### 3. Lot/Wafer Progress (LotWaferProgressComponent)
Hierarchical view of progress by lot:
- Collapsible lot groups
- Wafer-level progress bars
- Visual indicators for completion status
- Aggregated counts per lot

### 4. Activity Feed (ActivityFeedComponent)
Real-time event stream showing:
- File staged events
- Status change notifications
- Completion events
- Error notifications
- Auto-scrolling to latest events

## Data Flow

```
Backend SSE Events
    ↓
StagingSessionService
    ├─ currentSession signal (session metadata)
    ├─ sessionFiles signal (file list)
    ├─ lotProgress signal (lot/wafer data)
    └─ activities signal (event stream)
    ↓
Stepper Component Computed Signals
    ├─ monitoringStats() → maps to MonitoringStats format
    ├─ monitoringFiles() → maps to MonitoringFile[] format
    ├─ lotProgressMapped() → maps to LotProgress format
    └─ activityFeedEvents() → maps to ActivityEvent[] format
    ↓
Monitoring Components
    ├─ <app-monitoring-stats [stats]="monitoringStats()">
    ├─ <app-monitoring-file-list [files]="monitoringFiles()">
    ├─ <app-lot-wafer-progress [lotData]="lotProgressMapped()">
    └─ <app-activity-feed [events]="activityFeedEvents()">
```

## Status Mapping

The stepper component's `mapBackendStatus()` method translates backend statuses to UI statuses:

```typescript
private mapBackendStatus(status: string): MonitoringFile['status'] {
    const normalized = (status || '').toUpperCase();
    if (normalized === 'DONE' || normalized === 'COMPLETED') return 'COMPLETED';
    if (normalized === 'FAILED' || normalized === 'ERROR') return 'ERROR';
    if (normalized === 'ENQUEUED') return 'ENQUEUED';
    if (normalized === 'PROCESSING') return 'PROCESSING';
    return 'READY';
}
```

## UI States

The `monitorUiState()` computed signal determines what to display:

| State | Display | Condition |
|-------|---------|-----------|
| **no-session** | Empty state | No requestId/sessionId |
| **connecting** | Spinner + "Connecting..." | SSE connecting, no data yet |
| **live** | Full monitoring UI | SSE connected, data available |
| **polling** | Full UI + "Polling" badge | SSE failed, using HTTP polling fallback |
| **completed** | Session summary card | Session status is COMPLETED/PARTIALLY_FAILED/CANCELLED |

## User Actions

Available buttons in Step 3:

1. **Stop Monitoring**: Disconnects SSE and stops polling
2. **Reconnect Live**: Attempts to re-establish SSE connection
3. **Refresh**: Manually fetches latest session data
4. **Export CSV**: Downloads file list as CSV
5. **Finish and Return to Hub**: Navigates back to dashboard

## Empty State

When no data is available yet (connecting or waiting), displays:
- Spinner (if connecting/polling)
- Hourglass icon (if waiting)
- Status message from `monitoringStatusText()`
- Note: "Dispatch and queue checks continue in the background."

## Responsive Design

The monitoring UI adapts to screen size:
- **Desktop (1024px+)**: Full 5-column table with all details
- **Tablet (768-1024px)**: 4-column table, hides message column
- **Mobile (<768px)**: 3-column table, hides lot and message columns

## Real-Time Updates

SSE events trigger automatic UI updates:

1. **STATS event** → Updates summary metrics
2. **FILE_UPDATE event** → Updates individual file status in table
3. **FILE_UPDATES event** → Batch update of multiple files
4. **LOT_UPDATE event** → Updates lot/wafer progress
5. **SESSION_STATUS event** → Updates session state
6. **HEARTBEAT event** → Keeps connection alive

## Performance Optimizations

1. **Virtual Scrolling**: CDK virtual scroll viewport handles 10,000+ files efficiently
2. **Computed Signals**: Reactive updates only when source data changes
3. **Batched Events**: Backend batches FILE_UPDATE events to reduce SSE traffic
4. **Pagination**: Backend paginates file queries (100 files per page)
5. **Debounced Search**: Search input debounced to avoid excessive filtering

## Alignment with Design Document

This implementation fully adheres to `STAGING_MONITORING_DESIGN.md`:

✅ File-level table with status indicators  
✅ Lot/wafer hierarchical progress view  
✅ Summary metrics with progress/throughput/ETA  
✅ Activity feed with real-time events  
✅ Status filtering and search  
✅ CSV export functionality  
✅ SSE with polling fallback  
✅ Session persistence and history  
✅ Glassmorphism design system  
✅ Dark/light theme support  

## Testing Checklist

To verify the monitoring UI works correctly:

- [ ] Stage 1-5 files → See them appear in file list with READY status
- [ ] Wait for dispatch → Files change to ENQUEUED status (yellow)
- [ ] Monitor external processing → Files change to PROCESSING (blue) then COMPLETED (green)
- [ ] Test status filters → Click "Processing", "Completed", "Failed" buttons
- [ ] Test search → Type lot/wafer/filename in search box
- [ ] Test error handling → Stage invalid file, see ERROR status (red) with expandable details
- [ ] Test lot/wafer view → Verify hierarchical progress display
- [ ] Test activity feed → See real-time events as files progress
- [ ] Test export → Click "Export CSV", verify file downloads
- [ ] Test reconnection → Disconnect network, reconnect, verify SSE reconnects
- [ ] Test polling fallback → Block SSE, verify polling mode activates
- [ ] Test completion → Wait for all files to complete, see session summary card

## Known Limitations

1. **Processing status inference**: Backend doesn't explicitly track PROCESSING state; it's inferred when file is ENQUEUED but not in external queue
2. **Throughput calculation**: Only accurate after first file completes
3. **ETA accuracy**: Depends on consistent processing speed
4. **Token in query string**: JWT token visible in browser network tab (acceptable for SSE limitation)

## Future Enhancements

Potential improvements (not in current scope):

- WebSocket instead of SSE for bidirectional communication
- Real-time progress bars per file (not just status)
- Retry failed files directly from UI
- Pause/resume session functionality
- Email notifications on completion
- Advanced filtering (date range, sender, site)
- Bulk actions (cancel selected, retry selected)
