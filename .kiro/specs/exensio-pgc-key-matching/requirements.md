# Requirements Document

## Introduction

When the Exensio Reload stepper stages a payload, the backend calls the Exensio `lot-wafer-lookup` API to confirm that the data has been loaded into Exensio. Currently the `pgc_key` sent in that API request is hardcoded to `1` (wafer present) or `2` (wafer absent), which does not reflect the actual data type being staged.

This feature makes the `pgc_key` selection data-type-aware: the caller derives the correct `pgc_key` from the stepper's selected **Data Type** (PROBE, FT/Final Test, Defect, Bin Map/Wmap) and passes it through the staging pipeline so the Exensio API lookup uses the right program-group class.

Additionally, when a **Test Phase** is selected in the stepper (e.g. FT, QA, RG, CRSS), the Exensio lookup result must be validated by checking that the returned PPID contains the expected test phase as a suffix (e.g. `WS::CM8012X_FT`). A mismatch is treated as a load-not-yet-confirmed condition (retry), not a hard failure.

## Glossary

- **pgc_key**: Program Group Class key used by the Exensio `lot-wafer-lookup` API to scope the search to a specific data category.
- **Data Type**: The type of semiconductor test data selected in stepper Step 1 (e.g. PROBE, FT, Defect, Map).
- **Test Phase**: An optional sub-classification of the data type selected in stepper Step 1 (e.g. FT, QA, RG, CRSS).
- **PPID**: Parametric Program ID returned by the Exensio API in the `wafers[].ppid` field (e.g. `WS::CM8012X_FT`).
- **SENDER_STAGE**: The internal staging table that holds one row per payload being processed through the pipeline.
- **ExensioLoadMonitor**: The backend service that polls the Exensio API to confirm data has been loaded.
- **StageRecord**: The Java record representing one row from `SENDER_STAGE`.
- **PayloadCandidate**: The DTO used to insert a new row into `SENDER_STAGE` at staging time.

## Requirements

### Requirement 1: Data-Type-to-pgc_key Mapping

**User Story:** As a system, I want to derive the correct `pgc_key` from the user's selected Data Type, so that the Exensio API lookup is scoped to the right program-group class.

#### Acceptance Criteria

1. THE System SHALL map Data Type values to `pgc_key` as follows: `PROBE` → `1`, `FT` or `FINAL TEST` → `2`, `DEFECT` → `14`, `MAP` or `BIN MAP` or `WMAP` → `4`.
2. WHEN a Data Type does not match any known mapping, THE System SHALL fall back to the existing wafer-presence logic (`pgc_key = 1` if wafer is present, `pgc_key = 2` if wafer is absent).
3. THE System SHALL perform the mapping case-insensitively so that `ft`, `FT`, and `Final Test` all resolve to `pgc_key = 2`.
4. THE System SHALL expose the mapping as a named, testable utility so it can be used consistently across the staging pipeline.

### Requirement 2: Persist Data Type and Test Phase on the Staging Record

**User Story:** As a system, I want the Data Type and Test Phase selected in the stepper to be stored on each staging record, so that the Exensio monitor can use them during load confirmation without re-querying the frontend.

#### Acceptance Criteria

1. THE System SHALL add a `data_type` column (VARCHAR, nullable) to the `SENDER_STAGE` table via a new Liquibase changeset.
2. THE System SHALL add a `test_phase` column (VARCHAR, nullable) to the `SENDER_STAGE` table via a new Liquibase changeset.
3. WHEN a payload is staged, THE System SHALL persist the `data_type` and `test_phase` values from the staging request onto the `SENDER_STAGE` row.
4. THE `StageRecord` Java record SHALL include `dataType` and `testPhase` fields so the values are available to all pipeline stages.
5. WHEN `data_type` or `test_phase` is not provided in the staging request, THE System SHALL store `NULL` for those columns.

### Requirement 3: Pass Data Type and Test Phase Through the Staging API

**User Story:** As a frontend, I want to include the selected Data Type and Test Phase in the stage request, so that the backend can persist them and use them for Exensio verification.

#### Acceptance Criteria

1. THE `StagePayloadRequest` DTO SHALL include optional `dataType` and `testPhase` string fields.
2. WHEN the frontend stages selected payloads, THE Stepper SHALL include the currently selected `dataType` and `testPhase` values in the request body.
3. WHEN the frontend stages all matching payloads (stage-all), THE Stepper SHALL include the `dataType` and `testPhase` from the active discovery filters in the request body.
4. THE `StageAllRequest` DTO SHALL include optional `dataType` and `testPhase` string fields so the stage-all path also carries these values.

### Requirement 4: Use pgc_key in Exensio Lot-Wafer Lookup

**User Story:** As the Exensio Load Monitor, I want to send the correct `pgc_key` in the lot-wafer-lookup request, so that the API returns results scoped to the right program-group class.

#### Acceptance Criteria

1. WHEN `ExensioClient.lotWaferLookup` is called, THE System SHALL accept an optional `pgcKey` parameter and include it in the request body instead of the hardcoded wafer-presence value.
2. WHEN `pgcKey` is not provided (null), THE System SHALL fall back to the existing wafer-presence logic (`1` if wafer present, `2` if wafer absent).
3. WHEN `ExensioClient.lotWaferLookupBatch` is called, THE System SHALL derive the `pgc_key` from the `dataType` field on the `StageRecord` records in the batch, using the mapping from Requirement 1.
4. WHEN a batch contains records with mixed Data Types, THE System SHALL use the `pgc_key` of the most common Data Type in the batch, or split the batch by Data Type if the implementation supports it.

### Requirement 5: Validate PPID Suffix Against Test Phase

**User Story:** As the Exensio Load Monitor, I want to verify that the PPID returned by Exensio contains the expected Test Phase as a suffix, so that I confirm the correct program was loaded.

#### Acceptance Criteria

1. WHEN the Exensio API returns a `Found` result and the `StageRecord` has a non-blank `testPhase`, THE System SHALL check that the PPID ends with `_<testPhase>` (case-insensitive).
2. WHEN the PPID suffix does not match the expected Test Phase, THE System SHALL treat the result as `NotFound` and allow the monitor to retry on the next cycle.
3. WHEN the `StageRecord` has a blank or null `testPhase`, THE System SHALL skip the PPID suffix check and accept any PPID.
4. WHEN the Exensio API returns a PPID that is null or blank, THE System SHALL skip the PPID suffix check and accept the result.
5. THE System SHALL log a debug message when a PPID suffix mismatch causes a `Found` result to be downgraded to `NotFound`, including the lot, wafer, expected test phase, and actual PPID.

### Requirement 6: Backward Compatibility

**User Story:** As an operator, I want existing staging sessions and records without Data Type or Test Phase to continue working correctly, so that the upgrade does not break in-flight or historical data.

#### Acceptance Criteria

1. WHEN a `StageRecord` has a null `dataType`, THE System SHALL use the existing wafer-presence pgc_key fallback logic.
2. WHEN a `StageRecord` has a null `testPhase`, THE System SHALL skip the PPID suffix validation.
3. WHEN the `data_type` and `test_phase` columns are added to `SENDER_STAGE`, THE System SHALL allow NULL values so existing rows are unaffected.
