# Implementation Plan: Per-File Status Details

## Overview

This feature adds a rich inline detail line beneath each filename in the monitoring file list, showing a compact pipeline summary without requiring row expansion. The implementation focuses on the `RealtimeMonitoringFileListComponent` (used in the monitoring UI) and `StagingSessionService` (for SSE handling and activity events).

## Tasks

- [x] 1. Update StagingSessionService to handle integration status fields in ROW_UPDATE events
  - [x] 1.1 Enhance updateFileInList() to copy cpIntegrationStatus, cpIntegrationMessage, exensioIntegrationStatus, exensioIntegrationMessage fields
  - _Requirements: 1.1, 1.2, 1.4, 2.1-2.7, 3.1-3.4, 4.1-4.6, 5.1-5.5, 7.1-7.5_

  - [x] 1.2 Enhance updateFilesInListBatch() to copy integration status fields in batch updates
  - _Requirements: 1.1, 1.2, 1.4, 2.1-2.7, 3.1-3.4, 4.1-4.6, 5.1-5.5, 7.1-7.5_

- [x] 2. Add terminal activity message generation to StagingSessionService
  - [x] 2.1 Implement buildAndPushTerminalActivityMessage() method that creates activity events for COMPLETED files
    - _Requirements: 6.1, 6.3, 6.4, 6.5_
  - [x] 2.2 Implement buildAndPushTerminalActivityMessage() method that creates activity events for ERROR files
    - _Requirements: 6.2, 6.5_

- [x] 3. Enhance ROW_UPDATE handler to push activity messages for terminal files
  - [x] 3.1 When ROW_UPDATE sets status to COMPLETED, call buildAndPushTerminalActivityMessage() with proper format
    - _Requirements: 6.1, 6.3, 6.4, 6.5_
  - [x] 3.2 When ROW_UPDATE sets status to ERROR, call buildAndPushTerminalActivityMessage() with error format
    - _Requirements: 6.2, 6.5_

- [x] 4. Create detail line rendering methods in RealtimeMonitoringFileListComponent
  - [x] 4.1 Implement getEnrichmentSegment() method to return enrichment stage text based on cpIntegrationStatus
    - _Requirements: 2.1-2.7_
  - [x] 4.2 Implement getExensioSegment() method to return Exensio stage text based on exensioIntegrationStatus
    - _Requirements: 4.1-4.6_
  - [x] 4.3 Implement getOutputTargetBadge() method to return PRODUCTION/SANDBOX/UNKNOWN badge text
    - _Requirements: 3.1-3.4_
  - [x] 4.4 Implement getErrorSummary() method to return truncated error message (120 chars) with tooltip support
    - _Requirements: 5.1-5.5_
  - [x] 4.5 Implement getDetailLine() method to combine all segments into the final detail line string
    - _Requirements: 1.1, 1.2, 2.1-2.7, 3.1-3.4, 4.1-4.6, 5.1-5.5, 7.1-7.5_

- [x] 5. Update RealtimeMonitoringFileListComponent template to render detail lines
  - [x] 5.1 Add detail line div beneath filename in each file row (always visible, inside existing row element)
    - _Requirements: 1.1, 1.2_
  - [x] 5.2 Add CSS styling for detail line (0.75rem font, muted color, ellipsis overflow)
    - _Requirements: 1.1, 1.2_
  - [x] 5.3 Add segment icons and badges styling (enrichment, exensio, output target)
    - _Requirements: 2.1-2.7, 3.1-3.4, 4.1-4.6_
  - [x] 5.4 Add tooltip support for truncated error messages using GlassTooltipDirective
    - _Requirements: 5.5_

- [x] 6. Add detail line status-specific rendering logic
  - [x] 6.1 For READY/ENQUEUED files, show "Queued" label
    - _Requirements: 7.1_
  - [x] 6.2 For ENRICHMENT files with pending status, show "Enrichment: In Progress"
    - _Requirements: 7.2_
  - [x] 6.3 For EXENSIO_LOADING files with pending status, show "Enrichment: Done · Exensio: Loading"
    - _Requirements: 7.3_
  - [x] 6.4 For COMPLETED files, show full pipeline summary
    - _Requirements: 7.4_
  - [x] 6.5 For ERROR files, show error message per priority order
    - _Requirements: 7.5_

- [x] 7. Add placeholder handling for files with no integration data
  - [x] 7.1 When all integration fields are null/empty, show muted "Waiting..." placeholder
    - _Requirements: 1.3_

- [x] 8. Verify integration with existing error details panel
  - [x] 8.1 Ensure detail line and expanded error details panel work together without conflicts
    - _Requirements: 1.1, 1.2_

- [x] 9. Checkpoint - Ensure all code compiles and integrates properly
  - No test execution available in this environment. Verify syntax and type compatibility manually.

## Notes

- All changes are additive; no existing functionality is removed or changed
- Detail line is always visible, no expand click required
- SSE updates update detail line in-place without full list re-render
- Activity feed messages include pipeline summary for terminal files
- Error messages truncated to 120 chars with tooltip for full text
- All integration status values supported: success, pending, failure, timeout, not_found, error, not_configured
