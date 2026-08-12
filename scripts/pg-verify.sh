#!/usr/bin/env bash
set -euo pipefail

# ==========================================================================
# pg-verify.sh — Local PostgreSQL migration smoke test.
#
# Prerequisites on the build machine:
#   - Docker
#   - Java 17+ & Maven (on PATH)
#   - psql (PostgreSQL client, or use docker exec)
#
# Usage:
#   1. Start local PG:   docker compose -f scripts/docker-compose-pg.yml up -d
#   2. Run this script:  bash scripts/pg-verify.sh
#   3. Teardown PG:      docker compose -f scripts/docker-compose-pg.yml down -v
# ==========================================================================

cd "$(dirname "$0")/.."

echo "=== 1. Checking local PostgreSQL is reachable ==="
for i in {1..15}; do
  if docker exec exnr-local-pg pg_isready -U exnr -d exnr >/dev/null 2>&1; then
    echo "  PG ready."
    break
  fi
  echo "  Waiting... ($i/15)"
  sleep 2
done

echo ""
echo "=== 2. Building backend ==="
cd backend
mvn clean package -DskipTests -q
echo "  Build: OK"

echo ""
echo "=== 3. Running Liquibase migrations ==="
mvn spring-boot:run \
  -Dspring-boot.run.arguments="--spring.profiles.active=pg-local" \
  -q \
  > /tmp/exnr-pg-migration.log 2>&1 &
APP_PID=$!
trap "kill $APP_PID 2>/dev/null || true" EXIT

# Wait for startup (look for the "Started" line or similar)
for i in {1..60}; do
  sleep 2
  if grep -qE "Started ExensioreloadApplication|RefDB initialized" /tmp/exnr-pg-migration.log 2>/dev/null; then
    echo "  App started: OK"
    break
  fi
  if ! kill -0 $APP_PID 2>/dev/null; then
    echo "  App process died. Last log lines:"
    tail -40 /tmp/exnr-pg-migration.log
    echo ""
    echo "  ERROR: App failed to start. Check /tmp/exnr-pg-migration.log."
    exit 1
  fi
done

echo ""
echo "=== 4. Verifying tables ==="
PG="docker exec -i exnr-local-pg psql -U exnr -d exnr -t -A"

declare -A expected_tables=(
  [SENDER_STAGE]=1
  [sender_queue]=1
  [sender_queue_wafers]=1
  [load_session]=1
  [load_session_payload]=1
  [users]=1
  [user_roles]=1
  [audit_log]=1
  [password_history]=1
  [user_sessions]=1
  [refresh_tokens]=1
  [staging_session]=1
  [external_environment]=1
  [external_location]=1
  [etl_trigger_idempotency]=1
  [etl_trigger_audit_log]=1
)

missing=0
for table in "${!expected_tables[@]}"; do
  if echo "SELECT 1 FROM pg_tables WHERE tablename='$table'" | $PG | grep -q 1; then
    echo "  [x] $table"
  else
    echo "  [ ] $table -- MISSING"
    missing=1
  fi
done

echo ""
echo "=== 5. Checking Liquibase changelog state ==="
# Verify Liquibase ran to completion by checking the DATABASECHANGELOG table
LOCKED=$(echo "SELECT locked FROM databasechangeloglock" | $PG 2>/dev/null)
if [ "$LOCKED" = "f" ] || [ "$LOCKED" = "false" ]; then
  echo "  Lock table: unlocked (OK)"
else
  echo "  Lock table: LOCKED ($LOCKED) — migrations may still be running"
fi

CHANGE_COUNT=$(echo "SELECT COUNT(*) FROM databasechangelog" | $PG 2>/dev/null)
echo "  Applied changesets: $CHANGE_COUNT"

echo ""
if [ $missing -eq 1 ]; then
  echo "=== RESULT: FAILURE — some tables are missing ==="
  echo "Full migration log: /tmp/exnr-pg-migration.log"
  exit 1
else
  echo "=== RESULT: SUCCESS — all tables present, app started ==="
fi
