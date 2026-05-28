# Implementation Plan: CP Enrichment idFile + idData Matching

## Overview

Refactor the CP enrichment ES query to filter by both `idFile` and `idData`, replace `service.environment` success detection with message-based `should` clauses, and add a `pp_log` fallback path through `RefDbService`.

## Tasks

- [x] 1. Add pp_log query methods to RefDbService
  - Add `queryPpLogSuccess(String lot, String idFile)` — queries `pp_log` with `process_code = 0`, returns `output_directory` or null
  - Add `queryPpLogError(String lot, String idFile)` — queries `pp_log` with `process_code != 0`, returns `log_message` or null
  - Both methods bind `lot` directly and `'%' + idFile + '%'` for the LIKE on `extension` and `file_name`
  - Both methods use the existing `dataSource` connection pool
  - _Requirements: 6.1, 6.2_

- [ ]* 1.1 Write property tests for pp_log query methods
  - **Property 4: pp_log success path returns CpLogResult.Success with output_directory**
  - **Property 5: pp_log error path returns CpLogResult.Failure with log_message**
  - Use H2 in-memory DB (already configured in RefDbService test setup) with a `pp_log` table
  - **Validates: Requirements 3.3, 3.4, 3.5, 3.6**

- [x] 2. Inject RefDbService into ElasticsearchLogService
  - Add `RefDbService refDbService` constructor parameter to `ElasticsearchLogService`
  - Guard all `refDbService` calls with a null check (for test environments where it may not be wired)
  - _Requirements: 6.3, 6.4_

- [x] 3. Add idFile parameter to buildQuery and findCpLog
  - Add `String idFile` as the first parameter to `findCpLog(String idFile, String dataId, String lot, Instant since, String site)`
  - Add `String idFile` to the internal `buildQuery` overloads
  - When `idFile` is non-blank, add `{ "term": { "idFile": "<idFile>" } }` to the `must` array
  - When `idFile` is null or blank, omit the `idFile` term clause (backward compat)
  - _Requirements: 1.1, 1.2, 1.3, 5.1, 5.3, 7.1_

- [ ]* 3.1 Write property test for idFile/idData in must array
  - **Property 1: Both idFile and idData appear in the must array**
  - Generate random non-blank idFile and idData strings; assert both term clauses appear in must
  - **Property 6: Null or blank metadataId omits the idFile term filter**
  - Generate null and blank idFile values; assert no idFile term clause in must
  - **Validates: Requirements 1.1, 1.2, 1.3, 7.1**

- [x] 4. Add should clauses and minimum_should_match to buildQuery
  - Add the four `should` clauses to the ES query:
    - `wildcard` on `message` matching `*output path*PRODUCTION*` (boost 4)
    - `wildcard` on `message` matching `*SANDBOX*` (boost 3)
    - `bool.must_not` on `log.level: ERROR` (boost 3)
    - `term` on `log.level: ERROR` (boost 1)
  - Set `minimum_should_match: 1` on the `bool` query
  - Extend `_source` to include `idFile` and `idData`
  - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5, 1.4_

- [ ]* 4.1 Write example tests for should clause structure
  - Verify the built query JSON contains all four should clauses with correct boost values
  - Verify `minimum_should_match: 1` is set
  - Verify `_source` includes `idFile` and `idData`
  - **Validates: Requirements 2.1, 2.2, 2.3, 2.4, 2.5, 1.4**

- [x] 5. Refactor parseResponse to use message-based detection and pp_log fallback
  - Remove the `service.environment` check and `OUTPUT_PATH_PATTERN` regex from the hit-evaluation loop
  - Implement the new priority order per hit:
    1. `log.level == ERROR` → `CpLogResult.Failure(message or "CP processing error")`
    2. `message` contains `PRODUCTION` → `CpLogResult.Success(message, "PRODUCTION")`
    3. `message` contains `SANDBOX` → `CpLogResult.Success(message, "SANDBOX")`
    4. `message` contains `executed successfully` → call `refDbService.queryPpLogSuccess` / `queryPpLogError`
    5. No match → continue to next hit
  - Retain `detectOutputTarget` for use with `pp_log` `output_directory` values
  - _Requirements: 2.6, 2.7, 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 3.7, 3.8, 4.1, 4.2, 4.3, 6.3, 6.4, 7.2, 7.3_

- [ ]* 5.1 Write property test for ERROR log.level priority
  - **Property 3: ERROR log.level hits are always failures regardless of message content**
  - Generate random ES hit JSON with log.level=ERROR and random message (including PRODUCTION/SANDBOX)
  - Assert parseResponse returns CpLogResult.Failure
  - **Validates: Requirements 4.1, 4.3**

- [ ]* 5.2 Write property test for message-based output target detection
  - **Property 2: Message-based output target detection is exhaustive**
  - Generate random message strings with PRODUCTION, SANDBOX, both, or neither in random case
  - Assert detectOutputTarget returns the correct value
  - **Validates: Requirements 2.6, 2.7**

- [x] 6. Update CpLogMonitor to pass metadataId as idFile
  - In `processRecord`, change the `findCpLog` call to pass `record.metadataId()` as the first argument
  - _Requirements: 5.2_

- [ ] 7. Checkpoint — Ensure all backend tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [x] 8. Update ElasticsearchLogServiceTest for new signature
  - Update existing tests in `ElasticsearchLogServiceTest` to pass an `idFile` argument to `buildQuery`
  - Verify existing test assertions still hold
  - _Requirements: 1.1, 1.3_

- [ ] 9. Final checkpoint — Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional and can be skipped for a faster MVP
- No schema changes required — `metadata_id` and `data_id` already exist on `SENDER_STAGE`
- The `OUTPUT_PATH_PATTERN` regex and `extractOutputPath` helper can be removed or kept as dead code — removing is cleaner
- `detectOutputTarget` is retained and reused for `pp_log` `output_directory` values
- The H2 in-memory DB used in `RefDbService` tests does not have a `pp_log` table by default — the property tests for task 1.1 will need to create it in the test setup
