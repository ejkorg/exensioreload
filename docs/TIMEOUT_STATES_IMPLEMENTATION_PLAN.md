# Timeout States Implementation Plan

**Date:** July 4, 2026  
**Goal:** Add explicit timeout states for honest accounting of uncertain records

---

## Overview: Two Timeout Scenarios

### 1. Enrichment Timeout (ES + pp_log)

**Current:** Marks as DONE with manual_verify  
**Problem:** Conflates uncertainty with success  
**Solution:** Add `ENRICHMENT_TIMEOUT` state

### 2. Exensio Loading Timeout

**Current:** Marks as FAILED  
**Problem:** Conflates uncertainty with failure (wafer may exist, just not found yet)  
**Solution:** Add `EXENSIO_TIMEOUT` state

---

## Proposed New States

### State: ENRICHMENT_TIMEOUT

**When:** ES and pp_log both return NotFound after timeout (15 min default), Exensio direct lookup also returns NotFound

**Meaning:** "We don't know if enrichment happened - need manual verification"

**Possible Outcomes:**

- Manual operator marks as DONE (verified enriched)
- Manual operator marks as FAILED (confirmed not enriched)
- Auto-retry after X hours → transitions to ENRICHMENT or stays in timeout
- Ages out after N days → auto-mark as DONE with note

**Current vs Proposed:**

```
Current:
ENRICHMENT [15 min, ES+pp_log NotFound]
  → Try Exensio
  → NotFound → markDoneManualVerify() ❌ Misleading

Proposed:
ENRICHMENT [15 min, ES+pp_log NotFound]
  → Try Exensio
  → NotFound → ENRICHMENT_TIMEOUT ✅ Honest
```

---

### State: EXENSIO_TIMEOUT

**When:** Exensio API returns NotFound after timeout (5 min default)

**Meaning:** "Wafer not found in Exensio yet - may appear later or may not exist"

**Possible Outcomes:**

