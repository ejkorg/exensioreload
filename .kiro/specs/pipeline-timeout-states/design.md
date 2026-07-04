# Design Document: Pipeline Timeout States

## Overview

This design implements two new pipeline states (ENRICHMENT_TIMEOUT and EXENSIO_TIMEOUT) to provide honest accounting of records where enrichment or Exensio verification status remains uncertain after timeout periods. The current system conflates uncertain states with success (marking as DONE) or failure (marking as FAILED), which misleads operators and corrupts pipeline metrics.

The design adds explicit timeout states that:

- Distinguish uncertainty from confirmed success or failure
- Maintain accurate state accounting where all records sum correctly
- Provide clear operator visibility into records requiring manual intervention
- Enable future retry logic for unresolved records
- Preserve backward compatibility with existing deployments

## Architecture

### State Machine Extension

The current state machine has 7 states:

```
pending → ENQUEUED → ENRICHMENT → EXENSIO_LOADING → DONE
                          ↓              ↓
                        FAILED        FAILED

                      CANCELLED (from any state)
```

The extended state machine adds 2 new timeout states:

```
pending → ENQUEUED → ENRICHMENT ─────→ EXENSIO_LOADING → DONE
    ↓         ↓
 CANCELLED CANCELLED   ENRICHMENT_TIMEOUT  EXENSIO_TIMEOUT
                          ↓                    ↓
                     (manual/retry)       (manual/retry)
                          ↓                    ↓
                   DONE or FAILED        DONE or FAILED
```

**Note on CANCELLED:** Records can only be cancelled while in `pending` or `ENQUEUED` states (before enrichment starts). Once a record enters `ENRICHMENT` state, it has been consumed from the sender queue and enrichment has begun, so cancellation is no longer possible.

### Timeout Trigger Points

**ENRICHMENT_TIMEOUT** is triggered when:

1. Record has been in ENRICHMENT state for ≥ 15 minutes (configurable)
2. Elasticsearch returns NotFound for the lot/wafer
3. pp_log database returns NotFound for the lot/wafer
4. No concrete error is found (no actual failure, just absence of data)

**EXENSIO_TIMEOUT** is triggered when:

1. Record has been in EXENSIO_LOADING state for ≥ 60 minutes (configurable)
2. Exensio API returns NotFound for the wafer
3. Maximum retry attempts have been exhausted
4. No concrete error is found (wafer simply not found in Exensio yet)

### Component Architecture

```
┌──────────────────────────────────────────────────────────────┐
│                     Backend Services                         │
├──────────────────────────────────────────────────────────────┤
│                                                              │
│  CpLogMonitor                                                │
│  ├─ monitors ENRICHMENT state                                │
│  ├─ checks ES + pp_log + Exensio                            │
│  └─ triggers ENRICHMENT_TIMEOUT via RefDbService            │
│                                                              │
│  ExensioLoadMonitor                                          │
│  ├─ monitors EXENSIO_LOADING state                          │
│  ├─ checks Exensio API                                      │
│  └─ triggers EXENSIO_TIMEOUT via BatchResult updates        │
│                                                              │
│  RefDbService                                                │
│  ├─ markEnrichmentTimeout(record, diagnostic)               │
│  ├─ markExensioTimeout(record, reason)                      │
│  ├─ updateRecordStatus(id, status, message)                 │
│  └─ emits SSE via StateAggregationBatcher                   │
│                                                              │
│  StateAccountingService                                      │
│  ├─ queries all 9 states (includes 2 new timeouts)         │
│  ├─ validates: sum of all states = total count             │
│  └─ provides breakdown by sender/site                       │
│                                                              │
│  StateAggregationBatcher                                     │
│  ├─ batches state change events                             │
│  └─ emits SSE updates to frontend                          │
│                                                              │
└──────────────────────────────────────────────────────────────┘
                            │
                            │ SSE / REST API
                            ↓
┌──────────────────────────────────────────────────────────────┐
│                     Frontend Components                       │
├──────────────────────────────────────────────────────────────┤
│                                                              │
│  DashboardComponent                                          │
│  ├─ displays 9 metric cards (includes 2 timeout cards)     │
│  ├─ receives SSE state updates                              │
│  └─ updates card counts in real-time                        │
│                                                              │
│  StateLegendService                                          │
│  ├─ defines all 9 states with descriptions                  │
│  ├─ specifies valid transitions                             │
│  └─ provides tooltips and help text                         │
│                                                              │
│  StepperComponent                                            │
│  ├─ displays timeout counts in step 3 (monitor dispatch)   │
│  ├─ filters records by timeout status                       │
│  └─ shows file-level breakdown                              │
│                                                              │
│  BackendService                                              │
│  ├─ fetches DashboardMetricTotals from API                  │
│  ├─ includes enrichmentTimeout and exensioTimeout fields    │
│  └─ handles SSE event stream                                │
│                                                              │
└──────────────────────────────────────────────────────────────┘
```

