# Backlog Metric Fix - Dashboard Status Calculation

## Problem

The dashboard "backlog" metric was incorrectly including records in the `COMPLETED_MANUAL_VERIFICATION_REQUIRED` status, which represents a **final/completed state**, not an active processing state.

**Incorrect logic:**

```java
return queuedForCp + elasticsearchMonitoring + cpTimeout
    + completedManualVerification;  // ❌ WRONG - This is a final state
```

## Root Cause

The backlog calculation conflated two concepts:

1. **Records still being processed** (true backlog)
2. **Records needing attention** (which includes completed-but-unverified records)

Records in `COMPLETED_MANUAL_VERIFICATION_REQUIRED` status have **already finished processing** - they just need a human to review and confirm. This is a **completion state**, not an active processing state.

## Correct Definition

**Backlog should only include records actively being processed or waiting to be processed:**

### Active/Processing States (Backlog):

- `STAGED` - Staged and waiting for CP dispatch
- `QUEUED_FOR_CP` - Queued for CP processing
- `ELASTICSEARCH_MONITORING` - Waiting for CP completion verification
- `EXENSIO_MONITORING` - Waiting for Exensio load verification
- `CP_TIMEOUT` - Timed out but retryable (uncertain state)

### Final/Completion States (Not Backlog):

- `COMPLETED` - Successfully completed
- `COMPLETED_MANUAL_VERIFICATION_REQUIRED` - Completed, awaiting manual review
- `CP_FAILED` - Failed during CP processing
- `LOAD_FAILED` - Failed during Exensio load
- `CANCELLED` - Cancelled by user

## Solution

Updated `StageStatus.backlog()` method in:
`backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/stage/StageStatus.java`

**New implementation:**

```java
public long backlog() {
    return stagedToRefdb + queuedForCp + elasticsearchMonitoring
        + exensioMonitoring + cpTimeout;
}
```

**Changes:**

1. ✅ Added `stagedToRefdb` (STAGED records) - These are waiting to start processing
2. ✅ Kept `queuedForCp` - Records queued for CP
3. ✅ Kept `elasticsearchMonitoring` - Records being monitored for CP completion
4. ✅ Added `exensioMonitoring` - Records being monitored for Exensio load
5. ✅ Kept `cpTimeout` - Timed out records (uncertain, may be retried)
6. ❌ Removed `completedManualVerification` - This is a completion state, not backlog

## Impact

### Before

Backlog included records that were already done processing, inflating the backlog metric and potentially triggering false alerts.

### After

Backlog accurately reflects only records that are currently being processed or waiting to be processed. This provides:

- More accurate monitoring metrics
- Better alerting thresholds (fewer false positives)
- Clearer dashboard visualization
- Correct capacity planning

## Status Mapping

| Status                                 | Includes in Backlog? | Reason                                  |
| -------------------------------------- | -------------------- | --------------------------------------- |
| STAGED                                 | ✅ Yes               | Waiting to be dispatched for processing |
| QUEUED_FOR_CP                          | ✅ Yes               | Actively queued for CP processing       |
| ELASTICSEARCH_MONITORING               | ✅ Yes               | Actively being monitored for completion |
| EXENSIO_MONITORING                     | ✅ Yes               | Actively being monitored for load       |
| CP_TIMEOUT                             | ✅ Yes               | Uncertain, may be retried or failed     |
| COMPLETED_MANUAL_VERIFICATION_REQUIRED | ❌ No                | Already completed, just needs review    |
| COMPLETED                              | ❌ No                | Successfully completed                  |
| CP_FAILED                              | ❌ No                | Failed (final state)                    |
| LOAD_FAILED                            | ❌ No                | Failed (final state)                    |
| CANCELLED                              | ❌ No                | Cancelled (final state)                 |

## Files Modified

1. `backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/stage/StageStatus.java`
   - Updated `backlog()` method calculation
   - Enhanced JavaDoc comments explaining the definition

## Backward Compatibility

- No API changes
- No database changes
- Existing monitoring/alerting based on backlog metrics will be more accurate
- May require recalibration of alert thresholds if they were based on the inflated backlog numbers

## Testing

After deployment, verify:

1. Records in `COMPLETED_MANUAL_VERIFICATION_REQUIRED` no longer count toward backlog
2. Dashboard backlog metric decreases accordingly
3. Alert thresholds may need adjustment if previously calibrated to inflated backlog numbers
4. Backlog metric accurately reflects only active/processing records

## Example Scenario

**Before fix:**

- 100 staged records (waiting for CP)
- 50 in CP timeout (uncertain)
- 200 completed and awaiting manual verification
- Backlog = 100 + 0 + 50 + 200 = **350** ❌ (inflated)

**After fix:**

- 100 staged records (waiting for CP)
- 50 in CP timeout (uncertain)
- 200 completed and awaiting manual verification
- Backlog = 100 + 0 + 50 = **150** ✅ (accurate)

The 200 completed records are no longer counted, reducing false urgency and enabling better capacity planning.
