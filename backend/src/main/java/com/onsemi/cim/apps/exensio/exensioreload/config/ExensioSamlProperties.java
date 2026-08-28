package com.onsemi.cim.apps.exensio.exensioreload.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.onsemi.cim.apps.exensio.exensioreload.service.ExensioAuthService;
import com.onsemi.cim.apps.exensio.exensioreload.service.auth.SamlCredentials;
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

import java.util.Map;

/**
 * Loads SAML credentials from AWS Secrets Manager for Azure AD SAML SSO authentication.
 *
 * <p>This component is conditionally created only when {@code exensio.auth-mode=SAML}.
 * It reads the secret name from the {@code exensio.saml-secret-name} property and
 * fetches the SAML JSON secret from Secrets Manager on first use, caching it in memory.</p>
 *
 * <p>Satisfies Requirements:
 * <ul>
 *   <li>3.1: "THE Auth_Service SHALL retrieve all SAML credentials exclusively from AWS Secrets Manager"</li>
 *   <li>3.2: "THE SAML secret SHALL be a JSON object containing: idp_sso_url, idp_entity_id, idp_certificate, ..."</li>
 *   <li>3.3: "THE Auth_Service SHALL cache the parsed credential values in memory for the process lifetime"</li>
 *   <li>3.4: "IF any required field is missing from the SAML secret, THEN throw IllegalStateException identifying the field"</li>
 *   <li>3.5: "THE ExensioSamlProperties component SHALL be annotated with @ConditionalOnProperty"</li>
 *   <li>5.3: "Validate sign_requests=true requires sp_private_key to be present"</li>
 * </ul>
 * </p>
 */
@Component
@ConditionalOnProperty(name = "exensio.auth-mode", havingValue = "SAML")
public class ExensioSamlProperties {

    private static final Logger log = LoggerFactory.getLogger(ExensioSamlProperties.class);

    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${exensio.saml-secret-name:}")
    private String samlSecretName;

    private SamlCredentials credentials;

    private boolean credentialsLoaded = false;

    /**
     * Fail-fast validation at startup.
     *
     * <p>Requirement 3.1, 3.3, 3.4: Load credentials eagerly from Secrets Manager
     * and validate all required fields are present. Throw descriptive error if any field is missing.</p>
     */
    @PostConstruct
    public void validateSamlConfiguration() {
        if (samlSecretName == null || samlSecretName.isBlank()) {
            String msg = "SAML is enabled (exensio.auth-mode=SAML) but exensio.saml-secret-name is missing or empty. " +
                    "Please set exensio.saml-secret-name to the Secrets Manager secret name containing SAML credentials.";
            log.error(msg);
            throw new IllegalStateException(msg);
        }

        // Load credentials eagerly to fail fast if Secrets Manager is inaccessible
        loadCredentialsFromSecretsManager();
        log.info("SAML configuration validated: secret_name={}", samlSecretName);
    }

    /**
     * Get IdP SSO URL (idp_sso_url from the SAML secret).
     */
    public String getIdpSsoUrl() {
        ensureCredentialsLoaded();
        return credentials.idpSsoUrl();
    }

    /**
     * Get IdP entity ID (idp_entity_id from the SAML secret).
     */
    public String getIdpEntityId() {
        ensureCredentialsLoaded();
        return credentials.idpEntityId();
    }

    /**
     * Get IdP certificate PEM string (idp_certificate from the SAML secret).
     */
    public String getIdpCertificate() {
        ensureCredentialsLoaded();
        return credentials.idpCertificate();
    }

    /**
     * Get SP entity ID (sp_entity_id from the SAML secret).
     */
    public String getSpEntityId() {
        ensureCredentialsLoaded();
        return credentials.spEntityId();
    }

    /**
     * Get ACS URL (acs_url from the SAML secret).
     */
    public String getAcsUrl() {
        ensureCredentialsLoaded();
        return credentials.acsUrl();
    }

    /**
     * Get whether to sign AuthnRequests (sign_requests from the SAML secret).
     */
    public boolean isSignRequests() {
        ensureCredentialsLoaded();
        return credentials.signRequests();
    }

    /**
     * Get SP private key PEM string (sp_private_key from the SAML secret, optional).
     */
    public String getSpPrivateKey() {
        ensureCredentialsLoaded();
        return credentials.spPrivateKey();
    }

    /**
     * Get SP certificate PEM string (sp_certificate from the SAML secret, optional).
     */
    public String getSpCertificate() {
        ensureCredentialsLoaded();
        return credentials.spCertificate();
    }

    /**
     * Get service account username (service_account_username from the SAML secret).
     */
    public String getServiceAccountUsername() {
        ensureCredentialsLoaded();
        return credentials.serviceAccountUsername();
    }

    /**
     * Get service account password (service_account_password from the SAML secret).
     */
    public String getServiceAccountPassword() {
        ensureCredentialsLoaded();
        return credentials.serviceAccountPassword();
    }

    /**
     * Get predefined connection name (predefined_connection from the SAML secret).
     */
    public String getPredefinedConnection() {
        ensureCredentialsLoaded();
        return credentials.predefinedConnection();
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
     * Fetch SAML credentials from AWS Secrets Manager and cache them.
     *
     * <p>Requirement 3.1: "THE Auth_Service SHALL retrieve all SAML credentials
     * exclusively from AWS Secrets Manager secret"</p>
     *
     * <p>Requirement 3.4: "IF any required field is missing from the SAML secret,
     * THEN THE Auth_Service SHALL throw an IllegalStateException at startup
     * with a message identifying the missing field by name"</p>
     *
     * <p>Secret format (JSON):
     * <pre>
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
     *   "service_account_password": "...",
     *   "predefined_connection": "PRODUCTION_DB"
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
                    .secretId(samlSecretName)
                    .build();

            GetSecretValueResponse response = secretsClient.getSecretValue(request);
            String secretString = response.secretString();

            if (secretString == null || secretString.isBlank()) {
                throw new IllegalStateException(
                        "SAML secret '" + samlSecretName + "' is empty or does not contain a value"
                );
            }

            // Parse JSON secret into a map and then into SamlCredentials record
            @SuppressWarnings("unchecked")
            Map<String, Object> secretJson = objectMapper.readValue(secretString, Map.class);

            this.credentials = SamlCredentials.fromMap(secretJson);
            this.credentialsLoaded = true;
            log.info("SAML credentials loaded from Secrets Manager (secret_name={})", samlSecretName);

        } catch (SecretsManagerException e) {
            String msg = "Failed to retrieve SAML secret '" + samlSecretName
                    + "' from Secrets Manager: " + e.getMessage();
            log.error(msg);
            throw new IllegalStateException(msg, e);
        } catch (ExensioAuthService.ExensioAuthException e) {
            // Re-throw validation errors from SamlCredentials.fromMap()
            log.error("SAML secret validation failed: {}", e.getMessage());
            throw new IllegalStateException(e.getMessage(), e);
        } catch (Exception e) {
            String msg = "Error parsing SAML secret '" + samlSecretName + "': " + e.getMessage();
            log.error(msg);
            throw new IllegalStateException(msg, e);
        }
    }
}
