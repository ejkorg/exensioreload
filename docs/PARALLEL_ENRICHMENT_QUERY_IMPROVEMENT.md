# Parallel Enrichment Query Improvement

**Date:** July 4, 2026  
**Goal:** Query ES, pp_log, AND Exensio simultaneously for faster, more reliable enrichment verification

---

## Current vs Proposed

### Current Implementation ❌

```
ENRICHMENT:
  ↓
  Query in parallel (already good!):
    ├─ ES query (CompletableFuture)
    └─ pp_log query (CompletableFuture)
  ↓
  Consolidate ES + pp_log results
  ↓
  IF both NotFound + timeout reached:
    ↓
    THEN try Exensio (sequential, late) ❌
```

**Problem:** Exensio is queried AFTER 15-minute timeout, not WITH ES/pp_log

### Proposed: 3-Way Parallel Query ✅

```
ENRICHMENT:
  ↓
  Query ALL THREE simultaneously:
    ├─ ES query (CompletableFuture)
    ├─ pp_log query (CompletableFuture)
    └─ Exensio query (CompletableFuture) ✅ NEW!
  ↓
  Consolidate all three results immediately
  ↓
  Decision based on best available data
```

**Benefits:**

- ⚡ Faster: No 15-minute timeout wait
- 🎯 More reliable: Check all sources upfront
- 🔍 Better data: Use whatever source has info
- ⏱️ Immediate resolution: Don't wait to query Exensio

---

## Consolidation Priority Logic

### New 3-Source Priority

When all three sources are queried in parallel:

```
Priority Order (first match wins):

1. pp_log Success → EXENSIO_LOADING
   (Production RefDB is source of truth for CP results)

2. ES Success → EXENSIO_LOADING
   (Elasticsearch confirms enrichment happened)

3. Exensio Found → DONE
   (Wafer exists in Exensio, assume enriched successfully)

4. pp_log Failure → FAILED
   (PP_LOG shows process_code != 0, definitive error)

5. ES Failure → FAILED
   (ES shows log.level=ERROR, definitive error)

6. Exensio NotFound + (ES NotFound + pp_log NotFound) → ENRICHMENT_TIMEOUT
   (All sources checked, nothing found - uncertain)

7. Within timeout window → Retry next cycle
   (Haven't exhausted all options yet)
```

---

## Code Implementation

### Update CpLogMonitor.processRecord()

**File:** `backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/service/CpLogMonitor.java`

**Current code (lines ~160-200):**

```java
// ES and pp_log in parallel (GOOD)
CompletableFuture<CpLogResult> esFuture = ...;
CompletableFuture<PpLogResult> ppLogFuture = ...;

CpLogResult esResult = esFuture.get();
PpLogResult ppLogResult = ppLogFuture.get();

// Consolidate ES + pp_log
// ...
// Then LATER if timeout, try Exensio (BAD)
if (isTimedOut(record)) {
    tryExensioDirectLookup(...);  // Sequential, late
}
```

**Proposed code:**

