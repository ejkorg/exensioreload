# Wafer-Level Preflight Check Enhancement

## Overview

Enhanced the preflight check feature to return wafer numbers when users input only lot numbers for wafer-level classes (Class 1, 4, or 14). This allows users to see which wafers exist in Exensio and which have not been loaded.

## Changes Made

### Backend Changes

#### 1. DTO Updates

- **ExensioPreCheckRow.java**: Added `waferId` field to include wafer information in preflight check results
  - Updated to 3-parameter record: `(String lotId, String schemaName, String waferId)`
  - Added JavaDoc explaining wafer usage for wafer-level classes

- **LotVerificationResult.java**: Added `wafers` field to return list of wafer IDs
  - Updated to 3-parameter record: `(boolean found, String schema, List<String> wafers)`
  - Wafer list populated for wafer-level classes when only lot is provided

#### 2. Service Updates

- **ExensioPreCheckService.java**: Enhanced SQL queries and parsing logic
  - Updated Snowflake SQL queries to conditionally return all wafers for a lot when:
    - Data type is wafer-level class (pgc_key 1, 4, 5, or 14)
    - No specific wafer IDs provided in request
  - Modified query parameter binding to pass waferFilterMode:
    - 0 = lot-level only (class 2)
    - 1 = wafer-level with filter (specific wafers requested)
    - 2 = wafer-level return all wafers (only lot provided)
  - Updated `parseResponse()` to extract WAFER_ID from Oracle SQL results
  - Updated Snowflake result parsing to include WAFER_ID column

#### 3. Controller Updates

- **SenderController.java**: Enhanced lot verification endpoint
  - Modified `/verify-lots` endpoint to collect and group wafers by lot
  - Creates `Map<String, List<String>>` to track wafers per lot
  - Populates `LotVerificationResult` with wafer list for each lot
  - Maintains schema priority (first schema wins for duplicate lot entries)

### Frontend Changes

#### 1. Type Updates

- **backend.service.ts**: Updated `LotVerificationResult` interface
  - Added optional `wafers?: string[]` field

#### 2. Dialog Updates

- **lot-verification-dialog.component.ts**: Enhanced UI to display wafer information
  - Updated `LotVerificationDialogData` interface to include wafers
  - Added `getWafersForLot()` method to retrieve wafer list for a lot
  - Enhanced template to show wafer count and list below schema info
  - Added wafer info styling with chip-like display
  - Updated CSV export to include "Wafers" column

#### 3. UI/UX Improvements

- Wafer information displayed as:
  - Icon + count: "5 wafer(s):"
  - Comma-separated wafer IDs (truncated if too long)
  - Styled with indigo accent color to differentiate from schema
- CSV export now includes wafer column with semicolon-separated values

## SQL Query Logic

### Snowflake Query Enhancement

The queries now use a conditional parameter to determine filtering mode:

```sql
AND (? = 0 OR ? = 2 OR WAFER_ID IN (SELECT wafer_id FROM provided_wafers WHERE wafer_id IS NOT NULL))
```

Where the parameter is set based on:

- `0`: Lot-level class → no wafer filtering
- `1`: Wafer-level with specific wafers → filter by provided wafer IDs
- `2`: Wafer-level without wafer filter → return all wafers for the lot

### Oracle Query (HTTP Fallback)

The existing Oracle query already returns `WAFER_ID` in the result set. Updated the JSON parsing to extract this field and include it in `ExensioPreCheckRow`.

## User Experience Flow

1. User enters lot numbers without wafer numbers
2. User enables preflight check
3. System detects data type is Class 1, 4, or 14
4. Backend queries Exensio/Snowflake for all wafers in those lots
5. Dialog displays:
   - Lot ID
   - Schema where found
   - **NEW**: List of wafer IDs found in that schema
6. User can see which wafers exist and make informed decisions
7. CSV export includes wafer information for offline analysis

## Testing Notes

Due to environment constraints (no Java/Maven/Node.js runtime), the implementation cannot be tested locally. The developer should:

1. **Backend Tests**:
   - Verify `ExensioPreCheckRow` construction with 3 parameters
   - Test wafer aggregation logic in SenderController
   - Verify SQL parameter binding for waferFilterMode
   - Confirm wafer list returned in LotVerificationResult

2. **Frontend Tests**:
   - Verify wafer display in lot verification dialog
   - Test CSV export includes wafers column
   - Check UI styling for wafer info section

3. **Integration Tests**:
   - Test with Class 1 (Probe) data type + lot only
   - Test with Class 4 (Map) data type + lot only
   - Test with Class 14 (Defect) data type + lot only
   - Test with Class 2 (FT) → should not return wafers
   - Test with lot + wafer specified → should not return all wafers

## Files Modified

### Backend

- `backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/dto/ExensioPreCheckRow.java`
- `backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/dto/LotVerificationResult.java`
- `backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/service/ExensioPreCheckService.java`
- `backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/controller/SenderController.java`

### Frontend

- `frontend/src/app/api/backend.service.ts`
- `frontend/src/app/stepper/lot-verification-dialog.component.ts`

## Backward Compatibility

All changes are backward compatible:

- Wafer field is optional in frontend interfaces
- Existing lot-level queries (Class 2) continue to work unchanged
- Wafer information only populated when applicable (wafer-level classes without wafer filter)
- CSV export gracefully handles empty wafer lists
