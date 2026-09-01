package com.onsemi.cim.apps.exensio.exensioreload.service.saml;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Base64;
import org.opensaml.core.config.InitializationException;
import org.opensaml.core.config.InitializationService;
import org.opensaml.core.xml.XMLObject;
import org.opensaml.core.xml.io.Unmarshaller;
import org.opensaml.core.xml.io.UnmarshallingException;
import org.opensaml.core.xml.util.XMLObjectSupport;
import org.opensaml.saml.saml2.core.Assertion;
import org.opensaml.saml.saml2.core.Response;
import org.opensaml.saml.saml2.core.Subject;
import org.opensaml.security.x509.BasicX509Credential;
import org.opensaml.xmlsec.signature.Signature;
import org.opensaml.xmlsec.signature.support.SignatureException;
import org.opensaml.xmlsec.signature.support.SignatureValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import com.onsemi.cim.apps.exensio.exensioreload.service.ExensioAuthService;

/**
 * Validates SAML assertions received from Azure AD.
 *
 * <p>Parses a base64-encoded SAML response, validates its XML signature using the IdP certificate,
 * and extracts the authenticated user's NameID (sAMAccountName). Throws {@code ExensioAuthException}
 * if the signature is invalid or the assertion is malformed.</p>
 *
 * <p>Satisfies Requirements 5.4 (assertion signature validation) and Property 11 (signature validated
 * before token exchange).</p>
 */
public class SamlAssertionValidator {

    private static final Logger logger = LoggerFactory.getLogger(SamlAssertionValidator.class);

    static {
        try {
            InitializationService.initialize();
        } catch (InitializationException e) {
            throw new RuntimeException("Failed to initialize OpenSAML", e);
        }
    }

    /**
     * Validates the XML signature of a SAML response and extracts the NameID from the assertion.
     *
     * @param base64SamlResponse the base64-encoded SAML response from Azure AD
     * @param idpCertificatePem the IdP X.509 certificate in PEM format (used to validate the signature)
     * @return the NameID (sAMAccountName) of the authenticated user
     * @throws ExensioAuthService.ExensioAuthException if signature validation fails or assertion is malformed
     */
    public static String validateAndExtractNameId(String base64SamlResponse, String idpCertificatePem) {
        try {
            // Decode the base64 SAML response
            byte[] decodedResponse = Base64.getDecoder().decode(base64SamlResponse);
            String samlXml = new String(decodedResponse, StandardCharsets.UTF_8);

            // Parse the XML document
            Document document = parseXml(samlXml);
            Element responseElement = document.getDocumentElement();

            // Unmarshal to OpenSAML Response object
            Response response = (Response) unmarshallElement(responseElement);

            // Extract the assertion (typically there's one assertion per response)
            if (response.getAssertions().isEmpty()) {
                throw new ExensioAuthService.ExensioAuthException(
                    "SAML response contains no assertions");
            }

            Assertion assertion = response.getAssertions().get(0);

            // Validate the assertion signature
            validateAssertionSignature(assertion, idpCertificatePem);

            // Extract and return the NameID
            String nameId = extractNameId(assertion);
            logger.debug("SAML assertion validated successfully for user: {}", nameId);
            return nameId;

        } catch (ExensioAuthService.ExensioAuthException e) {
            throw e;
        } catch (Exception e) {
            throw new ExensioAuthService.ExensioAuthException(
                "SAML assertion validation failed: " + e.getMessage(), e);
        }
    }

