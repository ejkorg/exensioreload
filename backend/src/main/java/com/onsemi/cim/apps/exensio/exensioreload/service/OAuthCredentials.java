package com.onsemi.cim.apps.exensio.exensioreload.service;

/**
 * Azure AD service principal credentials for OAuth 2.0 client credentials flow.
 *
 * <p>Loaded from Secrets Manager at application startup when {@code exensio.auth-mode=OAUTH}.
 * All four fields are required; missing any field causes a startup failure.</p>
 *
 * <p>Satisfies Requirements 3.1 (credentials from Secrets Manager), 3.5 (JSON structure).</p>
 *
 * @param tenantId the Azure AD tenant ID (format: GUID)
 * @param clientId the service principal client ID (format: GUID)
 * @param clientSecret the service principal secret (long string, never logged or exposed)
 * @param scope the OAuth scope (typically "api://exensio-big-data-api/.default")
 */
public record OAuthCredentials(
        String tenantId,
        String clientId,
        String clientSecret,
        String scope
) {

    /**
     * Parse credentials from a Secrets Manager secret dictionary.
     *
     * <p>Validates that all required fields are present and non-blank.</p>
     *
     * @param data a map containing keys: "tenant_id", "client_id", "client_secret", "scope"
     * @return a new {@link OAuthCredentials} instance
     * @throws IllegalArgumentException if any required field is missing or blank
     */
    public static OAuthCredentials fromMap(java.util.Map<String, String> data) {
        String tenant = data.get("tenant_id");
        String client = data.get("client_id");
        String secret = data.get("client_secret");
        String scp = data.get("scope");

        java.util.List<String> missing = new java.util.ArrayList<>();
        if (tenant == null || tenant.isBlank()) missing.add("tenant_id");
        if (client == null || client.isBlank()) missing.add("client_id");
        if (secret == null || secret.isBlank()) missing.add("client_secret");
        if (scp == null || scp.isBlank()) missing.add("scope");

        if (!missing.isEmpty()) {
            throw new IllegalArgumentException(
                    "OAuth secret is missing required fields: " + String.join(", ", missing)
            );
        }

        return new OAuthCredentials(tenant, client, secret, scp);
    }
}
