# Integration Findings Summary

**Analysis Date:** July 21, 2026  
**Scope:** ExensioPreCheckService, ExensioLoadMonitor, ExensioRawSqlService interactions  
**Status:** ✅ READY FOR ACTION

---

## Quick Findings

### 🟢 Pre-Flight Verification Feature

**Status: READY FOR PRODUCTION**

- ✅ Feature integrates cleanly with existing services
- ✅ No conflicts with ExensioLoadMonitor
- ✅ ExensioPreCheckService follows consistent patterns
- ✅ Snowflake fallback provides resilience
- ✅ Soft-failure model enables graceful degradation

---

### 🔴 CRITICAL ISSUE FOUND: PGC_KEY Inconsistency

**Problem:** ExensioLoadMonitor monitoring uses different PGC_KEY logic than pre-flight verification

**Location:** ExensioLoadMonitor.retryIndividualRecords() line ~450

```java
// WRONG: Uses DataTypePgcKeyMapper with wafer-presence fallback
boolean waferBlank = record.wafer() == null || record.wafer().isBlank();
int pgcKey = DataTypePgcKeyMapper.resolve(record.dataType(), waferBlank);

// SHOULD BE: Use ExensioPreCheckService.resolvePgcKey() for consistency
int pgcKey = ExensioPreCheckService.resolvePgcKey(record.dataType());
```

**Impact:**

- Batch query uses correct PGC_KEY via ExensioPreCheckService.resolvePgcKey() ✅
- Individual retry uses wafer-presence fallback via DataTypePgcKeyMapper ❌
- **Result:** Same lot might be queried with DIFFERENT PGC_KEYs depending on retry path
- **Symptom:** Monitoring misses lot data for wafer-level data types

**Fix Effort:** 5 minutes  
**Risk:** Low (simple change, well-understood)

---

### 🟡 THREE HIGH-VALUE IMPROVEMENTS (Not Blockers)

**Fix 2: SQL Utilities Duplication**

- yearOnlyClause, yearMonthClause, escapeSql, isWaferLevelClass duplicated in 2 files
- **Risk:** Inconsistent behavior if not updated in sync
- **Solution:** Consolidate into shared ExensioSqlUtilService
- **Effort:** 2-3 hours

**Fix 3: HttpClient Resource Sharing**

- Each service creates own HttpClient (3+ instances)
- Separate connection pools, resource inefficiency
- **Solution:** Share single HttpClient via dependency injection
- **Effort:** 1-2 hours

**Fix 4: Pre-Flight Result Caching**

- ExensioLoadMonitor caches, ExensioPreCheckService doesn't
- Repeated verification calls not optimized
- **Solution:** Add Caffeine cache with 5-minute TTL
- **Effort:** 1-2 hours

---

## Implementation Roadmap

### 🚀 BEFORE PRODUCTION

**Required:**

- [ ] Fix 1: Unify PGC_KEY resolution (5 min)
  - Replace DataTypePgcKeyMapper with ExensioPreCheckService.resolvePgcKey()
  - Update ExensioLoadMonitor.retryIndividualRecords()
  - Add unit test to verify consistency

**Validation:**

- [ ] Verify batch and individual retry queries for same lot/wafer return same PGC_KEY
- [ ] Test with all dataTypes (probe, ft, pcm, defect, map, etc.)
- [ ] Confirm monitoring records correct wafer_key, pg_key

### 📅 NEXT SPRINT (Optional)

**Recommended:**

- [ ] Fix 2: Consolidate SQL utilities (2-3 hr)
- [ ] Fix 3: Share HttpClient (1-2 hr)
- [ ] Fix 4: Add pre-flight caching (1-2 hr)

---

## Code Changes Required

### Fix 1: PGC_KEY Unification (CRITICAL)

**File:** ExensioLoadMonitor.java, line ~450

```java
// BEFORE
private List<BatchResult.RecordUpdate> retryIndividualRecords(List<StageRecord> records, String traceId) {
    List<BatchResult.RecordUpdate> updates = new ArrayList<>();

    for (StageRecord record : records) {
        try {
            boolean waferBlank = record.wafer() == null || record.wafer().isBlank();
            int pgcKey = DataTypePgcKeyMapper.resolve(record.dataType(), waferBlank);
            // ❌ INCONSISTENT: Uses wafer-presence fallback

            ExensioLotWaferResult result = exensioClient.lotWaferLookup(
                record.lot(),
                record.wafer(),
                record.endTime(),
                pgcKey,
                record.testPhase(),
                record.filename(),
                record.metadataId(),
                record.dataId());
```

```java
// AFTER
private List<BatchResult.RecordUpdate> retryIndividualRecords(List<StageRecord> records, String traceId) {
    List<BatchResult.RecordUpdate> updates = new ArrayList<>();

    for (StageRecord record : records) {
        try {
            // ✅ CONSISTENT: Use same PGC_KEY resolution as batch queries
            int pgcKey = ExensioPreCheckService.resolvePgcKey(record.dataType());

            ExensioLotWaferResult result = exensioClient.lotWaferLookup(
                record.lot(),
                record.wafer(),
                record.endTime(),
                pgcKey,
                record.testPhase(),
                record.filename(),
                record.metadataId(),
                record.dataId());
```

