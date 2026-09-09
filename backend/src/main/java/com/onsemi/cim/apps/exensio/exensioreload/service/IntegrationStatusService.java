package com.onsemi.cim.apps.exensio.exensioreload.service;

import com.onsemi.cim.apps.exensio.exensioreload.config.IntegrationStatusProperties;
import org.springframework.boot.actuate.health.Health;
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
    private static final List<String> TERMINAL_STATES = List.of("success", "failure", "error");

    private final IntegrationStatusProperties properties;

    // Health indicators for real connection checks
    private final ElasticsearchHealthIndicator elasticsearchHealthIndicator;
    private final ExensioHealthIndicator exensioHealthIndicator;

    // Scheduled executor for periodic eviction tasks
    private final ScheduledExecutorService evictionScheduler = Executors.newSingleThreadScheduledExecutor();

    public IntegrationStatusService(IntegrationStatusProperties properties,
                                    ElasticsearchHealthIndicator elasticsearchHealthIndicator,
                                    ExensioHealthIndicator exensioHealthIndicator) {
        this.properties = properties;
        this.elasticsearchHealthIndicator = elasticsearchHealthIndicator;
        this.exensioHealthIndicator = exensioHealthIndicator;
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
        return snapshot(requestId, esConfigured, exensioConfigured, 0, 0, 0, 0, 0, 0);
    }

    public Map<String, Object> snapshot(
            String requestId,
            boolean esConfigured,
            boolean exensioConfigured,
            long stagedCount,
            long queuedCount,
            long enrichingCount,
            long exensioCount,
            long completedCount,
            long failedCount) {
        Map<String, Object> result = new HashMap<>();
        // Handle null requestId (e.g., when called without an active session)
        IntegrationStatus esStatus = requestId != null ? esStatusByRequest.get(requestId) : null;
        IntegrationStatus exStatus = requestId != null ? exensioStatusByRequest.get(requestId) : null;
        result.put("elasticsearch", toEsMap(esStatus, esConfigured, stagedCount, queuedCount, enrichingCount, exensioCount, completedCount, failedCount));
        result.put("exensio", toExensioMap(exStatus, exensioConfigured, stagedCount, queuedCount, enrichingCount, exensioCount, completedCount, failedCount));
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
     *
     * logic updated:
     * - If the status is NOT terminal (e.g. 'not_found', 'pending', 'error'),
     *   evict it after the standard TTL to clean up records that disappeared.
     * - If the status IS terminal ('COMPLETED', 'COMPLETED'), we preserve it much longer
     *   (10x TTL) so the user can see the final result after the session ends.
     */
    private void evictExpiredFromMap(ConcurrentHashMap<Long, IntegrationStatus> map, long ttlMillis, long now) {
        map.entrySet().removeIf(entry -> {
            long entryTime = entry.getValue().at().toEpochMilli();
            String status = entry.getValue().status();
            boolean isTerminal = TERMINAL_STATES.contains(status);

            // Terminal states are preserved 10x longer than transient states
            long effectiveTtl = isTerminal ? ttlMillis * 10 : ttlMillis;

            return (now - entryTime >= effectiveTtl);
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

    private Map<String, Object> toEsMap(
            IntegrationStatus status,
            boolean configured,
            long stagedCount,
            long queuedCount,
            long enrichingCount,
            long exensioCount,
            long completedCount,
            long failedCount) {
        Map<String, Object> out = new HashMap<>();
        out.put("configured", configured);

        if (!configured) {
            out.put("status", "not_configured");
            out.put("message", "Not configured");
            out.put("lastAt", null);
            return out;
        }

        if (status != null) {
            out.put("status", status.status());
            out.put("message", status.message());
            out.put("lastAt", status.at() != null ? status.at().toString() : null);
            return out;
        }

        // Status is null in memory (e.g. after backend restart or before first poll)
        // Infer from record / session stage progression:
        if (enrichingCount == 0 && (exensioCount > 0 || completedCount > 0)) {
            // Records have already passed Elasticsearch and progressed to Exensio or Completed
            out.put("status", "success");
            out.put("message", "Completed");
            out.put("lastAt", null);
            return out;
        }

        if (enrichingCount > 0) {
            out.put("status", "pending");
            out.put("message", "Monitoring Elasticsearch logs");
            out.put("lastAt", null);
            return out;
        }

        if (queuedCount > 0) {
            out.put("status", "pending");
            out.put("message", "Waiting for CP dispatch");
            out.put("lastAt", null);
            return out;
        }

        if (stagedCount > 0) {
            out.put("status", "pending");
            out.put("message", "Waiting for staging/dispatch");
            out.put("lastAt", null);
            return out;
        }

        if (failedCount > 0 && (stagedCount + queuedCount + enrichingCount + exensioCount + completedCount == 0)) {
            out.put("status", "failure");
            out.put("message", "Failed");
            out.put("lastAt", null);
            return out;
        }

        // No records processing and no in-memory status - perform real health check
        return checkElasticsearchHealth();
    }

    private Map<String, Object> checkElasticsearchHealth() {
        Map<String, Object> out = new HashMap<>();
        out.put("configured", true);
        try {
            Health health = elasticsearchHealthIndicator.health();
            boolean isUp = health.getStatus() != null && "UP".equals(health.getStatus().getCode());
            if (isUp) {
                out.put("status", "success");
                out.put("message", "Connected");
            } else {
                out.put("status", "failure");
                out.put("message", "Connection failed");
            }
            out.put("lastAt", Instant.now().toString());
        } catch (Exception e) {
            out.put("status", "failure");
            out.put("message", "Connection error: " + e.getMessage());
            out.put("lastAt", Instant.now().toString());
        }
        return out;
    }

    private Map<String, Object> toExensioMap(
            IntegrationStatus status,
            boolean configured,
            long stagedCount,
            long queuedCount,
            long enrichingCount,
            long exensioCount,
            long completedCount,
            long failedCount) {
        Map<String, Object> out = new HashMap<>();
        out.put("configured", configured);

        if (!configured) {
            out.put("status", "not_configured");
            out.put("message", "Not configured");
            out.put("lastAt", null);
            return out;
        }

        if (status != null) {
            out.put("status", status.status());
            out.put("message", status.message());
            out.put("lastAt", status.at() != null ? status.at().toString() : null);
            return out;
        }

        // Status is null in memory
        if (completedCount > 0 && exensioCount == 0 && enrichingCount == 0 && queuedCount == 0 && stagedCount == 0) {
            out.put("status", "success");
            out.put("message", "Completed");
            out.put("lastAt", null);
            return out;
        }

        if (exensioCount > 0) {
            out.put("status", "pending");
            out.put("message", "Monitoring Exensio load");
            out.put("lastAt", null);
            return out;
        }

        if (enrichingCount > 0 || queuedCount > 0 || stagedCount > 0) {
            out.put("status", "pending");
            out.put("message", "Awaiting previous stages");
            out.put("lastAt", null);
            return out;
        }

        if (failedCount > 0 && (stagedCount + queuedCount + enrichingCount + exensioCount + completedCount == 0)) {
            out.put("status", "failure");
            out.put("message", "Failed");
            out.put("lastAt", null);
            return out;
        }

        // No records processing and no in-memory status - perform real health check
        return checkExensioHealth();
    }

    private Map<String, Object> checkExensioHealth() {
        Map<String, Object> out = new HashMap<>();
        out.put("configured", true);
        try {
            Health health = exensioHealthIndicator.health();
            boolean isUp = health.getStatus() != null && "UP".equals(health.getStatus().getCode());
            if (isUp) {
                out.put("status", "success");
                out.put("message", "Connected");
            } else {
                out.put("status", "failure");
                out.put("message", "Connection failed");
            }
            out.put("lastAt", Instant.now().toString());
        } catch (Exception e) {
            out.put("status", "failure");
            out.put("message", "Connection error: " + e.getMessage());
            out.put("lastAt", Instant.now().toString());
        }
        return out;
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