# Device Filter Reporting - End-to-End Testing Guide

## Overview

This document provides step-by-step procedures for manual end-to-end testing of the Device Filter Reporting feature. These tests verify that the feature works correctly across the entire application flow from discovery through analytics reporting.

## Prerequisites

1. **Backend running** - `mvn spring-boot:run` from `backend/` directory
2. **Frontend running** - `npm start` from `frontend/` directory
3. **Database populated** - Sample data available in staging tables
4. **Postman or curl** - For API testing
5. **Browser** - Chrome, Firefox, or Safari

## Test Scenario 1: Discovery → Staging → Analytics Flow with Device Filtering

### 1.1 Verify Device Information in Discovery Preview

**Objective:** Verify that device information is retrieved and displayed in the discovery preview.

**Steps:**

1. Navigate to the Exensio Reload application
2. Click on **Loader** or **Discovery** tab
3. Select an external data source (e.g., Oracle, Snowflake)
4. Click **Preview** or **Discover**
5. Wait for preview results to load

**Expected Results:**

- [ ] Preview table displays a **Device** column
- [ ] Device column contains device identifiers (e.g., "IR71939") for rows where device data exists
- [ ] Some rows may have empty/NULL device values (this is expected for backward compatibility)
- [ ] **metadataId** column is visible (composite key for identification)
- [ ] **dataId** column is visible (data identifier for traceability)
- [ ] At least 80% of rows contain either device or metadata identifiers

**Verification Command (API):**

```bash
# Get discovery preview data with device column
curl -X POST http://localhost:8080/api/discovery/preview \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer YOUR_TOKEN" \
  -d '{
    "source": "external_oracle",
    "limit": 10
  }' | jq '.items[] | {device, metadataId, dataId, lot, wafer}'
```

**Expected Response:**

```json
{
  "device": "IR71939",
  "metadataId": "META-001",
  "dataId": "DATA-001",
  "lot": "LOT-123",
  "wafer": "WF-456"
}
```

---

### 1.2 Verify Device Filter in Discovery Preview

**Objective:** Verify that device filtering works during the discovery preview phase.

**Steps:**

1. In the discovery preview from 1.1, locate the **Device Filter** control
2. Click on the device filter dropdown
3. Select one or more devices (e.g., "IR71939", "IR72000")
4. Observe preview results update

**Expected Results:**

- [ ] Device filter dropdown appears in preview controls
- [ ] Filter dropdown populated with distinct device values from preview data
- [ ] Selecting devices filters preview results instantly
- [ ] Only rows with selected devices appear in preview table
- [ ] NULL device rows are excluded when device filter is applied
- [ ] Clearing filter shows all rows again
- [ ] Multiple device selection works (OR logic - show rows matching any selected device)

**Verification Command (API):**

```bash
# Get discovery preview with device filter
curl -X POST http://localhost:8080/api/discovery/preview \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer YOUR_TOKEN" \
  -d '{
    "source": "external_oracle",
    "devices": ["IR71939", "IR72000"],
    "limit": 10
  }' | jq '.items | map(.device) | unique'
```

**Expected Response:**

```json
["IR71939", "IR72000"]
```

---

### 1.3 Stage Payloads and Verify Device Persistence

**Objective:** Verify that device information is captured and persisted when staging payloads.

**Steps:**

1. From the discovery preview (with device data visible), select rows to stage
2. Complete the staging process (enter session details, confirm staging)
3. Wait for staging to complete successfully
4. Navigate to **My Sessions** page
5. Click on the session just created
6. In session details, scroll to view the staged payloads table

**Expected Results:**

- [ ] Session created successfully with device information
- [ ] Session details page shows a payload table
- [ ] Payload table displays a **Device** column
- [ ] Device values from preview are now visible in staging table
- [ ] Rows with NULL device in preview show "N/A" or blank in staging table
- [ ] Device information persists across page refresh
- [ ] Can sort/search by device column (if available)

**Verification Command (API):**

