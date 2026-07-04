# Monitor Page State Accounting Improvements — Design Document

## Overview

The monitor dashboard currently suffers from incomplete state visibility, with ~73% of records missing from accounting due to invisible CANCELLED records, ungrouped EXENSIO_LOADING states, and lack of timeout tracking. This design addresses these gaps through:

1. **Extended dashboard cards** showing all pipeline states explicitly
2. **New aggregation queries** tracking CANCELLED and EXENSIO_LOADING separately
3. **Timeout detection** for stuck enrichment records
4. **Real-time SSE updates** maintaining card accuracy
5. **Data integrity verification** endpoints for admin validation
6. **Complete state transition tracking** with visual pipeline representation

## Architecture

### Component Relationships

```
Dashboard Snapshot Request
    ↓
DashboardController.snapshot()
    ↓
RefDbService.fetchStatuses() [EXTENDED]
    ├─ Query: pending count (Staged)
    ├─ Query: ENQUEUED count (Queued for CP)
    ├─ Query: ENRICHMENT count (In Enrichment)
    ├─ Query: EXENSIO_LOADING count (Exensio Loading) [NEW]
    ├─ Query: FAILED count (Failed)
    ├─ Query: DONE count (Completed)
    └─ Query: CANCELLED count (Paused/Deleted) [NEW]
    ↓
StageMonitorService [ENHANCED]
    ├─ Broadcast STATE_AGGREGATION events [NEW]
    └─ Batch updates to reduce SSE traffic
    ↓
Frontend Dashboard Cards [EXTENDED]
    ├─ Card: Staged (pending)
    ├─ Card: Queued for CP (ENQUEUED)
    ├─ Card: In Enrichment (ENRICHMENT)
    ├─ Card: Exensio Loading (EXENSIO_LOADING) [NEW]
    ├─ Card: Completed (DONE)
    ├─ Card: Failed (FAILED)
    └─ Card: Cancelled (CANCELLED) [NEW]

Parallel Monitoring
    ├─ CpLogMonitor [ENHANCED]
    │   └─ Track timeout/stuck records
    │
    └─ ScheduledDataIntegrityJob [NEW]
        ├─ Verify all records in valid states
        ├─ Flag NULL/UNKNOWN status
        └─ Auto-remediate stuck enrichment records
```

## Components and Interfaces

### 1. Enhanced RefDbService.fetchStatuses()

**Current Query (7 columns):**

```sql
SELECT site, sender_id, sender_name,
  COUNT(*),                                    -- total
  SUM(CASE WHEN status = 'pending'),          -- ready
  SUM(CASE WHEN status IN (...)),             -- enqueued
  SUM(CASE WHEN status = 'FAILED'),           -- failed
  SUM(CASE WHEN status = 'DONE')              -- completed
FROM SENDER_STAGE GROUP BY site, sender_id
```

**Enhanced Query (9 columns) [NEW]:**

```sql
SELECT site, sender_id, sender_name,
  COUNT(*),                                    -- total
  SUM(CASE WHEN status = 'pending'),          -- ready (Staged)
  SUM(CASE WHEN status = 'ENQUEUED'),         -- queued (Queued for CP)
  SUM(CASE WHEN status = 'ENRICHMENT'),       -- enriching (In Enrichment)
  SUM(CASE WHEN status = 'EXENSIO_LOADING'),  -- exensio_loading [NEW]
  SUM(CASE WHEN status = 'FAILED'),           -- failed
  SUM(CASE WHEN status = 'DONE'),             -- completed
  SUM(CASE WHEN status = 'CANCELLED')         -- cancelled [NEW]
FROM SENDER_STAGE
WHERE [filters...]
GROUP BY site, sender_id
```

**Updated StageStatus Record:**

```java
public record StageStatus(
    String site,
    int senderId,
    String senderName,
    long total,
    long ready,           // pending only
    long queued,          // ENQUEUED only [NEW: extracted from enqueued]
    long enriching,       // ENRICHMENT only [NEW: extracted from enqueued]
    long exensioLoading,  // EXENSIO_LOADING only [NEW]
    long failed,
    long completed,
    long cancelled,       // CANCELLED [NEW]
    List<StageUserStatus> users
) { }
```

