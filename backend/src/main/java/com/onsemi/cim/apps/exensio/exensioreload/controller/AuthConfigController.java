package com.onsemi.cim.apps.exensio.exensioreload.controller;

import com.onsemi.cim.apps.exensio.exensioreload.config.SsoProperties;
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

    private final SsoProperties ssoProperties;

    public AuthConfigController(SsoProperties ssoProperties) {
        this.ssoProperties = ssoProperties;
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
        return ResponseEntity.ok(Map.of("ssoEnabled", ssoProperties.isEnabled()));
    }
}
