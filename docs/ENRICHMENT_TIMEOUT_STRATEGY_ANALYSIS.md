# Enrichment Timeout Strategy Analysis

**Date:** July 4, 2026  
**Topic:** Is transitioning timeout records to EXENSIO_LOADING the best approach?

---

## Current Implementation: The Timeout → Exensio Fallback Strategy

### What Currently Happens

When a record has **no concrete error** but **timed out from both ES and pp_log** (after 15 minutes):

```
ENRICHMENT (waiting for ES/pp_log)
    ↓ [timeout after 15 min, both ES and pp_log return NotFound]
    ↓ [NO concrete error found]
    ↓ tryExensioDirectLookup()
    ↓
    ├─ Exensio Found → DONE (with waferKey, pgKey)
    ├─ Exensio NotFound → DONE (manual_verify flag, diagnostic summary)
    └─ Exensio Error → DONE (manual_verify flag, error message)
```

**Key Point:** Records go to **DONE**, not EXENSIO_LOADING

(Note: The user question asks if records go to EXENSIO_LOADING - they actually go to DONE with `markDoneManualVerify()`)

---

## Analysis: Is This the Best Approach?

### ✅ PROS of Current Strategy

#### 1. **Avoid Indefinite Waiting (Good)**

- Records don't get stuck in ENRICHMENT forever
- Timeout is respected (15 minutes configurable)
- Prevents pipeline deadlock

#### 2. **Data-Driven Decision (Good)**

- Tries Exensio direct lookup as last resort
- If Exensio has data → actual enrichment happens
- If Exensio has no data → operator can manually verify

#### 3. **Operational Transparency (Good)**

- `markDoneManualVerify()` sets flag so operators know it needs review
- Detailed diagnostic summary explains what was attempted
- Error messages include ES/pp_log/Exensio status

#### 4. **Graceful Degradation (Good)**

- If all sources fail → still marks DONE
- Better than leaving record stuck indefinitely
- Operator can investigate manually

---

### ⚠️ CONS & CONCERNS with Current Strategy

#### 1. **Conflates Timeout with Success**

Problem: Record marked DONE even though enrichment is unresolved

```
Record Status Flow:
ENRICHMENT [15 min]
  → No ES result
  → No pp_log result
  → No Exensio result
  → Marked as DONE ❌ Misleading

From operator's perspective:
"DONE" = successfully enriched
But actually: "Unknown if enriched, manual verify needed"
```

**Issue:** UI shows DONE, but record actually needs verification

#### 2. **Loses Track of Unresolved Records**

Problem: Once marked DONE, hard to find records that need verification

```
All records go to DONE state:
- Actually enriched successfully
- Enrichment uncertain (timeout)
- Failed with error
- Never enriched (manual_verify flag)

Finding the uncertain ones requires:
- Query DONE records
- Filter by manual_verify=true
- Check error_message for "[Enrichment Unresolved]"
```

**Better:** Separate state or tag for "enrichment uncertain"

#### 3. **Exensio Direct Lookup May Be Stale**

Problem: Exensio lookup after 15-minute timeout might return cached/stale data

```
Timeline:
T=0min   → File staged
T=0-5min → ES enriching (actually working)
T=5min   → ES has result but slow to propagate
T=10min  → pp_log might have result
T=15min  → Timeout triggered
T=15min  → Exensio direct lookup
          → Returns stale data from T=5min
          → Claims success when ES still processing
```

**Risk:** False success if Exensio has earlier snapshot

#### 4. **No Retry with Backoff**

Problem: Goes straight from timeout to giving up

```
Current:
ENRICHMENT [15 min]
  → NotFound from ES/pp_log
  → Timeout triggered
  → Try Exensio once
  → Mark DONE (done forever)

Better would be:
  → Back off and retry in 5 min
  → Track retry count
  → Escalate if still unresolved after N retries
```

#### 5. **Accounting Problem: DONE Records Not Actually Complete**

Problem: "DONE" state means different things

```
Current accounting shows:
DONE: 1000 records
But actually:
- 950 successfully enriched (real DONE)
- 50 timeout with unresolved enrichment (uncertain)
```

**This violates the accounting invariant principle** — you lose visibility into actual completion status.

---

## Better Alternatives to Consider

### Option A: Explicit "ENRICHMENT_UNCERTAIN" State (RECOMMENDED)

```
ENRICHMENT
  ↓ [timeout after 15 min, no concrete error]
  ↓
  → ENRICHMENT_UNCERTAIN
      ├─ Exensio Found → DONE (with keys)
      ├─ Exensio NotFound → ENRICHMENT_UNCERTAIN [retry later]
      └─ Exensio Error → ENRICHMENT_UNCERTAIN [retry later]
```

**Pros:**

- Honest state name reflects reality
- Separates uncertain from confirmed done
- Accounting stays clear (uncertain != done)
- Operators know to investigate

**Cons:**

- Adds new state to pipeline
- More complex state machine
- Dashboard needs to handle new state

---

### Option B: Keep DONE, But Enhanced Tracking

```
DONE (marked)
  ├─ status: "DONE"
  ├─ enrichment_confidence: "certain" | "uncertain" | "failed"
  ├─ enrichment_certainty_reason: "ES success", "Timeout - Exensio found", etc.
  └─ requires_manual_review: true/false
```

