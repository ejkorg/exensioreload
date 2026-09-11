# Completed Status Schema Tracking Enhancement

## Problem

The current system doesn't distinguish **where** records were completed:

- **COMPLETED** (with schema PRODUCTION)
- **COMPLETED** (with schema SANDBOX)
- **COMPLETED_MANUAL_VERIFICATION_REQUIRED** (awaiting review, not actually completed)

This information is important for:

1. **Analytics**: Understand which schema (production vs sandbox/dev) is receiving data
2. **Audit Trail**: Track where records were ultimately loaded
3. **Troubleshooting**: Identify if records are going to unexpected schemas
4. **Capacity Planning**: Differentiate production vs non-production throughput

## Current State

### Data Available

The schema information **is already being captured** at various points:

1. **CP Log Detection**: `CpLogResult.Success` includes `outputTarget` (PRODUCTION/SANDBOX)
2. **Exensio Lookup**: `ExensioLotWaferResult.Found` includes `schema` (PRODUCTION/SANDBOX/FOUND)
3. **Lot Verification**: `LotVerificationResult` includes `schema` field
4. **Batch Results**: `BatchResult.RecordUpdate` includes `schema` field

### Problem: Schema Not Persisted

- Schema information is used during processing but **not stored in SENDER_STAGE** table
- `markCompletedFromExensio()` method doesn't accept or save schema parameter
- Dashboard and queries have no way to differentiate where records completed

## Solution

### 1. Add Database Columns

Create new Liquibase migration to add schema tracking columns to SENDER_STAGE:

```sql
ALTER TABLE SENDER_STAGE ADD (
    exensio_schema VARCHAR(50),          -- Schema where loaded (PRODUCTION, SANDBOX, FOUND, null)
    cp_output_schema VARCHAR(50)         -- CP output schema detected (PRODUCTION, SANDBOX, UNKNOWN)
);
```

### 2. Update Method Signatures

**Before:**

```java
public void markCompletedFromExensio(StageRecord record, Long exensioWaferKey, long exensioPgKey)
```

**After:**

```java
public void markCompletedFromExensio(StageRecord record, Long exensioWaferKey, long exensioPgKey, String schema)
```

### 3. Update Call Sites

**In CpLogMonitor.java** (around line 601):

```java
// Before
refDbService.markCompletedFromExensio(record, found.waferKey(), found.pgKey());

// After
refDbService.markCompletedFromExensio(record, found.waferKey(), found.pgKey(), found.schema());
```

**In ExensioLoadMonitor.java** - Any other completion paths need similar updates.

### 4. Update Dashboard Status Values

Currently the system shows three completion states:

1. `COMPLETED` - Successfully completed (schema unknown)
2. `COMPLETED_MANUAL_VERIFICATION_REQUIRED` - Completed but needs verification
3. `CP_FAILED` / `LOAD_FAILED` - Failed

**Proposed New Schema-Aware States:**

1. `COMPLETED_PRODUCTION` - Completed in PRODUCTION schema
2. `COMPLETED_SANDBOX` - Completed in SANDBOX schema
3. `COMPLETED_UNKNOWN` - Completed, schema unknown (fallback)
4. `COMPLETED_MANUAL_VERIFICATION_REQUIRED` - Completed but needs verification
5. `CP_FAILED` / `LOAD_FAILED` - Failed

OR maintain backward compatibility:

1. `COMPLETED` with new `exensio_schema` column to track destination
2. `COMPLETED_MANUAL_VERIFICATION_REQUIRED` with `exensio_schema` indicating where it would have gone

**Recommendation:** Keep `COMPLETED` status but track schema in columns for backward compatibility.

### 5. Update StageStatus Record

Add new fields to track schema-based completion:

```java
public record StageStatus(
    // ... existing fields ...
    long completedProduction,              // COMPLETED with schema=PRODUCTION
    long completedSandbox,                 // COMPLETED with schema=SANDBOX
    // Keep for backward compatibility:
    long completed                         // Total COMPLETED (any schema)
) {}
```

### 6. Update Dashboard Queries

Modify SQL queries to track completion by schema:

**Current:**

```sql
SELECT COUNT(*) as completed FROM SENDER_STAGE WHERE status = 'COMPLETED'
```

**Enhanced:**

```sql
SELECT
    COUNT(*) as completed,
    SUM(CASE WHEN exensio_schema = 'PRODUCTION' THEN 1 ELSE 0 END) as completed_production,
    SUM(CASE WHEN exensio_schema = 'SANDBOX' THEN 1 ELSE 0 END) as completed_sandbox
FROM SENDER_STAGE WHERE status = 'COMPLETED'
```

## Benefits

1. **Clearer Status**: Easy to see which records completed to production vs sandbox
2. **Better Metrics**: Dashboard can distinguish production vs non-production throughput
3. **Audit Trail**: Complete record of where each file ended up
4. **Troubleshooting**: Quickly identify if records are going to wrong schema
5. **Backward Compatible**: Existing `COMPLETED` status works, schema is optional detail

## Implementation Steps

1. **Phase 1 (Immediate):** Add columns to SENDER_STAGE table
2. **Phase 2 (Immediate):** Update method signatures to capture schema
3. **Phase 3 (Next):** Update dashboard queries to report by schema
4. **Phase 4 (Optional):** Add schema-specific status values or UI indicators

## Example Scenarios

### Scenario 1: Normal Production Load

```
Record: Lot=LOT001, Wafer=W06
- Stages: STAGED → CP_MONITORING → EXENSIO_MONITORING → COMPLETED
- Final: status=COMPLETED, exensio_schema=PRODUCTION
```

### Scenario 2: Sandbox Development

```
Record: Lot=TEST001, Wafer=W01
- Stages: STAGED → CP_MONITORING → EXENSIO_MONITORING → COMPLETED
- Final: status=COMPLETED, exensio_schema=SANDBOX
```

### Scenario 3: Manual Verification Required

```
Record: Lot=LOT002, Wafer=W03
- Stages: STAGED → CP_MONITORING → CP_TIMEOUT → COMPLETED_MANUAL_VERIFICATION_REQUIRED
- Final: status=COMPLETED_MANUAL_VERIFICATION_REQUIRED (no exensio_schema, needs human review)
```

## Related Considerations

- **CP Output Path/Target**: Already tracked in `cp_output_path` and `cp_output_target` columns
- **Backward Compatibility**: New columns are nullable, existing logic unaffected
- **Query Performance**: Consider indexes on `(status, exensio_schema)` for dashboard queries
- **Alert Thresholds**: May need schema-specific alerting (e.g., production vs sandbox thresholds)

## Files to Modify

1. Create new Liquibase migration for schema columns
2. `RefDbService.java` - Update `markCompletedFromExensio()` signature and implementation
3. `CpLogMonitor.java` - Pass schema when marking completed
4. `ExensioLoadMonitor.java` - Pass schema in any completion paths
5. `StageStatus.java` - Add schema-specific completion counts
6. `DashboardController.java` - Update queries to include schema
7. `StageRecordView.java` - Add `exensioSchema` field for API response

## Testing

After implementation:

1. Stage records that complete to PRODUCTION
2. Stage records that complete to SANDBOX
3. Verify `exensio_schema` column is populated correctly
4. Verify dashboard accurately reports by schema
5. Verify backward compatibility (existing COMPLETED records work)