```bash
# Get session details and verify device persistence
curl -X GET http://localhost:8080/api/sessions/SESSION_ID \
  -H "Authorization: Bearer YOUR_TOKEN" | jq '.payloads[] | {id, device, metadataId, dataId, status}'
```

**Expected Response:**

```json
{
  "id": 12345,
  "device": "IR71939",
  "metadataId": "META-001",
  "dataId": "DATA-001",
  "status": "COMPLETED"
}
```

---

### 1.4 Test Backward Compatibility with Legacy NULL Devices

**Objective:** Verify that legacy data with NULL devices works correctly without errors.

**Steps:**

1. In the same session from 1.3, scroll through payloads to find rows with no device
2. Verify these rows display "N/A" or are blank (not an error)
3. Navigate to **My Sessions** and filter by device
4. Apply a device filter (select one or more devices)
5. Observe that only payloads with matching devices appear
6. Clear the filter

**Expected Results:**

- [ ] Legacy rows with NULL device display gracefully (no errors)
- [ ] Device filter excludes NULL device rows
- [ ] Clearing filter shows all rows including NULL device rows
- [ ] Application does not error when querying mixed NULL/non-NULL data
- [ ] No database errors in logs related to NULL device handling

**Verification Command (API):**

```bash
# Query sessions with device filter and verify it excludes NULL
curl -X GET "http://localhost:8080/api/sessions?devices=IR71939&limit=20" \
  -H "Authorization: Bearer YOUR_TOKEN" | jq '.items[].payloads[] | select(.device == null)'
```

**Expected:** Empty array (no NULL devices in filtered results)

---

## Test Scenario 2: Analytics Page with Device Filtering

### 2.1 Verify Device Filter in Analytics Page

**Objective:** Verify device filter component appears and functions in the Analytics page.

**Steps:**

1. Navigate to **Analytics** page
2. Look for filter controls at the top of the page

**Expected Results:**

- [ ] Device filter control visible in filter bar
- [ ] Filter control is labeled "Filter by device" or similar
- [ ] Filter dropdown shows list of distinct devices
- [ ] List populated from actual staging data

**Verification Command (API):**

```bash
# Get distinct devices for filter dropdown
curl -X GET http://localhost:8080/api/sessions/devices \
  -H "Authorization: Bearer YOUR_TOKEN" | jq '.'
```

**Expected Response:**

```json
["IR71939", "IR72000", "IR72100"]
```

---

### 2.2 Apply Device Filter and Verify Results

**Objective:** Verify that applying device filter updates analytics results.

**Steps:**

1. On Analytics page, click device filter dropdown
2. Select one device (e.g., "IR71939")
3. Observe analytics results update
4. Note the metrics displayed
5. Select a different device
6. Observe metrics update to reflect new device

**Expected Results:**

- [ ] Filter updates immediately without page reload
- [ ] Analytics metrics change when filter changes
- [ ] All displayed data matches the selected device(s)
- [ ] No metrics from unselected devices appear
- [ ] Filter state persists if user navigates away and returns
- [ ] Multiple device selection works (shows data for any selected device)

**Verification Command (API):**

```bash
# Get analytics with device filter
curl -X GET "http://localhost:8080/api/analytics/summary?devices=IR71939&startDate=2026-01-01&endDate=2026-12-31" \
  -H "Authorization: Bearer YOUR_TOKEN" | jq '.metrics'
```

**Expected:** Only data for IR71939 device

---

### 2.3 Test Unfiltered Analytics (Default Behavior)

**Objective:** Verify that unfiltered analytics shows all devices (backward compatibility).

**Steps:**

1. On Analytics page, ensure no device filter is applied
2. Clear any existing filters if necessary
3. Observe analytics results show data from all devices

**Expected Results:**

- [ ] Default behavior shows all devices
- [ ] Metrics match pre-feature behavior (all data combined)
- [ ] No data is missing compared to legacy system
- [ ] Performance is similar to legacy system

**Verification Command (API):**

```bash
# Get unfiltered analytics
curl -X GET "http://localhost:8080/api/analytics/summary?startDate=2026-01-01&endDate=2026-12-31" \
  -H "Authorization: Bearer YOUR_TOKEN" | jq '.metrics | keys'
```

