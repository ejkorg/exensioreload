package com.onsemi.cim.apps.exensio.exensioreload.service;

/**
 * Controls which Exensio authentication flow is active.
 *
 * <p>Maps directly to the {@code exensio.auth-mode} application property.
 * If the property is absent or unrecognised, the factory defaults to
 * {@link #SESSION} and logs a warning (Requirement 2.4).</p>
 */
public enum AuthMode {

    /**
     * Username / password session-token flow via {@code POST /v1/session/login}.
     * This is the default and preserves the pre-migration behaviour (Requirement 2.2, 7.1).
     */
    SESSION,

    /**
     * Azure AD OAuth 2.0 client credentials flow.
     * An OIDC Bearer token is obtained from the Azure AD token endpoint and
     * cached until near expiry (Requirement 1.1).
     */
    OAUTH,

    /**
     * Azure AD SAML SSO flow.
     * A SAML assertion is obtained from Azure AD and exchanged for a Bearer token
     * via {@code POST /v1/saml/consumer} (Requirement 1.1).
     */
    SAML
}