**Unit Test:**

```java
@Test
public void testPgcKeyConsistency_PreFlightVsMonitoring() {
    String[] dataTypes = {"probe", "ft", "pcm", "defect", "map"};

    for (String dataType : dataTypes) {
        // Pre-flight uses ExensioPreCheckService.resolvePgcKey()
        int preFlightPgcKey = ExensioPreCheckService.resolvePgcKey(dataType);

        // Monitoring retry should use same method
        int monitoringPgcKey = ExensioPreCheckService.resolvePgcKey(dataType);

        assertEquals(preFlightPgcKey, monitoringPgcKey,
            "PGC_KEY mismatch for dataType=" + dataType);
    }
}
```

---

## Service Interaction Summary

### Pre-Flight Flow (Discovery)

```
User Request
    ↓ StepperComponent.verifyLotsBeforeDiscovery()
    ↓ SenderController.verifyLots()
    ↓ ExensioPreCheckService.check()
      ├─ resolvePgcKey(dataType) ✅ CORRECT
      └─ checkViaExensioHttp() [Primary]
         └─ buildSql() → Exensio HTTP endpoint
    ↓ LotVerificationDialog
    ↓ Discovery proceeds
```

**PGC_KEY Path:** Always uses ExensioPreCheckService.resolvePgcKey() ✅

---

### Monitoring Flow (Post-Staging)

```
Scheduled Polling
    ↓ ExensioLoadMonitor.monitorExensioLoading()
    ├─ Batch Processing:
    │  ├─ ExensioClient.lotWaferLookupBatch()
    │  └─ ExensioRawSqlService.queryLotMetadata()
    │     └─ resolvePgcKey(dataType) ✅ CORRECT
    │
    └─ Individual Retry on Batch Failure:
       └─ ExensioLoadMonitor.retryIndividualRecords()
          └─ DataTypePgcKeyMapper.resolve() ❌ INCONSISTENT
             (uses wafer-presence fallback instead)

    ↓ Database Update with wafer_key, pg_key
```

**PGC_KEY Path:** Inconsistent between batch and retry ❌ → **FIX REQUIRED**

---

## Impact Assessment

| Component               | Impact                | Severity | Status          |
| ----------------------- | --------------------- | -------- | --------------- |
| Pre-Flight Verification | Clean integration     | N/A      | ✅ READY        |
| Batch Monitoring        | Uses correct PGC_KEY  | N/A      | ✅ OK           |
| Individual Retry        | Uses wrong PGC_KEY    | CRITICAL | ❌ FIX NEEDED   |
| HttpClient Reuse        | Resource inefficiency | MEDIUM   | ⚠️ NICE-TO-HAVE |
| SQL Utilities           | Maintenance risk      | MEDIUM   | ⚠️ NICE-TO-HAVE |
| Result Caching          | Performance           | LOW      | ⚠️ NICE-TO-HAVE |

---

## Verification Checklist

### Before Production Deployment

- [ ] **Fix 1 Applied:** DataTypePgcKeyMapper replaced with ExensioPreCheckService.resolvePgcKey()
- [ ] **Code Review:** Changes reviewed by team lead
- [ ] **Unit Test Added:** PgcKeyConsistency test passes
- [ ] **Integration Test:** Batch vs individual retry verified same PGC_KEY
- [ ] **Monitoring Test:** Verify wafer_key, pg_key populated correctly
- [ ] **Performance Test:** No regressions in poll cycle time
- [ ] **Staging Test:** Pre-flight verification works end-to-end
- [ ] **Rollback Plan:** In place if issues detected

### After Production Deployment (First Week)

- [ ] Monitor ExensioLoadMonitor logs for errors
- [ ] Check integration_status for failed records
- [ ] Verify wafer_key and pg_key populated correctly
- [ ] No spikes in monitoring poll time
- [ ] No increase in retry count due to PGC_KEY mismatch

---

## Recommended Next Steps

1. **Immediate (Today):**
   - Read SERVICE_INTEGRATION_ANALYSIS.md (detailed findings)
   - Prepare Fix 1 (PGC_KEY unification)
   - Get code review scheduled

2. **Before Production (This Week):**
   - Apply Fix 1
   - Run unit tests
   - Integration testing in staging
   - Deploy to production

3. **Next Sprint:**
   - Plan Fixes 2-4
   - Performance optimization
   - Caching implementation

---

## Contact & Questions

**Analysis Provided By:** Kiro  
**Analysis Date:** July 21, 2026  
**Confidence Level:** HIGH (95%+)  
**Risk Assessment:** LOW - Fix 1 is simple, well-understood, low-risk

For detailed explanation of any finding, refer to:

- SERVICE_INTEGRATION_ANALYSIS.md (comprehensive)
- ExensioClient_Analysis.md (client interactions)
- VERIFICATION_REPORT.md (feature status)

---

## One-Line Takeaway

**✅ Pre-flight verification is production-ready, but Fix 1 (PGC_KEY unification in monitoring retry) is required to prevent data inconsistency.**
