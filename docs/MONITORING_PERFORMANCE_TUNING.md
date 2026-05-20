# Staging Monitoring Performance Tuning Guide

## Overview
This guide explains the timing parameters in the staging monitoring system and how to optimize them for different use cases.

## Key Timing Parameters

### Backend: SenderQueueMonitor Interval
**Location**: `SenderQueueMonitor.java`
```java
@Scheduled(fixedDelayString = "${refdb.dispatch.monitor-interval-ms:10000}")
```

**Default**: 10 seconds (optimized from 30s)
**What it does**: How often the system checks the external queue to detect completed files
**Impact**: Lower = faster completion detection, but more database queries

**Recommendations**:
- **Small batches (1-10 files)**: 5000ms (5 seconds) for near-instant feedback
- **Medium batches (10-100 files)**: 10000ms (10 seconds) - current default
- **Large batches (100+ files)**: 15000-30000ms to reduce database load

**Configuration**: Add to `application.properties`:
```properties
refdb.dispatch.monitor-interval-ms=5000
```

### Frontend: SSE Connection Timeout
**Location**: `staging-session.service.ts`
```typescript
private readonly sseConnectTimeoutMs = 3000;
```

**Default**: 3 seconds (optimized from 6s)
**What it does**: How long to wait for SSE connection before falling back to polling
**Impact**: Lower = faster fallback to polling if SSE unavailable

### Frontend: Polling Interval
**Location**: `staging-session.service.ts`
```typescript
this.pollingSub = interval(2000).subscribe(() => {
```

**Default**: 2 seconds (optimized from 3s)
**What it does**: How often to poll for updates when SSE is unavailable
**Impact**: Lower = more responsive UI, but more HTTP requests

## Performance Optimization Strategies

### For Development/Testing (Fast Feedback)
```properties
# Backend - Check queue every 5 seconds
refdb.dispatch.monitor-interval-ms=5000
```

```typescript
// Frontend - Aggressive polling
private readonly sseConnectTimeoutMs = 2000;
this.pollingSub = interval(1000).subscribe(() => {
```

**Result**: ~5-7 second total latency for single file completion

### For Production (Balanced)
```properties
# Backend - Check queue every 10 seconds (current default)
refdb.dispatch.monitor-interval-ms=10000
```

```typescript
// Frontend - Current defaults
private readonly sseConnectTimeoutMs = 3000;
this.pollingSub = interval(2000).subscribe(() => {
```

**Result**: ~10-13 second total latency, good balance of responsiveness and load

### For High-Volume Production (Reduced Load)
```properties
# Backend - Check queue every 30 seconds
refdb.dispatch.monitor-interval-ms=30000
```

```typescript
// Frontend - Relaxed polling
private readonly sseConnectTimeoutMs = 5000;
this.pollingSub = interval(5000).subscribe(() => {
```

**Result**: ~30-35 second latency, minimal database/network load

## Understanding the Latency Chain

For a single file, the total time from "file enters external queue" to "UI shows completed" is:

1. **External queue processing time**: Variable (depends on sender/external system)
2. **SenderQueueMonitor detection**: 0 to `monitor-interval-ms` (average: interval/2)
3. **SSE event transmission**: ~100-500ms (if SSE connected)
4. **Frontend update**: Immediate (signals update automatically)

**Example with current defaults (10s interval)**:
- Best case: External processing + 0s + 0.5s = ~0.5s after external completion
- Average case: External processing + 5s + 0.5s = ~5.5s after external completion
- Worst case: External processing + 10s + 0.5s = ~10.5s after external completion

## Troubleshooting Slow Updates

### Issue: "Connecting to monitoring stream..." stays for long time
**Cause**: SSE connection not establishing
**Solutions**:
1. Check browser console for SSE errors
2. Verify `/resender/api/stage/sessions/{id}/monitor` endpoint is accessible
3. Check for proxy/firewall blocking SSE connections
4. System will auto-fallback to polling after 3 seconds

### Issue: Updates are slow even with fast settings
**Possible causes**:
1. **External queue is slow**: The system can only detect completion after the external sender processes the file
2. **Database connection issues**: Check RefDB connection pool settings
3. **Network latency**: Check latency between backend and external database

### Issue: Too many database queries
**Solution**: Increase `monitor-interval-ms` to reduce query frequency

## Monitoring the Monitor

### Backend Logs
Look for these log messages:
```
INFO  SenderQueueMonitor - Marked 1 staged payloads complete for site X sender Y
```

### Frontend DevTools
Check Network tab for:
- SSE connection to `/monitor` endpoint
- Polling requests to `/sessions/{id}` endpoint

### Performance Metrics
- **SSE connected**: Best performance, real-time updates
- **Polling mode**: Good performance, 2-5 second updates
- **No connection**: Check backend/network issues

## Recommended Settings by Use Case

| Use Case | Monitor Interval | SSE Timeout | Polling Interval | Expected Latency |
|----------|-----------------|-------------|------------------|------------------|
| Development | 5s | 2s | 1s | 5-7s |
| Demo/Testing | 10s | 3s | 2s | 10-13s |
| Production (Low Volume) | 10s | 3s | 2s | 10-13s |
| Production (High Volume) | 20-30s | 5s | 5s | 20-35s |
| Single File Testing | 5s | 2s | 1s | 5-7s |

## Current Optimized Settings

The system is now configured with these optimized defaults:
- **Backend monitor interval**: 10 seconds (down from 30s)
- **SSE connection timeout**: 3 seconds (down from 6s)
- **Polling interval**: 2 seconds (down from 3s)

These provide a good balance for most use cases, including single file testing.
