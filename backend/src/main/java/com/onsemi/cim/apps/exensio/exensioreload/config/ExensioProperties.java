package com.onsemi.cim.apps.exensio.exensioreload.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for the Exensio Loading API integration.
 * Bound from the {@code exensio} prefix in application.yml.
 *
 * When {@code enabled} is false (default) the ExensioLoadMonitor is a no-op.
 * {@link com.onsemi.cim.apps.exensio.exensioreload.service.StagePipelinePolicy} then
 * completes records at CP queue consumption (if ES is also off) or after ES success.
 */
@Component
@ConfigurationProperties(prefix = "exensio")
public class ExensioProperties {

    private final CpElasticsearchProperties esProps;

    /** Master switch. Set EXENSIO_ENABLED=true to activate the monitor. */
    private boolean enabled = false;

    /** Target environment: QA or PROD. Controls which base URL is used. */
    private String env = "QA";

    private String qaBaseUrl = "";
    private String prodBaseUrl = "";

    /** Credentials for POST /v1/session/login */
    private String username = "";
    private String password = "";

    /**
     * dbname passed to the login body (e.g. "QA" or "PROD").
     * Defaults to the value of {@code env} if not explicitly set.
     */
    private String dbname = "";

    /**
     * dbschema passed to the login body.
     * Always derived automatically based on configuration and environment.
     * (Kept for backward compatibility but not used; automatic detection always applies.)
     */
    private String dbschema = "";

    /** How often ExensioLoadMonitor polls EXENSIO_LOADING records (ms). */
    private long pollIntervalMs = 60_000L;

    /** Whether to log Exensio API request URLs and payloads. */
    private boolean logRequestPayloads = false;

    /**
     * Minutes a record may stay in EXENSIO_LOADING before being marked FAILED.
     * Starts counting from when the record entered EXENSIO_LOADING (updatedAt).
     */
    private int timeoutMinutes = 60;

    // --- Batch & Parallel Processing Optimization Properties ---

    /**
     * Number of lot/wafer combinations to include in a single batch API request.
     * Range: 1-100. Default: 50.
     * When set to 1, records are processed individually (backward compatible).
     */
    private int batchSize = 50;

    /**
     * Number of threads in the thread pool for concurrent batch processing.
     * Range: 1-20. Default: 5.
     * When set to 1, batches are processed sequentially (backward compatible).
     */
    private int threadPoolSize = 5;

    /**
     * Maximum number of concurrent API requests to Exensio server.
     * Range: 1-50. Default: 10.
     * Used to prevent overwhelming the Exensio server with too many concurrent requests.
     */
    private int maxConcurrentRequests = 10;

    /**
     * Enable circuit breaker pattern to prevent cascading failures.
     * Default: true.
     */
    private boolean enableCircuitBreaker = true;

    /**
     * Number of consecutive failures before opening the circuit breaker.
     * Range: 1-100. Default: 5.
     */
    private int circuitBreakerThreshold = 5;

    /**
     * Time in milliseconds to wait before attempting to close the circuit breaker.
     * Range: 10000-300000. Default: 60000 (1 minute).
     */
    private long circuitBreakerResetMs = 60_000L;

    /**
     * Prefer Exensio raw SQL endpoint before standard lot/wafer lookup.
     */
    private boolean preferRawSql = true;

    /**
     * Timeout in seconds for raw SQL endpoint calls.
     */
    private int rawSqlTimeoutSeconds = 20;

    /**
     * Upper bound on rows returned by generated raw SQL queries.
     */
    private int rawSqlRowLimit = 200;

    // --- constructor ---

    public ExensioProperties(CpElasticsearchProperties esProps) {
        this.esProps = esProps;
    }

    // --- validation ---

    @jakarta.annotation.PostConstruct
    public void validate() {
        if (batchSize < 1 || batchSize > 100) {
            throw new IllegalArgumentException("exensio.batchSize must be between 1 and 100");
        }
        if (threadPoolSize < 1 || threadPoolSize > 20) {
            throw new IllegalArgumentException("exensio.threadPoolSize must be between 1 and 20");
        }
        if (maxConcurrentRequests < 1 || maxConcurrentRequests > 50) {
            throw new IllegalArgumentException("exensio.maxConcurrentRequests must be between 1 and 50");
        }
        if (circuitBreakerThreshold < 1 || circuitBreakerThreshold > 100) {
            throw new IllegalArgumentException("exensio.circuitBreakerThreshold must be between 1 and 100");
        }
        if (circuitBreakerResetMs < 10_000 || circuitBreakerResetMs > 300_000) {
            throw new IllegalArgumentException("exensio.circuitBreakerResetMs must be between 10000 and 300000");
        }
        if (rawSqlTimeoutSeconds < 5 || rawSqlTimeoutSeconds > 120) {
            throw new IllegalArgumentException("exensio.rawSqlTimeoutSeconds must be between 5 and 120");
        }
        if (rawSqlRowLimit < 10 || rawSqlRowLimit > 5000) {
            throw new IllegalArgumentException("exensio.rawSqlRowLimit must be between 10 and 5000");
        }
    }

    // --- derived helpers ---