### 2. New Debug Endpoint: State Accounting Verification

**Endpoint:** `GET /api/admin/debug/state-accounting`

**Authorization:** ROLE_ADMIN only

**Response:**

```json
{
  "timestamp": "2026-07-03T10:30:00Z",
  "database": {
    "total_count": 4544,
    "states": {
      "pending": 0,
      "ENQUEUED": 100,
      "ENRICHMENT": 900,
      "EXENSIO_LOADING": 150,
      "PROCESSING": 0,
      "FAILED": 45,
      "DONE": 240,
      "CANCELLED": 3109,
      "UNKNOWN": 0,
      "NULL_STATUS": 0
    },
    "sum_of_states": 4544,
    "discrepancies": []
  },
  "dashboard_cards": {
    "staged": 0,
    "queued": 100,
    "enriching": 900,
    "exensio_loading": 150,
    "failed": 45,
    "completed": 240,
    "cancelled": 3109,
    "sum": 4544
  },
  "data_integrity": {
    "valid": true,
    "warnings": [],
    "errors": []
  },
  "by_sender": [
    {
      "site": "SITE_A",
      "sender_id": 102,
      "sender_name": "EC_JND_TESEC_HIST",
      "total": 4544,
      "states": { ... }
    }
  ]
}
```

### 3. Enhanced StageMonitorService

**New Event Type: STATE_AGGREGATION**

When any record status changes:

```java
public void broadcastStateAggregation(String sessionId, StateAggregationEvent event) {
  // Event format:
  {
    "timestamp": "2026-07-03T10:30:00Z",
    "changes": [
      { "state": "pending", "previousCount": 5, "newCount": 4 },
      { "state": "ENRICHMENT", "previousCount": 1000, "newCount": 1001 }
    ],
    "totals": {
      "staged": 4,
      "queued": 100,
      "enriching": 1001,
      "exensio_loading": 150,
      "failed": 45,
      "completed": 240,
      "cancelled": 3109
    }
  }
  sendEvent(sessionId, "STATE_AGGREGATION", event);
}
```

**Batching Logic:**

- Collect status changes during 1-second window
- Aggregate changes per state
- Broadcast single aggregation event instead of per-record updates
- Reduces SSE traffic from ~1000 msgs/sec to ~1 msg/sec during bulk operations

### 4. New Timeout Tracking in CpLogMonitor

**Stuck Record Detection:**

```java
public void detectStuckEnrichmentRecords() {
  String query = """
    SELECT id, lot, metadata_id, updated_at
    FROM SENDER_STAGE
    WHERE status = 'ENRICHMENT'
    AND DATEDIFF(MINUTE, updated_at, GETDATE()) > ?  -- enrichment_timeout_minutes
    AND request_id = ?
  """;

  for (StageRecord stuck : stuckRecords) {
    long minutesStuck = calculateMinutesInState(stuck);

    // Attempt remediation
    if (canAutoRemediate(stuck)) {
      markDoneManualVerify(stuck, "Auto-remediated after " + minutesStuck + " minutes");
    }

    // Track for monitoring
    recordStuckMetric(stuck, minutesStuck);

    // Emit alert
    emitStuckRecordAlert(requestId, stuck, minutesStuck);
  }
}
```

### 5. New Data Integrity Job

**Scheduled: Hourly (configurable)**

```java
@Scheduled(cron = "0 0 * * * *")  // Every hour
public void verifyDataIntegrity() {
  // 1. Check for invalid states
  List<StageRecord> invalidRecords = findInvalidStates();
  if (!invalidRecords.isEmpty()) {
    logDataQualityIssue("INVALID_STATE", invalidRecords);
    alertAdmin("Data integrity check: " + invalidRecords.size() + " records in invalid states");
  }

  // 2. Check for NULL status
  List<StageRecord> nullStatus = findNullStatus();
  if (!nullStatus.isEmpty()) {
    logDataQualityIssue("NULL_STATUS", nullStatus);
    alertAdmin("Data integrity check: " + nullStatus.size() + " records with NULL status");
  }

  // 3. Detect and remediate stuck enrichment
  List<StageRecord> stuckRecords = findStuckEnrichmentRecords();
  for (StageRecord stuck : stuckRecords) {
    markDoneManualVerify(stuck, "Auto-remediated by integrity job");
  }

  // 4. Verify accounting balance
  long total = countAllRecords();
  long summedStates = sumAllStates();
  if (total != summedStates) {
    logDataQualityIssue("ACCOUNTING_IMBALANCE",
      "Total: " + total + ", Summed: " + summedStates);
  }
}
```