## Components and Interfaces

### Backend Components

#### 1. Database Schema (Migration)

**New Status Values:**

- Add `ENRICHMENT_TIMEOUT` to allowed status values
- Add `EXENSIO_TIMEOUT` to allowed status values

**Schema Changes:**

```sql
-- Update CHECK constraint if present
ALTER TABLE SENDER_STAGE DROP CONSTRAINT IF EXISTS chk_sender_stage_status;
ALTER TABLE SENDER_STAGE ADD CONSTRAINT chk_sender_stage_status
  CHECK (status IN (
    'pending',
    'ENQUEUED',
    'ENRICHMENT',
    'ENRICHMENT_TIMEOUT',
    'EXENSIO_LOADING',
    'EXENSIO_TIMEOUT',
    'DONE',
    'FAILED',
    'CANCELLED'
  ));
```

**Backward Compatibility:**

- Existing records unaffected (no data migration needed)
- New status values only used for future timeout detections
- Old queries continue to work (may not recognize new states)

#### 2. RefDbService

**New Methods:**

```java
/**
 * Mark record with enrichment timeout.
 * Called when ES, pp_log, and Exensio direct lookup all return NotFound after timeout.
 *
 * @param record The stage record that timed out
 * @param diagnosticSummary Detailed diagnostic from all enrichment sources
 */
public void markEnrichmentTimeout(StageRecord record, String diagnosticSummary) {
    String errorMessage = "[Enrichment Timeout] " + diagnosticSummary
        + " No definitive enrichment result found after "
        + elasticsearchProperties.getTimeoutMinutes() + " minutes. "
        + "Needs manual verification or retry.";

    updateRecordStatus(record.id(), "ENRICHMENT_TIMEOUT", errorMessage);

    // Emit SSE state change
    stateAggregationBatcher.recordStateChange(
        record.requestId(),
        "ENRICHMENT",
        "ENRICHMENT_TIMEOUT",
        1,
        1
    );

    log.info("Marked record {} as ENRICHMENT_TIMEOUT: {}",
        record.id(), diagnosticSummary);
}

/**
 * Mark record with Exensio timeout.
 * Called when Exensio API returns NotFound after configured timeout period.
 *
 * @param record The stage record that timed out
 * @param reason Description of timeout condition
 */
public void markExensioTimeout(StageRecord record, String reason) {
    String errorMessage = "[Exensio Timeout] " + reason
        + " Wafer not found after " + exensioProperties.getTimeoutMinutes()
        + " minutes. May need manual verification or retry.";

    updateRecordStatus(record.id(), "EXENSIO_TIMEOUT", errorMessage);

    // Emit SSE state change
    stateAggregationBatcher.recordStateChange(
        record.requestId(),
        "EXENSIO_LOADING",
        "EXENSIO_TIMEOUT",
        1,
        1
    );

    log.info("Marked record {} as EXENSIO_TIMEOUT: {}",
        record.id(), reason);
}
```

**Updated fetchStatusesFor() Query:**

