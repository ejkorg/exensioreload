# Implementation Plan: Staging Monitoring System Enhancements

## Overview

This implementation plan focuses on enhancing the existing staging monitoring system with improved SSE events, queue status visibility, reduced latency, and new UI components for lot/wafer progress and activity feeds.

## Tasks

- [x] 1. Enhance SSE Event System
  - Add new event DTOs (FileUpdateEvent, LotUpdateEvent, SessionStatusEvent, SessionStatsEvent)
  - Implement event batching for FILE_UPDATE events (500ms buffer, max 50 files)
  - Add display status mapping helper (ENQUEUED + queue check → "Queued" vs "Processing")
  - _Requirements: 2.2, 2.3, 2.4, 3.2, 3.3, 12.1, 12.2, 12.3, 12.4, 12.5_

- [x] 2. Update StageMonitorService with Enhanced Events
  - [x] 2.1 Add broadcastFileUpdate() and broadcastFileUpdates() methods
    - Implement single file update event
    - Implement batched file updates with buffering logic
    - _Requirements: 2.2, 4.4_

  - [x] 2.2 Write property test for event batching
    - **Property 8: File Status Change Broadcasting**
    - **Validates: Requirements 2.2, 4.4**

  - [x] 2.3 Add broadcastLotUpdate() method
    - Broadcast lot-level progress changes
    - _Requirements: 5.4_

  - [x] 2.4 Write property test for lot update broadcasting
    - **Property 9: Session Counter Broadcasting**
    - **Validates: Requirements 2.3**

  - [x] 2.5 Add broadcastSessionStatus() method
    - Broadcast session status transitions
    - _Requirements: 2.4, 8.4_

  - [x] 2.6 Write property test for session status broadcasting
    - **Property 10: Session Completion Broadcasting**
    - **Validates: Requirements 2.4, 4.5, 8.4**

  - [x] 2.7 Add broadcastStats() with enhanced metrics
    - Include throughput, ETA, success rate in STATS events
    - _Requirements: 2.3, 18.5_

  - [x] 2.8 Write property test for stats broadcasting
    - **Property 9: Session Counter Broadcasting**
    - **Validates: Requirements 2.3**

- [x] 3. Implement Throughput and ETA Calculations
  - [x] 3.1 Add calculateMetrics() method to StageSessionService
    - Query completion timestamps from last 5 minutes
    - Calculate throughput (files per minute)
    - Calculate ETA (remaining files / throughput)
    - Calculate success rate (done / (done + failed))
    - _Requirements: 18.1, 18.2, 18.3, 18.4_

  - [x] 3.2 Write property test for throughput calculation
    - **Property 41: Throughput Calculation Window**
    - **Validates: Requirements 18.1**

  - [x] 3.3 Write property test for ETA calculation
    - **Property 42: ETA Calculation Formula**
    - **Validates: Requirements 18.2**

  - [x] 3.4 Write property test for zero throughput handling
    - **Property 43: Zero Throughput Display**
    - **Validates: Requirements 18.3**

  - [x] 3.5 Update StagingSessionDetail DTO to include metrics
    - Add throughput, eta, successRate fields
    - _Requirements: 18.5_

- [x] 4. Reduce SenderQueueMonitor Latency
  - [x] 4.1 Update monitor interval to 30 seconds
    - Change @Scheduled annotation to fixedDelay = 30000
    - _Requirements: 4.1, 4.3_

  - [x] 4.2 Implement complete pagination for ENQUEUED records
    - Page through ALL ENQUEUED records (500 per page)
    - Process all active sessions in each cycle
    - _Requirements: 4.2_

  - [x] 4.3 Broadcast enhanced events on status changes
    - Use new FILE_UPDATE events instead of ROW_UPDATE
    - Include display status in events
    - Broadcast LOT_UPDATE when lot progress changes
    - _Requirements: 4.4, 5.4_

  - [x] 4.4 Write property test for completion detection latency
    - **Property 14: Completion Detection Latency**
    - **Validates: Requirements 3.4, 4.3**

- [x] 5. Implement Queue Status Visibility
  - [x] 5.1 Add getDisplayStatus() helper method
    - Map SENDER_STAGE.status + external queue presence → display status
    - NEW → "Staged", ENQUEUED+inQueue → "Queued", ENQUEUED+notInQueue → "Processing"
    - _Requirements: 3.2, 3.3, 12.1, 12.2, 12.3, 12.4, 12.5_

  - [x] 5.2 Write property test for status display mapping
    - **Property 33: Status Display Mapping**
    - **Validates: Requirements 12.1, 12.2, 12.3, 12.4, 12.5**

  - [x] 5.3 Update FILE_UPDATE events to include displayStatus
    - Add displayStatus field to FileUpdateEvent
    - _Requirements: 3.2, 3.3_

- [x] 6. Checkpoint - Ensure all backend tests pass
  - Backend enhancements complete: SSE events, throughput/ETA, queue status visibility, LOT_UPDATE broadcasting

