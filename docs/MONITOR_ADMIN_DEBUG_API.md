# Monitor Admin Debug Endpoint API Reference

## Overview

The Monitor Admin Debug API provides system administrators with tools to verify dashboard accounting accuracy and diagnose data integrity issues. This endpoint returns a detailed breakdown of record states in the database and compares them against dashboard aggregations.

## Authentication

All debug endpoints require:

- **Authorization:** Bearer token with `ROLE_ADMIN` or `ROLE_SUPER_ADMIN`
- **HTTP Method:** GET
- **Base URL:** `http://[server]/exensioreload/api/admin/debug`

### Example Header

```bash
curl -H "Authorization: Bearer <your-admin-token>" \
  "http://localhost:8080/exensioreload/api/admin/debug/state-accounting"
```

---

## Endpoint: State Accounting Verification

### Request

```
GET /api/admin/debug/state-accounting
```

### Query Parameters (Optional)

| Parameter   | Type    | Description                           |
| ----------- | ------- | ------------------------------------- |
| `requestId` | string  | Filter by specific request/session ID |
| `site`      | string  | Filter by site (e.g., "SITE_A")       |
| `senderId`  | integer | Filter by sender ID                   |

### Example Requests

**Full accounting check (no filters):**

```bash
curl -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8080/exensioreload/api/admin/debug/state-accounting"
```

**Check specific site:**

```bash
curl -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8080/exensioreload/api/admin/debug/state-accounting?site=SITE_A"
```

**Check specific sender:**

```bash
curl -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8080/exensioreload/api/admin/debug/state-accounting?senderId=102"
```

**Check specific request/session:**

```bash
curl -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8080/exensioreload/api/admin/debug/state-accounting?requestId=req-12345"
```

---

## Response Format

### Success Response (HTTP 200)

```json
{
  "timestamp": "2026-07-03T10:30:00Z",
  "request_filters": {
    "site": "SITE_A",
    "senderId": null,
    "requestId": null
  },
  "database": {
    "total_count": 4544,
    "states": {
      "pending": 0,
      "ENQUEUED": 100,
      "ENRICHMENT": 900,
      "EXENSIO_LOADING": 150,
      "PROCESSING": 0,
      "FAILED": 45,
      "DONE": 240,
      "CANCELLED": 3109,
      "UNKNOWN": 0,
      "NULL_STATUS": 0
    },
    "sum_of_states": 4544,
    "imbalance": 0
  },
  "dashboard_cards": {
    "staged": 0,
    "queued": 100,
    "enriching": 900,
    "exensio_loading": 150,
    "failed": 45,
    "completed": 240,
    "cancelled": 3109,
    "sum": 4544
  },
  "data_integrity": {
    "is_valid": true,
    "warnings": [],
    "errors": []
  },
  "by_sender": [
    {
      "site": "SITE_A",
      "sender_id": 102,
      "sender_name": "EC_JND_TESEC_HIST",
      "total": 4544,
      "states": {
        "pending": 0,
        "ENQUEUED": 100,
        "ENRICHMENT": 900,
        "EXENSIO_LOADING": 150,
        "PROCESSING": 0,
        "FAILED": 45,
        "DONE": 240,
        "CANCELLED": 3109,
        "UNKNOWN": 0,
        "NULL_STATUS": 0
      },
      "sum_of_states": 4544
    }
  ],
  "discrepancies": []
}
```

### Response Fields

#### Top Level

| Field             | Type     | Description                                                  |
| ----------------- | -------- | ------------------------------------------------------------ |
| `timestamp`       | ISO 8601 | Time when the report was generated                           |
| `request_filters` | object   | Filters applied to this query                                |
| `database`        | object   | Actual state distribution from the database                  |
| `dashboard_cards` | object   | State distribution as shown on dashboard                     |
| `data_integrity`  | object   | Validation results and warnings                              |
| `by_sender`       | array    | Detailed breakdown per sender (only when no senderId filter) |
| `discrepancies`   | array    | List of detected discrepancies                               |

#### Database Object

```json
"database": {
  "total_count": 4544,           // Total records in SENDER_STAGE
  "states": {                    // Count per state
    "pending": 0,
    "ENQUEUED": 100,
    "ENRICHMENT": 900,
    "EXENSIO_LOADING": 150,
    "PROCESSING": 0,
    "FAILED": 45,
    "DONE": 240,
    "CANCELLED": 3109,
    "UNKNOWN": 0,
    "NULL_STATUS": 0
  },
  "sum_of_states": 4544,         // Sum of all states (should = total_count)
  "imbalance": 0                 // total_count - sum_of_states (0 = balanced)
}
```

