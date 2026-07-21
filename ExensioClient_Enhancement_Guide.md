# ExensioClient Enhancement Guide for Lot Verification Integration

**Purpose:** Provide specific code enhancements to ExensioClient to support the lot existence verification feature while maintaining backward compatibility.

---

## Enhancement 1: Add PGC_KEY Resolution Utility

### Location

Add to `ExensioClient.java` as a static method alongside other utilities

### Rationale

- Eliminates duplication between ExensioClient (wafer-presence fallback) and ExensioPreCheckService (dataType mapping)
- Ensures consistent PGC_KEY resolution across both services
- Makes code more maintainable and testable

### Code

```java
/**
 * Resolves PGC_KEY from dataType string, consistent with ExensioPreCheckService.
 *
 * <p>Maps:
 * <ul>
 *   <li>probe → 1 (wafer-level)</li>
 *   <li>ft, final test → 2 (lot-level)</li>
 *   <li>pcm → 5 (wafer-level)</li>
 *   <li>defect → 14 (wafer-level)</li>
 *   <li>map, binmap, wxml, upm → 4 (wafer-level)</li>
 * </ul>
 * Defaults to 2 (FT) if dataType is null/blank or unknown.
 *
 * <p>Feature: lot-existence-verification, Property: PGC_KEY Resolution Consistency</p>
 *
 * @param dataType the data type string (case-insensitive)
 * @return PGC_KEY integer value
 */
public static int resolvePgcKeyFromDataType(String dataType) {
    if (dataType == null || dataType.isBlank()) {
        return 2; // Default to FT
    }

    String normalized = dataType.trim().toLowerCase();
    int pgcKey = switch (normalized) {
        case "probe" -> 1;
        case "ft", "final test" -> 2;
        case "pcm" -> 5;
        case "defect" -> 14;
        case "map", "binmap", "wxml", "upm" -> 4;
        default -> {
            log.debug("[ExensioClient] Unknown dataType '{}', defaulting to PGC_KEY=2 (FT)", dataType);
            yield 2;
        }
    };

    log.debug("[ExensioClient] Resolved dataType '{}' to PGC_KEY={}", dataType, pgcKey);
    return pgcKey;
}
```

### Usage Example

```java
// In ExensioClient when dataType is available:
String dataType = record.dataType();
int pgcKey = resolvePgcKeyFromDataType(dataType);
// Now use pgcKey directly instead of wafer-presence fallback

// In test code:
assertEquals(1, ExensioClient.resolvePgcKeyFromDataType("probe"));
assertEquals(2, ExensioClient.resolvePgcKeyFromDataType("ft"));
assertEquals(2, ExensioClient.resolvePgcKeyFromDataType(null));
```

---

## Enhancement 2: Add Verification Context Method

### Location

Add to `ExensioClient.java` as a public method (convenience wrapper)

### Rationale

- Provides convenient method for pre-flight lot verification
- Ensures consistent logging and tracing for verification calls
- Isolates verification behavior from regular lookup behavior

### Code

