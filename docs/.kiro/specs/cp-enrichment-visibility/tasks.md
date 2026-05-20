# Implementation Plan: CP Enrichment Visibility

## Overview

Fix the incorrect `DONE` transition on queue consumption, add Elasticsearch polling to detect CP enrichment outcomes, store output path/target, and surface everything in the UI.

## Tasks

- [x] 1. Database migration — add CP enrichment columns
  - Create `db.changelog-9.3-cp-enrichment-columns.xml` Liquibase changeset
  - Add nullable `cp_output_path VARCHAR2(1000)` to `SENDER_STAGE`
  - Add nullable `cp_output_target VARCHAR2(20)` to `SENDER_STAGE`
  - _Requirements: 5.1, 5.2_

- [x] 2. Update domain models and DB layer
  - [x] 2.1 Add `cpOutputPath` and `cpOutputTarget` fields to `StageRecord.java`
    - _Requirements: 5.1, 5.2_
  - [x] 2.2 Add `cpOutputPath` and `cpOutputTarget` fields to `StageRecordView.java`
    - _Requirements: 5.4_
  - [x] 2.3 Update `mapRecord()` in `RefDbService.java` to read the two new columns from `ResultSet`
    - _Requirements: 5.1, 5.2_

- [x] 3. Fix queue consumption status — ENRICHMENT instead of DONE
  - [x] 3.1 Add `markEnrichmentRecords(List<StageRecord>)` to `RefDbService.java`
    - Sets status to `ENRICHMENT`, broadcasts SSE `ROW_UPDATE` with `msg: "Consumed by CP (processing)"`
    - _Requirements: 1.1, 1.2_
  - [x] 3.2 Change `SenderQueueMonitor.monitorQueue()` to call `markEnrichmentRecords()` instead of `markCompletedRecords()`
    - _Requirements: 1.1_

- [x] 4. Checkpoint — ensure existing tests pass after queue monitor change
  - Ensure all tests pass, ask the user if questions arise.

- [x] 5. Elasticsearch configuration and client
  - [x] 5.1 Create `CpElasticsearchProperties.java` (`@ConfigurationProperties("cp.elasticsearch")`)
    - Fields: `url`, `apiKey`, `username`, `password`, `indexPattern`, `cpConfigFilter`, `pollIntervalMs`, `enrichmentTimeoutMinutes`
    - _Requirements: 6.1, 6.2, 6.3, 6.4, 6.5_
  - [x] 5.2 Create `ElasticsearchClientConfig.java` — `@Configuration` bean
    - Build `RestClient` from properties
    - Auth precedence: API key → basic auth → unauthenticated
    - If `url` is blank, return a no-op stub client
    - _Requirements: 6.1, 6.7_

- [x] 6. Implement `ElasticsearchLogService.java`
  - [x] 6.1 Define `CpLogResult` sealed interface with `Success`, `Failure`, `NotFound` records
    - `Success` carries `outputPath`, `outputTarget`, `logTimestamp`
    - `Failure` carries `errorMessage`, `logTimestamp`
    - _Requirements: 3.1, 4.1_
  - [x] 6.2 Implement `findCpLog(String dataId, String lot, Instant since)` method
    - Build ES query: `cpConfig: *sender*`, `idData: <dataId>`, `mLot: <lot>`, `@timestamp >= since`
    - Sort by `@timestamp desc`, fetch up to 10 hits
    - Iterate hits: if any hit has `error.type` or `error.message` → return `Failure`
    - Else if any hit has `"output path"` in `message` → parse path, detect target, return `Success`
    - Else → return `NotFound`
    - _Requirements: 2.3, 2.4, 2.5, 2.6, 3.1, 4.1_
  - [x] 6.3 Implement `extractOutputPath(String message)` — regex `output path\s*=\s*(.+)`
    - _Requirements: 3.3_
  - [x] 6.4 Implement `detectOutputTarget(String path)` — contains `PRODUCTION` → `"PRODUCTION"`, contains `SANDBOX` → `"SANDBOX"`, else `"UNKNOWN"`
    - _Requirements: 5.3_
  - [ ]* 6.5 Write unit tests for `ElasticsearchLogService`
    - Mock ES client responses for success, failure, not-found, malformed message
    - Test `extractOutputPath` with various message formats
    - _Requirements: 3.1, 3.3, 4.1_
  - [ ]* 6.6 Write property test for `detectOutputTarget`
    - **Property 3: Output target detection is total and deterministic**
    - **Validates: Requirements 5.3**

