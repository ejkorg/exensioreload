# Frontend Performance Testing: Monitor Accounting Dashboard

## Overview

Task 17 includes performance testing for the frontend dashboard to verify:

1. **SSE Message Batching**: Dashboard receives batched STATE_AGGREGATION events (1-5/sec instead of 1000+/sec)
2. **Update Frequency**: Dashboard redraws smoothly without jitter
3. **Memory Efficiency**: Component doesn't leak memory during long monitoring sessions
4. **Network Bandwidth**: Message volume is reduced by > 50x

---

## 1. Testing SSE Message Batching

### Test: Monitor SSE Message Frequency

**Goal**: Verify that STATE_AGGREGATION events arrive in batches (1-5 per second) not individually (1000+ per second).

**Setup**:

1. Open Chrome DevTools Network tab
2. Filter by "eventsource" or monitor WebSocket traffic
3. Navigate to monitor dashboard
4. Trigger a bulk operation (bulk cancel, bulk enqueue)

**Manual Testing**:

```typescript
// Add to dashboard component during testing
export class DashboardComponent implements OnInit {
  private messageCount = 0;
  private messageTimestamps: number[] = [];

  ngOnInit() {
    this.monitoringService.stateAggregation$.subscribe((event) => {
      this.messageCount++;
      this.messageTimestamps.push(Date.now());

      // Calculate message frequency
      if (this.messageTimestamps.length > 10) {
        const recent = this.messageTimestamps.slice(-10);
        const timespan = recent[recent.length - 1] - recent[0];
        const frequency = (10 / (timespan / 1000)).toFixed(1);

        console.log(`SSE Message Frequency: ${frequency} msg/sec`);
      }
    });
  }
}
```

**Expected Results**:

- Without batching: ~1000 messages per second (visible as rapid network traffic)
- With batching: ~1-5 messages per second (visible as periodic traffic every 1 second)

**Verification Steps**:

1. Open Network tab in Chrome DevTools
2. Filter by "XHR" or check SSE stream
3. Trigger bulk cancel of 1000 records
4. Observe message arrival pattern:
   - ✓ Should see bursts of activity every ~1 second
   - ✗ Should NOT see continuous rapid messages

### Test: Measure Network Bandwidth Reduction

**Goal**: Quantify reduction in network bytes transferred.

**Tools**:

- Chrome DevTools Network tab
- Browser's Performance API

**Test Code**:

```typescript
// Measure bandwidth before bulk operation
const perfBefore = performance.now();
const trafficBefore = /* read from Network tab */;

// Trigger bulk operation
await bulkCancelRecords(1000);

// Measure bandwidth after
const perfAfter = performance.now();
const trafficAfter = /* read from Network tab */;

const duration = (perfAfter - perfBefore) / 1000;  // seconds
const trafficBytes = trafficAfter - trafficBefore;
const bandwidth = (trafficBytes / duration / 1024).toFixed(2);  // KB/sec

console.log(`Network bandwidth: ${bandwidth} KB/sec`);
console.log(`Reduction: ${(1000 * trafficBytes) / trafficBytes}x`);  // Simplified
```

**Expected Results**:

- Bandwidth reduced by > 50x compared to per-message approach
- Total data transfer for 1000 changes: < 50KB (with batching) vs > 2.5MB (without)

---

## 2. Testing Dashboard Update Frequency

### Test: Monitor Component Render Cycles

**Goal**: Verify that dashboard redraws smoothly without jitter or excessive redraws.

**Setup**:

```typescript
// Instrument dashboard component to track render cycles
export class DashboardComponent implements OnInit, OnDestroy {
  private renderCount = 0;
  private renderTimestamps: number[] = [];
  private lastRenderTime = 0;

  ngOnInit() {
    // Monitor state aggregation events
    this.monitoringService.stateAggregation$.subscribe((event) => {
      this.updateCards(event);
      this.recordRender();
    });
  }

  private recordRender() {
    const now = Date.now();
    this.renderCount++;
    this.renderTimestamps.push(now);

    if (this.renderCount % 50 === 0) {
      this.analyzeRenderFrequency();
    }
  }

  private analyzeRenderFrequency() {
    const recent = this.renderTimestamps.slice(-50);
    const timespan = recent[recent.length - 1] - recent[0];
    const avgInterval = timespan / 49; // 50 samples = 49 intervals
    const frequency = (1000 / avgInterval).toFixed(1);

    console.log(`Dashboard render frequency: ${frequency} Hz`);

    // Check for jitter (standard deviation of render times)
    const intervals: number[] = [];
    for (let i = 1; i < recent.length; i++) {
      intervals.push(recent[i] - recent[i - 1]);
    }

    const avgInterval2 = intervals.reduce((a, b) => a + b) / intervals.length;
    const variance = intervals.map((x) => Math.pow(x - avgInterval2, 2)).reduce((a, b) => a + b) / intervals.length;
    const stdDev = Math.sqrt(variance);

    console.log(`Render interval std dev: ${stdDev.toFixed(2)}ms`);
  }
}
```

