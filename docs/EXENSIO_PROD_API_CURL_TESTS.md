# Exensio Production API Manual Testing with cURL

This document provides manual curl tests for the Exensio Production API at `https://api-prod.canyon.aws.pdf.com/api/v1/`

## Prerequisites

```bash
# Set base URL and credentials
export EXENSIO_PROD_URL="https://api-prod.canyon.aws.pdf.com/api/v1"
export EXENSIO_USERNAME="<your-exensio-username>"
export EXENSIO_PASSWORD="<your-exensio-password>"
export EXENSIO_DBNAME="<exensio-database-name>"  # typically the company/site name
export EXENSIO_DBSCHEMA="PRODUCTION"  # or QA/STAGING

# Test data
export LOT_ID="S7U180015"  # example lot
export WAFER_ID="W001"      # example wafer
```

---

## 1. **Authentication**

### 1.1 Login / Get Bearer Token

First, you need to obtain an authentication token to use other endpoints.

```bash
# Method 1: Using environment variables
TOKEN=$(curl -sS -X POST "${EXENSIO_PROD_URL}/auth/login" \
  -H "Content-Type: application/json" \
  -d '{
    "username": "'${EXENSIO_USERNAME}'",
    "password": "'${EXENSIO_PASSWORD}'"
  }' | jq -r '.token // .access_token // .data.token')

echo "Token: $TOKEN"
```

**Expected Response:**
```json
{
  "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "expires_in": 3600,
  "user": {
    "id": "user123",
    "username": "your-username"
  }
}
```

### 1.2 Verify Token
```bash
curl -sS -X GET "${EXENSIO_PROD_URL}/auth/verify" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json"
```

---

## 2. **Lot/Wafer Lookup** (Core Endpoint)

### 2.1 Single Record Lookup

This is the main endpoint used by ExensioLoadMonitor to verify if a lot/wafer has been loaded.

```bash
curl -sS -X POST "${EXENSIO_PROD_URL}/key/lot-wafer-lookup" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{
    "pgc_key": 1,
    "lot_ids": ["'${LOT_ID}'"],
    "wafer_ids": ["'${WAFER_ID}'"]
  }' | jq '.'
```

**Request Body Structure:**
```json
{
  "pgc_key": 1,              // PGC key (typically 1)
  "lot_ids": ["S7U180015"],  // Array of lot IDs to search
  "wafer_ids": ["W001"]      // Array of wafer IDs to search
}
```

**Expected Success Response:**
```json
{
  "status": "SUCCESS",
  "pgc_key": 1,
  "lots": [
    {
      "lot_id": "S7U180015",
      "wafers": [
        {
          "wafer_id": "W001",
          "loaded_at": "2026-05-25T10:30:00Z",
          "location": "PRODUCTION",
          "state": "LOADED"
        }
      ]
    }
  ]
}
```

**Expected Not-Found Response:**
```json
{
  "status": "NOT_FOUND",
  "pgc_key": 1,
  "lots": []
}
```

### 2.2 Batch Lot/Wafer Lookup

Lookup multiple lots/wafers in a single request for better performance.

```bash
curl -sS -X POST "${EXENSIO_PROD_URL}/key/lot-wafer-lookup-batch" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{
    "pgc_key": 1,
    "lot_ids": ["S7U180015", "S7U180016", "S7U180017"],
    "wafer_ids": ["W001", "W002", "W003"]
  }' | jq '.'
```

**Expected Response (Multiple Lots):**
```json
{
  "status": "SUCCESS",
  "pgc_key": 1,
  "lots": [
    {
      "lot_id": "S7U180015",
      "wafers": [
        {
          "wafer_id": "W001",
          "loaded_at": "2026-05-25T10:30:00Z",
          "location": "PRODUCTION",
          "state": "LOADED"
        }
      ]
    },
    {
      "lot_id": "S7U180016",
      "wafers": [
        {
          "wafer_id": "W002",
          "loaded_at": "2026-05-25T10:35:00Z",
          "location": "PRODUCTION",
          "state": "LOADED"
        }
      ]
    },
    {
      "lot_id": "S7U180017",
      "wafers": []  # Not found
    }
  ]
}
```

---

## 3. **Lot Information**

### 3.1 Get Lot Details
```bash
curl -sS -X GET "${EXENSIO_PROD_URL}/lots/${LOT_ID}" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" | jq '.'
```

