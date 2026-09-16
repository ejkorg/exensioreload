package com.onsemi.cim.apps.exensio.exensioreload.dto;

/**
 * Result of dispatching staged records to the sender queue.
 * Includes queue capacity information for UI feedback.
 */
public record DispatchResult(
        /** Number of records successfully dispatched to the queue */
        int dispatched,
        /** True when sender queue is at capacity (maxQueueSize reached) */
        boolean queueAtCapacity,
        /** Number of available slots in the sender queue (0 if at capacity) */
        int queueAvailable
) {}