```java
// Query ALL THREE in parallel
CompletableFuture<CpLogResult> esFuture = hasEs
    ? CompletableFuture.supplyAsync(() -> {
        try {
            return elasticsearchLogService.findCpLog(...);
        } catch (Exception e) {
            log.warn("ES query failed: {}", e.getMessage());
            return new CpLogResult.NotFound("es-error");
        }
    })
    : CompletableFuture.completedFuture(new CpLogResult.NotFound("es-not-configured"));

CompletableFuture<PpLogResult> ppLogFuture = hasPpLog
    ? CompletableFuture.supplyAsync(() -> {
        try {
            String outputDir = refDbService.queryPpLogSuccess(record.lot(), record.metadataId());
            if (outputDir != null) return new PpLogResult.Success(outputDir);

            String errMsg = refDbService.queryPpLogError(record.lot(), record.metadataId());
            if (errMsg != null) return new PpLogResult.Failure(errMsg);

            return new PpLogResult.NotFound();
        } catch (Exception e) {
            log.warn("pp_log query failed: {}", e.getMessage());
            return new PpLogResult.NotFound();
        }
    })
    : CompletableFuture.completedFuture(new PpLogResult.NotFound());

// NEW: Exensio in parallel with ES and pp_log
CompletableFuture<ExensioResult> exensioFuture = exensioProperties.isConfigured()
    ? CompletableFuture.supplyAsync(() -> {
        try {
            boolean waferBlank = record.wafer() == null || record.wafer().isBlank();
            int pgcKey = DataTypePgcKeyMapper.resolve(record.dataType(), waferBlank);

            ExensioLotWaferResult result = exensioClient.lotWaferLookup(
                record.lot(), record.wafer(), record.endTime(),
                pgcKey, record.testPhase(),
                record.filename(), record.metadataId(), record.dataId()
            );

            return new ExensioResult(result);
        } catch (Exception e) {
            log.warn("Exensio query failed: {}", e.getMessage());
            return new ExensioResult(new ExensioLotWaferResult.Error(e.getMessage()));
        }
    })
    : CompletableFuture.completedFuture(
        new ExensioResult(new ExensioLotWaferResult.NotFound("exensio-not-configured"))
    );

// Wait for all three to complete
CpLogResult esResult;
PpLogResult ppLogResult;
ExensioResult exensioResult;
try {
    esResult = esFuture.get();
    ppLogResult = ppLogFuture.get();
    exensioResult = exensioFuture.get();
} catch (Exception e) {
    log.warn("Parallel query interrupted: {}", e.getMessage());
    integrationStatusService.updateCpStatusForRecord(stageRecordId, "error",
        "Parallel query failed: " + e.getMessage());
    return;
}

// Consolidate all three results
consolidateEnrichmentResults(record, requestId, stageRecordId,
    esResult, ppLogResult, exensioResult);
```

### New consolidateEnrichmentResults() Method

