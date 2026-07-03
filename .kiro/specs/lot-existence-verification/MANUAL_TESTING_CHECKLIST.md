# Manual Testing Checklist - Lot Existence Verification

## Pre-Testing Setup

- [ ] Backend service is running on configured environment
- [ ] Frontend application is built and accessible
- [ ] Exensio database is reachable (Snowflake and/or HTTP endpoint)
- [ ] User is logged in with appropriate permissions
- [ ] Network connectivity to all services confirmed

## Core Functionality Tests

### Test 1: Verify 1 Lot (Exists in Exensio)

- [ ] Navigate to Discovery Configuration step
- [ ] Enter 1 lot that exists in Exensio (e.g., verify with DBA first)
- [ ] Click "Run Discovery Preview"
- [ ] Verify dialog appears with:
  - [ ] Total Lots: 1
  - [ ] Found in Exensio: 1
  - [ ] Not Found: 0
- [ ] Verify "Continue with All" button is available
- [ ] Verify "Continue with Lots Not in Exensio" button is DISABLED with tooltip
- [ ] Click "Continue with All" and verify discovery proceeds
- [ ] Verify summary banner shows "Showing results for all lots (1 verified, 1 found, 0 not found)"
- [ ] Duration: < 2 seconds for verification

### Test 2: Verify 10 Lots (5 Exist, 5 Don't)

- [ ] Navigate to Discovery Configuration step
- [ ] Enter 10 lots: 5 that exist, 5 that don't (verify mix with DBA)
- [ ] Click "Run Discovery Preview"
- [ ] Verify dialog appears with:
  - [ ] Total Lots: 10
  - [ ] Found in Exensio: 5
  - [ ] Not Found: 5
- [ ] Verify found lots display in "Found in Exensio" column
- [ ] Verify not-found lots display in "Not Found in Exensio" column
- [ ] Verify NO warning banner (since there are lots to discover)
- [ ] Test "Continue with All": discovery runs with all 10 lots
- [ ] Cancel and re-run; test "Continue with Lots Not in Exensio": discovery runs with only 5 lots
- [ ] Verify lots are properly filtered in discovery results
- [ ] Duration: < 3 seconds for verification

### Test 3: Verify 100+ Lots (Batch Processing)

- [ ] Navigate to Discovery Configuration step
- [ ] Enter 150 lots (mix of existing and non-existing)
- [ ] Click "Run Discovery Preview"
- [ ] Verify:
  - [ ] Loading overlay shows "Verifying lots in Exensio..."
  - [ ] Spinner animation present
  - [ ] No timeout errors (should complete within 5 seconds)
- [ ] Verify dialog appears with correct counts
- [ ] Verify all 150 lots are accounted for (found + not found = 150)
- [ ] Test CSV export works (see CSV Export Tests below)
- [ ] Duration: < 5 seconds for verification

### Test 4: Verify All Lots Exist

- [ ] Navigate to Discovery Configuration step
- [ ] Enter lots that ALL exist in Exensio
- [ ] Click "Run Discovery Preview"
- [ ] Verify dialog shows:
  - [ ] Total Lots: N
  - [ ] Found in Exensio: N
  - [ ] Not Found: 0
  - [ ] **Warning banner**: "All lots already exist in Exensio..."
- [ ] Verify "Continue with Lots Not in Exensio" button is DISABLED
- [ ] Verify "Continue with All" is available and recommended
- [ ] Click "Continue with All" and verify discovery proceeds with existing lots

### Test 5: Verify All Lots Don't Exist

- [ ] Navigate to Discovery Configuration step
- [ ] Enter lots that NONE exist in Exensio
- [ ] Click "Run Discovery Preview"
- [ ] Verify dialog shows:
  - [ ] Total Lots: N
  - [ ] Found in Exensio: 0
  - [ ] Not Found: N
  - [ ] No warning banner (normal case)
- [ ] Verify "Found in Exensio" column is empty
- [ ] Verify "Not Found" column shows all lots
- [ ] Verify "Continue with Lots Not in Exensio" is available and RECOMMENDED
- [ ] Click and verify discovery runs with all lots

---

## CSV Export Tests

### Test 6: CSV Export with Mixed Results

- [ ] Run verification with 10 lots (5 found, 5 not found)
- [ ] In verification dialog, click "Export to CSV"
- [ ] Verify browser downloads file with name pattern: `lot-verification-YYYYMMDD-HHMMSS.csv`
- [ ] Open CSV file and verify:
  - [ ] Header row: `Lot ID,Status,Verified At`
  - [ ] Exactly 10 data rows (one per lot)
  - [ ] Status column shows "Found in Exensio" or "Not Found in Exensio"
  - [ ] Verified At column shows ISO format timestamp
  - [ ] All lot IDs are present and correct
  - [ ] Special characters (quotes, commas) in lot IDs are properly escaped
