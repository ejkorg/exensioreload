package com.onsemi.cim.apps.exensio.exensioreload.service.saml;

import java.util.Base64;
import java.util.UUID;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.StringLength;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Property-based tests for SamlAuthnRequestBuilder — verifies correct SAML AuthnRequest structure.
 *
 * Feature: exensioreload-saml-auth
 * Property 4: SAML AuthnRequest structure is correct
 * Validates: Requirements 1.5
 *
 * <p>For any sp_entity_id and acs_url values in the SAML secret, the generated AuthnRequest
 * XML SHALL contain the SP entity ID as the Issuer, the ACS URL as the
 * AssertionConsumerServiceURL, and urn:oasis:names:tc:SAML:1.1:nameid-format:unspecified
 * as the NameIDPolicy format.</p>
 */
class SamlAuthnRequestBuilderPropertiesTest {

    private static final String IDP_SSO_URL = "https://login.microsoftonline.com/tenant/saml2";
    private static final String IDP_ENTITY_ID = "https://sts.windows.net/tenant/";
    private static final String IDP_CERTIFICATE = "-----BEGIN CERTIFICATE-----\n"
        + "MIIDTTCCAjWgAwIBAgIBADANBgkqhkiG9w0BAQQFADBQMQswCQYDVQQGEwJBVTET\n"
        + "-----END CERTIFICATE-----";
    private static final String SERVICE_ACCOUNT_USERNAME = "svc@example.com";
    private static final String SERVICE_ACCOUNT_PASSWORD = "password123";
    private static final String PREDEFINED_CONNECTION = "PROD_DB";

    /**
     * Property 4: AuthnRequest contains correct SP entity ID
     *
     * For any sp_entity_id value, the generated AuthnRequest SHALL contain
     * that value in the Issuer element.
     */
    @Property(tries = 100)
    void authnRequestContainsCorrectSpEntityId(
        @ForAll @StringLength(min = 5, max = 200) String spEntityId
    ) {
        SamlCredentials creds = new SamlCredentials(
            IDP_SSO_URL, IDP_ENTITY_ID, IDP_CERTIFICATE,
            spEntityId, "https://example.com/acs",
            false, null, null,
            SERVICE_ACCOUNT_USERNAME, SERVICE_ACCOUNT_PASSWORD,
            PREDEFINED_CONNECTION
        );

        SamlAuthnRequestBuilder builder = new SamlAuthnRequestBuilder(creds);
        String base64Request = builder.buildAuthnRequest();

        // Decode and parse
        String xml = new String(Base64.getDecoder().decode(base64Request), StandardCharsets.UTF_8);
        assertThat(xml).contains("<saml:Issuer").contains(spEntityId);
    }

    /**
     * Property 4: AuthnRequest contains correct ACS URL
     *
     * For any acs_url value, the generated AuthnRequest SHALL contain
     * that value as the AssertionConsumerServiceURL attribute.
     */
    @Property(tries = 100)
    void authnRequestContainsCorrectAcsUrl(
        @ForAll @StringLength(min = 10, max = 200) String acsUrl
    ) {
        SamlCredentials creds = new SamlCredentials(
            IDP_SSO_URL, IDP_ENTITY_ID, IDP_CERTIFICATE,
            "https://sp.example.com", acsUrl,
            false, null, null,
            SERVICE_ACCOUNT_USERNAME, SERVICE_ACCOUNT_PASSWORD,
            PREDEFINED_CONNECTION
        );

        SamlAuthnRequestBuilder builder = new SamlAuthnRequestBuilder(creds);
        String base64Request = builder.buildAuthnRequest();

        // Decode and verify
        String xml = new String(Base64.getDecoder().decode(base64Request), StandardCharsets.UTF_8);
        assertThat(xml).contains("AssertionConsumerServiceURL=\"" + acsUrl + "\"");
    }

