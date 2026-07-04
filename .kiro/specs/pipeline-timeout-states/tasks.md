# Implementation Plan: Pipeline Timeout States

## Overview

This implementation adds two new timeout states (ENRICHMENT_TIMEOUT and EXENSIO_TIMEOUT) to provide honest accounting of records with uncertain enrichment or verification status. The implementation follows a backend-first approach, then frontend visualization, with incremental validation at checkpoints.

## Tasks

- [x] 1. Database schema migration for new timeout states
  - Create Liquibase changeset to add ENRICHMENT_TIMEOUT and EXENSIO_TIMEOUT to status constraint
  - Test constraint validation with both new status values
  - Verify existing records unaffected by migration
  - _Requirements: 3.1, 3.2, 3.3, 3.4_

- [ ]\* 1.1 Write unit tests for schema migration
  - Test inserting records with ENRICHMENT_TIMEOUT status
  - Test inserting records with EXENSIO_TIMEOUT status
  - Test constraint allows all 9 status values
  - _Requirements: 3.4_

- [x] 2. Implement RefDbService timeout marking methods
  - Add markEnrichmentTimeout(StageRecord, String) method
  - Add markExensioTimeout(StageRecord, String) method
  - Emit SSE state change events via StateAggregationBatcher
  - Update fetchStatusesFor() SQL query to include enrichmentTimeout and exensioTimeout counts
  - _Requirements: 1.1, 1.2, 1.3, 2.1, 2.2, 2.3, 4.1, 4.2_

- [ ]\* 2.1 Write property test for timeout status transitions
  - **Property 1: State Transition to Enrichment Timeout**
  - **Property 2: State Transition to Exensio Timeout**
  - **Validates: Requirements 1.1, 2.1**

- [ ]\* 2.2 Write property test for diagnostic information
  - **Property 3: Timeout Records Include Diagnostic Information**
  - **Validates: Requirements 1.2, 2.2, 10.1, 10.2, 10.3, 10.4, 10.5**

- [ ]\* 2.3 Write property test for SSE event emission
  - **Property 4: SSE Events on Timeout Transitions**
  - **Validates: Requirements 1.3, 2.3, 7.1, 7.2**

- [x] 3. Update CpLogMonitor for enrichment timeout detection
  - Modify timeout detection logic to mark ENRICHMENT_TIMEOUT instead of calling tryExensioDirectLookup
  - Check ES NotFound AND pp_log NotFound AND no concrete error → markEnrichmentTimeout()
  - Build diagnostic summary including ES and pp_log responses
  - _Requirements: 1.1, 1.2, 1.3, 10.1, 10.2_

- [x]\* 3.1 Write unit tests for CpLogMonitor timeout logic
  - Test ES NotFound + pp_log NotFound → calls markEnrichmentTimeout()
  - Test ES Found or pp_log Found → continues normal flow
  - Test concrete error → calls markFailed()
  - _Requirements: 1.1_

- [x] 4. Update ExensioLoadMonitor for Exensio timeout detection
  - Add ENRICHMENT_TIMEOUT and EXENSIO_TIMEOUT to BatchResult.UpdateType enum
  - Modify timeout handling to create EXENSIO_TIMEOUT updates instead of FAILED
  - Include configured timeout duration in timeout message
  - _Requirements: 2.1, 2.2, 2.3, 10.3, 10.4_

- [ ]\* 4.1 Write unit tests for ExensioLoadMonitor timeout logic
  - Test timed-out record generates EXENSIO_TIMEOUT UpdateType
  - Test timeout duration calculated correctly
  - Test timeout message includes configured duration
  - _Requirements: 2.1, 2.2_

- [ ] 5. Checkpoint - Backend timeout detection complete
  - Ensure all tests pass, ask the user if questions arise.

- [x] 6. Update StateAccountingService for new timeout states
  - Update StageStatus record to include enrichmentTimeout and exensioTimeout fields
  - Update isAccountingBalanced() to include timeout states in sum
  - Update database query to count ENRICHMENT_TIMEOUT and EXENSIO_TIMEOUT records
  - _Requirements: 4.1, 4.2, 4.3, 4.4_

- [ ]\* 6.1 Write property test for accounting balance invariant
  - **Property 7: Accounting Balance Invariant**
  - **Validates: Requirements 4.3, 4.4**

- [ ]\* 6.2 Write property test for state accounting queries
  - **Property 8: State Accounting Queries Include Timeout States**
  - **Validates: Requirements 4.1, 4.2**

- [ ]\* 6.3 Write unit tests for StateAccountingService
  - Test isAccountingBalanced() with records in all 9 states
  - Test query returns non-null enrichmentTimeout and exensioTimeout counts
  - Test discrepancy detection when sum doesn't equal total
  - _Requirements: 4.4_

- [x] 7. Update StateAccountingReport DTO
  - Add enrichmentTimeout and exensioTimeout fields to DatabaseStateCounts
  - Add enrichmentTimeout and exensioTimeout fields to DashboardCardCounts
  - Update accounting validation logic to include timeout states
  - _Requirements: 4.1, 4.2, 4.4_

- [ ] 8. Checkpoint - Backend state accounting complete
  - Ensure all tests pass, ask the user if questions arise.

- [x] 9. Update frontend Backend Service DTOs
  - Add enrichmentTimeout field to DashboardMetricTotals interface
  - Add exensioTimeout field to DashboardMetricTotals interface
  - Update TypeScript type definitions
  - _Requirements: 5.1, 5.2_

- [ ]\* 9.1 Write unit tests for TypeScript DTOs
  - Test DashboardMetricTotals accepts enrichmentTimeout field
  - Test DashboardMetricTotals accepts exensioTimeout field
  - _Requirements: 5.1, 5.2_

