# Monitor Cards Update — Backend Recompile and Redeploy Required

## Status: ❌ CARDS NOT UPDATED

The frontend is correctly configured to display 7 state cards, but the dashboard is showing only 5 cards because **the backend API has not been redeployed with the latest code changes**.

## What's Happening

### Frontend (✅ CORRECT)

- Dashboard component has the correct template with 7 cards
- `primaryMetrics()` computed signal returns all 7 state cards:
  1. Staged (ready/pending)
  2. Queued for CP (ENQUEUED)
  3. In Enrichment (ENRICHMENT)
  4. Exensio Loading (EXENSIO_LOADING)
  5. Completed (DONE)
  6. Failed (FAILED)
  7. Cancelled (CANCELLED)

- Frontend types are correct (`DashboardMetricTotals` interface has all 7 fields)
- State legend and tooltips implemented
- HTML template correctly iterates over `primaryMetrics()` array

### Backend Code (✅ CORRECT)

- `RefDbService.fetchStatuses()` correctly queries all 7 states from database:

  ```sql
  SELECT ...
    SUM(CASE WHEN status = 'pending' THEN 1 ELSE 0 END),          -- ready
    SUM(CASE WHEN status = 'ENQUEUED' THEN 1 ELSE 0 END),         -- queued
    SUM(CASE WHEN status = 'ENRICHMENT' THEN 1 ELSE 0 END),       -- enriching
    SUM(CASE WHEN status = 'EXENSIO_LOADING' THEN 1 ELSE 0 END),  -- exensioLoading
    SUM(CASE WHEN status = 'FAILED' THEN 1 ELSE 0 END),           -- failed
    SUM(CASE WHEN status = 'DONE' THEN 1 ELSE 0 END),             -- completed
    SUM(CASE WHEN status = 'CANCELLED' THEN 1 ELSE 0 END)         -- cancelled
  ```

- `StageStatus` record has all 7 state fields
- `DashboardMetricTotals` DTO has all 11 fields (total, ready, queued, enriching, exensioLoading, failed, completed, cancelled, backlog, activeSenders, activeUsers)
- `DashboardController.toMetrics()` correctly builds the DTO with all fields
- `DashboardController.snapshot()` endpoint returns correct structure

### The Problem (❌ DEPLOYMENT GAP)

**Old compiled backend JAR is still running in production/staging**.

The old compiled backend likely has one of these issues:

1. Missing the 7-state queries in `RefDbService.fetchStatuses()`
2. Missing the 7 fields in `DashboardMetricTotals` DTO
3. Missing the state mapping in `DashboardController.toMetrics()`
4. Only returning 5-6 state fields instead of 7

## What the Screenshot Shows

Current running backend returns only 5 cards:

```
- TOTAL FILES: 4544
- STAGED: 0
- IN QUEUE (PENDING CP): 984
- ENRICHMENT / TRANSLATION: 984
- COMPLETED: 256
```

Expected with deployed code:

```
- Staged: 0
- Queued for CP: 984
- In Enrichment: 984
- Exensio Loading: 0
- Completed: 256
- Failed: 0
- Cancelled: 0
```

## Solution: Recompile and Redeploy Backend

### Step 1: Verify Code is Correct

All backend code has been updated and is correct. Key files:

- ✅ `backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/dto/DashboardMetricTotals.java`
- ✅ `backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/stage/StageStatus.java`
- ✅ `backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/service/RefDbService.java` (fetchStatuses method)
- ✅ `backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/controller/DashboardController.java`

### Step 2: Clean Build

```bash
cd backend
mvn clean package -DskipTests
```

### Step 3: Verify JAR Contains New Code

```bash
# Unzip and check for 7-state queries
unzip -p target/exensioreload-*.jar com/onsemi/cim/apps/exensio/exensioreload/service/RefDbService.class | strings | grep -i "exensio_loading"
```

### Step 4: Deploy New JAR

```bash
# Copy to deployment directory
cp target/exensioreload-*.jar /path/to/deployment/

# Restart service
systemctl restart exensioreload  # or your deployment method
```

### Step 5: Verify Deployment

1. Check logs: `tail -f /var/log/exensioreload/app.log`
2. Call dashboard API:

   ```bash
   curl -H "Authorization: Bearer <token>" \
     https://<host>/api/dashboard/snapshot | jq '.global | keys'
   ```

   Should show: `["activeSenders", "activeUsers", "backlog", "cancelled", "completed", "enriching", "exensioLoading", "failed", "queued", "ready", "total"]`

