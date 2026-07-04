package com.onsemi.cim.apps.exensio.exensioreload.stage;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Event representing aggregated state changes across multiple records.
 * Broadcast via SSE to keep dashboard cards in sync with database state changes.
 *
 * This event batches multiple state transitions into a single update,
 * reducing SSE traffic and improving real-time dashboard accuracy.
 */
public record StateAggregationEvent(
        Instant timestamp,
        List<StateChange> changes,
        Map<String, Long> totals,
        String requestId
) {
    /**
     * Represents a single state change in the aggregation.
     * Shows previous and new counts for a specific state.
     */
    public record StateChange(
            String state,
            long previousCount,
            long newCount
    ) {}
}
