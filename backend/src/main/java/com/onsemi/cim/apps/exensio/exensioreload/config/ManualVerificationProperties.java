package com.onsemi.cim.apps.exensio.exensioreload.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * ManualVerificationProperties
 *
 * Configuration for manual verification retry feature.
 * Enables/disables and configures the silent background verification
 * of COMPLETED_MANUAL_VERIFICATION_REQUIRED records.
 *
 * Usage in application.yml:
 * app:
 *   manual-verification:
 *     enabled: true
 *     retry-interval-ms: 300000
 *
 * Requirement: Silent background verification + auto-completion (Task 11)
 */
@Component
@ConfigurationProperties(prefix = "app.manual-verification")
public class ManualVerificationProperties {

    /** Enable or disable manual verification retry feature */
    private boolean enabled = true;

    /** Interval (milliseconds) for silent retry of COMPLETED_MANUAL_VERIFICATION_REQUIRED records */
    private long retryIntervalMs = 300000; // 5 minutes default

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getRetryIntervalMs() {
        return retryIntervalMs;
    }

    public void setRetryIntervalMs(long retryIntervalMs) {
        this.retryIntervalMs = retryIntervalMs;
    }
}
