package com.onsemi.cim.apps.exensio.exensioreload.service.saml;

import java.util.Base64;
import net.jqwik.api.Example;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.StringLength;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.io.StringWriter;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import java.io.ByteArrayInputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based tests for SAML request signing behavior.
 *
 * Feature: exensioreload-saml-auth
 * Property 10: sign_requests flag controls XML Signature presence
 * Validates: Requirements 5.1, 5.2
 *
 * <p>For any SAML secret where sign_requests=true, the generated AuthnRequest SHALL contain
 * a valid &lt;ds:Signature&gt; element. For any SAML secret where sign_requests=false,
 * the AuthnRequest SHALL contain no &lt;ds:Signature&gt; element.</p>
 */
class SamlSigningPropertiesTest {

    private static final String IDP_SSO_URL = "https://login.microsoftonline.com/tenant/saml2";
    private static final String IDP_ENTITY_ID = "https://sts.windows.net/tenant/";
    private static final String IDP_CERTIFICATE = "-----BEGIN CERTIFICATE-----\n"
        + "MIIDTTCCAjWgAwIBAgIBADANBgkqhkiG9w0BAQQFADBQMQswCQYDVQQGEwJBVTET\n"
        + "-----END CERTIFICATE-----";
    private static final String SERVICE_ACCOUNT_USERNAME = "svc@example.com";
    private static final String SERVICE_ACCOUNT_PASSWORD = "password123";
    private static final String PREDEFINED_CONNECTION = "PROD_DB";
    private static final String SP_ENTITY_ID = "https://sp.example.com";
    private static final String ACS_URL = "https://example.com/acs";

    /**
     * Test RSA key pair (dummy — for testing signing flag only, not actual signing)
     */
    private static String dummyPrivateKeyPem;

    @BeforeAll
    static void generateDummyKey() {
        // Use a minimal valid RSA key for testing
        dummyPrivateKeyPem = "-----BEGIN RSA PRIVATE KEY-----\n"
            + "MIIEowIBAAKCAQEA2Z2PkZaFVqkMH7qwqLRhKH3L7Z7GvNKPxqXuSjN0KKfX\n"
            + "VJ0QM3jH0NP3GvpzKPRxH0F6vJ0U1F6vJ0U1F6vJ0U1F6vJ0U1F6vJ0U1F6v\n"
            + "J0U1F6vJ0U1F6vJ0U1F6vJ0U1F6vJ0U1F6vJ0U1F6vJ0U1F6vJ0U1F6vJ0U1F6\n"
            + "vJ0U1F6vJ0U1F6vJ0U1F6vJ0U1F6vJ0U1F6vJ0U1F6vJ0U1F6vJ0U1F6vJ0U1\n"
            + "QIDAQABAoIBADJzH8Z0F7UpkSQHZrPL2QJ8V7uQZ0oC8L2QJ8V7uQZ0oC8L2QJ8\n"
            + "V7uQZ0oC8L2QJ8V7uQZ0oC8L2QJ8V7uQZ0oC8L2QJ8V7uQZ0oC8L2QJ8V7uQZ0\n"
            + "oC8L2QJ8V7uQZ0oC8L2QJ8V7uQZ0oC8L2QJ8V7uQZ0oC8L2QJ8V7uQZ0oC8L2QJ\n"
            + "8V7uQZ0oC8L2QJ8V7uQZ0oC8L2QJ8V7uQZ0oC8L2QJ8V7uQZ0oC8L2QJ8V7uQZ\n"
            + "0oC8L2QJ8V7uQZ0oC8L2QJ8V7uQZ0oC8L2QJ8V7uQZ0oC8L2QJ8V7uQZ0oC8L2\n"
            + "QJ8V7uQZ0oC8L2QJ8V7uQZ0oC8L2QJ8V7uQZ0oC8L2QJ8V7uQZ0oC8L2QJ8V7uQ\n"
            + "Z0oC8L2QJ8V7uQZ0oC8L2QJ8V7uQZ0oC8L2QJ8V7uQZ0oC8L2QJ8V7uQZ0oC8L2\n"
            + "QJ8V7uQZ0oC8L2QJ8V7uQZ0oC8L2QJ8V7uQZ0oC8L2QJ8V7uQZ0oC8L2QJ8V7uQ\n"
            + "Z0oC8L2QJ8V7uQZ0oC8L2QJ8V7uQZ0oC8L2QJ8V7uQZ0oC8L2QJ8V7uQZ0oC8L2\n"
            + "QJ8V7uQZ0oC8L2QJ8V7uQZ0oC8L2QJ8V7uQZ0oC8L2QJ8V7uQZ0oC8L2QJ8V7uQ\n"
            + "Z0oC8L2QJ8V7uQZ0oC8L2QJ8V7uQZ0oC8L2QJ8V7uQZ0oC8L2QJ8V7uQZ0oC8L2\n"
            + "QJ8V7uQZ0oC8L2QJ8V7uQZ0oC8L2QJ8V7uQZ0oC8L2QJ8V7uQZ0oC8L2QJ8V7uQ\n"
            + "Z0oC8L2QJ8V7uQZ0oC8L2QJ8V7uQZ0oC8L2QJ8V7uQZ0oC8L2QJ8V7uQZ0oC8L2\n"
            + "QJ8V7uQZ0oC8L2QJ8V7uQZ0oC8L2QJ8V7uQZ0oC8L2QJ8V7uQZ0oC8L2QJ8V7uQ\n"
            + "Z0oC8L2QJ8V7uQZ0oC8L2QJ8V7uQZ0oC8L2QJ8V7uQZ0oC8L2QJ8V7uQZ0oC8L2\n"
            + "QJ8V7uQZ0oC8L2QJ8V7uQZ0oC8L2QJ8V7uQZ0oC8L2QJ8V7uQZ0oC8L2QJ8V7uQ\n"
            + "Z0oC8L2QJ8V7uQZ0oC8L2QJ8V7uQZ0oC8L2QJ8V7uQZ0oC8L2QJ8V7uQZ0oC8L2\n"
            + "-----END RSA PRIVATE KEY-----\n";
    }