**Pros:**

- Keeps single DONE state
- Detailed metadata explains situation
- Dashboard can show confidence levels
- Backward compatible

**Cons:**

- Requires schema changes
- Dashboard must interpret confidence levels
- Still shows DONE visually (misleading)

---

### Option C: Time-Based Retry with Max Attempts

```
ENRICHMENT
  ↓ [timeout 15 min, attempt 1]
  → Back off 5 minutes
  ↓ [timeout 15 min, attempt 2]
  → Back off 10 minutes
  ↓ [timeout 15 min, attempt 3]
  → Give up, mark ENRICHMENT_FAILED_TIMEOUT
```

**Pros:**

- Gives multiple chances
- No false early success
- Clear final state

**Cons:**

- Longer overall time to resolution
- More database queries
- Pipeline stays occupied longer

---

## Current Code Assessment

### What the Code Actually Does

```java
// CpLogMonitor.tryExensioDirectLookup()

if (isTimedOut(record)) {  // After 15 minutes with no ES/pp_log
    tryExensioDirectLookup(...);
}

// Possible outcomes:
case Found → markDoneFromExensio()        // Confident DONE
case NotFound → markDoneManualVerify()    // Uncertain DONE
case Error → markDoneManualVerify()       // Failed, needs review
```

### Problem in Current Code

```java
// When Exensio lookup doesn't find record:
refDbService.markDoneManualVerify(record,
    "[Enrichment Unresolved] " + diagnosticSummary
    + " Exensio: not found..."
    + ". Manual verification required."
);
```

**Issue:** Record is marked DONE, but actually **still unresolved**

The `[Enrichment Unresolved]` prefix is a convention, not a state:

- UI doesn't understand this convention
- Dashboard shows DONE (misleading)
- Operators must manually inspect error_message to know it's uncertain

---

## Recommendation: What Should Be Done?

### Short Term (Keep Current, Improve Visibility)

**Status quo but with better UI/reporting:**

1. ✅ Keep timeout → Exensio fallback (it works)
2. ⚠️ Add dashboard filter: "DONE records requiring manual review"
   - Query: `status = 'DONE' AND error_message LIKE '%Enrichment Unresolved%'`
   - Show these separately from successfully enriched records

3. ⚠️ Add accounting metric: "Enrichment Certainty"

   ```
   Total: 1000
   - Certainly Done: 950 (ES/pp_log success + Exensio found)
   - Uncertain Done: 50 (timeout with unresolved)
   ```

4. ⚠️ Add admin endpoint to list uncertain records for operator review

---

### Medium Term (Recommended)

**Add intermediate state for better accounting:**

1. **Create new state:** `ENRICHMENT_UNCERTAIN`
2. **Logic:**

   ```
   ES/pp_log timeout + no concrete error
     → Try Exensio once
     → If found: DONE
     → If not found or error: ENRICHMENT_UNCERTAIN (not DONE)
   ```

3. **Operator workflow:**
   - Dashboard shows separate "Uncertain" card
   - Operator can manually trigger re-check
   - After N days, auto-mark as DONE (manual_verify)

4. **Accounting stays honest:**
   ```
   Staged: 100
   Queued: 80
   Enriching: 10
   Uncertain: 5  ← New, honest accounting
   Exensio: 0
   Done: 800
   Failed: 5
   ```

---

### Long Term (Strategic)

1. **Improve ES/pp_log reliability** so timeouts are rare
2. **Add background retry worker** for uncertain records
3. **Integrate with manual verification queue** (Jira, ticket system)
4. **Implement exponential backoff** instead of immediate fallback

---

## Verdict

### Current Approach: ⚠️ **NOT IDEAL** but **FUNCTIONAL**

**Problems:**

- ❌ Conflates timeout with success
- ❌ Loses track of uncertain records in accounting
- ❌ "DONE" state is ambiguous
- ❌ Operators must manually inspect error_message

**Why it works anyway:**

- ✅ Better than leaving records stuck
- ✅ Exensio fallback catches many cases
- ✅ `manual_verify` flag allows operator override
- ✅ Diagnostic messages are detailed

**Recommendation:**

- **Keep current logic** (timeout → Exensio → DONE or manual_verify)
- **But improve accountability:**
  - Add "Enrichment Certainty" metric to accounting
  - Filter dashboard to show uncertain vs certain DONE records
  - Provide operator UI to review uncertain records
  - Consider adding intermediate state in future

---

## Summary Table

| Aspect                  | Current                  | Issue                     | Recommendation                              |
| ----------------------- | ------------------------ | ------------------------- | ------------------------------------------- |
| **Timeout Handling**    | → Exensio → DONE         | Loses context             | Add intermediate state or enhance tracking  |
| **Accounting**          | DONE = ambiguous         | Conflates success/timeout | Separate "certain DONE" vs "uncertain DONE" |
| **Operator Visibility** | Error message convention | Must inspect text         | Add dashboard filter + UI                   |
| **False Success Risk**  | Low (Exensio validates)  | Exensio may be stale      | Add retry with backoff                      |
| **Overall Soundness**   | Good                     | Fair                      | Acceptable but improvable                   |

**Conclusion:** The strategy is pragmatic and reasonable, but the accountability is weak. **Enhance tracking without changing core logic.**
