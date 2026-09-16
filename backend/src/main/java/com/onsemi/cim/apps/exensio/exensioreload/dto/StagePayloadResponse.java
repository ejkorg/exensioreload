package com.onsemi.cim.apps.exensio.exensioreload.dto;

import java.util.List;

public record StagePayloadResponse(int staged,
                                   int duplicates,
                                   List<DuplicatePayloadView> duplicatePayloads,
                                   int dispatched,
                                   boolean requiresConfirmation,
                                   long totalAvailable,
                                   boolean truncated,
                                   /** Records that already existed and were re-queued (markRetry) rather than freshly inserted */
                                   int requeued,
                                   /** True when sender queue is at capacity and cannot accept more items immediately */
                                   boolean queueAtCapacity,
                                   /** Number of available slots in the sender queue (0 if at capacity) */
                                   int queueAvailable) {}
