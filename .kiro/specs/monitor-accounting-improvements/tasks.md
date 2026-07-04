# Implementation Tasks: Monitor Accounting Improvements

## Overview

Implementation plan to close dashboard accounting gaps by making CANCELLED, EXENSIO_LOADING, and timeout states visible and trackable.

## Tasks

- [x] 1. Extend RefDbService aggregation queries
  - Modify `fetchStatuses()` to count CANCELLED and EXENSIO_LOADING separately
  - Update `fetchStatusesFor()` to include new counts
  - Update `fetchStatusesForUser()` to include new counts
  - Verify all three methods return consistent results
  - _Requirements: 1, 2_

- [ ]\* 1.1 Write property test for accounting invariant
  - **Property 1: Accounting Invariant**
  - **Validates: Requirements 1, 2, 6**

- [x] 2. Update StageStatus record structure
  - Add fields: `queued`, `enriching`, `exensioLoading`, `cancelled`
  - Deprecate existing `enqueued` field (keep as computed property for backward compatibility)
  - Update constructor and accessors
  - _Requirements: 1, 3, 6_

- [ ]\* 2.1 Write property test for state validity
  - **Property 2: State Validity**
  - **Validates: Requirements 2, 8**

- [x] 3. Update DashboardController.snapshot() to new StageStatus fields
  - Refactor `toMetrics()` to use individual state counts
  - Build dashboard cards with new granular counts
  - Ensure backward compatibility with existing metric calculations
  - _Requirements: 1, 3, 5_

- [x] 4. Create admin debug endpoint for state accounting verification
  - New endpoint: `GET /api/admin/debug/state-accounting`
  - Query database for all 8 state counts plus NULL/UNKNOWN checks
  - Compare against dashboard aggregation
  - Return discrepancy analysis with sample record IDs
  - Add authorization check (ROLE_ADMIN only)
  - _Requirements: 2, 6_

- [ ]\* 4.1 Write unit test for debug endpoint
  - Test happy path (all states present)
  - Test with discrepancies (missing states)
  - Test with NULL/UNKNOWN records
  - _Requirements: 2, 6_

- [x] 5. Implement STATE_AGGREGATION event in StageMonitorService
  - New event type: `STATE_AGGREGATION` with timestamp, state changes, new totals
  - Add method `broadcastStateAggregation(sessionId, event)`
  - Implement batching logic (1-second window for collecting changes)
  - _Requirements: 5, 7_

- [x] 6. Update status update triggers to emit aggregation events
  - Modify `markEnrichmentRecords()` to trigger aggregation broadcast
  - Modify `markFailed()` to trigger aggregation broadcast
  - Modify `markDone()` methods to trigger aggregation broadcast
  - Modify `bulkCancelBySender()` to batch and broadcast single aggregation
  - _Requirements: 5, 7_

- [ ]\* 6.1 Write property test for real-time accuracy
  - **Property 5: Real-Time Accuracy**
  - **Validates: Requirement 7**

- [x] 7. Enhance CpLogMonitor for stuck record detection
  - Add method `detectStuckEnrichmentRecords()` with timeout check
  - Query for records in ENRICHMENT exceeding `enrichmentTimeoutMinutes`
  - Emit alert event for each stuck record (record_id, lot, minutes_stuck)
  - Call `markDoneManualVerify()` for auto-remediation
  - _Requirements: 4, 8_

- [ ]\* 7.1 Write property test for stuck record detection
  - **Property 6: Stuck Record Detection**
  - **Validates: Requirement 4, 8**

- [x] 8. Implement DataIntegrityJob (scheduled hourly)
  - Query for invalid status values (not in valid set)
  - Query for NULL status records
  - Query for CANCELLED records still in external queue
  - Generate DataIntegrityReport with findings
  - Log issues and emit admin alerts
  - _Requirements: 8_