- Auto-retry after X hours → Found → DONE
- Auto-retry after X hours → Still NotFound → stays EXENSIO_TIMEOUT
- Manual operator marks as DONE (wafer confirmed exists)
- Manual operator marks as FAILED (wafer confirmed doesn't exist)
- Ages out after N days → auto-mark as FAILED with note

**Current vs Proposed:**

```
Current:
EXENSIO_LOADING [5 min, NotFound]
  → Mark as FAILED ❌ Assumes doesn't exist

Proposed:
EXENSIO_LOADING [5 min, NotFound]
  → EXENSIO_TIMEOUT ✅ Uncertain, not failed
```

---

## Implementation Plan

### Phase 1: Backend Changes

#### 1.1 Add New Status Values

**File:** Database schema (via migration)

**Add two new status values:**

- `'ENRICHMENT_TIMEOUT'`
- `'EXENSIO_TIMEOUT'`

**Migration SQL:**

```sql
-- Verify status column accepts new values (should already support VARCHAR)
-- If there's a CHECK constraint, update it:
ALTER TABLE SENDER_STAGE DROP CONSTRAINT chk_sender_stage_status;
ALTER TABLE SENDER_STAGE ADD CONSTRAINT chk_sender_stage_status
  CHECK (status IN (
    'pending',
    'ENQUEUED',
    'ENRICHMENT',
    'ENRICHMENT_TIMEOUT',     -- NEW
    'EXENSIO_LOADING',
    'EXENSIO_TIMEOUT',         -- NEW
    'DONE',
    'FAILED',
    'CANCELLED'
  ));
```

#### 1.2 Add RefDbService Methods

**File:** `backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/service/RefDbService.java`

**Add methods:**

```java
/**
 * Mark record with enrichment timeout - no ES/pp_log/Exensio results found.
 * Record needs manual verification or auto-retry.
 */
public void markEnrichmentTimeout(StageRecord record, String diagnosticSummary) {
    String errorMessage = "[Enrichment Timeout] " + diagnosticSummary
        + " No definitive enrichment result found. Needs manual verification or retry.";

    updateRecordStatus(record.id(), "ENRICHMENT_TIMEOUT", errorMessage);

    // Emit SSE update
    stateAggregationBatcher.recordStateChange(
        record.requestId(),
        "ENRICHMENT",
        "ENRICHMENT_TIMEOUT",
        1,
        1
    );

    log.info("Marked record {} as ENRICHMENT_TIMEOUT: {}", record.id(), diagnosticSummary);
}

/**
 * Mark record with Exensio timeout - wafer not found in Exensio after timeout.
 * Wafer may appear later or may not exist.
 */
public void markExensioTimeout(StageRecord record, String reason) {
    String errorMessage = "[Exensio Timeout] " + reason
        + " Wafer not found after " + exensioProperties.getTimeoutMinutes()
        + " minutes. May need manual verification or retry.";

    updateRecordStatus(record.id(), "EXENSIO_TIMEOUT", errorMessage);

    // Emit SSE update
    stateAggregationBatcher.recordStateChange(
        record.requestId(),
        "EXENSIO_LOADING",
        "EXENSIO_TIMEOUT",
        1,
        1
    );

    log.info("Marked record {} as EXENSIO_TIMEOUT: {}", record.id(), reason);
}
```

#### 1.3 Update CpLogMonitor

**File:** `backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/service/CpLogMonitor.java`

**Method:** `tryExensioDirectLookup()`

**Change:**

```java
// Before:
case ExensioLotWaferResult.NotFound notFound -> {
    refDbService.markDoneManualVerify(record, ...);  // ❌ OLD
}

// After:
case ExensioLotWaferResult.NotFound notFound -> {
    refDbService.markEnrichmentTimeout(record, diagnosticSummary);  // ✅ NEW
}
```

#### 1.4 Update ExensioLoadMonitor

**File:** `backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/service/ExensioLoadMonitor.java`

**Lines:** 368-370, 582-584

**Change:**

```java
// Before:
if (record != null && isTimedOut(record)) {
    updates.add(new BatchResult.RecordUpdate(
        update.recordId(),
        BatchResult.UpdateType.FAILED,  // ❌ OLD - Assumes doesn't exist
        null, null,
        "Exensio load timeout — wafer not found after " + props.getTimeoutMinutes() + " minutes",
        null, null, null, traceId
    ));
}

// After:
if (record != null && isTimedOut(record)) {
    updates.add(new BatchResult.RecordUpdate(
        update.recordId(),
        BatchResult.UpdateType.EXENSIO_TIMEOUT,  // ✅ NEW - Uncertain
        null, null,
        "Exensio load timeout — wafer not found after " + props.getTimeoutMinutes() + " minutes. May need retry.",
        null, null, null, traceId
    ));
}
```

**Also need to handle new UpdateType:**

```java
// In BatchResult enum:
public enum UpdateType {
    DONE,
    FAILED,
    NOT_FOUND,
    ERROR,
    ENRICHMENT_TIMEOUT,  // NEW
    EXENSIO_TIMEOUT      // NEW
}
```

#### 1.5 Update StateAccountingService

**File:** `backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/service/StateAccountingService.java`

**Add queries for new states:**

```java
// Add to fetchStatuses():
long enrichmentTimeout = statusResult.enrichmentTimeout();
long exensioTimeout = statusResult.exensioTimeout();

// Update StageStatus record:
public record StageStatus(
    long ready,           // pending
    long queued,          // ENQUEUED
    long enriching,       // ENRICHMENT
    long enrichmentTimeout,  // ENRICHMENT_TIMEOUT - NEW
    long exensioLoading,  // EXENSIO_LOADING
    long exensioTimeout,  // EXENSIO_TIMEOUT - NEW
    long failed,          // FAILED
    long completed,       // DONE
    long cancelled        // CANCELLED
) {
    // Accounting validation
    public boolean isAccountingBalanced(long total) {
        return ready + queued + enriching + enrichmentTimeout
             + exensioLoading + exensioTimeout
             + failed + completed + cancelled == total;
    }
}
```

#### 1.6 Update RefDbService Query

**File:** `backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/service/RefDbService.java`

**Method:** `fetchStatuses()`

**Update SQL:**

```sql
SELECT
  SUM(CASE WHEN status = 'pending' THEN 1 ELSE 0 END) as ready,
  SUM(CASE WHEN status = 'ENQUEUED' THEN 1 ELSE 0 END) as queued,
  SUM(CASE WHEN status = 'ENRICHMENT' THEN 1 ELSE 0 END) as enriching,
  SUM(CASE WHEN status = 'ENRICHMENT_TIMEOUT' THEN 1 ELSE 0 END) as enrichmentTimeout,  -- NEW
  SUM(CASE WHEN status = 'EXENSIO_LOADING' THEN 1 ELSE 0 END) as exensioLoading,
  SUM(CASE WHEN status = 'EXENSIO_TIMEOUT' THEN 1 ELSE 0 END) as exensioTimeout,        -- NEW
  SUM(CASE WHEN status = 'FAILED' THEN 1 ELSE 0 END) as failed,
  SUM(CASE WHEN status = 'DONE' THEN 1 ELSE 0 END) as completed,
  SUM(CASE WHEN status = 'CANCELLED' THEN 1 ELSE 0 END) as cancelled
FROM SENDER_STAGE
WHERE request_id = ? AND status != 'CANCELLED'
```

---

### Phase 2: Frontend Changes

#### 2.1 Update Backend Service DTOs

**File:** `frontend/src/app/api/backend.service.ts`

**Add fields to DashboardMetricTotals:**

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

#### 2.2 Add Dashboard Cards

**File:** `frontend/src/app/dashboard/dashboard.component.ts`

**Add to primaryMetrics():**

```typescript
primaryMetrics = computed(() => {
  const metrics = this.dashboardMetrics();
  if (!metrics) return [];

  return [
    { label: 'Staged', count: metrics.ready, ... },
    { label: 'Queued for CP', count: metrics.queued, ... },
    { label: 'In Enrichment', count: metrics.enriching, ... },
    {
      label: 'Enrichment Timeout',         // NEW
      count: metrics.enrichmentTimeout,
      icon: 'schedule',
      color: 'warning',
      description: 'Records where ES and pp_log timed out with no result'
    },
    { label: 'Exensio Loading', count: metrics.exensioLoading, ... },
    {
      label: 'Exensio Timeout',            // NEW
      count: metrics.exensioTimeout,
      icon: 'schedule',
      color: 'warning',
      description: 'Wafers not found in Exensio after timeout'
    },
    { label: 'Completed', count: metrics.completed, ... },
    { label: 'Failed', count: metrics.failed, ... },
    { label: 'Cancelled', count: metrics.cancelled, ... },
  ];
});
```

#### 2.3 Update State Legend

**File:** `frontend/src/app/dashboard/state-legend.service.ts`

**Add timeout states:**

```typescript
export const STATE_DEFINITIONS: StateDefinition[] = [
  // ... existing states ...
  {
    key: 'enrichmentTimeout',
    label: 'Enrichment Timeout',
    icon: 'schedule',
    color: 'warning',
    description: 'No enrichment confirmation from ES or pp_log after timeout. Needs manual verification or retry.',
    possibleTransitions: ['completed', 'failed', 'enrichment'], // Can retry or be manually resolved
  },
  {
    key: 'exensioTimeout',
    label: 'Exensio Timeout',
    icon: 'schedule',
    color: 'warning',
    description:
      'Wafer not found in Exensio after timeout. May appear later or may not exist. Needs retry or manual check.',
    possibleTransitions: ['completed', 'failed', 'exensioLoading'], // Can retry or be manually resolved
  },
];
```

#### 2.4 Update Monitoring Stats Component

**File:** `frontend/src/app/shared/components/monitoring-stats.component.ts`

**Add cards for timeout states** (following same pattern as other cards)

#### 2.5 Update Stepper Component

**File:** `frontend/src/app/stepper/stepper.component.ts`

**Add to monitoringStats():**

```typescript
const enrichmentTimeoutCount = hasFileBreakdown ? files.filter((f) => f.status === 'ENRICHMENT_TIMEOUT').length : 0;

const exensioTimeoutCount = hasFileBreakdown ? files.filter((f) => f.status === 'EXENSIO_TIMEOUT').length : 0;

return {
  // ... existing fields ...
  enrichmentTimeout: enrichmentTimeoutCount,
  exensioTimeout: exensioTimeoutCount,
};
```

---

### Phase 3: Operator Actions

#### 3.1 Manual Resolution Endpoints

**Add admin endpoints for manual resolution:**

```java
// RefDbService.java
public void manuallyMarkDone(long recordId, String operatorUsername, String notes) {
    // Transition ENRICHMENT_TIMEOUT or EXENSIO_TIMEOUT → DONE
    // Log operator action in audit trail
}

public void manuallyMarkFailed(long recordId, String operatorUsername, String reason) {
    // Transition ENRICHMENT_TIMEOUT or EXENSIO_TIMEOUT → FAILED
    // Log operator action in audit trail
}

public void retryTimeoutRecord(long recordId) {
    // Transition ENRICHMENT_TIMEOUT → ENRICHMENT (retry ES/pp_log)
    // Transition EXENSIO_TIMEOUT → EXENSIO_LOADING (retry Exensio)
}
```

#### 3.2 UI for Manual Actions

**Add to metric detail sidebar:**

```html
<div *ngIf="record.status === 'ENRICHMENT_TIMEOUT' || record.status === 'EXENSIO_TIMEOUT'">
  <h4>Timeout Record - Manual Action Required</h4>
  <button (click)="retryRecord(record)">Retry</button>
  <button (click)="markAsDone(record)">Mark as Done</button>
  <button (click)="markAsFailed(record)">Mark as Failed</button>
</div>
```

---

### Phase 4: Auto-Retry Logic (Optional Enhancement)

#### 4.1 Scheduled Retry Job

**Create new service:**

```java
@Service
public class TimeoutRetryService {

    @Scheduled(fixedDelay = 3600000)  // Every hour
    public void retryTimeoutRecords() {
        // Find ENRICHMENT_TIMEOUT records older than 1 hour
        List<StageRecord> enrichmentTimeouts = refDbService.listRecords(
            null, null, "ENRICHMENT_TIMEOUT", 100
        );

        for (StageRecord record : enrichmentTimeouts) {
            if (shouldRetry(record)) {
                // Transition back to ENRICHMENT for retry
                refDbService.updateStatus(record.id(), "ENRICHMENT");
                log.info("Auto-retrying enrichment for record {}", record.id());
            }
        }

        // Find EXENSIO_TIMEOUT records older than 1 hour
        List<StageRecord> exensioTimeouts = refDbService.listRecords(
            null, null, "EXENSIO_TIMEOUT", 100
        );

        for (StageRecord record : exensioTimeouts) {
            if (shouldRetry(record)) {
                // Transition back to EXENSIO_LOADING for retry
                refDbService.updateStatus(record.id(), "EXENSIO_LOADING");
                log.info("Auto-retrying Exensio for record {}", record.id());
            }
        }
    }

    private boolean shouldRetry(StageRecord record) {
        // Logic: retry if < 3 attempts and last attempt was > 1 hour ago
        return getRetryCount(record) < 3
            && record.updatedAt().plus(Duration.ofHours(1)).isBefore(Instant.now());
    }
}
```

---

## Benefits Summary

### 1. Honest Accounting ✅

```
Before:
- Completed: 1000 (includes 50 uncertain)
- Failed: 20

After:
- Completed: 950 (only confirmed)
- Failed: 20 (only confirmed failures)
- Enrichment Timeout: 30 (uncertain - needs review)
- Exensio Timeout: 20 (uncertain - wafer may exist)
```

### 2. Clear Operator Workflow ✅

- Dashboard shows timeout cards prominently
- Operators know exactly which records need attention
- Can manually resolve or trigger retry
- Audit trail of all manual actions

### 3. Better State Machine ✅

```
States reflect reality:
- DONE = verified successful
- FAILED = verified error
- ENRICHMENT_TIMEOUT = uncertain enrichment
- EXENSIO_TIMEOUT = uncertain wafer existence
- No conflation of success/failure/uncertainty
```

### 4. Auto-Retry Capability ✅

- Timeout records can be automatically retried
- Prevents premature failure marking
- Gives system multiple chances to resolve

---

## Implementation Effort

| Phase                       | Effort   | Priority           |
| --------------------------- | -------- | ------------------ |
| **Phase 1: Backend**        | 2-3 days | HIGH               |
| **Phase 2: Frontend**       | 2 days   | HIGH               |
| **Phase 3: Manual Actions** | 1 day    | MEDIUM             |
| **Phase 4: Auto-Retry**     | 1-2 days | LOW (nice-to-have) |

**Total:** ~5-8 days for full implementation

---

## Deployment Strategy

### Step 1: Schema Migration

- Add new status values to database
- Test constraint validation

### Step 2: Backend Deploy

- Deploy new RefDbService methods
- Update CpLogMonitor and ExensioLoadMonitor
- Verify existing records unaffected

### Step 3: Frontend Deploy

- Add timeout cards to dashboard
- Update state legend
- Test SSE updates

### Step 4: Monitoring

- Watch for records entering timeout states
- Verify operators can manually resolve
- Monitor auto-retry effectiveness (if implemented)

---

## Backward Compatibility

✅ **Fully backward compatible**

- Existing DONE/FAILED records unchanged
- New timeout states only for future records
- Old frontend still works (just doesn't show new states)
- No breaking API changes

---

## Recommendation

**Implement both timeout states together:**

- ENRICHMENT_TIMEOUT (no ES/pp_log/Exensio results)
- EXENSIO_TIMEOUT (wafer not found in Exensio)

**Benefits:**

- Consistent state machine logic
- Honest accounting across entire pipeline
- Clear operator workflows
- Foundation for auto-retry enhancements

**Next Steps:**

1. Review and approve this plan
2. Create database migration scripts
3. Implement backend changes (Phase 1)
4. Implement frontend changes (Phase 2)
5. Test in staging environment
6. Deploy to production
7. Monitor effectiveness

---

**Status:** Ready for implementation approval
