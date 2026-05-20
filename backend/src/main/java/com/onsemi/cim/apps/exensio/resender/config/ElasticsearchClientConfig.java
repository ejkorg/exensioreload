package com.onsemi.cim.apps.exensio.resender.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Provides the shared {@link HttpClient} bean used by {@link com.onsemi.cim.apps.exensio.resender.service.ElasticsearchLogService}
 * to make REST calls to Elasticsearch.
 *
 * <p>Uses the JDK built-in {@link java.net.http.HttpClient} — no extra dependencies required.</p>
 */
@Configuration
public class ElasticsearchClientConfig {

    /**
     * Shared JDK HttpClient for all ES requests.
     * Connection timeout is 10 seconds; per-request timeout is set on each request.
     */
    @Bean
    public HttpClient elasticsearchHttpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }
}