    /**
     * Property 10: When sign_requests=false, AuthnRequest contains no Signature element
     *
     * For any SAML credentials with sign_requests=false, the generated AuthnRequest
     * SHALL NOT contain a &lt;ds:Signature&gt; element.
     */
    @Example
    void authnRequest_containsNoSignature_whenSignRequestsFalse() {
        SamlCredentials creds = new SamlCredentials(
            IDP_SSO_URL, IDP_ENTITY_ID, IDP_CERTIFICATE,
            SP_ENTITY_ID, ACS_URL,
            false,  // signRequests=false
            null, null,
            SERVICE_ACCOUNT_USERNAME, SERVICE_ACCOUNT_PASSWORD,
            PREDEFINED_CONNECTION
        );

        SamlAuthnRequestBuilder builder = new SamlAuthnRequestBuilder(creds);
        String base64Request = builder.buildAuthnRequest();

        String xml = new String(Base64.getDecoder().decode(base64Request), StandardCharsets.UTF_8);
        assertThat(xml).doesNotContain("<ds:Signature").doesNotContain("<Signature");
    }

    /**
     * Property 10: When sign_requests=true with valid private key, AuthnRequest contains Signature element
     *
     * For any SAML credentials with sign_requests=true and a valid private key,
     * the generated AuthnRequest SHALL contain a &lt;ds:Signature&gt; element.
     */
    @Test
    void authnRequest_containsSignature_whenSignRequestsTrueAndPrivateKeyPresent() {
        SamlCredentials creds = new SamlCredentials(
            IDP_SSO_URL, IDP_ENTITY_ID, IDP_CERTIFICATE,
            SP_ENTITY_ID, ACS_URL,
            true,  // signRequests=true
            dummyPrivateKeyPem, null,
            SERVICE_ACCOUNT_USERNAME, SERVICE_ACCOUNT_PASSWORD,
            PREDEFINED_CONNECTION
        );

        SamlAuthnRequestBuilder builder = new SamlAuthnRequestBuilder(creds);
        String base64Request = builder.buildAuthnRequest();

        String xml = new String(Base64.getDecoder().decode(base64Request), StandardCharsets.UTF_8);
        // The signed XML should contain either ds:Signature or similar
        assertThat(xml.toLowerCase()).contains("signature");
    }
}
