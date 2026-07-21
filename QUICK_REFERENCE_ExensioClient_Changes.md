# Quick Reference: ExensioClient Changes for Lot Verification

## At-a-Glance Summary

| Aspect                 | Current State                            | Enhancement                      | Impact                  |
| ---------------------- | ---------------------------------------- | -------------------------------- | ----------------------- |
| PGC_KEY Resolution     | Wafer-presence fallback in ExensioClient | Add static utility method        | Code reuse, consistency |
| Verification API       | Only via lotWaferLookup()                | Add verifyLotExistence() wrapper | Better semantics        |
| Batch Filtering        | Manual in caller                         | Add filterByVerificationStatus() | Feature enablement      |
| Performance Monitoring | Minimal logging                          | Add threshold-based alerts       | Observability           |
| Batch Size Limits      | No chunking (ROWNUM limit risk)          | Add smart chunking               | Reliability             |

---

## Before & After Code Comparison

### Current Flow (Without Enhancement)

```java
// In discovery service
String lot = "LOT001";
String wafer = "WAFER001";

// Must manually determine pgcKey
int pgcKey = wafer != null ? 1 : 2;  // Heuristic, not always correct

ExensioLotWaferResult result = exensioClient.lotWaferLookup(
    lot, wafer, null, pgcKey, null, null, null, null
);

if (result instanceof ExensioLotWaferResult.Found) {
    // Handle found
} else if (result instanceof ExensioLotWaferResult.NotFound) {
    // Handle not found
}
```

### Enhanced Flow (With Enhancement)

```java
// In discovery service
String lot = "LOT001";
String wafer = "WAFER001";
String dataType = "FT";

// Direct, semantic method
ExensioLotWaferResult result = exensioClient.verifyLotExistence(lot, wafer, dataType);

// Same result handling, but clearer intent
if (result instanceof ExensioLotWaferResult.Found) {
    // Handle found
} else if (result instanceof ExensioLotWaferResult.NotFound) {
    // Handle not found
}
```

---

## Enhancement Checklist Quick View

### Enhancement 1: PGC_KEY Resolution

```
Location: ExensioClient.java (static method)
Status: ✅ Ready
Complexity: ⚫ Low
Lines Added: ~20
Breaking Changes: ❌ None
Tests Needed: 5-6 unit tests
```

### Enhancement 2: Verification Method

```
Location: ExensioClient.java (public methods)
Status: ✅ Ready
Complexity: ⚫ Low
Lines Added: ~30
Breaking Changes: ❌ None
Tests Needed: 4-5 unit tests
```

### Enhancement 3: Batch Filtering

```
Location: BatchLookupResult.java (static methods)
Status: ✅ Ready
Complexity: ⚫ Low
Lines Added: ~40
Breaking Changes: ❌ None
Tests Needed: 6-8 unit tests
```

### Enhancement 4: Performance Monitoring

```
Location: ExensioClient.java (private method + integration)
Status: ✅ Ready
Complexity: ⚫ Low
Lines Added: ~25
Breaking Changes: ❌ None
Tests Needed: 3-4 unit tests
Config Needed: ✅ application.properties thresholds
```

### Enhancement 5: Batch Chunking

```
Location: ExensioClient.java (private method + integration)
Status: ✅ Ready
Complexity: 🟡 Medium
Lines Added: ~50
Breaking Changes: ❌ None (transparent to caller)
Tests Needed: 8-10 unit tests
Config Needed: ✅ application.properties batch size
```

---

## Code Snippet Quick Reference

### Use Case 1: Verify Single Lot

```java
ExensioLotWaferResult result = exensioClient.verifyLotExistence(
    "LOT001", "WAFER001", "FT"
);

if (result instanceof ExensioLotWaferResult.Found) {
    log.info("Lot verified");
} else {
    log.warn("Lot not found");
}
```

### Use Case 2: Filter Batch Results (User chose "Not Found")

