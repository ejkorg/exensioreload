# Monitor Dashboard User Guide

## Overview

The Monitor Dashboard provides real-time visibility into the data processing pipeline, displaying the distribution of records across all stages from initial staging through completion or failure. This guide explains each metric card, their meanings, and how to interpret the data.

## Dashboard Layout

The monitor page displays **7 metric cards** representing the complete data processing pipeline:

```
┌─────────────────────────────────────────────────────┐
│ Total Files: [N]                                    │
├──────────┬──────────┬──────────┬──────────┐
│ Staged   │ Queued   │ Enriching│ Exensio  │
│    A     │    B     │    C     │    D     │
├──────────┼──────────┼──────────┼──────────┤
│ Completed│  Failed  │ Cancelled│         │
│    E     │    F     │    G     │  [Alerts]│
└──────────┴──────────┴──────────┴──────────┘
```

### Accounting Balance

The sum of all metric cards should equal or exceed the **Total Files** count:

```
Staged + Queued + Enriching + Exensio + Completed + Failed + Cancelled ≥ Total Files
```

If this balance is not maintained, it indicates a data integrity issue. See [Troubleshooting](#troubleshooting-dashboard-issues).

---

## Metric Cards Explained

### 1. **Staged** Card (Pending Records)

**Count:** Records ready for dispatch to the CP system  
**Status:** `pending`  
**What It Means:**

- These are newly uploaded records waiting to be sent to the CP pipeline
- They have been validated and are ready to move forward
- High count indicates recent uploads or potential bottleneck at dispatch

**Typical Flow:**

```
File Upload → [STAGED] → Queued → Enriching → Completed
```

**Actions:**

- Click the card to view sample records in the staged state
- Check associated files in the Staging Session for validation details

---

### 2. **Queued** Card (Queued for CP Processing)

**Count:** Records waiting in the CP system queue  
**Status:** `ENQUEUED`  
**What It Means:**

- These records have been sent to the CP system but are not yet processing
- They are waiting in the external processing queue for a slot
- High count indicates CP system may be overloaded or processing slowly

**Typical Flow:**

```
Staged → [QUEUED] → Enriching → Completed
```

**Actions:**

- Click the card to view records in the queue
- If queued count is unusually high, check CP system logs for bottlenecks

---

### 3. **Enriching** Card (In CP Enrichment)

**Count:** Records currently being processed by CP enrichment  
**Status:** `ENRICHMENT`  
**What It Means:**

- These records are actively undergoing CP enrichment/translation
- They should progress through this state relatively quickly (< 5 minutes typically)
- High count suggests CP system is working hard, or records are stuck

**Typical Flow:**

```
Queued → [ENRICHING] → Completed
```

**Stuck Records Alert:**

- If a record stays in ENRICHMENT longer than the timeout (default: 5 minutes), it will be flagged
- See [Stuck Records Badge](#stuck-records-badge) below for details

**Actions:**

- Click the card to view records in enrichment
- If count remains very high, check CP system performance metrics
- Check for stuck records badge (red indicator)

---

### 4. **Exensio Loading** Card (Exensio Verification)

**Count:** Records undergoing Exensio-specific verification  
**Status:** `EXENSIO_LOADING`  
**What It Means:**

- These records are in the Exensio verification pipeline (if Exensio integration is enabled)
- This is a separate stage from CP enrichment, allowing independent monitoring
- Exensio performs additional validation/enrichment on the enriched data
- If Exensio is not configured, this card will show 0 or not appear

**Typical Flow:**

```
Enriching → [EXENSIO_LOADING] → Completed
```

**Actions:**

- Click the card to view records being verified by Exensio
- Monitor this separately from Enriching to identify Exensio bottlenecks

---

### 5. **Completed** Card (Successfully Processed)

**Count:** Records that successfully completed the pipeline  
**Status:** `DONE`  
**What It Means:**

- These records have been fully enriched and validated
- They are ready for downstream consumption
- This is the successful terminal state for a record

**Typical Flow:**

```
Enriching → [COMPLETED]
```

**Actions:**

- Click the card to view sample completed records
- Verify that the records match your expectations

---

### 6. **Failed** Card (Processing Failures)

**Count:** Records that encountered errors during processing  
**Status:** `FAILED`  
**What It Means:**

- These records could not be processed due to errors
- The error reason is typically captured in the system logs
- Failed records are terminal and require investigation or deletion

**Typical Flow:**

```
Enriching → [FAILED] (error occurred)
```

**Actions:**

- Click the card to view failed records
- Check the record details for error messages
- Contact support or check logs for root cause analysis

---

### 7. **Cancelled** Card (Paused or Deleted)

**Count:** Records that were paused or soft-deleted  
**Status:** `CANCELLED`  
**What It Means:**

- These records were marked as paused/deleted by a user action
- They are excluded from active processing but remain in the database
- This might happen during bulk pause operations or manual deletion

**Typical Flow:**

```
[Any Stage] → [CANCELLED] (user action)
```

**Actions:**

- Click the card to view cancelled records
- Check the record details to see which stage they were in when cancelled
- These records may be permanently deleted or archived later

---

## State Transition Legend

### Understanding Record Flow

Hover over any metric card to see a tooltip explaining possible transitions:

| From      | To        | When                           |
| --------- | --------- | ------------------------------ |
| Staged    | Queued    | Dispatch initiated             |
| Queued    | Enriching | CP system processes record     |
| Enriching | Exensio   | Exensio verification enabled   |
| Exensio   | Completed | Exensio verification passes    |
| Enriching | Completed | No Exensio, or Exensio skipped |
| Any       | Failed    | Error during processing        |
| Any       | Cancelled | User pauses or deletes record  |

### Invalid States

If you see records in unexpected states (e.g., UNKNOWN or invalid status values), this indicates a data integrity issue. See [Troubleshooting](#troubleshooting-dashboard-issues).

---

## Real-Time Updates

### Live Updates via SSE

The dashboard updates in real-time as records change states:

- **Update Frequency:** Metric cards update within 1 second of state changes
- **Batching:** Multiple rapid state changes are batched into a single aggregation update to reduce traffic
- **Reconnection:** If your browser loses connection to the server, the dashboard will automatically refresh card counts when reconnected

### Visual Feedback

- Card counts may briefly highlight/fade to indicate a change
- The update timestamp shows when each card was last updated
- Connection status is visible at the top of the page (green = connected, red = disconnected)

---

## Interpreting the Dashboard

### Healthy Pipeline Indicators

✅ **Balanced accounting:** Sum of all cards ≈ Total Files  
✅ **Low Queued count:** Records move through quickly  
✅ **Low Enriching count:** Enrichment completes fast  
✅ **High Completed count:** Success rate is good  
✅ **Low Failed count:** Few processing errors  
✅ **Low Stuck records:** No timeout alerts

### Warning Signs

⚠️ **Accounting imbalance:** Cards don't sum to total  
⚠️ **High Queued count:** Possible CP bottleneck  
⚠️ **High Enriching count:** Records stuck in enrichment  
⚠️ **Stuck records badge:** Records exceeding timeout threshold  
⚠️ **High Failed count:** Increase in processing errors

---

## Stuck Records Badge

### What is a Stuck Record?

A record is considered **stuck** if it remains in the ENRICHMENT state for longer than the configured timeout (default: **5 minutes**).

### Visual Indicator

When stuck records are detected, a **red alert badge** appears on the dashboard:

```
[🔴 3 Stuck]  ← Shows count of stuck records
```

### Stuck Records Detail View

Click the stuck records badge to see:

- **List of stuck records** with details (ID, lot, wafer, time in enrichment)
- **Duration in enrichment** for each stuck record (e.g., "8 minutes 32 seconds")
- **Timeline** showing when they entered enrichment

### What Happens to Stuck Records

- **Auto-Remediation:** After the scheduled integrity check (hourly by default), stuck records are automatically marked as completed with a manual-verify flag
- **Admin Alert:** An admin alert is emitted when stuck records are detected
- **Logging:** The auto-remediation action is logged with details for audit purposes

### Actions

- **Investigate:** Click stuck records to view details and check logs
- **Manual Resolution:** Admins can manually mark stuck records as done or failed from the detail view
- **Adjust Timeout:** If false positives occur, contact your admin to increase the timeout threshold (see [Configuration](#configuration))

---

## Filtering and Scoping

### By Sender

The dashboard typically displays metrics for a specific sender. To change the sender:

1. Use the **Sender Selector** dropdown at the top
2. The cards and all metrics will update to show data for that sender only
3. Accounting balance applies per sender

### By Site

Similarly, to filter by site:

1. Use the **Site Selector** dropdown
2. Cards update to show data for that site
3. Cross-sender metrics can be aggregated using the aggregation controls

---

## Click-to-Explore Detail View

Each metric card is clickable and opens a **detail sidebar** showing:

- **Top 20 sample records** in that state
- **Columns displayed:**
  - Status (current state)
  - Filename (source file)
  - Lot ID
  - Wafer number
  - Created timestamp
  - Duration in state

**Actions in detail view:**

- **Sort:** Click column headers to sort (default: most recent first)
- **Copy:** Click a record to copy its ID
- **Close:** Click the X or outside the sidebar to close

---

## Troubleshooting Dashboard Issues

See [MONITOR_DASHBOARD_TROUBLESHOOTING.md](./MONITOR_DASHBOARD_TROUBLESHOOTING.md) for detailed troubleshooting steps.

---

## Configuration and Advanced Topics

For information about:

- **Timeout threshold configuration** → See [MONITOR_CONFIGURATION_GUIDE.md](./MONITOR_CONFIGURATION_GUIDE.md)
- **Data integrity job settings** → See [MONITOR_CONFIGURATION_GUIDE.md](./MONITOR_CONFIGURATION_GUIDE.md)
- **Admin debug endpoints** → See [MONITOR_ADMIN_DEBUG_API.md](./MONITOR_ADMIN_DEBUG_API.md)

---

## FAQ

### Q: Why don't the cards always sum to the Total Files count?

**A:** They should! If they don't, there's likely a data integrity issue. See the troubleshooting guide for steps to diagnose and resolve.

### Q: What if a record is in a state longer than expected?

**A:** Check the stuck records badge. If the timeout is configured too low, contact your admin. Otherwise, investigate the record details and check system logs.

### Q: Can I manually change a record's state?

**A:** This depends on your permissions. Admins can use the debug endpoint to verify state and remediate issues. Contact your admin for state changes.

### Q: How often does the accounting check run?

**A:** By default, the data integrity check runs hourly. This can be configured. See [MONITOR_CONFIGURATION_GUIDE.md](./MONITOR_CONFIGURATION_GUIDE.md).

### Q: What is the difference between Enriching and Exensio Loading?

**A:** **Enriching** is CP pipeline enrichment/translation. **Exensio Loading** is separate Exensio verification (if enabled). A record typically goes through both stages if Exensio is configured.

---

## Related Documentation

- [MONITOR_CONFIGURATION_GUIDE.md](./MONITOR_CONFIGURATION_GUIDE.md) — Configuration options
- [MONITOR_ADMIN_DEBUG_API.md](./MONITOR_ADMIN_DEBUG_API.md) — Admin verification endpoint
- [MONITOR_DASHBOARD_TROUBLESHOOTING.md](./MONITOR_DASHBOARD_TROUBLESHOOTING.md) — Troubleshooting guide

</content>
</invoke>
