# Implementation Plan: Per-File Integration Status

## Overview

This implementation adds per-file tracking of CP (Elasticsearch) and Exensio integration status. The status is stored keyed by `StageRecord.id()` (numeric primary key) instead of just per `requestId`. This allows the monitoring UI to show real-time status for each individual file.

**Key Changes:**

1. `IntegrationStatusService`: Add per-record maps for CP and Exensio status
2. `CpLogMonitor`: Update to call `updateCpStatusForRecord()` for each record
3. `ExensioLoadMonitor`: Update to call `updateExensioStatusForRecord()` for each record
4. `StageRecordView`: Add 4 new fields for integration status
5. `StageRecordMapper`: Look up and populate integration status
6. `StageMonitorService`: Include integration status in ROW_UPDATE SSE events

## Tasks

- [x] 1. Set up IntegrationStatusService for per-record tracking
- [x] 1.1 Add cpStatusByRecord and exensioStatusByRecord ConcurrentHashMaps
  - Store per-record status keyed by stageRecordId (long)
  - _Requirements: 1.1, 2.1_

- [x] 1.2 Add updateCpStatusForRecord() method
  - Store CpStatus with status, message, and timestamp
  - _Requirements: 1.2-1.7_

- [x] 1.3 Add updateExensioStatusForRecord() method
  - Store ExensioStatus with status, message, and timestamp
  - _Requirements: 2.2-2.5_

- [x] 1.4 Add getCpStatusForRecord() method
  - Return CpStatus or null for given stageRecordId
  - _Requirements: 1.10_

- [x] 1.5 Add getExensioStatusForRecord() method
  - Return ExensioStatus or null for given stageRecordId
  - _Requirements: 2.6_

- [x] 1.6 Add TTL eviction logic for terminal-state records
  - Evict entries after configured TTL (default: 120 minutes)
  - Only evict for terminal states: DONE, FAILED, COMPLETED, ERROR
  - _Requirements: 8.1-8.2_

- [x] 1.7 Add max entries eviction logic (LRU)
  - Evict oldest entries when max entries reached (default: 50,000)
  - _Requirements: 8.4_

- [x] 1.8 Add configuration properties for TTL and max entries
  - Add `app.integration.status.record-ttl-minutes` (default: 120)
  - Add `app.integration.status.max-entries` (default: 50000)
  - _Requirements: 8.3_

- [ ]\* 1.9 Write unit tests for IntegrationStatusService
  - Test update/get methods for both CP and Exensio
  - Test TTL eviction behavior
  - Test max entries eviction (LRU)
  - _Requirements: 1.1-1.10, 2.1-2.6, 8.1-8.4_

- [x] 2. Update CpLogMonitor to track per-record status
- [x] 2.1 Update processRecord() to call updateCpStatusForRecord() for all cases
  - ES Success (PRODUCTION/SANDBOX): status="success", msg="CP log found in ES"
  - ES Available + pp_log Success: status="success", msg="output_directory from pp_log"
  - ES Available + pp_log Error: status="failure", msg="log_message from pp_log"
  - ES Available + ES Failure: status="failure", msg=errorMessage
  - ES Available + NotFound (retry): status="not_found", msg="No ES log yet — retrying"
  - ES Available + Timeout: status="timeout", msg=timeoutMessage
  - ES Available + Exception: status="error", msg="ES query failed"
  - **No CP/ES Available**: Query pp_log directly with same timeout logic
    - pp_log Success: status="success", msg="output_directory"
    - pp_log Error: status="failure", msg="log_message"
    - pp_log NotFound: status="not_found", msg="No pp_log entry — retrying"
    - pp_log Timeout: status="timeout", msg=timeoutMessage
    - pp_log Error: status="error", msg="pp_log query failed"
  - _Requirements: 1.2-1.8_

- [x] 2.2 Update processRecord() to emit ROW_UPDATE SSE after status update
  - Emit SSE event with new integration status fields
  - Best-effort (no error if no subscribers)
  - _Requirements: 4.2_

- [ ]\* 2.3 Write unit tests for CpLogMonitor per-record status
  - Test ES Success → status "success"
  - Test pp_log Success → status "success" with output_directory
  - Test pp_log Error → status "failure" with log_message
  - Test ES Failure → status "failure"
  - Test Timeout → status "timeout"
  - Test NotFound → status "not_found"
  - Test Exception → status "error"
  - Test No CP/ES → pp_log fallback behavior
  - _Requirements: 1.2-1.8_

- [ ]\* 2.4 Write property tests for CpLogMonitor
  - **Property N: Per-record CP status isolation**
    - Two distinct record IDs should have independent status
  - **Property N: CP status matches enrichment outcome**
    - Status and message should match ES or pp_log result
  - **Validates: Requirements 1.2-1.8**