```java
Map<String, Boolean> verificationMap = verificationResponse.getLotExists();
BatchLookupResult batchResult = exensioClient.lotWaferLookupBatch(records);

// Keep only lots NOT found in Exensio
BatchLookupResult filtered = BatchLookupResult.filterByVerificationStatus(
    batchResult,
    verificationMap
);
```

### Use Case 3: Handle Large Batches

```java
// Transparent — batch chunking handles internally
List<StageRecord> largeList = /* 500+ records */;
BatchLookupResult result = exensioClient.lotWaferLookupBatch(largeList);
// Chunking happens automatically if > 200 records
```

---

## Testing Quick Checklist

### Unit Tests to Add

- [ ] `testResolvePgcKeyFromDataType_Probe()` - returns 1
- [ ] `testResolvePgcKeyFromDataType_FT()` - returns 2
- [ ] `testResolvePgcKeyFromDataType_PCM()` - returns 5
- [ ] `testResolvePgcKeyFromDataType_Defect()` - returns 14
- [ ] `testResolvePgcKeyFromDataType_Map()` - returns 4
- [ ] `testResolvePgcKeyFromDataType_Null()` - returns 2 (default)
- [ ] `testVerifyLotExistence_Found()`
- [ ] `testVerifyLotExistence_NotFound()`
- [ ] `testVerifyLotExistence_Error()`
- [ ] `testFilterByVerificationStatus_ExcludesFound()`
- [ ] `testFilterByVerificationStatus_IncludesNotFound()`
- [ ] `testBatchChunking_Splits500Into5Chunks()`
- [ ] `testPerformanceMonitoring_LogsWarning_OnThresholdExceeded()`
- [ ] `testPerformanceMonitoring_NoLog_OnNormalSpeed()`

---

## Property Configuration Needed

### In application.properties or application-\*.yml

```properties
# Batch lookup performance thresholds (milliseconds)
exensio.batch-lookup-warning-threshold-ms=5000
exensio.batch-lookup-error-threshold-ms=15000

# Batch chunking configuration
exensio.batch-chunk-size=100
exensio.batch-chunk-enable-at-size=200

# PGC Key fallback
exensio.pgc-key-default=2
```

---

## Performance Expectations

### Current Performance (Before Enhancement)

| Operation                  | Time     | Records |
| -------------------------- | -------- | ------- |
| Single lot verify          | 200ms    | 1       |
| Batch lookup (no chunking) | 2000ms   | 100     |
| Batch lookup               | ❌ Error | 500+    |

### Expected Performance (After Enhancement)

| Operation              | Time   | Records |
| ---------------------- | ------ | ------- |
| Single lot verify      | 200ms  | 1       |
| Batch lookup           | 1500ms | 100     |
| Batch lookup (chunked) | 3000ms | 500     |
| Batch lookup (chunked) | 6000ms | 1000    |

---

## Deployment Checklist

**Pre-Deployment:**

- [ ] All enhancements peer-reviewed
- [ ] Unit tests pass (>80% coverage)
- [ ] Integration tests with discovery flow pass
- [ ] No breaking changes detected
- [ ] Properties configured in all environments
- [ ] Performance benchmarks meet SLAs

**Deployment:**

- [ ] Deploy to staging first
- [ ] Run smoke tests
- [ ] Monitor performance metrics
- [ ] Deploy to production during low-traffic window

**Post-Deployment:**

- [ ] Monitor logs for errors
- [ ] Check performance metrics
- [ ] Verify batch operations work with 1000+ lots
- [ ] Confirm no regressions in existing features

---

## Troubleshooting Quick Guide

### Issue: ROWNUM Limit Hit on Large Batches

**Solution:** Batch chunking enhancement (Enhancement 5)
**Diagnostic:** Log says "HTTP 5xx" or batch returns partial results
**Fix:** Enable batch chunking in properties

### Issue: PGC_KEY Mismatch

**Solution:** Use resolvePgcKeyFromDataType() instead of heuristics
**Diagnostic:** Wrong records returned, PPID mismatch
**Fix:** Call new static utility with dataType

### Issue: Verification Takes > 10 seconds