```java
// Add two new CASE WHEN clauses for timeout states
String query = "SELECT "
    + "SUM(CASE WHEN status = 'pending' THEN 1 ELSE 0 END) as ready, "
    + "SUM(CASE WHEN status = 'ENQUEUED' THEN 1 ELSE 0 END) as queued, "
    + "SUM(CASE WHEN status = 'ENRICHMENT' THEN 1 ELSE 0 END) as enriching, "
    + "SUM(CASE WHEN status = 'ENRICHMENT_TIMEOUT' THEN 1 ELSE 0 END) as enrichmentTimeout, "
    + "SUM(CASE WHEN status = 'EXENSIO_LOADING' THEN 1 ELSE 0 END) as exensioLoading, "
    + "SUM(CASE WHEN status = 'EXENSIO_TIMEOUT' THEN 1 ELSE 0 END) as exensioTimeout, "
    + "SUM(CASE WHEN status = 'FAILED' THEN 1 ELSE 0 END) as failed, "
    + "SUM(CASE WHEN status = 'DONE' THEN 1 ELSE 0 END) as completed, "
    + "SUM(CASE WHEN status = 'CANCELLED' THEN 1 ELSE 0 END) as cancelled "
    + "FROM " + table + " WHERE request_id = ? AND status != 'CANCELLED'";
```

#### 3. CpLogMonitor

**Updated Timeout Detection Logic:**

```java
// Remove the Exensio direct lookup from normal timeout flow
// Instead, directly mark as ENRICHMENT_TIMEOUT when ES/pp_log timeout with NotFound

private void checkEnrichmentTimeout(StageRecord record) {
    boolean isTimedOut = Duration.between(record.updatedAt(), Instant.now())
        .compareTo(elasticsearchProperties.getTimeout()) >= 0;

    if (!isTimedOut) {
        return;  // Not yet timed out
    }

    // Check ES and pp_log results
    boolean esNotFound = checkElasticsearch(record) == NotFound;
    boolean ppLogNotFound = checkPpLog(record) == NotFound;
    boolean noConcreteError = !hasConcreteError(record);

    if (esNotFound && ppLogNotFound && noConcreteError) {
        // All enrichment sources returned NotFound after timeout
        // Mark as ENRICHMENT_TIMEOUT (uncertain enrichment status)
        String diagnosticSummary = buildDiagnosticSummary(record, "ES: NotFound", "pp_log: NotFound");
        refDbService.markEnrichmentTimeout(record, diagnosticSummary);
    } else if (hasConcreteError(record)) {
        // Concrete error found, mark as FAILED
        refDbService.markFailed(record, getErrorMessage(record));
    }
    // If enrichment succeeded (Found from ES or pp_log), continue normal flow
}
```

**Behavioral Change:**

- Previously: Timeout with NotFound → Try Exensio fallback → DONE (with manual_verify) ❌ Misleading + overuses Exensio API
- Now: Timeout with NotFound → ENRICHMENT_TIMEOUT ✅ Honest accounting, no Exensio API overuse

**Note on Exensio Fallback:** The Exensio direct lookup fallback could be implemented as a separate background retry process that periodically checks ENRICHMENT_TIMEOUT records, but it should NOT be part of the normal timeout detection flow to avoid overusing the Exensio API.

#### 4. ExensioLoadMonitor

**Updated Timeout Detection Logic:**

```java
// In processBatch() method
for (ExensioLoadMonitor.PendingUpdate update : pendingUpdates) {
    StageRecord record = getRecordById(update.recordId());

    if (record != null && isTimedOut(record)) {
        // OLD: Mark as FAILED (assumes wafer doesn't exist)
        // updates.add(new BatchResult.RecordUpdate(
        //     update.recordId(),
        //     BatchResult.UpdateType.FAILED, ...
        // ));

        // NEW: Mark as EXENSIO_TIMEOUT (uncertain if wafer exists)
        updates.add(new BatchResult.RecordUpdate(
            update.recordId(),
            BatchResult.UpdateType.EXENSIO_TIMEOUT,
            null, null,
            "Exensio load timeout — wafer not found after "
                + exensioProperties.getTimeoutMinutes()
                + " minutes. May need retry.",
            null, null, null, traceId
        ));
    }
}
```

**New BatchResult.UpdateType Enum Values:**

```java
public enum UpdateType {
    DONE,
    FAILED,
    NOT_FOUND,
    ERROR,
    ENRICHMENT_TIMEOUT,  // NEW
    EXENSIO_TIMEOUT      // NEW
}
```

#### 5. StateAccountingService

**Updated StageStatus Record:**

