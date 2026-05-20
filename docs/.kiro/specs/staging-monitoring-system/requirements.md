# Requirements Document

## Introduction

The DTP Resender Staging Monitoring System enables users to track the complete lifecycle of staged semiconductor test data files from initial staging through external processor consumption. The system provides real-time visibility into file status transitions, lot/wafer progress aggregation, and historical session management across 20+ manufacturing sites.

## Glossary

- **Staging_Session**: A persistent record of a user's file staging operation, containing metadata (user, site, sender, timestamps) and aggregated progress counters
- **SENDER_STAGE**: Oracle RefDB table storing individual staged file records with status (NEW, ENQUEUED, DONE, FAILED)
- **External_Processor**: External system that consumes files from DTP_SENDER_QUEUE_ITEM
- **SSE**: Server-Sent Events protocol for real-time server-to-client push notifications
- **Request_ID**: UUID linking staged files to their parent staging session
- **Lot**: Semiconductor manufacturing lot identifier grouping multiple wafers
- **Wafer**: Individual silicon wafer within a lot
- **RefDB**: Reference database (Oracle) storing staging metadata and session records
- **DTP_SENDER_QUEUE_ITEM**: External Oracle table where staged files are enqueued for processor consumption

## Requirements

### Requirement 1: Session Creation and Lifecycle Management

**User Story:** As a user staging files, I want the system to create a persistent session before staging begins, so that all my staged files are tracked together with proper metadata.

#### Acceptance Criteria

1. WHEN a user initiates file staging, THE System SHALL create a staging_session record with a unique UUID identifier before any files are staged
2. WHEN creating a session, THE System SHALL capture username, site, sender_id, sender_name, environment, and creation timestamp
3. WHEN files are staged, THE System SHALL associate each SENDER_STAGE record with the session's UUID via the request_id field
4. WHEN all files in a session reach terminal status (DONE or FAILED), THE System SHALL transition the session status to COMPLETED or PARTIALLY_FAILED
5. WHEN a user cancels a session, THE System SHALL mark remaining NEW files as CANCELLED and update the session status to CANCELLED

### Requirement 2: Real-Time Progress Monitoring via SSE

**User Story:** As a user monitoring staged files, I want real-time updates pushed to my browser, so that I can see progress without manual refreshing.

#### Acceptance Criteria

1. WHEN a user connects to a session monitor endpoint, THE System SHALL establish an SSE connection and send initial session state
2. WHEN a file's status changes, THE System SHALL broadcast a FILE_UPDATE event to all connected clients for that session
3. WHEN session counters change, THE System SHALL broadcast a STATS event containing total, staged, enqueued, done, failed counts and progress percentage
4. WHEN a session completes, THE System SHALL broadcast a SESSION_STATUS event with terminal status and close the SSE connection
5. WHEN an SSE connection is idle, THE System SHALL send a HEARTBEAT event every 15 seconds to maintain connection liveness
6. WHEN multiple browser tabs connect to the same session, THE System SHALL maintain separate SSE emitters and broadcast events to all

### Requirement 3: External Queue Status Detection

**User Story:** As a user monitoring files, I want to distinguish between files waiting in queue versus files being processed, so that I understand where delays occur.

#### Acceptance Criteria

1. WHEN a file is marked ENQUEUED in SENDER_STAGE, THE System SHALL check if the corresponding record exists in DTP_SENDER_QUEUE_ITEM
2. WHEN an ENQUEUED file exists in DTP_SENDER_QUEUE_ITEM, THE System SHALL report status as "Queued"
3. WHEN an ENQUEUED file does NOT exist in DTP_SENDER_QUEUE_ITEM, THE System SHALL report status as "Processing"
4. WHEN the scheduled monitor detects a file no longer in DTP_SENDER_QUEUE_ITEM, THE System SHALL transition the file status to DONE
5. WHEN a user requests on-demand refresh, THE System SHALL immediately check external queue status and update file statuses

