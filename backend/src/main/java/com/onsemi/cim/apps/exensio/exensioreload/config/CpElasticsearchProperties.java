package com.onsemi.cim.apps.exensio.exensioreload.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Configuration properties for the CP Elasticsearch integration.
 * Bound from the {@code cp.elasticsearch} prefix in application.yml.
 *
 * <p>Requirements: 6.1, 6.2, 6.3, 6.4, 6.5</p>
 */
@Component
@ConfigurationProperties(prefix = "cp.elasticsearch")
public class CpElasticsearchProperties {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(CpElasticsearchProperties.class);

    /** Base URL of the Elasticsearch cluster (e.g. https://elasticsearch:9200). */
    private String url = "";

    /** API key for authentication (preferred when non-blank). */
    private String apiKey = "";

    /** Username for basic auth fallback. */
    private String username = "";

    /** Password for basic auth fallback. */
    private String password = "";

    /** ES index pattern to query for CP logs. Default: logs*dataport* */
    private String indexPattern = "logs*dataport*";

    /** cpConfig wildcard filter to isolate ExensioReload-triggered files. Default: *sender* */
    private String cpConfigFilter = "*sender*";

    /** Optional service.country filter to isolate a specific external log source. */
    private String serviceCountryFilter = "";

    /** Whether to require lot (mLot) matching in ES queries. Default: false (rely on idData only). */
    private boolean requireLot = false;

    /** Optional per-location mapping for the service-country field name in ES documents.
     * Key: upper-cased site key as found in dbconnections.yml (e.g. EXTERNAL-PROD or EXTERNAL-QA)
     * Value: the ES field name to use for the country term (e.g. service.country, service_country)
     */
    private Map<String, String> serviceCountryFieldByLocation = new HashMap<>();

    /** Polling interval in milliseconds. Default: 60 000 ms (1 minute). */
    private long pollIntervalMs = 60_000L;

    /** Timeout in minutes before a record stuck in ENRICHMENT falls through to Exensio or marked for manual verify. Default: 15 minutes. */
    private int enrichmentTimeoutMinutes = 15;

    /** Whether to log Elasticsearch request payloads (query JSON). */
    private boolean logRequestPayloads = false;

    /** Whether to log isConfigured() checks on every call. Default: false. */
    private boolean debugConfigCheck = false;

    // --- Connection & Performance Optimization ---

    /** Connection timeout in milliseconds. Default: 10 000 ms. */
    private long connectionTimeoutMs = 10_000L;

    /** Socket timeout in milliseconds. Default: 30 000 ms. */
    private long socketTimeoutMs = 30_000L;

    /** Maximum number of connections in the pool. Default: 20. */
    private int maxConnections = 20;

    /** Maximum connections per route. Default: 10. */
    private int maxConnectionsPerRoute = 10;

    /** Connection time-to-live in seconds. Default: 60s. */
    private long connectionTimeToLiveSeconds = 60L;

    /** Enable circuit breaker pattern for ES queries. Default: true. */
    private boolean enableCircuitBreaker = true;

    /** Number of consecutive failures before opening the circuit breaker. Default: 5. */
    private int circuitBreakerThreshold = 5;