#### Dashboard Cards Object

```json
"dashboard_cards": {
  "staged": 0,           // pending count
  "queued": 100,         // ENQUEUED count
  "enriching": 900,      // ENRICHMENT count
  "exensio_loading": 150,// EXENSIO_LOADING count
  "failed": 45,          // FAILED count
  "completed": 240,      // DONE count
  "cancelled": 3109,     // CANCELLED count
  "sum": 4544            // Sum of all cards
}
```

#### Data Integrity Object

```json
"data_integrity": {
  "is_valid": true,
  "warnings": [
    "3 records with NULL status detected"
  ],
  "errors": [
    "1 record with invalid state 'STUCK_UNKNOWN'"
  ]
}
```

#### Discrepancies Array

Each discrepancy represents a data issue:

```json
"discrepancies": [
  {
    "type": "ACCOUNTING_IMBALANCE",
    "severity": "ERROR",
    "description": "Database total does not equal sum of states",
    "total_count": 4544,
    "sum_of_states": 4540,
    "difference": 4
  },
  {
    "type": "INVALID_STATE",
    "severity": "ERROR",
    "description": "Records found in non-standard state",
    "state": "STUCK_UNKNOWN",
    "count": 1,
    "sample_record_ids": [12345]
  },
  {
    "type": "NULL_STATUS",
    "severity": "WARNING",
    "description": "Records with NULL status found",
    "count": 3,
    "sample_record_ids": [67890, 67891, 67892]
  },
  {
    "type": "STUCK_ENRICHMENT",
    "severity": "WARNING",
    "description": "Records stuck in ENRICHMENT beyond timeout",
    "timeout_minutes": 5,
    "count": 7,
    "sample_records": [
      {
        "id": 11111,
        "lot": "S7U180015",
        "minutes_in_enrichment": 127
      }
    ]
  }
]
```

---

## Interpreting Results

### Healthy State

✅ `imbalance = 0` (database.total_count == database.sum_of_states)  
✅ `is_valid = true` (no data integrity errors)  
✅ `discrepancies = []` (no issues detected)  
✅ Database totals match dashboard card sums

### Imbalance Detected

❌ `imbalance > 0` — There are records not counted in any known state

**Example:**

```json
{
  "total_count": 100,
  "sum_of_states": 95,
  "imbalance": 5
}
```

**Investigation Steps:**

1. Check the `discrepancies` array for specific issues (INVALID_STATE, NULL_STATUS)
2. Query the database directly for records with invalid or NULL status:
   ```sql
   SELECT id, status FROM SENDER_STAGE
   WHERE status NOT IN ('pending', 'ENQUEUED', 'ENRICHMENT', 'EXENSIO_LOADING', 'PROCESSING', 'FAILED', 'DONE', 'CANCELLED')
   OR status IS NULL
   ```
3. Contact support with sample record IDs from `sample_record_ids`

### Data Integrity Issues

#### Null Status Warning

```json
{
  "type": "NULL_STATUS",
  "severity": "WARNING",
  "count": 3,
  "sample_record_ids": [67890, 67891, 67892]
}
```

**Meaning:** Some records have `NULL` in the status field  
**Action:** Query the database to investigate these records, check if they need cleanup or correction

#### Invalid State Error

```json
{
  "type": "INVALID_STATE",
  "severity": "ERROR",
  "state": "STUCK_UNKNOWN",
  "count": 1,
  "sample_record_ids": [12345]
}
```

**Meaning:** A record has a status value that is not in the valid set  
**Action:** Investigate the record, potentially contact support to correct the status

#### Stuck Enrichment Warning

```json
{
  "type": "STUCK_ENRICHMENT",
  "severity": "WARNING",
  "timeout_minutes": 5,
  "count": 7
}
```

**Meaning:** Records are in ENRICHMENT state longer than the timeout threshold  
**Action:** The data integrity job will auto-remediate these on the next run, or admins can manually resolve them

---

## Common Issues and Solutions

### Issue 1: Accounting Imbalance

**Symptom:** `imbalance > 0`, cards don't sum to total  
**Cause:** Records in non-standard or NULL status  
**Solution:**

1. Check the `discrepancies` array for INVALID_STATE or NULL_STATUS entries
2. Query database for affected records
3. Correct or delete invalid records
4. Run the verification endpoint again

### Issue 2: Dashboard Cards Don't Match Database

**Symptom:** `database.sum_of_states` matches `total_count`, but `dashboard_cards.sum` differs  
**Cause:** Dashboard query may be caching outdated data or using different filters  
**Solution:**

1. Refresh the browser dashboard
2. Check if filters (site, sender) are applied correctly
3. Restart the backend service if data is stale
4. Check backend logs for query errors

