# Queue Full Monitoring Transition Solution

## Current Situation

### Problem
When the sender queue table (`DTP_SENDER_QUEUE_ITEM`) has **more than 600 items**, the system blocks further enqueueing:

**Backend Behavior**:
1. **SenderDispatchService** (line 173-175):
   ```java
   if (available <= 0) {
       log.info("Queue for site {} sender {} already at capacity {} ({} existing)", 
           site, senderId, maxQueueSize, existing);
       return; // BLOCKS enqueueing
   }
   ```

2. **SenderService** (line 42-45):
   ```java
   long threshold = env.getProperty("app.sender.threshold", Long.class, 600L);
   if (pending > threshold) {
       log.info("Queue above threshold ({} > {}). Sender will not run.", pending, threshold);
       return; // BLOCKS sender execution
   }
   ```

**Frontend Behavior**:
- Staging still creates a session
- Files are staged to `STAGE_RECORD` table with status `pending`
- But **nothing gets enqueued** because queue is full
- User is stuck on Step 2 (Staging) with no visible progress

### User Request
> "Possible to transition to monitoring stepper 3 UI after stage trigger even if sender queue table is full or has more than 600 queues? It will just be monitored stepper 3 UI monitoring?"

**Answer**: YES - this is the correct UX behavior. The session IS created, files ARE staged, and the user SHOULD see Step 3 monitoring UI even when the queue is full.

---

## Solution: Allow Step 3 Transition Regardless of Queue Status

### Core Principle
**Staging success ≠ Enqueueing success**

The session is valid and monitoring should start when:
- ✅ Session is created (sessionId exists)
- ✅ Files are staged to `STAGE_RECORD` table
- ❌ Files don't need to be enqueued immediately

### Why This Makes Sense
1. **Background dispatch continues**: The `@Scheduled` dispatcher will enqueue files as queue space becomes available
2. **User can monitor progress**: Step 3 UI shows staged files waiting to be queued
3. **Transparent status**: User sees `READY` → `QUEUED_FOR_CP` → `ENRICHING` → `COMPLETED` as capacity allows
4. **No dead end**: Currently, users are stuck in Step 2 with no visibility

---

## Implementation Changes

### ✅ Frontend Already Handles This Correctly

The stepper component **already transitions to Step 3** even when staging returns 0 enqueued:

```typescript
// frontend/src/app/stepper/stepper.component.ts (line 2915-2925)
if (stagedCount <= 0) {
  this.staging.set(false);
  // Files are already in the queue — proceed to monitoring so the user
  // can see their current status rather than hitting a dead end.
  this.restagedCount.set(payloads.length);
  this.toast.info(
    `These ${payloads.length} file${payloads.length === 1 ? ' is' : 's are'} 
     already staged and queued. Opening monitoring to track progress.`,
    6000,
  );
  this.completeStep(1);
  this.currentStep.set(2);
  this.startMonitoring(); // ✅ Transitions to Step 3
  return;
}
```

**The logic is already there!** It handles the case where files are staged but not immediately enqueued.

---

### Backend Behavior is CORRECT

The backend correctly:
1. ✅ Creates the session (returns `sessionId`)
2. ✅ Stages files to `STAGE_RECORD` with `status='pending'`
3. ✅ Attempts to enqueue up to available capacity
4. ✅ Returns count of actually enqueued files
5. ✅ Scheduled dispatcher (`@Scheduled(fixedDelayString = "${refdb.dispatch.interval-ms:60000}")`) continues to try enqueueing in the background

**No backend changes needed** - the system is designed this way intentionally.

---

## Current System Flow (When Queue is Full)

### Step 1: User clicks "Stage Selected"
```
Frontend → POST /api/stage/sessions
         → Backend creates StagingSession
         → Returns { sessionId: "abc123", status: "STAGING" }
```

### Step 2: Frontend stages payloads
```
Frontend → POST /api/sender/{senderId}/stage-payloads
         → Backend:
           1. Inserts records into STAGE_RECORD (status='pending')
           2. Calls SenderDispatchService.pushGroup()
           3. Checks queue capacity: 650 existing > 600 max
           4. Logs: "Queue already at capacity"
           5. Returns { staged: 100, enqueued: 0, duplicates: 0 }
```

### Step 3: Frontend receives response
```typescript
stagedCount = 100
enqueuedCount = 0

// Current behavior - CORRECT:
if (stagedCount <= 0) {
  // Transition to Step 3 monitoring
  this.completeStep(1);
  this.currentStep.set(2);
  this.startMonitoring();
}
```

**BUT WAIT** - if `stagedCount > 0`, the code continues with normal flow and **also transitions to Step 3**:

```typescript
// Line 2928-2935
this.restagedCount.set(response?.requeued ?? 0);
this.completeStep(1);
this.currentStep.set(2);
this.staging.set(false);
console.log('[STAGING] Moving to step 3 and starting monitoring');
this.startMonitoring(); // ✅ Always transitions
```

---

## The Real Question: What's the Actual Problem?

If the code already transitions to Step 3, what's the issue?

### Possible Issues:

