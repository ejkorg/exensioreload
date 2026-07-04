# Monitor Page State Accounting Analysis

## Executive Summary

The current monitor page dashboard cards **do not account for all records** in your system. Out of 4544 total files, only 1240 are represented in the five visible cards (Staged: 0, In Queue CP: 0, Enrichment: 1000, Completed: 240, Failed: 0). This means **3304 records are in hidden/unmapped states**.

---

## 1. Complete List of Possible Record States

### Database Status Values (SENDER_STAGE.STATUS)

The system tracks 8 distinct database status values:

| Status            | Display Name                                            | Database Source | Description                                                                   |
| ----------------- | ------------------------------------------------------- | --------------- | ----------------------------------------------------------------------------- |
| `pending`         | **Staged**                                              | SENDER_STAGE    | Records staged and ready to dispatch to CP (NEW state)                        |
| `ENQUEUED`        | **In Queue (Pending CP)**                               | SENDER_STAGE    | Records queued but not yet consumed by CP                                     |
| `ENRICHMENT`      | **In Queue (Pending CP)** or **Enrichment/Translation** | SENDER_STAGE    | Records in CP enrichment pipeline; display depends on external queue presence |
| `EXENSIO_LOADING` | **Exensio Loading**                                     | SENDER_STAGE    | Records waiting for Exensio verification/loading                              |
| `PROCESSING`      | **In Queue (Pending CP)** or **Enrichment/Translation** | SENDER_STAGE    | Legacy compatibility status (handled same as ENRICHMENT)                      |
| `DONE`            | **Completed**                                           | SENDER_STAGE    | Records successfully processed and completed                                  |
| `FAILED`          | **Failed**                                              | SENDER_STAGE    | Records that encountered errors during processing                             |
| `CANCELLED`       | **Cancelled**                                           | SENDER_STAGE    | Records marked for soft-delete (excluded from dashboard ready counts)         |
| `UNKNOWN`         | **Unknown**                                             | System default  | Records with no status or NULL status (should not occur)                      |

**Source:** `StatusMapper.getDisplayStatus()` in backend

---

## 2. How Records Are Counted by Status

### Current Dashboard Query Logic

The dashboard uses this SQL aggregation (from `RefDbService.fetchStatuses()`):

```sql
SELECT
  site,
  sender_id,
  MAX(sender_name) AS sender_name,
  COUNT(*) AS total,
  SUM(CASE WHEN status = 'pending' THEN 1 ELSE 0 END) AS ready,
  SUM(CASE WHEN status IN ('ENQUEUED','ENRICHMENT','EXENSIO_LOADING') THEN 1 ELSE 0 END) AS enqueued,
  SUM(CASE WHEN status = 'FAILED' THEN 1 ELSE 0 END) AS failed,
  SUM(CASE WHEN status = 'DONE' THEN 1 ELSE 0 END) AS completed
FROM SENDER_STAGE
WHERE 1=1 [AND request_id = ?]
GROUP BY site, sender_id
```

### Current Card Mappings (StageStatus Record)

The dashboard displays five cards with these mappings:

| Card                      | Field       | SQL Column                                                   | Status Values                            |
| ------------------------- | ----------- | ------------------------------------------------------------ | ---------------------------------------- |
| **Total Files**           | `total`     | `COUNT(*)`                                                   | All records (excluding implicit filters) |
| **Staged**                | `ready`     | `SUM(status = 'pending')`                                    | `pending` only                           |
| **In Queue (Pending CP)** | `enqueued`  | `SUM(status IN ('ENQUEUED','ENRICHMENT','EXENSIO_LOADING'))` | 3 status values                          |
| **Failed**                | `failed`    | `SUM(status = 'FAILED')`                                     | `FAILED` only                            |
| **Completed**             | `completed` | `SUM(status = 'DONE')`                                       | `DONE` only                              |

---

## 3. The Accounting Gap Problem

### Where Are the Missing Records?

**Current visible totals (your example):**

- Total: 4544
- Staged (pending): 0
- In Queue (pending CP): 0
- Enrichment/Translation: 1000 (shown as In Queue while in external queue)
- Completed (DONE): 240
- **Sum: 1240 records visible**
- **Missing: 3304 records (~73%)**

### Likely Reasons Records Are Missing

Records in the following states are **not counted in any card**:

1. **CANCELLED** — Records marked for soft-delete
   - Excluded from dispatch but remain in database
   - Not shown in any card
   - May constitute a significant portion of missing records

2. **EXENSIO_LOADING** — Records in Exensio loading/verification
   - Should appear in "In Queue" but may be hidden
   - Should be included in `enqueued` count (see SQL above)
   - May indicate Exensio module is processing these separately

3. **PROCESSING** — Legacy compatibility status
   - Treated same as ENRICHMENT in SQL aggregation
   - Should appear in "In Queue" but may not be present

