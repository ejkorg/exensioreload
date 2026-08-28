package com.onsemi.cim.apps.exensio.exensioreload.service.saml;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.onsemi.cim.apps.exensio.exensioreload.service.ExensioAuthService;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles SAML assertion exchange with the Exensio API.
 *
 * <p>Takes a base64-encoded SAML assertion from Azure AD and exchanges it at the
 * Exensio {@code POST /v1/saml/consumer} endpoint to receive a Bearer token and expiry.</p>
 *
 * <p>The request body is {@code application/x-www-form-urlencoded} containing:
 * <ul>
 *   <li>{@code SAMLResponse}: the base64-encoded SAML assertion (URL-encoded)</li>
 *   <li>{@code predefined-connection}: the Exensio database connection name</li>
 * </ul>
 * </p>
 *
 * <p>Error handling:</p>
 * <ul>
 *   <li>HTTP 401: SAML assertion rejected by Exensio (likely LDAP group validation failure)
 *       → Logs warning and throws {@code ExensioAuthException}</li>
 *   <li>HTTP 403: Predefined connection not authorized for the service account
 *       → Logs warning and throws {@code ExensioAuthException}</li>
 *   <li>Other non-2xx: Throws {@code ExensioAuthException} with status and response body</li>
 * </ul>
 *
 * <p>Token expiry defaults to now + 3600 seconds (1 hour) if Exensio does not provide one.</p>
 *
 * <p>Satisfies Requirements 1.1 (SAML token acquisition), 1.2 (predefined-connection inclusion),
 * 1.4 (error propagation), 8.2 (401 error handling), 8.3 (403 error handling).</p>
 */
public class ExensioSamlTokenExchanger {

    private static final Logger logger = LoggerFactory.getLogger(ExensioSamlTokenExchanger.class);

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    /**
     * Creates a new token exchanger.
     *
     * @param objectMapper for parsing JSON responses
     * @param httpClient for making HTTP requests to Exensio
     */
    public ExensioSamlTokenExchanger(ObjectMapper objectMapper, HttpClient httpClient) {
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    /**
     * Exchanges a SAML assertion for an Exensio Bearer token.
     *
     * <p>Requirement 1.2: "THE Auth_Service SHALL include the {@code predefined-connection}
     * parameter in the {@code application/x-www-form-urlencoded} request body"</p>
     *
     * <p>Requirement 1.4: "WHEN Exensio returns an error, THE Auth_Service SHALL throw an
     * {@code ExensioAuthService.ExensioAuthException} with HTTP status and response body"</p>
     *
     * @param samlAssertion a base64-encoded SAML assertion from Azure AD
     * @param predefinedConnection the Exensio database connection name the service account is authorized for
     * @param baseUrl the Exensio API base URL (e.g., https://exensio-prod.example.com)
     * @return an {@link ExensioTokenResponse} containing the Bearer token and expiry timestamp
     * @throws ExensioAuthService.ExensioAuthException if the exchange fails
     */
    public ExensioTokenResponse exchange(
        String samlAssertion,
        String predefinedConnection,
        String baseUrl
    ) {
        // Build the request body: application/x-www-form-urlencoded
        String body = "SAMLResponse=" + urlEncode(samlAssertion)
                    + "&predefined-connection=" + urlEncode(predefinedConnection);

        String url = baseUrl.replaceAll("/$", "") + "/v1/saml/consumer";

        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(java.time.Duration.ofSeconds(20))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

            HttpResponse<String> response = httpClient.send(
                request,
                HttpResponse.BodyHandlers.ofString()
            );

            // Handle HTTP 401: SAML assertion rejected by Exensio
            if (response.statusCode() == 401) {
                logger.warn(
                    "SAML token exchange rejected by Exensio (HTTP 401) — "
                    + "check LDAP group membership and Azure AD group claims"
                );
                throw new ExensioAuthService.ExensioAuthException(
                    "SAML token exchange failed: HTTP 401 (LDAP group validation or assertion validation failed)"
                );
            }

            // Handle HTTP 403: predefined-connection not authorized
            if (response.statusCode() == 403) {
                logger.warn(
                    "predefined-connection '{}' not authorized (HTTP 403) — "
                    + "verify service account has access to this connection",
                    predefinedConnection
                );
                throw new ExensioAuthService.ExensioAuthException(
                    "SAML token exchange failed: HTTP 403 (predefined-connection not authorized)"
                );
            }

            // Handle other non-2xx responses
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String detail = response.body() != null && !response.body().isBlank()
                    ? response.body()
                    : "(no response body)";
                logger.warn(
                    "SAML token exchange failed: HTTP {} — {}",
                    response.statusCode(),
                    detail
                );
                throw new ExensioAuthService.ExensioAuthException(
                    "SAML token exchange failed: HTTP " + response.statusCode()
                    + " — " + detail
                );
            }

            // Parse successful 2xx response
            JsonNode json = objectMapper.readTree(response.body());
            String token = json.path("token").asText(null);

            if (token == null || token.isBlank()) {
                throw new ExensioAuthService.ExensioAuthException(
                    "SAML token exchange response missing 'token' field"
                );
            }

            // Requirement 1.3: Parse expiry or default to now + 3600 seconds
            long expiry;
            if (json.has("expiry") && json.path("expiry").isNumber()) {
                expiry = json.path("expiry").asLong();
            } else {
                // Default to 1 hour from now
                expiry = Instant.now().plus(1, ChronoUnit.HOURS).getEpochSecond();
            }

            logger.debug(
                "SAML token exchange successful (expiry={})",
                Instant.ofEpochSecond(expiry)
            );

            return new ExensioTokenResponse(token, expiry);

        } catch (ExensioAuthService.ExensioAuthException e) {
            throw e;
        } catch (Exception e) {
            logger.error(
                "Unexpected error during SAML token exchange: {}",
                e.getMessage(),
                e
            );
            throw new ExensioAuthService.ExensioAuthException(
                "SAML token exchange error: " + e.getMessage(),
                e
            );
        }
    }

    /**
     * URL-encodes a string for use in application/x-www-form-urlencoded bodies.
     *
     * <p>Uses UTF-8 encoding and the standard {@code %XX} hex format.</p>
     */
    private String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