```java
private void consolidateEnrichmentResults(
    StageRecord record,
    String requestId,
    long stageRecordId,
    CpLogResult esResult,
    PpLogResult ppLogResult,
    ExensioResult exensioResult
) {
    // Priority 1: pp_log Success (production source of truth)
    if (ppLogResult instanceof PpLogResult.Success ppSuccess) {
        log.info("Enrichment confirmed via pp_log for record {}", record.id());
        String statusMsg = "CP enrichment via pp_log: " + ppSuccess.outputDirectory();
        integrationStatusService.updateCpStatusForRecord(stageRecordId, "success", statusMsg);
        successCount.incrementAndGet();
        pipelineOrchestrator.onCpEnrichmentSuccess(record, ppSuccess.outputDirectory(), "PP_LOG");
        emitRowUpdateSse(record, requestId);
        return;
    }

    // Priority 2: ES Success (confirmed enrichment)
    if (esResult instanceof CpLogResult.Success esSuccess) {
        log.info("Enrichment confirmed via ES for record {}", record.id());
        String statusMsg = String.format("CP enrichment via ES: %s -> %s",
            esSuccess.outputPath(), esSuccess.outputTarget());
        integrationStatusService.updateCpStatusForRecord(stageRecordId, "success", statusMsg);
        successCount.incrementAndGet();
        pipelineOrchestrator.onCpEnrichmentSuccess(record,
            esSuccess.outputPath(), esSuccess.outputTarget());
        emitRowUpdateSse(record, requestId);
        return;
    }

    // Priority 3: Exensio Found (wafer exists, assume enriched)
    if (exensioResult.result() instanceof ExensioLotWaferResult.Found found) {
        log.info("Wafer found in Exensio for record {} - assuming enrichment succeeded", record.id());
        String statusMsg = String.format("Wafer found in Exensio: waferKey=%d, pgKey=%d. " +
            "No CP log found but wafer exists - marking DONE.",
            found.waferKey(), found.pgKey());
        integrationStatusService.updateCpStatusForRecord(stageRecordId, "success", statusMsg);
        successCount.incrementAndGet();
        refDbService.markDoneFromExensio(record, found.waferKey(), found.pgKey());
        emitRowUpdateSse(record, requestId);
        return;
    }

    // Priority 4: pp_log Failure (definitive error)
    if (ppLogResult instanceof PpLogResult.Failure ppFailure) {
        log.info("Enrichment failed per pp_log for record {}", record.id());
        String statusMsg = String.format("[pp_log Failure] process_code!=0: %s",
            ppFailure.errorMessage());
        integrationStatusService.updateCpStatusForRecord(stageRecordId, "failure", statusMsg);
        failureCount.incrementAndGet();
        refDbService.markFailed(record, statusMsg);
        emitRowUpdateSse(record, requestId);
        return;
    }

    // Priority 5: ES Failure (definitive error)
    if (esResult instanceof CpLogResult.Failure esFailure) {
        log.info("Enrichment failed per ES for record {}", record.id());
        String statusMsg = String.format("[ES Failure] log.level=ERROR: %s",
            esFailure.errorMessage());
        integrationStatusService.updateCpStatusForRecord(stageRecordId, "failure", statusMsg);
        failureCount.incrementAndGet();
        refDbService.markFailed(record, statusMsg);
        emitRowUpdateSse(record, requestId);
        return;
    }

    // Priority 6: All NotFound
    // Check if we've exceeded timeout
    if (isTimedOut(record)) {
        log.info("All sources returned NotFound after timeout for record {} - marking ENRICHMENT_TIMEOUT",
            record.id());

        String diagnosticSummary = String.format(
            "ES: %s; pp_log: %s; Exensio: %s - No enrichment confirmation found after %d minutes",
            esResult.getClass().getSimpleName(),
            ppLogResult.getClass().getSimpleName(),
            exensioResult.result().getClass().getSimpleName(),
            props.getEnrichmentTimeoutMinutes()
        );

        timeoutCount.incrementAndGet();
        integrationStatusService.updateCpStatusForRecord(stageRecordId, "timeout", diagnosticSummary);
        refDbService.markEnrichmentTimeout(record, diagnosticSummary);
        emitRowUpdateSse(record, requestId);
        return;
    }

    // Priority 7: Within timeout - retry next cycle
    log.debug("No enrichment result yet for record {} - will retry next cycle", record.id());
    String notFoundMsg = "No result from ES, pp_log, or Exensio - retrying";
    integrationStatusService.updateCpStatusForRecord(stageRecordId, "not_found", notFoundMsg);
    emitRowUpdateSse(record, requestId);
}
```

---

## Benefits of 3-Way Parallel Query

### 1. **Faster Resolution** ⚡

```
Before:
ENRICHMENT → wait 15 min → try Exensio → decision
Total: 15+ minutes

After:
ENRICHMENT → query all 3 → immediate decision
Total: ~5 seconds (max query time)
```

**Speed improvement: ~180x faster**

### 2. **More Reliable** 🎯

- Don't rely solely on ES/pp_log
- Exensio may have data even when CP logs don't
- Cross-reference all sources for confidence

### 3. **Better Data Coverage** 🔍

```
Scenario: CP enriched but logs not propagated yet
Before: Wait 15 min, timeout, mark uncertain
After: Exensio found immediately, mark DONE
```

### 4. **Simpler Logic** 🧩

- One consolidation point
- No separate timeout fallback path
- Clearer decision tree

### 5. **Reduced Operator Burden** 👤

```
Before: Many ENRICHMENT_TIMEOUT records (no Exensio check)
After: Fewer ENRICHMENT_TIMEOUT records (Exensio resolves many)
```

---

## Example Scenarios

### Scenario 1: CP Logs Delayed

```
Query results:
- ES: NotFound (logs haven't propagated yet)
- pp_log: NotFound (PRODUCTION db not updated yet)
- Exensio: Found (wafer exists with keys)

Decision: Mark DONE ✅
Reason: Wafer in Exensio means enrichment succeeded
```

