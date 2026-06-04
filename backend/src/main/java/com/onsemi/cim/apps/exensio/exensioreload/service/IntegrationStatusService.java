package com.onsemi.cim.apps.exensio.exensioreload.service;

import com.onsemi.cim.apps.exensio.exensioreload.config.IntegrationStatusProperties;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class IntegrationStatusService {

    public record IntegrationStatus(String status, String message, Instant at) {}

    private final ConcurrentHashMap<String, IntegrationStatus> esStatusByRequest = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, IntegrationStatus> exensioStatusByRequest = new ConcurrentHashMap<>();

    // Per-record status maps (keyed by StageRecord.id())
    private final ConcurrentHashMap<Long, IntegrationStatus> cpStatusByRecord = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, IntegrationStatus> exensioStatusByRecord = new ConcurrentHashMap<>();

    // For LRU eviction tracking
    private final ConcurrentHashMap<Long, Long> accessOrder = new ConcurrentHashMap<>();
    private final AtomicLong accessCounter = new AtomicLong(0);

    // Terminal states that trigger TTL-based eviction
    private static final List<String> TERMINAL_STATES = List.of("DONE", "FAILED", "COMPLETED", "ERROR");

    private final IntegrationStatusProperties properties;

    // Scheduled executor for periodic eviction tasks
    private final ScheduledExecutorService evictionScheduler = Executors.newSingleThreadScheduledExecutor();

    public IntegrationStatusService(IntegrationStatusProperties properties) {
        this.properties = properties;
        // Schedule periodic eviction every 5 minutes
        evictionScheduler.scheduleAtFixedRate(this::evictExpiredEntries, 5, 5, TimeUnit.MINUTES);
    }

    public void updateElasticsearch(String requestId, String status, String message) {
        if (requestId == null || requestId.isBlank()) {
            return;
        }
        esStatusByRequest.put(requestId, new IntegrationStatus(status, message, Instant.now()));
    }

    public void updateExensio(String requestId, String status, String message) {
        if (requestId == null || requestId.isBlank()) {
            return;
        }
        exensioStatusByRequest.put(requestId, new IntegrationStatus(status, message, Instant.now()));
    }

    public void updateCpStatusForRecord(long stageRecordId, String status, String message) {
        if (stageRecordId <= 0) {
            return;
        }
        cpStatusByRecord.put(stageRecordId, new IntegrationStatus(status, message, Instant.now()));
    }

    public void updateExensioStatusForRecord(long stageRecordId, String status, String message) {
        if (stageRecordId <= 0) {
            return;
        }
        exensioStatusByRecord.put(stageRecordId, new IntegrationStatus(status, message, Instant.now()));
    }

    public Map<String, Object> snapshot(String requestId, boolean esConfigured, boolean exensioConfigured) {
        Map<String, Object> result = new HashMap<>();
        result.put("elasticsearch", toMap(esStatusByRequest.get(requestId), esConfigured));
        result.put("exensio", toMap(exensioStatusByRequest.get(requestId), exensioConfigured));
        return result;
    }

    public IntegrationStatus getCpStatusForRecord(long stageRecordId) {
        if (stageRecordId <= 0) {
            return null;
        }
        recordAccess(stageRecordId);
        return cpStatusByRecord.get(stageRecordId);
    }

    public IntegrationStatus getExensioStatusForRecord(long stageRecordId) {
        if (stageRecordId <= 0) {
            return null;
        }
        recordAccess(stageRecordId);
        return exensioStatusByRecord.get(stageRecordId);
    }

    /**
     * Records access order for LRU eviction tracking.
     */
    private void recordAccess(long stageRecordId) {
        accessOrder.put(stageRecordId, accessCounter.incrementAndGet());
    }

    /**
     * Evicts entries for records in terminal states after TTL has elapsed.
     * Also evicts oldest entries when max entries limit is exceeded.
     */
    private void evictExpiredEntries() {
        long now = Instant.now().toEpochMilli();
        long ttlMillis = properties.getRecordTtlMinutes() * 60L * 1000L;
        int maxEntries = properties.getMaxEntries();

        // Evict expired entries for terminal states
        evictExpiredFromMap(cpStatusByRecord, ttlMillis, now);
        evictExpiredFromMap(exensioStatusByRecord, ttlMillis, now);

        // Evict if over max entries
        ensureMaxEntries(maxEntries);
    }

    /**
     * Evicts entries from a map if they are older than TTL.
     * Only evicts entries whose corresponding record is in a terminal state.
     */
    private void evictExpiredFromMap(ConcurrentHashMap<Long, IntegrationStatus> map, long ttlMillis, long now) {
        map.entrySet().removeIf(entry -> {
            long entryTime = entry.getValue().at().toEpochMilli();
            if (now - entryTime >= ttlMillis) {
                // Entry has expired, check if record is in terminal state
                // Note: We don't have access to the actual StageRecord here,
                // so we'll evict based on status stored in the IntegrationStatus
                String status = entry.getValue().status();
                return TERMINAL_STATES.contains(status);
            }
            return false;
        });
    }

    /**
     * Ensures the map doesn't exceed maxEntries by evicting oldest accessed entries.
     */
    private void ensureMaxEntries(int maxEntries) {
        while (cpStatusByRecord.size() > maxEntries || exensioStatusByRecord.size() > maxEntries) {
            // Find the oldest access across both maps
            Long oldestKey = null;
            Long oldestTime = Long.MAX_VALUE;

            for (Map.Entry<Long, Long> entry : accessOrder.entrySet()) {
                if (entry.getValue() < oldestTime) {
                    oldestTime = entry.getValue();
                    oldestKey = entry.getKey();
                }
            }

            if (oldestKey != null) {
                accessOrder.remove(oldestKey);
                cpStatusByRecord.remove(oldestKey);
                exensioStatusByRecord.remove(oldestKey);
            } else {
                break;
            }
        }
    }

    private Map<String, Object> toMap(IntegrationStatus status, boolean configured) {
        Map<String, Object> out = new HashMap<>();
        out.put("configured", configured);

        if (!configured) {
            out.put("status", "not_configured");
            out.put("message", "Not configured");
            out.put("lastAt", null);
            return out;
        }

        if (status == null) {
            out.put("status", "pending");
            out.put("message", "Waiting for first check");
            out.put("lastAt", null);
            return out;
        }

        out.put("status", status.status());
        out.put("message", status.message());
        out.put("lastAt", status.at() != null ? status.at().toString() : null);
        return out;
    }
}