```java
/**
 * Executes a pre-flight lot existence verification.
 *
 * <p>This is a convenience wrapper around {@link #lotWaferLookup(String, String, Instant, Integer, String)}
 * optimized for the lot existence verification feature.
 *
 * <p>Requirements: lot-existence-verification 1.1, 2.1</p>
 *
 * @param lot the lot ID to verify
 * @param wafer optional wafer ID for wafer-level verification
 * @param dataType the data type (probe, ft, pcm, defect, map, etc.)
 * @return Found if lot exists in Exensio, NotFound if it does not, Error on failure
 */
public ExensioLotWaferResult verifyLotExistence(String lot, String wafer, String dataType) {
    return verifyLotExistence(lot, wafer, dataType, UUID.randomUUID().toString());
}

/**
 * Executes a pre-flight lot existence verification with explicit trace ID.
 *
 * <p>Used internally and by testing to correlate logs.
 *
 * @param lot the lot ID to verify
 * @param wafer optional wafer ID for wafer-level verification
 * @param dataType the data type (probe, ft, pcm, defect, map, etc.)
 * @param traceId correlation ID for logging
 * @return Found if lot exists in Exensio, NotFound if it does not, Error on failure
 */
public ExensioLotWaferResult verifyLotExistence(String lot, String wafer, String dataType, String traceId) {
    int pgcKey = resolvePgcKeyFromDataType(dataType);

    log.info("[PRECHECK] Starting lot existence verification: lot={}, wafer={}, dataType={}, pgcKey={}, traceId={}",
            lot, wafer, dataType, pgcKey, traceId);

    try {
        ExensioLotWaferResult result = lotWaferLookup(lot, wafer, null, pgcKey, null, null, null, null);

        if (result instanceof ExensioLotWaferResult.Found) {
            log.info("[PRECHECK] Lot existence verification FOUND: lot={}, wafer={}, traceId={}", lot, wafer, traceId);
        } else if (result instanceof ExensioLotWaferResult.NotFound) {
            log.info("[PRECHECK] Lot existence verification NOT FOUND: lot={}, wafer={}, traceId={}", lot, wafer, traceId);
        } else if (result instanceof ExensioLotWaferResult.Error err) {
            log.warn("[PRECHECK] Lot existence verification ERROR: lot={}, wafer={}, error={}, traceId={}",
                    lot, wafer, err.message(), traceId);
        }

        return result;
    } catch (Exception e) {
        log.error("[PRECHECK] Lot existence verification exception: lot={}, wafer={}, traceId={}, error={}",
                lot, wafer, traceId, e.getMessage(), e);
        return new ExensioLotWaferResult.Error("Verification failed: " + e.getMessage());
    }
}
```

### Usage Example

```java
// In discovery or staging flow:
String lot = "LOT001";
String wafer = "WAFER001";
String dataType = stageRecord.getDataType();

ExensioLotWaferResult verifyResult = exensioClient.verifyLotExistence(lot, wafer, dataType);

if (verifyResult instanceof ExensioLotWaferResult.Found found) {
    log.info("Lot {} exists - proceeding with discovery", lot);
    // Proceed with discovery
} else if (verifyResult instanceof ExensioLotWaferResult.NotFound) {
    log.info("Lot {} does not exist - skipping discovery", lot);
    // Skip discovery for this lot
} else if (verifyResult instanceof ExensioLotWaferResult.Error err) {
    log.error("Lot {} verification failed: {}", lot, err.message());
    // Handle error (retry, skip, or alert)
}
```

---

## Enhancement 3: Add Batch Result Filtering

### Location

Add to `BatchLookupResult.java` as a static utility method

### Rationale

- Provides unified interface for filtering batch results after pre-flight verification
- Supports the discovery flow where user selects "Continue with Lots Not in Exensio"
- Maintains immutability of original result

### Code

```java
/**
 * Filters batch results to include only lots that passed verification.
 *
 * <p>Used by discovery preview when user has selected "Continue with Lots Not in Exensio"
 * to filter out lots that were marked as already existing in Exensio.
 *
 * <p>Requirements: lot-existence-verification 4.3, 10.4</p>
 *
 * @param batchResult the original batch result
 * @param verificationMap map of lot ID to verified status (true = found, false = not found)
 * @return filtered batch result containing only verified lots
 */
public static BatchLookupResult filterByVerificationStatus(
        BatchLookupResult batchResult,
        Map<String, Boolean> verificationMap) {

    if (verificationMap == null || verificationMap.isEmpty()) {
        log.debug("[BatchLookup] No verification map provided - returning original results");
        return batchResult;
    }

    if (!batchResult.isSuccess()) {
        log.debug("[BatchLookup] Batch result is not success - cannot filter");
        return batchResult;
    }

    List<LotResult> allLots = batchResult.getLots();
    List<LotResult> filteredLots = new ArrayList<>();

    for (LotResult lot : allLots) {
        String lotId = lot.getLotId();
        boolean verified = verificationMap.getOrDefault(lotId, true);

        if (!verified) {
            // Include this lot because it was NOT verified as existing
            filteredLots.add(lot);
            log.debug("[BatchLookup] Lot {} INCLUDED (not verified as existing)", lotId);
        } else {
            log.debug("[BatchLookup] Lot {} EXCLUDED (verified as existing)", lotId);
        }
    }

    log.info("[BatchLookup] Filtered batch results: {} original lots → {} after verification",
            allLots.size(), filteredLots.size());

    return new BatchLookupResult(filteredLots);
}

/**
 * Variant that inverts the filter logic (include only verified lots).
 *
 * <p>Used when user selects "Continue with All".
 *
 * @param batchResult the original batch result
 * @param verificationMap map of lot ID to verified status
 * @return filtered batch result containing only verified lots
 */
public static BatchLookupResult filterByVerificationStatusInclusive(
        BatchLookupResult batchResult,
        Map<String, Boolean> verificationMap) {

    if (verificationMap == null || verificationMap.isEmpty()) {
        return batchResult;
    }

    if (!batchResult.isSuccess()) {
        return batchResult;
    }

    List<LotResult> allLots = batchResult.getLots();
    List<LotResult> filteredLots = new ArrayList<>();

    for (LotResult lot : allLots) {
        String lotId = lot.getLotId();
        boolean verified = verificationMap.getOrDefault(lotId, false);

        if (verified) {
            // Include this lot because it WAS verified as existing
            filteredLots.add(lot);
        }
    }

    return new BatchLookupResult(filteredLots);
}
```