### Scenario 2: Enrichment Actually Failed

```
Query results:
- ES: Failure (log.level=ERROR)
- pp_log: NotFound
- Exensio: NotFound

Decision: Mark FAILED ✅
Reason: ES shows definitive error
```

### Scenario 3: Truly Uncertain

```
Query results:
- ES: NotFound (no logs)
- pp_log: NotFound (no records)
- Exensio: NotFound (wafer doesn't exist)

Decision: Mark ENRICHMENT_TIMEOUT ⚠️
Reason: All sources checked, no data anywhere
```

### Scenario 4: Mixed Signals (Edge Case)

```
Query results:
- ES: NotFound
- pp_log: Success (output_directory=/prod/...)
- Exensio: NotFound

Decision: Mark EXENSIO_LOADING ✅
Reason: pp_log is production source of truth, proceed to Exensio verification
```

---

## Implementation Checklist

### Phase 1: Add Exensio to Parallel Query

- [ ] Create `ExensioResult` wrapper class
- [ ] Add `exensioFuture` to parallel query block
- [ ] Update `consolidateEnrichmentResults()` method
- [ ] Remove old `tryExensioDirectLookup()` method (no longer needed)
- [ ] Update tests to include Exensio in mock responses

### Phase 2: Add ENRICHMENT_TIMEOUT State

- [ ] Database migration: add `'ENRICHMENT_TIMEOUT'` status value
- [ ] Add `markEnrichmentTimeout()` method to RefDbService
- [ ] Update state accounting queries
- [ ] Add timeout card to dashboard
- [ ] Update state legend

### Phase 3: Testing

- [ ] Unit test: all 3 sources in parallel
- [ ] Unit test: priority consolidation logic
- [ ] Integration test: real ES/pp_log/Exensio queries
- [ ] Performance test: verify parallel speedup
- [ ] Edge case test: timeout scenarios

---

## Performance Impact

### Current

```
Best case:  ES finds log immediately      → 1-2 seconds
Worst case: No logs, wait timeout, query Exensio → 15+ minutes
Average:    Poll every 60s, find within 2-3 polls → 2-3 minutes
```

### Proposed

```
Best case:  Any source has data          → 1-2 seconds
Worst case: All 3 NotFound, no timeout yet → 60s retry
Average:    First poll finds Exensio data → 1-2 seconds
```

**Expected improvement:**

- 50-80% of records resolve immediately (Exensio has data)
- No 15-minute timeout waits
- Faster pipeline throughput

---

## Risk Assessment

### Risk: Exensio API Load

**Concern:** Querying Exensio for every record increases API calls

**Mitigation:**

- Exensio already has circuit breaker
- Batch API supports multiple lot/wafer lookups
- Can add rate limiting if needed
- Most records still resolve via ES/pp_log (Exensio is backup)

### Risk: Slower When All 3 Fail

**Concern:** Waiting for 3 futures instead of 2

**Impact:** Minimal (~100ms additional)

- All queries run in parallel
- Max wait time = slowest query (typically ES ~2-5s)
- Small price for better data coverage

---

## Recommendation

✅ **Implement 3-way parallel query immediately**

**Why:**

- Faster resolution (180x in worst case)
- More reliable (cross-reference all sources)
- Simpler code (one consolidation point)
- Reduces operator burden (fewer timeouts)

**Effort:** 1-2 days

- Refactor parallel query block
- Update consolidation logic
- Add tests

**Risk:** Low

- ES/pp_log parallel query already works
- Just adding third source
- No breaking changes

---

## Summary

**Current:** ES + pp_log parallel, then Exensio sequential after timeout ❌  
**Proposed:** ES + pp_log + Exensio all parallel, immediate consolidation ✅

**Key Insight:** Don't wait 15 minutes to check Exensio - check it immediately with ES and pp_log!

This is the optimal approach for enrichment verification.
