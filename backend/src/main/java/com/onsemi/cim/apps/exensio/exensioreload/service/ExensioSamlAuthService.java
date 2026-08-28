package com.onsemi.cim.apps.exensio.exensioreload.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.onsemi.cim.apps.exensio.exensioreload.config.ExensioProperties;
import com.onsemi.cim.apps.exensio.exensioreload.config.ExensioSamlProperties;
import com.onsemi.cim.apps.exensio.exensioreload.service.auth.SamlCredentials;
import com.onsemi.cim.apps.exensio.exensioreload.service.saml.ExensioSamlTokenExchanger;
import com.onsemi.cim.apps.exensio.exensioreload.service.saml.SamlAuthenticationFacade;
import com.onsemi.cim.apps.exensio.exensioreload.service.saml.SamlAssertionValidator;
import com.onsemi.cim.apps.exensio.exensioreload.service.saml.ExensioTokenResponse;
import jakarta.annotation.PreDestroy;
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
import java.time.temporal.ChronoUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * SAML SSO authentication service for Azure AD integration.
 *
 * <p>Acquires a Bearer token via Azure AD SAML SSO using the following flow:
 * <ol>
 *   <li>Build SAML AuthnRequest with SP entity ID and ACS URL</li>
 *   <li>Attempt Azure AD authentication using tiered strategy fallback
 *       (form-POST, WS-Federation, Selenium headless browser)</li>
 *   <li>Validate returned SAML assertion signature against IdP certificate</li>
 *   <li>Exchange assertion at {@code POST /v1/saml/consumer} for Bearer token</li>
 *   <li>Cache token with expiry; re-authenticate when expired or invalidated (401 response)</li>
 *   <li>On shutdown: call {@code POST /v1/session/logout} if token is cached</li>
 * </ol>
 * </p>
 *
 * <p>Thread-safe: uses {@code volatile} fields and {@code ReentrantLock} double-checked locking
 * to prevent concurrent SAML authentication storms.</p>
 *
 * <p>Implements {@link ExensioTokenProvider} so callers require no changes when auth mode
 * switches from SESSION to SAML.</p>
 *
 * <p>Satisfies Requirements:
 * <ul>
 *   <li>1.1: SAML Token Acquisition flow</li>
 *   <li>1.3: Token caching with expiry</li>
 *   <li>2.1: Conditional bean registration on exensio.auth-mode=SAML</li>
 *   <li>2.4: Log "Auth mode: SAML" at INFO in constructor</li>
 *   <li>4.1: Bearer token presented in Authorization header</li>
 *   <li>4.3: Double-checked locking for concurrent token access</li>
 *   <li>4.4: 401 invalidation triggers re-authentication</li>
 *   <li>4.5: Logout on shutdown</li>
 *   <li>6.1: Health indicator integration (via ExensioTokenProvider interface)</li>
 * </ul>
 * </p>
 */
@Service
@ConditionalOnProperty(name = "exensio.auth-mode", havingValue = "SAML")
public class ExensioSamlAuthService implements ExensioTokenProvider {

    private static final Logger log = LoggerFactory.getLogger(ExensioSamlAuthService.class);

    private final ExensioSamlProperties samlProps;
    private final ExensioProperties props;
    private final HttpClient httpClient;
    private final SamlAuthenticationFacade samlFacade;
    private final SamlAssertionValidator assertionValidator;
    private final ExensioSamlTokenExchanger tokenExchanger;

    // Token caching fields — volatile for visibility across threads
    private volatile String cachedToken;
    private volatile Instant cachedTokenExpiry;

    // Lock to prevent concurrent SAML authentication storms
    private final ReentrantLock tokenLock = new ReentrantLock();

    /**
     * Constructs the SAML authentication service.
     *
     * <p>Requirement 2.4: "THE Auth_Service SHALL log 'Auth mode: SAML (Azure AD SSO)'
     * at INFO in constructor"</p>
     *
     * @param samlProps SAML credentials and configuration (loaded from Secrets Manager)
     * @param props Exensio base URL and other configuration
     * @param objectMapper for parsing JSON responses
     */
    public ExensioSamlAuthService(
            ExensioSamlProperties samlProps,
            ExensioProperties props,
            ObjectMapper objectMapper
    ) {
        this.samlProps = samlProps;
        this.props = props;
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.samlFacade = new SamlAuthenticationFacade(buildSamlCredentials(samlProps));
        this.assertionValidator = new SamlAssertionValidator(samlProps.getIdpCertificate());
        this.tokenExchanger = new ExensioSamlTokenExchanger(objectMapper, httpClient);
        log.info("Auth mode: SAML (Azure AD SSO)");
    }

    /**
     * Returns a valid Bearer token, acquiring a fresh one via SAML if necessary.
     *
     * <p>Requirement 4.3: Uses double-checked locking pattern to prevent concurrent
     * SAML authentication storms when multiple threads call getToken() simultaneously.</p>
     *
     * <p>Requirement 1.3: Caches the token with associated expiry; returns cached token
     * if still valid (not expired).</p>
     *
     * @param schema Exensio database schema (ignored in SAML mode; accepted for interface compatibility)
     * @return Bearer token ready for {@code Authorization: Bearer <token>} header
     * @throws ExensioAuthService.ExensioAuthException if SAML authentication fails
     */
    @Override
    public String getToken(String schema) {
        // First check: avoid lock contention if token is valid and cached
        String token = this.cachedToken;
        if (token != null && Instant.now().isBefore(cachedTokenExpiry)) {
            return token;
        }

        // Acquire lock for second check and potential refresh
        tokenLock.lock();
        try {
            // Second check: confirm token is still invalid/expired under lock
            token = this.cachedToken;
            if (token != null && Instant.now().isBefore(cachedTokenExpiry)) {
                return token;
            }

            // Token is missing or expired — refresh
            return refreshToken();
        } finally {
            tokenLock.unlock();
        }
    }