### Requirement 4: Completion Detection with Reduced Latency

**User Story:** As a user monitoring files, I want completion detection within 30 seconds, so that I receive timely feedback on processing status.

#### Acceptance Criteria

1. WHEN the queue monitor runs, THE System SHALL execute every 30 seconds (not 120 seconds)
2. WHEN processing ENQUEUED records, THE System SHALL page through ALL records for active sessions (not just 200)
3. WHEN a file completes, THE System SHALL update SENDER_STAGE status to DONE within one monitor cycle
4. WHEN a file completes, THE System SHALL broadcast FILE_UPDATE and STATS events via SSE immediately after status update
5. WHEN all files in a session complete, THE System SHALL broadcast SESSION_STATUS event with COMPLETED status

### Requirement 5: Lot and Wafer Progress Aggregation

**User Story:** As a user monitoring large staging sessions, I want to see progress grouped by lot and wafer, so that I can quickly assess completion at a higher level than individual files.

#### Acceptance Criteria

1. WHEN a user requests lot/wafer progress, THE System SHALL aggregate files by lot identifier and wafer identifier
2. WHEN displaying lot progress, THE System SHALL show total wafers, completed wafers, and failed wafers per lot
3. WHEN displaying wafer progress, THE System SHALL show file count and completion percentage per wafer
4. WHEN a lot's aggregate status changes, THE System SHALL broadcast a LOT_UPDATE event via SSE
5. WHEN rendering lot/wafer hierarchy, THE System SHALL support collapsible lot groups with expandable wafer details

### Requirement 6: Session History and Retrieval

**User Story:** As a user, I want to view all my past staging sessions, so that I can review historical operations and their outcomes.

#### Acceptance Criteria

1. WHEN a user requests session history, THE System SHALL return all sessions for that username ordered by creation timestamp descending
2. WHEN displaying session list, THE System SHALL show session ID, site, sender, total files, progress percentage, and status
3. WHEN a user selects a historical session, THE System SHALL retrieve full session detail with current file counts
4. WHEN viewing a completed session, THE System SHALL display final state without establishing new SSE connection
5. WHEN viewing an active session from history, THE System SHALL reconnect SSE for live updates

### Requirement 7: Paginated File Listing for Large Sessions

**User Story:** As a user with sessions containing 1000+ files, I want paginated file lists, so that the UI remains responsive.

#### Acceptance Criteria

1. WHEN a session contains more than 100 files, THE System SHALL provide paginated file listing with configurable page size
2. WHEN requesting a file page, THE System SHALL support filtering by status (NEW, ENQUEUED, DONE, FAILED)
3. WHEN requesting a file page, THE System SHALL support text search across lot, wafer, and filename fields
4. WHEN rendering file lists, THE System SHALL use virtual scrolling for smooth performance with large datasets
5. WHEN exporting session files, THE System SHALL generate CSV containing all files regardless of pagination

### Requirement 8: Session Cancellation

**User Story:** As a user, I want to cancel an in-progress staging session, so that I can stop processing files that are no longer needed.

#### Acceptance Criteria

1. WHEN a user cancels a session, THE System SHALL mark all NEW status files as CANCELLED
2. WHEN a user cancels a session, THE System SHALL NOT modify ENQUEUED, DONE, or FAILED files
3. WHEN a session is cancelled, THE System SHALL update the session status to CANCELLED
4. WHEN a session is cancelled, THE System SHALL broadcast SESSION_STATUS event with CANCELLED status
5. WHEN a cancelled session has remaining ENQUEUED files, THE System SHALL continue monitoring those files until completion

### Requirement 9: On-Demand External Status Refresh

**User Story:** As a user, I want to manually refresh external queue status, so that I can get immediate updates without waiting for the scheduled monitor cycle.

#### Acceptance Criteria