4. **NULL / UNKNOWN** — Malformed records
   - Records with no status value
   - Treated as "Unknown" by display logic
   - Not counted in any SQL sum

5. **Implicit Filtering Not Shown in UI**
   - User-based filtering (records staged by different users)
   - Date range filtering not reflected in main cards
   - Site or sender filtering may be active

### Why iddata Removal Marked Records as "Enrichment/Translation"

When you removed `iddata` from the sender queue table (DTP_SENDER_QUEUE_ITEM):

1. Records with matching metadata_id|data_id no longer exist in external queue
2. StatusMapper logic: `inExternalQueue ? "In Queue (pending CP)" : "Enrichment / Translation"`
3. When `inExternalQueue` = false for a record in ENRICHMENT status → displays as **"Enrichment/Translation"**
4. The record remains in ENRICHMENT status but presentation changes
5. This is **not a state transition** — it's a display change based on external queue presence

---

## 4. Are Failed/Errored Records Counted Separately?

### Yes, but with nuance

**FAILED records:**

- Counted separately: `SUM(CASE WHEN status = 'FAILED')`
- Shown in **Failed card**
- These are records that encountered errors during CP enrichment

**CANCELLED records:**

- **NOT counted in any card** (major accounting gap)
- Records explicitly marked for deletion via bulk operations
- Still in database but excluded from dispatch
- Should appear in dashboard for visibility

**ERROR vs FAILED:**

- No database status called "ERROR"
- "ERROR" appears only in external integration statuses (CP status, Exensio status)
- Internal database uses "FAILED" for error terminal state

---

## 5. Do the Five Cards Cover All Pipeline Paths?

### No. The cards miss entire states.

#### Current Card Coverage

| Status                                            | Card Coverage  | Problem                        |
| ------------------------------------------------- | -------------- | ------------------------------ |
| `pending` → Staged                                | ✅ Yes         | Correct                        |
| `ENQUEUED` → In Queue                             | ⚠️ Partial     | May not be present in data     |
| `ENRICHMENT` → In Queue or Enrichment/Translation | ⚠️ Conditional | Depends on external queue      |
| `EXENSIO_LOADING` → In Queue                      | ⚠️ Unclear     | Likely grouped with ENRICHMENT |
| `PROCESSING` → In Queue                           | ⚠️ Legacy      | Grouped with ENRICHMENT        |
| `FAILED` → Failed                                 | ✅ Yes         | Correct                        |
| `DONE` → Completed                                | ✅ Yes         | Correct                        |
| `CANCELLED` → **MISSING**                         | ❌ No          | Not shown anywhere             |
| `UNKNOWN` → **MISSING**                           | ❌ No          | Not shown anywhere             |

#### Complete Pipeline States (as they should be)

A record's complete lifecycle includes:

```
NEW/Staged
  ↓
Dispatch to CP
  ↓
ENRICHMENT (in CP pipeline)
  ├→ DONE (CP completes) ← optional Exensio verification
  │   ├→ EXENSIO_LOADING (Exensio verification)
  │   │   └→ DONE (Exensio confirms)
  │   └→ DONE (if Exensio not configured)
  ├→ FAILED (CP errors)
  └→ TIMEOUT (stuck in ENRICHMENT > 15 mins)
      └→ DONE (with manual-verify flag)

Or alternatively:
pending → CANCELLED (user paused/deleted)
```

**Missing from UI:**

- Explicit CANCELLED card
- Timeout/stuck records (in ENRICHMENT but exceeded timeout)
- Exensio verification queue (separate from CP queue)

---

## 6. Root Cause Analysis

### Why Accounting Doesn't Balance

1. **CANCELLED state is completely invisible**
   - Bulk operations (pause, delete) mark records as CANCELLED
   - Not shown in any dashboard card
   - Example: If 3000+ records were paused/deleted, they'd disappear from visible totals

2. **EXENSIO_LOADING may be separate**
   - If Exensio module is configured and actively loading records
   - These might be managed separately from the main pipeline
   - Not clearly distinguished from ENRICHMENT in current UI

3. **Timeout/Stuck Records**
   - Records stuck in ENRICHMENT for > 15 minutes (configurable)
   - CpLogMonitor should mark them DONE with manual-verify
   - May not be completing correctly, leaving records in ambiguous state

4. **External Queue Dependency**
   - Display status depends on both DB status AND external queue presence
   - Removing data from DTP_SENDER_QUEUE_ITEM changes display without changing DB status
   - UI shows "Enrichment/Translation" when records are actually waiting for CP

5. **SQL Aggregation Gaps**
   - SQL doesn't explicitly sum CANCELLED or UNKNOWN
   - `COUNT(*)` may include records in states not covered by the CASE statements
   - Database queries may apply implicit filters (user, date range) that aren't visible

---

## 7. Recommendations

### Immediate Actions