    /**
     * Invalidates the cached SAML token, forcing a fresh acquisition on the next call.
     *
     * <p>Called by {@link ExensioClient} when an API call returns HTTP 401.</p>
     *
     * <p>Requirement 4.4: "WHEN an Exensio API call returns HTTP 401 under SAML mode,
     * THE Auth_Service SHALL invoke invalidateToken() to clear the cached Session_Token
     * and re-authenticate via the full SAML flow on the next getToken() call"</p>
     *
     * @param schema Exensio database schema (ignored in SAML mode)
     */
    @Override
    public void invalidateToken(String schema) {
        this.cachedToken = null;
        this.cachedTokenExpiry = null;
        log.debug("SAML token invalidated (likely due to 401 response from Exensio)");
    }

    /**
     * Performs SAML authentication refresh: acquire assertion, validate, exchange for token.
     *
     * <p>This method is called under lock by {@link #getToken(String)}.</p>
     *
     * <p>Requirement 1.1: "WHEN exensio.auth-mode is set to SAML, THE Auth_Service SHALL
     * build a SAML AuthnRequest, obtain a SAML_Assertion from Azure AD, and exchange it
     * at POST /v1/saml/consumer to receive a Session_Token"</p>
     *
     * @return the newly acquired Bearer token
     * @throws ExensioAuthService.ExensioAuthException if any step fails
     */
    private String refreshToken() {
        try {
            // Step 1: Acquire SAML assertion from Azure AD using tiered fallback strategy
            String samlAssertion = samlFacade.acquireSamlAssertion();

            // Step 2: Validate assertion signature against IdP certificate
            assertionValidator.validate(samlAssertion);

            // Step 3: Exchange assertion with Exensio for Bearer token
            ExensioTokenResponse response = tokenExchanger.exchange(
                    samlAssertion,
                    samlProps.getPredefinedConnection(),
                    props.resolvedBaseUrl()
            );

            // Step 4: Cache the token and expiry
            this.cachedToken = response.token();
            this.cachedTokenExpiry = Instant.ofEpochSecond(response.expiry());

            // Requirement 2.4: Log successful acquisition at INFO with expiry
            log.info("SAML token acquired (auth_mode=SAML, expiry={})", cachedTokenExpiry);

            return this.cachedToken;

        } catch (ExensioAuthService.ExensioAuthException e) {
            throw e;
        } catch (Exception e) {
            String detail = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            throw new ExensioAuthService.ExensioAuthException(
                    "SAML token refresh failed: " + detail,
                    e
            );
        }
    }

    /**
     * Performs cleanup on application shutdown.
     *
     * <p>Requirement 4.5: "WHEN the application shuts down and the Auth_Service has a
     * cached Session_Token, THE Auth_Service shutdown() method SHALL call
     * POST /v1/session/logout with the cached Session_Token and SHALL clear the cached
     * token regardless of the logout response"</p>
     *
     * <p>Requirement 4.5b: "WHEN the application shuts down and no Session_Token is cached,
     * THE Auth_Service shutdown() method SHALL complete without making any HTTP calls"</p>
     */
    @Override
    @PreDestroy
    public void shutdown() {
        String token = this.cachedToken;

        // Requirement 4.5b: If no token cached, return immediately without HTTP calls
        if (token == null) {
            log.debug("SAML shutdown: no cached token to logout");
            return;
        }

        // Requirement 4.5: Call logout if token is cached
        try {
            String logoutUrl = props.resolvedBaseUrl().replaceAll("/$", "") + "/v1/session/logout";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(logoutUrl))
                    .timeout(Duration.ofSeconds(5))
                    .header("Authorization", "Bearer " + token)
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();

            HttpResponse<String> response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.discarding()
            );

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                log.info("SAML session logout successful");
            } else {
                log.debug("SAML session logout returned HTTP {}", response.statusCode());
            }
        } catch (Exception e) {
            log.debug("SAML logout error on shutdown (non-critical): {}", e.getMessage());
        } finally {
            // Clear token regardless of logout success or failure
            this.cachedToken = null;
            this.cachedTokenExpiry = null;
        }
    }
}

    /**
     * Builds a SamlCredentials record from ExensioSamlProperties.
     *
     * <p>This helper method extracts the necessary fields from the properties to create
     * a SamlCredentials instance for use by the authentication facade and validators.</p>
     */
    private static SamlCredentials buildSamlCredentials(ExensioSamlProperties props) {
        return new SamlCredentials(
                props.getIdpSsoUrl(),
                props.getIdpEntityId(),
                props.getIdpCertificate(),
                props.getSpEntityId(),
                props.getAcsUrl(),
                props.isSignRequests(),
                props.getSpPrivateKey(),
                props.getSpCertificate(),
                props.getServiceAccountUsername(),
                props.getServiceAccountPassword(),
                props.getPredefinedConnection()
        );
    }
}
