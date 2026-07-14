package com.onsemi.cim.apps.exensio.exensioreload.stage;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class StageMonitorService {
    private static final Logger log = LoggerFactory.getLogger(StageMonitorService.class);

    private final Map<String, Set<SseEmitter>> emitters = new ConcurrentHashMap<>();

    public SseEmitter createEmitter(String requestId) {
        if (requestId == null) {
            log.warn("Attempt to create SSE emitter with null requestId; returning a completed emitter");
            SseEmitter dummy = new SseEmitter(0L);
            try { dummy.complete(); } catch (Exception ignored) {}
            return dummy;
        }
        return subscribe(requestId);
    }

    public SseEmitter subscribe(String requestId) {
        if (requestId == null) {
            log.warn("Attempt to subscribe SSE with null requestId; returning a completed emitter");
            SseEmitter dummy = new SseEmitter(0L);
            try { dummy.complete(); } catch (Exception ignored) {}
            return dummy;
        }
        log.info("Creating SSE emitter for requestId: {}", requestId);
        SseEmitter emitter = new SseEmitter(30 * 60 * 1000L);
        emitters.computeIfAbsent(requestId, ignored -> ConcurrentHashMap.newKeySet()).add(emitter);

        emitter.onCompletion(() -> {
            log.info("SSE emitter completed for requestId: {}", requestId);
            removeEmitter(requestId, emitter);
        });
        emitter.onTimeout(() -> {
            log.warn("SSE emitter timeout for requestId: {}", requestId);
            removeEmitter(requestId, emitter);
        });
        emitter.onError((e) -> {
            if (isClientDisconnectError(e)) {
                log.info("SSE emitter disconnected by client for requestId: {} ({})", requestId, rootCauseMessage(e));
            } else {
                log.error("SSE emitter error for requestId: {}", requestId, e);
            }
            removeEmitter(requestId, emitter);
        });

        // Send initial events asynchronously after emitter is returned
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                // Small delay to ensure response is committed
                Thread.sleep(50);

                log.info("Sending SSE connection comment for requestId: {}", requestId);
                emitter.send(SseEmitter.event().comment("SSE connection established"));

                log.info("Sending initial HEARTBEAT for requestId: {}", requestId);
                emitter.send(SseEmitter.event().name("HEARTBEAT").data(Map.of("timestamp", Instant.now().toString(), "requestId", requestId)));

                log.info("Initial SSE events sent successfully for requestId: {}", requestId);
            } catch (Exception e) {
                if (isClientDisconnectError(e)) {
                    log.info("Client disconnected before initial SSE events completed for requestId: {} ({})", requestId, rootCauseMessage(e));
                } else {
                    log.error("Failed to send initial SSE events for requestId: {}", requestId, e);
                }
            }
        });

        log.info("SSE emitter created successfully for requestId: {}, total emitters: {}", requestId, emitters.get(requestId).size());
        return emitter;
    }

    public void sendEvent(String requestId, String type, Object payload) {
        if (requestId == null) {
            log.warn("Attempt to send SSE event '{}' with null requestId; skipping", type);
            return;
        }
        Set<SseEmitter> sessionEmitters = emitters.get(requestId);
        if (sessionEmitters == null || sessionEmitters.isEmpty()) {
            return;
        }
        Set<SseEmitter> failed = new HashSet<>();
        for (SseEmitter emitter : sessionEmitters) {
            try {
                emitter.send(SseEmitter.event().name(type).data(payload));
            } catch (Exception e) {
                if (!isClientDisconnectError(e)) {
                    log.debug("Failed SSE send for requestId: {} event: {}", requestId, type, e);
                }
                failed.add(emitter);
            }
        }
        failed.forEach(emitter -> removeEmitter(requestId, emitter));
    }

    /**
     * Broadcast a single file update event
     */
    public void broadcastFileUpdate(String sessionId, FileUpdateEvent event) {
        sendEvent(sessionId, "FILE_UPDATE", event);
    }

    /**
     * Broadcast multiple file update events as a batch
     */
    public void broadcastFileUpdates(String sessionId, List<FileUpdateEvent> events) {
        if (events == null || events.isEmpty()) {
            return;
        }
        if (events.size() == 1) {
            broadcastFileUpdate(sessionId, events.get(0));
        } else {
            sendEvent(sessionId, "FILE_UPDATES", events);
        }
    }

    /**
     * Broadcast a lot update event
     */
    public void broadcastLotUpdate(String sessionId, LotUpdateEvent event) {
        sendEvent(sessionId, "LOT_UPDATE", event);
    }

    /**
     * Broadcast a session status change event
     */
    public void broadcastSessionStatus(String sessionId, SessionStatusEvent event) {
        sendEvent(sessionId, "SESSION_STATUS", event);
    }

    /**
     * Broadcast session statistics with enhanced metrics
     */
    public void broadcastStats(String sessionId, SessionStatsEvent event) {
        sendEvent(sessionId, "STATS", event);
    }

    /**
     * Broadcast aggregated state changes to update dashboard cards in real-time
     */
    public void broadcastStateAggregation(String sessionId, StateAggregationEvent event) {
        sendEvent(sessionId, "STATE_AGGREGATION", event);
    }

    public void completeSession(String requestId) {
        sendEvent(requestId, "COMPLETE", Map.of("requestId", requestId, "completedAt", Instant.now().toString()));
        Set<SseEmitter> sessionEmitters = emitters.remove(requestId);
        if (sessionEmitters != null) {
            for (SseEmitter emitter : sessionEmitters) {
                try {
                    emitter.complete();
                } catch (Exception ignored) {
                }
            }
        }
    }

    @Scheduled(fixedDelay = 15000)
    public void sendHeartbeat() {
        String ts = Instant.now().toString();
        for (String requestId : emitters.keySet()) {
            sendEvent(requestId, "HEARTBEAT", Map.of("timestamp", ts));
        }
    }

    private void removeEmitter(String requestId, SseEmitter emitter) {
        Set<SseEmitter> sessionEmitters = emitters.get(requestId);
        if (sessionEmitters == null) {
            return;
        }
        sessionEmitters.remove(emitter);
        if (sessionEmitters.isEmpty()) {
            emitters.remove(requestId);
        }
    }

    private boolean isClientDisconnectError(Throwable throwable) {
        String message = rootCauseMessage(throwable).toLowerCase();
        return message.contains("broken pipe")
                || message.contains("connection reset")
                || message.contains("forcibly closed")
                || message.contains("connection aborted")
                || message.contains("clientabortexception");
    }

    private String rootCauseMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