## Data Models

### StageStatus Record (Enhanced)

```java
public record StageStatus(
    String site,
    int senderId,
    String senderName,
    long total,
    long ready,           // CASE WHEN status = 'pending'
    long queued,          // CASE WHEN status = 'ENQUEUED'      [NEW]
    long enriching,       // CASE WHEN status = 'ENRICHMENT'    [NEW]
    long exensioLoading,  // CASE WHEN status = 'EXENSIO_LOADING' [NEW]
    long failed,          // CASE WHEN status = 'FAILED'
    long completed,       // CASE WHEN status = 'DONE'
    long cancelled,       // CASE WHEN status = 'CANCELLED'     [NEW]
    List<StageUserStatus> users
) {
  public long backlog() {
    return queued + enriching + exensioLoading;  // Records still in processing
  }

  public long accountingCheck() {
    return ready + queued + enriching + exensioLoading + failed + completed + cancelled;
  }
}
```

### StateAggregationEvent

```java
public record StateAggregationEvent(
    Instant timestamp,
    List<StateChange> changes,
    Map<String, Long> totals,
    String requestId
) {
  public record StateChange(
      String state,
      long previousCount,
      long newCount
  ) {}
}
```

### DataIntegrityReport

```java
public record DataIntegrityReport(
    Instant timestamp,
    long totalCount,
    Map<String, Long> stateCounts,
    long sumOfStates,
    boolean isValid,
    List<String> warnings,
    List<String> errors,
    List<DataIssue> issues
) {
  public record DataIssue(
      String type,  // INVALID_STATE, NULL_STATUS, STUCK_ENRICHMENT, etc.
      long count,
      List<Long> sampleRecordIds
  ) {}
}
```

## Error Handling

### Invalid State Detection

- **Detection**: Scheduled job queries for status NOT IN (pending, ENQUEUED, ENRICHMENT, EXENSIO_LOADING, PROCESSING, FAILED, DONE, CANCELLED)
- **Response**: Log issue, flag in data integrity report, alert admin
- **Recovery**: Manual investigation required (not auto-remediable)

### NULL Status Records

- **Detection**: Scheduled job queries for status IS NULL
- **Response**: Log issue, flag in data integrity report, alert admin
- **Recovery**: Admin can manually set correct status or delete record

### Stuck Enrichment Records

- **Detection**: Records in ENRICHMENT for > timeout (default 5 mins)
- **Response**: Log to integrationStatusService, emit alert via SSE
- **Recovery**: Auto-remediate by marking DONE with manual-verify flag

### Orphaned Queue Entries

- **Detection**: Records in CANCELLED but still in DTP_SENDER_QUEUE_ITEM
- **Response**: Log issue, flag in data integrity report
- **Recovery**: Clean up queue entries (manual or automated)

## Testing Strategy

### Unit Tests

- Test StageStatus calculation of backlog() and accountingCheck()
- Test StateAggregationEvent batching logic
- Test data integrity detection functions
- Test state transition triggers

### Property-Based Tests

- For all records, sum of states = total (accounting property)
- All records in database are in valid states (state validity property)
- Status transitions follow allowed paths (state machine property)
- Aggregation accuracy: card counts match database counts (query accuracy property)

### Integration Tests

- End-to-end dashboard metric calculation
- SSE broadcast on status change
- Data integrity job detection and remediation
- Timeout detection and auto-remediation

---

## Correctness Properties

A property is a characteristic or behavior that should hold true across all valid executions of a system—essentially, a formal statement about what the system should do. Properties serve as the bridge between human-readable specifications and machine-verifiable correctness guarantees.

### Property 1: Accounting Invariant

**For any** session with N total records, the sum of all state counts (ready + queued + enriching + exensioLoading + failed + completed + cancelled) SHALL equal N.