1. WHEN a user triggers manual refresh, THE System SHALL query DTP_SENDER_QUEUE_ITEM for all ENQUEUED files in the session
2. WHEN manual refresh detects completed files, THE System SHALL update their status to DONE immediately
3. WHEN manual refresh completes, THE System SHALL return updated session detail with new counters
4. WHEN manual refresh completes, THE System SHALL broadcast STATS event via SSE
5. WHEN manual refresh is triggered, THE System SHALL update the session's last_checked_at timestamp

### Requirement 10: SSE Reconnection and Recovery

**User Story:** As a user monitoring a session, I want automatic reconnection if my network drops, so that I don't lose progress visibility.

#### Acceptance Criteria

1. WHEN an SSE connection drops, THE System SHALL attempt reconnection with exponential backoff (1s, 2s, 4s, 8s, max 30s)
2. WHEN reconnecting, THE System SHALL fetch full session snapshot via HTTP to recover any missed events
3. WHEN a session reaches terminal status, THE System SHALL send final events and close the SSE connection gracefully
4. WHEN a client reconnects to a completed session, THE System SHALL send SESSION_STATUS event with terminal status and close immediately
5. WHEN SSE is unavailable, THE System SHALL fall back to HTTP polling every 3 seconds

### Requirement 11: Session Counter Accuracy

**User Story:** As a user, I want accurate file counts at all times, so that progress indicators reflect true system state.

#### Acceptance Criteria

1. WHEN session counters are queried, THE System SHALL recount from SENDER_STAGE WHERE request_id matches session ID
2. WHEN a file status changes, THE System SHALL update the corresponding session counter atomically
3. WHEN displaying progress percentage, THE System SHALL calculate as (files_done + files_failed) / total_files * 100
4. WHEN a session is created, THE System SHALL initialize all counters to zero
5. WHEN files are staged, THE System SHALL increment total_files and files_staged counters

### Requirement 12: Status Mapping Consistency

**User Story:** As a user, I want consistent status terminology across backend and frontend, so that I'm not confused by mismatched labels.

#### Acceptance Criteria

1. WHEN the backend writes DONE status, THE Frontend SHALL display "Completed"
2. WHEN the backend writes NEW status, THE Frontend SHALL display "Staged"
3. WHEN the backend writes ENQUEUED status and file exists in external queue, THE Frontend SHALL display "Queued"
4. WHEN the backend writes ENQUEUED status and file does NOT exist in external queue, THE Frontend SHALL display "Processing"
5. WHEN the backend writes FAILED status, THE Frontend SHALL display "Failed"

### Requirement 13: Database Performance Optimization

**User Story:** As a system administrator, I want efficient database queries for monitoring operations, so that the system scales to thousands of concurrent files.

#### Acceptance Criteria

1. WHEN querying files by session and status, THE System SHALL use a composite index on (request_id, status)
2. WHEN counting files per session, THE System SHALL use indexed queries avoiding full table scans
3. WHEN aggregating lot/wafer progress, THE System SHALL use GROUP BY queries with WHERE request_id filter
4. WHEN the queue monitor processes records, THE System SHALL page through results in batches of 500
5. WHEN checking external queue, THE System SHALL fetch all keys for a site/sender in a single query

### Requirement 14: Multi-Session Concurrent Monitoring

**User Story:** As a system supporting multiple users, I want each session to have isolated SSE streams, so that users only receive events for their own sessions.

#### Acceptance Criteria

1. WHEN multiple sessions are active, THE System SHALL maintain separate SSE emitter sets per session ID
2. WHEN broadcasting events, THE System SHALL only send to emitters registered for the target session
3. WHEN a session completes, THE System SHALL close only that session's emitters without affecting others
4. WHEN the heartbeat runs, THE System SHALL iterate all active sessions and send heartbeats to each
5. WHEN an emitter fails, THE System SHALL remove it from the session's emitter set without affecting other emitters

### Requirement 15: Session Status Transitions