    /**
     * Parses an XML string into a W3C Document.
     *
     * @param xmlString the XML as a string
     * @return the parsed Document
     * @throws ExensioAuthService.ExensioAuthException if parsing fails
     */
    private static Document parseXml(String xmlString) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);

            DocumentBuilder builder = factory.newDocumentBuilder();
            return builder.parse(new ByteArrayInputStream(xmlString.getBytes(StandardCharsets.UTF_8)));
        } catch (ParserConfigurationException | SAXException e) {
            throw new ExensioAuthService.ExensioAuthException(
                "Failed to parse SAML XML: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new ExensioAuthService.ExensioAuthException(
                "XML parsing error: " + e.getMessage(), e);
        }
    }

    /**
     * Unmarshalls a W3C Element into an OpenSAML XMLObject.
     *
     * @param element the W3C Element
     * @return the unmarshalled XMLObject
     * @throws ExensioAuthService.ExensioAuthException if unmarshalling fails
     */
    private static XMLObject unmarshallElement(Element element) {
        try {
            Unmarshaller unmarshaller = XMLObjectSupport.getUnmarshaller(element);
            if (unmarshaller == null) {
                throw new ExensioAuthService.ExensioAuthException(
                    "No OpenSAML unmarshaller registered for element: " + element.getNodeName());
            }
            return unmarshaller.unmarshall(element);
        } catch (UnmarshallingException e) {
            throw new ExensioAuthService.ExensioAuthException(
                "Failed to unmarshal SAML Response: " + e.getMessage(), e);
        }
    }

    /**
     * Validates the XML signature of the SAML assertion using the IdP certificate.
     *
     * @param assertion the SAML assertion
     * @param idpCertificatePem the IdP X.509 certificate in PEM format
     * @throws ExensioAuthService.ExensioAuthException if signature validation fails
     */
    private static void validateAssertionSignature(Assertion assertion, String idpCertificatePem) {
        // Extract the signature from the assertion
        Signature signature = assertion.getSignature();
        if (signature == null) {
            throw new ExensioAuthService.ExensioAuthException(
                "SAML assertion is not signed");
        }

        try {
            // Load the IdP certificate
            X509Certificate idpCertificate = loadX509Certificate(idpCertificatePem);
            BasicX509Credential credential = new BasicX509Credential(idpCertificate);

            // Validate the signature
            SignatureValidator.validate(signature, credential);
            logger.debug("SAML assertion signature validated successfully");

        } catch (ExensioAuthService.ExensioAuthException e) {
            throw e;
        } catch (SignatureException e) {
            throw new ExensioAuthService.ExensioAuthException(
                "SAML assertion signature validation failed: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new ExensioAuthService.ExensioAuthException(
                "SAML signature validation error: " + e.getMessage(), e);
        }
    }

    /**
     * Loads an X.509 certificate from a PEM-formatted string.
     *
     * @param certificatePem the certificate in PEM format
     * @return the X509Certificate
     * @throws ExensioAuthService.ExensioAuthException if certificate loading fails
     */
    private static X509Certificate loadX509Certificate(String certificatePem) {
        try {
            String cert = certificatePem
                .replace("-----BEGIN CERTIFICATE-----", "")
                .replace("-----END CERTIFICATE-----", "")
                .replaceAll("\\s", "");

            byte[] decodedCert = Base64.getDecoder().decode(cert);
            CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");
            return (X509Certificate) certificateFactory.generateCertificate(
                new ByteArrayInputStream(decodedCert));
        } catch (CertificateException e) {
            throw new ExensioAuthService.ExensioAuthException(
                "Failed to load IdP X.509 certificate: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new ExensioAuthService.ExensioAuthException(
                "Certificate loading error: " + e.getMessage(), e);
        }
    }

    /**
     * Extracts the NameID (sAMAccountName) from a SAML assertion's subject.
     *
     * @param assertion the SAML assertion
     * @return the NameID value
     * @throws ExensioAuthService.ExensioAuthException if NameID is not found
     */
    private static String extractNameId(Assertion assertion) {
        Subject subject = assertion.getSubject();
        if (subject == null) {
            throw new ExensioAuthService.ExensioAuthException(
                "SAML assertion subject is missing");
        }

        if (subject.getNameID() == null) {
            throw new ExensioAuthService.ExensioAuthException(
                "SAML assertion NameID is missing");
        }

        String nameId = subject.getNameID().getValue();
        if (nameId == null || nameId.isBlank()) {
            throw new ExensioAuthService.ExensioAuthException(
                "SAML assertion NameID is empty");
        }

        return nameId;
    }
}