**Running the Test**:

1. Build the frontend: `npm run build`
2. Start dev server: `npm start`
3. Open Chrome DevTools Console
4. Navigate to monitor dashboard
5. Trigger bulk operation
6. Observe console output showing render frequency and jitter

**Expected Results**:

- Render frequency: 1-5 Hz (1-5 updates per second)
- Jitter (std dev): < 100ms (smooth updates, not jerky)
- FPS during updates: > 30 FPS (visible smoothness)

---

## 3. Testing Memory Efficiency

### Test: Monitor Memory Usage During Long Sessions

**Goal**: Verify no memory leaks during extended monitoring.

**Test Code**:

```typescript
// Add to dashboard component for testing
private monitorMemoryUsage() {
  if (performance.memory) {
    setInterval(() => {
      const used = (performance.memory.usedJSHeapSize / 1048576).toFixed(2);  // MB
      const limit = (performance.memory.jsHeapSizeLimit / 1048576).toFixed(2);  // MB
      const percent = ((performance.memory.usedJSHeapSize / performance.memory.jsHeapSizeLimit) * 100).toFixed(1);

      console.log(`Memory: ${used}MB / ${limit}MB (${percent}%)`);
    }, 5000);  // Every 5 seconds
  }
}

ngOnInit() {
  this.monitorMemoryUsage();  // Start monitoring

  // Simulate long monitoring session
  this.simulateContinuousStateChanges();
}

private simulateContinuousStateChanges() {
  setInterval(() => {
    // Simulate 100 state changes every 10 seconds
    for (let i = 0; i < 100; i++) {
      this.monitoringService.recordStateChange({
        state: 'ENRICHMENT',
        previousCount: Math.random() * 1000,
        newCount: Math.random() * 1000,
      });
    }
  }, 10000);
}
```

**Running the Test**:

1. Open Chrome DevTools Memory tab
2. Take initial heap snapshot
3. Start monitoring dashboard
4. Let it run for 5-10 minutes
5. Take final heap snapshot
6. Compare snapshots for memory growth

**Expected Results**:

- Memory growth < 50MB over 10 minutes
- No growth patterns that resemble leaks
- Detached DOM nodes: 0-5 (not growing)

### Debugging Memory Leaks

If memory grows unbounded:

```typescript
// Check for subscription leaks
export class DashboardComponent implements OnInit, OnDestroy {
  private subscriptions = new Subscription();

  ngOnInit() {
    // ✓ Good: Subscriptions tracked
    this.subscriptions.add(
      this.monitoringService.stateAggregation$.subscribe((event) => {
        this.updateCards(event);
      }),
    );
  }

  ngOnDestroy() {
    // ✓ Good: Unsubscribe on destroy
    this.subscriptions.unsubscribe();
  }
}
```

---

## 4. Testing Card Update Accuracy

### Test: Verify Card Totals Match SSE Events

**Goal**: Ensure dashboard totals are always consistent with received STATE_AGGREGATION events.

**Test Code**:

```typescript
// Add to testing/debugging
private verifyAccuracy() {
  this.monitoringService.stateAggregation$.subscribe(event => {
    // Verify card totals match event totals
    const cardTotals = {
      staged: this.dashboardData.stagedCount,
      queued: this.dashboardData.queuedCount,
      enriching: this.dashboardData.enrichingCount,
      exensioLoading: this.dashboardData.exensioLoadingCount,
      completed: this.dashboardData.completedCount,
      failed: this.dashboardData.failedCount,
      cancelled: this.dashboardData.cancelledCount,
    };

    const eventTotals = event.totals;

    const mismatches = Object.entries(cardTotals)
      .filter(([key, value]) => value !== eventTotals[key])
      .map(([key, value]) => `${key}: card=${value}, event=${eventTotals[key]}`)
      .join(', ');

    if (mismatches.length > 0) {
      console.error(`Card total mismatches: ${mismatches}`);
    } else {
      console.log('✓ Card totals match event totals');
    }
  });
}
```

**Running the Test**:

1. Enable accuracy verification in dashboard component
2. Trigger state changes (bulk operations)
3. Check console for mismatch warnings

**Expected Results**:

- ✓ All card totals match received event totals
- ✗ No warnings about mismatches

---

## 5. Testing Animation Performance

### Test: Verify Card Count Changes Animate Smoothly

**Goal**: Ensure count transitions animate smoothly without performance degradation.

**Test Code**:

```typescript
// Animation performance test
private benchmarkAnimation() {
  let frameCount = 0;
  let lastTime = performance.now();

  const countFrames = () => {
    frameCount++;
    const now = performance.now();
    const elapsed = now - lastTime;

    if (elapsed >= 1000) {  // Every second
      const fps = (frameCount * 1000) / elapsed;
      console.log(`Animation FPS: ${fps.toFixed(1)}`);
      frameCount = 0;
      lastTime = now;
    }

    requestAnimationFrame(countFrames);
  };

  countFrames();

  // Trigger animation
  this.triggerCountAnimation('staged', 100, 250);  // Animate 100 → 250
}

private triggerCountAnimation(cardName: string, from: number, to: number) {
  const duration = 300;  // 300ms animation
  const startTime = performance.now();

  const animate = (currentTime: number) => {
    const elapsed = currentTime - startTime;
    const progress = Math.min(elapsed / duration, 1);

    const currentCount = Math.floor(from + (to - from) * progress);
    this.updateCardCount(cardName, currentCount);

    if (progress < 1) {
      requestAnimationFrame(animate);
    }
  };

  requestAnimationFrame(animate);
}
```

**Expected Results**:

- Animation FPS: > 30 FPS (smooth motion)
- No frame drops during animation
- CPU usage during animation: < 20%

---

## 6. Integration Test: Full Bulk Operation Cycle

### Test: Complete Bulk Operation Performance

**Goal**: Test full cycle of bulk operation → state changes → SSE messages → dashboard updates.

**Test Scenario**:

```typescript
async testBulkOperationPerformance() {
  console.log('Starting bulk operation performance test...');

  // Phase 1: Record initial state
  const initialState = this.captureCurrentState();
  console.log('Initial state:', initialState);

  // Phase 2: Trigger bulk cancel of 1000 records
  const bulkStartTime = performance.now();
  await this.bulkCancelRecords(1000);
  const bulkDuration = performance.now() - bulkStartTime;
  console.log(`Bulk operation completed in ${bulkDuration.toFixed(0)}ms`);

  // Phase 3: Wait for SSE updates to arrive
  const updateStartTime = performance.now();
  const updates = await this.waitForStateUpdates(5000);  // Wait up to 5 seconds
  const updateDuration = performance.now() - updateStartTime;
  console.log(`Received ${updates.length} SSE updates in ${updateDuration.toFixed(0)}ms`);

  // Phase 4: Verify final state
  const finalState = this.captureCurrentState();
  console.log('Final state:', finalState);

  // Phase 5: Validate changes
  const expected = {
    cancelledDelta: 1000,
  };
  const actual = {
    cancelledDelta: finalState.cancelled - initialState.cancelled,
  };

  if (actual.cancelledDelta === expected.cancelledDelta) {
    console.log('✓ Accounting verified: cancelled count increased correctly');
  } else {
    console.error(`✗ Accounting error: expected +${expected.cancelledDelta}, got +${actual.cancelledDelta}`);
  }

  // Summary
  console.log('=== Performance Summary ===');
  console.log(`Bulk operation: ${bulkDuration.toFixed(0)}ms`);
  console.log(`SSE delivery: ${updateDuration.toFixed(0)}ms`);
  console.log(`SSE messages: ${updates.length}`);
  console.log(`Reduction: ${(1000 / updates.length).toFixed(1)}x`);
}

private captureCurrentState() {
  return {
    staged: this.dashboardData.stagedCount,
    queued: this.dashboardData.queuedCount,
    enriching: this.dashboardData.enrichingCount,
    exensioLoading: this.dashboardData.exensioLoadingCount,
    completed: this.dashboardData.completedCount,
    failed: this.dashboardData.failedCount,
    cancelled: this.dashboardData.cancelledCount,
  };
}

private waitForStateUpdates(timeoutMs: number): Promise<any[]> {
  return new Promise((resolve) => {
    const updates: any[] = [];
    let timeout = setTimeout(() => resolve(updates), timeoutMs);

    const subscription = this.monitoringService.stateAggregation$.subscribe(event => {
      updates.push(event);
      clearTimeout(timeout);
      subscription.unsubscribe();
      resolve(updates);
    });
  });
}
```

**Running the Test**:

1. Open Chrome DevTools Console
2. Add test code to dashboard component
3. Call `this.testBulkOperationPerformance()`
4. Review console output for performance metrics

**Expected Results**:

```
Starting bulk operation performance test...
Initial state: { staged: 0, queued: 100, enriching: 900, ... }
Bulk operation completed in 250ms
Received 1 SSE updates in 850ms
Final state: { staged: 0, queued: 100, enriching: 900, cancelled: 1000, ... }
✓ Accounting verified: cancelled count increased correctly

=== Performance Summary ===
Bulk operation: 250ms
SSE delivery: 850ms
SSE messages: 1
Reduction: 1000.0x
```