1. **Add a CANCELLED card**
   - SQL: `SUM(CASE WHEN status = 'CANCELLED' THEN 1 ELSE 0 END)`
   - Will account for paused/deleted records
   - Helps verify the accounting gap

2. **Verify SQL totals**
   - Run this query to find unmapped records:

   ```sql
   SELECT status, COUNT(*) as count
   FROM SENDER_STAGE
   WHERE request_id = '[your-request-id]'
   GROUP BY status
   ORDER BY count DESC
   ```

   - Compare against card totals
   - Look for statuses not in (pending, ENQUEUED, ENRICHMENT, EXENSIO_LOADING, FAILED, DONE, CANCELLED)

3. **Check for UNKNOWN/NULL records**
   - SQL: `SELECT COUNT(*) FROM SENDER_STAGE WHERE status IS NULL OR status NOT IN ('pending','ENQUEUED','ENRICHMENT','EXENSIO_LOADING','PROCESSING','FAILED','DONE','CANCELLED')`
   - These indicate data integrity issues

### Long-Term Solutions

1. **Explicit State Cards**

   ```
   - Total Files: SUM(all)
   - Staged: pending only
   - Queued for CP: ENQUEUED + ENRICHMENT (when in external queue)
   - In Enrichment: ENRICHMENT (when NOT in external queue)
   - Exensio Loading: EXENSIO_LOADING
   - Completed: DONE
   - Failed: FAILED
   - Cancelled/Paused: CANCELLED
   ```

2. **Track Timeout State**
   - Add Elasticsearch flag or separate DB column for "stuck in enrichment"
   - Display as separate card or indicator
   - Monitor with CpLogMonitor timeouts (default: 15 minutes)

3. **Distinguish Queue Types**
   - Separate card for records in external CP queue vs waiting to enter
   - Track "pending dispatch" vs "in CP processing" vs "waiting for verification"

4. **Add Data Integrity Check**
   - Scheduled job to verify all records are in valid states
   - Alert on UNKNOWN or NULL status records
   - Ensure SQL accounting always equals total

---

## 8. Database Schema Notes

### Key Tables

- **SENDER_STAGE** — Primary staging table
  - Fields: id, site, sender_id, status, request_id, ...
  - Status values are stored here
  - Queried by dashboard and monitors

- **DTP_SENDER_QUEUE_ITEM** — External CP queue table
  - Contains records currently in CP processing
  - Composite key: metadata_id|data_id
  - Removal from this table triggers display status change (ENRICHMENT → "Enrichment/Translation")

### State Transition Triggers

- **pending → ENRICHMENT**: SenderDispatchService (when sent to CP)
- **ENRICHMENT → DONE**: CpLogMonitor (when CP completes)
- **ENRICHMENT → EXENSIO_LOADING**: CpLogMonitor (when Exensio verification needed)
- **ENRICHMENT → FAILED**: CpLogMonitor (when CP errors)
- **pending/FAILED → CANCELLED**: DashboardBulkController (user action)
- **ENRICHMENT → DONE (stuck)**: CpLogMonitor (timeout after 15 mins)

---

## 9. Summary Table: What's Tracked vs What's Hidden

| Aspect                                  | Status                  | Details                                                                             |
| --------------------------------------- | ----------------------- | ----------------------------------------------------------------------------------- |
| **Database states defined**             | ✅ 8 distinct           | pending, ENQUEUED, ENRICHMENT, EXENSIO_LOADING, PROCESSING, DONE, FAILED, CANCELLED |
| **Dashboard cards tracking all states** | ❌ No                   | Only 5 cards: Staged, In Queue, Enrichment, Failed, Completed                       |
| **Failed/Error records counted**        | ✅ Yes                  | FAILED card shows errors                                                            |
| **CANCELLED records visible**           | ❌ No                   | Major accounting gap (~3300 missing)                                                |
| **EXENSIO_LOADING tracked**             | ⚠️ Unclear              | May be grouped with ENRICHMENT or separate                                          |
| **Timeout/Stuck records**               | ⚠️ Timeout logic exists | But state not clearly visible to user                                               |
| **NULL/UNKNOWN status**                 | ⚠️ Possible             | Not validated or tracked                                                            |
| **External queue effects display**      | ✅ Yes                  | Record in ENRICHMENT + not in external queue → shows "Enrichment/Translation"       |
| **User-based filtering applied**        | ✅ Yes                  | Records filtered by staged_by/last_requested_by but may not be obvious              |

---

## Next Steps

1. **Run the verification SQL query** (Section 7) to find exactly where the 3304 missing records are
2. **Add CANCELLED card** to see if that accounts for the gap
3. **Review CpLogMonitor logs** to check for timeout transitions
4. **Verify SQL accounting** matches total via direct database query
5. **Consider UI redesign** to show all states explicitly rather than relying on conditional display logic
