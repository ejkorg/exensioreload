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
 * Direct form-POST SAML strategy for Azure AD authentication.
 *
 * <p>Sends service account credentials directly as a form POST to the Azure AD login endpoint.
 * Parses the response HTML to extract the {@code SAMLResponse} form field.
 * If the response indicates an MFA challenge or CAPTCHA, this strategy throws to trigger fallback.</p>
 *
 * <p>This is the simplest strategy and works in environments where the service account does not
 * have MFA enforcement. For MFA-enabled accounts, the fallback strategies (WS-Federation or Selenium)
 * are attempted.</p>
 *
 * <p>Satisfies Requirement 9.1 (three-strategy fallback, Strategy 1).</p>
 */
public class FormPostSamlStrategy implements SamlAuthStrategy {

    private static final Logger logger = LoggerFactory.getLogger(FormPostSamlStrategy.class);

    private static final String STRATEGY_NAME = "FormPost";

    // Regex pattern to extract SAMLResponse value from HTML form
    private static final Pattern SAML_RESPONSE_PATTERN = Pattern.compile(
        "name=\"SAMLResponse\"\\s+value=\"([^\"]+)\"",
        Pattern.CASE_INSENSITIVE
    );

    // Patterns to detect MFA or CAPTCHA challenges
    private static final Pattern MFA_CHALLENGE_PATTERN = Pattern.compile(
        "(mfa|multi-factor|additional verification|sms|totp)",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern CAPTCHA_PATTERN = Pattern.compile(
        "(captcha|recaptcha|\"g-recaptcha\"|challenge)",
        Pattern.CASE_INSENSITIVE
    );

    private final SamlCredentials credentials;
    private final HttpClient httpClient;

    public FormPostSamlStrategy(SamlCredentials credentials) {
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
        logger.debug("FormPost strategy: initiating direct form-POST to Azure AD");

        // Build and send the AuthnRequest
        String authnRequest = new SamlAuthnRequestBuilder(credentials).buildAuthnRequest();
        String loginFormUrl = buildFormPostUrl(authnRequest);

        // POST service account credentials
        String loginForm = sendLoginForm(loginFormUrl);

        // Check for MFA or CAPTCHA in the response
        if (containsMfaChallenge(loginForm)) {
            throw new Exception("MFA challenge detected in Azure AD response — falling back to next strategy");
        }
        if (containsCaptchaChallenge(loginForm)) {
            throw new Exception("CAPTCHA challenge detected in Azure AD response — falling back to next strategy");
        }

        // Extract SAMLResponse
        String samlResponse = extractSamlResponse(loginForm);
        if (samlResponse == null || samlResponse.isBlank()) {
            throw new Exception("SAMLResponse not found in Azure AD response");
        }

        logger.debug("FormPost strategy: successfully extracted SAMLResponse");
        return samlResponse;
    }

    /**
     * Builds the form-POST URL for Azure AD direct login.
     * Includes the AuthnRequest as a hidden form field to be submitted to the IdP.
     */
    private String buildFormPostUrl(String authnRequest) {
        // The idpSsoUrl is the Azure AD SAML 2.0 SSO endpoint where we POST credentials
        return credentials.idpSsoUrl();
    }

    /**
     * Sends service account credentials as a form POST to the Azure AD login endpoint.
     *
     * @param loginFormUrl the IdP SSO endpoint
     * @return the response HTML from Azure AD
     * @throws Exception if the POST fails
     */
    private String sendLoginForm(String loginFormUrl) throws Exception {
        // Construct form body: username=user&password=pass&SAMLRequest=encoded_authnrequest
        String samlRequest = new SamlAuthnRequestBuilder(credentials).buildAuthnRequest();
        String formBody = String.format(
            "username=%s&password=%s&SAMLRequest=%s",
            urlEncode(credentials.serviceAccountUsername()),
            urlEncode(credentials.serviceAccountPassword()),
            urlEncode(samlRequest)
        );

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(loginFormUrl))
            .timeout(Duration.ofSeconds(20))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(formBody))
            .build();

        HttpResponse<String> response = httpClient.send(request, BodyHandlers.ofString());

        if (response.statusCode() < 200 || response.statusCode() >= 400) {
            throw new Exception(
                "Azure AD login failed: HTTP " + response.statusCode() + 
                " — may indicate credentials are wrong, account is locked, or MFA is required"
            );
        }

        // 3xx redirects or 200 with SAML form are both valid responses here
        return response.body();
    }

    /**
     * Extracts the Base64-encoded SAMLResponse from the HTML form response.
     *
     * @param html the HTML response from Azure AD
     * @return the Base64-encoded SAMLResponse, or null if not found
     */
    private String extractSamlResponse(String html) {
        Matcher matcher = SAML_RESPONSE_PATTERN.matcher(html);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    /**
     * Checks if the response indicates an MFA challenge.
     */
    private boolean containsMfaChallenge(String html) {
        return MFA_CHALLENGE_PATTERN.matcher(html).find();
    }

    /**
     * Checks if the response indicates a CAPTCHA challenge.
     */
    private boolean containsCaptchaChallenge(String html) {
        return CAPTCHA_PATTERN.matcher(html).find();
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