### Issue 3: High Stuck Enrichment Count

**Symptom:** Many records with `minutes_in_enrichment >> timeout_minutes`  
**Cause:** CP pipeline bottleneck or process failure  
**Solution:**

1. Check CP system logs and performance metrics
2. Increase timeout threshold if false positives (see [Configuration Guide](./MONITOR_CONFIGURATION_GUIDE.md))
3. The data integrity job will auto-remediate on next run
4. Contact support if the issue persists

### Issue 4: Discrepancy Shows, But Dashboard Looks Fine

**Symptom:** Discrepancies reported, but accounting appears balanced  
**Cause:** Potential race condition or recently-fixed issue  
**Solution:**

1. Wait 1-2 seconds and run the endpoint again (data may be in transit)
2. Check if any background jobs are running
3. If persistent, investigate the specific record IDs in sample_record_ids

---

## Error Responses

### 401 Unauthorized

```json
{
  "timestamp": "2026-07-03T10:30:00Z",
  "status": 401,
  "error": "Unauthorized",
  "message": "Authentication required"
}
```

**Cause:** Missing or invalid Bearer token  
**Solution:** Ensure your token has `ROLE_ADMIN` or `ROLE_SUPER_ADMIN`

### 403 Forbidden

```json
{
  "timestamp": "2026-07-03T10:30:00Z",
  "status": 403,
  "error": "Forbidden",
  "message": "User does not have admin privileges"
}
```

**Cause:** Token is valid, but user doesn't have admin role  
**Solution:** Contact your administrator to grant admin role

### 500 Internal Server Error

```json
{
  "timestamp": "2026-07-03T10:30:00Z",
  "status": 500,
  "error": "Internal Server Error",
  "message": "An error occurred during verification"
}
```

**Cause:** Backend error during query or processing  
**Solution:** Check backend logs, ensure database is accessible, contact support

---

## Usage Scenarios

### Scenario 1: Daily Accounting Audit

**Goal:** Verify accounting is balanced at the end of each day

```bash
curl -s -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8080/exensioreload/api/admin/debug/state-accounting" \
  | jq '.data_integrity.is_valid'

# Output: true (all good) or false (issues detected)
```

### Scenario 2: Investigate Accounting Imbalance

**Goal:** Find missing records and identify the issue

```bash
curl -s -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8080/exensioreload/api/admin/debug/state-accounting" \
  | jq '.discrepancies[] | select(.type == "INVALID_STATE")'

# Review sample_record_ids for manual investigation
```

### Scenario 3: Check Specific Sender Accounting

**Goal:** Verify a specific sender's records are balanced

```bash
curl -s -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8080/exensioreload/api/admin/debug/state-accounting?senderId=102" \
  | jq '.database | {total_count, sum_of_states, imbalance}'
```

### Scenario 4: Monitor Stuck Records

**Goal:** Check for records stuck in enrichment

```bash
curl -s -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8080/exensioreload/api/admin/debug/state-accounting" \
  | jq '.discrepancies[] | select(.type == "STUCK_ENRICHMENT")'
```

---

## Integration with Monitoring

### Nagios/Icinga Check

Create a monitoring check that alerts if accounting is imbalanced:

```bash
#!/bin/bash
RESPONSE=$(curl -s -H "Authorization: Bearer $ADMIN_TOKEN" \
  "http://localhost:8080/exensioreload/api/admin/debug/state-accounting")

IS_VALID=$(echo "$RESPONSE" | jq '.data_integrity.is_valid')

if [ "$IS_VALID" = "true" ]; then
  echo "OK: Accounting is valid"
  exit 0
else
  echo "CRITICAL: Accounting imbalance detected"
  exit 2
fi
```

### Automated Reporting

Schedule daily/hourly runs to generate accounting reports:

```bash
# Generate daily report
curl -s -H "Authorization: Bearer $ADMIN_TOKEN" \
  "http://localhost:8080/exensioreload/api/admin/debug/state-accounting" \
  > /var/reports/accounting_$(date +%Y-%m-%d).json
```

---

## Related Documentation

- [MONITOR_CONFIGURATION_GUIDE.md](./MONITOR_CONFIGURATION_GUIDE.md) — Configuration options for integrity checks
- [MONITOR_DASHBOARD_USER_GUIDE.md](./MONITOR_DASHBOARD_USER_GUIDE.md) — Dashboard user guide
- [MONITOR_DASHBOARD_TROUBLESHOOTING.md](./MONITOR_DASHBOARD_TROUBLESHOOTING.md) — Troubleshooting guide

</content>
</invoke>
