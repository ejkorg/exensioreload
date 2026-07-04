# Monitor Page State Accounting Improvements

## Introduction

The monitor page dashboard currently displays five metric cards (Staged, In Queue, Enrichment/Translation, Completed, Failed) that do not account for all records in the system. Analysis shows ~73% of records (3304 out of 4544) are missing from the visible totals, primarily because records in CANCELLED state are invisible and state transitions are not fully represented in the UI.

This feature aims to close accounting gaps and provide complete visibility into record state distribution across the entire pipeline.

## Glossary

- **SENDER_STAGE**: Primary staging database table tracking record processing status
- **Record State**: Current status value of a record in SENDER_STAGE (pending, ENRICHMENT, DONE, etc.)
- **Display Status**: User-facing label shown in dashboard (different from DB status due to external queue logic)
- **External Queue**: DTP_SENDER_QUEUE_ITEM table tracking records currently in CP processing
- **Monitor Page**: Dashboard showing real-time aggregated metrics for staged payloads
- **Accounting Gap**: Records in valid states but not represented in any dashboard metric card
- **CANCELLED**: Soft-delete state for records marked paused or deleted by bulk operations
- **EXENSIO_LOADING**: State indicating record is undergoing Exensio verification/enrichment
- **Pipeline State**: Complete set of possible record transitions from staging through completion

## Requirements

### Requirement 1: Add Visibility for Cancelled Records

**User Story:** As a system monitor, I want to see how many records have been paused or deleted, so that I understand the full accounting of staged payloads and can distinguish active from inactive records.

#### Acceptance Criteria

1. WHEN the monitor page loads, THE Dashboard SHALL display a new card showing count of CANCELLED records
2. WHEN records are bulk-paused or deleted via DashboardBulkController, THE CANCELLED card SHALL update in real-time via SSE
3. WHEN a user views the dashboard, THE sum of all metric cards (Staged + In Queue + Enrichment + Completed + Failed + Cancelled) SHALL equal or exceed the Total Files count
4. WHEN filtering by sender or site, THE CANCELLED count SHALL be scoped to that sender/site
5. WHEN no CANCELLED records exist, THE CANCELLED card SHALL display 0 (not be hidden)

### Requirement 2: Explicit State Breakdown Query

**User Story:** As a database analyst, I want to query the complete state distribution of records, so that I can verify all records are in valid states and identify data integrity issues.

#### Acceptance Criteria

1. WHEN a debug endpoint is called, THE System SHALL return counts of all possible record states (pending, ENQUEUED, ENRICHMENT, EXENSIO_LOADING, PROCESSING, DONE, FAILED, CANCELLED, UNKNOWN)
2. WHEN records exist in UNKNOWN or NULL status, THE System SHALL flag them as data integrity issues
3. WHEN summing all returned states, THE total SHALL equal the COUNT(\*) from SENDER_STAGE
4. WHEN the endpoint returns state distribution, THE response SHALL include counts per site and sender_id

### Requirement 3: Distinguish EXENSIO_LOADING From ENRICHMENT

**User Story:** As an operations engineer, I want to see how many records are in Exensio verification separately from CP enrichment, so that I can monitor the Exensio pipeline independently.

#### Acceptance Criteria

1. WHEN the monitor page loads and Exensio is configured, THE Dashboard SHALL display EXENSIO_LOADING records in a distinct card (not grouped with ENRICHMENT)
2. WHEN records transition from ENRICHMENT to EXENSIO_LOADING, THE count in Enrichment card SHALL decrease and Exensio card SHALL increase
3. WHEN Exensio verification completes, THE record SHALL transition to DONE and the Exensio card SHALL decrease
4. WHEN Exensio is not configured, THE EXENSIO_LOADING card SHALL not appear (or display 0)
5. WHEN SSE events broadcast status changes, EXENSIO_LOADING transitions SHALL trigger real-time updates to the Exensio card

### Requirement 4: Add Timeout/Stuck Records Indicator

**User Story:** As a system operator, I want to be alerted to records stuck in enrichment beyond the timeout threshold, so that I can investigate and remediate processing delays.

#### Acceptance Criteria

1. WHEN a record remains in ENRICHMENT status for longer than the enrichment timeout (default 5 minutes), THE System SHALL track it as "stuck"
2. WHEN viewing the monitor page, THE Dashboard SHALL display a card or badge showing count of stuck records
3. WHEN stuck records are automatically resolved (marked DONE with manual-verify), THE stuck count SHALL decrease
4. WHEN a stuck record is detected, THE System SHALL emit an alert via SSE with details (record_id, lot, duration_in_enrichment)
5. WHEN the timeout threshold is configurable, CHANGING the threshold value SHALL NOT retroactively change past stuck statuses