### Usage Example

```java
// User selects "Continue with Lots Not in Exensio"
Map<String, Boolean> verificationMap = verificationResponse.getLotExists();
BatchLookupResult batchResult = exensioClient.lotWaferLookupBatch(records);

// Filter to keep only non-verified (new) lots
BatchLookupResult filteredResult = BatchLookupResult.filterByVerificationStatus(
    batchResult,
    verificationMap
);

log.info("Staging {} new lots (filtered from {} total)",
    filteredResult.getLots().size(),
    batchResult.getLots().size());
```

---

## Enhancement 4: Add Performance Monitoring

### Location

Add to `ExensioClient.java` (integrate into existing methods)

### Rationale

- Enables detection of slow verification queries
- Provides visibility into Exensio API latency
- Alerts when verification threatens discovery SLA

### Code

```java
/**
 * Monitors execution time of batch lookup and logs warnings if threshold exceeded.
 *
 * <p>Feature: lot-existence-verification, Property: Verification Timeout Handling</p>
 */
private void monitorBatchLookupPerformance(String operationName, long elapsedMs, int recordCount) {
    long warningThreshold = props.getBatchLookupWarningThresholdMs(); // e.g., 5000ms
    long errorThreshold = props.getBatchLookupErrorThresholdMs();     // e.g., 15000ms

    if (elapsedMs >= errorThreshold) {
        log.error("[PERF] {} exceeded error threshold: {}ms >= {}ms for {} records",
                operationName, elapsedMs, errorThreshold, recordCount);
        // Could trigger alert/metric
    } else if (elapsedMs >= warningThreshold) {
        log.warn("[PERF] {} exceeded warning threshold: {}ms >= {}ms for {} records",
                operationName, elapsedMs, warningThreshold, recordCount);
        // Could trigger metric
    } else {
        log.debug("[PERF] {} completed in {}ms for {} records",
                operationName, elapsedMs, recordCount);
    }
}
```

### Integration Point

```java
// In doLotWaferLookupBatchEndpoint() method:
long startTime = System.currentTimeMillis();
// ... existing query execution code ...
long responseTimeMs = System.currentTimeMillis() - startTime;

log.debug("Batch API call completed: batchSize={}, uniqueLots={}, uniqueWafers={}, responseTimeMs={}, statusCode={}",
        batchSize, uniqueLots.size(), uniqueWafers.size(), responseTimeMs, response.statusCode());

// NEW: Add performance monitoring
monitorBatchLookupPerformance("Batch lot-wafer lookup", responseTimeMs, batchSize);
```

---

## Enhancement 5: Add Batch Chunking for Large Jobs

### Location

Add to `ExensioClient.java` as private helper method

### Rationale

- Prevents hitting Oracle ROWNUM limit when verifying 1000+ lots
- Ensures reliable pre-flight verification for large discovery jobs
- Maintains backward compatibility (transparent to caller)

### Code