- [x] 7. Add `markExensioLoading` to `RefDbService.java`
  - Updates status to `EXENSIO_LOADING`, sets `cp_output_path`, `cp_output_target`
  - Broadcasts SSE `ROW_UPDATE` with `status: "EXENSIO_LOADING"`, `msg: "Exensio Loading"`, `cpOutputPath`, `cpOutputTarget`
  - _Requirements: 3.2, 3.3, 3.5_

- [x] 8. Implement `CpLogMonitor.java`
  - [x] 8.1 Create `@Component` with `@Scheduled(fixedDelayString = "${cp.elasticsearch.poll-interval-ms:60000}")`
    - Inject `RefDbService`, `ElasticsearchLogService`, `CpElasticsearchProperties`
    - _Requirements: 2.1_
  - [x] 8.2 Implement `monitorEnrichmentRecords()` main loop
    - Load all `ENRICHMENT` records via `refDbService.listRecords(null, null, "ENRICHMENT", ...)`
    - For each record call `elasticsearchLogService.findCpLog(dataId, lot, enrichmentStartedAt)`
    - On `Success` → call `refDbService.markExensioLoading(record, outputPath, outputTarget)`
    - On `Failure` → call `refDbService.markFailed(record, errorMessage truncated to 500 chars)`
    - On `NotFound` + timeout exceeded → call `refDbService.markFailed(record, "CP enrichment timeout...")`
    - If ES is disabled (url blank) → log DEBUG and return immediately
    - _Requirements: 2.2, 2.7, 3.2, 4.2, 4.3, 6.7_
  - [x] 8.3 Implement timeout check: `record.updatedAt().plus(timeoutMinutes).isBefore(Instant.now())`
    - _Requirements: 2.7_
  - [ ]* 8.4 Write unit tests for `CpLogMonitor`
    - Mock `ElasticsearchLogService` to return each result type
    - Assert correct `RefDbService` method called for each case
    - Assert timeout triggers `markFailed` with correct message
    - _Requirements: 2.7, 3.2, 4.2_
  - [ ]* 8.5 Write property test for status machine invariant
    - **Property 1: Status machine — ENRICHMENT never skips to DONE**
    - **Validates: Requirements 1.3**
  - [ ]* 8.6 Write property test for timestamp guard
    - **Property 2: Timestamp guard on ES log matching**
    - **Validates: Requirements 2.6**
  - [ ]* 8.7 Write property test for error message truncation
    - **Property 4: Error message truncation**
    - **Validates: Requirements 4.5**

- [x] 9. Checkpoint — ensure all backend tests pass - read steering .md file to know what we can install and run in this env.
  - Ensure all tests pass, ask the user if questions arise.

- [x] 10. Frontend — monitoring file detail UI
  - [x] 10.1 Update `StageRecordView` TypeScript interface in `backend.service.ts` to include `cpOutputPath` and `cpOutputTarget`
    - _Requirements: 5.4_
  - [x] 10.2 Update `monitoring-file-list.component.ts` expanded row to show `cpOutputPath` and `cpOutputTarget` badge when available
    - PRODUCTION badge: distinct color (e.g. green/success)
    - SANDBOX badge: distinct color (e.g. amber/warning)
    - _Requirements: 5.5, 5.6_
  - [x] 10.3 Update `realtime-monitoring-file-list.component.ts` to show same detail in expanded/tooltip view
    - _Requirements: 5.5, 5.6_
  - [ ]* 10.4 Write unit tests for output target badge rendering
    - Test PRODUCTION, SANDBOX, UNKNOWN, and null/absent cases
    - _Requirements: 5.6_

- [x] 11. Final checkpoint — ensure all tests pass - read steering .md file for context
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional and can be skipped for faster MVP
- The immediate bug fix (task 3) is independent and can be deployed before ES polling is ready
- ES polling (tasks 5–9) is safe to deploy with ES unconfigured — it will no-op until credentials are added
- `EXENSIO_LOADING → DONE` transition is out of scope — records stay in `EXENSIO_LOADING` until a future Exensio integration phase
- Property tests use jqwik (already available in the project's test scope)
