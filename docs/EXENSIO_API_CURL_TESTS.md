# Exensio API Manual Testing with cURL

This document provides manual curl tests for key Exensio API endpoints. All endpoints require authentication via Bearer token (from login).

## Prerequisites

```bash
# Set base URL and auth token
export BASE_URL="http://localhost:8080/exensioreload"
export TOKEN="<your-jwt-token-here>"
export SITE="SITE1"  # or your test site
export SENDER_ID="1"  # or your test sender ID

# Environment variables for testing
export DATA_ID="19545843"  # test dataId
export LOT="S7U180015"     # test lot
```

## 1. **Health Check** (No Auth Required)

### 1.1 Test Endpoint
```bash
curl -sS -X GET "${BASE_URL}/api/test/hello" \
  -H "Content-Type: application/json"
```

**Expected Response:**
```json
{
  "message": "Hello from TestController",
  "timestamp": "2026-05-25T10:30:00Z",
  "status": "OK"
}
```

---

## 2. **Stage Records API**

### 2.1 List Stage Records
```bash
curl -sS -X GET "${BASE_URL}/api/stage/records" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" \
  -G \
    --data-urlencode "site=${SITE}" \
    --data-urlencode "page=0" \
    --data-urlencode "size=50" \
    --data-urlencode "sortBy=createdAt" \
    --data-urlencode "sortDir=desc"
```

**Optional Parameters:**
- `senderId=<id>` — filter by sender
- `status=PENDING|ENRICHMENT|SUCCESS|FAILURE|NOT_FOUND` — filter by status
- `q=<query>` — text search
- `requestId=<id>` — filter by request ID
- `page=<n>` — pagination (default: 0)
- `size=<n>` — page size (default: 50, max: 500)

**Expected Response:**
```json
{
  "items": [
    {
      "id": "12345",
      "dataId": "19545843",
      "lot": "S7U180015",
      "status": "ENRICHMENT",
      "sender": {"id": 1, "name": "DataPort"},
      "site": "SITE1",
      "createdAt": "2026-05-25T10:00:00Z",
      "enrichmentStartedAt": "2026-05-25T10:01:00Z"
    }
  ],
  "total": 250,
  "page": 0,
  "size": 50
}
```

### 2.2 Get Stage Stats
```bash
curl -sS -X GET "${BASE_URL}/api/stage/stats" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json"
```

**Expected Response:**
```json
{
  "total": 1000,
  "PENDING": 100,
  "ENRICHMENT": 250,
  "SUCCESS": 500,
  "FAILURE": 100,
  "NOT_FOUND": 50
}
```

### 2.3 Export Stage Records (CSV)
```bash
curl -sS -X GET "${BASE_URL}/api/stage/records/export" \
  -H "Authorization: Bearer ${TOKEN}" \
  --data-urlencode "site=${SITE}" \
  -G \
    --data-urlencode "senderId=${SENDER_ID}" \
    --data-urlencode "useGzip=true" \
  -o "stage-records-export.csv.gz"
```

---

## 3. **Sessions API**

### 3.1 Create Session
```bash
curl -sS -X POST "${BASE_URL}/api/stage/sessions" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{
    "site": "'${SITE}'",
    "senderId": '${SENDER_ID}',
    "senderName": "DataPort",
    "environment": "PROD"
  }'
```

**Expected Response:**
```json
{
  "sessionId": "sess_abc123xyz",
  "site": "SITE1",
  "senderId": 1,
  "createdAt": "2026-05-25T10:30:00Z",
  "status": "ACTIVE"
}
```

### 3.2 List Sessions
```bash
curl -sS -X GET "${BASE_URL}/api/stage/sessions" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" \
  -G \
    --data-urlencode "page=0" \
    --data-urlencode "size=20" \
    --data-urlencode "site=${SITE}"
```

**Optional Parameters:**
- `q=<query>` — text search
- `senderId=<id>` — filter by sender
- `username=<user>` — filter by user (admin only)
- `sessionId=<id>` — filter by session ID
- `status=ACTIVE|COMPLETED|CANCELLED` — filter by status

### 3.3 Get Session Details
```bash
curl -sS -X GET "${BASE_URL}/api/stage/sessions/{sessionId}" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json"
```

### 3.4 Get Session Files
```bash
curl -sS -X GET "${BASE_URL}/api/stage/sessions/{sessionId}/files" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json"
```

### 3.5 Get Session Analytics
```bash
curl -sS -X GET "${BASE_URL}/api/stage/sessions/{sessionId}/analytics" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json"
```

**Expected Response:**
```json
{
  "sessionId": "sess_abc123xyz",
  "totalFiles": 1500,
  "successCount": 1200,
  "failureCount": 100,
  "notFoundCount": 200,
  "enrichmentInProgressCount": 0,
  "successRate": 80.0,
  "averageEnrichmentTimeMs": 2500
}
```

### 3.6 Refresh Session
```bash
curl -sS -X POST "${BASE_URL}/api/stage/sessions/{sessionId}/refresh" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json"
```

### 3.7 Cancel Session
```bash
curl -sS -X POST "${BASE_URL}/api/stage/sessions/{sessionId}/cancel" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json"
```

---

## 4. **Monitor API (Server-Sent Events)**

### 4.1 Monitor Stage Records (SSE Stream)
```bash
curl -sS -N -X GET "${BASE_URL}/api/stage/monitor" \
  -H "Authorization: Bearer ${TOKEN}" \
  --data-urlencode "requestId=req_12345"
```

