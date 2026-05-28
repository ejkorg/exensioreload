# Quick Exensio Production API Test

## PowerShell Test Script

Paste this into PowerShell on your server:

```powershell
# Set credentials
$EXENSIO_PROD_URL = "https://api-prod.canyon.aws.pdf.com/api/v1/"
$EXENSIO_USERNAME = "YQS_API_USER"
$EXENSIO_PASSWORD = "xNsqy667p"
$LOT_ID = "S7U180015"  # Change this to your test lot
$WAFER_ID = "W001"      # Change this to your test wafer

# Step 1: Get authentication token
Write-Host "1. Logging in to Exensio..." -ForegroundColor Green
$loginBody = @{
    username = $EXENSIO_USERNAME
    password = $EXENSIO_PASSWORD
} | ConvertTo-Json

$loginResponse = curl -sS -X POST "${EXENSIO_PROD_URL}auth/login" `
  -H "Content-Type: application/json" `
  -d $loginBody | ConvertFrom-Json

$TOKEN = $loginResponse.token
if ($null -eq $TOKEN) {
    Write-Host "✗ Login failed!" -ForegroundColor Red
    Write-Host $loginResponse
    exit
}
Write-Host "✓ Token obtained: $($TOKEN.Substring(0,50))..." -ForegroundColor Green

# Step 2: Test lot-wafer lookup
Write-Host "`n2. Looking up lot/wafer in Exensio..." -ForegroundColor Green
$lookupBody = @{
    pgc_key = 1
    lot_ids = @($LOT_ID)
    wafer_ids = @($WAFER_ID)
} | ConvertTo-Json

$lookupResponse = curl -sS -X POST "${EXENSIO_PROD_URL}key/lot-wafer-lookup" `
  -H "Authorization: Bearer $TOKEN" `
  -H "Content-Type: application/json" `
  -d $lookupBody | ConvertFrom-Json

$status = $lookupResponse.status
Write-Host "✓ Response status: $status" -ForegroundColor Green

if ($status -eq "SUCCESS") {
    Write-Host "✓ Lot found in Exensio!" -ForegroundColor Green
    $lots = $lookupResponse.lots
    if ($lots -and $lots.Count -gt 0) {
        $lot = $lots[0]
        Write-Host "  Lot ID: $($lot.lot_id)"
        if ($lot.wafers -and $lot.wafers.Count -gt 0) {
            $wafer = $lot.wafers[0]
            Write-Host "  Wafer ID: $($wafer.wafer_id)"
            Write-Host "  State: $($wafer.state)"
            Write-Host "  Location: $($wafer.location)"
            Write-Host "  Loaded At: $($wafer.loaded_at)"
        }
    }
} else {
    Write-Host "✗ Lot not found in Exensio" -ForegroundColor Red
}

# Step 3: Get lot details
Write-Host "`n3. Fetching lot details..." -ForegroundColor Green
$detailsResponse = curl -sS -X GET "${EXENSIO_PROD_URL}lots/$LOT_ID" `
  -H "Authorization: Bearer $TOKEN" `
  -H "Content-Type: application/json" | ConvertFrom-Json

if ($detailsResponse.lot_id) {
    Write-Host "✓ Lot details:" -ForegroundColor Green
    Write-Host "  Lot ID: $($detailsResponse.lot_id)"
    Write-Host "  Status: $($detailsResponse.status)"
    Write-Host "  Load Date: $($detailsResponse.load_date)"
    Write-Host "  Wafer Count: $($detailsResponse.wafer_count)"
} else {
    Write-Host "✗ Could not fetch lot details" -ForegroundColor Red
}

Write-Host "`n✓ All tests completed" -ForegroundColor Green
```

---

## Bash Test Script (for Linux/Mac)

Or if you're on Linux/Mac, use this bash version:

```bash
#!/bin/bash

# Set credentials
EXENSIO_PROD_URL="https://api-prod.canyon.aws.pdf.com/api/v1/"
EXENSIO_USERNAME="YQS_API_USER"
EXENSIO_PASSWORD="xNsqy667p"
LOT_ID="S7U180015"  # Change this to your test lot
WAFER_ID="W001"      # Change this to your test wafer

echo "1. Logging in to Exensio..."
LOGIN_RESPONSE=$(curl -sS -X POST "${EXENSIO_PROD_URL}auth/login" \
  -H "Content-Type: application/json" \
  -d '{
    "username": "'${EXENSIO_USERNAME}'",
    "password": "'${EXENSIO_PASSWORD}'"
  }')

TOKEN=$(echo "$LOGIN_RESPONSE" | jq -r '.token // empty')
if [ -z "$TOKEN" ]; then
    echo "✗ Login failed!"
    echo "$LOGIN_RESPONSE" | jq '.'
    exit 1
