package com.onsemi.cim.apps.exensio.exensioreload.service.saml;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * WS-Federation SAML strategy for Azure AD authentication.
 *
 * <p>Uses the WS-Federation passive sign-in endpoint to obtain a SAML token without browser interaction.
 * This strategy is useful for environments where form-based login fails (e.g., due to MFA) but
 * WS-Federation is enabled for the tenant.</p>
 *
 * <p>Sends service account credentials to the WS-Federation endpoint, parses the {@code wresult}
 * token from the response, and converts it to a SAMLResponse format for Exensio consumption.
 * If WS-Federation is not enabled for the tenant (404), throws {@link UnsupportedOperationException}
 * to skip this strategy and try the next one (Selenium).</p>
 *
 * <p>Satisfies Requirement 9.1 (three-strategy fallback, Strategy 2).</p>
 */
public class WsFederationSamlStrategy implements SamlAuthStrategy {

    private static final Logger logger = LoggerFactory.getLogger(WsFederationSamlStrategy.class);

    private static final String STRATEGY_NAME = "WsFederation";

    // Regex pattern to extract wresult token from HTML
    private static final Pattern WRESULT_PATTERN = Pattern.compile(
        "name=\"wresult\"\\s+value=\"([^\"]+)\"",
        Pattern.CASE_INSENSITIVE
    );

    private final SamlCredentials credentials;
    private final HttpClient httpClient;

    public WsFederationSamlStrategy(SamlCredentials credentials) {
        this.credentials = credentials;
        this.httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    }

    @Override
    public String name() {
        return STRATEGY_NAME;
    }

    @Override
    public String authenticate() throws Exception {
        logger.debug("WsFederation strategy: initiating WS-Federation authentication");

        // Construct the WS-Federation endpoint URL
        String wsFederationUrl = buildWsFederationUrl();

        // POST service account credentials to WS-Federation endpoint
        String wresult = sendWsFederationRequest(wsFederationUrl);
        if (wresult == null || wresult.isBlank()) {
            throw new Exception("wresult token not found in WS-Federation response");
        }

        // Convert wresult to SAMLResponse format
        String samlResponse = convertToSamlResponse(wresult);

        logger.debug("WsFederation strategy: successfully obtained SAML response from WS-Federation");
        return samlResponse;
    }

    /**
     * Builds the WS-Federation URL from the IdP SSO URL.
     * Extracts the tenant ID from the IdP SSO URL and constructs the WS-Federation endpoint.
     */
    private String buildWsFederationUrl() {
        // idpSsoUrl is typically: https://login.microsoftonline.com/{tenant}/saml2
        // wsfed endpoint: https://login.microsoftonline.com/{tenant}/wsfed
        String idpSsoUrl = credentials.idpSsoUrl();
        if (idpSsoUrl.contains("/saml2")) {
            return idpSsoUrl.replace("/saml2", "/wsfed");
        }
        // Fallback: assume standard Azure AD structure
        return idpSsoUrl.replaceAll("/$", "") + "/wsfed";
    }

    /**
     * Sends service account credentials to the WS-Federation endpoint.
     *
     * @param wsFederationUrl the WS-Federation endpoint
     * @return the wresult token from the response
     * @throws UnsupportedOperationException if WS-Federation is not enabled (404)
     * @throws Exception if the request fails
     */
    private String sendWsFederationRequest(String wsFederationUrl) throws Exception {
        String formBody = String.format(
            "UserName=%s&Password=%s&wa=wsignin1.0&wtrealm=%s&wctx=rm%3d0",
            urlEncode(credentials.serviceAccountUsername()),
            urlEncode(credentials.serviceAccountPassword()),
            urlEncode(credentials.spEntityId())
        );

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(wsFederationUrl))
            .timeout(Duration.ofSeconds(20))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(formBody))
            .build();

        HttpResponse<String> response = httpClient.send(request, BodyHandlers.ofString());

        // If 404, WS-Federation is not enabled for this tenant
        if (response.statusCode() == 404) {
            logger.debug("WS-Federation endpoint returned 404 — not enabled for this tenant");
            throw new UnsupportedOperationException(
                "WS-Federation not enabled for tenant — skipping to next strategy"
            );
        }

        if (response.statusCode() < 200 || response.statusCode() >= 400) {
            throw new Exception(
                "WS-Federation request failed: HTTP " + response.statusCode() +
                " — may indicate incorrect tenant or credentials"
            );
        }

        // Extract wresult from response
        String wresult = extractWresult(response.body());
        return wresult;
    }

    /**
     * Extracts the wresult token from the HTML form response.
     *
     * @param html the HTML response from WS-Federation endpoint
     * @return the wresult token, or null if not found
     */
    private String extractWresult(String html) {
        Matcher matcher = WRESULT_PATTERN.matcher(html);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    /**
     * Converts WS-Federation wresult token to a SAML response format for Exensio.
     *
     * <p>The wresult is already a SAML assertion from Azure AD, but may be in a different
     * format or encoding. This method ensures it is in the correct format for the Exensio
     * SAML consumer endpoint.</p>
     *
     * @param wresult the WS-Federation wresult token
     * @return the SAML response in the format expected by Exensio
     */
    private String convertToSamlResponse(String wresult) {
        // wresult is typically already Base64-encoded, but may have been HTML-encoded
        // Decode if necessary and ensure it's in the format Exensio expects
        String decoded = htmlDecode(wresult);
        
        // If it looks like Base64, return as-is; otherwise encode it
        if (isBase64(decoded)) {
            return decoded;
        }
        
        // Encode raw SAML as Base64 for transport to Exensio
        return Base64.getEncoder().encodeToString(decoded.getBytes());
    }

    /**
     * Simple HTML entity decoder for form values.
     */
    private String htmlDecode(String encoded) {
        return encoded
            .replace("&quot;", "\"")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">");
    }

    /**
     * Checks if a string is valid Base64.
     */
    private boolean isBase64(String str) {
        try {
            Base64.getDecoder().decode(str);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Simple URL encoding for form parameters.
     */
    private String urlEncode(String value) {
        return value
            .replace(" ", "%20")
            .replace("+", "%2B")
            .replace("&", "%26")
            .replace("=", "%3D")
            .replace("\"", "%22");
    }
}
