package com.onsemi.cim.apps.exensio.exensioreload.controller;

import com.onsemi.cim.apps.exensio.exensioreload.config.SsoProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Exposes runtime authentication configuration to the Angular frontend.
 *
 * <p>{@code GET /api/auth/config} returns a JSON object indicating whether SSO is enabled,
 * so the {@code LoginComponent} can conditionally show the "Sign in with onsemi SSO" button
 * without hardcoding the flag in the frontend build.
 *
 * <p>Requirement: 6.3
 */
@RestController
@RequestMapping("/api/auth")
public class AuthConfigController {

    private static final Logger logger = LoggerFactory.getLogger(AuthConfigController.class);

    private final SsoProperties ssoProperties;

    public AuthConfigController(SsoProperties ssoProperties) {
        this.ssoProperties = ssoProperties;
    }

    @PostConstruct
    void logSsoConfig() {
        boolean ssoEnabled = ssoProperties.isEnabled();
        boolean hasClientId = ssoProperties.getClientId() != null && !ssoProperties.getClientId().trim().isEmpty();
        boolean hasClientSecret = ssoProperties.getClientSecret() != null && !ssoProperties.getClientSecret().trim().isEmpty();
        boolean hasTenantId = ssoProperties.getTenantId() != null && !ssoProperties.getTenantId().trim().isEmpty();
        logger.info("SSO config at startup: ssoEnabled={}, hasClientId={}, hasClientSecret={}, hasTenantId={}",
                ssoEnabled, hasClientId, hasClientSecret, hasTenantId);
        if (!ssoEnabled) {
            logger.warn("SSO is DISABLED. The 'Sign in with SSO' button will NOT appear on the login page. " +
                    "To enable SSO, set ONSEMI_SSO_CLIENT_ID, ONSEMI_SSO_CLIENT_SECRET, and ONSEMI_SSO_TENANT_ID env vars, " +
                    "or set ONSEMI_SSO_ENABLED=true.");
        }
    }

    /**
     * Returns the current authentication configuration.
     *
     * <p>Example response:
     * <pre>{@code { "ssoEnabled": true }}</pre>
     *
     * <p>Requirement: 6.3
     */
    @GetMapping("/config")
    public ResponseEntity<Map<String, Object>> config() {
        boolean result = ssoProperties.isEnabled();
        logger.debug("GET /api/auth/config → ssoEnabled={}", result);
        return ResponseEntity.ok(Map.of("ssoEnabled", result));
    }
}