**Validates: Requirements 1, 2, 6**

### Property 2: State Validity

**For any** record in the database, the status field SHALL contain one of: {pending, ENQUEUED, ENRICHMENT, EXENSIO_LOADING, PROCESSING, DONE, FAILED, CANCELLED}.

**Validates: Requirements 2, 8**

### Property 3: Cancelled Visibility

**For any** CANCELLED record, querying the dashboard SHALL include it in the cancelled card count and the accounting sum.

**Validates: Requirement 1**

### Property 4: Exensio Loading Distinction

**For any** record in EXENSIO_LOADING status, the dashboard SHALL display it separately from ENRICHMENT records (not grouped).

**Validates: Requirement 3**

### Property 5: Real-Time Accuracy

**For any** status transition from state A to state B, SSE broadcast of STATE_AGGREGATION SHALL reflect the count change within 1 second, and dashboard card totals SHALL update accordingly.

**Validates: Requirement 7**

### Property 6: Stuck Record Detection

**For any** record in ENRICHMENT status exceeding the timeout threshold, the system SHALL either (a) detect and mark it as stuck, or (b) auto-remediate it, within the next scheduled integrity check.

**Validates: Requirement 4, 8**

### Property 7: Query Accuracy

**For any** aggregation query with filters (by site, sender_id, request_id), the returned state counts grouped by (site, sender_id) SHALL match a direct count of matching records in each state.

**Validates: Requirements 2, 6**

### Property 8: Timeout Configuration Immutability

**For any** configuration change to enrichment timeout minutes, records already stuck at the old timeout threshold SHALL NOT retroactively change their remediation status.

**Validates: Requirement 4**

---

## Implementation Notes

### Database Indexes

- Ensure index on (status, request_id) for efficient state aggregation queries
- Ensure index on (status, updated_at) for timeout detection
- Consider index on (site, sender_id) for fast filtering

### Performance Considerations

- Aggregation queries scan entire SENDER_STAGE table; optimize with partitioning by request_id if table grows large
- SSE batching reduces updates from 1000+/sec to ~1/sec during bulk operations
- Timeout detection runs hourly; adjust frequency if more responsive detection needed

### Configuration

- `enrichmentTimeoutMinutes`: How long before ENRICHMENT records are considered stuck (default: 5)
- `integrityCheckCron`: Schedule for data integrity job (default: hourly)
- `sseUpdateBatchWindowMs`: Window for batching aggregation updates (default: 1000ms)

### Backward Compatibility

- Old `enqueued` field in StageStatus can be kept as computed property: `enqueued = queued + enriching + exensioLoading`
- Existing dashboard code continues to work; new cards are additive
- New queries don't affect existing record processing logic

---

## Frontend Dashboard Updates

### Card Layout (7 cards total)

```
┌─────────────────────────────────────────────────────┐
│ Total Files: 4544                                   │
├──────────┬──────────┬──────────┬──────────┐
│ Staged   │ Queued   │ Enriching│ Exensio  │
│    0     │   100    │   900    │   150    │
├──────────┼──────────┼──────────┼──────────┤
│ Completed│  Failed  │ Cancelled│         │
│   240    │    45    │  3109    │  [Alert]│
└──────────┴──────────┴──────────┴──────────┘

[Alert] = Stuck records badge if count > 0
```

### Card Click Behavior

- Click card → Show detail sidebar with sample records in that state
- Show top 20 records, sorted by created_at DESC
- Include status, filename, lot, wafer for context

### State Legend (Tooltip on hover)

- **Staged**: Ready for dispatch (pending)
- **Queued for CP**: Waiting to enter CP pipeline (ENQUEUED)
- **In Enrichment**: Currently being processed by CP
- **Exensio Loading**: Undergoing Exensio verification
- **Completed**: Successfully processed (DONE)
- **Failed**: Encountered error during processing
- **Cancelled**: Paused or soft-deleted by user

---

## Summary

This design provides complete accounting visibility by:

1. Extending database queries to capture all 8 record states explicitly
2. Adding real-time SSE updates via aggregation events
3. Implementing timeout detection and auto-remediation
4. Adding admin verification endpoints for data integrity
5. Maintaining backward compatibility with existing dashboard code