    /** Time in milliseconds to wait before attempting to close the circuit breaker. Default: 60 000 ms. */
    private long circuitBreakerResetMs = 60_000L;

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url == null ? "" : url; }

    public boolean isLogRequestPayloads() { return logRequestPayloads; }
    public void setLogRequestPayloads(boolean logRequestPayloads) { this.logRequestPayloads = logRequestPayloads; }

    public boolean isDebugConfigCheck() { return debugConfigCheck; }
    public void setDebugConfigCheck(boolean debugConfigCheck) { this.debugConfigCheck = debugConfigCheck; }

    public long getConnectionTimeoutMs() { return connectionTimeoutMs; }
    public void setConnectionTimeoutMs(long connectionTimeoutMs) { this.connectionTimeoutMs = connectionTimeoutMs; }

    public long getSocketTimeoutMs() { return socketTimeoutMs; }
    public void setSocketTimeoutMs(long socketTimeoutMs) { this.socketTimeoutMs = socketTimeoutMs; }

    public int getMaxConnections() { return maxConnections; }
    public void setMaxConnections(int maxConnections) { this.maxConnections = maxConnections; }

    public int getMaxConnectionsPerRoute() { return maxConnectionsPerRoute; }
    public void setMaxConnectionsPerRoute(int maxConnectionsPerRoute) { this.maxConnectionsPerRoute = maxConnectionsPerRoute; }

    public long getConnectionTimeToLiveSeconds() { return connectionTimeToLiveSeconds; }
    public void setConnectionTimeToLiveSeconds(long connectionTimeToLiveSeconds) { this.connectionTimeToLiveSeconds = connectionTimeToLiveSeconds; }

    public boolean isEnableCircuitBreaker() { return enableCircuitBreaker; }
    public void setEnableCircuitBreaker(boolean enableCircuitBreaker) { this.enableCircuitBreaker = enableCircuitBreaker; }

    public int getCircuitBreakerThreshold() { return circuitBreakerThreshold; }
    public void setCircuitBreakerThreshold(int circuitBreakerThreshold) { this.circuitBreakerThreshold = circuitBreakerThreshold; }

    public long getCircuitBreakerResetMs() { return circuitBreakerResetMs; }
    public void setCircuitBreakerResetMs(long circuitBreakerResetMs) { this.circuitBreakerResetMs = circuitBreakerResetMs; }

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey == null ? "" : apiKey; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username == null ? "" : username; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password == null ? "" : password; }

    public String getIndexPattern() { return indexPattern; }
    public void setIndexPattern(String indexPattern) { this.indexPattern = indexPattern; }

    public String getCpConfigFilter() { return cpConfigFilter; }
    public void setCpConfigFilter(String cpConfigFilter) { this.cpConfigFilter = cpConfigFilter; }

    public String getServiceCountryFilter() { return serviceCountryFilter; }
    public void setServiceCountryFilter(String serviceCountryFilter) { this.serviceCountryFilter = serviceCountryFilter; }

    public Map<String, String> getServiceCountryFieldByLocation() { return serviceCountryFieldByLocation; }
    public void setServiceCountryFieldByLocation(Map<String, String> serviceCountryFieldByLocation) {
        this.serviceCountryFieldByLocation = serviceCountryFieldByLocation == null ? new HashMap<>() : serviceCountryFieldByLocation;
    }

    /**
     * Resolve the ES field name to use for the service-country term for a given site.
     * Falls back to "service.country" when no mapping is found.
     */
    public String resolveServiceCountryField(String site) {
        if (serviceCountryFieldByLocation == null || serviceCountryFieldByLocation.isEmpty()) {
            return "service.country";
        }
        if (site == null || site.isBlank()) return "service.country";
        String key = site.trim().toUpperCase(Locale.ROOT);
        String v = serviceCountryFieldByLocation.get(key);
        if (v != null && !v.isBlank()) return v.trim();
        // try common variants
        v = serviceCountryFieldByLocation.get(key + "-PROD");
        if (v != null && !v.isBlank()) return v.trim();
        v = serviceCountryFieldByLocation.get(key + "-QA");
        if (v != null && !v.isBlank()) return v.trim();
        return "service.country";
    }

    public long getPollIntervalMs() { return pollIntervalMs; }
    public void setPollIntervalMs(long pollIntervalMs) { this.pollIntervalMs = pollIntervalMs; }

    public int getEnrichmentTimeoutMinutes() { return enrichmentTimeoutMinutes; }
    public void setEnrichmentTimeoutMinutes(int enrichmentTimeoutMinutes) { this.enrichmentTimeoutMinutes = enrichmentTimeoutMinutes; }

    public boolean isRequireLot() { return requireLot; }
    public void setRequireLot(boolean requireLot) { this.requireLot = requireLot; }

    @jakarta.annotation.PostConstruct
    public void validate() {
        log.info("Elasticsearch Configuration: url={}, username={}, apiKey present={}, logRequestPayloads={}",
            url, username, (apiKey != null && !apiKey.isBlank()), logRequestPayloads);

        if (!isConfigured()) {
            return;
        }

        // Validate URL format
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw new IllegalArgumentException("ES URL must start with http:// or https://");
        }

        // Validate auth is provided
        boolean hasApiKey = apiKey != null && !apiKey.isBlank();
        boolean hasBasicAuth = username != null && !username.isBlank() && password != null && !password.isBlank();
        if (!hasApiKey && !hasBasicAuth) {
            log.warn("ES is configured but no authentication provided (API key or username/password). Queries may fail.");
        }

        // Validate index pattern
        if (indexPattern == null || indexPattern.isBlank()) {
            throw new IllegalArgumentException("ES index-pattern must not be blank when ES is configured");
        }

        // Validate polling and timeout values
        if (pollIntervalMs < 1) {
            throw new IllegalArgumentException("ES poll-interval-ms must be at least 1");
        }
        if (enrichmentTimeoutMinutes < 1) {
            throw new IllegalArgumentException("ES enrichment-timeout-minutes must be at least 1");
        }

        // Validate connection pool settings
        if (connectionTimeoutMs < 0) {
            throw new IllegalArgumentException("ES connection-timeout-ms must be non-negative");
        }
        if (socketTimeoutMs < 0) {
            throw new IllegalArgumentException("ES socket-timeout-ms must be non-negative");
        }
        if (maxConnections < 1) {
            throw new IllegalArgumentException("ES max-connections must be at least 1");
        }
        if (maxConnectionsPerRoute < 1) {
            throw new IllegalArgumentException("ES max-connections-per-route must be at least 1");
        }
        if (maxConnectionsPerRoute > maxConnections) {
            throw new IllegalArgumentException("ES max-connections-per-route must not exceed max-connections");
        }
        if (connectionTimeToLiveSeconds < 0) {
            throw new IllegalArgumentException("ES connection-time-to-live-seconds must be non-negative");
        }

        // Validate circuit breaker settings
        if (enableCircuitBreaker) {
            if (circuitBreakerThreshold < 1) {
                throw new IllegalArgumentException("ES circuit-breaker-threshold must be at least 1");
            }
            if (circuitBreakerResetMs < 1) {
                throw new IllegalArgumentException("ES circuit-breaker-reset-ms must be at least 1");
            }
        }

        log.info("Elasticsearch configuration validated successfully");
    }

    /** Returns true if Elasticsearch is configured (url is non-blank). */
    public boolean isConfigured() {
        boolean configured = url != null && !url.isBlank();
        if (debugConfigCheck) {
            log.debug("Elasticsearch isConfigured() = {}", configured);
        }
        return configured;
    }

    /**
     * Resolve the final Elasticsearch search URL.
     * If the configured URL already ends with /_search (or contains it), use it as-is.
     * Otherwise, append the index pattern and /_search.
     */
    public String resolveSearchUrl() {
        if (url == null) {
            return "";
        }
        String trimmed = url.trim();
        if (trimmed.isBlank()) {
            return trimmed;
        }
        String normalized = trimmed.replaceAll("/+$", "");
        if (normalized.contains("/_search")) {
            return normalized;
        }
        return normalized + "/" + indexPattern + "/_search";
    }
}
