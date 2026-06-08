package com.onsemi.cim.apps.exensio.exensioreload.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.onsemi.cim.apps.exensio.exensioreload.service.ExensioLoadMonitor;
import com.onsemi.cim.apps.exensio.exensioreload.service.MetadataImporterService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;

/**
 * Binds Caffeine cache statistics to Micrometer for Prometheus scraping.
 *
 * <p>Registers gauges for hit rate, miss rate, eviction count, and estimated size
 * on all managed caches.</p>
 */
@Configuration
public class MetricsConfig {

    private static final Logger log = LoggerFactory.getLogger(MetricsConfig.class);

    private final MeterRegistry registry;
    private final ExensioLoadMonitor exensioLoadMonitor;
    private final MetadataImporterService metadataImporterService;

    public MetricsConfig(MeterRegistry registry,
                         ExensioLoadMonitor exensioLoadMonitor,
                         MetadataImporterService metadataImporterService) {
        this.registry = registry;
        this.exensioLoadMonitor = exensioLoadMonitor;
        this.metadataImporterService = metadataImporterService;
    }

    @PostConstruct
    public void bindCacheMetrics() {
        // Exensio lookup cache
        Cache<?, ?> exensioCache = exensioLoadMonitor.getLookupCache();
        if (exensioCache != null) {
            CaffeineCacheMetrics.monitor(registry, exensioCache, "exensio.lookupCache");
            log.info("Bound Micrometer metrics for exensio.lookupCache");
        }

        // Discovery preview cache
        Cache<?, ?> previewCache = metadataImporterService.getPreviewCache();
        if (previewCache != null) {
            CaffeineCacheMetrics.monitor(registry, previewCache, "discovery.previewCache");
            log.info("Bound Micrometer metrics for discovery.previewCache");
        }

        // Discovery results cache
        Cache<?, ?> resultsCache = metadataImporterService.getDiscoveryResultsCache();
        if (resultsCache != null) {
            CaffeineCacheMetrics.monitor(registry, resultsCache, "discovery.resultsCache");
            log.info("Bound Micrometer metrics for discovery.resultsCache");
        }
    }
}