```java
public record StageStatus(
    long ready,              // pending
    long queued,             // ENQUEUED
    long enriching,          // ENRICHMENT
    long enrichmentTimeout,  // ENRICHMENT_TIMEOUT - NEW
    long exensioLoading,     // EXENSIO_LOADING
    long exensioTimeout,     // EXENSIO_TIMEOUT - NEW
    long failed,             // FAILED
    long completed,          // DONE
    long cancelled           // CANCELLED
) {
    /**
     * Verify accounting balance: sum of all states equals total.
     * This is the core invariant that must hold for honest accounting.
     */
    public boolean isAccountingBalanced(long total) {
        long sum = ready + queued + enriching + enrichmentTimeout
                 + exensioLoading + exensioTimeout
                 + failed + completed + cancelled;
        return sum == total;
    }
}
```

**Updated Query in StateAccountingService:**

```java
String query = "SELECT site, sender_id, MAX(sender_name) AS sender_name, "
    + "COUNT(*) AS total, "
    + "SUM(CASE WHEN status = 'pending' THEN 1 ELSE 0 END) AS pending, "
    + "SUM(CASE WHEN status = 'ENQUEUED' THEN 1 ELSE 0 END) AS enqueued, "
    + "SUM(CASE WHEN status = 'ENRICHMENT' THEN 1 ELSE 0 END) AS enrichment, "
    + "SUM(CASE WHEN status = 'ENRICHMENT_TIMEOUT' THEN 1 ELSE 0 END) AS enrichment_timeout, "
    + "SUM(CASE WHEN status = 'EXENSIO_LOADING' THEN 1 ELSE 0 END) AS exensio_loading, "
    + "SUM(CASE WHEN status = 'EXENSIO_TIMEOUT' THEN 1 ELSE 0 END) AS exensio_timeout, "
    + "SUM(CASE WHEN status = 'FAILED' THEN 1 ELSE 0 END) AS failed, "
    + "SUM(CASE WHEN status = 'DONE' THEN 1 ELSE 0 END) AS done, "
    + "SUM(CASE WHEN status = 'CANCELLED' THEN 1 ELSE 0 END) AS cancelled "
    + "FROM " + table + " WHERE 1=1 ...";
```

### Frontend Components

#### 1. Backend Service (TypeScript DTOs)

**Updated DashboardMetricTotals Interface:**

```typescript
export interface DashboardMetricTotals {
  total: number;
  ready: number;
  queued: number;
  enriching: number;
  enrichmentTimeout: number; // NEW
  exensioLoading: number;
  exensioTimeout: number; // NEW
  completed: number;
  failed: number;
  cancelled: number;
  backlog: number;
  activeSenders: number;
  activeUsers: number;
}
```

#### 2. DashboardComponent

**Updated primaryMetrics() Signal:**

```typescript
primaryMetrics = computed(() => {
  const metrics = this.dashboardMetrics();
  if (!metrics) return [];

  return [
    {
      label: 'Staged',
      count: metrics.ready,
      icon: 'inventory_2',
      color: 'primary',
      description: 'Records staged and ready for processing',
    },
    {
      label: 'Queued for CP',
      count: metrics.queued,
      icon: 'schedule',
      color: 'accent',
      description: 'Records waiting in coverage point enrichment queue',
    },
    {
      label: 'In Enrichment',
      count: metrics.enriching,
      icon: 'data_exploration',
      color: 'accent',
      description: 'Records actively enriching from Elasticsearch and pp_log',
    },
    {
      label: 'Enrichment Timeout', // NEW
      count: metrics.enrichmentTimeout,
      icon: 'schedule',
      color: 'warning',
      description: 'Records where ES and pp_log timed out with no result. Needs manual verification or retry.',
    },
    {
      label: 'Exensio Loading',
      count: metrics.exensioLoading,
      icon: 'cloud_upload',
      color: 'accent',
      description: 'Records being verified in Exensio',
    },
    {
      label: 'Exensio Timeout', // NEW
      count: metrics.exensioTimeout,
      icon: 'schedule',
      color: 'warning',
      description: 'Wafers not found in Exensio after timeout. May appear later or may not exist.',
    },
    {
      label: 'Completed',
      count: metrics.completed,
      icon: 'check_circle',
      color: 'success',
      description: 'Records successfully verified and loaded',
    },
    {
      label: 'Failed',
      count: metrics.failed,
      icon: 'error',
      color: 'error',
      description: 'Records that failed with errors',
    },
    {
      label: 'Cancelled',
      count: metrics.cancelled,
      icon: 'cancel',
      color: 'muted',
      description: 'Records cancelled by user',
    },
  ];
});
```

