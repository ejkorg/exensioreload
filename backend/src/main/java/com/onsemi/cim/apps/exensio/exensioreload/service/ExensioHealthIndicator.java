package com.onsemi.cim.apps.exensio.exensioreload.service;

import com.onsemi.cim.apps.exensio.exensioreload.config.ExensioProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Health indicator for Exensio.
 * <p>
 * Checks if Exensio is configured and reachable by attempting to acquire a token
 * via the active authentication implementation (SESSION or OAUTH mode).
 * </p>
 * <p>
 * For simplicity, we check if we can obtain a token. If we can, we consider the service reachable.
 * Works with both {@link ExensioAuthService} (SESSION mode) and {@link ExensioOAuthAuthService} (OAUTH mode)
 * via the {@link ExensioTokenProvider} interface (Requirement 5.3).
 * </p>
 */
@Component
public class ExensioHealthIndicator implements HealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(ExensioHealthIndicator.class);

    private final ExensioProperties props;
    private final ExensioTokenProvider tokenProvider;

    public ExensioHealthIndicator(ExensioProperties props, ExensioTokenProvider tokenProvider) {
        this.props = props;
        this.tokenProvider = tokenProvider;
    }

    @PostConstruct
    public void init() {
        // Log active auth mode at startup (Requirement 5.5)
        String authMode = props.getAuthMode();
        log.info("ExensioHealthIndicator initialized with auth mode: {}", authMode);
    }

    @Override
    public Health health() {
        if (!props.isConfigured()) {
            return Health.down()
                    .withDetail("error", "Exensio is not configured (exensio.enabled=false or missing base URL)")
                    .build();
        }

        try {
            // Try to get a token using the active auth service implementation (Requirement 5.3)
            // This works for both SESSION and OAUTH modes via the ExensioTokenProvider interface
            tokenProvider.getToken(props.resolvedDbschema());
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