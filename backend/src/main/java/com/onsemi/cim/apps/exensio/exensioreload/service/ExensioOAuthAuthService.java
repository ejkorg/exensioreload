package com.onsemi.cim.apps.exensio.exensioreload.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.onsemi.cim.apps.exensio.exensioreload.config.ExensioOAuthProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Manages Exensio API authentication via Azure AD OAuth 2.0 client credentials flow.
 *
 * <p>This service is conditionally registered based on the {@code exensio.auth-mode} property:
 * when set to {@code OAUTH}, this bean is created; otherwise {@link ExensioAuthService} is used.</p>
 *
 * <p>Token caching and refresh are thread-safe. Credentials are retrieved once from
 * Secrets Manager (via {@link ExensioOAuthProperties}) and cached in memory for the process lifetime.</p>
 *
 * <p>The {@code schema} parameter is accepted for interface compatibility with {@link ExensioTokenProvider}
 * but is ignored in OAUTH mode — OIDC tokens are schema-agnostic.</p>
 *
 * <p>Satisfies Requirements:
 * <ul>
 *   <li>1.1, 1.2: Uses client credentials grant to Azure AD</li>
 *   <li>1.3, 1.4: Implements token caching with proactive refresh at 60s buffer</li>
 *   <li>1.5: Propagates Azure AD errors as ExensioAuthService.ExensioAuthException</li>
 *   <li>4.1, 4.3: Returns tokens for Authorization header, logs auth_mode on acquisition</li>
 *   <li>5.1: Conditionally registered via @ConditionalOnProperty</li>
 * </ul>
 * </p>
 */
@Service
@ConditionalOnProperty(name = "exensio.auth-mode", havingValue = "OAUTH")
public class ExensioOAuthAuthService implements ExensioTokenProvider {

    private static final Logger log = LoggerFactory.getLogger(ExensioOAuthAuthService.class);

    /**
     * Proactive refresh buffer: refresh token when 60 seconds or less remain before expiry.
     * Requirement 1.4: "WHEN a cached OIDC_Token is within 60 seconds of expiry,
     * THE Auth_Service SHALL proactively refresh the token"
     */
    private static final int EXPIRY_BUFFER_SECONDS = 60;

    private final ExensioOAuthProperties oauthProps;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    // Thread-safe token caching with double-check pattern
    private volatile CachedToken cachedToken;
    private final ReentrantLock tokenLock = new ReentrantLock();

    /**
     * Cached token with expiry timestamp.
     */
    private static class CachedToken {
        final String value;
        final Instant expiresAt;

        CachedToken(String value, Instant expiresAt) {
            this.value = value;
            this.expiresAt = expiresAt;
        }

        /**
         * Check if token is expired or within buffer period.
         */
        boolean isExpiredOrNearExpiry() {
            return Instant.now().isAfter(expiresAt.minusSeconds(60)); // 60 second buffer
        }
    }

    public ExensioOAuthAuthService(ExensioOAuthProperties oauthProps, ObjectMapper objectMapper) {
        this.oauthProps = oauthProps;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        log.info("Auth mode: OAUTH (Azure AD client credentials)");
    }

    /**
     * Returns a valid OAuth Bearer token, refreshing from Azure AD if necessary.
     *
     * <p>Implements double-check locking pattern: first check outside lock for performance,
     * then acquire lock and re-check to avoid redundant refreshes when multiple threads compete.
     * (Requirement 1.3: token caching; Requirement 1.4: proactive refresh within 60s buffer)</p>
     *
     * @param schema Exensio database schema (ignored in OAUTH mode; accepted for interface compatibility)
     * @return Valid Bearer token string suitable for Authorization header
     * @throws ExensioAuthService.ExensioAuthException if Azure AD fails or credentials are invalid
     */
    @Override
    public String getToken(String schema) {
        CachedToken token = this.cachedToken;
        if (token != null && !token.isExpiredOrNearExpiry()) {
            return token.value;
        }

        // Acquire lock for cache update
        tokenLock.lock();
        try {
            // Double-check after acquiring lock
            token = this.cachedToken;
            if (token != null && !token.isExpiredOrNearExpiry()) {
                return token.value;
            }

            // Token missing or expired — acquire fresh from Azure AD
            return refreshToken();
        } finally {
            tokenLock.unlock();
        }
    }

    /**
     * Invalidates the cached token, forcing a fresh acquisition on the next {@link #getToken(String)} call.
     *
     * <p>Called by {@link ExensioClient} when it receives HTTP 401 (Requirement 4.2).</p>
     */
    @Override
    public void invalidateToken(String schema) {
        this.cachedToken = null;
    }

    /**
     * Shutdown hook: no-op in OAUTH mode.
     *
     * <p>OIDC tokens are stateless and self-expiring — no logout call needed.
     * (Requirement 4.3)</p>
     */
    @Override
    public void shutdown() {
        // OIDC tokens are stateless — no logout call needed
    }

    /**
     * Acquires a fresh token from Azure AD and caches it.
     *
     * <p>Requirement 1.1: "THE Auth_Service SHALL obtain an OIDC_Token from Azure AD using
     * the client credentials grant ({@code grant_type=client_credentials})"</p>
     *
     * @return Bearer token value
     * @throws ExensioAuthService.ExensioAuthException if Azure AD fails
     */
    private String refreshToken() {
        try {
            String tokenUrl = String.format(
                    "https://login.microsoftonline.com/%s/oauth2/v2.0/token",
                    oauthProps.getTenantId()
            );

            String requestBody = String.format(
                    "grant_type=client_credentials&client_id=%s&client_secret=%s&scope=%s",
                    urlEncode(oauthProps.getClientId()),
                    urlEncode(oauthProps.getClientSecret()),
                    urlEncode(oauthProps.getScope())
            );

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(tokenUrl))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String detail = "HTTP " + response.statusCode() + " from Azure AD: " + response.body();
                log.warn("Azure AD token acquisition failed: {}", detail);
                throw new ExensioAuthService.ExensioAuthException(
                        "Azure AD token request failed: " + detail
                );
            }

            JsonNode json = objectMapper.readTree(response.body());
            String accessToken = json.path("access_token").asText(null);
            long expiresIn = json.path("expires_in").asLong(3600);

            if (accessToken == null || accessToken.isBlank()) {
                throw new ExensioAuthService.ExensioAuthException(
                        "Azure AD response missing 'access_token' field"
                );
            }

            // Cache token with expiry
            Instant expiresAt = Instant.now().plusSeconds(expiresIn);
            this.cachedToken = new CachedToken(accessToken, expiresAt);

            // Requirement 4.1, 4.3: Log active auth mode and expires_in on successful acquisition
            log.info("OAuth token acquired from Azure AD (auth_mode=OAUTH, expires_in={})", expiresIn);

            return accessToken;

        } catch (ExensioAuthService.ExensioAuthException e) {
            throw e;
        } catch (Exception e) {
            throw new ExensioAuthService.ExensioAuthException(
                    "Azure AD token acquisition error: " + e.getMessage(), e
            );
        }
    }

    /**
     * Simple URL encoding for form parameters (application/x-www-form-urlencoded).
     */
    private String urlEncode(String value) {
        try {
            return java.net.URLEncoder.encode(value, "UTF-8");
        } catch (Exception e) {
            return value; // Fallback if encoding fails
        }
    }
}
