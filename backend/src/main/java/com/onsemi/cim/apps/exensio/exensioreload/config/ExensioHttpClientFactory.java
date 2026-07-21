package com.onsemi.cim.apps.exensio.exensioreload.config;

import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Factory for creating and sharing HttpClient instances for Exensio services.
 * 
 * <p>Provides a single, reusable HttpClient bean to reduce resource consumption.
 * Previously, ExensioClient, ExensioPreCheckService, and ExensioRawSqlService each
 * created their own HttpClient instances, leading to separate connection pools and
 * wasted resources.</p>
 * 
 * <p>Feature: lot-existence-verification, Property: HttpClient Resource Sharing</p>
 */
@Configuration
public class ExensioHttpClientFactory {

    /**
     * Creates a shared HttpClient bean for all Exensio HTTP operations.
     * 
     * <p>Configuration:
     * <ul>
     *   <li>Redirect policy: NEVER (don't follow 3xx redirects automatically)</li>
     *   <li>Connect timeout: 10 seconds (connection establishment)</li>
     * </ul>
     * 
     * <p>This bean is injected into ExensioClient, ExensioPreCheckService, and 
     * ExensioRawSqlService to share a single connection pool.</p>
     * 
     * @return shared HttpClient instance
     */
    @Bean(name = "exensioHttpClient")
    public HttpClient exensioHttpClient() {
        return HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }
}
