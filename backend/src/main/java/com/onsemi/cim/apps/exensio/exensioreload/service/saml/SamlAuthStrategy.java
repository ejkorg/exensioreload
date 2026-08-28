package com.onsemi.cim.apps.exensio.exensioreload.service.saml;

/**
 * Strategy interface for SAML authentication methods.
 *
 * <p>The SAML authentication facade uses a tiered fallback approach, attempting multiple strategies
 * in order until one succeeds. Each strategy represents a different mechanism for obtaining a SAML
 * assertion from Azure AD using service account credentials.</p>
 *
 * <p>Implementations should:</p>
 * <ul>
 *   <li>Return a base64-encoded SAML assertion on success</li>
 *   <li>Throw {@link UnsupportedOperationException} if the strategy is unavailable (e.g., Selenium not on classpath)
 *       — this signals the facade to skip to the next strategy</li>
 *   <li>Throw any other exception (including checked exceptions wrapped in RuntimeException) to signal failure
 *       — the facade will collect the error message and try the next strategy</li>
 * </ul>
 *
 * <p>Satisfies Requirements 9.1 (three-strategy fallback), 9.5 (Selenium availability check).</p>
 *
 * @see com.onsemi.cim.apps.exensio.exensioreload.service.saml.SamlAuthenticationFacade
 */
public interface SamlAuthStrategy {

    /**
     * The human-readable name of this strategy, used for logging and error messages.
     *
     * <p>Examples: "FormPost", "WsFederation", "SeleniumChrome"</p>
     *
     * @return the strategy name
     */
    String name();

    /**
     * Authenticate with Azure AD using this strategy and return a base64-encoded SAML assertion.
     *
     * <p>On success: return a SAML assertion from Azure AD in base64 format, ready for exchange
     * at Exensio's {@code POST /v1/saml/consumer} endpoint.</p>
     *
     * <p>On unavailable: throw {@link UnsupportedOperationException} with a description (e.g.,
     * "Selenium not on classpath"). The facade will skip this strategy and try the next one.</p>
     *
     * <p>On failure: throw any other exception. The facade will collect the error message,
     * log at WARN level, and attempt the next strategy. Checked exceptions should be wrapped
     * in RuntimeException.</p>
     *
     * @return base64-encoded SAML assertion
     * @throws UnsupportedOperationException if this strategy is not available in the current runtime
     * @throws Exception if authentication fails (any exception other than UnsupportedOperationException)
     */
    String authenticate() throws Exception;
}