**Expected Stream Output (line-by-line):**
```
data: {"recordId":"123","status":"ENRICHMENT","progress":"25%"}
data: {"recordId":"123","status":"SUCCESS","outputPath":"...","target":"PRODUCTION"}
...
```

### 4.2 Monitor Session (SSE Stream)
```bash
curl -sS -N -X GET "${BASE_URL}/api/stage/sessions/{sessionId}/monitor" \
  -H "Authorization: Bearer ${TOKEN}"
```

---

## 5. **Authentication API**

### 5.1 User Registration
```bash
curl -sS -X POST "${BASE_URL}/api/auth/register" \
  -H "Content-Type: application/json" \
  -d '{
    "username": "testuser",
    "email": "test@example.com",
    "password": "SecurePassword123!",
    "firstName": "Test",
    "lastName": "User"
  }'
```

### 5.2 User Login (if available)
```bash
# Note: Exact endpoint depends on your auth implementation
# This is a common pattern for Spring Security + JWT
curl -sS -X POST "${BASE_URL}/api/auth/login" \
  -H "Content-Type: application/json" \
  -d '{
    "username": "testuser",
    "password": "SecurePassword123!"
  }'
```

---

## 6. **Admin API**

### 6.1 List All Users (Admin Only)
```bash
curl -sS -X GET "${BASE_URL}/api/admin/users" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" \
  -G \
    --data-urlencode "page=0" \
    --data-urlencode "size=50"
```

---

## 7. **Coverage API**

### 7.1 Get Record Coverage Stats
```bash
curl -sS -X GET "${BASE_URL}/api/stage/records/coverage" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json"
```

**Expected Response:**
```json
{
  "totalRecords": 5000,
  "enrichedRecords": 3500,
  "coveragePercentage": 70.0,
  "successRate": 85.7,
  "failureCount": 500,
  "notFoundCount": 1000
}
```

---

## 8. **Common Testing Scenarios**

### 8.1 Test Complete Flow: Create Session → Monitor → Export

```bash
#!/bin/bash

# Step 1: Create session
SESSION_RESPONSE=$(curl -sS -X POST "${BASE_URL}/api/stage/sessions" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{
    "site": "'${SITE}'",
    "senderId": '${SENDER_ID}',
    "senderName": "DataPort",
    "environment": "PROD"
  }')

SESSION_ID=$(echo "$SESSION_RESPONSE" | jq -r '.sessionId')
echo "Created session: $SESSION_ID"

# Step 2: Wait a bit for processing
sleep 2

# Step 3: Get session analytics
curl -sS -X GET "${BASE_URL}/api/stage/sessions/${SESSION_ID}/analytics" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" | jq '.'

# Step 4: Export results
curl -sS -X GET "${BASE_URL}/api/stage/records/export" \
  -H "Authorization: Bearer ${TOKEN}" \
  -G \
    --data-urlencode "site=${SITE}" \
  -o "results-${SESSION_ID}.csv"

echo "Exported to results-${SESSION_ID}.csv"
```

### 8.2 Test Error Handling

```bash
# Missing required parameters
curl -sS -X GET "${BASE_URL}/api/stage/records" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json"
# Expected: 400 Bad Request - "site is required"

# Invalid token
curl -sS -X GET "${BASE_URL}/api/stage/records" \
  -H "Authorization: Bearer invalid_token_here" \
  -H "Content-Type: application/json" \
  -G --data-urlencode "site=${SITE}"
# Expected: 401 Unauthorized

# Non-existent session
curl -sS -X GET "${BASE_URL}/api/stage/sessions/sess_nonexistent" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json"
# Expected: 404 Not Found
```

---

## 9. **Debugging Tips**

### 9.1 Pretty-print JSON responses
```bash
curl -sS ... | jq '.'
```

### 9.2 Show response headers
```bash
curl -i -sS ...
```

### 9.3 Show request/response details
```bash
curl -v -sS ...
```

### 9.4 Save full response to file
```bash
curl -sS ... > response.json && cat response.json | jq '.'
```

### 9.5 Test with custom headers
```bash
curl -sS -X GET "${BASE_URL}/api/stage/records" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" \
  -H "X-Custom-Header: value" \
  -G --data-urlencode "site=${SITE}"
```

---

## 10. **Performance Testing**

### 10.1 Bulk record request
```bash
# Request max allowed page size (500)
curl -sS -X GET "${BASE_URL}/api/stage/records" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" \
  -G \
    --data-urlencode "site=${SITE}" \
    --data-urlencode "page=0" \
    --data-urlencode "size=500" \
  | jq '.total'
```

### 10.2 Measure response time
```bash
time curl -sS -X GET "${BASE_URL}/api/stage/records" \
  -H "Authorization: Bearer ${TOKEN}" \
  -G --data-urlencode "site=${SITE}" \
  > /dev/null
```

---

## Notes

- All endpoints require authentication via Bearer token except `/api/test/hello`
- Pagination defaults: `page=0`, `size=50` (max 500)
- Sorting: `sortBy` and `sortDir` optional; defaults to `createdAt desc`
- Timestamps are in ISO 8601 format (UTC)
- CSV export supports gzip compression via `useGzip=true`
- Session monitoring uses Server-Sent Events (SSE); keep connection open to receive updates