**User Story:** As a user, I want clear session status progression, so that I understand what phase my staging operation is in.

#### Acceptance Criteria

1. WHEN a session is created, THE System SHALL set status to STAGING
2. WHEN all files are staged and dispatch begins, THE System SHALL transition status to DISPATCHING
3. WHEN all files are enqueued and awaiting external processing, THE System SHALL transition status to MONITORING
4. WHEN all files reach DONE status, THE System SHALL transition status to COMPLETED
5. WHEN some files are DONE and some are FAILED, THE System SHALL transition status to PARTIALLY_FAILED

### Requirement 16: Activity Feed Event Logging

**User Story:** As a user monitoring a session, I want a chronological activity feed, so that I can see what happened and when.

#### Acceptance Criteria

1. WHEN a file status changes, THE System SHALL add an activity event with timestamp, file identifier, old status, and new status
2. WHEN a session status changes, THE System SHALL add an activity event with timestamp and new session status
3. WHEN displaying the activity feed, THE System SHALL show most recent events first
4. WHEN the activity feed exceeds 100 events, THE System SHALL retain only the most recent 100
5. WHEN broadcasting activity events via SSE, THE System SHALL include event type, timestamp, and descriptive message

### Requirement 17: CSV Export for Session Files

**User Story:** As a user, I want to export session files to CSV, so that I can analyze data in external tools.

#### Acceptance Criteria

1. WHEN a user requests CSV export, THE System SHALL generate a file containing all session files regardless of pagination
2. WHEN generating CSV, THE System SHALL include columns: metadata_id, data_id, lot, wafer, filename, status, created_at, updated_at
3. WHEN generating CSV, THE System SHALL use proper escaping for fields containing commas or quotes
4. WHEN CSV export completes, THE System SHALL return the file with Content-Type text/csv and appropriate filename
5. WHEN exporting large sessions (1000+ files), THE System SHALL stream the CSV to avoid memory exhaustion

### Requirement 18: Throughput and ETA Calculation

**User Story:** As a user monitoring a session, I want to see estimated completion time, so that I can plan my workflow.

#### Acceptance Criteria

1. WHEN calculating throughput, THE System SHALL measure files completed per minute over the last 5 minutes
2. WHEN calculating ETA, THE System SHALL divide remaining files by current throughput
3. WHEN throughput is zero, THE System SHALL display "Calculating..." instead of infinite ETA
4. WHEN a session completes, THE System SHALL display actual completion time instead of ETA
5. WHEN broadcasting STATS events, THE System SHALL include throughput (files/min) and ETA (minutes remaining)

### Requirement 19: Backward Compatibility with Legacy Data

**User Story:** As a system administrator, I want the new monitoring system to handle legacy staged files, so that existing data remains accessible.

#### Acceptance Criteria

1. WHEN a SENDER_STAGE record has a request_id with no matching staging_session, THE System SHALL display file data without session metadata
2. WHEN querying files without a session, THE System SHALL support filtering by site and sender as fallback
3. WHEN displaying legacy files, THE System SHALL show "Unknown Session" in the session field
4. WHEN a legacy file completes, THE System SHALL update its status normally
5. WHEN migrating to the new system, THE System SHALL NOT require backfilling staging_session records for historical data

### Requirement 20: Error Handling and Resilience

**User Story:** As a user, I want the monitoring system to handle errors gracefully, so that temporary failures don't break my session tracking.

#### Acceptance Criteria

1. WHEN an SSE emitter fails, THE System SHALL log the error and remove the emitter without crashing the monitor service
2. WHEN external queue queries fail, THE System SHALL log the error and retry on the next monitor cycle
3. WHEN session counter updates fail, THE System SHALL log the error and continue processing other sessions
4. WHEN a database connection is lost, THE System SHALL attempt reconnection with exponential backoff
5. WHEN the frontend loses SSE connection, THE System SHALL display a "Reconnecting..." indicator and attempt automatic recovery