- [ ] Verify dialog remains open after export (not closed)

### Test 7: CSV Export with Special Characters

- [ ] Create test lots with special characters:
  - [ ] Lot with quotes: `LOT"ABC`
  - [ ] Lot with commas: `LOT,123`
  - [ ] Lot with backslashes: `LOT\456`
- [ ] Run verification and export CSV
- [ ] Verify CSV opens correctly in Excel/CSV viewer
- [ ] Verify special characters are properly escaped

---

## User Action Tests

### Test 8: Cancel from Verification Dialog

- [ ] Run verification with any lots
- [ ] When dialog appears, click "Cancel" button
- [ ] Verify:
  - [ ] Dialog closes
  - [ ] Discovery does NOT run
  - [ ] User is returned to Configuration step
  - [ ] Previously entered lots are still visible

### Test 9: Continue with All vs Not Found

- [ ] Run verification with 10 lots (5 found, 5 not found)
- [ ] In verification dialog, click "Continue with All"
- [ ] Verify discovery shows ALL 10 lots
- [ ] Run verification again with same 10 lots
- [ ] This time click "Continue with Lots Not in Exensio"
- [ ] Verify discovery shows ONLY 5 lots (the not-found ones)
- [ ] Compare discovery results between the two runs

### Test 10: Dismiss Verification Summary Banner

- [ ] Complete verification and proceed with discovery
- [ ] Verify summary banner appears above discovery results
- [ ] Banner shows: "Showing results for all lots (X verified, Y found, Z not found)"
- [ ] Click X button on banner to dismiss
- [ ] Verify banner disappears
- [ ] Verify discovery results remain visible

### Test 11: Modify Filters After Verification

- [ ] Complete verification and proceed with discovery
- [ ] Verify summary banner appears
- [ ] Return to Configuration step (click back/edit button)
- [ ] Modify lot/wafer pairs (add/remove/change lots)
- [ ] Verify summary banner is cleared
- [ ] Run new verification
- [ ] Verify new verification summary appears

---

## Error Handling Tests

### Test 12: Error Handling - Exensio Unavailable (Simulate)

- [ ] Stop/disable Exensio service or simulate network timeout
- [ ] Run verification with lots
- [ ] Verify:
  - [ ] Loading overlay shows "Verifying lots in Exensio..."
  - [ ] After timeout/error, error message appears: "Lot verification failed: [error details]"
  - [ ] Confirmation dialog appears: "Would you like to skip verification and continue with discovery?"
- [ ] Click "OK" to skip verification
- [ ] Verify discovery runs with ALL input lots (verification skipped)
- [ ] Restore Exensio service

### Test 13: Error Handling - Authentication Failure

- [ ] Configure invalid/expired credentials for Exensio
- [ ] Run verification
- [ ] Verify error is caught and user is prompted to skip
- [ ] System attempts token refresh before showing error
- [ ] User can choose to skip verification or cancel

### Test 14: Large Lot List Error

- [ ] Attempt to verify > 1000 lots
- [ ] Verify backend validation catches this
- [ ] Verify error response: "Too many lots. Maximum 1000 lots per request."
- [ ] Dialog shows error to user

---

## Historical Mode Tests

### Test 15: Verification with Historical Mode + Date Range

- [ ] Enable historical mode
- [ ] Set date range: e.g., 2024-01-01 to 2024-03-31
- [ ] Enter lots with data in that date range
- [ ] Click "Run Discovery Preview"
- [ ] Verify dialog appears with date range info banner:
  - [ ] "Date range filters applied: 01/01/2024 - 03/31/2024"
- [ ] Verify lots are marked as found only if they have data in that date range
- [ ] Verify discovery applies the same date range to results

### Test 16: Verification with Historical Mode - Date Range Only

