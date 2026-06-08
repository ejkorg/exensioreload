package com.onsemi.cim.apps.exensio.exensioreload.service;

import com.onsemi.cim.apps.exensio.exensioreload.config.CpElasticsearchProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * Health indicator for Elasticsearch.
 * <p>
 * Checks if Elasticsearch is configured and reachable by sending a request to the cluster health endpoint.
 * </p>
 */
@Component
public class ElasticsearchHealthIndicator implements HealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(ElasticsearchHealthIndicator.class);

    private final CpElasticsearchProperties props;
    private final HttpClient httpClient;

    public ElasticsearchHealthIndicator(CpElasticsearchProperties props, HttpClient httpClient) {
        this.props = props;
        this.httpClient = httpClient;
    }

    @Override
    public Health health() {
        if (!props.isConfigured()) {
            return Health.down()
                    .withDetail("error", "Elasticsearch is not configured (cp.elasticsearch.url is blank)")
                    .build();
        }

        try {
            String url = props.resolveSearchUrl();
            // Replace the index pattern and _search with just _cluster/health for a lightweight check
            String healthCheckUrl = url.replaceFirst("/[^/]+/_search$", "/_cluster/health");
            // If the above replacement didn't work (e.g., URL doesn't end with _search), try appending
            if (healthCheckUrl.equals(url)) {
                healthCheckUrl = url + "/_cluster/health";
            }

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(healthCheckUrl))
                    .timeout(java.time.Duration.ofSeconds(2))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                return Health.up()
                        .withDetail("elasticsearch", "Cluster is reachable")
                        .build();
            } else {
                return Health.down()
                        .withDetail("elasticsearch", "Returned HTTP status: " + response.statusCode())
                        .withDetail("response", response.body())
                        .build();
            }
        } catch (IOException | InterruptedException e) {
            log.warn("Elasticsearch health check failed: {}", e.getMessage());
            return Health.down()
                    .withDetail("elasticsearch", "Failed to connect: " + e.getMessage())
                    .build();
        }
    }
}