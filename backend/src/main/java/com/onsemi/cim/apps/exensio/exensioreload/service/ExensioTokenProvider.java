package com.onsemi.cim.apps.exensio.exensioreload.service;

/**
 * Common interface for Exensio authentication implementations.
 *
 * <p>Both the existing session-based {@link ExensioAuthService} and the new
 * {@code ExensioOAuthAuthService} implement this interface. All upstream callers
 * ({@link ExensioClient}, {@link ExensioRawSqlService}, {@link ExensioPreCheckService},
 * {@link ExensioLoadMonitor}) depend on this interface so that no code changes
 * are required when {@code AUTH_MODE} switches between SESSION and OAUTH.</p>
 *
 * <p>Satisfies Requirement 4.4 (token presentation interface remains identical
 * for callers regardless of auth mode).</p>
 */
public interface ExensioTokenProvider {

    /**
     * Returns a valid Bearer token for the given schema.
     *
     * <p>In SESSION mode the schema determines which Exensio database schema is
     * authenticated against. In OAUTH mode the OIDC token is schema-agnostic;
     * the {@code schema} parameter is accepted for interface compatibility but ignored.</p>
     *
     * @param schema Exensio database schema (e.g. "PRODUCTION", "SANDBOX"); may be null in OAUTH mode
     * @return Bearer token string — ready to be placed in an {@code Authorization: Bearer <token>} header
     * @throws ExensioAuthService.ExensioAuthException if token acquisition fails
     */
    String getToken(String schema);

    /**
     * Evicts the cached token for the given schema, forcing a fresh acquisition on the next call.
     *
     * <p>Called by {@link ExensioClient} when an API call returns HTTP 401.</p>
     *
     * @param schema Exensio database schema; may be null in OAUTH mode
     */
    void invalidateToken(String schema);

    /**
     * Performs any necessary cleanup on application shutdown.
     *
     * <p>SESSION mode calls {@code POST /v1/session/logout} for each cached schema token.
     * OAUTH mode is a no-op because OIDC tokens are stateless and self-expiring
     * (Requirement 4.3).</p>
     */
    void shutdown();
}