    /**
     * Property 4: AuthnRequest contains correct NameIDPolicy format
     *
     * For any valid SAML credentials, the generated AuthnRequest SHALL contain
     * the NameIDPolicy format as urn:oasis:names:tc:SAML:1.1:nameid-format:unspecified.
     */
    @Property(tries = 100)
    void authnRequestContainsCorrectNameIdPolicyFormat(
        @ForAll @StringLength(min = 5, max = 100) String entityId
    ) {
        SamlCredentials creds = new SamlCredentials(
            IDP_SSO_URL, IDP_ENTITY_ID, IDP_CERTIFICATE,
            entityId, "https://example.com/acs",
            false, null, null,
            SERVICE_ACCOUNT_USERNAME, SERVICE_ACCOUNT_PASSWORD,
            PREDEFINED_CONNECTION
        );

        SamlAuthnRequestBuilder builder = new SamlAuthnRequestBuilder(creds);
        String base64Request = builder.buildAuthnRequest();

        String xml = new String(Base64.getDecoder().decode(base64Request), StandardCharsets.UTF_8);
        String expectedFormat = "urn:oasis:names:tc:SAML:1.1:nameid-format:unspecified";
        assertThat(xml).contains("Format=\"" + expectedFormat + "\"");
    }

    /**
     * Property 4: AuthnRequest is valid Base64
     *
     * For any SAML credentials, the returned AuthnRequest SHALL be valid Base64.
     */
    @Example
    void authnRequestIsValidBase64() {
        SamlCredentials creds = new SamlCredentials(
            IDP_SSO_URL, IDP_ENTITY_ID, IDP_CERTIFICATE,
            "https://sp.example.com", "https://example.com/acs",
            false, null, null,
            SERVICE_ACCOUNT_USERNAME, SERVICE_ACCOUNT_PASSWORD,
            PREDEFINED_CONNECTION
        );

        SamlAuthnRequestBuilder builder = new SamlAuthnRequestBuilder(creds);
        String base64Request = builder.buildAuthnRequest();

        // Should not throw
        byte[] decoded = Base64.getDecoder().decode(base64Request);
        assertThat(decoded).isNotEmpty();
        assertThat(new String(decoded, StandardCharsets.UTF_8)).startsWith("<?xml").contains("AuthnRequest");
    }

    /**
     * Test: Missing sp_private_key when sign_requests=true throws SamlBuilderException
     */
    @Test
    void buildAuthnRequest_throwsException_whenSignRequestsTrueButPrivateKeyMissing() {
        SamlCredentials creds = new SamlCredentials(
            IDP_SSO_URL, IDP_ENTITY_ID, IDP_CERTIFICATE,
            "https://sp.example.com", "https://example.com/acs",
            true, null, null,  // signRequests=true, spPrivateKey=null
            SERVICE_ACCOUNT_USERNAME, SERVICE_ACCOUNT_PASSWORD,
            PREDEFINED_CONNECTION
        );

        SamlAuthnRequestBuilder builder = new SamlAuthnRequestBuilder(creds);
        assertThatThrownBy(builder::buildAuthnRequest)
            .isInstanceOf(SamlAuthnRequestBuilder.SamlBuilderException.class)
            .hasMessageContaining("sp_private_key");
    }

    /**
     * Test: Missing sp_private_key when sign_requests=true and blank throws SamlBuilderException
     */
    @Test
    void buildAuthnRequest_throwsException_whenSignRequestsTrueButPrivateKeyBlank() {
        SamlCredentials creds = new SamlCredentials(
            IDP_SSO_URL, IDP_ENTITY_ID, IDP_CERTIFICATE,
            "https://sp.example.com", "https://example.com/acs",
            true, "", null,  // signRequests=true, spPrivateKey=""
            SERVICE_ACCOUNT_USERNAME, SERVICE_ACCOUNT_PASSWORD,
            PREDEFINED_CONNECTION
        );

        SamlAuthnRequestBuilder builder = new SamlAuthnRequestBuilder(creds);
        assertThatThrownBy(builder::buildAuthnRequest)
            .isInstanceOf(SamlAuthnRequestBuilder.SamlBuilderException.class)
            .hasMessageContaining("sp_private_key");
    }
}
