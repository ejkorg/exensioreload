# Device Filter Reporting - API Documentation

## Overview

This document describes the API enhancements for Device Filter Reporting feature. All new endpoints and parameters maintain backward compatibility with the existing API.

## Base URL

```
https://api.example.com/api/
```

All requests require authentication via Bearer token in the `Authorization` header.

## New Endpoints

### GET /sessions/devices

Retrieve distinct device values for filter dropdown population.

**Description:** Returns a list of unique non-NULL device identifiers from the staging table. Use this endpoint to populate device filter dropdowns in the UI.

**Endpoint:** `GET /api/sessions/devices`

**Headers:**

```
Authorization: Bearer <TOKEN>
Content-Type: application/json
```

**Query Parameters:**

| Parameter   | Type | Required | Description                                                                                                    |
| ----------- | ---- | -------- | -------------------------------------------------------------------------------------------------------------- |
| `sessionId` | Long | No       | Optional session ID to get devices for specific session only. If omitted, returns devices across all sessions. |

**Request Examples:**

Get devices from all sessions:

```bash
curl -X GET "http://localhost:8080/api/sessions/devices" \
  -H "Authorization: Bearer YOUR_TOKEN" \
  -H "Content-Type: application/json"
```

Get devices from specific session:

```bash
curl -X GET "http://localhost:8080/api/sessions/devices?sessionId=12345" \
  -H "Authorization: Bearer YOUR_TOKEN" \
  -H "Content-Type: application/json"
```

**Response:**

**Status Code:** `200 OK`

**Response Body:**

```json
["IR71939", "IR72000", "IR72100", "IR72500"]
```

**Error Responses:**

| Status | Description                                                        |
| ------ | ------------------------------------------------------------------ |
| 401    | Unauthorized - Invalid or missing token                            |
| 403    | Forbidden - User lacks permission                                  |
| 404    | Not Found - Session ID not found (if sessionId parameter provided) |
| 500    | Internal Server Error                                              |

**Example Error:**

```json
{
  "error": "SESSION_NOT_FOUND",
  "message": "Session with ID 99999 not found",
  "timestamp": "2026-07-04T12:34:56Z"
}
```

**Performance:** Should complete within 1 second even with 1M+ records. Uses indexed device column.

---

## Updated Endpoints

### GET /sessions

Retrieve staging sessions with optional device filtering.

**Description:** Returns paginated list of staging sessions. Now supports device filtering to show only sessions containing specified devices.

**Endpoint:** `GET /api/sessions`

**Headers:**

```
Authorization: Bearer <TOKEN>
Content-Type: application/json
```

**Query Parameters:**

| Parameter     | Type     | Required | Description                                                                                                                                                                     |
| ------------- | -------- | -------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `devices`     | String[] | No       | **NEW** - Comma-separated list of device identifiers to filter by. Results include only sessions with payloads matching any of these devices. If omitted, all devices included. |
| `site`        | String   | No       | Filter by site name                                                                                                                                                             |
| `environment` | String   | No       | Filter by environment (DEV/PROD/etc)                                                                                                                                            |
| `senderId`    | Integer  | No       | Filter by sender ID                                                                                                                                                             |
| `startDate`   | String   | No       | Filter by start date (ISO 8601: YYYY-MM-DD)                                                                                                                                     |
| `endDate`     | String   | No       | Filter by end date (ISO 8601: YYYY-MM-DD)                                                                                                                                       |
| `status`      | String   | No       | Filter by session status (ACTIVE/COMPLETED/FAILED)                                                                                                                              |
| `page`        | Integer  | No       | Page number (0-indexed, default: 0)                                                                                                                                             |
| `limit`       | Integer  | No       | Results per page (default: 20, max: 100)                                                                                                                                        |
| `sort`        | String   | No       | Sort field (e.g., "createdAt:desc")                                                                                                                                             |

**Request Examples:**

Get all sessions (backward compatible):

```bash
curl -X GET "http://localhost:8080/api/sessions?limit=20" \
  -H "Authorization: Bearer YOUR_TOKEN"
```

Filter by single device:

```bash
curl -X GET "http://localhost:8080/api/sessions?devices=IR71939&limit=20" \
  -H "Authorization: Bearer YOUR_TOKEN"
```

Filter by multiple devices:

```bash
curl -X GET "http://localhost:8080/api/sessions?devices=IR71939&devices=IR72000&limit=20" \
  -H "Authorization: Bearer YOUR_TOKEN"
```

Or using comma-separated format:

```bash
curl -X GET "http://localhost:8080/api/sessions?devices=IR71939,IR72000&limit=20" \
  -H "Authorization: Bearer YOUR_TOKEN"
```

Combine device filter with other filters:

```bash
curl -X GET "http://localhost:8080/api/sessions?devices=IR71939&site=SITE_A&status=COMPLETED&startDate=2026-01-01&endDate=2026-12-31&page=0&limit=20" \
  -H "Authorization: Bearer YOUR_TOKEN"
```

**Response:**

**Status Code:** `200 OK`

**Response Body:**

```json
{
  "items": [
    {
      "id": 12345,
      "name": "Production Load Session",
      "site": "SITE_A",
      "environment": "PROD",
      "createdAt": "2026-07-01T10:00:00Z",
      "status": "COMPLETED",
      "payloadCount": 1500,
      "successCount": 1485,
      "failureCount": 15,
      "devices": ["IR71939", "IR72000"]
    },
    {
      "id": 12346,
      "name": "Development Load Session",
      "site": "SITE_B",
      "environment": "DEV",
      "createdAt": "2026-07-02T14:30:00Z",
      "status": "COMPLETED",
      "payloadCount": 500,
      "successCount": 495,
      "failureCount": 5,
      "devices": ["IR71939"]
    }
  ],
  "page": 0,
  "limit": 20,
  "total": 42,
  "hasMore": true
}
```

**Device Filtering Behavior:**

- **Multiple devices:** Results include sessions with payloads matching ANY of the specified devices (OR logic)
- **NULL device handling:** Payloads with NULL device values are excluded from filtered results
- **Empty filter:** When `devices` parameter omitted, all devices included (backward compatible)
- **No matches:** Returns empty array if no sessions match filter criteria

**Error Responses:**

| Status | Description                             |
| ------ | --------------------------------------- |
| 400    | Bad Request - Invalid parameters        |
| 401    | Unauthorized - Invalid or missing token |
| 403    | Forbidden - User lacks permission       |
| 500    | Internal Server Error                   |

---

### GET /analytics/summary

Retrieve analytics summary with optional device filtering.

**Description:** Returns aggregated analytics metrics across all sessions. Now supports device filtering to analyze device-specific trends.

**Endpoint:** `GET /api/analytics/summary`

**Headers:**

```
Authorization: Bearer <TOKEN>
Content-Type: application/json
```

**Query Parameters:**

| Parameter     | Type     | Required | Description                                                                                                                                          |
| ------------- | -------- | -------- | ---------------------------------------------------------------------------------------------------------------------------------------------------- |
| `devices`     | String[] | No       | **NEW** - Comma-separated list of device identifiers to filter by. Analytics includes only data for these devices. If omitted, all devices included. |
| `site`        | String   | No       | Filter by site name                                                                                                                                  |
| `startDate`   | String   | No       | Filter by start date (ISO 8601: YYYY-MM-DD)                                                                                                          |
| `endDate`     | String   | No       | Filter by end date (ISO 8601: YYYY-MM-DD)                                                                                                            |
| `environment` | String   | No       | Filter by environment                                                                                                                                |
| `status`      | String   | No       | Filter by status                                                                                                                                     |

**Request Examples:**

Get analytics for all devices (backward compatible):

```bash
curl -X GET "http://localhost:8080/api/analytics/summary?startDate=2026-01-01&endDate=2026-12-31" \
  -H "Authorization: Bearer YOUR_TOKEN"
```

Get analytics for specific device:

```bash
curl -X GET "http://localhost:8080/api/analytics/summary?devices=IR71939&startDate=2026-01-01&endDate=2026-12-31" \
  -H "Authorization: Bearer YOUR_TOKEN"
```

