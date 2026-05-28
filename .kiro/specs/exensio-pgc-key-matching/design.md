# Design Document: Exensio pgc_key Matching

## Overview

The Exensio `lot-wafer-lookup` API uses a `pgc_key` field in the request body to scope the search to a specific program-group class. Currently the backend hardcodes this to `1` (wafer present) or `2` (wafer absent), which is incorrect for FT, Defect, and Map data types.

This design adds:
1. A utility that maps the stepper's **Data Type** selection to the correct `pgc_key`.
2. Persistence of `data_type` and `test_phase` on each `SENDER_STAGE` row.
3. Propagation of those values through the staging API request chain.
4. Use of the derived `pgc_key` in both single and batch Exensio API calls.
5. PPID suffix validation against the selected Test Phase during load confirmation.

## Architecture

The change touches four layers:

```
Angular Stepper (Step 1)
  └─ StagePayloadRequest / StageAllRequest  (already has dataType/testPhase in StageAllRequest)
       └─ SenderController.stagePayloads / stageAllMatching
            └─ RefDbService.stagePayloads  (INSERT with data_type, test_phase)
                 └─ SENDER_STAGE table  (new columns: data_type, test_phase)
                      └─ ExensioLoadMonitor (reads StageRecord.dataType / .testPhase)
                           └─ ExensioClient.lotWaferLookup / lotWaferLookupBatch
                                └─ Exensio API  (pgc_key derived from dataType)
```

## Components and Interfaces

### New: `DataTypePgcKeyMapper` (Java utility class)

A stateless utility class responsible for the canonical Data Type → `pgc_key` mapping.

```java
public final class DataTypePgcKeyMapper {

    // pgc_key constants
    public static final int PGC_KEY_PROBE    = 1;
    public static final int PGC_KEY_FT       = 2;
    public static final int PGC_KEY_WMAP     = 4;
    public static final int PGC_KEY_DEFECT   = 14;

    /**
     * Resolves the pgc_key for a given data type string.
     * Falls back to wafer-presence logic when dataType is unknown or null.
     *
     * @param dataType   the data type string from the stepper (may be null)
     * @param waferBlank true when the wafer ID is absent (used for fallback)
     * @return the pgc_key to send in the Exensio lot-wafer-lookup request
     */
    public static int resolve(String dataType, boolean waferBlank) { ... }
}
```

Mapping table (case-insensitive):

| Data Type input | pgc_key |
|---|---|
| `PROBE` | 1 |
| `FT`, `FINAL TEST`, `FINAL_TEST` | 2 |
| `DEFECT` | 14 |
| `MAP`, `BIN MAP`, `BINMAP`, `WMAP` | 4 |
| anything else / null | fallback: `1` if wafer present, `2` if wafer absent |

### Modified: `StageRecord` (Java record)

Add two new fields at the end of the record to preserve backward compatibility with existing `ResultSet` mapping code:

```java
public record StageRecord(
    // ... existing fields ...
    Long exensioWaferKey,
    Long exensioPgKey,
    String dataType,    // NEW
    String testPhase    // NEW
) {}
```

### Modified: `PayloadCandidate` (Java record)

Add `dataType` and `testPhase` so they flow from the controller into `RefDbService.stagePayloads`:

```java
public record PayloadCandidate(
    String metadataId, String dataId,
    String lot, String wafer,
    String filename, java.time.Instant endTime,
    String dataType,   // NEW
    String testPhase   // NEW
) { ... }
```

### Modified: `StagePayloadRequest` (Java DTO)

Add optional fields (already present in `StageAllRequest`, now added to the per-payload request):

```java
public record StagePayloadRequest(
    String site, String environment,
    Integer senderId, String senderName,
    List<Payload> payloads,
    boolean triggerDispatch, boolean forceDuplicates,
    String userEmail, String requestId,
    String dataType,   // NEW
    String testPhase   // NEW
) { ... }
```

### Modified: `ExensioClient`

Add an overload of `lotWaferLookup` that accepts an explicit `pgcKey`:

```java
// New overload — explicit pgcKey
public ExensioLotWaferResult lotWaferLookup(String lot, String wafer,
                                             Instant targetEndTime, Integer pgcKey)

// Existing overloads delegate to the new one with pgcKey=null (fallback)
```

In `doLotWaferLookupBatch`, replace the `allWafersBlank` heuristic with `DataTypePgcKeyMapper.resolve(record.dataType(), ...)` per record, then use the most common `pgc_key` across the batch.

### Modified: `ExensioLoadMonitor`

In `retryIndividualRecords`, pass the `pgcKey` derived from `record.dataType()` to `exensioClient.lotWaferLookup(...)`.

### New: PPID suffix validation (inline in `ExensioClient.parseResponse`)

After a `Found` result is identified, apply the PPID check before returning:

```java
private boolean ppidMatchesTestPhase(String ppid, String testPhase) {
    if (testPhase == null || testPhase.isBlank()) return true;
    if (ppid == null || ppid.isBlank()) return true;
    return ppid.toUpperCase().endsWith("_" + testPhase.trim().toUpperCase());
}
```

