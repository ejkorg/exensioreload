package com.onsemi.cim.apps.exensio.exensioreload.service.auth;

import java.time.Instant;

/**
 * An immutable snapshot of a cached Bearer token and its expiry instant.
 *
 * <p>Used by {@code ExensioOAuthAuthService} to avoid unnecessary round-trips
 * to the Azure AD token endpoint (Requirement 1.3). The 60-second proactive
 * refresh buffer is enforced by {@link #isExpiredOrNearExpiry()} (Requirement 1.4).</p>
 *
 * @param value     the raw Bearer token string
 * @param expiresAt the {@link Instant} at which the token expires
 */
public record CachedToken(String value, Instant expiresAt) {

    /** Default proactive-refresh buffer in seconds (Requirement 1.4). */
    private static final int DEFAULT_BUFFER_SECONDS = 60;

    /**
     * Returns {@code true} when the token has expired or will expire within the
     * default 60-second buffer, meaning a proactive refresh should be triggered.
     *
     * @return {@code true} if a refresh is needed
     */
    public boolean isExpiredOrNearExpiry() {
        return isExpiredOrNearExpiry(DEFAULT_BUFFER_SECONDS);
    }

    /**
     * Returns {@code true} when the token has expired or will expire within
     * {@code bufferSeconds} seconds.
     *
     * @param bufferSeconds number of seconds before expiry at which to trigger refresh
     * @return {@code true} if a refresh is needed
     */
    public boolean isExpiredOrNearExpiry(int bufferSeconds) {
        return Instant.now().isAfter(expiresAt.minusSeconds(bufferSeconds));
    }
}
