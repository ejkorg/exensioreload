package com.onsemi.cim.apps.exensio.exensioreload.service.saml;

/**
 * Exensio SAML token exchange response.
 *
 * <p>Returned by the Exensio API endpoint {@code POST /v1/saml/consumer} after successful
 * validation of a SAML assertion. The token is an opaque Bearer token suitable for inclusion
 * in the {@code Authorization} header of subsequent Exensio API calls.</p>
 *
 * <p>The {@code expiry} field is a Unix timestamp (seconds since epoch). If Exensio does not
 * provide an expiry value, the client defaults to now + 3600 seconds (1 hour).</p>
 *
 * <p>Satisfies Requirements 1.2 (token exchange response), 1.3 (token caching with expiry).</p>
 *
 * @param token the opaque Bearer token string for SAML-authenticated API calls
 * @param expiry Unix timestamp (seconds since epoch) when the token expires; default to now + 3600 if absent
 */
public record ExensioTokenResponse(String token, long expiry) {
}
