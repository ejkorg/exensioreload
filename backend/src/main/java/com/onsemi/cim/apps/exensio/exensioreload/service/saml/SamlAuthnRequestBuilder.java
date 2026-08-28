package com.onsemi.cim.apps.exensio.exensioreload.service.saml;

import java.io.ByteArrayOutputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.UUID;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import net.shibboleth.shared.xml.SerializeSupport;
import org.joda.time.DateTime;
import org.opensaml.core.config.InitializationException;
import org.opensaml.core.config.InitializationService;
import org.opensaml.core.xml.XMLObjectBuilderFactory;
import org.opensaml.core.xml.config.XMLObjectProviderRegistrySupport;
import org.opensaml.core.xml.io.MarshallingException;
import org.opensaml.core.xml.util.XMLObjectSupport;
import org.opensaml.saml.common.SAMLVersion;
import org.opensaml.saml.saml2.core.AuthnRequest;
import org.opensaml.saml.saml2.core.Issuer;
import org.opensaml.saml.saml2.core.NameIDPolicy;
import org.opensaml.security.SecurityException;
import org.opensaml.security.x509.BasicX509Credential;
import org.opensaml.xmlsec.signature.support.Signer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Element;

/**
 * Builds SAML AuthnRequest XML for Azure AD federation.
 *
 * <p>Constructs a SAML AuthnRequest with the SP entity ID and ACS URL, optionally signed
 * with the SP private key, and returns it as a Base64-encoded string for transport
 * to the IdP.</p>
 *
 * <p>Satisfies Requirements 1.5 (AuthnRequest structure), 5.1 (optional signing),
 * and 5.2 (sign_requests flag control).</p>
 */
public class SamlAuthnRequestBuilder {

    private static final Logger logger = LoggerFactory.getLogger(SamlAuthnRequestBuilder.class);
    private static final String NAMEID_FORMAT = "urn:oasis:names:tc:SAML:1.1:nameid-format:unspecified";

    private final SamlCredentials credentials;

    static {
        try {
            InitializationService.initialize();
        } catch (InitializationException e) {
            throw new RuntimeException("Failed to initialize OpenSAML", e);
        }
    }

    /**
     * Constructs a new SAML AuthnRequest builder.
     *
     * @param credentials the SAML credentials containing entity IDs and optional signing key
     */
    public SamlAuthnRequestBuilder(SamlCredentials credentials) {
        this.credentials = credentials;
    }

    /**
     * Builds a SAML AuthnRequest, optionally signs it, and returns it as a Base64-encoded string.
     *
     * @return Base64-encoded SAML AuthnRequest XML
     * @throws SamlBuilderException if building or signing fails
     */
    public String buildAuthnRequest() {
        try {
            AuthnRequest authnRequest = buildAuthnRequestObject();

            if (credentials.signRequests()) {
                signAuthnRequest(authnRequest);
            }

            String xml = marshallToString(authnRequest);
            return Base64.getEncoder().encodeToString(xml.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new SamlBuilderException("Failed to build SAML AuthnRequest", e);
        }
    }

    /**
     * Builds the AuthnRequest SAML object with correct structure and metadata.
     */
    private AuthnRequest buildAuthnRequestObject() {
        XMLObjectBuilderFactory builderFactory = XMLObjectProviderRegistrySupport.getBuilderFactory();

        // Build Issuer
        Issuer issuer = (Issuer) builderFactory
            .getBuilder(Issuer.DEFAULT_ELEMENT_NAME)
            .buildObject(Issuer.DEFAULT_ELEMENT_NAME);
        issuer.setValue(credentials.spEntityId());

        // Build NameIDPolicy
        NameIDPolicy nameIDPolicy = (NameIDPolicy) builderFactory
            .getBuilder(NameIDPolicy.DEFAULT_ELEMENT_NAME)
            .buildObject(NameIDPolicy.DEFAULT_ELEMENT_NAME);
        nameIDPolicy.setFormat(NAMEID_FORMAT);

        // Build AuthnRequest
        AuthnRequest authnRequest = (AuthnRequest) builderFactory
            .getBuilder(AuthnRequest.DEFAULT_ELEMENT_NAME)
            .buildObject(AuthnRequest.DEFAULT_ELEMENT_NAME);
        authnRequest.setVersion(SAMLVersion.VERSION_20);
        authnRequest.setID("_" + UUID.randomUUID());
        authnRequest.setIssueInstant(new DateTime());
        authnRequest.setDestination(credentials.idpSsoUrl());
        authnRequest.setAssertionConsumerServiceURL(credentials.acsUrl());
        authnRequest.setIssuer(issuer);
        authnRequest.setNameIDPolicy(nameIDPolicy);

        return authnRequest;
    }

    /**
     * Signs the AuthnRequest XML using the SP private key.
     *
     * @param authnRequest the AuthnRequest to sign
     * @throws SamlBuilderException if signing fails
     */
    private void signAuthnRequest(AuthnRequest authnRequest) {
        try {
            if (credentials.spPrivateKey() == null || credentials.spPrivateKey().isBlank()) {
                throw new SamlBuilderException("sign_requests is true but sp_private_key is missing or blank");
            }

            PrivateKey privateKey = loadPrivateKey(credentials.spPrivateKey());
            BasicX509Credential credential = new BasicX509Credential(privateKey);

            Signer.signObject(authnRequest, credential);
            logger.debug("SAML AuthnRequest signed with SP private key");
        } catch (SecurityException e) {
            throw new SamlBuilderException("Failed to sign AuthnRequest", e);
        }
    }

    /**
     * Loads a private key from PEM-formatted string.
     *
     * @param pemPrivateKey the PEM-formatted private key
     * @return the PrivateKey object
     * @throws SamlBuilderException if key parsing fails
     */
    private PrivateKey loadPrivateKey(String pemPrivateKey) {
        try {
            String key = pemPrivateKey
                .replace("-----BEGIN RSA PRIVATE KEY-----", "")
                .replace("-----END RSA PRIVATE KEY-----", "")
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");

            byte[] decodedKey = Base64.getDecoder().decode(key);
            PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(decodedKey);
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            return keyFactory.generatePrivate(spec);
        } catch (Exception e) {
            throw new SamlBuilderException("Failed to load private key from PEM", e);
        }
    }

    /**
     * Marshalls the AuthnRequest SAML object to an XML string.
     *
     * @param authnRequest the AuthnRequest to marshall
     * @return the XML string representation
     * @throws SamlBuilderException if marshalling fails
     */
    private String marshallToString(AuthnRequest authnRequest) {
        try {
            Element element = XMLObjectSupport.marshall(authnRequest);
            StringWriter stringWriter = new StringWriter();
            TransformerFactory transformerFactory = TransformerFactory.newInstance();
            Transformer transformer = transformerFactory.newTransformer();
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
            transformer.transform(new DOMSource(element), new StreamResult(stringWriter));
            return stringWriter.toString();
        } catch (MarshallingException e) {
            throw new SamlBuilderException("Failed to marshall AuthnRequest to XML", e);
        } catch (Exception e) {
            throw new SamlBuilderException("Failed to serialize AuthnRequest XML", e);
        }
    }

    /**
     * Exception thrown when SAML AuthnRequest building fails.
     */
    public static class SamlBuilderException extends RuntimeException {
        public SamlBuilderException(String message) {
            super(message);
        }

        public SamlBuilderException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