    /** Returns true when the integration is enabled and a base URL is configured. */
    public boolean isConfigured() {
        return enabled && resolvedBaseUrl() != null && !resolvedBaseUrl().isBlank();
    }

    /** Returns the base URL for the active environment. */
    public String resolvedBaseUrl() {
        return "PROD".equalsIgnoreCase(env) ? prodBaseUrl : qaBaseUrl;
    }

    /** Returns the effective dbname (falls back to env value). */
    public String resolvedDbname() {
        return (dbname != null && !dbname.isBlank()) ? dbname : env;
    }

    /** Returns the primary schema to use, always derived from configuration (no manual override). */
    public String resolvedDbschema() {
        boolean esConfigured = esProps.isConfigured();
        boolean exensioConfigured = isConfigured();
        
        // Case 1: Both ES and Exensio configured → use environment-based detection
        if (esConfigured && exensioConfigured) {
            if ("PROD".equalsIgnoreCase(env) || "PRD".equalsIgnoreCase(env)) {
                return "PRODUCTION";
            } else if ("SBX".equalsIgnoreCase(env) || "SANDBOX".equalsIgnoreCase(env)) {
                return "SANDBOX";
            }
            return "PRODUCTION";
        }
        
        // Case 2: Only Exensio configured or neither configured → default to PRODUCTION (will fallback to SANDBOX if needed)
        return "PRODUCTION";
    }

    /** Returns the fallback schema to try if primary schema fails. Always provided except when both ES and Exensio use environment-based detection. */
    public String resolvedDbschemaFallback() {
        boolean esConfigured = esProps.isConfigured();
        boolean exensioConfigured = isConfigured();
        
        // No fallback when Exensio is not configured
        if (!exensioConfigured) {
            return null;
        }
        
        // No fallback when both ES and Exensio are configured (environment determines single schema)
        if (esConfigured && exensioConfigured) {
            return null;
        }
        
        // In all other cases where Exensio is configured, provide SANDBOX fallback
        return "SANDBOX";
    }

    // --- getters / setters ---

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getEnv() { return env; }
    public void setEnv(String env) { this.env = env; }

    public String getQaBaseUrl() { return qaBaseUrl; }
    public void setQaBaseUrl(String qaBaseUrl) { this.qaBaseUrl = qaBaseUrl == null ? "" : qaBaseUrl; }

    public String getProdBaseUrl() { return prodBaseUrl; }
    public void setProdBaseUrl(String prodBaseUrl) { this.prodBaseUrl = prodBaseUrl == null ? "" : prodBaseUrl; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username == null ? "" : username; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password == null ? "" : password; }

    public String getDbname() { return dbname; }
    public void setDbname(String dbname) { this.dbname = dbname == null ? "" : dbname; }

    public String getDbschema() { return dbschema; }
    public void setDbschema(String dbschema) { 
        // Ignored - schema is always auto-detected. Kept for backward compatibility with YAML configs.
        this.dbschema = dbschema == null ? "" : dbschema; 
    }

    public long getPollIntervalMs() { return pollIntervalMs; }
    public void setPollIntervalMs(long pollIntervalMs) { this.pollIntervalMs = pollIntervalMs; }

    public boolean isLogRequestPayloads() { return logRequestPayloads; }
    public void setLogRequestPayloads(boolean logRequestPayloads) { this.logRequestPayloads = logRequestPayloads; }

    public int getTimeoutMinutes() { return timeoutMinutes; }
    public void setTimeoutMinutes(int timeoutMinutes) { this.timeoutMinutes = timeoutMinutes; }

    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int batchSize) { this.batchSize = batchSize; }

    public int getThreadPoolSize() { return threadPoolSize; }
    public void setThreadPoolSize(int threadPoolSize) { this.threadPoolSize = threadPoolSize; }

    public int getMaxConcurrentRequests() { return maxConcurrentRequests; }
    public void setMaxConcurrentRequests(int maxConcurrentRequests) { this.maxConcurrentRequests = maxConcurrentRequests; }

    public boolean isEnableCircuitBreaker() { return enableCircuitBreaker; }
    public void setEnableCircuitBreaker(boolean enableCircuitBreaker) { this.enableCircuitBreaker = enableCircuitBreaker; }

    public int getCircuitBreakerThreshold() { return circuitBreakerThreshold; }
    public void setCircuitBreakerThreshold(int circuitBreakerThreshold) { this.circuitBreakerThreshold = circuitBreakerThreshold; }

    public long getCircuitBreakerResetMs() { return circuitBreakerResetMs; }
    public void setCircuitBreakerResetMs(long circuitBreakerResetMs) { this.circuitBreakerResetMs = circuitBreakerResetMs; }

    public boolean isPreferRawSql() { return preferRawSql; }
    public void setPreferRawSql(boolean preferRawSql) { this.preferRawSql = preferRawSql; }

    public int getRawSqlTimeoutSeconds() { return rawSqlTimeoutSeconds; }
    public void setRawSqlTimeoutSeconds(int rawSqlTimeoutSeconds) { this.rawSqlTimeoutSeconds = rawSqlTimeoutSeconds; }

    public int getRawSqlRowLimit() { return rawSqlRowLimit; }
    public void setRawSqlRowLimit(int rawSqlRowLimit) { this.rawSqlRowLimit = rawSqlRowLimit; }
}