**Expected:** All metrics present, no filtering applied

---

## Test Scenario 3: My Sessions Page with Device Filtering

### 3.1 Verify Device Filter in My Sessions

**Objective:** Verify device filter appears and functions on My Sessions page.

**Steps:**

1. Navigate to **My Sessions** page
2. Look for device filter control in filter bar

**Expected Results:**

- [ ] Device filter visible in filter controls
- [ ] Dropdown populated with distinct devices from user's sessions
- [ ] Filter control is responsive

---

### 3.2 Apply Device Filter and Verify Session List

**Objective:** Verify that device filter updates session list.

**Steps:**

1. Click device filter and select one or more devices
2. Observe session list updates
3. Click on a filtered session to view details
4. Verify payloads in session details match device filter
5. Switch to different device filter
6. Session list updates accordingly

**Expected Results:**

- [ ] Session list filters immediately
- [ ] Only sessions with payloads matching selected devices appear
- [ ] Session details show only matching payloads
- [ ] Clicking through filtered sessions works correctly
- [ ] Multiple device selection shows sessions with any matching device
- [ ] Filter state persists across navigation

**Verification Command (API):**

```bash
# Get sessions filtered by device
curl -X GET "http://localhost:8080/api/sessions?devices=IR71939&limit=10" \
  -H "Authorization: Bearer YOUR_TOKEN" | jq '.items[] | {id, name, payloadCount}'
```

**Expected:** Only sessions containing IR71939 device payloads

---

### 3.3 Verify Device Display in Session Details

**Objective:** Verify that device information is displayed in session detail view.

**Steps:**

1. Open any session from My Sessions page
2. Scroll to payload table in session details
3. Look for Device column in payload table

**Expected Results:**

- [ ] Session detail page displays payload table
- [ ] Device column visible in payload table
- [ ] Device values show for payloads that have device
- [ ] Device column shows "N/A" or is blank for NULL device payloads
- [ ] Can sort payloads by device column (if feature available)

---

## Test Scenario 4: Dashboard with Device Filtering

### 4.1 Verify Device Filter in Dashboard

**Objective:** Verify device filter appears in dashboard.

**Steps:**

1. Navigate to **Dashboard** page
2. Look for device filter in dashboard filter bar
3. Observe real-time metrics updating

**Expected Results:**

- [ ] Device filter visible in dashboard filter controls
- [ ] Filter dropdown populated with distinct devices
- [ ] Dashboard displays default metrics (all devices)
- [ ] Real-time SSE updates streaming without errors

---

### 4.2 Apply Device Filter and Verify Metric Updates

**Objective:** Verify that device filter updates all dashboard metrics.

**Steps:**

1. Click device filter and select one device
2. Observe all metric cards on dashboard update
3. Select different device(s)
4. Observe metrics update again
5. Watch real-time updates continue while filter active

**Expected Results:**

- [ ] All metric cards update when device filter changes
- [ ] Metrics display only data for selected device(s)
- [ ] Real-time SSE updates filter by device (only relevant updates appear)
- [ ] Performance remains good even with active filter
- [ ] Filter persists if user navigates away and returns
- [ ] Multiple device selection works correctly

**Verification Command (API):**

```bash
# Get dashboard metrics with device filter
curl -X GET "http://localhost:8080/api/dashboard/metrics?devices=IR71939" \
  -H "Authorization: Bearer YOUR_TOKEN" | jq '.summary | keys'
```

**Expected:** Only data for IR71939 in metrics

---

### 4.3 Test Real-Time Updates with Device Filter

**Objective:** Verify that new real-time updates respect device filter on dashboard.

**Steps:**

1. Apply a device filter on dashboard (e.g., select "IR71939")
2. Have someone stage new data with device "IR72000" in parallel
3. Observe dashboard metrics and real-time updates
4. Verify updates for "IR72000" do NOT appear on dashboard
5. Change device filter to include "IR72000"
6. Verify new data now appears in real-time updates

**Expected Results:**

