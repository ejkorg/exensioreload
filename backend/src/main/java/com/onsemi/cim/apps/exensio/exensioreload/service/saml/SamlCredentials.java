package com.onsemi.cim.apps.exensio.exensioreload.service.saml;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * SAML SSO credentials for Azure AD federation.
 *
 * <p>Loaded from Secrets Manager at application startup when {@code exensio.auth-mode=SAML}.
 * All required fields must be present; missing fields cause a startup failure with the field name
 * in the error message.</p>
 *
 * <p>The {@code sign_requests} flag controls whether SAML AuthnRequests are signed with the service
 * provider private key. When {@code true}, {@code sp_private_key} is required.</p>
 *
 * <p>Satisfies Requirements 3.2 (SAML credential structure), 3.3 (field validation),
 * 3.4 (missing field identification), 5.1 (request signing control).</p>
 *
 * @param idpSsoUrl the Azure AD SAML 2.0 SSO endpoint (e.g., https://login.microsoftonline.com/{tenant}/saml2)
 * @param idpEntityId the Identity Provider entity ID (typically the Azure AD tenant URL)
 * @param idpCertificate the IdP X.509 certificate in PEM format (used to validate SAML assertions)
 * @param spEntityId the Service Provider entity ID (typically the Exensio API base URL or a unique identifier)
 * @param acsUrl the Assertion Consumer Service URL where Azure AD POSTs the SAML assertion (e.g., https://exensio/v1/saml/consumer)
 * @param signRequests whether AuthnRequests should be signed with the service provider private key
 * @param spPrivateKey the SP private key in PEM format (required if {@code sign_requests} is true)
 * @param spCertificate the SP X.509 certificate in PEM format (optional; used for signing metadata)
 * @param serviceAccountUsername the Azure AD service account username (typically an email or UPN)
 * @param serviceAccountPassword the Azure AD service account password (never logged)
 * @param predefinedConnection the Exensio database connection name the service account is authorized to access
 */
public record SamlCredentials(
        @JsonProperty("idp_sso_url")
        String idpSsoUrl,

        @JsonProperty("idp_entity_id")
        String idpEntityId,

        @JsonProperty("idp_certificate")
        String idpCertificate,

        @JsonProperty("sp_entity_id")
        String spEntityId,

        @JsonProperty("acs_url")
        String acsUrl,

        @JsonProperty("sign_requests")
        boolean signRequests,

        @JsonProperty("sp_private_key")
        String spPrivateKey,

        @JsonProperty("sp_certificate")
        String spCertificate,

        @JsonProperty("service_account_username")
        String serviceAccountUsername,

        @JsonProperty("service_account_password")
        String serviceAccountPassword,

        @JsonProperty("predefined_connection")
        String predefinedConnection
) {
}
