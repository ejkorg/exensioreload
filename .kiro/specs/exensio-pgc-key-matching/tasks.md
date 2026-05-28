# Implementation Plan: Exensio pgc_key Matching

## Overview

Implement data-type-aware `pgc_key` selection and PPID test-phase validation for the Exensio lot-wafer-lookup pipeline. Changes span the Java backend (utility class, DTOs, DB schema, API client) and the Angular frontend (stepper request body).

## Tasks

- [x] 1. Add `DataTypePgcKeyMapper` utility class
  - Create `backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/service/DataTypePgcKeyMapper.java`
  - Implement `resolve(String dataType, boolean waferBlank)` with the mapping: PROBE→1, FT/FINAL TEST→2, DEFECT→14, MAP/BIN MAP/WMAP→4, fallback to wafer-presence
  - Mapping must be case-insensitive
  - _Requirements: 1.1, 1.2, 1.3, 1.4_

- [ ]* 1.1 Write property test for DataTypePgcKeyMapper
  - **Property 1: Data type mapping is case-insensitive and exhaustive**
  - Generate random case variants of each known data type; assert correct pgc_key
  - Generate unknown strings; assert fallback behavior
  - **Validates: Requirements 1.1, 1.3**

- [x] 2. Add Liquibase changeset for data_type and test_phase columns
  - Create `backend/src/main/resources/db/changelog/db.changelog-9.5-data-type-test-phase.xml`
  - Add nullable `data_type VARCHAR(100)` column to `SENDER_STAGE`
  - Add nullable `test_phase VARCHAR(50)` column to `SENDER_STAGE`
  - Include the new changeset in `db.changelog-1.0.xml`
  - _Requirements: 2.1, 2.2, 2.3, 6.3_

- [x] 3. Extend StageRecord and PayloadCandidate with dataType and testPhase
  - Add `String dataType` and `String testPhase` fields to `StageRecord` record (append after existing fields)
  - Add `String dataType` and `String testPhase` fields to `PayloadCandidate` record
  - Update all `ResultSet` mapping code in `RefDbService` that constructs `StageRecord` to read the new columns
  - _Requirements: 2.4, 2.5_

- [ ]* 3.1 Write property test for staging round-trip
  - **Property 2: Staging round-trip preserves dataType and testPhase**
  - Generate random dataType/testPhase strings (including null); stage a record; read it back; assert equality
  - **Validates: Requirements 2.3, 2.5, 6.3**

- [x] 4. Extend StagePayloadRequest with dataType and testPhase
  - Add optional `String dataType` and `String testPhase` fields to `StagePayloadRequest` record
  - Update `SenderController.stagePayloads` to pass `request.dataType()` and `request.testPhase()` into `PayloadCandidate` construction
  - Update `SenderController.stageAllMatching` to pass `request.dataType()` and `request.testPhase()` (already on `StageAllRequest`) into `PayloadCandidate` construction
  - _Requirements: 3.1, 3.4_

- [x] 5. Update RefDbService INSERT to persist data_type and test_phase
  - Modify the `stagePayloads` INSERT SQL in `RefDbService` to include `data_type` and `test_phase` columns
  - Bind `PayloadCandidate.dataType()` and `PayloadCandidate.testPhase()` in the prepared statement
  - _Requirements: 2.3, 2.5_

- [x] 6. Checkpoint — Ensure all backend unit tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [x] 7. Add pgcKey parameter to ExensioClient.lotWaferLookup
  - Add new overload: `lotWaferLookup(String lot, String wafer, Instant targetEndTime, Integer pgcKey)`
  - In `doLotWaferLookup`, use the provided `pgcKey` when non-null; otherwise fall back to wafer-presence logic
  - Existing two-arg and three-arg overloads delegate to the new four-arg overload with `pgcKey=null`
  - _Requirements: 4.1, 4.2, 6.1_

- [ ]* 7.1 Write property test for pgcKey in request body
  - **Property 3: pgcKey is reflected in the Exensio API request body**
  - Intercept the HTTP request body; assert `pgc_key` field equals the provided value
  - Test null pgcKey falls back to wafer-presence logic
  - **Validates: Requirements 4.1, 4.2**

- [x] 8. Update ExensioClient batch lookup to derive pgcKey from StageRecord.dataType
  - In `doLotWaferLookupBatch`, replace the `allWafersBlank` heuristic with `DataTypePgcKeyMapper.resolve(record.dataType(), waferBlank)` per record
  - Use the most common `pgc_key` across the batch for the request body
  - _Requirements: 4.3, 4.4, 6.1_

- [ ]* 8.1 Write property test for batch pgcKey derivation
  - **Property 4: Batch lookup derives pgcKey from StageRecord.dataType**
  - Generate batches of StageRecord with uniform dataType; assert request body pgc_key matches mapping
  - **Validates: Requirements 4.3, 6.1**

- [x] 9. Update ExensioLoadMonitor to pass pgcKey in individual retries
  - In `retryIndividualRecords`, call `exensioClient.lotWaferLookup(record.lot(), record.wafer(), record.endTime(), DataTypePgcKeyMapper.resolve(record.dataType(), waferBlank))`
  - _Requirements: 4.1, 4.3_

- [x] 10. Add PPID suffix validation in ExensioClient.parseResponse
  - Add private method `ppidMatchesTestPhase(String ppid, String testPhase)` implementing the four-case logic
  - Thread `testPhase` from `StageRecord` through the call chain into `parseResponse`
  - After a `Found` result is identified, call `ppidMatchesTestPhase`; if false, return `NotFound` and log at DEBUG
  - _Requirements: 5.1, 5.2, 5.3, 5.4, 5.5, 6.2_

- [ ]* 10.1 Write property test for PPID suffix validation
  - **Property 5: PPID suffix validation correctly gates Found results**
  - Generate random PPID and testPhase combinations covering all four cases (null testPhase, null PPID, match, mismatch)
  - **Validates: Requirements 5.1, 5.2, 5.3, 5.4, 6.2**

- [x] 11. Checkpoint — Ensure all backend tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [x] 12. Update Angular StepperComponent to include dataType and testPhase in stage requests
  - In the method that builds the `StagePayloadRequest` body for staging selected rows, add `dataType: this.selectedDataType()` and `testPhase: this.selectedTestPhase()`
  - In the method that builds the `StageAllRequest` body for stage-all, confirm `dataType` and `testPhase` from discovery filters are included (they may already be present via `lastDiscoveryFilters`)
  - Update the `StagePayloadRequestBody` TypeScript interface in `backend.service.ts` to include `dataType` and `testPhase` optional fields
  - _Requirements: 3.2, 3.3_

- [ ]* 12.1 Write frontend example test for stage request body
  - Verify `StepperComponent` includes `dataType` and `testPhase` in the stage request body
  - Verify stage-all request body includes `dataType` and `testPhase` from discovery filters
  - **Validates: Requirements 3.2, 3.3**

- [ ] 13. Final checkpoint — Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional and can be skipped for a faster MVP
- New columns are nullable — no data migration needed for existing rows
- `StageAllRequest` already has `dataType` and `testPhase`; only `StagePayloadRequest` needs the new fields
- The PPID suffix check is a soft gate (retry, not fail) — no change to timeout/failure logic
- `DataTypePgcKeyMapper` is a pure utility with no Spring dependencies — easy to unit test