- [x] 3. Update ExensioLoadMonitor to track per-record status
- [x] 3.1 Update recordBatchIntegrationStatus() to call updateExensioStatusForRecord()
  - DONE: status="success", msg=successMessage
  - NOT_FOUND: status="not_found", msg=notFoundMessage
  - FAILED: status="failure", msg=errorMessage
  - ERROR: status="error", msg=errorMessage
  - _Requirements: 2.2-2.5_

- [x] 3.2 Update recordBatchIntegrationStatus() to emit ROW_UPDATE SSE after status update
  - Emit SSE event with new integration status fields
  - Best-effort (no error if no subscribers)
  - _Requirements: 4.3_

- [ ]\* 3.3 Write unit tests for ExensioLoadMonitor per-record status
  - Test DONE → status "success"
  - Test NOT_FOUND → status "not_found"
  - Test FAILED → status "failure"
  - Test ERROR → status "error"
  - _Requirements: 2.2-2.5_

- [ ]\* 3.4 Write property tests for ExensioLoadMonitor
  - **Property N: Per-record Exensio status isolation**
    - Two distinct record IDs should have independent status
  - **Property N: Exensio status matches batch lookup outcome**
    - Status and message should match batch result
  - **Validates: Requirements 2.2-2.5**

- [x] 4. Extend StageRecordView with integration status fields
- [x] 4.1 Add 4 new fields to StageRecordView
  - cpIntegrationStatus: String (optional)
  - cpIntegrationMessage: String (optional)
  - exensioIntegrationStatus: String (optional)
  - exensioIntegrationMessage: String (optional)
  - _Requirements: 3.1-3.2_

- [ ]\* 4.2 Write unit tests for StageRecordView
  - Test record creation with integration status fields
  - _Requirements: 3.1-3.2_

- [x] 5. Update StageRecordMapper to populate integration status
- [x] 5.1 Inject IntegrationStatusService, CpElasticsearchProperties, ExensioProperties
  - _Requirements: 3.6_

- [x] 5.2 Update toView() to look up integration status
  - Call getCpStatusForRecord(record.id())
  - Call getExensioStatusForRecord(record.id())
  - Populate 4 new fields from retrieved status or defaults
  - _Requirements: 3.3_

- [x] 5.3 Implement default status logic
  - CP: "pending" if record.status() == "ENRICHMENT" and esConfigured, else "not_configured"
  - Exensio: "pending" if record.status() == "EXENSIO_LOADING" and exensioConfigured, else "not_configured"
  - _Requirements: 3.4-3.7_

- [ ]\* 5.4 Write unit tests for StageRecordMapper
  - Test toView populates integration status from IntegrationStatusService
  - Test default "pending" for ENRICHMENT records
  - Test default "pending" for EXENSIO_LOADING records
  - Test default "not_configured" when ES not configured
  - Test default "not_configured" when Exensio not configured
  - _Requirements: 3.3-3.7_

- [ ]\* 5.5 Write property tests for StageRecordMapper
  - **Property N: StageRecordView includes integration status**
    - All 4 new fields should be present with correct values
  - **Validates: Requirements 3.1-3.7**

- [x] 6. Update StageMonitorService to include integration status in ROW_UPDATE
- [x] 6.1 Update ROW_UPDATE event to include integration status fields
  - cpIntegrationStatus, cpIntegrationMessage
  - exensioIntegrationStatus, exensioIntegrationMessage
  - _Requirements: 4.1_

- [ ]\* 6.2 Write unit tests for ROW_UPDATE event
  - Test event includes all 4 new fields
  - _Requirements: 4.1_

- [x] 7. Frontend integration
- [x] 7.1 Update MonitoringFileItem TypeScript interface
  - Add cpIntegrationStatus, cpIntegrationMessage
  - Add exensioIntegrationStatus, exensioIntegrationMessage
  - _Requirements: 5.1_

- [x] 7.2 Update service mapping to include integration status fields
  - _Requirements: 5.2_

- [x] 7.3 Update ROW_UPDATE SSE handler to update new fields
  - _Requirements: 5.3_

- [x] 7.4 Create IntegrationBadge component (CP + Exensio variants)
  - Display status with appropriate color and icon
  - _Requirements: 5.4-5.5_

- [x] 7.5 Update RealtimeMonitoringFileListComponent to display new columns
  - CP Status badge column
  - Exensio Status badge column
  - _Requirements: 5.4-5.5_

- [x] 7.6 Update detail panel to show integration messages
  - Show cpIntegrationMessage and exensioIntegrationMessage
  - _Requirements: 5.6_

- [x] 8. Checkpoint - Ensure all tests pass
- Ensure all tests pass, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional and can be skipped for faster MVP
- Each task references specific requirements for traceability
- Checkpoints ensure incremental validation
- Property tests validate universal correctness properties
- Unit tests validate specific examples and edge cases
