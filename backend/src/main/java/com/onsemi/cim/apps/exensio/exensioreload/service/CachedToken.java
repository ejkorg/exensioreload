package com.onsemi.cim.apps.exensio.exensioreload.service;

import java.time.Instant;

/**
 * A Bearer token paired with its expiry time.
 *
 * <p>Used by {@code ExensioOAuthAuthService} to cache OIDC tokens from Azure AD
 * until they expire (minus a 60-second proactive refresh buffer).</p>
 *
 * <p>Satisfies Requirement 1.3 (token caching) and Requirement 1.4 (proactive refresh
 * within 60 seconds of expiry).</p>
 *
 * @param value the Bearer token string (ready to pass as "Authorization: Bearer <value>")
 * @param expiresAt the expiration timestamp (UTC); token is considered valid if
 *                  current time is at least 60 seconds before this instant
 */
public record CachedToken(String value, Instant expiresAt) {

    /**
     * Returns true if the token is still valid and does not need proactive refreshing.
     *
     * <p>A token is considered valid if at least {@code bufferSeconds} of lifetime remain.
     * This implements the proactive refresh pattern: if fewer than 60 seconds remain,
     * {@code isValid()} returns false, triggering a new token acquisition.</p>
     *
     * <p>Default buffer is 60 seconds (Requirement 1.4).</p>
     *
     * @param bufferSeconds the proactive refresh buffer (seconds); defaults to 60
     * @return true if the token is still valid and does not need refreshing
     */
    public boolean isValid(int bufferSeconds) {
        Instant refreshThreshold = expiresAt.minusSeconds(bufferSeconds);
        return Instant.now().isBefore(refreshThreshold);
    }

    /**
     * Returns true if the token is still valid with the default 60-second buffer.
     *
     * @return true if the token is still valid
     */
    public boolean isValid() {
        return isValid(60);
    }
}