```java
/**
 * Splits batch records into chunks to avoid hitting Oracle ROWNUM limit.
 *
 * <p>Each chunk processes up to BATCH_CHUNK_SIZE lots in a single query.
 * Results from all chunks are merged transparently.
 *
 * <p>Feature: lot-existence-verification, Property: Batch Size Limit</p>
 */
private static final int BATCH_CHUNK_SIZE = 100;

/**
 * Processes batch lookup in chunks if record count is high.
 *
 * @param records the batch of records to look up
 * @param token the authentication token
 * @return merged BatchLookupResult from all chunks
 */
private BatchLookupResult doLotWaferLookupBatchChunked(List<StageRecord> records, String token) {
    if (records.size() <= BATCH_CHUNK_SIZE) {
        // Single chunk - use existing method directly
        return doLotWaferLookupBatchEndpoint(records, token);
    }

    log.info("[BATCH] Splitting {} records into chunks of {} for batch lookup",
            records.size(), BATCH_CHUNK_SIZE);

    List<BatchLookupResult.LotResult> allLots = new ArrayList<>();
    Map<String, Long> lotKeys = new HashMap<>();
    int chunkNumber = 0;

    for (int i = 0; i < records.size(); i += BATCH_CHUNK_SIZE) {
        int endIdx = Math.min(i + BATCH_CHUNK_SIZE, records.size());
        List<StageRecord> chunk = records.subList(i, endIdx);
        chunkNumber++;

        log.debug("[BATCH] Processing chunk {}: records {}-{} of {}",
                chunkNumber, i + 1, endIdx, records.size());

        try {
            BatchLookupResult chunkResult = doLotWaferLookupBatchEndpoint(chunk, token);

            if (chunkResult.isSuccess()) {
                allLots.addAll(chunkResult.getLots());
                for (BatchLookupResult.LotResult lot : chunkResult.getLots()) {
                    lotKeys.putIfAbsent(lot.getLotId(), lot.getLotKey());
                }
                log.debug("[BATCH] Chunk {} succeeded: {} lots found", chunkNumber, chunkResult.getLots().size());
            } else {
                log.warn("[BATCH] Chunk {} failed: {}", chunkNumber, chunkResult.getErrorMessage());
                // Continue processing other chunks even if one fails
            }
        } catch (Exception e) {
            log.error("[BATCH] Chunk {} exception: {}", chunkNumber, e.getMessage(), e);
            // Continue with other chunks
        }
    }

    log.info("[BATCH] Batch lookup completed: {} chunks processed, {} total lots found",
            chunkNumber, allLots.size());

    return new BatchLookupResult(allLots);
}

// Update doLotWaferLookupBatch() to call chunked version when needed
public BatchLookupResult lotWaferLookupBatch(List<StageRecord> records, String traceId) {
    // ... existing token management and retry logic ...

    // Use chunked version transparently for large batches
    BatchLookupResult result = (records.size() > BATCH_CHUNK_SIZE * 2)
            ? doLotWaferLookupBatchChunked(records, token)
            : doLotWaferLookupBatchEndpoint(records, token);

    // ... existing retry/error handling ...
    return result;
}
```

---

## Summary of Changes

| Enhancement                 | Impact                  | Complexity | Priority |
| --------------------------- | ----------------------- | ---------- | -------- |
| PGC_KEY Resolution Utility  | Code reuse, consistency | Low        | High     |
| Verification Context Method | Convenience, logging    | Low        | High     |
| Batch Result Filtering      | Feature enablement      | Low        | High     |
| Performance Monitoring      | Observability           | Low        | Medium   |
| Batch Chunking              | Reliability             | Medium     | Medium   |

### Backward Compatibility

✅ All enhancements are **additive** - no existing methods changed
✅ New methods are public/private helpers - don't affect existing APIs
✅ Existing callers continue to work without modification

### Testing Strategy

Each enhancement should include unit tests:

- `resolvePgcKeyFromDataType()`: Test each dataType mapping
- `verifyLotExistence()`: Test success/failure/error cases
- `filterByVerificationStatus()`: Test filtering logic with various map contents
- `monitorBatchLookupPerformance()`: Test threshold behavior
- Batch chunking: Test with 1, 50, 100, 200, 500+ record counts

---

## Implementation Checklist

- [ ] Add `resolvePgcKeyFromDataType()` to ExensioClient
- [ ] Add `verifyLotExistence()` overloads to ExensioClient
- [ ] Add `filterByVerificationStatus()` to BatchLookupResult
- [ ] Add `monitorBatchLookupPerformance()` to ExensioClient
- [ ] Integrate performance monitoring into batch lookup
- [ ] Add batch chunking logic to ExensioClient
- [ ] Update ExensioClient javadoc
- [ ] Add unit tests for each enhancement
- [ ] Verify backward compatibility
- [ ] Document performance thresholds in properties
- [ ] Test with large batch sizes (500+, 1000+ lots)