**Solution:** Performance monitoring enables alerts
**Diagnostic:** Performance monitoring logs show threshold exceeded
**Fix:** Check Exensio availability, database load

### Issue: Batch Filtering Returns Wrong Records

**Solution:** Use filterByVerificationStatus() method
**Diagnostic:** Wrong lots included/excluded
**Fix:** Verify verification map keys match lot IDs exactly

---

## Migration Guide

### If You're Currently Using ExensioClient

**No changes required** — all enhancements are backward compatible.

**But consider updating to:**

```java
// Old way (still works)
int pgcKey = wafer != null ? 1 : 2;

// New way (recommended)
int pgcKey = ExensioClient.resolvePgcKeyFromDataType(dataType);
```

```java
// Old way (still works)
ExensioLotWaferResult result = exensioClient.lotWaferLookup(
    lot, wafer, null, pgcKey, null, null, null, null
);

// New way (recommended for verification)
ExensioLotWaferResult result = exensioClient.verifyLotExistence(lot, wafer, dataType);
```

---

## Document Reference Map

| Need                          | Document                                           |
| ----------------------------- | -------------------------------------------------- |
| Full business flow analysis   | `ExensioClient_Analysis.md`                        |
| Specific code implementations | `ExensioClient_Enhancement_Guide.md`               |
| Overall integration status    | `.kiro/specs/lot-existence-verification/design.md` |
| Implementation checklist      | This file (QUICK_REFERENCE)                        |

---

## Key Metrics to Monitor

After deployment, track these metrics:

```
Metric: Lot verification success rate
Target: > 99.5%
Alert: < 99%

Metric: Batch lookup average latency
Target: < 2s for 100 lots
Alert: > 5s

Metric: Batch chunking error rate
Target: 0%
Alert: > 0.1%

Metric: Performance threshold breaches
Target: < 1 per day
Alert: > 5 per day
```

---

## FAQ - Quick Answers

**Q: Will this break my existing code?**
A: No — all enhancements are additive. Existing methods unchanged.

**Q: How many lines of code to add?**
A: ~165 lines of new code across all enhancements (easily reviewable).

**Q: Do I need to deploy all enhancements at once?**
A: No — implement in phases. Phase 1-2 are minimum for feature, Phase 3-4 are optional robustness improvements.

**Q: What if Exensio is down during verification?**
A: ExensioClient returns Error, caller can skip verification and proceed (graceful degradation).

**Q: Will this work with 1000+ lots?**
A: Yes — batch chunking enhancement handles this transparently.

**Q: How do I test locally?**
A: Add unit tests (see Testing Checklist). Mock Exensio responses using existing test utilities.

---

## Contact & Support

For questions about these enhancements:

1. Review detailed analysis in `ExensioClient_Analysis.md`
2. Check code examples in `ExensioClient_Enhancement_Guide.md`
3. Refer to implementation checklist below

### Implementation Checklist

```
Phase 1: Add PGC_KEY Utility
- [ ] Add method to ExensioClient
- [ ] Write 6 unit tests
- [ ] Peer review
- [ ] Merge to main

Phase 2: Add Verification Methods
- [ ] Add methods to ExensioClient
- [ ] Write 5 unit tests
- [ ] Integrate with discovery flow
- [ ] Peer review
- [ ] Merge to main

Phase 3: Add Batch Filtering
- [ ] Add methods to BatchLookupResult
- [ ] Write 8 unit tests
- [ ] Integration test with discovery
- [ ] Peer review
- [ ] Merge to main

Phase 4: Add Monitoring & Chunking
- [ ] Add monitoring to ExensioClient
- [ ] Add chunking to ExensioClient
- [ ] Add 12 unit tests
- [ ] Performance testing
- [ ] Peer review
- [ ] Merge to main
```

---

**Status:** ✅ READY FOR IMPLEMENTATION  
**Estimated Effort:** 12-16 hours development + 8-12 hours testing  
**Risk Level:** LOW (all additive, no breaking changes)  
**Target Release:** Next sprint + 1