**Total Card Count:** 9 cards (was 7, now 9 with 2 new timeout cards)

#### 3. StateLegendService

**Updated STATE_DEFINITIONS Array:**

```typescript
export const STATE_DEFINITIONS: StateDefinition[] = [
  // ... existing states ...
  {
    key: 'enrichmentTimeout',
    label: 'Enrichment Timeout',
    icon: 'schedule',
    color: 'warning',
    description:
      'No enrichment confirmation from ES or pp_log after timeout (15 min default). ' +
      'Exensio direct lookup also returned NotFound. ' +
      'Needs manual verification or retry.',
    possibleTransitions: ['completed', 'failed', 'enrichment'],
    notes: [
      'This is NOT a failure - enrichment status is uncertain',
      'Operator should verify if enrichment actually occurred',
      'Can be manually marked as DONE if verified',
      'Can be manually marked as FAILED if confirmed no enrichment',
      'May be automatically retried after cooldown period',
    ],
  },
  {
    key: 'exensioTimeout',
    label: 'Exensio Timeout',
    icon: 'schedule',
    color: 'warning',
    description:
      'Wafer not found in Exensio after timeout (60 min default). ' +
      'May appear later or may not exist. ' +
      'Needs retry or manual verification.',
    possibleTransitions: ['completed', 'failed', 'exensioLoading'],
    notes: [
      'This is NOT a failure - wafer existence is uncertain',
      'Wafer may appear in Exensio later (delayed loading)',
      'Wafer may genuinely not exist in Exensio',
      'Can be manually marked as DONE if wafer verified',
      'Can be manually marked as FAILED if wafer confirmed missing',
      'May be automatically retried after cooldown period',
    ],
  },
];
```

#### 4. StepperComponent

**Updated monitoringStats() Signal:**

```typescript
monitoringStats = computed(() => {
  const session = this.activeSession();
  if (!session?.files) return null;

  const files = session.files;
  const hasFileBreakdown = files.length > 0;

  const stagedCount = hasFileBreakdown ? files.filter((f) => f.status === 'pending').length : 0;
  const queuedCount = hasFileBreakdown ? files.filter((f) => f.status === 'ENQUEUED').length : 0;
  const enrichingCount = hasFileBreakdown ? files.filter((f) => f.status === 'ENRICHMENT').length : 0;
  const enrichmentTimeoutCount = hasFileBreakdown ? files.filter((f) => f.status === 'ENRICHMENT_TIMEOUT').length : 0; // NEW
  const exensioLoadingCount = hasFileBreakdown ? files.filter((f) => f.status === 'EXENSIO_LOADING').length : 0;
  const exensioTimeoutCount = hasFileBreakdown ? files.filter((f) => f.status === 'EXENSIO_TIMEOUT').length : 0; // NEW
  const completedCount = hasFileBreakdown ? files.filter((f) => f.status === 'DONE').length : 0;
  const failedCount = hasFileBreakdown ? files.filter((f) => f.status === 'FAILED').length : 0;

  return {
    staged: stagedCount,
    queued: queuedCount,
    enriching: enrichingCount,
    enrichmentTimeout: enrichmentTimeoutCount, // NEW
    exensioLoading: exensioLoadingCount,
    exensioTimeout: exensioTimeoutCount, // NEW
    completed: completedCount,
    failed: failedCount,
    total: files.length,
  };
});
```

## Data Models

### Database Schema

**SENDER_STAGE Table:**

- **status** (VARCHAR): Existing column, add two new allowed values
  - Existing: `pending`, `ENQUEUED`, `ENRICHMENT`, `EXENSIO_LOADING`, `DONE`, `FAILED`, `CANCELLED`
  - New: `ENRICHMENT_TIMEOUT`, `EXENSIO_TIMEOUT`
- **error_message** (TEXT): Existing column, stores diagnostic information
  - For ENRICHMENT_TIMEOUT: "[Enrichment Timeout] ES: NotFound, pp_log: NotFound, Exensio: NotFound. No definitive enrichment result found after 15 minutes. Needs manual verification or retry."
  - For EXENSIO_TIMEOUT: "[Exensio Timeout] Wafer not found after 60 minutes. May need manual verification or retry."