1. **Toast Message Confusion**
   - Current: "Successfully staged 100 payloads" (implies they're processing)
   - Reality: Files are staged but **not enqueued** (queue is full)
   - **User expects progress but sees nothing moving**

2. **Step 3 UI Shows "Waiting"**
   - Files stuck in `READY` status
   - No visible indication that queue is full
   - User doesn't know why nothing is processing

3. **No Feedback About Queue Capacity**
   - Backend logs: "Queue already at capacity"
   - User sees: Nothing (logs are server-side)
   - **Missing UI feedback**

---

## Recommended Enhancements

### 1. Add Queue Capacity Feedback (Backend Response)

**Modify**: `backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/service/SenderDispatchService.java`

```java
// Add to StagePayloadsResponse (or create new field)
public record DispatchResult(
    int staged,
    int enqueued,
    int duplicates,
    boolean queueAtCapacity,  // NEW
    int queueAvailable        // NEW
) {}

// In pushGroup method (line 173-175):
if (available <= 0) {
    log.info("Queue for site {} sender {} already at capacity {} ({} existing)", 
        site, senderId, maxQueueSize, existing);
    
    // Return capacity info instead of silent return
    return new DispatchResult(0, 0, 0, true, 0);
}
```

### 2. Show Queue Status Banner in Step 3 UI

**Modify**: `frontend/src/app/stepper/stepper.component.ts`

Add a signal to track queue status:

```typescript
queueAtCapacity = signal(false);
queueAvailableSlots = signal(0);

// In stagePayloads response handler (line 2900+):
next: (response: any) => {
  if (response?.queueAtCapacity) {
    this.queueAtCapacity.set(true);
    this.queueAvailableSlots.set(response?.queueAvailable ?? 0);
    
    this.toast.warning(
      `Sender queue is at capacity. ${stagedCount} files staged and ` +
      `will be queued automatically as capacity becomes available.`,
      8000
    );
  }
  
  // ... existing transition logic ...
  this.startMonitoring();
}
```

### 3. Add Queue Status Banner in Step 3 Template

**Modify**: `frontend/src/app/stepper/stepper.component.html`

```html
<!-- In Step 3 content, after existing banners -->
@if (queueAtCapacity() && currentStep() === 2) {
  <div class="info-banner queue-capacity-banner">
    <app-glass-icon name="schedule" [size]="20"></app-glass-icon>
    <div class="banner-content">
      <div class="banner-title">Sender Queue At Capacity</div>
      <div class="banner-message">
        Files are staged and ready. They will be automatically queued and 
        processed as soon as capacity becomes available (checked every minute).
      </div>
    </div>
    <button class="banner-action" (click)="refreshQueueStatus()">
      Check Status
    </button>
  </div>
}
```

### 4. Add Refresh Queue Status Method

```typescript
refreshQueueStatus(): void {
  const site = this.selectedSite();
  const senderId = this.selectedSenderId();
  
  if (!site || !senderId) return;
  
  this.backend.getSenderQueueCount(senderId, site).subscribe({
    next: (result) => {
      const queueCount = result.count;
      const threshold = 600; // From config
      
      if (queueCount < threshold) {
        this.queueAtCapacity.set(false);
        this.toast.success(
          `Queue capacity available! Files will begin processing soon.`,
          5000
        );
      } else {
        this.toast.info(
          `Queue still at capacity (${queueCount} items). ` +
          `Will retry automatically.`,
          5000
        );
      }
    },
    error: (err) => {
      console.error('Failed to check queue status:', err);
    }
  });
}
```

---

## Summary

### Current Behavior
✅ **Already transitions to Step 3 monitoring** when queue is full
✅ Backend correctly stages files even when queue is full
✅ Background dispatcher automatically enqueues files as capacity allows

### Missing Pieces
❌ **No user feedback** that queue is full
❌ **No visibility** into why files aren't processing immediately
❌ **No indication** that automatic processing will happen

### Solution
1. ✅ **Keep current transition logic** - it's correct
2. ➕ **Add queue capacity response field** from backend
3. ➕ **Show queue status banner** in Step 3 UI
4. ➕ **Provide "Check Status" button** for manual refresh
5. ➕ **Toast notification** explaining delayed processing

---

## Implementation Priority

### Phase 1: Quick Fix (5 minutes)
Just improve the toast message to indicate queue is full:

```typescript
// When stagedCount > 0 but enqueuedCount === 0
this.toast.info(
  `${stagedCount} files staged. Processing will begin automatically ` +
  `as queue capacity becomes available (monitored every minute).`,
  8000
);
```

### Phase 2: Full Solution (30 minutes)
Implement all enhancements above for complete transparency.

---

## Testing

After implementation, test this scenario:

1. **Fill queue to >600 items** (via manual staging or bulk operations)
2. **Stage 10 new files**
3. **Verify**:
   - ✅ Session is created
   - ✅ Files are staged with `status='pending'`
   - ✅ **Step 3 UI opens** (not stuck in Step 2)
   - ✅ Queue capacity banner is shown
   - ✅ Toast explains delayed processing
   - ✅ Files eventually transition to `QUEUED_FOR_CP` as capacity frees up
   - ✅ User can monitor progress in real-time

The UX should feel **intentional and transparent**, not broken.
