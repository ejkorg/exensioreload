package com.onsemi.cim.apps.exensio.exensioreload.service;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.onsemi.cim.apps.exensio.exensioreload.dto.ExensioPreCheckRequest;
import com.onsemi.cim.apps.exensio.exensioreload.dto.ExensioPreCheckResponse;

/**
 * Caching wrapper around ExensioPreCheckService.
 * 
 * <p>Caches pre-flight verification results to avoid redundant queries when the same
 * lots are verified multiple times within a short time window.</p>
 * 
 * <p>Cache key: hash of (lotIds, waferIds, dataType, blocks). Cache TTL: 5 minutes (configurable).</p>
 * 
 * <p>Feature: lot-existence-verification, Property: Pre-Flight Result Caching</p>
 */
@Service
public class ExensioPreCheckCacheService {

    private static final Logger log = LoggerFactory.getLogger(ExensioPreCheckCacheService.class);

    private final ExensioPreCheckService preCheckService;
    private final Cache<String, ExensioPreCheckResponse> cache;

    public ExensioPreCheckCacheService(
            ExensioPreCheckService preCheckService,
            @Value("${exensio.precheck-cache-ttl-minutes:5}") int cacheTtlMinutes) {
        this.preCheckService = preCheckService;
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(cacheTtlMinutes, TimeUnit.MINUTES)
                .maximumSize(1000)
                .recordStats()
                .build();

        log.info("[ExensioPreCheckCache] Initialized with TTL={}min, maxSize=1000", cacheTtlMinutes);
    }

    /**
     * Checks lot existence, using cache when available.
     * 
     * <p>Cache hit: Returns cached result immediately (log info level).
     * Cache miss: Queries ExensioPreCheckService and caches the result.</p>
     * 
     * @param request the pre-check request
     * @return cached or fresh pre-check response
     */
    public ExensioPreCheckResponse check(ExensioPreCheckRequest request) {
        String cacheKey = buildCacheKey(request);

        // Try cache first
        ExensioPreCheckResponse cached = cache.getIfPresent(cacheKey);
        if (cached != null) {
            log.info("[ExensioPreCheckCache] Cache HIT: lots={}, wafers={}, dataType={}",
                    request.lotIds().size(),
                    request.waferIds() != null ? request.waferIds().size() : 0,
                    request.dataType());
            return cached;
        }

        // Cache miss: execute query
        log.debug("[ExensioPreCheckCache] Cache MISS: lots={}, wafers={}, dataType={} — querying...",
                request.lotIds().size(),
                request.waferIds() != null ? request.waferIds().size() : 0,
                request.dataType());

        ExensioPreCheckResponse result = preCheckService.check(request);

        // Cache the result (both success and error responses are cached to avoid repeated failures)
        cache.put(cacheKey, result);

        log.debug("[ExensioPreCheckCache] Cached result: lotsFound={}, lotsNotFound={}, error={}",
                result.lotsFound().size(),
                result.lotsNotFound().size(),
                result.error());

        return result;
    }

    /**
     * Builds a cache key from the request parameters.
     * Uses hash of lots, wafers, dataType, and date blocks for a compact key.
     */
    private String buildCacheKey(ExensioPreCheckRequest request) {
        StringBuilder sb = new StringBuilder();

        // Lots (sorted for consistency)
        if (request.lotIds() != null && !request.lotIds().isEmpty()) {
            request.lotIds().stream().sorted().forEach(l -> sb.append(l).append("|"));
        }
        sb.append(":");

        // Wafers (sorted for consistency)
        if (request.waferIds() != null && !request.waferIds().isEmpty()) {
            request.waferIds().stream().sorted().forEach(w -> sb.append(w).append("|"));
        }
        sb.append(":");

        // DataType
        sb.append(request.dataType()).append(":");

        // Date blocks (sorted for consistency)
        if (request.blocks() != null && !request.blocks().isEmpty()) {
            request.blocks().stream()
                    .sorted((a, b) -> {
                        if (!Objects.equals(a.year(), b.year())) {
                            return Integer.compare(a.year() != null ? a.year() : 0,
                                                   b.year() != null ? b.year() : 0);
                        }
                        return Integer.compare(a.month() != null ? a.month() : 0,
                                               b.month() != null ? b.month() : 0);
                    })
                    .forEach(b -> sb.append(b.year()).append("-").append(b.month()).append("|"));
        }

        // Use hash to keep key compact
        return String.valueOf(sb.toString().hashCode());
    }

    /**
     * Clears the entire cache (for testing or cache invalidation).
     */
    public void clearCache() {
        cache.invalidateAll();
        log.info("[ExensioPreCheckCache] Cache cleared");
    }

    /**
     * Returns cache statistics for monitoring.
     */
    public String getCacheStats() {
        return cache.stats().toString();
    }
}
