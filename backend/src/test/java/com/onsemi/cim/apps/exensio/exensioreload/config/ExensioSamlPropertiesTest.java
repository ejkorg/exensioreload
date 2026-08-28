package com.onsemi.cim.apps.exensio.exensioreload.config;

import com.onsemi.cim.apps.exensio.exensioreload.service.auth.SamlCredentials;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for ExensioSamlProperties SAML credential validation.
 *
 * Requirement 3.4: "IF any required field is missing from the SAML secret,
 * THEN THE Auth_Service SHALL throw an IllegalStateException at startup with a
 * message identifying the missing field by name"
 *
 * Test Pattern: Generate valid SAML credentials, then remove one field at a time,
 * and verify that the exception message contains the field name.
 */
public class ExensioSamlPropertiesTest {

    /**
     * Helper: Create a complete valid SAML credentials map for testing.
     */
    private static Map<String, Object> createValidSamlCredentialsMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("idp_sso_url", "https://login.microsoftonline.com/tenant-id/saml2");
        map.put("idp_entity_id", "https://sts.windows.net/tenant-id/");
        map.put("idp_certificate", "-----BEGIN CERTIFICATE-----\nMIIC...\n-----END CERTIFICATE-----");
        map.put("sp_entity_id", "https://exensio-prod.example.com/api/v1/saml/metadata");
        map.put("acs_url", "https://exensio-prod.example.com/api/v1/saml/consumer");
        map.put("sign_requests", false);
        map.put("sp_private_key", null);
        map.put("sp_certificate", null);
        map.put("service_account_username", "exensio-svc@domain.com");
        map.put("service_account_password", "password123");
        map.put("predefined_connection", "PRODUCTION_DB");
        return map;
    }

    /**
     * Property 6: Missing required secret field causes startup failure naming that field.
     *
     * **Validates: Requirements 3.4**
     *
     * For each required field, verify that removing it from the SAML secret
     * causes SamlCredentials.fromMap() to throw an exception that names the missing field.
     */
    @ParameterizedTest(name = "Missing field: {0}")
    @ValueSource(strings = {
            "idp_sso_url",
            "idp_entity_id",
            "idp_certificate",
            "sp_entity_id",
            "acs_url",
            "service_account_username",
            "service_account_password",
            "predefined_connection"
    })
    public void testMissingRequiredFieldThrowsWithFieldNameInMessage(String missingField) {
        // Arrange: Create valid map and remove one field
        Map<String, Object> credentials = createValidSamlCredentialsMap();
        credentials.remove(missingField);

        // Act & Assert: Verify exception message contains the field name
        Exception exception = assertThrows(Exception.class, () -> {
            SamlCredentials.fromMap(credentials);
        });

        assertTrue(
                exception.getMessage().contains(missingField),
                "Exception message should contain missing field name: " + missingField
                        + ", but got: " + exception.getMessage()
        );
    }

    /**
     * Verify that when sign_requests=true but sp_private_key is missing,
     * an exception is thrown naming the sp_private_key field.
     *
     * **Validates: Requirements 3.4, 5.3**
     */
    @Test
    public void testSignRequestsTrueRequiresSpPrivateKey() {
        // Arrange: Create map with sign_requests=true but no sp_private_key
        Map<String, Object> credentials = createValidSamlCredentialsMap();
        credentials.put("sign_requests", true);
        credentials.put("sp_private_key", null);

        // Act & Assert: Verify exception message mentions sp_private_key
        Exception exception = assertThrows(Exception.class, () -> {
            SamlCredentials.fromMap(credentials);
        });

        assertTrue(
                exception.getMessage().contains("sp_private_key"),
                "Exception should mention sp_private_key when sign_requests=true and key is missing"
        );
    }

    /**
     * Verify that valid SAML credentials with all required fields parse successfully.
     *
     * **Edge Case: Valid complete credentials**
     */
    @Test
    public void testValidCompleteCredentialsParseSuccessfully() {
        // Arrange: Create complete valid credentials
        Map<String, Object> credentials = createValidSamlCredentialsMap();

        // Act: Parse should succeed without exception
        SamlCredentials result = SamlCredentials.fromMap(credentials);

        // Assert: Verify all fields are populated
        assert result.idpSsoUrl().equals("https://login.microsoftonline.com/tenant-id/saml2");
        assert result.idpEntityId().equals("https://sts.windows.net/tenant-id/");
        assert result.spEntityId().equals("https://exensio-prod.example.com/api/v1/saml/metadata");
        assert result.acsUrl().equals("https://exensio-prod.example.com/api/v1/saml/consumer");
        assert result.serviceAccountUsername().equals("exensio-svc@domain.com");
        assert result.serviceAccountPassword().equals("password123");
        assert result.predefinedConnection().equals("PRODUCTION_DB");
    }

    /**
     * Verify that blank/whitespace values are treated as missing fields.
     *
     * **Edge Case: Whitespace-only required field**
     */
    @Test
    public void testBlankRequiredFieldThrowsWithFieldName() {
        // Arrange: Create map with a required field as whitespace only
        Map<String, Object> credentials = createValidSamlCredentialsMap();
        credentials.put("idp_sso_url", "   ");  // Whitespace only

        // Act & Assert: Verify exception mentions the blank field
        Exception exception = assertThrows(Exception.class, () -> {
            SamlCredentials.fromMap(credentials);
        });

        assertTrue(
                exception.getMessage().contains("idp_sso_url"),
                "Exception should mention idp_sso_url when it is blank/whitespace"
        );
    }
}