- **updated_at** (TIMESTAMP): Existing column, used to calculate timeout duration

**No new columns required.** The existing schema supports the new states via the status field and diagnostic messages via error_message.

### DTOs

**Backend (Java):**

```java
// StageStatus.java - Updated record
public record StageStatus(
    long ready,
    long queued,
    long enriching,
    long enrichmentTimeout,  // NEW
    long exensioLoading,
    long exensioTimeout,     // NEW
    long failed,
    long completed,
    long cancelled
) {
    public boolean isAccountingBalanced(long total) {
        return (ready + queued + enriching + enrichmentTimeout
              + exensioLoading + exensioTimeout
              + failed + completed + cancelled) == total;
    }
}

// BatchResult.UpdateType - Updated enum
public enum UpdateType {
    DONE,
    FAILED,
    NOT_FOUND,
    ERROR,
    ENRICHMENT_TIMEOUT,  // NEW
    EXENSIO_TIMEOUT      // NEW
}
```

**Frontend (TypeScript):**

```typescript
// DashboardMetricTotals interface
interface DashboardMetricTotals {
  total: number;
  ready: number;
  queued: number;
  enriching: number;
  enrichmentTimeout: number; // NEW
  exensioLoading: number;
  exensioTimeout: number; // NEW
  completed: number;
  failed: number;
  cancelled: number;
  backlog: number;
  activeSenders: number;
  activeUsers: number;
}

// StateDefinition interface
interface StateDefinition {
  key: string;
  label: string;
  icon: string;
  color: 'primary' | 'accent' | 'warning' | 'error' | 'success' | 'muted';
  description: string;
  possibleTransitions: string[];
  notes?: string[];
}
```

## Correctness Properties

_A property is a characteristic or behavior that should hold true across all valid executions of a system—essentially, a formal statement about what the system should do. Properties serve as the bridge between human-readable specifications and machine-verifiable correctness guarantees._

### Property 1: State Transition to Enrichment Timeout

_For any_ stage record that has timed out with ES NotFound AND pp_log NotFound AND Exensio direct lookup NotFound, the record should transition to ENRICHMENT_TIMEOUT status.

**Validates: Requirements 1.1**

### Property 2: State Transition to Exensio Timeout

_For any_ stage record in EXENSIO_LOADING that has timed out with Exensio NotFound, the record should transition to EXENSIO_TIMEOUT status.

**Validates: Requirements 2.1**

### Property 3: Timeout Records Include Diagnostic Information

_For any_ record transitioning to ENRICHMENT_TIMEOUT or EXENSIO_TIMEOUT, the error_message field should contain diagnostic information about which sources were queried and what responses were received.

**Validates: Requirements 1.2, 2.2, 10.1, 10.2, 10.3, 10.4, 10.5**

### Property 4: SSE Events on Timeout Transitions

_For any_ record that transitions to ENRICHMENT_TIMEOUT or EXENSIO_TIMEOUT, an SSE state change event should be emitted with the correct before/after states and the request_id.

**Validates: Requirements 1.3, 2.3, 7.1, 7.2**

### Property 5: Metrics Segregate Timeout States

_For any_ pipeline query, ENRICHMENT_TIMEOUT records should be counted separately from DONE records, and EXENSIO_TIMEOUT records should be counted separately from FAILED records.

**Validates: Requirements 1.4, 2.4**

### Property 6: Database Accepts Timeout Status Values

_For any_ valid stage record, updating the status to ENRICHMENT_TIMEOUT or EXENSIO_TIMEOUT should succeed without constraint violations.

**Validates: Requirements 3.1, 3.2, 3.4**

### Property 7: Accounting Balance Invariant

_For any_ session's stage records, the sum (ready + queued + enriching + enrichmentTimeout + exensioLoading + exensioTimeout + failed + completed + cancelled) must equal the total record count.

**Validates: Requirements 4.3, 4.4**

### Property 8: State Accounting Queries Include Timeout States

_For any_ StateAccountingService query, the returned StageStatus should include non-null counts for enrichmentTimeout and exensioTimeout fields.

**Validates: Requirements 4.1, 4.2**

### Property 9: Frontend SSE Event Handling

_For any_ ENRICHMENT_TIMEOUT or EXENSIO_TIMEOUT SSE event received by the frontend, the corresponding dashboard card count should increment by the event count.

