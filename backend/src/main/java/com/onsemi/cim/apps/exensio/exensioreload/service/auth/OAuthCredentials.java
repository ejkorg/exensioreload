package com.onsemi.cim.apps.exensio.exensioreload.service.auth;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.onsemi.cim.apps.exensio.exensioreload.service.ExensioAuthService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Azure AD service principal credentials loaded from AWS Secrets Manager.
 *
 * <p>The Secrets Manager secret must contain a JSON object with the following structure
 * (Requirement 3.5):
 * <pre>{@code
 * {
 *   "tenant_id":     "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx",
 *   "client_id":     "yyyyyyyy-yyyy-yyyy-yyyy-yyyyyyyyyyyy",
 *   "client_secret": "zzzzzzzz~very-long-secret-value~",
 *   "scope":         "api://exensio-big-data-api/.default"
 * }
 * }</pre>
 *
 * <p>Credentials are loaded once at first use and cached in memory for the lifetime
 * of the process (Requirement 3.3).</p>
 */
public record OAuthCredentials(
        @JsonProperty("tenant_id")     String tenantId,
        @JsonProperty("client_id")     String clientId,
        @JsonProperty("client_secret") String clientSecret,
        @JsonProperty("scope")         String scope
) {

    /**
     * Validates that all required fields are present and non-blank.
     *
     * @param data raw map parsed from the Secrets Manager JSON secret
     * @return a validated {@code OAuthCredentials} instance
     * @throws ExensioAuthService.ExensioAuthException if any required field is missing or blank
     */
    public static OAuthCredentials fromMap(Map<String, String> data) {
        List<String> missing = new ArrayList<>();
        if (isBlank(data, "tenant_id"))     missing.add("tenant_id");
        if (isBlank(data, "client_id"))     missing.add("client_id");
        if (isBlank(data, "client_secret")) missing.add("client_secret");
        if (isBlank(data, "scope"))         missing.add("scope");

        if (!missing.isEmpty()) {
            throw new ExensioAuthService.ExensioAuthException(
                    "OAuth secret is missing required fields: " + String.join(", ", missing));
        }

        return new OAuthCredentials(
                data.get("tenant_id"),
                data.get("client_id"),
                data.get("client_secret"),
                data.get("scope")
        );
    }

    private static boolean isBlank(Map<String, String> data, String key) {
        String v = data.get(key);
        return v == null || v.isBlank();
    }
}
