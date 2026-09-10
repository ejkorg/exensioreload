package com.onsemi.cim.apps.exensio.exensioreload.pipeline;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.stats.CacheStats;

/**
 * Cache for pipeline configurations per site using Caffeine.
 * 
 * Caches parsed pipeline configurations to avoid repeated YAML parsing.
 * Configuration can be invalidated manually when dbconnections.yml is reloaded.
 * 
 * Requirements: 12.2
 */
@Component
public class PipelineConfigCache {

    private static final Logger log = LoggerFactory.getLogger(PipelineConfigCache.class);

    private final PipelineConfigLoader configLoader;
    private final long expireAfterMinutes;
    private final Cache<String, Optional<PipelineConfig>> cache;

    /**
     * Create a new PipelineConfigCache with configurable expiration time.
     * 
     * @param configLoader the PipelineConfigLoader to load configs on cache miss
     * @param expireAfterMinutes cache expiration time in minutes (from application.yml)
     */
    public PipelineConfigCache(
            PipelineConfigLoader configLoader,
            @Value("${pipeline.cache.expire-after-minutes:5}") long expireAfterMinutes) {
        
        Objects.requireNonNull(configLoader, "configLoader must not be null");
        
        this.configLoader = configLoader;
        this.expireAfterMinutes = Math.max(1, expireAfterMinutes); // Ensure at least 1 minute
        
        // Build Caffeine cache with expiration
        this.cache = Caffeine.newBuilder()
            .expireAfterWrite(this.expireAfterMinutes, TimeUnit.MINUTES)
            .build();
        
        log.info("PipelineConfigCache initialized with {} minute expiration", this.expireAfterMinutes);
    }

    /**
     * Get pipeline configuration for a site, loading on cache miss.
     * 
     * Returns Optional.empty() if site has no pipeline configuration.
     * Caches the result (including empty) to avoid repeated parsing.
     * 
     * Requirements: 12.2
     * 
     * @param site the site name (e.g., "CEBU-PROD")
     * @return Optional containing PipelineConfig if pipeline is configured for this site
     * @throws PipelineConfigException if configuration is invalid
     */
    public Optional<PipelineConfig> getConfig(String site) throws PipelineConfigException {
        if (site == null || site.isBlank()) {
            return Optional.empty();
        }

        String normalizedSite = site.trim().toUpperCase();
        
        // Cache the Optional to avoid repeated parsing even when no config exists
        Optional<PipelineConfig> config = cache.get(normalizedSite, key -> {
            log.debug("Loading pipeline configuration for site: {}", key);
            return configLoader.loadPipelineConfig(key);
        });
        
        return config;
    }

    /**
     * Invalidate entire cache when dbconnections.yml is reloaded.
     * 
     * Requirements: 12.2
     */
    public void invalidateAll() {
        log.info("Invalidating all cached pipeline configurations");
        cache.invalidateAll();
    }

    /**
     * Invalidate cache for a specific site.
     * 
     * @param site the site name to invalidate
     */
    public void invalidate(String site) {
        if (site == null || site.isBlank()) {
            return;
        }
        
        String normalizedSite = site.trim().toUpperCase();
        log.debug("Invalidating cached pipeline configuration for site: {}", normalizedSite);
        cache.invalidate(normalizedSite);
    }

    /**
     * Get cache statistics for monitoring.
     * 
     * @return cache statistics
     */
    public CacheStats stats() {
        return cache.stats();
    }

    /**
     * Get the configured expiration time in minutes.
     * 
     * @return expiration time in minutes
     */
    public long getExpireAfterMinutes() {
        return expireAfterMinutes;
    }
}