**Validates: Requirements 7.3, 7.4**

### Property 10: Status Filtering Correctness

_For any_ collection of stage records, filtering by status='ENRICHMENT_TIMEOUT' should return only records with that exact status, and filtering by status='EXENSIO_TIMEOUT' should return only records with that exact status.

**Validates: Requirements 9.4, 9.5**

## Error Handling

### Timeout Detection Errors

**Scenario:** CpLogMonitor or ExensioLoadMonitor fails to detect timeout condition

**Handling:**

- Monitors run continuously with configured polling intervals
- If a monitor crashes, Spring will restart the @Scheduled task
- Timeout checks are idempotent - re-checking already timed-out records is safe
- Logs warning if timeout detection takes longer than expected

**Recovery:**

- Next polling cycle will detect any missed timeouts
- Manual intervention: admin can query for stale records in ENRICHMENT or EXENSIO_LOADING states

### Database Update Failures

**Scenario:** updateRecordStatus() fails when marking timeout state

**Handling:**

- Database operation wrapped in transaction
- On SQLException, log error with record ID and stack trace
- Do not emit SSE event if database update failed
- Retry on next monitor poll cycle

**Recovery:**

- Monitor will re-attempt timeout marking on next cycle
- Admin can manually update status via AdminDebugController

### SSE Event Emission Failures

**Scenario:** StateAggregationBatcher fails to emit SSE event

**Handling:**

- SSE emission is non-blocking and fire-and-forget
- Failure to emit SSE does not fail the status update transaction
- Log warning if SSE emission fails
- Frontend will eventually sync on next full metrics fetch

**Recovery:**

- Frontend periodically fetches full dashboard metrics (fallback to polling)
- SSE is an optimization, not required for correctness

### Frontend Data Desynchronization

**Scenario:** Frontend displays incorrect timeout counts due to missed SSE events

**Handling:**

- Frontend has periodic full metrics refresh (every 30 seconds)
- On SSE reconnection, frontend refetches full metrics
- Dashboard component is stateless - always renders current metrics signal value

**Recovery:**

- Automatic via next metrics fetch cycle
- User can manually refresh page
- No persistent incorrect state

### Constraint Violation on New Status Values

**Scenario:** Database rejects new ENRICHMENT_TIMEOUT or EXENSIO_TIMEOUT values

**Handling:**

- Pre-deployment validation: test migration in staging environment
- If constraint exists but wasn't updated, status update will fail with SQLException
- Log detailed error including constraint name and attempted value
- Monitor will continue processing other records

**Recovery:**

- Deploy corrected database migration
- Re-run status updates for affected records

### Backward Compatibility Issues

**Scenario:** Old frontend or old backend doesn't recognize new states

**Handling:**

- **Old backend + New frontend:** Frontend gracefully handles zero counts for unknown fields
- **New backend + Old frontend:** Backend returns new fields, old frontend ignores them (no error)
- **Old queries:** Records with new states won't match old status filters but won't cause errors

**Recovery:**

- Coordinate deployment: backend first, then frontend
- Monitor logs for any unexpected errors after deployment
- Rollback capability: new states only affect future records, existing records unchanged

## Testing Strategy

### Dual Testing Approach

This feature requires both **unit tests** and **property-based tests** to ensure comprehensive correctness:

- **Unit tests** verify specific examples, edge cases, and integration points
- **Property-based tests** verify universal properties hold across all inputs
- Both approaches are complementary and necessary for thorough validation

### Property-Based Testing

**Framework:** jqwik for Java backend tests

**Configuration:**

- Minimum 100 iterations per property test (due to randomization)
- Each property test references its design document property number
- Tag format: `@Tag("Feature: pipeline-timeout-states, Property {N}: {description}")`

**Property Test Coverage:**

1. **Property 1-2: State Transitions**
   - Generate random stage records in ENRICHMENT state
   - Simulate timeout conditions (ES NotFound, pp_log NotFound, Exensio NotFound)
   - Verify status transitions to ENRICHMENT_TIMEOUT
   - Generate random records in EXENSIO_LOADING state
   - Simulate Exensio timeout (NotFound after 60 min)
   - Verify status transitions to EXENSIO_TIMEOUT

