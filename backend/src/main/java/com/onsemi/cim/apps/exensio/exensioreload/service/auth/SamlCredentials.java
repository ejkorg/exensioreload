package com.onsemi.cim.apps.exensio.exensioreload.service.auth;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.onsemi.cim.apps.exensio.exensioreload.service.ExensioAuthService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * SAML credentials loaded from AWS Secrets Manager for Azure AD SAML SSO authentication.
 *
 * <p>The Secrets Manager secret must contain a JSON object with the following structure
 * (Requirement 3.2, 3.5):
 * <pre>{@code
 * {
 *   "idp_sso_url": "https://login.microsoftonline.com/{tenant}/saml2",
 *   "idp_entity_id": "https://sts.windows.net/{tenant}/",
 *   "idp_certificate": "-----BEGIN CERTIFICATE-----\n...\n-----END CERTIFICATE-----",
 *   "sp_entity_id": "https://exensio-prod.example.com/api/v1/saml/metadata",
 *   "acs_url": "https://exensio-prod.example.com/api/v1/saml/consumer",
 *   "sign_requests": true,
 *   "sp_private_key": "-----BEGIN RSA PRIVATE KEY-----\n...\n-----END RSA PRIVATE KEY-----",
 *   "sp_certificate": "-----BEGIN CERTIFICATE-----\n...\n-----END CERTIFICATE-----",
 *   "service_account_username": "exensio-svc@domain.com",
 *   "service_account_password": "password123",
 *   "predefined_connection": "PRODUCTION_DB"
 * }
 * }</pre>
 *
 * <p>Credentials are loaded once at first use and cached in memory for the lifetime
 * of the process (Requirement 3.3).</p>
 */
public record SamlCredentials(
        @JsonProperty("idp_sso_url")              String idpSsoUrl,
        @JsonProperty("idp_entity_id")            String idpEntityId,
        @JsonProperty("idp_certificate")          String idpCertificate,
        @JsonProperty("sp_entity_id")             String spEntityId,
        @JsonProperty("acs_url")                  String acsUrl,
        @JsonProperty("sign_requests")            boolean signRequests,
        @JsonProperty("sp_private_key")           String spPrivateKey,
        @JsonProperty("sp_certificate")           String spCertificate,
        @JsonProperty("service_account_username") String serviceAccountUsername,
        @JsonProperty("service_account_password") String serviceAccountPassword,
        @JsonProperty("predefined_connection")    String predefinedConnection
) {

    /**
     * Validates that all required fields are present and non-blank.
     *
     * @param data raw map parsed from the Secrets Manager JSON secret
     * @return a validated {@code SamlCredentials} instance
     * @throws ExensioAuthService.ExensioAuthException if any required field is missing or blank
     */
    public static SamlCredentials fromMap(Map<String, Object> data) {
        List<String> missing = new ArrayList<>();
        if (isBlank(data, "idp_sso_url"))              missing.add("idp_sso_url");
        if (isBlank(data, "idp_entity_id"))            missing.add("idp_entity_id");
        if (isBlank(data, "idp_certificate"))          missing.add("idp_certificate");
        if (isBlank(data, "sp_entity_id"))             missing.add("sp_entity_id");
        if (isBlank(data, "acs_url"))                  missing.add("acs_url");
        if (isBlank(data, "service_account_username")) missing.add("service_account_username");
        if (isBlank(data, "service_account_password")) missing.add("service_account_password");
        if (isBlank(data, "predefined_connection"))    missing.add("predefined_connection");

        if (!missing.isEmpty()) {
            throw new ExensioAuthService.ExensioAuthException(
                    "SAML secret is missing required fields: " + String.join(", ", missing));
        }

        // If sign_requests is true, sp_private_key must be present
        boolean signRequests = Boolean.parseBoolean(String.valueOf(data.getOrDefault("sign_requests", false)));
        if (signRequests && isBlank(data, "sp_private_key")) {
            throw new ExensioAuthService.ExensioAuthException(
                    "SAML secret: sign_requests is true but sp_private_key is missing");
        }

        return new SamlCredentials(
                (String) data.get("idp_sso_url"),
                (String) data.get("idp_entity_id"),
                (String) data.get("idp_certificate"),
                (String) data.get("sp_entity_id"),
                (String) data.get("acs_url"),
                signRequests,
                (String) data.get("sp_private_key"),
                (String) data.get("sp_certificate"),
                (String) data.get("service_account_username"),
                (String) data.get("service_account_password"),
                (String) data.get("predefined_connection")
        );
    }

    private static boolean isBlank(Map<String, Object> data, String key) {
        Object v = data.get(key);
        return v == null || String.valueOf(v).isBlank();
    }
}
