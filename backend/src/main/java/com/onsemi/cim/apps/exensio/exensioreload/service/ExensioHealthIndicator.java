package com.onsemi.cim.apps.exensio.exensioreload.service;

import com.onsemi.cim.apps.exensio.exensioreload.config.ExensioProperties;
import com.onsemi.cim.apps.exensio.exensioreload.service.ExensioAuthService;
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
 * Health indicator for Exensio.
 * <p>
 * Checks if Exensio is configured and reachable by attempting to authenticate and fetch the server status
 * (or simply checking that we can get a token and hit a lightweight endpoint).
 * </p>
 * <p>
 * For simplicity, we check if we can obtain a token. If we can, we consider the service reachable.
 * In a more advanced setup, we might call a health endpoint on the Exensio server.
 * </p>
 */
@Component
public class ExensioHealthIndicator implements HealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(ExensioHealthIndicator.class);

    private final ExensioProperties props;
    private final ExensioAuthService authService;
    private final HttpClient httpClient;

    public ExensioHealthIndicator(ExensioProperties props, ExensioAuthService authService, HttpClient httpClient) {
        this.props = props;
        this.authService = authService;
        this.httpClient = httpClient;
    }

    @Override
    public Health health() {
        if (!props.isConfigured()) {
            return Health.down()
                    .withDetail("error", "Exensio is not configured (exensio.enabled=false or missing base URL)")
                    .build();
        }

        try {
            // Try to get a token (this will also validate the credentials and endpoint)
            authService.getToken(); // This may throw ExensioAuthException
            return Health.up()
                    .withDetail("exensio", "Credentials are valid and token service is reachable")
                    .build();
        } catch (ExensioAuthService.ExensioAuthException e) {
            log.warn("Exensio health check failed: {}", e.getMessage());
            return Health.down()
                    .withDetail("exensio", "Authentication failed: " + e.getMessage())
                    .build();
        } catch (Exception e) {
            log.warn("Exensio health check failed: {}", e.getMessage());
            return Health.down()
                    .withDetail("exensio", "Failed to connect: " + e.getMessage())
                    .build();
        }
    }
}