fi
echo "✓ Token obtained: ${TOKEN:0:50}..."

echo ""
echo "2. Looking up lot/wafer in Exensio..."
LOOKUP_RESPONSE=$(curl -sS -X POST "${EXENSIO_PROD_URL}key/lot-wafer-lookup" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{
    "pgc_key": 1,
    "lot_ids": ["'${LOT_ID}'"],
    "wafer_ids": ["'${WAFER_ID}'"]
  }')

STATUS=$(echo "$LOOKUP_RESPONSE" | jq -r '.status')
echo "✓ Response status: $STATUS"

if [ "$STATUS" = "SUCCESS" ]; then
    echo "✓ Lot found in Exensio!"
    echo "$LOOKUP_RESPONSE" | jq '.lots[0]'
else
    echo "✗ Lot not found in Exensio"
    echo "$LOOKUP_RESPONSE" | jq '.'
fi

echo ""
echo "3. Fetching lot details..."
DETAILS_RESPONSE=$(curl -sS -X GET "${EXENSIO_PROD_URL}lots/${LOT_ID}" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json")

LOT_INFO=$(echo "$DETAILS_RESPONSE" | jq -r '.lot_id // empty')
if [ -n "$LOT_INFO" ]; then
    echo "✓ Lot details:"
    echo "$DETAILS_RESPONSE" | jq '.lot_id, .status, .load_date, .wafer_count'
else
    echo "✗ Could not fetch lot details"
fi

echo ""
echo "✓ All tests completed"
```

---

## Simple One-Liner Tests

### Test 1: Get Authentication Token
```bash
curl -sS -X POST "https://api-prod.canyon.aws.pdf.com/api/v1/auth/login" -H "Content-Type: application/json" -d '{"username":"YQS_API_USER","password":"xNsqy667p"}' | jq '.token'
```

### Test 2: Test Lot/Wafer Lookup (requires token from Test 1)
```bash
TOKEN="<paste-token-here>"
curl -sS -X POST "https://api-prod.canyon.aws.pdf.com/api/v1/key/lot-wafer-lookup" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{"pgc_key":1,"lot_ids":["S7U180015"],"wafer_ids":["W001"]}' | jq '.'
```

### Test 3: Get Lot Details (requires token)
```bash
TOKEN="<paste-token-here>"
curl -sS -X GET "https://api-prod.canyon.aws.pdf.com/api/v1/lots/S7U180015" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" | jq '.'
```

---

## Full Interactive Test (Copy & Paste Everything)

**For PowerShell:**
```powershell
$url="https://api-prod.canyon.aws.pdf.com/api/v1/"; $u="YQS_API_USER"; $p="xNsqy667p"; $l="S7U180015"; $w="W001"; Write-Host "Logging in..." ; $token=(curl -sS -X POST "${url}auth/login" -H "Content-Type: application/json" -d (@{username=$u;password=$p}|ConvertTo-Json) | ConvertFrom-Json).token; if($token){Write-Host "✓ Got token"; Write-Host "Looking up..." ; $resp=curl -sS -X POST "${url}key/lot-wafer-lookup" -H "Authorization: Bearer $token" -H "Content-Type: application/json" -d (@{pgc_key=1;lot_ids=@($l);wafer_ids=@($w)}|ConvertTo-Json) | ConvertFrom-Json; Write-Host $resp.status ; $resp.lots[0] | ConvertTo-Json} else {Write-Host "✗ Login failed"}
```

**For Bash:**
```bash
url="https://api-prod.canyon.aws.pdf.com/api/v1/"; u="YQS_API_USER"; p="xNsqy667p"; l="S7U180015"; w="W001"; echo "Logging in..."; token=$(curl -sS -X POST "${url}auth/login" -H "Content-Type: application/json" -d '{"username":"'$u'","password":"'$p'"}' | jq -r .token); if [ ! -z "$token" ]; then echo "✓ Got token"; echo "Looking up..."; curl -sS -X POST "${url}key/lot-wafer-lookup" -H "Authorization: Bearer $token" -H "Content-Type: application/json" -d '{"pgc_key":1,"lot_ids":["'$l'"],"wafer_ids":["'$w'"]}' | jq '.'; else echo "✗ Login failed"; fi
```

---

## Expected Output

**Success Response:**
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

**Not Found Response:**
```json
{
  "status": "NOT_FOUND",
  "pgc_key": 1,
  "lots": []
}
```

---

## Notes

- Replace `S7U180015` and `W001` with your actual test lot/wafer IDs
- The token typically expires in 1 hour; get a fresh one if needed
- Keep credentials secure; don't commit these values to version control
