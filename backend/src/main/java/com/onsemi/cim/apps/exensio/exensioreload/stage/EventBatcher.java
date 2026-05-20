package com.onsemi.cim.apps.exensio.exensioreload.stage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * @author fg8n8x
 */

/**
 * Batches FILE_UPDATE events to reduce SSE traffic during rapid status changes
 */
@Component
public class EventBatcher {
    private static final Logger log = LoggerFactory.getLogger(EventBatcher.class);
    private static final int BATCH_DELAY_MS = 500;
    private static final int MAX_BATCH_SIZE = 50;

    private final Map<String, List<FileUpdateEvent>> pendingEvents = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final StageMonitorService monitorService;

    public EventBatcher(StageMonitorService monitorService) {
        this.monitorService = monitorService;
        startBatchProcessor();
    }

    /**
     * Add a file update event to the batch for a session
     */
    public void addFileUpdate(String sessionId, FileUpdateEvent event) {
        pendingEvents.computeIfAbsent(sessionId, k -> new ArrayList<>()).add(event);

        // If batch is full, flush immediately
        List<FileUpdateEvent> batch = pendingEvents.get(sessionId);
        if (batch != null && batch.size() >= MAX_BATCH_SIZE) {
            flushBatch(sessionId);
        }
    }

    /**
     * Start the batch processor that flushes events every 500ms
     */
    private void startBatchProcessor() {
        scheduler.scheduleAtFixedRate(() -> {
            try {
                for (String sessionId : pendingEvents.keySet()) {
                    flushBatch(sessionId);
                }
            } catch (Exception e) {
                log.error("Error flushing event batches", e);
            }
        }, BATCH_DELAY_MS, BATCH_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * Flush pending events for a session
     */
    private void flushBatch(String sessionId) {
        List<FileUpdateEvent> batch = pendingEvents.remove(sessionId);
        if (batch == null || batch.isEmpty()) {
            return;
        }

        if (batch.size() == 1) {
            // Single event - send as FILE_UPDATE
            monitorService.sendEvent(sessionId, "FILE_UPDATE", batch.get(0));
        } else {
            // Multiple events - send as FILE_UPDATES array
            monitorService.sendEvent(sessionId, "FILE_UPDATES", batch);
        }
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
}

