#!/bin/bash

# Exensio Production API Test Script
# Usage: bash exensio_test.sh [lot_id] [wafer_id]

set -e

# Configuration
EXENSIO_PROD_URL="https://api-prod.canyon.aws.pdf.com/api/v1/"
EXENSIO_USERNAME="YQS_API_USER"
EXENSIO_PASSWORD="xNsqy667p"
LOT_ID="${1:-S7U180015}"
WAFER_ID="${2:-W001}"

# Detect environment and set Exensio schema accordingly
ENVIRONMENT="${ENVIRONMENT:-${ENV:-PRD}}"
case "${ENVIRONMENT}" in
  PRD|PROD|PRODUCTION)
    EXENSIO_DBNAME="PROD"
    EXENSIO_DBSCHEMA="PRODUCTION"
    ENV_LABEL="Production"
    ;;
  SBX|SANDBOX)
    EXENSIO_DBNAME="SBX"
    EXENSIO_DBSCHEMA="SANDBOX"
    ENV_LABEL="Sandbox"
    ;;
  *)
    echo "⚠️  Unknown environment: $ENVIRONMENT (defaulting to PRD)"
    EXENSIO_DBNAME="PROD"
    EXENSIO_DBSCHEMA="PRODUCTION"
    ENV_LABEL="Production (default)"
    ;;
esac

echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "Exensio Production API Test"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""
echo "Environment: $ENV_LABEL ($ENVIRONMENT)"
echo "URL: $EXENSIO_PROD_URL"
echo "Schema: $EXENSIO_DBSCHEMA"
echo "Lot: $LOT_ID"
echo "Wafer: $WAFER_ID"
echo ""

# Step 1: Login
echo "1️⃣  Logging in to Exensio..."
LOGIN_RESPONSE=$(curl -sS -X POST "${EXENSIO_PROD_URL}session/login" \
  -H "Content-Type: application/json" \
  -d '{
    "username": "'${EXENSIO_USERNAME}'",
    "password": "'${EXENSIO_PASSWORD}'",
    "dbname": "'${EXENSIO_DBNAME}'",
    "dbschema": "'${EXENSIO_DBSCHEMA}'"
  }')

TOKEN=$(echo "$LOGIN_RESPONSE" | jq -r '.token // .access_token // empty' 2>/dev/null)
if [ -z "$TOKEN" ]; then
    echo "❌ Login failed!"
    echo "Response:"
    echo "$LOGIN_RESPONSE" | jq '.' 2>/dev/null || echo "$LOGIN_RESPONSE"
    exit 1
fi
echo "✅ Token obtained: ${TOKEN:0:50}..."
echo ""

# Step 2: Test lot-wafer lookup
echo "2️⃣  Looking up lot/wafer in Exensio..."
LOOKUP_RESPONSE=$(curl -sS -X POST "${EXENSIO_PROD_URL}key/lot-wafer-lookup" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{
    "pgc_key": 1,
    "lot_ids": ["'${LOT_ID}'"],
    "wafer_ids": ["'${WAFER_ID}'"]
  }')

STATUS=$(echo "$LOOKUP_RESPONSE" | jq -r '.status // empty' 2>/dev/null)
echo "Response status: $STATUS"

if [ "$STATUS" = "SUCCESS" ]; then
    echo "✅ Lot found in Exensio!"
    echo ""
    echo "Lot Details:"
    echo "$LOOKUP_RESPONSE" | jq '.lots[0]' 2>/dev/null || echo "$LOOKUP_RESPONSE"
elif [ "$STATUS" = "NOT_FOUND" ]; then
    echo "❌ Lot NOT found in Exensio"
    echo "Response:"
    echo "$LOOKUP_RESPONSE" | jq '.' 2>/dev/null || echo "$LOOKUP_RESPONSE"
else
    echo "⚠️  Unexpected response:"
    echo "$LOOKUP_RESPONSE" | jq '.' 2>/dev/null || echo "$LOOKUP_RESPONSE"
fi
echo ""

# Step 3: Get lot details (if lookup succeeded)
if [ "$STATUS" = "SUCCESS" ]; then
    echo "3️⃣  Fetching detailed lot information..."
    DETAILS_RESPONSE=$(curl -sS -X GET "${EXENSIO_PROD_URL}lots/${LOT_ID}" \
      -H "Authorization: Bearer ${TOKEN}" \
      -H "Content-Type: application/json")

    LOT_ID_VERIFY=$(echo "$DETAILS_RESPONSE" | jq -r '.lot_id // empty' 2>/dev/null)
    if [ -n "$LOT_ID_VERIFY" ]; then
        echo "✅ Lot details retrieved:"
        echo "$DETAILS_RESPONSE" | jq '{lot_id, status, load_date, wafer_count, part_number: .part_number}' 2>/dev/null || echo "$DETAILS_RESPONSE"
    else
        echo "⚠️  Could not fetch detailed lot information"
    fi
    echo ""
fi

echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "✅ Test completed"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