- [ ] Real-time updates only show data matching device filter
- [ ] New device data is excluded when filter is not set to that device
- [ ] Changing filter immediately switches which updates are shown
- [ ] No data loss when changing filters
- [ ] SSE connection remains stable with active filter

---

## Test Scenario 5: API Backward Compatibility

### 5.1 Test API without Device Parameters

**Objective:** Verify that APIs work identically to pre-feature behavior when device parameter is omitted.

**Steps:**

1. Call GET /api/sessions without device parameter

```bash
curl -X GET "http://localhost:8080/api/sessions?limit=10" \
  -H "Authorization: Bearer YOUR_TOKEN" | jq '.items | length'
```

2. Call GET /api/sessions with empty device parameter

```bash
curl -X GET "http://localhost:8080/api/sessions?limit=10&devices=" \
  -H "Authorization: Bearer YOUR_TOKEN" | jq '.items | length'
```

3. Compare results - should be identical

**Expected Results:**

- [ ] Both calls return same number of results
- [ ] API behaves identically to pre-feature system
- [ ] No data is missing
- [ ] Query performance is similar

---

### 5.2 Test API with Valid Device Filter

**Objective:** Verify that device filter parameter correctly filters API results.

**Steps:**

```bash
# Get all sessions
ALL=$(curl -s -X GET "http://localhost:8080/api/sessions?limit=100" \
  -H "Authorization: Bearer YOUR_TOKEN" | jq '.items | length')

# Get sessions for specific device
FILTERED=$(curl -s -X GET "http://localhost:8080/api/sessions?devices=IR71939&limit=100" \
  -H "Authorization: Bearer YOUR_TOKEN" | jq '.items | length')

echo "All: $ALL, Filtered: $FILTERED"
```

**Expected Results:**

- [ ] `FILTERED` is less than or equal to `ALL`
- [ ] All items in `FILTERED` response have device = "IR71939"
- [ ] Items with other devices or NULL devices not in response

---

### 5.3 Test API with Multiple Device Filters

**Objective:** Verify that multiple device values filter correctly (OR logic).

**Steps:**

```bash
curl -X GET "http://localhost:8080/api/sessions?devices=IR71939&devices=IR72000&limit=20" \
  -H "Authorization: Bearer YOUR_TOKEN" | jq '.items[].payloads[] | .device' | sort | uniq
```

**Expected Results:**

- [ ] Response includes only "IR71939" and "IR72000"
- [ ] No other devices present
- [ ] No NULL devices (unless explicitly included)
- [ ] All selected devices represented in results

---

### 5.4 Test API with Pagination and Device Filter

**Objective:** Verify that device filter works correctly with paginated results.

**Steps:**

```bash
# Get first page
PAGE1=$(curl -s -X GET "http://localhost:8080/api/sessions?devices=IR71939&limit=5&page=0" \
  -H "Authorization: Bearer YOUR_TOKEN")

# Get second page
PAGE2=$(curl -s -X GET "http://localhost:8080/api/sessions?devices=IR71939&limit=5&page=1" \
  -H "Authorization: Bearer YOUR_TOKEN")

# Verify total matches sum of pages
TOTAL=$(echo $PAGE1 | jq '.total')
PAGE1_COUNT=$(echo $PAGE1 | jq '.items | length')
PAGE2_COUNT=$(echo $PAGE2 | jq '.items | length')

echo "Total: $TOTAL, Page1: $PAGE1_COUNT, Page2: $PAGE2_COUNT"
```

**Expected Results:**

- [ ] Page 1 and Page 2 have no duplicates
- [ ] Sum of all pages equals total count
- [ ] All items in all pages have device = "IR71939"
- [ ] Pagination works smoothly with filter

---

## Test Scenario 6: Metadata (metadataId, dataId) Preservation

### 6.1 Verify Metadata in Discovery Preview

**Objective:** Verify that metadataId and dataId are captured in discovery.

**Steps:**

1. Navigate to discovery preview
2. Look for metadataId and dataId columns

**Expected Results:**