- [x] 10. Update DashboardComponent with timeout metric cards
  - Add Enrichment Timeout card to primaryMetrics() signal
  - Add Exensio Timeout card to primaryMetrics() signal
  - Use warning color and schedule icon for both timeout cards
  - Include descriptive tooltips explaining timeout states
  - _Requirements: 5.1, 5.2, 5.3, 5.4, 5.5_

- [ ]\* 10.1 Write unit tests for dashboard metric cards
  - Test 9 cards are rendered (including 2 timeout cards)
  - Test enrichmentTimeout count displayed correctly
  - Test exensioTimeout count displayed correctly
  - Test warning color applied to timeout cards
  - Test schedule icon used for timeout cards
  - _Requirements: 5.1, 5.2, 5.3, 5.4_

- [x] 11. Update StateLegendService with timeout state definitions
  - Add enrichmentTimeout StateDefinition with description and transitions
  - Add exensioTimeout StateDefinition with description and transitions
  - Include notes explaining uncertainty vs. failure
  - Define possible transitions for timeout states
  - _Requirements: 6.1, 6.2, 6.3, 6.4, 6.5_

- [x]\* 11.1 Write unit tests for state legend definitions
  - Test STATE_DEFINITIONS includes enrichmentTimeout
  - Test STATE_DEFINITIONS includes exensioTimeout
  - Test descriptions are present and non-empty
  - Test possible transitions are defined
  - _Requirements: 6.1, 6.2, 6.3, 6.4_

- [x] 12. Update StepperComponent monitoring stats
  - Add enrichmentTimeoutCount to monitoringStats() signal
  - Add exensioTimeoutCount to monitoringStats() signal
  - Filter files by ENRICHMENT_TIMEOUT and EXENSIO_TIMEOUT status
  - Display timeout counts in step 3 (monitor dispatch page)
  - _Requirements: 9.1, 9.2, 9.3, 9.4, 9.5_

- [ ]\* 12.1 Write property test for status filtering
  - **Property 10: Status Filtering Correctness**
  - **Validates: Requirements 9.4, 9.5**

- [ ]\* 12.2 Write unit tests for stepper monitoring stats
  - Test enrichmentTimeout count calculated from file statuses
  - Test exensioTimeout count calculated from file statuses
  - Test filtering by ENRICHMENT_TIMEOUT returns correct records
  - Test filtering by EXENSIO_TIMEOUT returns correct records
  - _Requirements: 9.1, 9.2, 9.4, 9.5_

- [x] 13. Update frontend SSE event handling
  - Handle ENRICHMENT_TIMEOUT SSE events in BackendService
  - Handle EXENSIO_TIMEOUT SSE events in BackendService
  - Increment dashboard card counts when timeout events received
  - _Requirements: 7.1, 7.2, 7.3, 7.4_

- [ ]\* 13.1 Write property test for SSE event handling
  - **Property 9: Frontend SSE Event Handling**
  - **Validates: Requirements 7.3, 7.4**

- [ ]\* 13.2 Write unit tests for SSE event handling
  - Test enrichmentTimeout increments on ENRICHMENT → ENRICHMENT_TIMEOUT event
  - Test exensioTimeout increments on EXENSIO_LOADING → EXENSIO_TIMEOUT event
  - Test dashboard metrics signal updates correctly
  - _Requirements: 7.3, 7.4_

- [x] 14. Checkpoint - Frontend visualization complete
  - Ensure all tests pass, ask the user if questions arise.

- [ ] 15. Integration testing across full stack
  - Test end-to-end ENRICHMENT timeout flow (pending → ENRICHMENT → ENRICHMENT_TIMEOUT)
  - Test end-to-end EXENSIO timeout flow (EXENSIO_LOADING → EXENSIO_TIMEOUT)
  - Verify SSE events propagate to frontend and update dashboard
  - Verify accounting balance maintained with timeout states
  - _Requirements: 1.1, 1.2, 1.3, 2.1, 2.2, 2.3, 4.4, 7.1, 7.2, 7.3, 7.4_

- [ ]\* 15.1 Write integration tests
  - Test full ENRICHMENT timeout flow with database + SSE + frontend
  - Test full EXENSIO timeout flow with database + SSE + frontend
  - Test StateAccountingService isAccountingBalanced() across all states
  - _Requirements: 4.4, 7.3, 7.4_

- [ ] 16. Backward compatibility verification
  - Test old frontend gracefully handles new enrichmentTimeout/exensioTimeout fields
  - Test new frontend handles missing timeout fields (null/undefined)
  - Test existing DONE/FAILED records unaffected by changes
  - Verify no breaking API changes
  - _Requirements: 8.1, 8.2, 8.3, 8.4_

- [ ]\* 16.1 Write backward compatibility tests
  - Test old frontend interface (mock) ignores unknown fields
  - Test new frontend handles missing fields gracefully
  - Test existing records unchanged after migration
  - _Requirements: 8.1, 8.2, 8.3_

- [ ] 17. Final checkpoint - Complete implementation validated
  - Ensure all tests pass
  - Review code for consistency with design
  - Verify all requirements validated by tests
  - Ask the user for final approval before deployment

## Notes

- Tasks marked with `*` are optional testing tasks and can be skipped for faster MVP
- Each task references specific requirements for traceability
- Property tests validate universal correctness properties across all inputs
- Unit tests validate specific examples and edge cases
- Checkpoints ensure incremental validation and allow user review at key milestones
- Backend tasks (1-8) should be completed before frontend tasks (9-14) to ensure API stability