### Requirement 5: Complete Pipeline State Transparency

**User Story:** As a dashboard user, I want to see all possible record states and transitions in one place, so that I understand the complete flow and can identify where records may be bottlenecked.

#### Acceptance Criteria

1. THE Dashboard SHALL display at least 7 distinct metric cards covering: Staged, Queued, Enriching, Exensio Loading, Completed, Failed, Cancelled
2. WHEN any card is clicked, THE System SHALL show a detail view or sidebar with sample records in that state
3. WHEN multiple states are related (e.g., ENRICHMENT can transition to DONE or FAILED), THE Dashboard UI SHALL indicate possible next states
4. WHEN a record is in an unexpected or invalid state, THE System SHALL flag it with a warning indicator
5. THE Dashboard SHALL include a legend explaining each state and its meaning (tooltip or info panel)

### Requirement 6: Accounting Verification Endpoint

**User Story:** As a system administrator, I want to verify that dashboard accounting is correct and identify any missing records, so that I can detect data integrity issues early.

#### Acceptance Criteria

1. WHEN an admin calls a verification endpoint, THE System SHALL return:
   - Total records in database
   - Count per state (pending, ENQUEUED, ENRICHMENT, EXENSIO_LOADING, FAILED, DONE, CANCELLED, UNKNOWN)
   - Sum of all states
   - Discrepancies (records in states not covered by dashboard cards)
2. WHEN discrepancies exist, THE response SHALL include list of sample record IDs and their states
3. WHEN the endpoint is called, THE System SHALL also check for:
   - Records with NULL status
   - Records with invalid status values
   - Records in CANCELLED but still referenced in external queue
4. WHEN results show imbalance (sum of states < total), THE response SHALL highlight which states are missing counts
5. THE Endpoint SHALL support filtering by request_id, site, sender_id for targeted verification

### Requirement 7: Real-Time State Aggregation Updates

**User Story:** As a monitor, I want all dashboard cards to update in real-time when any record changes state, so that the displayed metrics are always current and accurate.

#### Acceptance Criteria

1. WHEN a record status changes, THE System SHALL broadcast an aggregation update event via SSE
2. WHEN the aggregation update is received, THE affected dashboard card SHALL update to reflect the new count
3. WHEN multiple status changes occur rapidly, THE System SHALL batch aggregation updates to avoid excessive redraws
4. WHEN a user is viewing the dashboard and statuses change, THE displayed totals SHALL never show partial/inconsistent state
5. WHEN SSE connection drops and reconnects, THE dashboard SHALL refresh card counts to ensure accuracy

### Requirement 8: Data Integrity Checks

**User Story:** As a data steward, I want automated checks to ensure all records are in valid states and no orphaned records exist, so that data quality is maintained.

#### Acceptance Criteria

1. WHEN a scheduled job runs (default: hourly), THE System SHALL verify:
   - All records in SENDER_STAGE have valid status values
   - No records have NULL status
   - Records in ENRICHMENT/EXENSIO_LOADING have corresponding entries or are aged (exceeding timeout)
2. IF invalid records are found, THE System SHALL log them and emit an alert
3. WHEN records in ENRICHMENT exceed timeout without transition, THE System SHALL attempt auto-remediation (mark DONE with manual-verify)
4. WHEN auto-remediation occurs, THE System SHALL log the action with details
5. THE scheduled job SHALL report results via dashboard alert or admin email

---

## Supplementary Notes

### Related Components

- `StatusMapper.getDisplayStatus()` — Maps DB status to display labels
- `RefDbService.fetchStatuses()` — Aggregation query for dashboard metrics
- `StageMonitorService` — SSE broadcast for real-time updates
- `CpLogMonitor` — Monitors enrichment timeouts and transitions
- `DashboardController.snapshot()` — Main dashboard endpoint

### Current Gaps

- CANCELLED records: invisible in all cards
- EXENSIO_LOADING: grouped with ENRICHMENT, not separately tracked
- Timeout/stuck records: logic exists but not visualized
- NULL/UNKNOWN records: not validated or tracked
- Real-time aggregation: updates are per-record, not aggregated by card

### Configuration Points

- Enrichment timeout: configurable in `CpElasticsearchProperties.enrichmentTimeoutMinutes` (default: 5)
- Exensio enabled: configurable in `ExensioProperties.enabled`
- SSE update batching: can be controlled in `EventBatcher`

---

## Acceptance Criteria Quality Review

✅ All criteria are testable via property-based testing
✅ Criteria follow EARS patterns (WHEN/THE/SHALL)
✅ No vague terms ("quickly", "efficiently")
✅ All criteria are measurable and specific
✅ No escape clauses ("where possible", "if feasible")
✅ Solution-free (focus on what, not how)