Get analytics for multiple devices:

```bash
curl -X GET "http://localhost:8080/api/analytics/summary?devices=IR71939&devices=IR72000&startDate=2026-01-01&endDate=2026-12-31" \
  -H "Authorization: Bearer YOUR_TOKEN"
```

Combine with other filters:

```bash
curl -X GET "http://localhost:8080/api/analytics/summary?devices=IR71939&site=SITE_A&status=COMPLETED&startDate=2026-01-01&endDate=2026-12-31" \
  -H "Authorization: Bearer YOUR_TOKEN"
```

**Response:**

**Status Code:** `200 OK`

**Response Body:**

```json
{
  "metrics": {
    "totalPayloads": 15000,
    "successfulPayloads": 14850,
    "failedPayloads": 150,
    "successRate": 99.0,
    "averageProcessingTime": 450,
    "totalProcessingTime": 6750000,
    "uniqueSessions": 42,
    "uniqueSites": 3,
    "uniqueDevices": 2,
    "deviceBreakdown": {
      "IR71939": {
        "totalPayloads": 10000,
        "successfulPayloads": 9950,
        "failedPayloads": 50,
        "successRate": 99.5
      },
      "IR72000": {
        "totalPayloads": 5000,
        "successfulPayloads": 4900,
        "failedPayloads": 100,
        "successRate": 98.0
      }
    }
  },
  "period": {
    "startDate": "2026-01-01",
    "endDate": "2026-12-31"
  },
  "appliedFilters": {
    "devices": ["IR71939", "IR72000"],
    "site": null,
    "environment": null,
    "status": null
  }
}
```

**Device Filtering Behavior:**

- **Multiple devices:** Analytics includes data from payloads matching ANY device (OR logic)
- **NULL device handling:** Payloads with NULL device excluded from calculations
- **Empty filter:** When `devices` parameter omitted, all payloads included (backward compatible)
- **Device breakdown:** Device-specific metrics always included when devices queried

---

### GET /dashboard/metrics

Retrieve dashboard metrics with optional device filtering.

**Description:** Returns real-time and historical metrics for dashboard display. Supports device filtering to monitor device-specific performance.

**Endpoint:** `GET /api/dashboard/metrics`

**Headers:**

```
Authorization: Bearer <TOKEN>
Content-Type: application/json
```

**Query Parameters:**

| Parameter   | Type     | Required | Description                                                                                                  |
| ----------- | -------- | -------- | ------------------------------------------------------------------------------------------------------------ |
| `devices`   | String[] | No       | **NEW** - Comma-separated list of device identifiers. Dashboard metrics include only data for these devices. |
| `site`      | String   | No       | Filter by site                                                                                               |
| `timeRange` | String   | No       | Time range (LAST_HOUR, LAST_DAY, LAST_WEEK, default: LAST_DAY)                                               |

**Request Examples:**

Get dashboard metrics for all devices:

```bash
curl -X GET "http://localhost:8080/api/dashboard/metrics?timeRange=LAST_DAY" \
  -H "Authorization: Bearer YOUR_TOKEN"
```

Get dashboard metrics for specific device:

```bash
curl -X GET "http://localhost:8080/api/dashboard/metrics?devices=IR71939&timeRange=LAST_DAY" \
  -H "Authorization: Bearer YOUR_TOKEN"
```

Get dashboard metrics for multiple devices:

```bash
curl -X GET "http://localhost:8080/api/dashboard/metrics?devices=IR71939&devices=IR72000&timeRange=LAST_DAY" \
  -H "Authorization: Bearer YOUR_TOKEN"
```

**Response:**

**Status Code:** `200 OK`

**Response Body:**

```json
{
  "summary": {
    "activeSessionsTotal": 5,
    "completedSessionsTotal": 42,
    "failedSessionsTotal": 2,
    "pendingPayloads": 250,
    "totalPayloadsProcessed": 95000,
    "overallSuccessRate": 98.5
  },
  "devices": {
    "IR71939": {
      "activeCount": 2,
      "successRate": 99.0,
      "lastUpdate": "2026-07-04T12:30:00Z"
    },
    "IR72000": {
      "activeCount": 3,
      "successRate": 98.0,
      "lastUpdate": "2026-07-04T12:29:30Z"
    }
  },
  "timeRange": "LAST_DAY",
  "appliedFilters": {
    "devices": ["IR71939", "IR72000"]
  }
}
```