**Expected Response:**
```json
{
  "lot_id": "S7U180015",
  "part_number": "PN12345",
  "tester": "PROBE1",
  "load_date": "2026-05-25T10:30:00Z",
  "status": "LOADED",
  "wafer_count": 10,
  "wafers": [
    {
      "wafer_id": "W001",
      "state": "LOADED",
      "location": "PRODUCTION"
    }
  ]
}
```

### 3.2 Search Lots
```bash
curl -sS -X GET "${EXENSIO_PROD_URL}/lots" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" \
  -G \
    --data-urlencode "part_number=PN12345" \
    --data-urlencode "page=0" \
    --data-urlencode "size=50"
```

---

## 4. **Wafer Information**

### 4.1 Get Wafer Details
```bash
curl -sS -X GET "${EXENSIO_PROD_URL}/wafers/${WAFER_ID}" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" | jq '.'
```

**Expected Response:**
```json
{
  "wafer_id": "W001",
  "lot_id": "S7U180015",
  "state": "LOADED",
  "location": "PRODUCTION",
  "loaded_at": "2026-05-25T10:30:00Z",
  "last_updated": "2026-05-25T10:30:00Z"
}
```

### 4.2 Search Wafers by Lot
```bash
curl -sS -X GET "${EXENSIO_PROD_URL}/lots/${LOT_ID}/wafers" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" | jq '.'
```

---

## 5. **Queue/State Information**

### 5.1 Get Queue Status
```bash
curl -sS -X GET "${EXENSIO_PROD_URL}/queues" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" | jq '.'
```

**Expected Response:**
```json
{
  "queues": [
    {
      "queue_id": "Q1",
      "name": "PRODUCTION",
      "lot_count": 1500,
      "wafer_count": 15000,
      "state": "RUNNING"
    }
  ]
}
```

### 5.2 Get Lot Queue Position
```bash
curl -sS -X GET "${EXENSIO_PROD_URL}/lots/${LOT_ID}/queue-info" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" | jq '.'
```

---

## 6. **Advanced Queries**

### 6.1 Get Statistics
```bash
curl -sS -X GET "${EXENSIO_PROD_URL}/stats" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" | jq '.'
```

**Expected Response:**
```json
{
  "total_lots_loaded": 5000,
  "total_wafers_loaded": 50000,
  "average_load_time_seconds": 300,
  "queues": {
    "PRODUCTION": 1500,
    "QA": 200,
    "SANDBOX": 50
  }
}
```

### 6.2 Get Health Status
```bash
curl -sS -X GET "${EXENSIO_PROD_URL}/health" \
  -H "Content-Type: application/json"
```

**Expected Response:**
```json
{
  "status": "UP",
  "version": "2.1.0",
  "database": "CONNECTED",
  "queue_service": "UP"
}
```

---

## 7. **Error Handling & Debugging**

### 7.1 Test Authentication Errors

```bash
# Invalid credentials
curl -sS -X POST "${EXENSIO_PROD_URL}/auth/login" \
  -H "Content-Type: application/json" \
  -d '{
    "username": "invalid_user",
    "password": "invalid_pass"
  }' | jq '.'
# Expected: 401 Unauthorized

# Expired token
curl -sS -X GET "${EXENSIO_PROD_URL}/lots" \
  -H "Authorization: Bearer expired_token_here" \
  -H "Content-Type: application/json"
# Expected: 401 Unauthorized or 403 Forbidden

# Missing token
curl -sS -X GET "${EXENSIO_PROD_URL}/lots" \
  -H "Content-Type: application/json"
# Expected: 401 Unauthorized
```

### 7.2 Test Not-Found Cases

```bash
# Non-existent lot
curl -sS -X POST "${EXENSIO_PROD_URL}/key/lot-wafer-lookup" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{
    "pgc_key": 1,
    "lot_ids": ["NONEXISTENT999"],
    "wafer_ids": ["W999"]
  }' | jq '.'
# Expected: 200 with empty lots array or 404 depending on API version
```

### 7.3 Test Validation Errors

```bash
# Missing required fields
curl -sS -X POST "${EXENSIO_PROD_URL}/key/lot-wafer-lookup" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{
    "pgc_key": 1
  }' | jq '.'
# Expected: 400 Bad Request - "lot_ids and wafer_ids are required"

# Invalid pgc_key
curl -sS -X POST "${EXENSIO_PROD_URL}/key/lot-wafer-lookup" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{
    "pgc_key": 999,
    "lot_ids": ["S7U180015"],
    "wafer_ids": ["W001"]
  }' | jq '.'
# Expected: 400 Bad Request or 404 - invalid PGC key
```

