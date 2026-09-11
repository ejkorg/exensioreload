# SSE Endpoint Diagnostic Guide

## Current Issue

```
The underlying connection was closed: An unexpected error occurred on a send.
```

This error typically indicates one of these issues:

1. **Backend service not running** on port 8004
2. **Nginx unable to connect** to backend upstream
3. **Backend crashed or not responding**
4. **Network connectivity issue** between nginx and backend

## Diagnostics Checklist

### 1. Verify Backend Service is Running

Run these commands on the server (usaz15ls088):

```bash
# Check if port 8004 is listening
netstat -tlnp | grep 8004
# or
ss -tlnp | grep 8004

# Expected output should show something like:
# tcp  0  0  127.0.0.1:8004  0.0.0.0:*  LISTEN  <PID>/java

# Check if Java process is running
ps aux | grep 8004
ps aux | grep exensio

# Check process status
systemctl status exensioreload
# or if using service:
service exensioreload status
```

### 2. Check Backend Logs

```bash
# Find backend log location
find / -name "*exensio*reload*.log" 2>/dev/null
find /var/log -name "*exensio*" 2>/dev/null
find /export -name "*exensio*reload*.log" 2>/dev/null

# View logs (replace path as needed)
tail -100 /var/log/exensio-reload/error.log
tail -100 /path/to/backend/logs/application.log

# Look for errors starting with:
# - "Cannot find a matching route"
# - "Connection refused"
# - "Address already in use"
# - "ClassNotFoundException" or any startup errors
```

### 3. Test Backend Directly (Bypass Nginx)

```bash
# Test health check endpoint directly on backend
curl -i -H "Authorization: Bearer YOUR_TOKEN" \
  http://usaz15ls088:8004/api/stage/sessions/test/monitor-health

# Expected: 200 OK with JSON response

# If that fails, try basic connectivity
curl -i http://usaz15ls088:8004/actuator/health
# (if Spring Boot actuator is enabled)
```

### 4. Test Nginx Connection to Backend

```bash
# From nginx server, check if it can reach backend
curl -i http://localhost:8004/api/stage/sessions/test/monitor-health
# Should connect to localhost:8004

# Check nginx upstream definition
grep -A 3 "upstream exensioreload_backend" /export/home/dpower/nginx/conf/nginx.conf
# Should show: server usaz15ls088:8004;

# Test from nginx to backend host
ping usaz15ls088
telnet usaz15ls088 8004
```

### 5. Check Nginx Configuration

```bash
# Verify SSE location block is present
grep -n "location.*monitor" /export/home/dpower/nginx/conf/nginx.conf

# Should see:
# location ~ /exensio-reload/api/stage/sessions/.*/monitor {

# Verify location comes before general API block
grep -n "location.*api/" /export/home/dpower/nginx/conf/nginx.conf

# SSE monitor location should have line number BEFORE general /api/ location
```

### 6. Check Nginx Error Log

```bash
# Watch nginx errors in real-time
tail -f /export/home/dpower/nginx/logs/error.log

# Look for errors like:
# - "upstream timed out"
# - "connect() failed"
# - "no live upstreams while connecting to upstream"
# - "502 Bad Gateway"

# Search for recent errors
tail -50 /export/home/dpower/nginx/logs/error.log | grep -i "error\|upstream\|502"
```

### 7. Check Nginx Access Log

```bash
# Check if requests are hitting nginx
tail -20 /export/home/dpower/nginx/logs/access.log | grep monitor

# Look for status codes:
# 200 = Success
# 404 = Not found (nginx routing issue)
# 502 = Backend unavailable
# 504 = Gateway timeout
```

## Most Likely Causes

### Cause 1: Backend Not Running

**Symptoms:**

- `ss -tlnp` doesn't show port 8004
- `ps aux | grep exensio` shows no process

**Fix:**

```bash
# Start the backend service
systemctl start exensioreload
# or
service exensioreload start
# or manually:
cd /path/to/backend
nohup java -jar exensio-reload.jar > /var/log/exensio-reload/app.log 2>&1 &
```

### Cause 2: Backend Crashed

**Symptoms:**

- Backend was running but now isn't
- Recent errors in backend logs
- Port 8004 was listening, now isn't

**Fix:**

```bash
# Check logs for crash reason
tail -200 /var/log/exensio-reload/application.log | tail -50

# Common causes:
# - Out of memory: check heap size (-Xmx setting)
# - Database connection lost: check database connectivity
# - Missing environment variables: check PP_LOG_DB_URL, etc.

# Restart with proper settings
systemctl restart exensioreload
```

### Cause 3: Nginx Configuration Wrong

**Symptoms:**

- Backend responds fine when accessed directly
- But nginx returns 502/504

**Fix:**

```bash
# Verify upstream definition matches
grep "upstream exensioreload_backend" /export/home/dpower/nginx/conf/nginx.conf

# Must match the hostname in /exensio-reload/api/ location
# Check if usaz15ls088 resolves correctly:
nslookup usaz15ls088
# or
ping usaz15ls088

# If DNS fails, use IP instead:
# upstream exensioreload_backend {
#     server 10.253.112.87:8004;
# }
```

### Cause 4: Network/Firewall Issue

**Symptoms:**

- Backend is running
- Direct curl to backend fails
- Nginx can't reach backend

**Fix:**

```bash
# Check firewall rules
sudo iptables -L -n | grep 8004
sudo firewall-cmd --list-all

# If blocked, allow port:
sudo firewall-cmd --permanent --add-port=8004/tcp
sudo firewall-cmd --reload

# Or check SELinux
getenforce
# If "Enforcing", may need to adjust policies
```

## Quick Diagnostics Script

Run this on the server to gather diagnostics:

```bash
#!/bin/bash
echo "=== Backend Service Status ==="
systemctl status exensioreload 2>&1 | head -5
echo ""

echo "=== Port 8004 Listening? ==="
ss -tlnp | grep 8004 || echo "NOT LISTENING"
echo ""

echo "=== Backend Direct Test ==="
curl -s -i http://localhost:8004/api/stage/sessions/test/monitor-health 2>&1 | head -10 || echo "FAILED"
echo ""

echo "=== Nginx Config SSE Location ==="
grep -A 2 "location ~ /exensio-reload/api/stage/sessions/.*/monitor" /export/home/dpower/nginx/conf/nginx.conf | head -5
echo ""

echo "=== Recent Nginx Errors ==="
tail -10 /export/home/dpower/nginx/logs/error.log
echo ""

echo "=== Recent Backend Errors ==="
tail -10 /var/log/exensio-reload/error.log 2>/dev/null || echo "Log not found"
```

## Next Steps

1. **Run diagnostics** to identify which component is failing
2. **Fix the issue** based on findings
3. **Restart services** as needed
4. **Test endpoints** again using curl commands above
5. **Check logs** after each test to understand what's happening

## If Backend Code Changes Need to be Deployed

The backend service must be restarted for the new pipeline-aware code to take effect:

```bash
# Stop current service
systemctl stop exensioreload

# Rebuild (if not already done)
cd /path/to/backend
mvn clean package -DskipTests

# Start with new code
systemctl start exensioreload

# Verify it started
sleep 5
ps aux | grep exensio | grep -v grep
```