---

## API Response Codes

### Success Responses

| Code | Meaning    | Use Case                                  |
| ---- | ---------- | ----------------------------------------- |
| 200  | OK         | Successful GET request                    |
| 201  | Created    | Successful POST request creating resource |
| 204  | No Content | Successful DELETE request                 |

### Client Error Responses

| Code | Meaning              | Example                                       |
| ---- | -------------------- | --------------------------------------------- |
| 400  | Bad Request          | Invalid device format in query parameter      |
| 401  | Unauthorized         | Missing or invalid authentication token       |
| 403  | Forbidden            | User lacks permission to access device data   |
| 404  | Not Found            | Referenced session/resource not found         |
| 409  | Conflict             | Device filter conflicts with other parameters |
| 422  | Unprocessable Entity | Invalid filter combination                    |

### Server Error Responses

| Code | Meaning               | Action                                     |
| ---- | --------------------- | ------------------------------------------ |
| 500  | Internal Server Error | Retry request, contact support if persists |
| 502  | Bad Gateway           | Service temporarily unavailable            |
| 503  | Service Unavailable   | Server maintenance, retry later            |

---

## Filtering Behavior

### Device Filter Semantics

When `devices` parameter provided:

1. **Multiple devices** are combined with OR logic
   - `devices=IR71939&devices=IR72000` → Results with either device
2. **NULL devices** are excluded
   - Payloads with device = NULL not included in filtered results
   - To include NULL devices, must make separate unfiltered request

3. **Empty device list** treated same as parameter omitted
   - `devices=` → Same as no device parameter → All devices included

4. **Invalid device values**
   - Unknown device names return empty results (not an error)
   - Example: `devices=INVALID_DEVICE` → Empty results

### Multi-Filter Composition

When combining device filter with other filters:

```
All filters combined with AND logic

Filters:
  devices = ["IR71939"] AND
  site = "SITE_A" AND
  status = "COMPLETED"

Results: Only payloads matching ALL conditions
```

---

## Pagination with Device Filters

Device filters work correctly with pagination:

```bash
# Get first page of filtered results
curl -X GET "http://localhost:8080/api/sessions?devices=IR71939&page=0&limit=20"

# Get second page
curl -X GET "http://localhost:8080/api/sessions?devices=IR71939&page=1&limit=20"

# Total count includes only filtered results
{
  "total": 150,  # This is total for IR71939 only
  "page": 0,
  "limit": 20,
  "items": [...]
}
```

---

## Backward Compatibility

All API changes are fully backward compatible:

1. **Device parameter optional** - Existing code works without changes
2. **Default behavior unchanged** - Requests without device filter return all data
3. **No breaking changes** - Response format extended, not modified

Example - Existing code continues to work:

```bash
# Old code (still works)
curl "http://localhost:8080/api/sessions?limit=20"

# Returns same results as before (all devices)
```

---

## Rate Limiting

Device filter queries subject to standard rate limits:

| Endpoint           | Limit | Window |
| ------------------ | ----- | ------ |
| /sessions/devices  | 1000  | 1 hour |
| /sessions          | 500   | 1 hour |
| /analytics/summary | 200   | 1 hour |
| /dashboard/metrics | 1000  | 1 hour |

---

## Authentication

All endpoints require authentication via Bearer token:

```bash
curl -X GET "http://localhost:8080/api/sessions/devices" \
  -H "Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..."
```

### Token Refresh

Token automatically refreshed on 401 response. See auth documentation for details.

---

## Error Handling

### Handling Errors in Client Code