- [ ] metadataId column visible in preview
- [ ] dataId column visible in preview
- [ ] Values populated for each row
- [ ] Can be used to identify source data

---

### 6.2 Verify Metadata Persisted in Staging

**Objective:** Verify that metadata survives staging process.

**Steps:**

1. Stage payloads from preview
2. Query session details

```bash
curl -X GET "http://localhost:8080/api/sessions/SESSION_ID" \
  -H "Authorization: Bearer YOUR_TOKEN" | jq '.payloads[] | {id, metadataId, dataId, device}'
```

**Expected Results:**

- [ ] metadataId retained after staging
- [ ] dataId retained after staging
- [ ] device retained after staging
- [ ] Can query staged data by these identifiers

---

## Test Scenario 7: Performance Verification

### 7.1 Query Performance with Device Filter

**Objective:** Verify that device filter queries complete within acceptable time.

**Steps:**

```bash
# Measure query time with device filter
time curl -X GET "http://localhost:8080/api/sessions?devices=IR71939&limit=100&page=0" \
  -H "Authorization: Bearer YOUR_TOKEN" > /dev/null

# Measure without filter
time curl -X GET "http://localhost:8080/api/sessions?limit=100&page=0" \
  -H "Authorization: Bearer YOUR_TOKEN" > /dev/null
```

**Expected Results:**

- [ ] Device filtered query completes within 2 seconds
- [ ] Performance similar to non-filtered query
- [ ] No N+1 query problems
- [ ] Database indexes are being used

---

### 7.2 Distinct Device Query Performance

**Objective:** Verify that retrieving distinct devices is efficient.

**Steps:**

```bash
time curl -X GET "http://localhost:8080/api/sessions/devices" \
  -H "Authorization: Bearer YOUR_TOKEN"
```

**Expected Results:**

- [ ] Query completes within 1 second
- [ ] Returns all unique device values
- [ ] List can be used for filter dropdown without latency issues

---

## Test Summary Checklist

Copy and complete this checklist to document end-to-end testing completion:

```markdown
## E2E Testing Completion Checklist

**Date:** ******\_******
**Tester:** ******\_******

### Test Scenario 1: Discovery → Staging → Analytics

- [ ] 1.1 Device in discovery preview ✓
- [ ] 1.2 Device filter in discovery works ✓
- [ ] 1.3 Device persisted after staging ✓
- [ ] 1.4 Backward compatibility with NULL devices ✓

### Test Scenario 2: Analytics Device Filtering

- [ ] 2.1 Device filter visible in Analytics ✓
- [ ] 2.2 Device filter updates results ✓
- [ ] 2.3 Unfiltered analytics shows all devices ✓

### Test Scenario 3: My Sessions Device Filtering

- [ ] 3.1 Device filter in My Sessions ✓
- [ ] 3.2 Device filter updates session list ✓
- [ ] 3.3 Device displayed in session details ✓

### Test Scenario 4: Dashboard Device Filtering

- [ ] 4.1 Device filter in Dashboard ✓
- [ ] 4.2 Device filter updates all metrics ✓
- [ ] 4.3 Real-time updates respect device filter ✓

### Test Scenario 5: API Backward Compatibility

- [ ] 5.1 API works without device parameter ✓
- [ ] 5.2 API with device filter works ✓
- [ ] 5.3 Multi-device filter works ✓
- [ ] 5.4 Pagination with filter works ✓

### Test Scenario 6: Metadata Preservation

- [ ] 6.1 Metadata in discovery preview ✓
- [ ] 6.2 Metadata persisted after staging ✓

### Test Scenario 7: Performance

- [ ] 7.1 Device filter query performance good ✓
- [ ] 7.2 Distinct device query performance good ✓

### Issues Found

- [ ] None
- [ ] Minor issues (list below)
- [ ] Major issues (list below)

**Issues:**

1. ***
2. ***
3. ***

### Sign-off

- All tests passed: [ ] YES / [ ] NO
- Ready for deployment: [ ] YES / [ ] NO
- Deployment date: ******\_******

**Tester Signature:** ********\_******** **Date:** **\_\_\_**
```
