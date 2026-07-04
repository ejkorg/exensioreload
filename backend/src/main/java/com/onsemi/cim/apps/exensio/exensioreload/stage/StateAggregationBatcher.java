package com.onsemi.cim.apps.exensio.exensioreload.stage;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Batches STATE_AGGREGATION events to reduce SSE traffic during rapid state changes.
 * Collects state changes over a 1-second window and broadcasts a single aggregated event.
 *
 * This dramatically reduces message volume during bulk operations (e.g., bulk cancel, bulk enqueue).
 * Instead of sending ~1000 messages/sec, this batches them into ~1 message/sec.
 */
@Component
public class StateAggregationBatcher {
    private static final Logger log = LoggerFactory.getLogger(StateAggregationBatcher.class);
    private static final long BATCH_DELAY_MS = 1000;  // 1-second batching window

    private final Map<String, StateChangeAccumulator> pendingAggregations = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "StateAggregationBatcher");
        thread.setDaemon(true);
        return thread;
    });
    private final StageMonitorService monitorService;

    public StateAggregationBatcher(StageMonitorService monitorService) {
        this.monitorService = monitorService;
        startBatchProcessor();
    }

    /**
     * Record a state change for a session.
     * Changes are accumulated and broadcast together in a batched event.
     *
     * @param sessionId the session/request ID to track
     * @param state the record state that changed
     * @param previousCount the count before this change
     * @param newCount the count after this change
     */
    public void recordStateChange(String sessionId, String state, long previousCount, long newCount) {
        StateChangeAccumulator accumulator = pendingAggregations.computeIfAbsent(sessionId, k -> {
            log.debug("Starting new state aggregation batch for sessionId: {}", sessionId);
            return new StateChangeAccumulator(sessionId);
        });

        accumulator.recordChange(state, newCount);
    }

    /**
     * Start the batch processor that flushes aggregations every 1 second
     */
    private void startBatchProcessor() {
        scheduler.scheduleAtFixedRate(() -> {
            try {
                for (String sessionId : new ArrayList<>(pendingAggregations.keySet())) {
                    flushBatch(sessionId);
                }
            } catch (Exception e) {
                log.error("Error flushing state aggregation batches", e);
            }
        }, BATCH_DELAY_MS, BATCH_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * Flush pending state aggregations for a session
     */
    private void flushBatch(String sessionId) {
        StateChangeAccumulator accumulator = pendingAggregations.remove(sessionId);
        if (accumulator == null || accumulator.isEmpty()) {
            return;
        }

        StateAggregationEvent event = accumulator.buildEvent();
        log.debug("Broadcasting state aggregation for sessionId: {}, changes: {}", 
            sessionId, event.changes().size());

        monitorService.broadcastStateAggregation(sessionId, event);
    }

    /**
     * Shutdown the batch processor
     */
    public void shutdown() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Accumulates state changes for a session over the batching window.
     * Tracks previous and current counts for each state.
     */
    private static class StateChangeAccumulator {
        private final String sessionId;
        private final Map<String, Long> previousCounts = new ConcurrentHashMap<>();
        private final Map<String, Long> currentCounts = new ConcurrentHashMap<>();

        StateChangeAccumulator(String sessionId) {
            this.sessionId = sessionId;
        }

        void recordChange(String state, long newCount) {
            // On first record for this state, store the previous count
            previousCounts.computeIfAbsent(state, k -> newCount);
            // Always update current count
            currentCounts.put(state, newCount);
        }

        boolean isEmpty() {
            return currentCounts.isEmpty();
        }

        StateAggregationEvent buildEvent() {
            List<StateAggregationEvent.StateChange> changes = new ArrayList<>();

            for (String state : currentCounts.keySet()) {
                long prev = previousCounts.get(state);
                long current = currentCounts.get(state);
                if (prev != current) {
                    changes.add(new StateAggregationEvent.StateChange(state, prev, current));
                }
            }

            // If no actual changes (shouldn't happen), return empty changes list
            if (changes.isEmpty()) {
                log.debug("No actual state changes in batch for sessionId: {}", sessionId);
            }

            return new StateAggregationEvent(
                    Instant.now(),
                    changes,
                    Map.copyOf(currentCounts),
                    sessionId
            );
        }
    }
}
