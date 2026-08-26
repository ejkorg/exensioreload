#!/bin/bash
# Timezone Configuration Verification Script
# Run this after deploying the timezone fix to verify correct configuration

set -e

echo "=========================================="
echo "Exensio Reload Timezone Verification"
echo "=========================================="
echo ""

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

SUCCESS=0
WARNINGS=0
FAILURES=0

check_pass() {
    echo -e "${GREEN}✓${NC} $1"
    ((SUCCESS++))
}

check_warn() {
    echo -e "${YELLOW}⚠${NC} $1"
    ((WARNINGS++))
}

check_fail() {
    echo -e "${RED}✗${NC} $1"
    ((FAILURES++))
}

echo "1. Checking System Timezone..."
SYSTEM_TZ=$(timedatectl | grep "Time zone" | awk '{print $3}' 2>/dev/null || echo "unknown")
if [ "$SYSTEM_TZ" == "UTC" ]; then
    check_pass "System timezone is UTC"
else
    check_warn "System timezone is $SYSTEM_TZ (not UTC, but JVM can override)"
fi
echo ""

echo "2. Checking Java Process Timezone..."
JAVA_PID=$(pgrep -f "exensioreload.jar" 2>/dev/null || echo "")
if [ -z "$JAVA_PID" ]; then
    check_fail "Exensioreload process not found. Is it running?"
else
    check_pass "Found Java process: PID $JAVA_PID"
    
    # Check environment variables
    JAVA_OPTS=$(cat /proc/$JAVA_PID/environ 2>/dev/null | tr '\0' '\n' | grep "JAVA_OPTS" || echo "")
    if echo "$JAVA_OPTS" | grep -q "user.timezone=UTC"; then
        check_pass "JVM timezone set to UTC in JAVA_OPTS"
    else
        check_warn "Could not confirm -Duser.timezone=UTC in process environment"
        echo "   Run: cat /proc/$JAVA_PID/cmdline | tr '\0' ' ' | grep -o 'user.timezone=[^ ]*'"
    fi
fi
echo ""

echo "3. Checking Application Logs for Timezone Evidence..."
LOG_FILE="/var/log/exensioreload/exensioreload.log"
if [ ! -f "$LOG_FILE" ]; then
    LOG_FILE="./logs/exensioreload.log"
fi

if [ -f "$LOG_FILE" ]; then
    RECENT_TZ=$(tail -n 1000 "$LOG_FILE" | grep "systemTZ=" | tail -1 2>/dev/null || echo "")
    if echo "$RECENT_TZ" | grep -q "systemTZ=UTC"; then
        check_pass "Recent log shows systemTZ=UTC"
        echo "   $(echo "$RECENT_TZ" | grep -o 'systemTZ=[^ ,]*')"
    else
        check_fail "No recent log entry with systemTZ=UTC found"
        echo "   This indicates JVM timezone is NOT set to UTC"
        echo "   Search logs manually: grep 'systemTZ=' $LOG_FILE"
    fi
else
    check_warn "Log file not found at $LOG_FILE"
fi
echo ""

echo "4. Checking application.yml Configuration..."
APP_YML="backend/src/main/resources/application.yml"
if [ ! -f "$APP_YML" ]; then
    APP_YML="./src/main/resources/application.yml"
fi

if [ -f "$APP_YML" ]; then
    if grep -q "connection-timezone: UTC" "$APP_YML"; then
        check_pass "application.yml has refdb.connection-timezone: UTC"
    else
        check_warn "application.yml missing refdb.connection-timezone: UTC"
    fi
    
    if grep -q "lookback-buffer-seconds:" "$APP_YML"; then
        BUFFER=$(grep "lookback-buffer-seconds:" "$APP_YML" | awk '{print $2}')
        check_pass "application.yml has lookback-buffer-seconds: $BUFFER"
    else
        check_warn "application.yml missing lookback-buffer-seconds configuration"
    fi
else
    check_warn "application.yml not found at $APP_YML"
fi
echo ""

echo "5. Checking Elasticsearch Query Success Rate..."
if [ -f "$LOG_FILE" ]; then
    TOTAL_QUERIES=$(tail -n 5000 "$LOG_FILE" | grep -c "ES query RESULT:" 2>/dev/null || echo "0")
    SUCCESS_QUERIES=$(tail -n 5000 "$LOG_FILE" | grep -c "ES query RESULT: Success" 2>/dev/null || echo "0")
    NOTFOUND_QUERIES=$(tail -n 5000 "$LOG_FILE" | grep -c "ES query RESULT: NotFound" 2>/dev/null || echo "0")
    
    if [ "$TOTAL_QUERIES" -gt 0 ]; then
        SUCCESS_RATE=$((SUCCESS_QUERIES * 100 / TOTAL_QUERIES))
        echo "   Recent ES queries (last 5000 log lines):"
        echo "   - Total: $TOTAL_QUERIES"
        echo "   - Success: $SUCCESS_QUERIES ($SUCCESS_RATE%)"
        echo "   - NotFound: $NOTFOUND_QUERIES"
        
        if [ "$SUCCESS_RATE" -ge 80 ]; then
            check_pass "ES success rate >= 80% (healthy)"
        elif [ "$SUCCESS_RATE" -ge 50 ]; then
            check_warn "ES success rate $SUCCESS_RATE% (moderate, investigate)"
        else
            check_fail "ES success rate $SUCCESS_RATE% (low, timezone likely misconfigured)"
        fi
    else
        check_warn "No ES query results found in recent logs"
    fi
else
    check_warn "Cannot check ES query success rate (log file not found)"
fi
echo ""

echo "=========================================="
echo "Verification Summary"
echo "=========================================="
echo -e "${GREEN}Passed:${NC}   $SUCCESS"
echo -e "${YELLOW}Warnings:${NC} $WARNINGS"
echo -e "${RED}Failed:${NC}   $FAILURES"
echo ""

if [ "$FAILURES" -eq 0 ] && [ "$WARNINGS" -eq 0 ]; then
    echo -e "${GREEN}✓ All checks passed! Timezone configuration is correct.${NC}"
    exit 0
elif [ "$FAILURES" -gt 0 ]; then
    echo -e "${RED}✗ Configuration issues detected. Review failures above.${NC}"
    echo ""
    echo "Common fixes:"
    echo "1. Add -Duser.timezone=UTC to JVM startup (systemd/docker/script)"
    echo "2. Restart the application"
    echo "3. Check logs for systemTZ=UTC"
    exit 1
else
    echo -e "${YELLOW}⚠ Configuration mostly correct, but some checks inconclusive.${NC}"
    exit 0
fi