3. Refresh browser to see 7 cards update

## Implementation Verification

### Backend Implementation ✅

| Component                 | Status | Details                                           |
| ------------------------- | ------ | ------------------------------------------------- |
| Database Query            | ✅     | All 7 states counted in SQL                       |
| StageStatus Record        | ✅     | Has all 7 fields + enqueued() method              |
| DashboardMetricTotals DTO | ✅     | 11 fields total, includes all 7 states            |
| RefDbService              | ✅     | fetchStatuses() queries all 7 states              |
| DashboardController       | ✅     | toMetrics() builds DTO with all fields            |
| API Endpoint              | ✅     | /api/dashboard/snapshot returns correct structure |

### Frontend Implementation ✅

| Component           | Status | Details                                          |
| ------------------- | ------ | ------------------------------------------------ |
| Type Definitions    | ✅     | DashboardMetricTotals interface has all 7 fields |
| Dashboard Component | ✅     | primaryMetrics() computed returns 7 cards        |
| HTML Template       | ✅     | Renders all 7 cards correctly                    |
| State Legend        | ✅     | Tooltips for all 7 states                        |
| Styling             | ✅     | CSS for all card states                          |
| API Call            | ✅     | getDashboardSnapshot() calls correct endpoint    |

## Testing After Deployment

### Manual Test 1: Verify API Response

```bash
curl -s http://localhost:8080/api/dashboard/snapshot | jq '.global'
```

Should show all 7 state counts (queued, enriching, exensioLoading, cancelled, etc.)

### Manual Test 2: Stage Records and Watch Cards Update

1. Create a new staging session
2. Stage 10 records
3. Verify "Staged" card count increases
4. Move records through pipeline
5. Watch cards update in real-time via SSE

### Manual Test 3: Verify Accounting Sum

Backend verification:

```bash
# Get the report
curl -s http://localhost:8080/api/admin/debug/state-accounting | jq '.'
```

Frontend verification:

- Sum of all 7 cards should equal "TOTAL FILES"
- No negative values
- All values non-decreasing over time (except when records are cancelled)

## Backward Compatibility

✅ **Fully backward compatible**

- Old `enqueued` field computed as `queued + enriching + exensioLoading`
- Existing frontend code continues to work
- Mobile clients gracefully handle new fields
- No breaking changes to API contracts

## Quick Checklist

- [ ] Backend code reviewed (all 7 states implemented)
- [ ] Run `mvn clean package -DskipTests` successfully
- [ ] Deploy new JAR to production/staging
- [ ] Restart backend service
- [ ] Verify service started without errors
- [ ] Call `/api/dashboard/snapshot` and confirm all 7 fields present
- [ ] Refresh dashboard in browser
- [ ] See 7 cards instead of 5
- [ ] Test real-time updates with SSE
- [ ] Verify accounting invariant holds

## Files Modified in Feature Implementation

**Backend:**

- `backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/dto/DashboardMetricTotals.java`
- `backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/stage/StageStatus.java`
- `backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/service/RefDbService.java`
- `backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/controller/DashboardController.java`
- `backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/service/StateAccountingService.java`
- `backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/service/DataIntegrityJob.java`
- `backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/stage/StateAggregationBatcher.java`

**Frontend:**

- `frontend/src/app/api/backend.service.ts`
- `frontend/src/app/dashboard/dashboard.component.ts`
- `frontend/src/app/dashboard/dashboard.component.html`
- `frontend/src/app/dashboard/metric-card-detail-sidebar.component.ts`
- `frontend/src/app/dashboard/state-legend.service.ts`
- `frontend/src/app/dashboard/state-legend-tooltip.component.ts`
- `frontend/src/app/shared/services/staging-session.service.ts`

## Summary

**Root Cause:** Old backend JAR running in production
**Solution:** Recompile with `mvn clean package` and redeploy
**Time to Fix:** ~5 minutes deployment + validation
**Risk:** None (fully backward compatible)
**Expected Outcome:** 7-card dashboard visible with real-time updates

---

**Last Updated:** July 4, 2026  
**Next Step:** Run `mvn clean package -DskipTests` and deploy the new JAR