---

## 8. **Complete End-to-End Testing Flow**

```bash
#!/bin/bash

set -e

echo "1. Obtaining authentication token..."
LOGIN_RESPONSE=$(curl -sS -X POST "${EXENSIO_PROD_URL}/auth/login" \
  -H "Content-Type: application/json" \
  -d '{
    "username": "'${EXENSIO_USERNAME}'",
    "password": "'${EXENSIO_PASSWORD}'"
  }')

TOKEN=$(echo "$LOGIN_RESPONSE" | jq -r '.token // .access_token')
echo "✓ Token obtained: ${TOKEN:0:50}..."

echo ""
echo "2. Testing lot-wafer lookup..."
LOOKUP_RESPONSE=$(curl -sS -X POST "${EXENSIO_PROD_URL}/key/lot-wafer-lookup" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{
    "pgc_key": 1,
    "lot_ids": ["'${LOT_ID}'"],
    "wafer_ids": ["'${WAFER_ID}'"]
  }')

STATUS=$(echo "$LOOKUP_RESPONSE" | jq -r '.status')
echo "✓ Lookup status: $STATUS"

if [ "$STATUS" = "SUCCESS" ]; then
  echo "✓ Lot found in Exensio"
  echo "$LOOKUP_RESPONSE" | jq '.lots[0].wafers[0]'
else
  echo "✗ Lot not found in Exensio"
fi

echo ""
echo "3. Testing lot details..."
curl -sS -X GET "${EXENSIO_PROD_URL}/lots/${LOT_ID}" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" | jq '.lot_id, .status, .load_date'

echo ""
echo "4. Testing statistics..."
curl -sS -X GET "${EXENSIO_PROD_URL}/stats" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" | jq '.total_lots_loaded, .total_wafers_loaded'

echo ""
echo "✓ All tests completed"
```

Run the script:
```bash
chmod +x test_exensio.sh
./test_exensio.sh
```

---

## 9. **Performance Testing**

### 9.1 Measure Lot Lookup Response Time
```bash
time curl -sS -X POST "${EXENSIO_PROD_URL}/key/lot-wafer-lookup" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{
    "pgc_key": 1,
    "lot_ids": ["'${LOT_ID}'"],
    "wafer_ids": ["'${WAFER_ID}'"]
  }' > /dev/null
```

### 9.2 Batch Lookup Performance
```bash
# Create a file with multiple lots (10 lots)
LOTS='["L1","L2","L3","L4","L5","L6","L7","L8","L9","L10"]'
WAFERS='["W1","W2","W3","W4","W5","W6","W7","W8","W9","W10"]'

time curl -sS -X POST "${EXENSIO_PROD_URL}/key/lot-wafer-lookup-batch" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{
    "pgc_key": 1,
    "lot_ids": '${LOTS}',
    "wafer_ids": '${WAFERS}'
  }' > /dev/null
```

---

## 10. **Troubleshooting**

### Common Issues

| Error | Cause | Solution |
|-------|-------|----------|
| `401 Unauthorized` | Invalid/expired token | Get new token via login endpoint |
| `403 Forbidden` | User lacks permissions | Check user role/permissions in Exensio |
| `400 Bad Request` | Invalid request body | Check JSON syntax and required fields |
| `404 Not Found` | Endpoint doesn't exist | Verify API version (v1?) and endpoint path |
| `500 Internal Server Error` | Server error | Check Exensio logs and retry |
| `Connection timeout` | Network/firewall issue | Verify URL is accessible: `curl -I ${EXENSIO_PROD_URL}/health` |

### Network Connectivity Check
```bash
# Verify API is accessible
curl -I -sS "${EXENSIO_PROD_URL}/health"

# Measure latency
curl -w "Time: %{time_total}s\n" -o /dev/null -sS "${EXENSIO_PROD_URL}/health"
```

---

## Notes

- **Base URL:** `https://api-prod.canyon.aws.pdf.com/api/v1`
- **Authentication:** Bearer token (obtain via `/auth/login`)
- **Token Expiry:** Typically 1 hour; refresh as needed
- **Rate Limiting:** Check response headers for rate limit info
- **Request Timeout:** Set to 15 seconds in ExensioClient
- **Batch Lookup:** Recommended for ≥3 lots to reduce latency
- **pgc_key:** Usually `1` for standard operations