2. **Property 3: Diagnostic Information**
   - Generate random timeout scenarios
   - Verify error_message contains expected diagnostic keywords
   - Verify all queried sources are mentioned in diagnostic

3. **Property 4: SSE Event Emission**
   - Generate random timeout transitions
   - Capture SSE events via test spy
   - Verify event contains correct before/after states and request_id

4. **Property 7: Accounting Balance Invariant**
   - Generate random distributions of records across all 9 states
   - Query StateAccountingService for counts
   - Verify sum of all state counts equals total record count
   - This is the critical invariant that validates honest accounting

5. **Property 8: Query Inclusion**
   - Generate random record sets with timeout states
   - Query fetchStatusesFor()
   - Verify enrichmentTimeout and exensioTimeout fields are populated

6. **Property 9-10: Filtering and Event Handling**
   - Generate random record collections
   - Test filtering by timeout status values
   - Verify only matching records are returned

### Unit Testing

**Backend Unit Tests:**

1. **RefDbService.markEnrichmentTimeout()**
   - Test that status updates to ENRICHMENT_TIMEOUT
   - Test that error_message contains diagnostic summary
   - Test that SSE event is emitted via StateAggregationBatcher
   - Test that log entry is created

2. **RefDbService.markExensioTimeout()**
   - Test that status updates to EXENSIO_TIMEOUT
   - Test that error_message contains timeout reason
   - Test that SSE event is emitted
   - Test that configured timeout duration appears in message

3. **CpLogMonitor.tryExensioDirectLookup()**
   - Test NotFound case → calls markEnrichmentTimeout() (not markDoneManualVerify())
   - Test Found case → calls markDoneFromExensio() (existing behavior preserved)
   - Test Error case → calls markEnrichmentTimeout()

4. **ExensioLoadMonitor timeout handling**
   - Test that timed-out records generate EXENSIO_TIMEOUT update type
   - Test that timeout duration is calculated correctly
   - Test that BatchResult contains EXENSIO_TIMEOUT UpdateType

5. **StateAccountingService balance validation**
   - Test isAccountingBalanced() with records distributed across all 9 states
   - Test that sum includes enrichmentTimeout and exensioTimeout
   - Test discrepancy detection when sum != total

6. **Database Migration**
   - Test that ENRICHMENT_TIMEOUT status value can be inserted
   - Test that EXENSIO_TIMEOUT status value can be inserted
   - Test that constraint allows both new values (if constraint exists)
   - Test that existing status values still work

**Frontend Unit Tests:**

1. **DashboardComponent metric cards**
   - Test that 9 cards are rendered (including 2 timeout cards)
   - Test that enrichmentTimeout count is displayed correctly
   - Test that exensioTimeout count is displayed correctly
   - Test that warning color is applied to timeout cards
   - Test that schedule icon is used for timeout cards

2. **StateLegendService definitions**
   - Test that STATE_DEFINITIONS includes enrichmentTimeout
   - Test that STATE_DEFINITIONS includes exensioTimeout
   - Test that descriptions are present and non-empty
   - Test that possible transitions are defined

3. **StepperComponent monitoring stats**
   - Test that enrichmentTimeout count is calculated from file statuses
   - Test that exensioTimeout count is calculated from file statuses
   - Test that filtering by timeout status returns correct records

4. **BackendService SSE handling**
   - Test that SSE events with timeout state changes update metrics signal
   - Test that enrichmentTimeout increments on ENRICHMENT → ENRICHMENT_TIMEOUT event
   - Test that exensioTimeout increments on EXENSIO_LOADING → EXENSIO_TIMEOUT event

5. **Backward compatibility**
   - Test that old frontend gracefully handles new fields (mock old interface)
   - Test that new frontend handles missing new fields (null/undefined)

### Integration Testing

1. **End-to-end timeout flow**
   - Stage record → ENRICHMENT → timeout with all NotFound → ENRICHMENT_TIMEOUT
   - Verify database record status
   - Verify SSE event emitted
   - Verify dashboard card count incremented

2. **Accounting balance across states**
   - Create records in all 9 states
   - Query StateAccountingService
   - Verify isAccountingBalanced() returns true
   - Verify DashboardMetricTotals sums correctly

3. **Database migration reversibility**
   - Apply migration in test database
   - Insert timeout status records
   - Rollback migration
   - Verify no constraint violations on existing data