The `testPhase` must be threaded from `StageRecord` into `parseResponse` via the call chain.

### Modified: Angular `StepperComponent`

In `buildStageRequest()` (or equivalent staging method), include `selectedDataType()` and `selectedTestPhase()` in the `StagePayloadRequest` body. These signals already exist on the component.

## Data Models

### SENDER_STAGE table — new columns (Liquibase changeset `9.5`)

| Column | Type | Nullable | Notes |
|---|---|---|---|
| `data_type` | VARCHAR(100) | YES | e.g. `PROBE`, `FT`, `DEFECT`, `MAP` |
| `test_phase` | VARCHAR(50) | YES | e.g. `FT`, `QA`, `RG`, `CRSS` |

### `StageRecord` field additions

```
dataType  : String  (maps to data_type column)
testPhase : String  (maps to test_phase column)
```

## Correctness Properties

*A property is a characteristic or behavior that should hold true across all valid executions of a system — essentially, a formal statement about what the system should do. Properties serve as the bridge between human-readable specifications and machine-verifiable correctness guarantees.*

### Property 1: Data type mapping is case-insensitive and exhaustive

*For any* data type string that is a case variant of a known type (`PROBE`, `FT`, `FINAL TEST`, `DEFECT`, `MAP`, `BIN MAP`, `WMAP`), `DataTypePgcKeyMapper.resolve(dataType, waferBlank)` SHALL return the same `pgc_key` regardless of the case of the input.

**Validates: Requirements 1.1, 1.3**

### Property 2: Staging round-trip preserves dataType and testPhase

*For any* staging request that includes a `dataType` and `testPhase` (including null), reading the resulting `StageRecord` from the database SHALL return the same `dataType` and `testPhase` values that were submitted.

**Validates: Requirements 2.3, 2.5, 6.3**

### Property 3: pgcKey is reflected in the Exensio API request body

*For any* `pgcKey` value passed to `ExensioClient.lotWaferLookup`, the serialized JSON request body sent to the Exensio API SHALL contain `"pgc_key": <pgcKey>`.

**Validates: Requirements 4.1, 4.2**

### Property 4: Batch lookup derives pgcKey from StageRecord.dataType

*For any* batch of `StageRecord` objects where all records share the same `dataType`, the `pgc_key` in the batch API request body SHALL equal `DataTypePgcKeyMapper.resolve(dataType, waferBlank)`.

**Validates: Requirements 4.3, 6.1**

### Property 5: PPID suffix validation correctly gates Found results

*For any* combination of PPID string and testPhase string:
- When `testPhase` is null or blank → the result is accepted (no downgrade).
- When `ppid` is null or blank → the result is accepted (no downgrade).
- When both are non-blank and `ppid` ends with `_<testPhase>` (case-insensitive) → the result is accepted.
- When both are non-blank and `ppid` does NOT end with `_<testPhase>` → the result is downgraded to `NotFound`.

**Validates: Requirements 5.1, 5.2, 5.3, 5.4, 6.2**

## Error Handling

- **Unknown data type**: `DataTypePgcKeyMapper.resolve` falls back silently to wafer-presence logic. No exception is thrown.
- **PPID mismatch**: Logged at DEBUG level with lot, wafer, expected test phase, and actual PPID. The record stays in `EXENSIO_LOADING` and retries on the next monitor cycle.
- **Null dataType / testPhase on StageRecord**: Both fields are nullable. All consumers guard with null checks before using them.
- **Batch with mixed data types**: The batch uses the `pgc_key` of the most common data type. Records that don't match the batch's `pgc_key` will be retried individually via the existing `retryIndividualRecords` fallback path.

## Testing Strategy

### Unit tests (JUnit 5)

- `DataTypePgcKeyMapperTest`: verify each known mapping, case variants, unknown inputs, null input.
- `ExensioClientPpidValidationTest`: verify `ppidMatchesTestPhase` for match, mismatch, null PPID, null testPhase.
- `StagePayloadRequestSerializationTest`: verify `dataType` and `testPhase` are serialized/deserialized correctly.

### Property-based tests (jqwik — already used in the project)

Each correctness property above maps to one `@Property` test:

- **P1** — `@Property` generates random case variants of known data type strings; asserts `resolve()` returns the expected `pgc_key`.
- **P2** — `@Property` generates random `dataType` / `testPhase` strings (including null); stages a record; reads it back; asserts equality.
- **P3** — `@Property` generates random `pgcKey` integers; captures the HTTP request body; asserts `pgc_key` field matches.
- **P4** — `@Property` generates batches of `StageRecord` with a uniform `dataType`; asserts the batch request body `pgc_key` matches the mapping.
- **P5** — `@Property` generates random PPID and testPhase strings; asserts the four cases of the validation function.

Each property test runs a minimum of 100 iterations.
Tag format: `// Feature: exensio-pgc-key-matching, Property N: <property_text>`

### Frontend tests (Jasmine/Karma)

- Verify `StepperComponent` includes `dataType` and `testPhase` in the stage request body when staging selected rows.
- Verify `StepperComponent` includes `dataType` and `testPhase` from discovery filters in the stage-all request body.
