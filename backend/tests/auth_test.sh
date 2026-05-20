#!/usr/bin/env bash
# Simple test script to reproduce login -> refresh -> me flows using curl
# Adjust USER, PASS and URL as needed. Run from a shell on a machine that can
# reach the nginx host (usaz15ls088:8080).

set -euo pipefail
BASE=http://usaz15ls088:8080/api
USER=${1:-admin}
PASS=${2:-password}
JAR=$(mktemp)

echo "Using cookie jar: $JAR"


echo "--- LOGIN ---"
# Capture login response body and status
LOGIN_OUT=$(mktemp)
HTTP_CODE=$(curl -s -w "%{http_code}" -o $LOGIN_OUT -c $JAR -H "Content-Type: application/json" \
  -d "{\"username\": \"$USER\", \"password\": \"$PASS\"}" \
  $BASE/auth/login || true)

echo "login HTTP code: $HTTP_CODE"
cat $LOGIN_OUT

echo "\n--- COOKIES (jar) ---"
cat $JAR || true

echo "\n--- REFRESH ---"
# Capture refresh response (may rotate cookie and return new access token)
REF_OUT=$(mktemp)
REF_CODE=$(curl -s -w "%{http_code}" -o $REF_OUT -b $JAR -c $JAR -X POST $BASE/auth/refresh || true)
echo "refresh HTTP code: $REF_CODE"
cat $REF_OUT

# Extract access token: prefer refresh response, fall back to login response
ACCESS_TOKEN=""
if [ -s "$REF_OUT" ]; then
  ACCESS_TOKEN=$(sed -n 's/.*"accessToken"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$REF_OUT" | head -n1)
fi
if [ -z "$ACCESS_TOKEN" ] && [ -s "$LOGIN_OUT" ]; then
  ACCESS_TOKEN=$(sed -n 's/.*"accessToken"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$LOGIN_OUT" | head -n1)
fi

echo "\n--- CALL /me (with Authorization if token present) ---"
if [ -n "$ACCESS_TOKEN" ]; then
  echo "Using access token: ${ACCESS_TOKEN:0:6}..."
  curl -v -b $JAR -H "Authorization: Bearer $ACCESS_TOKEN" $BASE/auth/me || true
else
  echo "No access token available; calling /me without Authorization header"
  curl -v -b $JAR $BASE/auth/me || true
fi

echo "\n--- FINAL COOKIES (jar) ---"
cat $JAR || true

rm -f $JAR

echo "Done"
