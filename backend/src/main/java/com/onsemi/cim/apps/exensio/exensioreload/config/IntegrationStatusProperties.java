package com.onsemi.cim.apps.exensio.exensioreload.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for per-record integration status tracking.
 * Controls TTL and memory management for the IntegrationStatusService.
 */
@Component
@ConfigurationProperties(prefix = "app.integration.status")
public class IntegrationStatusProperties {

    /**
     * Minutes to retain per-file integration status entries for records in terminal states
     * (DONE, FAILED, COMPLETED, ERROR) before eviction.
     * Default: 120 minutes (2 hours).
     */
    private int recordTtlMinutes = 120;

    /**
     * Maximum number of per-file status entries to retain in memory.
     * When exceeded, oldest entries are evicted (LRU policy).
     * Default: 50000 entries.
     */
    private int maxEntries = 50000;

    public int getRecordTtlMinutes() {
        return recordTtlMinutes;
    }

    public void setRecordTtlMinutes(int recordTtlMinutes) {
        if (recordTtlMinutes < 1) {
            throw new IllegalArgumentException("recordTtlMinutes must be at least 1");
        }
        this.recordTtlMinutes = recordTtlMinutes;
    }

    public int getMaxEntries() {
        return maxEntries;
    }

    public void setMaxEntries(int maxEntries) {
        if (maxEntries < 1000) {
            throw new IllegalArgumentException("maxEntries must be at least 1000");
        }
        this.maxEntries = maxEntries;
    }
}