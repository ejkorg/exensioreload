package com.onsemi.cim.apps.exensio.resender.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for the CP Elasticsearch integration.
 * Bound from the {@code cp.elasticsearch} prefix in application.yml.
 *
 * <p>Requirements: 6.1, 6.2, 6.3, 6.4, 6.5</p>
 */
@Component
@ConfigurationProperties(prefix = "cp.elasticsearch")
public class CpElasticsearchProperties {

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

    /** cpConfig wildcard filter to isolate resender-triggered files. Default: *sender* */
    private String cpConfigFilter = "*sender*";

    /** Polling interval in milliseconds. Default: 60 000 ms (1 minute). */
    private long pollIntervalMs = 60_000L;

    /** Timeout in minutes before a record stuck in ENRICHMENT is marked FAILED. Default: 30 minutes. */
    private int enrichmentTimeoutMinutes = 30;

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url == null ? "" : url; }

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

    public long getPollIntervalMs() { return pollIntervalMs; }
    public void setPollIntervalMs(long pollIntervalMs) { this.pollIntervalMs = pollIntervalMs; }

    public int getEnrichmentTimeoutMinutes() { return enrichmentTimeoutMinutes; }
    public void setEnrichmentTimeoutMinutes(int enrichmentTimeoutMinutes) { this.enrichmentTimeoutMinutes = enrichmentTimeoutMinutes; }

    /** Returns true if Elasticsearch is configured (url is non-blank). */
    public boolean isConfigured() {
        return url != null && !url.isBlank();
    }
}
