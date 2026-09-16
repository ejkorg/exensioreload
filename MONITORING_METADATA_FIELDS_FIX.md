# Fix: Monitoring File List Missing Metadata Fields (device, step, tester, recipe)

## Issue
In Step 3 monitoring UI (file-list monitor table), the device, step, tester, and recipe columns were showing dashes instead of actual values, while Step 2 discovery preview displayed these fields correctly.

## Root Cause
The backend `StageRecordMapper.toView()` method was not normalizing the `device` field with the "-" sentinel value like it did for `step`, `testerId`, and `testProgram`.

**Inconsistent mapping:**
```java
// ❌ BEFORE: device was returned raw (could be null)
record.device(),
normalizeDisplayValue(record.step(), "-"),
normalizeDisplayValue(record.testerId(), "-"),
normalizeDisplayValue(record.testProgram(), "-"),
```

The frontend's `monitoringPaginationService.mapToMonitoringFile()` method has a `nullIfDash()` helper that converts "-" to null for proper display in the UI. Without the "-" sentinel from the backend, the raw null values weren't being handled consistently.

## Solution
Applied `normalizeDisplayValue(record.device(), "-")` to the device field to match the pattern used for other metadata fields.

**Fixed mapping:**
```java
// ✅ AFTER: device is normalized like other fields
normalizeDisplayValue(record.device(), "-"),
normalizeDisplayValue(record.step(), "-"),
normalizeDisplayValue(record.testerId(), "-"),
normalizeDisplayValue(record.testProgram(), "-"),
```

## Files Modified
- `backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/controller/StageRecordMapper.java`
  - Line 130: Added `normalizeDisplayValue(record.device(), "-")` wrapper

## Data Flow
1. **Database → StageRecord:** Raw null/empty values from staging table
2. **StageRecord → StageRecordView (mapper):** Now consistently converts nulls to "-" sentinel
3. **Backend API Response:** Returns "-" for empty fields
4. **Frontend mapping:** `nullIfDash()` converts "-" back to null for display
5. **UI rendering:** Device/step/tester/recipe fields display actual values or "-" placeholder

## Testing
The fix is minimal and surgical — only adds normalization for one field to match the existing pattern for three other fields. No logic changes, just consistent data transformation.

To verify on remote development node:
```bash
mvn clean package -DskipTests
```

Then in the frontend, when monitoring Step 3 files after discovery, the device, step, tester, and recipe columns should now display actual values instead of dashes.

## Related Files
- Frontend mapping service: `frontend/src/app/shared/services/monitoring-pagination.service.ts` (mapToMonitoringFile method, lines 369-390)
- Frontend display component: `frontend/src/app/shared/components/monitoring-file-list.component.ts` (uses MonitoringFile interface with device/step/testerId/testProgram fields)
