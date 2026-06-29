package com.onsemi.cim.apps.exensio.exensioreload.service;

import com.onsemi.cim.apps.exensio.exensioreload.config.ExternalDbConfig;
import com.onsemi.cim.apps.exensio.exensioreload.entity.ExternalLocation;
import com.onsemi.cim.apps.exensio.exensioreload.repository.ExternalLocationRepository;
import com.onsemi.cim.apps.exensio.exensioreload.repository.ExternalMetadataRepository;
import com.onsemi.cim.apps.exensio.exensioreload.repository.MetadataRow;
import com.onsemi.cim.apps.exensio.exensioreload.repository.MetadataPageResult;
import com.onsemi.cim.apps.exensio.exensioreload.repository.MetadataSummary;
import com.onsemi.cim.apps.exensio.exensioreload.stage.DuplicatePayload;
import com.onsemi.cim.apps.exensio.exensioreload.stage.PayloadCandidate;
import com.onsemi.cim.apps.exensio.exensioreload.stage.StageResult;
import com.onsemi.cim.apps.exensio.exensioreload.repository.SenderCandidate;
import com.onsemi.cim.apps.exensio.exensioreload.dto.DiscoveryPreviewResponse;
import com.onsemi.cim.apps.exensio.exensioreload.dto.DiscoveryPreviewRow;
import org.springframework.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.server.ResponseStatusException;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class MetadataImporterService {
    private final Logger log = LoggerFactory.getLogger(MetadataImporterService.class);
    private static final DateTimeFormatter FMT_MICROS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS");
    private static final DateTimeFormatter FMT_SECONDS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final LocalDateTime START_FALLBACK = LocalDateTime.of(1970, 1, 1, 0, 0);
    private static final LocalDateTime END_FALLBACK = LocalDateTime.of(2099, 12, 31, 23, 59, 59);
    // Maximum number of rows fetched from external DB for preview requests (configurable)
    @Value("${app.preview.max-rows-cap:2000}")
    private int maxPreviewRowsCap;
    @Value("${app.staging.batch-size:200}")
    private int stagingBatchSize;
    @Value("${app.staging.historical-fast-path:true}")
    private boolean historicalFastPathEnabled;
    private final ExternalDbConfig externalDbConfig;
    private final RefDbService refDbService;
    private final SenderService senderService;
    private final MailService mailService;
    private final com.onsemi.cim.apps.exensio.exensioreload.config.DiscoveryProperties discoveryProps;
    private final ExternalMetadataRepository externalMetadataRepository;
    private final ExternalLocationRepository externalLocationRepository;
    private final ExternalDbResolverService externalDbResolverService;
    private final org.springframework.core.env.Environment env;

    /**
     * Short-lived cache for preview responses to avoid redundant DB queries.
     * Cache TTL: 30 seconds, max 200 entries.
     * Cache key format: "site|env|senderId|start|end|lots|wafers|testerType|dataType|testPhase|location|page|size"
     */
    private final Cache<String, DiscoveryPreviewResponse> previewCache = Caffeine.newBuilder()
            .maximumSize(200)
            .expireAfterWrite(30, TimeUnit.SECONDS)
            .recordStats()
            .build();

    /**
     * Longer-lived cache for full discovery result sets used by bulk "Stage All" operations.
     * Cache TTL: 10 minutes, max 50 entries.
     * Cache key format: UUID Token
     */
    private final Cache<String, List<DiscoveryPreviewRow>> discoveryResultsCache = Caffeine.newBuilder()
            .maximumSize(50)
            .expireAfterWrite(10, TimeUnit.MINUTES)
            .recordStats()
            .build();

    // Map of in-flight preview requests to a per-key lock object to prevent
    // concurrent identical preview queries from triggering duplicate DB work.
    private final ConcurrentHashMap<String, Object> previewLocks = new ConcurrentHashMap<>();

    // Lightweight staging metrics for monitoring historical operations
    private final AtomicLong stagingLastDurationMs = new AtomicLong(0);
    private final AtomicLong stagingLastDiscovered = new AtomicLong(0);
    private final AtomicLong stagingLastStaged = new AtomicLong(0);
    private final AtomicLong stagingLastDuplicates = new AtomicLong(0);
    private final AtomicLong stagingLastBatches = new AtomicLong(0);

    public MetadataImporterService(ExternalDbConfig externalDbConfig,
                                   RefDbService refDbService,
                                   SenderService senderService,
                                   MailService mailService,
                                   com.onsemi.cim.apps.exensio.exensioreload.config.DiscoveryProperties discoveryProps,
                                   ExternalMetadataRepository externalMetadataRepository,
                                   ExternalLocationRepository externalLocationRepository,
                                   ExternalDbResolverService externalDbResolverService,
                                   org.springframework.core.env.Environment env) {
        this.externalDbConfig = externalDbConfig;
        this.refDbService = refDbService;
        this.senderService = senderService;
        this.mailService = mailService;
        this.discoveryProps = discoveryProps;
        this.externalMetadataRepository = externalMetadataRepository;
        this.externalLocationRepository = externalLocationRepository;
        this.externalDbResolverService = externalDbResolverService;
        this.env = env;
    }

    // Helper used by controller to find location by id
    public com.onsemi.cim.apps.exensio.exensioreload.entity.ExternalLocation findLocationById(Long id) {
        return externalLocationRepository.findById(id).orElse(null);
    }

    public java.sql.Connection resolveConnectionForLocation(com.onsemi.cim.apps.exensio.exensioreload.entity.ExternalLocation location, String environment) throws java.sql.SQLException {
        return externalDbResolverService.resolveConnectionForLocation(location, environment);
    }

    // Resolve a Connection directly by a configured connection key (db_connection_name)
    // This lets callers provide a connection key instead of a saved ExternalLocation id.
    public java.sql.Connection resolveConnectionForKey(String key, String environment) throws java.sql.SQLException {
        return externalDbConfig.getConnectionByKey(key, environment);
    }

    public java.util.List<com.onsemi.cim.apps.exensio.exensioreload.repository.SenderCandidate> findSendersWithConnection(java.sql.Connection c, String location, String dataType, String testerType, String dataTypeExt, String testPhase) {
        if (externalMetadataRepository instanceof com.onsemi.cim.apps.exensio.exensioreload.repository.JdbcExternalMetadataRepository) {
            return ((com.onsemi.cim.apps.exensio.exensioreload.repository.JdbcExternalMetadataRepository) externalMetadataRepository).findSendersWithConnection(c, location, dataType, testerType, dataTypeExt, testPhase);
        }
        throw new UnsupportedOperationException("Sender lookup only supported by JDBC implementation");
    }

    public String describeSenderLookupQueryWithConnection(java.sql.Connection c, String location, String dataType, String testerType, String dataTypeExt, String testPhase) {
        if (externalMetadataRepository instanceof com.onsemi.cim.apps.exensio.exensioreload.repository.JdbcExternalMetadataRepository) {
            return ((com.onsemi.cim.apps.exensio.exensioreload.repository.JdbcExternalMetadataRepository) externalMetadataRepository).describeSenderLookupQueryWithConnection(c, location, dataType, testerType, dataTypeExt, testPhase);
        }
        return null;
    }

    public java.util.List<SenderCandidate> findHistoricalSendersWithConnection(java.sql.Connection c, String dataType) {
        if (externalMetadataRepository instanceof com.onsemi.cim.apps.exensio.exensioreload.repository.JdbcExternalMetadataRepository) {
            return ((com.onsemi.cim.apps.exensio.exensioreload.repository.JdbcExternalMetadataRepository) externalMetadataRepository)
                    .findHistoricalSendersWithConnection(c, dataType);
        }
        throw new UnsupportedOperationException("Historical sender lookup only supported by JDBC implementation");
    }

    /**
     * Dev-only: return the describe SQL including parameter values when enabled
     * via active 'dev' profile or the property 'app.discovery.debug-sql=true'.
     * Falls back to the placeholder-only describe if debugging is not enabled or
     * when the repository implementation doesn't support the params variant.
     */
    public String describeSenderLookupQueryWithParamsWithConnection(java.sql.Connection c, String location, String dataType, String testerType, String dataTypeExt, String testPhase) {
        boolean debugProperty = Boolean.parseBoolean(org.springframework.util.StringUtils.hasText(env.getProperty("app.discovery.debug-sql")) ? env.getProperty("app.discovery.debug-sql") : "false");
        boolean devProfile = Arrays.asList(env.getActiveProfiles()).contains("dev");
        boolean enabled = debugProperty || devProfile;

        if (!enabled) {
            return describeSenderLookupQueryWithConnection(c, location, dataType, testerType, dataTypeExt, testPhase);
        }

        if (externalMetadataRepository instanceof com.onsemi.cim.apps.exensio.exensioreload.repository.JdbcExternalMetadataRepository) {
            try {
                return ((com.onsemi.cim.apps.exensio.exensioreload.repository.JdbcExternalMetadataRepository) externalMetadataRepository)
                        .describeSenderLookupQueryWithParamsWithConnection(c, location, dataType, testerType, dataTypeExt, testPhase);
            } catch (Exception ex) {
                log.warn("Failed generating param-formatted describe SQL: {}", ex.getMessage());
                return describeSenderLookupQueryWithConnection(c, location, dataType, testerType, dataTypeExt, testPhase);
            }
        }
        return describeSenderLookupQueryWithConnection(c, location, dataType, testerType, dataTypeExt, testPhase);
    }

    /**
     * Force-enabled variant: when 'force' is true and we're running with the
     * JDBC implementation, return the param-formatted SQL unconditionally. This
     * is used by controllers that implement a per-request debug toggle and want
     * to bypass the profile/property gating.
     */
    public String describeSenderLookupQueryWithParamsWithConnection(java.sql.Connection c, String location, String dataType, String testerType, String dataTypeExt, String testPhase, boolean force) {
        if (force && externalMetadataRepository instanceof com.onsemi.cim.apps.exensio.exensioreload.repository.JdbcExternalMetadataRepository) {
            try {
                return ((com.onsemi.cim.apps.exensio.exensioreload.repository.JdbcExternalMetadataRepository) externalMetadataRepository)
                        .describeSenderLookupQueryWithParamsWithConnection(c, location, dataType, testerType, dataTypeExt, testPhase);
            } catch (Exception ex) {
                log.warn("Forced param-formatted describe failed: {}", ex.getMessage());
                return describeSenderLookupQueryWithConnection(c, location, dataType, testerType, dataTypeExt, testPhase);
            }
        }
        return describeSenderLookupQueryWithParamsWithConnection(c, location, dataType, testerType, dataTypeExt, testPhase);
    }

    public java.util.List<com.onsemi.cim.apps.exensio.exensioreload.repository.SenderCandidate> findAllSendersWithConnection(java.sql.Connection c) {
        if (externalMetadataRepository instanceof com.onsemi.cim.apps.exensio.exensioreload.repository.JdbcExternalMetadataRepository) {
            return ((com.onsemi.cim.apps.exensio.exensioreload.repository.JdbcExternalMetadataRepository) externalMetadataRepository).findAllSendersWithConnection(c);
        }
        throw new UnsupportedOperationException("Sender list only supported by JDBC implementation");
    }

    // Distinct value helpers using an existing connection
    public java.util.List<String> findDistinctLocationsWithConnection(java.sql.Connection c, String dataType, String testerType, String testPhase) {
        if (externalMetadataRepository instanceof com.onsemi.cim.apps.exensio.exensioreload.repository.JdbcExternalMetadataRepository) {
            return ((com.onsemi.cim.apps.exensio.exensioreload.repository.JdbcExternalMetadataRepository) externalMetadataRepository).findDistinctLocationsWithConnection(c, dataType, testerType, testPhase);
        }
        throw new UnsupportedOperationException("Distinct locations supported only by JDBC implementation");
    }

    public java.util.List<String> findDistinctDataTypesWithConnection(java.sql.Connection c, String location, String testerType, String testPhase) {
        if (externalMetadataRepository instanceof com.onsemi.cim.apps.exensio.exensioreload.repository.JdbcExternalMetadataRepository) {
            return ((com.onsemi.cim.apps.exensio.exensioreload.repository.JdbcExternalMetadataRepository) externalMetadataRepository).findDistinctDataTypesWithConnection(c, location, testerType, testPhase);
        }
        throw new UnsupportedOperationException("Distinct data types supported only by JDBC implementation");
    }

    public java.util.List<String> findDistinctTesterTypesWithConnection(java.sql.Connection c, String location, String dataType, String testPhase) {
        if (externalMetadataRepository instanceof com.onsemi.cim.apps.exensio.exensioreload.repository.JdbcExternalMetadataRepository) {
            return ((com.onsemi.cim.apps.exensio.exensioreload.repository.JdbcExternalMetadataRepository) externalMetadataRepository).findDistinctTesterTypesWithConnection(c, location, dataType, testPhase);
        }
        throw new UnsupportedOperationException("Distinct tester types supported only by JDBC implementation");
    }

    public java.util.List<String> findDistinctDataTypeExtsWithConnection(java.sql.Connection c, String location, String dataType, String testerType) {
        if (externalMetadataRepository instanceof com.onsemi.cim.apps.exensio.exensioreload.repository.JdbcExternalMetadataRepository) {
            return ((com.onsemi.cim.apps.exensio.exensioreload.repository.JdbcExternalMetadataRepository) externalMetadataRepository).findDistinctDataTypeExtsWithConnection(c, location, dataType, testerType);
        }
        throw new UnsupportedOperationException("Distinct data type extensions supported only by JDBC implementation");
    }

    public java.util.List<String> findDistinctTestPhasesWithConnection(java.sql.Connection c, String location, String dataType, String dataTypeExt, String testerType, Integer senderId, String senderName) {
        return findDistinctTestPhasesWithConnection(c, location, dataType, dataTypeExt, testerType, senderId, senderName, false);
    }

    public java.util.List<String> findDistinctTestPhasesWithConnection(java.sql.Connection c,
                                                                       String location,
                                                                       String dataType,
                                                                       String dataTypeExt,
                                                                       String testerType,
                                                                       Integer senderId,
                                                                       String senderName,
                                                                       boolean exactTesterType) {
        if (externalMetadataRepository instanceof com.onsemi.cim.apps.exensio.exensioreload.repository.JdbcExternalMetadataRepository) {
            return ((com.onsemi.cim.apps.exensio.exensioreload.repository.JdbcExternalMetadataRepository) externalMetadataRepository)
                    .findDistinctTestPhasesWithConnection(c, location, dataType, dataTypeExt, testerType, senderId, senderName, exactTesterType);
        }
        throw new UnsupportedOperationException("Distinct test phases supported only by JDBC implementation");
    }

    public java.util.List<String> findDistinctDevicesWithConnection(java.sql.Connection c, String dataType, String testerType) {
        if (externalMetadataRepository instanceof com.onsemi.cim.apps.exensio.exensioreload.repository.JdbcExternalMetadataRepository) {
            return ((com.onsemi.cim.apps.exensio.exensioreload.repository.JdbcExternalMetadataRepository) externalMetadataRepository)
                    .findDistinctDevicesWithConnection(c, dataType, testerType);
        }
        throw new UnsupportedOperationException("Distinct devices supported only by JDBC implementation");
    }

    /**
     * Discover metadata rows from external site and enqueue into local sender queue.
     * Returns number enqueued.
     */
    public DiscoveryPreviewResponse previewMetadata(String site, String environment, Integer senderId,
                                                    String startDate, String endDate,
                                                    java.util.List<String> lots, java.util.List<String> wafers,
                                                    java.util.List<String> devices,
                                                    String testerType, String dataType, String dataTypeExt, String testPhase,
                                                    String location, Long locationId, int page, int size, boolean strictFilters, boolean bypassCap) {
        if (site == null || site.isBlank()) {
            throw new IllegalArgumentException("site is required");
        }
        if (senderId == null || senderId <= 0) {
            throw new IllegalArgumentException("senderId is required");
        }
        String resolvedEnv = (environment == null || environment.isBlank()) ? "qa" : environment;
        int resolvedSize = size <= 0 ? 50 : (bypassCap ? size : Math.min(size, maxPreviewRowsCap)); // Respect bypassCap when requested
        int resolvedPage = Math.max(page, 0);
        int offset = resolvedPage * resolvedSize;

        boolean hasLotsFilter = lots != null && lots.stream().anyMatch(v -> v != null && !v.isBlank());
        boolean hasWafersFilter = wafers != null && wafers.stream().anyMatch(v -> v != null && !v.isBlank());
        boolean hasTesterType = testerType != null && !testerType.isBlank();
        boolean hasStart = startDate != null && !startDate.isBlank();
        boolean hasEnd = endDate != null && !endDate.isBlank();

        // Require date range only when provided; otherwise rely on tester/lot/wafer filters.
        if (hasStart ^ hasEnd) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Provide both startDate and endDate together.");
        }
        if (!hasLotsFilter && !hasWafersFilter && !hasTesterType && (!hasStart || !hasEnd)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Provide at least one filter: testerType, lot/wafer, or a start/end date range.");
        }

        LocalDateTime lstart = null;
        LocalDateTime lend = null;
        if (hasStart) {
            try {
                lstart = parseDateStrict(startDate);
                lend = parseDateStrict(endDate);
            } catch (IllegalArgumentException ex) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
            }
            if (lend.isBefore(lstart)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "endDate must not be before startDate.");
            }
        }

        // Parameters validated; proceed to query

        // When strictFilters is true (e.g., historical mode), include additional filters
        // such as dataTypeExt and location in the external query.
        // Test Phase is ALWAYS applied when provided by the user (not just in strictFilters mode)
        // to respect user intent. The query remains fast because indexed columns (date range,
        // lots, wafers) are applied first, then Test Phase filters the already-narrowed result set.

        // For preview queries we traditionally omitted the `location` predicate in non-historical mode
        // to avoid performance issues on dedicated DBs.
        // We now force the location filter IF:
        // 1. strictFilters is true (Historical Mode)
        // 2. OR: We have a Managed Connection (locationId != null) AND a Date Range is provided (Safe to filter)
        boolean hasDateRange = hasStart && hasEnd;
        boolean forceLocation = (locationId != null && hasDateRange) || (site != null && site.startsWith("EXTERNAL-"));

        // Determine which filters to apply:
        // - Test Phase: Always apply when provided by user (respects user intent)
        // - dataTypeExt: Only apply in strictFilters mode
        // - location: Apply in strictFilters mode OR when forced by Managed+DateRange
        boolean hasTestPhase = testPhase != null && !testPhase.isBlank();
        String effectiveTestPhase = hasTestPhase ? testPhase : null;
        String effectiveDataTypeExt = (strictFilters ? dataTypeExt : null);
        String effectiveLocation = (strictFilters || forceLocation ? location : null);

        // Build cache key for this request (include all filters that will be used in the query)
        String cacheKey = buildPreviewCacheKey(site, resolvedEnv, senderId, lstart, lend, lots, wafers,
                testerType, /*dataType*/ dataType,
                /*dataTypeExt*/ effectiveDataTypeExt,
                /*testPhase*/ effectiveTestPhase,
                /*location*/ effectiveLocation,
                resolvedPage, resolvedSize, bypassCap);

        // Use a per-key in-flight lock so concurrent identical preview requests
        // don't run the same expensive query twice. This ensures the UI can't
        // accidentally double-trigger the preview and flood the DB.
        Object lock = previewLocks.computeIfAbsent(cacheKey, k -> new Object());
        synchronized (lock) {
            try {
                // Re-check cache inside the lock
                DiscoveryPreviewResponse cached = previewCache.getIfPresent(cacheKey);
                if (cached != null) {
                    if (log.isDebugEnabled()) {
                        log.debug("Preview cache HIT for key hash={}", cacheKey.hashCode());
                    }
                    return cached;
                }

                // Build the final SQL used for this preview (for debugging/diagnostics).
                // Note: call the repository's describePreviewQuery but suppress duplicate
                // info-logs by using the implementation that doesn't emit info-level messages.
                String debugSql = null;
                try {
                    // Describe using the effective filter set so debug SQL matches the executed query
                    if (externalMetadataRepository instanceof com.onsemi.cim.apps.exensio.exensioreload.repository.JdbcExternalMetadataRepository) {
                        debugSql = ((com.onsemi.cim.apps.exensio.exensioreload.repository.JdbcExternalMetadataRepository) externalMetadataRepository)
                                .describePreviewQuery(lstart, lend, dataType,
                                        /*dataTypeExt*/ effectiveDataTypeExt,
                                        /*testPhase*/ effectiveTestPhase,
                                        testerType,
                                        /*location*/ effectiveLocation,
                                        lots, wafers, devices, offset, resolvedSize);
                    } else {
                        debugSql = externalMetadataRepository.describePreviewQuery(lstart, lend, dataType,
                                effectiveDataTypeExt, effectiveTestPhase, testerType,
                                effectiveLocation, lots, wafers, devices, offset, resolvedSize);
                    }
                } catch (Exception ex) {
                    log.warn("Failed generating preview debug SQL: {}", ex.getMessage());
                }
                if (log.isInfoEnabled()) {
                    log.info("Preview SQL for site={} sender={} page={} size={} (strictFilters={}, testPhase={}): {}", site, senderId, resolvedPage, resolvedSize, strictFilters, effectiveTestPhase, debugSql);
                }

                // For preview we avoid the potentially expensive COUNT(*) OVER() query by
                // fetching the page of rows and only performing an explicit total count
                // when `bypassCap` is requested. This reduces DB work for the common
                // preview case.
                long queryStartNanos = System.nanoTime();
                // Execute the preview query. The query remains fast because:
                // 1. Indexed columns (date range, lots, wafers) are applied first
                // 2. Test Phase filter (when provided) is applied on the already-narrowed result set
                // 3. This ensures user-provided Test Phase is respected while maintaining performance
                List<MetadataRow> rows = externalMetadataRepository.findMetadataPage(
                        site, resolvedEnv, lstart, lend, dataType,
                        effectiveDataTypeExt,
                        effectiveTestPhase,
                        testerType,
                        effectiveLocation,
                        lots, wafers, devices, offset, resolvedSize);
                long queryDurationMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - queryStartNanos);

                long total;
                if (bypassCap) {
                    // Caller explicitly requested an accurate total — run the count query.
                    total = externalMetadataRepository.countMetadata(site, resolvedEnv, lstart, lend, dataType,
                            effectiveDataTypeExt, effectiveTestPhase, testerType,
                            effectiveLocation, lots, wafers);
                } else {
                    // Don't run a full count by default; infer whether more rows exist.
                    if (rows.size() == resolvedSize) {
                        // There may be more rows than returned; indicate that by setting
                        // total to one more than page size so UI can show "more available".
                        total = resolvedSize + 1L;
                    } else {
                        total = rows.size();
                    }
                }

                if (log.isInfoEnabled()) {
                    log.info("Preview query completed for site={} sender={} returnedRows={} total={} durationMs={}", site, senderId, rows != null ? rows.size() : 0, total, queryDurationMs);
                }

                List<DiscoveryPreviewRow> items = deduplicatePreviewRows(rows.stream()
                        .map(row -> new DiscoveryPreviewRow(
                                nullSafe(row.getId()),
                                nullSafe(row.getIdData()),
                                nullSafe(row.getLot()),
                                nullSafe(row.getWafer()),
                                nullSafe(row.getDevice()),
                                nullSafe(row.getOriginalFileName()),
                                toIsoString(row.getEndTime())
                        ))
                    .toList(), dataType);

                if (!bypassCap) {
                    if (items.size() == resolvedSize) {
                        if (total <= resolvedSize) {
                            total = items.size();
                        }
                    } else {
                        total = items.size();
                    }
                }

                if (log.isDebugEnabled()) {
                    log.debug("Preview result total={} page={} size={} returned={}", total, resolvedPage, resolvedSize, items.size());
                }

                boolean capped = !bypassCap && total > items.size();
                String message = null;
                if (total == 0) {
                    message = "No results match the current filters.";
                } else if (capped) {
                    message = "Results limited to " + items.size() + " rows. Total available: " + total + ".";
                } else if (bypassCap) {
                    message = "Bypass cap enabled — total available: " + total + ". Returning " + items.size() + " rows.";
                }

                DiscoveryPreviewResponse response = new DiscoveryPreviewResponse(items, total, items.size(), resolvedPage, resolvedSize, debugSql, capped, bypassCap, message);

                // Cache the response
                previewCache.put(cacheKey, response);

                return response;
            } finally {
                previewLocks.remove(cacheKey);
            }
        }
    }

    public MetadataSummary summarizePreview(String site, String environment, Integer senderId,
                                            String startDate, String endDate,
                                            java.util.List<String> lots, java.util.List<String> wafers,
                                            java.util.List<String> devices,
                                            String testerType, String dataType, String dataTypeExt, String testPhase, String location, boolean historicalMode) {
        if (site == null || site.isBlank()) {
            throw new IllegalArgumentException("site is required");
        }
        if (senderId == null || senderId <= 0) {
            throw new IllegalArgumentException("senderId is required");
        }
        String resolvedEnv = (environment == null || environment.isBlank()) ? "qa" : environment;

        // Reuse existing validation logic so callers must provide at least one filter
        validatePreviewRequest(site, senderId, startDate, endDate, lots, wafers, testerType);

        LocalDateTime lstart = null;
        LocalDateTime lend = null;
        boolean hasDateRange = false;
        if (startDate != null && !startDate.isBlank()) {
            lstart = parseDateStrict(startDate);
            lend = parseDateStrict(endDate);
            hasDateRange = true;
        }

        // Check if lots were provided
        boolean hasLots = lots != null && lots.stream().anyMatch(v -> v != null && !v.isBlank());

        // OPTIMIZATION FOR SUMMARY QUERIES:
        // Summary queries (count + min/max date) are fast with only indexed filters:
        // - date range (indexed on end_time)
        // - lots/wafers (indexed)
        // - dataType (used for view selection)
        //
        // For historical summaries WITHOUT lots, skip non-indexed optional filters
        // (testerType, testPhase, dataTypeExt, location) to ensure fast execution.
        // These filters will still apply during actual staging (stage-all request).
        boolean applyOptionalFiltersForSummary = false;  // NEVER apply optional filters to summary

        // Always pass dataType if provided - it's used for view selection, not just filtering
        String effectiveDataType = dataType != null && !dataType.isBlank() ? dataType : null;
        String effectiveTesterType = applyOptionalFiltersForSummary && testerType != null && !testerType.isBlank() ? testerType : null;
        String effectiveTestPhase = applyOptionalFiltersForSummary && testPhase != null && !testPhase.isBlank() ? testPhase : null;
        String effectiveDataTypeExt = applyOptionalFiltersForSummary && dataTypeExt != null && !dataTypeExt.isBlank() ? dataTypeExt : null;
        String effectiveLocation = applyOptionalFiltersForSummary && location != null && !location.isBlank() ? location : null;

        return externalMetadataRepository.summarizeMetadata(site, resolvedEnv, lstart, lend,
                effectiveDataType,
                effectiveDataTypeExt,
                effectiveTestPhase,
                effectiveTesterType,
                effectiveLocation,
                lots, wafers, devices);
    }

    /**
     * Build a cache key for preview requests.
     * Includes all parameters that affect the query result.
     */
    private String buildPreviewCacheKey(String site, String environment, Integer senderId,
                                        LocalDateTime start, LocalDateTime end,
                                        java.util.List<String> lots, java.util.List<String> wafers,
                                        String testerType, String dataType, String dataTypeExt, String testPhase,
                                        String location, int page, int size, boolean bypassCap) {
        StringBuilder sb = new StringBuilder();
        sb.append(site).append("|");
        sb.append(environment).append("|");
        sb.append(senderId).append("|");
        sb.append(start).append("|");
        sb.append(end).append("|");
        sb.append(lots != null ? String.join(",", lots.stream().filter(Objects::nonNull).toList()) : "").append("|");
        sb.append(wafers != null ? String.join(",", wafers.stream().filter(Objects::nonNull).toList()) : "").append("|");
        sb.append(nullToEmpty(testerType)).append("|");
        sb.append(nullToEmpty(dataType)).append("|");
        sb.append(nullToEmpty(dataTypeExt)).append("|");
        sb.append(nullToEmpty(testPhase)).append("|");
        sb.append(nullToEmpty(location)).append("|");
        sb.append(page).append("|");
        sb.append(size).append("|").append(bypassCap);
        return sb.toString();
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /**
     * Returns cache statistics for monitoring.
     */
    public java.util.Map<String, Object> getPreviewCacheStats() {
        var stats = previewCache.stats();
        java.util.Map<String, Object> result = new java.util.HashMap<>();
        result.put("hitCount", stats.hitCount());
        result.put("missCount", stats.missCount());
        result.put("hitRate", stats.hitRate());
        result.put("evictionCount", stats.evictionCount());
        result.put("estimatedSize", previewCache.estimatedSize());
        return result;
    }

    /**
     * Returns the preview cache for metrics binding.
     */
    public Cache<String, DiscoveryPreviewResponse> getPreviewCache() { return previewCache; }

    /**
     * Returns the discovery results cache for metrics binding.
     */
    public Cache<String, List<DiscoveryPreviewRow>> getDiscoveryResultsCache() { return discoveryResultsCache; }

    /**
     * Cache a full discovery result set and return a token to retrieve it later.
     */
    public String cacheDiscoveryResults(List<DiscoveryPreviewRow> rows) {
        if (rows == null || rows.isEmpty()) return null;
        String token = java.util.UUID.randomUUID().toString();
        putCachedDiscoveryResults(token, rows, null);
        return token;
    }

    /**
     * Cache a discovery result set under an explicitly provided token.
     */
    public void putCachedDiscoveryResults(String token, List<DiscoveryPreviewRow> rows) {
        putCachedDiscoveryResults(token, rows, null);
    }

    public void putCachedDiscoveryResults(String token, List<DiscoveryPreviewRow> rows, String dataType) {
        if (token == null || rows == null || rows.isEmpty()) return;
        rows = deduplicatePreviewRows(rows, dataType);
        discoveryResultsCache.put(token, rows);
        log.info("Discovery results cached under token={}, rows={}", token, rows.size());
    }

    /**
     * Retrieve a cached discovery result set by token.
     */
    public List<DiscoveryPreviewRow> getCachedDiscoveryResults(String token) {
        if (token == null || token.isBlank()) return null;
        List<DiscoveryPreviewRow> cached = discoveryResultsCache.getIfPresent(token);
        if (cached != null) {
            log.info("Stage-all using cached discovery results (token={}, rows={}), skipping DB re-query", token, cached.size());
        } else {
            log.info("Discovery cache miss for token={}, falling back to paginated re-query", token);
        }
        return cached;
    }

    /**
     * Invalidates the preview cache. Useful for admin operations.
     */
    public void invalidatePreviewCache() {
        previewCache.invalidateAll();
        discoveryResultsCache.invalidateAll();
        log.info("Preview and discovery caches invalidated");
    }

    /**
     * Returns last-run staging metrics for monitoring historical operations.
     */
    public java.util.Map<String, Object> getStagingStats(String requestId) {
        java.util.Map<String, Object> result = new java.util.HashMap<>();
        result.put("lastDurationMs", stagingLastDurationMs.get());
        result.put("lastDiscovered", stagingLastDiscovered.get());
        result.put("lastStaged", stagingLastStaged.get());
        result.put("lastDuplicates", stagingLastDuplicates.get());
        result.put("lastBatches", stagingLastBatches.get());
        result.put("batchSize", stagingBatchSize);
        result.put("historicalFastPathEnabled", historicalFastPathEnabled);

        // Include actual database counts if requestId is provided
        if (requestId != null && !requestId.isBlank()) {
            List<com.onsemi.cim.apps.exensio.exensioreload.stage.StageStatus> statuses = refDbService.fetchStatuses(requestId);
            result.put("sessionStatuses", statuses);

            long total = 0;
            long ready = 0;
            long enqueued = 0;
            long failed = 0;
            long completed = 0;

            for (com.onsemi.cim.apps.exensio.exensioreload.stage.StageStatus s : statuses) {
                total += s.total();
                ready += s.ready();
                enqueued += s.enqueued();
                failed += s.failed();
                completed += s.completed();
            }

            result.put("total", total);
            result.put("ready", ready);
            result.put("processing", enqueued);
            result.put("failed", failed);
            result.put("completed", completed);

            double progress = total > 0 ? ((completed + failed) * 100.0 / total) : 0;
            result.put("progress", progress);
        }

        return result;
    }

    public int discoverAndEnqueue(String site, String environment, Integer senderId, String startDate, String endDate,
                                  String testerType, String dataType, String dataTypeExt, String testPhase, String location, Long locationId, boolean writeListFile,
                                  int numberOfDataToSend, int countLimitTrigger, String requestId) {
        if (senderId == null || senderId <= 0) {
            log.warn("senderId is required to stage discovery results (site={}, environment={})", site, environment);
            return 0;
        }

        final int resolvedSenderId = senderId;
        final int batchSize = stagingBatchSize;
        final long startNanos = System.nanoTime();
        final List<PayloadCandidate> batch = new ArrayList<>(batchSize);
        Path listFilePath = null;
        final BufferedWriter[] bwRef = new BufferedWriter[1];
        final int[] discoveredCount = {0};
        final int[] stagedCount = {0};
        AtomicInteger batchCounter = new AtomicInteger(0);
        final java.util.List<DuplicatePayload> duplicatesOverall = new java.util.ArrayList<>();
        final java.util.List<String> enqueuePayloadIds = new java.util.ArrayList<>();

        try {
            if (writeListFile) {
                listFilePath = Path.of(String.format("sender_list_%s.txt", Integer.toString(resolvedSenderId)));
                bwRef[0] = Files.newBufferedWriter(listFilePath, StandardCharsets.UTF_8);
            }

            // For manual discovery calls treat empty strings as "no filter" so the
            // underlying query can avoid adding an end_time predicate when both
            // bounds are omitted.
            LocalDateTime lstart = (startDate == null || startDate.isBlank()) ? null : resolveStart(startDate);
            LocalDateTime lend = (endDate == null || endDate.isBlank()) ? null : resolveEnd(endDate);

            // Local lots/wafers lists are optional for scheduled/manual discovery.
            // Controller preview endpoints provide explicit lists; scheduled discovery
            // and other callers leave these as null.
            java.util.List<String> lots = null;
            java.util.List<String> wafers = null;

            // Pre-check external queue size via resolved external connection
            if (locationId != null) {
                ExternalLocation loc = externalLocationRepository.findById(locationId).orElse(null);
                if (loc == null) {
                    log.warn("External location id {} not found, aborting discovery", locationId);
                    return 0;
                }
                try (Connection c = externalDbResolverService.resolveConnectionForLocation(loc, environment)) {
                    String countSql = "select count(id) as count from DTP_SENDER_QUEUE_ITEM where id_sender=?";
                    try (PreparedStatement cps = c.prepareStatement(countSql)) {
                        cps.setString(1, Integer.toString(resolvedSenderId));
                        try (ResultSet crs = cps.executeQuery()) {
                            if (crs.next()) {
                                int existing = crs.getInt(1);
                                log.info("External queue size for sender {} is {}", resolvedSenderId, existing);
                                if (existing >= countLimitTrigger) {
                                    log.info("Queue above threshold ({} >= {}), skipping discovery", existing, countLimitTrigger);
                                    return 0;
                                }
                            }
                        }
                    }
                }
            } else {
                try (Connection c = externalDbConfig.getConnection(site, environment)) {
                    String countSql = "select count(id) as count from DTP_SENDER_QUEUE_ITEM where id_sender=?";
                    try (PreparedStatement cps = c.prepareStatement(countSql)) {
                        cps.setString(1, Integer.toString(resolvedSenderId));
                        try (ResultSet crs = cps.executeQuery()) {
                            if (crs.next()) {
                                int existing = crs.getInt(1);
                                log.info("External queue size for sender {} is {}", resolvedSenderId, existing);
                                if (existing >= countLimitTrigger) {
                                    log.info("Queue above threshold ({} >= {}), skipping discovery", existing, countLimitTrigger);
                                    return 0;
                                }
                            }
                        }
                    }
                }
            }

            final int maxToStage = numberOfDataToSend > 0 ? numberOfDataToSend : Integer.MAX_VALUE;

            // If lots or wafers are provided, prefer a lot/wafer-focused discovery stream.
            // Preserve dataType filter when supplied to avoid overly broad results.
            // IMPORTANT: Always preserve Test Phase when explicitly provided by the user,
            // as it represents user intent and should be respected.
            final boolean hasLotsParam = lots != null && !lots.isEmpty();
            final boolean hasWafersParam = wafers != null && !wafers.isEmpty();
            final boolean hasTestPhaseProvided = testPhase != null && !testPhase.isBlank();
            String qDataTypeParam = dataType;
            String qDataTypeExtParam = dataTypeExt;
            String qTestPhaseParam = testPhase;  // Preserve Test Phase when provided
            String qTesterTypeParam = testerType;
            String qLocationParam = location;

            boolean isExternalSite = site != null && site.startsWith("EXTERNAL-");

            if (hasLotsParam || hasWafersParam) {
                qTesterTypeParam = null;
                if (!isExternalSite) qLocationParam = null;
                qDataTypeExtParam = null;
                // Keep Test Phase if user provided it
                if (log.isDebugEnabled()) {
                    log.debug("Using lot/wafer-focused discovery stream (lotsProvided={}, wafersProvided={}) — dataType={} dataTypeExtCleared testPhase={} testerTypeCleared locationCleared", hasLotsParam, hasWafersParam, qDataTypeParam, qTestPhaseParam);
                }
            }
            // Historical fast-path: when enabled and not focusing on lots/wafers,
            // clear optional filters to leverage indexed-only predicates downstream.
            // However, preserve Test Phase when explicitly provided by the user.
            if (historicalFastPathEnabled && !hasLotsParam && !hasWafersParam) {
                qTesterTypeParam = null;
                if (!isExternalSite) qLocationParam = null;
                qDataTypeExtParam = null;
                // Only clear Test Phase if it wasn't explicitly provided by the user
                if (!hasTestPhaseProvided) {
                    qTestPhaseParam = null;
                }
                if (log.isDebugEnabled()) {
                    if (hasTestPhaseProvided) {
                        log.debug("Historical fast-path enabled — clearing optional filters (testerType, location, dataTypeExt) but preserving user-provided testPhase={} for dataType={}", qTestPhaseParam, qDataTypeParam);
                    } else {
                        log.debug("Historical fast-path enabled — clearing optional filters (testerType, location, dataTypeExt, testPhase) for efficient streaming. dataType={}", qDataTypeParam);
                    }
                }
            }

            // Capture final copy for lambda — qDataTypeParam may be reassigned below
            // Only pass dataType through (not testPhase) to preserve existing Exensio
            // lookup matching behavior — testPhase is used for PPID suffix validation
            // and changing it from null would alter the match semantics.
            String dataTypeForPayload = qDataTypeParam;

            java.util.function.Consumer<MetadataRow> processor = mr -> {
                discoveredCount[0]++;
                String metadataIdValue = mr.getId();
                String dataIdValue = mr.getIdData();
                String payload = (metadataIdValue == null ? "" : metadataIdValue) + "," + (dataIdValue == null ? "" : dataIdValue);
                if (bwRef[0] != null) {
                    try { bwRef[0].write(payload); bwRef[0].newLine(); } catch (Exception e) { log.warn("Failed writing to list file: {}", e.getMessage()); }
                }
                if (metadataIdValue == null || metadataIdValue.isBlank() || dataIdValue == null || dataIdValue.isBlank()) {
                    return;
                }
                enqueuePayloadIds.add(payload);
                java.time.Instant endTime = mr.getEndTime() == null ? null : mr.getEndTime().toInstant(java.time.ZoneOffset.UTC);
                batch.add(new PayloadCandidate(metadataIdValue, dataIdValue, mr.getLot(), mr.getWafer(), mr.getOriginalFileName(), endTime, dataTypeForPayload, null));
                if (batch.size() >= batchSize) {
                    StageResult result = stageCurrentBatch(site, resolvedSenderId, batch, requestId);
                    stagedCount[0] += result.stagedCount();
                    if (!result.duplicates().isEmpty()) {
                        duplicatesOverall.addAll(result.duplicates());
                    }
                    batchCounter.incrementAndGet();
                }
            };

            // Stream and stage
            if (locationId != null) {
                ExternalLocation loc = externalLocationRepository.findById(locationId).orElse(null);
                if (loc == null) {
                    log.warn("External location id {} not found, aborting discovery", locationId);
                } else {
                    try (Connection conn = externalDbResolverService.resolveConnectionForLocation(loc, environment)) {
                        externalMetadataRepository.streamMetadataWithConnection(conn, lstart, lend, qDataTypeParam, qDataTypeExtParam, qTestPhaseParam, qTesterTypeParam, qLocationParam, lots, wafers, maxToStage, processor);
                    }
                }
            } else {
                externalMetadataRepository.streamMetadata(site, environment, lstart, lend, qDataTypeParam, qDataTypeExtParam, qTestPhaseParam, qTesterTypeParam, qLocationParam, lots, wafers, maxToStage, processor);
            }

            boolean hadTailBatch = !batch.isEmpty();
            StageResult tail = stageCurrentBatch(site, resolvedSenderId, batch, requestId);
            stagedCount[0] += tail.stagedCount();
            if (!tail.duplicates().isEmpty()) {
                duplicatesOverall.addAll(tail.duplicates());
            }
            if (hadTailBatch) {
                batchCounter.incrementAndGet();
            }

        } catch (Exception ex) {
            log.error("Failed to discover metadata from site {}: {}", site, ex.getMessage(), ex);
            return 0;
        } finally {
            if (bwRef[0] != null) try { bwRef[0].close(); } catch (Exception ignore) {}
        }

        if (discoveredCount[0] == 0) {
            log.info("No metadata rows discovered for given criteria");
            return 0;
        }

        if (!enqueuePayloadIds.isEmpty()) {
            try {
                senderService.enqueuePayloadsWithResult(resolvedSenderId, enqueuePayloadIds, "metadata_discover");
            } catch (Exception ex) {
                log.warn("Failed enqueueing {} payloads for sender {} after discovery: {}", enqueuePayloadIds.size(), resolvedSenderId, ex.getMessage());
            }
        }

        long durationMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
        log.info("Discovered {} rows and staged {} payloads for sender {} in {} ms (batches={}). Skipped {} duplicates.", discoveredCount[0], stagedCount[0], resolvedSenderId, durationMs, batchCounter.get(), duplicatesOverall.size());

        // Update lightweight staging metrics for monitoring
        stagingLastDurationMs.set(durationMs);
        stagingLastDiscovered.set(discoveredCount[0]);
        stagingLastStaged.set(stagedCount[0]);
        stagingLastDuplicates.set(duplicatesOverall.size());
        stagingLastBatches.set(batchCounter.get());

        // Notification: prefer discovery properties, then fallback to env var
        String recipient = discoveryProps.getNotifyRecipient();
        if (recipient == null || recipient.isBlank()) {
            recipient = com.onsemi.cim.apps.exensio.exensioreload.config.ConfigUtils.getString(env, "reloader.notify-recipient", "RELOADER_NOTIFY_RECIPIENT", null);
        }
        if (recipient != null && !recipient.isBlank()) {
            String subj = String.format("Reloader: discovery complete for sender %s", resolvedSenderId);
            StringBuilder body = new StringBuilder();
            body.append(String.format("Discovered %d rows and staged %d payloads for sender %s", discoveredCount[0], stagedCount[0], resolvedSenderId));
            if (!duplicatesOverall.isEmpty()) {
                body.append(". Skipped ").append(duplicatesOverall.size()).append(" duplicate items:\n");
                int c = 0;
                for (DuplicatePayload duplicate : duplicatesOverall) {
                    if (c++ >= 50) { body.append("... (truncated)\n"); break; }
                    body.append(formatDuplicateForNotification(duplicate)).append("\n");
                }
            }
            boolean attach = discoveryProps.isNotifyAttachList();
            if (attach && listFilePath != null) {
                mailService.sendWithAttachment(recipient, subj, body.toString(), listFilePath);
            } else {
                mailService.send(recipient, subj, body.toString());
            }
        }

        return stagedCount[0];
    }

    private StageResult stageCurrentBatch(String site, int senderId, List<PayloadCandidate> batch, String requestId) {
        if (batch == null || batch.isEmpty()) {
            return StageResult.empty();
        }
        if (!batch.isEmpty()) {
            StageResult res = refDbService.stagePayloads(site, senderId, null, "metadata_discover", new ArrayList<>(batch), true, requestId);
            batch.clear();
            return res;
        }
        return StageResult.empty(); // Should not be reached if batch is empty, but for completeness
    }

    private LocalDateTime resolveStart(String value) {
        return parseDateOrDefault(value, START_FALLBACK);
    }

    private LocalDateTime resolveEnd(String value) {
        return parseDateOrDefault(value, END_FALLBACK);
    }

    private LocalDateTime parseDateOrDefault(String value, LocalDateTime fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return LocalDateTime.parse(value, FMT_MICROS);
        } catch (Exception ignore) {
            try {
                return LocalDateTime.parse(value, FMT_SECONDS);
            } catch (Exception ignored) {
                // Accept common ISO strings from HTML datetime-local inputs (e.g. "2025-12-10T14:30")
                try {
                    return LocalDateTime.parse(value, java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME);
                } catch (Exception isoIgnore) {
                    // Accept offset-based strings by dropping the offset to local time
                    try {
                        return java.time.OffsetDateTime.parse(value, java.time.format.DateTimeFormatter.ISO_DATE_TIME).toLocalDateTime();
                    } catch (Exception finalIgnore) {
                        return fallback;
                    }
                }
            }
        }
    }

    private LocalDateTime parseDateStrict(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Date value is required.");
        }

        // Try in order of specificity: micros, seconds, ISO local, ISO offset
        try {
            return LocalDateTime.parse(value, FMT_MICROS);
        } catch (Exception ignore) {}

        try {
            return LocalDateTime.parse(value, FMT_SECONDS);
        } catch (Exception ignore) {}

        try {
            return LocalDateTime.parse(value, java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        } catch (Exception ignore) {}

        try {
            return java.time.OffsetDateTime.parse(value, java.time.format.DateTimeFormatter.ISO_DATE_TIME).toLocalDateTime();
        } catch (Exception ignore) {}

        throw new IllegalArgumentException("Invalid date format: " + value + ". Expected yyyy-MM-dd HH:mm:ss[.SSSSSS], yyyy-MM-dd'T'HH:mm[:ss][.SSS], or ISO 8601 with offset.");
    }

    /**
     * Validate preview request parameters without executing the query.
     * Throws ResponseStatusException on invalid input so controllers can return 400.
     */
    public void validatePreviewRequest(String site, Integer senderId, String startDate, String endDate, java.util.List<String> lots, java.util.List<String> wafers, String testerType) {
        if (site == null || site.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "site is required");
        }
        if (senderId == null || senderId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "senderId is required");
        }
        boolean hasLotsFilter = lots != null && lots.stream().anyMatch(v -> v != null && !v.isBlank());
        boolean hasWafersFilter = wafers != null && wafers.stream().anyMatch(v -> v != null && !v.isBlank());
        boolean hasTesterType = testerType != null && !testerType.isBlank();
        boolean hasStart = startDate != null && !startDate.isBlank();
        boolean hasEnd = endDate != null && !endDate.isBlank();
        if (hasStart ^ hasEnd) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Provide both startDate and endDate together.");
        }
        boolean anyFilter = hasLotsFilter || hasWafersFilter || hasTesterType || (hasStart && hasEnd);
        if (!anyFilter) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Provide at least one filter: testerType, lot/wafer, or a start/end date range.");
        }
        if (hasStart) {
            LocalDateTime s = null;
            LocalDateTime e = null;
            try {
                s = parseDateStrict(startDate);
                e = parseDateStrict(endDate);
            } catch (IllegalArgumentException ex) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
            }
            if (e.isBefore(s)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "endDate must not be before startDate.");
            }
        }
    }

    private String toIsoString(LocalDateTime value) {
        if (value == null) {
            return null;
        }
        // Normalize to UTC so downstream parseIsoInstant can round-trip
        return value.atZone(java.time.ZoneOffset.UTC).toInstant().toString();
    }

    private String formatDuplicateForNotification(DuplicatePayload duplicate) {
        StringBuilder sb = new StringBuilder();
        sb.append(duplicate.metadataId()).append(",").append(duplicate.dataId());
        if (duplicate.previousStatus() != null && !duplicate.previousStatus().isBlank()) {
            sb.append(" status=").append(duplicate.previousStatus());
        }
        if (duplicate.previousProcessedAt() != null) {
            sb.append(" processedAt=").append(duplicate.previousProcessedAt());
        }
        return sb.toString();
    }

    private String nullSafe(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * Collapse multiple metadata ids for the same lot+wafer+filename to a single preview row.
     * Keeps the row with the latest end_time (ties broken by metadata id).
     */
    List<DiscoveryPreviewRow> deduplicatePreviewRows(List<DiscoveryPreviewRow> rows, String dataType) {
        if (rows == null || rows.size() <= 1) {
            return rows == null ? List.of() : rows;
        }

        Map<String, DiscoveryPreviewRow> best = new LinkedHashMap<>();
        for (DiscoveryPreviewRow row : rows) {
            String key = previewDedupKey(row, dataType);
            DiscoveryPreviewRow existing = best.get(key);
            if (existing == null || isNewerPreviewRow(row, existing)) {
                best.put(key, row);
            }
        }

        if (best.size() == rows.size()) {
            return rows;
        }
        if (log.isInfoEnabled()) {
            log.info("Preview rows deduplicated from {} to {}", rows.size(), best.size());
        }
        return new ArrayList<>(best.values());
    }

    private String previewDedupKey(DiscoveryPreviewRow row, String dataType) {
        String lot = nullSafe(row.lot());
        String wafer = normalizePreviewWafer(row.wafer());
        String normalizedDataType = dataType == null ? "" : dataType.trim().toUpperCase();
        if ("PCM".equals(normalizedDataType)) {
            return String.join("|",
                    lot == null ? "" : lot,
                    wafer == null ? "" : wafer,
                    "");
        }
        String filename = nullSafe(row.originalFileName());
        return String.join("|",
                lot == null ? "" : lot,
                wafer == null ? "" : wafer,
                filename == null ? "" : filename);
    }

    private String normalizePreviewWafer(String wafer) {
        String normalized = nullSafe(wafer);
        if (normalized == null || "-".equals(normalized)) {
            return null;
        }
        return normalized;
    }

    private boolean isNewerPreviewRow(DiscoveryPreviewRow candidate, DiscoveryPreviewRow existing) {
        String candidateEnd = candidate.endTime();
        String existingEnd = existing.endTime();
        if (candidateEnd != null && existingEnd != null) {
            int cmp = candidateEnd.compareTo(existingEnd);
            if (cmp != 0) {
                return cmp > 0;
            }
        } else if (candidateEnd != null) {
            return true;
        } else if (existingEnd != null) {
            return false;
        }

        String candidateId = nullSafe(candidate.metadataId());
        String existingId = nullSafe(existing.metadataId());
        if (candidateId != null && existingId != null) {
            return candidateId.compareTo(existingId) > 0;
        }
        return candidateId != null;
    }
}