- [x] 7. Create LotWaferProgressComponent (Frontend)
  - [x] 7.1 Create component with collapsible lot groups
    - Implement expandable/collapsible lot headers
    - Show wafer list when lot is expanded
    - Display progress bars for lots and wafers
    - _Requirements: 5.1, 5.2, 5.3, 5.5_

  - [x] 7.2 Subscribe to LOT_UPDATE SSE events
    - Update lot progress in real-time
    - _Requirements: 5.4_

  - [x] 7.3 Add glassmorphism styling
    - Match existing design system
    - Support dark and light themes
    - _Requirements: Frontend Standards_

  - [x] 7.4 Write unit tests for lot/wafer component
    - Test expand/collapse functionality
    - Test progress calculations
    - _Requirements: 5.1, 5.2, 5.3_

- [x] 8. Create ActivityFeedComponent (Frontend)
  - [x] 8.1 Create component with chronological event list
    - Display events in descending timestamp order
    - Limit to 100 most recent events
    - Auto-scroll to newest events
    - _Requirements: 16.1, 16.2, 16.3, 16.4_

  - [x] 8.2 Subscribe to all SSE event types
    - Convert FILE_UPDATE, LOT_UPDATE, SESSION_STATUS to activity events
    - Format descriptive messages for each event type
    - _Requirements: 16.1, 16.2, 16.5_

  - [x] 8.3 Add glassmorphism styling
    - Match existing design system
    - Support dark and light themes
    - _Requirements: Frontend Standards_

  - [x] 8.4 Write unit tests for activity feed
    - Test event ordering
    - Test size limiting (100 events)
    - _Requirements: 16.3, 16.4_

- [x] 9. Update StagingSessionService (Frontend)
  - [x] 9.1 Add event listeners for new SSE event types
    - Listen for FILE_UPDATE, FILE_UPDATES, LOT_UPDATE, SESSION_STATUS
    - Update component signals based on events
    - _Requirements: 2.2, 2.3, 2.4, 5.4_

  - [x] 9.2 Implement display status mapping
    - Map backend status to display status in UI
    - _Requirements: 12.1, 12.2, 12.3, 12.4, 12.5_

  - [x] 9.3 Add activity feed signal and methods
    - Maintain activities signal with 100-event limit
    - Convert SSE events to activity events
    - _Requirements: 16.1, 16.2, 16.3, 16.4_

  - [x] 9.4 Write unit tests for SSE event handling
    - Test event listener registration
    - Test signal updates on events
    - _Requirements: 2.2, 2.3, 2.4_

- [x] 10. Update StepperComponent Step 3 UI
  - [x] 10.1 Add LotWaferProgressComponent to template
    - Place below summary metrics
    - Pass sessionId as input
    - _Requirements: 5.1, 5.2, 5.3, 5.5_

  - [x] 10.2 Add ActivityFeedComponent to template
    - Place in sidebar or bottom section
    - Pass activities signal as input
    - _Requirements: 16.1, 16.2, 16.3, 16.4_

  - [x] 10.3 Update file list to show display status
    - Use displayStatus instead of raw status
    - Update status badge colors
    - _Requirements: 12.1, 12.2, 12.3, 12.4, 12.5_

  - [x] 10.4 Add throughput and ETA display
    - Show throughput (files/min) and ETA (minutes remaining)
    - Handle zero throughput case ("Calculating...")
    - _Requirements: 18.1, 18.2, 18.3, 18.4_

- [x] 11. Update MySessionsComponent
  - [x] 11.1 Add LotWaferProgressComponent to session detail
    - Show lot/wafer progress when session is selected
    - _Requirements: 5.1, 5.2, 5.3_

  - [x] 11.2 Add ActivityFeedComponent to session detail
    - Show activity feed for selected session
    - _Requirements: 16.1, 16.2, 16.3, 16.4_

  - [x] 11.3 Update file list to show display status
    - Use displayStatus instead of raw status
    - _Requirements: 12.1, 12.2, 12.3, 12.4, 12.5_

- [x] 12. Checkpoint - Ensure all frontend tests pass
  - Frontend enhancements complete: LotWaferProgressComponent, ActivityFeedComponent, StagingSessionService updates, StepperComponent integration

- [x] 13. Integration Testing
  - [x] 13.1 Test full flow: create → stage → monitor → complete
    - Verify all SSE events are received
    - Verify lot/wafer progress updates
    - Verify activity feed populates
    - _Requirements: 1.1, 1.3, 2.1, 2.2, 2.3, 2.4_

  - [x] 13.2 Test event batching with rapid status changes
    - Stage 100+ files
    - Verify FILE_UPDATES batching works
    - Verify no events are lost
    - _Requirements: 2.2, 4.4_

  - [x] 13.3 Test queue status visibility
    - Verify "Queued" vs "Processing" distinction
    - Test manual refresh updates display status
    - _Requirements: 3.2, 3.3, 9.1, 9.2, 9.3, 9.4_

- [x] 14. Final Checkpoint - End-to-end validation
  - All implementation tasks complete. System ready for testing.

## Notes

- Each task references specific requirements for traceability
- Checkpoints ensure incremental validation
- Property tests validate universal correctness properties
- Unit tests validate specific examples and edge cases
- Integration tests validate end-to-end flows