- [ ] Enable historical mode
- [ ] Set date range: e.g., 2024-01-01 to 2024-03-31
- [ ] Do NOT specify any lots (leave empty)
- [ ] Click "Run Discovery Preview"
- [ ] Verify:
  - [ ] Verification is SKIPPED (no dialog appears)
  - [ ] Discovery proceeds immediately with date range filter
  - [ ] No summary banner (verification wasn't done)

---

## Bulk Lot Input Tests

### Test 17: Verification with Bulk Lot Input

- [ ] Navigate to Discovery Configuration
- [ ] Click "Bulk Add Lots" (if feature exists)
- [ ] Paste or upload a list of 50 lots
- [ ] Click "Add Lots"
- [ ] Verify lots appear in lot/wafer pairs
- [ ] Click "Run Discovery Preview"
- [ ] Verify verification dialog correctly shows:
  - [ ] Total: 50
  - [ ] Found/Not Found counts are correct
- [ ] Verify all bulk-added lots are included in verification

### Test 18: Deduplication with Bulk Input

- [ ] Add 10 lots manually
- [ ] Bulk add same 10 lots again
- [ ] Click "Run Discovery Preview"
- [ ] Verify:
  - [ ] Deduplication works (no duplicates sent to backend)
  - [ ] Dialog still shows counts correctly (not doubled)
  - [ ] Total verified count matches unique lots only

---

## Performance Tests

### Test 19: Verification Performance - 100 Lots

- [ ] Verify 100 lots
- [ ] Measure time from "Run Discovery Preview" click to dialog appearance
- [ ] Expected: < 3 seconds
- [ ] Record actual time: **\_** seconds

### Test 20: Verification Performance - 500 Lots

- [ ] Verify 500 lots (test batching)
- [ ] Measure time from "Run Discovery Preview" click to dialog appearance
- [ ] Expected: < 5 seconds
- [ ] Record actual time: **\_** seconds
- [ ] Verify backend batches queries correctly (check logs for batch info)

### Test 21: Verification Performance - 1000 Lots (Max)

- [ ] Verify 1000 lots (at limit)
- [ ] Measure time
- [ ] Expected: < 5 seconds
- [ ] Verify no performance degradation

---

## UI/UX Tests

### Test 22: Dialog Responsiveness

- [ ] Open verification dialog with 50 lots
- [ ] Verify:
  - [ ] Dialog is scrollable within lot lists
  - [ ] Custom scrollbars are visible and work
  - [ ] Buttons are clickable and responsive
  - [ ] Export button works from any scroll position
  - [ ] Text is readable and not truncated

### Test 23: Mobile Responsiveness

- [ ] Open verification dialog on mobile device (or use browser DevTools)
- [ ] Verify:
  - [ ] Dialog adapts to screen size
  - [ ] Columns stack vertically if needed
  - [ ] Buttons are full-width and easy to tap
  - [ ] Lot lists are scrollable with proper sizing

### Test 24: Accessibility

- [ ] Open verification dialog
- [ ] Verify:
  - [ ] Dialog has `role="dialog"` and `aria-modal="true"`
  - [ ] Dialog title has `id` and is referenced by `aria-labelledby`
  - [ ] Dialog can be closed with Escape key
  - [ ] Buttons have proper labels (not just icons)
  - [ ] Tab key navigation works through all interactive elements
  - [ ] Screen reader can read lot IDs and status counts

---

## Integration Tests

### Test 25: Full Workflow - Configuration → Verification → Discovery → Monitor

- [ ] Configure discovery filters (site, location, data type, lots)
- [ ] Click "Run Discovery Preview"
- [ ] Verify lots
- [ ] Select "Continue with Lots Not in Exensio"
- [ ] Verify discovery results appear with correct filtering
- [ ] Select some files to stage
- [ ] Click "Stage Selected"
- [ ] Verify staging proceeds normally
- [ ] Navigate to Monitor tab
- [ ] Verify monitoring displays correctly

### Test 26: Full Workflow - Retry After Cancellation

- [ ] Start discovery verification
- [ ] Dialog appears
- [ ] Click "Cancel"
- [ ] Verify returned to Configuration
- [ ] Modify lots or filters
- [ ] Click "Run Discovery Preview" again
- [ ] Verify new verification runs (not cached)
- [ ] Verify new results are displayed

---

## Data Integrity Tests

### Test 27: Lot Matching - Case Sensitivity

- [ ] Enter lot: `LOT123` (uppercase)
- [ ] Run verification
- [ ] Verify system finds lot even if database has `lot123` (case-insensitive)
- [ ] Same test with lowercase, mixed case

### Test 28: Lot Matching - Whitespace Handling

- [ ] Enter lot: `  LOT123  ` (with spaces)
- [ ] Run verification
- [ ] Verify system trims whitespace and finds lot
- [ ] Verify display shows trimmed lot ID

### Test 29: Result Consistency

- [ ] Run verification with same 20 lots twice (same session, same filters)
- [ ] Verify results are identical
- [ ] Run verification again after 1 hour
- [ ] Results should be the same (unless data changed in Exensio)

---

## Summary Reporting

### Totals

- **Total Tests**: 29
- **Passed**: **\_** / 29
- **Failed**: **\_** / 29
- **Skipped**: **\_** / 29

### Issues Found

1. ***
2. ***
3. ***
4. ***
5. ***

### Recommendations

- ***
- ***
- ***

### Sign-Off

- **Tester Name**: **********\_**********
- **Date**: **********\_**********
- **Environment**: PROD / QA / DEV
- **Notes**: ********************\_\_\_********************