- [x] 9. Add timeout configuration to CpElasticsearchProperties
  - Ensure `enrichmentTimeoutMinutes` is configurable (default: 5)
  - Add validation (must be >= 1 minute)
  - Document in application.yml comments
  - _Requirements: 4, 8_

- [x] 10. Frontend: Update dashboard to display 7 cards
  - Render cards for: Staged, Queued, Enriching, Exensio Loading, Completed, Failed, Cancelled
  - Wire each card to updated StageStatus fields
  - Add responsive grid layout
  - Add color coding by state type (processing vs terminal)
  - _Requirements: 1, 3, 5_

- [ ]\* 10.1 Write unit tests for card rendering
  - Test all 7 cards display correct counts
  - Test with zero and non-zero values
  - Test responsive layout
  - _Requirements: 1, 3, 5_

- [x] 11. Frontend: Add click handler for card detail sidebar
  - Implement detail sidebar showing sample records in clicked state
  - List top 20 records sorted by created_at DESC
  - Display: status, filename, lot, wafer for each record
  - Add close button and backdrop click handler
  - _Requirements: 5_

- [x] 12. Frontend: Add state legend/tooltip
  - Create hover tooltip for each card
  - Explain what each state means
  - Show example transitions (e.g., "pending → ENRICHMENT → DONE")
  - Make tooltips accessible (keyboard navigation)
  - _Requirements: 5_

- [x] 13. Frontend: Add stuck records alert badge
  - If stuck record count > 0, show badge on dashboard
  - Badge shows count (e.g., "🔴 3 Stuck")
  - Click badge to show stuck records detail sidebar
  - Include "duration in enrichment" for each stuck record
  - _Requirements: 4_

- [x] 14. Wire SSE STATE_AGGREGATION event to dashboard
  - Add event listener in monitor component
  - Update card totals on STATE_AGGREGATION event
  - Animate count change (fade or highlight)
  - Handle connection drop and reconnect (refresh cards)
  - _Requirements: 7_

- [ ]\* 14.1 Write property test for aggregation event accuracy
  - Verify card counts match event totals
  - **Validates: Requirement 7**

- [x] 15. Integration test: End-to-end accounting verification
  - Stage N records, verify accounting sum = N
  - Bulk cancel M records, verify CANCELLED count increases and others unchanged
  - Mark records DONE, verify transitions and totals
  - Verify debug endpoint matches dashboard totals
  - _Requirements: 1, 2, 6, 7_

- [x] 16. Checkpoint: Verify all accounting tests pass
  - Ensure accounting invariant tests pass (Property 1)
  - Ensure state validity tests pass (Property 2)
  - Ensure query accuracy tests pass (Property 7)
  - Ensure debug endpoint matches aggregation logic
  - Ask user if questions arise.

- [x] 17. Performance testing
  - Benchmark aggregation query performance with 100k records
  - Verify SSE batching reduces message volume > 50x
  - Test timeout detection query performance on large tables
  - Optimize indexes if needed
  - _Requirements: 5, 7, 8_

- [x] 18. Documentation
  - Update dashboard user guide with new cards and states
  - Document admin debug endpoint in API docs
  - Add configuration section for timeout and integrity check settings
  - Create troubleshooting guide for accounting imbalances
  - _Requirements: All_

- [x] 19. Final checkpoint: Complete feature validation
  - Ensure all tests pass, ask the user if questions arise.
  - All seven cards visible and accurate
  - Accounting sum always equals total
  - Real-time updates working via SSE
  - Data integrity job running hourly
  - Admin debug endpoint functional

## Notes

- Tasks marked with `*` are optional property-based tests. Include them for comprehensive correctness validation.
- Focus on core implementation first (tasks 1-9), then frontend (tasks 10-14), then validation (tasks 15-19).
- Backward compatibility: keep old `enqueued` field as computed property; don't break existing dashboard code.
- Database indexes should be optimized before performance testing (task 17).