```javascript
// JavaScript example
fetch('/api/sessions/devices', {
  headers: { Authorization: `Bearer ${token}` },
})
  .then((response) => {
    if (!response.ok) {
      throw new Error(`API error: ${response.status}`);
    }
    return response.json();
  })
  .then((devices) => {
    // Use devices in filter dropdown
    populateFilterDropdown(devices);
  })
  .catch((error) => {
    console.error('Failed to load devices:', error);
    // Show error message to user
    showErrorToast('Unable to load device filter options');
  });
```

```bash
# Bash example
DEVICES=$(curl -s -X GET "http://localhost:8080/api/sessions/devices" \
  -H "Authorization: Bearer $TOKEN")

if [ $? -eq 0 ]; then
  echo "Devices: $DEVICES"
else
  echo "Error: Failed to retrieve devices"
fi
```

---

## Examples by Use Case

### Use Case 1: Populate Device Filter Dropdown

```bash
# Get distinct devices for filter
curl -X GET "http://localhost:8080/api/sessions/devices" \
  -H "Authorization: Bearer YOUR_TOKEN" | \
  jq -r '.[]'

# Output:
# IR71939
# IR72000
# IR72100
```

### Use Case 2: Filter Sessions by Device

```bash
# Get sessions for specific device
curl -X GET "http://localhost:8080/api/sessions?devices=IR71939&limit=20&sort=createdAt:desc" \
  -H "Authorization: Bearer YOUR_TOKEN" | \
  jq '.items[] | {id, name, createdAt, status}'
```

### Use Case 3: Analytics for Device

```bash
# Get analytics for specific device
curl -X GET "http://localhost:8080/api/analytics/summary?devices=IR71939&startDate=2026-01-01&endDate=2026-12-31" \
  -H "Authorization: Bearer YOUR_TOKEN" | \
  jq '.metrics | {totalPayloads, successRate, averageProcessingTime}'
```

### Use Case 4: Multi-Device Analysis

```bash
# Compare metrics across devices
curl -X GET "http://localhost:8080/api/analytics/summary?devices=IR71939&devices=IR72000&startDate=2026-06-01&endDate=2026-07-04" \
  -H "Authorization: Bearer YOUR_TOKEN" | \
  jq '.metrics.deviceBreakdown | to_entries[] | {device: .key, successRate: .value.successRate}'
```

### Use Case 5: Dashboard Filtered View

```bash
# Get dashboard metrics for device
curl -X GET "http://localhost:8080/api/dashboard/metrics?devices=IR71939&timeRange=LAST_DAY" \
  -H "Authorization: Bearer YOUR_TOKEN" | \
  jq '.summary'
```

---

## Migration Guide

### Migrating from Pre-Feature API

No code changes required. Existing API calls continue to work:

**Before:**

```bash
curl "http://localhost:8080/api/sessions?limit=20"
```

**After (supports new feature, backward compatible):**

```bash
# Same as before - returns all devices
curl "http://localhost:8080/api/sessions?limit=20"

# Or with device filter
curl "http://localhost:8080/api/sessions?devices=IR71939&limit=20"
```

---

## Performance Considerations

### Optimal Queries

✅ **Good**: Specific device filter with date range

```bash
/api/sessions?devices=IR71939&startDate=2026-01-01&endDate=2026-03-31&limit=20
```

✅ **Good**: Multiple devices with pagination

```bash
/api/sessions?devices=IR71939&devices=IR72000&page=0&limit=50
```

❌ **Avoid**: No filters on large datasets

```bash
/api/sessions?limit=10000  # Could be slow without pagination
```

### Query Performance

| Query                     | Est. Time | Notes                   |
| ------------------------- | --------- | ----------------------- |
| All devices, all time     | <2s       | Uses indexes, paginated |
| Single device, date range | <200ms    | Highly optimized        |
| 5 devices, no date filter | <500ms    | Still uses index        |
| Distinct devices list     | <100ms    | Cached when possible    |

---

## Versioning

Current API version: **v1**

Future versions may be accessed via:

```bash
/api/v2/sessions?devices=...
```

Current version maintains backward compatibility and will not change.

---

## Support

For API questions or issues:

- Email: api-support@example.com
- Documentation: https://docs.example.com/api
- Status: https://status.example.com