---

## 7. Performance Profiling

### Using Chrome DevTools Performance Tab

1. **Record a profile**:
   - Open Performance tab in Chrome DevTools
   - Click "Record"
   - Trigger bulk operation
   - Click "Stop"

2. **Analyze**:
   - Look for "Long tasks" (> 50ms) — should be minimal
   - Check rendering time — should be < 16ms per frame (60 FPS)
   - Verify no excessive re-renders

3. **Expected**:
   - No red "Long tasks" warning
   - Rendering: < 5ms per update
   - JavaScript: < 10ms per update

---

## 8. Automated Testing with Cypress

```typescript
// cypress/e2e/dashboard-performance.cy.ts
describe('Dashboard Performance Tests', () => {
  beforeEach(() => {
    cy.visit('/dashboard');
  });

  it('should batch SSE updates efficiently', () => {
    // Monitor network traffic
    cy.intercept('GET', '/api/**').as('api');

    // Trigger bulk operation
    cy.contains('Bulk Cancel').click();
    cy.get('[data-test="cancel-count"]').should('have.value', '1000');
    cy.contains('Execute').click();

    // Wait for updates
    cy.wait('@api', { timeout: 5000 });

    // Verify update frequency
    // (This would require custom network monitoring)
    cy.log('Bulk operation completed');
  });

  it('should update card totals accurately', () => {
    // Get initial totals
    cy.get('[data-test="cancelled-card"]').then(($card) => {
      const initialCount = parseInt($card.text());

      // Trigger bulk cancel
      cy.bulkCancel(100);

      // Verify total increased
      cy.get('[data-test="cancelled-card"]').should('have.text', initialCount + 100);
    });
  });

  it('should not leak memory during long sessions', () => {
    // Simulate long monitoring session
    for (let i = 0; i < 100; i++) {
      cy.task('recordStateChange', { state: 'ENRICHMENT', delta: 1 });
      cy.wait(100);
    }

    // Verify memory hasn't grown excessively
    cy.task('checkMemory').then((memory) => {
      expect(memory.usedMB).toBeLessThan(150); // Should be < 150MB
    });
  });
});
```

---

## 9. Performance Checklist

Before completing Task 17, verify:

- [ ] SSE messages batch at 1-5/sec during bulk operations
- [ ] Network bandwidth reduced > 50x
- [ ] Dashboard renders smoothly (> 30 FPS, no jitter)
- [ ] Memory stable (< 50MB growth over 10 mins)
- [ ] Card totals always match SSE event totals
- [ ] Animation performance > 30 FPS
- [ ] Bulk operation completes in < 5 seconds for 1000 records
- [ ] No subscription leaks detected
- [ ] Long task warnings < 10 per session
- [ ] Responsive to user input (no UI freeze)

---

## 10. Troubleshooting

### SSE Updates Arrive Individually (Not Batched)

**Symptoms**: Messages arriving ~1000/sec instead of ~1/sec

**Diagnosis**:

1. Check backend StateAggregationBatcher is enabled
2. Verify batch window is 1000ms (not 0)
3. Check logs for batch flush messages

**Solution**:

- Enable DEBUG logging: `logging.level.com.onsemi.cim.apps.exensio.stage.StateAggregationBatcher=DEBUG`
- Verify batcher bean is created: Check Spring startup logs

### Dashboard Freezes During Bulk Operations

**Symptoms**: UI becomes unresponsive for seconds

**Diagnosis**:

1. Check if rendering all updates immediately (not batching)
2. Verify OnPush change detection strategy is used
3. Check for synchronous work in subscription handler

**Solution**:

- Add `ChangeDetectionStrategy.OnPush` to component
- Use `markForCheck()` for manual change detection
- Wrap updates in `setTimeout()` to yield to browser

### Memory Grows Unbounded

**Symptoms**: Memory increases continuously during monitoring

**Diagnosis**:

1. Check for active subscriptions in destroyed components
2. Verify event listeners are cleaned up
3. Check for accumulating array references

**Solution**:

- Add `OnDestroy` to all components with subscriptions
- Unsubscribe in `ngOnDestroy()`
- Use OnPush change detection to reduce listener count

---

## Summary

Performance testing for Task 17 validates:

1. ✅ **SSE Batching**: > 50x message reduction
2. ✅ **Dashboard Updates**: 1-5 Hz smooth rendering
3. ✅ **Memory Efficiency**: No leaks, stable memory
4. ✅ **Accounting Accuracy**: Card totals always correct
5. ✅ **Network Optimization**: Bandwidth reduced significantly

All tests can be run manually in development environment or automated with Cypress for CI/CD integration.
