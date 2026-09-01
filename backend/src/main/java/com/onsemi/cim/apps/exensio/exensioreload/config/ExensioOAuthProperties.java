package com.onsemi.cim.apps.exensio.exensioreload.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;
import software.amazon.awssdk.services.secretsmanager.model.SecretsManagerException;

/**
 * Loads OAuth credentials from AWS Secrets Manager for Azure AD authentication.
 *
 * <p>This component is conditionally created only when {@code exensio.auth-mode=OAUTH}.
 * It reads the secret name from the {@code exensio.oauth-secret-name} property and
 * fetches the OAuth JSON secret from Secrets Manager on first use, caching it in memory.</p>
 *
 * <p>Satisfies Requirements:
 * <ul>
 *   <li>3.1: "THE Auth_Service SHALL retrieve Azure AD credentials exclusively from AWS Secrets Manager"</li>
 *   <li>3.3: "THE Auth_Service SHALL cache the credential values in memory for the lifetime
 *            of the process and SHALL NOT call Secrets Manager on every token request"</li>
 *   <li>2.5: "THE deployment infrastructure SHALL create the OAuth credential secret with
 *            encryption at rest using AWS KMS"</li>
 *   <li>5.4: "THE Exensioreload configuration SHALL add exensio.auth-mode as a new property
 *            with default value SESSION"</li>
 * </ul>
 * </p>
 */
@Component
@ConditionalOnProperty(name = "exensio.auth-mode", havingValue = "OAUTH")
public class ExensioOAuthProperties {

    private static final Logger log = LoggerFactory.getLogger(ExensioOAuthProperties.class);

    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${exensio.oauth-secret-name:}")
    private String oauthSecretName;

    private String tenantId;
    private String clientId;
    private String clientSecret;
    private String scope;

    private boolean credentialsLoaded = false;

    /**
     * Fail-fast validation at startup.
     *
     * <p>Requirement 2.5: "THE Auth_Service SHALL validate on startup that the required
     * credentials for the configured AUTH_MODE are present, and fail fast with a descriptive
     * error if they are not"</p>
     */
    @PostConstruct
    public void validateOAuthConfiguration() {
        if (oauthSecretName == null || oauthSecretName.isBlank()) {
            String msg = "OAuth is enabled (exensio.auth-mode=OAUTH) but exensio.oauth-secret-name is missing or empty. "
                    + "Please set exensio.oauth-secret-name to the Secrets Manager secret name containing "
                    + "{\"tenant_id\": \"...\", \"client_id\": \"...\", \"client_secret\": \"...\", \"scope\": \"...\"}";
            log.error(msg);
            throw new IllegalStateException(msg);
        }

        // Load credentials eagerly to fail fast if Secrets Manager is inaccessible
        loadCredentialsFromSecretsManager();
        log.info("OAuth configuration validated: secret_name={}", oauthSecretName);
    }

    /**
     * Get tenant ID (tenant_id from the OAuth secret).
     */
    public String getTenantId() {
        ensureCredentialsLoaded();
        return tenantId;
    }

    /**
     * Get client ID (client_id from the OAuth secret).
     */
    public String getClientId() {
        ensureCredentialsLoaded();
        return clientId;
    }

    /**
     * Get client secret (client_secret from the OAuth secret).
     */
    public String getClientSecret() {
        ensureCredentialsLoaded();
        return clientSecret;
    }

    /**
     * Get OAuth scope (scope from the OAuth secret).
     */
    public String getScope() {
        ensureCredentialsLoaded();
        return scope;
    }

    /**
     * Ensure credentials are loaded (cached for process lifetime).
     *
     * <p>Requirement 3.3: "THE Auth_Service SHALL cache the credential values in memory
     * for the lifetime of the process and SHALL NOT call Secrets Manager on every token request"</p>
     */
    private void ensureCredentialsLoaded() {
        if (!credentialsLoaded) {
            loadCredentialsFromSecretsManager();
        }
    }

    /**
     * Fetch OAuth credentials from AWS Secrets Manager and cache them.
     *
     * <p>Requirement 3.1: "THE Auth_Service SHALL retrieve Azure AD credentials (tenant ID,
     * client ID, client secret, and OAuth scope) exclusively from AWS Secrets Manager"</p>
     *
     * <p>Secret format (JSON):
     * <pre>
     * {
     *   "tenant_id": "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx",
     *   "client_id": "yyyyyyyy-yyyy-yyyy-yyyy-yyyyyyyyyyyy",
     *   "client_secret": "zzzzzzzz~very-long-secret-value~",
     *   "scope": "api://exensio-big-data-api/.default"
     * }
     * </pre>
     * </p>
     */
    private void loadCredentialsFromSecretsManager() {
        if (credentialsLoaded) {
            return;
        }

        try (SecretsManagerClient secretsClient = SecretsManagerClient.builder().build()) {
            GetSecretValueRequest request = GetSecretValueRequest.builder()
                    .secretId(oauthSecretName)
                    .build();

            GetSecretValueResponse response = secretsClient.getSecretValue(request);
            String secretString = response.secretString();

            if (secretString == null || secretString.isBlank()) {
                throw new IllegalStateException(
                        "OAuth secret '" + oauthSecretName + "' is empty or does not contain a value"
                );
            }

            // Parse JSON secret
            JsonNode secretJson = objectMapper.readTree(secretString);

            this.tenantId = extractRequiredField(secretJson, "tenant_id");
            this.clientId = extractRequiredField(secretJson, "client_id");
            this.clientSecret = extractRequiredField(secretJson, "client_secret");
            this.scope = extractRequiredField(secretJson, "scope");

            this.credentialsLoaded = true;
            log.info("OAuth credentials loaded from Secrets Manager (secret_name={})", oauthSecretName);

        } catch (SecretsManagerException e) {
            String msg = "Failed to retrieve OAuth secret '" + oauthSecretName
                    + "' from Secrets Manager: " + e.getMessage();
            log.error(msg);
            throw new IllegalStateException(msg, e);
        } catch (Exception e) {
            String msg = "Error parsing OAuth secret '" + oauthSecretName + "': " + e.getMessage();
            log.error(msg);
            throw new IllegalStateException(msg, e);
        }
    }

    /**
     * Extract a required field from the OAuth secret JSON.
     *
     * @param json the parsed JSON object
     * @param fieldName the field name to extract
     * @return the field value as text
     * @throws IllegalStateException if the field is missing or blank
     */
    private String extractRequiredField(JsonNode json, String fieldName) {
        String value = json.path(fieldName).asText(null);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "OAuth secret '" + oauthSecretName + "' is missing required field: " + fieldName
            );
        }
        return value;
    }
}
