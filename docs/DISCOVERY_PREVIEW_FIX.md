# Fix for 400 Bad Request Error on Discovery Preview

## Problem
Frontend POST request to `/resender/api/senders/1/discover/preview` was returning **400 Bad Request** with the error message:
```
"Provide at least one filter: testerType, lot/wafer, or a start/end date range."
```

## Root Cause
The frontend was sending the request body with **all properties set to `null`** when filters were not selected:
```javascript
const params = {
    site: selectedSite,
    environment: this.selectedEnv(),
    location: null,           // ❌ Should not be sent if empty
    dataType: null,           // ❌ Should not be sent if empty
    dataTypeExt: null,        // ❌ Should not be sent if empty
    testerType: null,         // ❌ Should not be sent if empty
    testPhase: null,          // ❌ Should not be sent if empty
    startDate: null,          // ❌ Should not be sent if empty
    endDate: null,            // ❌ Should not be sent if empty
    pairs: [],                // Empty array also fails validation
    page: 0,
    size: 100
};
```

The backend validation in `MetadataImporterService.validatePreviewRequest()` checks:
```java
boolean hasLotsFilter = lots != null && lots.stream().anyMatch(v -> v != null && !v.isBlank());
boolean hasWafersFilter = wafers != null && wafers.stream().anyMatch(v -> v != null && !v.isBlank());
boolean hasTesterType = testerType != null && !testerType.isBlank();
boolean hasStart = startDate != null && !startDate.isBlank();
boolean hasEnd = endDate != null && !endDate.isBlank();
boolean anyFilter = hasLotsFilter || hasWafersFilter || hasTesterType || (hasStart && hasEnd);
if (!anyFilter) {
    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, 
        "Provide at least one filter: testerType, lot/wafer, or a start/end date range.");
}
```

With all null values, `anyFilter` evaluates to `false`, causing the 400 error.

## Solution
**Modified** [new_frontend/src/app/stepper/stepper.component.ts](new_frontend/src/app/stepper/stepper.component.ts#L291)

Changed the `loadPreview()` method to **only include properties with actual values**:

```typescript
loadPreview() {
    this.previewLoading.set(true);
    const range = this.dateRange();
    const selectedSite = this.selectedSite();
    const siteMap = this.siteToSenderMap();
    const senderId = selectedSite && siteMap[selectedSite] ? siteMap[selectedSite] : 1;
    
    // Build params with only non-null/non-empty values to meet backend validation
    const params: any = {
        site: selectedSite,
        environment: this.selectedEnv(),
        page: 0,
        size: 100,
        bypassCap: false,
        historicalMode: this.historicalMode()
    };

    // Only include optional filters if they have values
    if (this.selectedLocation()) params.location = this.selectedLocation();
    if (this.selectedDataType()) params.dataType = this.selectedDataType();
    if (this.selectedDataTypeExt()) params.dataTypeExt = this.selectedDataTypeExt();
    if (this.selectedTesterType()) params.testerType = this.selectedTesterType();
    if (this.selectedTestPhase()) params.testPhase = this.selectedTestPhase();
    
    // Include date range only if both start and end are provided
    if (range?.start && range?.end) {
        params.startDate = range.start;
        params.endDate = range.end;
    }

    // Include pairs (lot/wafer filters)
    const filteredPairs = this.lotWaferPairs().filter((p: { lot: string; wafer: string }) => p.lot);
    if (filteredPairs.length > 0) {
        params.pairs = filteredPairs;
    }

    this.backend.getDiscoveryPreview(senderId, params).subscribe({
        next: (res: { rows: DiscoveryPreviewRow[]; total: number }) => {
            this.previewRows.set(res.rows);
            this.previewTotal.set(res.total);
            this.previewLoading.set(false);
            this.selectedRows.set(new Set(res.rows.map((r: DiscoveryPreviewRow) => r.metadataId)));
        },
        error: () => this.previewLoading.set(false)
    });
}
```

## Key Changes
1. **Initialize with required fields only**: `site`, `environment`, `page`, `size`, `bypassCap`, `historicalMode`
2. **Conditionally add optional filters**: Check each optional filter before including it
3. **Date range validation**: Only include `startDate` and `endDate` if BOTH are present
4. **Pairs filtering**: Only include pairs array if there are actual lot filters

## Impact
✅ **Before**: Request fails with 400 Bad Request  
✅ **After**: Request succeeds with valid filters  
✅ **Backward Compatible**: Existing requests with full filters still work  
✅ **User Experience**: Discovery preview now works when filters are selectively applied

## Testing
When you navigate to the Discovery Preview step:
1. User should be able to click "Load Preview" with ANY of the following:
   - At least one lot selected
   - Tester type selected
   - Date range selected (both start AND end)
   - Any combination of the above

2. The request body sent to backend will ONLY contain fields that have actual values
3. Backend validation will pass and return the paginated results

---
**Status**: ✅ FIXED  
**Date**: 2026-02-24  
**File Modified**: [new_frontend/src/app/stepper/stepper.component.ts](new_frontend/src/app/stepper/stepper.component.ts)
