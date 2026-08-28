package com.onsemi.cim.apps.exensio.exensioreload.service.saml;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Date;

import net.jqwik.api.Example;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.opensaml.core.config.InitializationException;
import org.opensaml.core.config.InitializationService;
import org.opensaml.core.xml.XMLObjectBuilderFactory;
import org.opensaml.core.xml.config.XMLObjectProviderRegistrySupport;
import org.opensaml.core.xml.io.MarshallingException;
import org.opensaml.core.xml.util.XMLObjectSupport;
import org.opensaml.saml.common.SAMLVersion;
import org.opensaml.saml.saml2.core.Assertion;
import org.opensaml.saml.saml2.core.Issuer;
import org.opensaml.saml.saml2.core.NameID;
import org.opensaml.saml.saml2.core.Response;
import org.opensaml.saml.saml2.core.Subject;
import org.opensaml.saml.saml2.core.SubjectConfirmation;
import org.opensaml.saml.saml2.core.SubjectConfirmationData;
import org.opensaml.security.SecurityException;
import org.opensaml.security.credential.Credential;
import org.opensaml.security.x509.BasicX509Credential;
import org.opensaml.xmlsec.signature.support.Signer;
import org.w3c.dom.Element;

import sun.security.x509.AlgorithmId;
import sun.security.x509.CertificateAlgorithmId;
import sun.security.x509.CertificateIssuerName;
import sun.security.x509.CertificateSerialNumber;
import sun.security.x509.CertificateSubjectName;
import sun.security.x509.CertificateValidity;
import sun.security.x509.CertificateVersion;
import sun.security.x509.CertificateX509Key;
import sun.security.x509.X500Name;
import sun.security.x509.X509CertImpl;
import sun.security.x509.X509CertInfo;

import com.onsemi.cim.apps.exensio.exensioreload.service.ExensioAuthService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Property-based tests for SAML assertion signature validation.
 *
 * Feature: exensioreload-saml-auth
 * Property 11: SAML assertion signature is validated before token exchange
 * Validates: Requirements 5.4
 *
 * <p>For any SAML assertion whose XML signature does not verify against the IdP certificate,
 * the service SHALL throw ExensioAuthService.ExensioAuthException and SHALL NOT call
 * POST /v1/saml/consumer.</p>
 */
class SamlAssertionValidatorPropertiesTest {

    private static KeyPair keyPair;
    private static X509Certificate certificate;
    private static String certificatePem;

    @BeforeAll
    static void setupKeyPair() throws Exception {
        InitializationService.initialize();

        // Generate a 2048-bit RSA key pair
        KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
        keyPairGenerator.initialize(2048);
        keyPair = keyPairGenerator.generateKeyPair();

        // Create a self-signed certificate
        certificate = generateSelfSignedCertificate(keyPair);
        certificatePem = toPem(certificate);
    }

    /**
     * Property 11: Invalid assertion signature throws ExensioAuthException before token exchange
     *
     * For any SAML response with an invalid signature (e.g., signed by a different key),
     * validateAndExtractNameId SHALL throw ExensioAuthException.
     */
    @Example
    void assertionValidator_throwsException_whenSignatureIsInvalid() throws Exception {
        // Create a valid signed assertion with one key pair
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(2048);
        KeyPair validKeyPair = keyGen.generateKeyPair();
        X509Certificate validCertificate = generateSelfSignedCertificate(validKeyPair);
        String validCertPem = toPem(validCertificate);

        // Create a valid assertion signed with the validKeyPair
        Assertion assertion = createSignedAssertion("user@example.com", validKeyPair.getPrivate());

        // Wrap in a Response
        Response response = (Response) XMLObjectProviderRegistrySupport.getBuilderFactory()
            .getBuilder(Response.DEFAULT_ELEMENT_NAME)
            .buildObject(Response.DEFAULT_ELEMENT_NAME);
        response.getAssertions().add(assertion);

        // Marshal and encode
        String base64Response = marshalAndEncode(response);

        // Now try to validate with a DIFFERENT certificate (from our keyPair, not validKeyPair)
        // This should fail because the signature was created with validKeyPair
        assertThatThrownBy(() -> SamlAssertionValidator.validateAndExtractNameId(base64Response, certificatePem))
            .isInstanceOf(ExensioAuthService.ExensioAuthException.class)
            .hasMessageContaining("signature validation failed");
    }

    /**
     * Property 11: Valid assertion signature passes validation and extracts NameID
     *
     * For any SAML response with a valid signature (signed with the matching private key),
     * validateAndExtractNameId SHALL succeed and return the NameID.
     */
    @Test
    void assertionValidator_succeedsAndExtractsNameId_whenSignatureIsValid() throws Exception {
        // Create a signed assertion with our keyPair
        Assertion assertion = createSignedAssertion("user@example.com", keyPair.getPrivate());

        // Wrap in a Response
        Response response = (Response) XMLObjectProviderRegistrySupport.getBuilderFactory()
            .getBuilder(Response.DEFAULT_ELEMENT_NAME)
            .buildObject(Response.DEFAULT_ELEMENT_NAME);
        response.getAssertions().add(assertion);

        // Marshal and encode
        String base64Response = marshalAndEncode(response);

        // Validate with the matching certificate
        String nameId = SamlAssertionValidator.validateAndExtractNameId(base64Response, certificatePem);

        assertThat(nameId).isEqualTo("user@example.com");
    }

    /**
     * Property 11: Missing assertion signature throws exception before token exchange
     *
     * For any SAML response with an unsigned assertion, validateAndExtractNameId
     * SHALL throw ExensioAuthException.
     */
    @Test
    void assertionValidator_throwsException_whenAssertionHasNoSignature() throws Exception {
        // Create an unsigned assertion
        Assertion assertion = createUnsignedAssertion("user@example.com");

        // Wrap in a Response
        Response response = (Response) XMLObjectProviderRegistrySupport.getBuilderFactory()
            .getBuilder(Response.DEFAULT_ELEMENT_NAME)
            .buildObject(Response.DEFAULT_ELEMENT_NAME);
        response.getAssertions().add(assertion);

        // Marshal and encode
        String base64Response = marshalAndEncode(response);

        // Should throw because there's no signature
        assertThatThrownBy(() -> SamlAssertionValidator.validateAndExtractNameId(base64Response, certificatePem))
            .isInstanceOf(ExensioAuthService.ExensioAuthException.class)
            .hasMessageContaining("not signed");
    }

    /**
     * Property 11: Missing NameID throws exception
     *
     * For any SAML response where the assertion has no NameID in the subject,
     * validateAndExtractNameId SHALL throw ExensioAuthException.
     */
    @Test
    void assertionValidator_throwsException_whenNameIdIsMissing() throws Exception {
        // Create an assertion without NameID
        Assertion assertion = createSignedAssertionWithoutNameId(keyPair.getPrivate());

        // Wrap in a Response
        Response response = (Response) XMLObjectProviderRegistrySupport.getBuilderFactory()
            .getBuilder(Response.DEFAULT_ELEMENT_NAME)
            .buildObject(Response.DEFAULT_ELEMENT_NAME);
        response.getAssertions().add(assertion);

        // Marshal and encode
        String base64Response = marshalAndEncode(response);

        // Should throw because there's no NameID
        assertThatThrownBy(() -> SamlAssertionValidator.validateAndExtractNameId(base64Response, certificatePem))
            .isInstanceOf(ExensioAuthService.ExensioAuthException.class)
            .hasMessageContaining("NameID");
    }

    /**
     * Property 11: Tampered assertion signature throws exception before token exchange
     *
     * For any SAML response where the assertion signature has been modified after creation,
     * validateAndExtractNameId SHALL throw ExensioAuthException.
     */
    @Test
    void assertionValidator_throwsException_whenAssertionIsTampered() throws Exception {
        // Create a valid signed assertion
        Assertion assertion = createSignedAssertion("user@example.com", keyPair.getPrivate());

        // Wrap in a Response
        Response response = (Response) XMLObjectProviderRegistrySupport.getBuilderFactory()
            .getBuilder(Response.DEFAULT_ELEMENT_NAME)
            .buildObject(Response.DEFAULT_ELEMENT_NAME);
        response.getAssertions().add(assertion);

        // Marshal and encode
        String base64Response = marshalAndEncode(response);

        // Decode, tamper with it, and re-encode
        byte[] decoded = Base64.getDecoder().decode(base64Response);
        String xml = new String(decoded, StandardCharsets.UTF_8);
        // Change the NameID value (without updating the signature)
        String tamperedXml = xml.replace("user@example.com", "attacker@example.com");
        String tamperedBase64 = Base64.getEncoder().encodeToString(tamperedXml.getBytes(StandardCharsets.UTF_8));

        // Should throw because signature no longer matches
        assertThatThrownBy(() -> SamlAssertionValidator.validateAndExtractNameId(tamperedBase64, certificatePem))
            .isInstanceOf(ExensioAuthService.ExensioAuthException.class)
            .hasMessageContaining("signature");
    }

    // ========== Helper Methods ==========

    /**
     * Creates a signed SAML assertion with a given NameID.
     */
    private static Assertion createSignedAssertion(String nameId, PrivateKey privateKey) throws Exception {
        XMLObjectBuilderFactory builderFactory = XMLObjectProviderRegistrySupport.getBuilderFactory();

        // Create NameID
        NameID nameIdObj = (NameID) builderFactory
            .getBuilder(NameID.DEFAULT_ELEMENT_NAME)
            .buildObject(NameID.DEFAULT_ELEMENT_NAME);
        nameIdObj.setValue(nameId);

        // Create Subject with NameID and SubjectConfirmation
        Subject subject = (Subject) builderFactory
            .getBuilder(Subject.DEFAULT_ELEMENT_NAME)
            .buildObject(Subject.DEFAULT_ELEMENT_NAME);
        subject.setNameID(nameIdObj);

        SubjectConfirmation confirmation = (SubjectConfirmation) builderFactory
            .getBuilder(SubjectConfirmation.DEFAULT_ELEMENT_NAME)
            .buildObject(SubjectConfirmation.DEFAULT_ELEMENT_NAME);
        confirmation.setMethod("urn:oasis:names:tc:SAML:2.0:cm:bearer");

        SubjectConfirmationData confirmationData = (SubjectConfirmationData) builderFactory
            .getBuilder(SubjectConfirmationData.DEFAULT_ELEMENT_NAME)
            .buildObject(SubjectConfirmationData.DEFAULT_ELEMENT_NAME);
        confirmationData.setNotOnOrAfter(new DateTime().plusHours(1));

        confirmation.setSubjectConfirmationData(confirmationData);
        subject.getSubjectConfirmations().add(confirmation);

        // Create Issuer
        Issuer issuer = (Issuer) builderFactory
            .getBuilder(Issuer.DEFAULT_ELEMENT_NAME)
            .buildObject(Issuer.DEFAULT_ELEMENT_NAME);
        issuer.setValue("https://sts.windows.net/tenant/");

        // Create Assertion
        Assertion assertion = (Assertion) builderFactory
            .getBuilder(Assertion.DEFAULT_ELEMENT_NAME)
            .buildObject(Assertion.DEFAULT_ELEMENT_NAME);
        assertion.setVersion(SAMLVersion.VERSION_20);
        assertion.setID("_" + java.util.UUID.randomUUID());
        assertion.setIssueInstant(new DateTime());
        assertion.setIssuer(issuer);
        assertion.setSubject(subject);

        // Sign the assertion
        BasicX509Credential credential = new BasicX509Credential(privateKey);
        Signer.signObject(assertion, credential);

        return assertion;
    }

    /**
     * Creates an unsigned SAML assertion with a given NameID.
     */
    private static Assertion createUnsignedAssertion(String nameId) throws Exception {
        XMLObjectBuilderFactory builderFactory = XMLObjectProviderRegistrySupport.getBuilderFactory();

        // Create NameID
        NameID nameIdObj = (NameID) builderFactory
            .getBuilder(NameID.DEFAULT_ELEMENT_NAME)
            .buildObject(NameID.DEFAULT_ELEMENT_NAME);
        nameIdObj.setValue(nameId);

        // Create Subject
        Subject subject = (Subject) builderFactory
            .getBuilder(Subject.DEFAULT_ELEMENT_NAME)
            .buildObject(Subject.DEFAULT_ELEMENT_NAME);
        subject.setNameID(nameIdObj);

        // Create Issuer
        Issuer issuer = (Issuer) builderFactory
            .getBuilder(Issuer.DEFAULT_ELEMENT_NAME)
            .buildObject(Issuer.DEFAULT_ELEMENT_NAME);
        issuer.setValue("https://sts.windows.net/tenant/");

        // Create Assertion (without signing)
        Assertion assertion = (Assertion) builderFactory
            .getBuilder(Assertion.DEFAULT_ELEMENT_NAME)
            .buildObject(Assertion.DEFAULT_ELEMENT_NAME);
        assertion.setVersion(SAMLVersion.VERSION_20);
        assertion.setID("_" + java.util.UUID.randomUUID());
        assertion.setIssueInstant(new DateTime());
        assertion.setIssuer(issuer);
        assertion.setSubject(subject);

        return assertion;
    }

    /**
     * Creates a signed SAML assertion WITHOUT a NameID in the subject.
     */
    private static Assertion createSignedAssertionWithoutNameId(PrivateKey privateKey) throws Exception {
        XMLObjectBuilderFactory builderFactory = XMLObjectProviderRegistrySupport.getBuilderFactory();

        // Create Subject WITHOUT NameID
        Subject subject = (Subject) builderFactory
            .getBuilder(Subject.DEFAULT_ELEMENT_NAME)
            .buildObject(Subject.DEFAULT_ELEMENT_NAME);

        // Create Issuer
        Issuer issuer = (Issuer) builderFactory
            .getBuilder(Issuer.DEFAULT_ELEMENT_NAME)
            .buildObject(Issuer.DEFAULT_ELEMENT_NAME);
        issuer.setValue("https://sts.windows.net/tenant/");

        // Create Assertion
        Assertion assertion = (Assertion) builderFactory
            .getBuilder(Assertion.DEFAULT_ELEMENT_NAME)
            .buildObject(Assertion.DEFAULT_ELEMENT_NAME);
        assertion.setVersion(SAMLVersion.VERSION_20);
        assertion.setID("_" + java.util.UUID.randomUUID());
        assertion.setIssueInstant(new DateTime());
        assertion.setIssuer(issuer);
        assertion.setSubject(subject);

        // Sign the assertion
        BasicX509Credential credential = new BasicX509Credential(privateKey);
        Signer.signObject(assertion, credential);

        return assertion;
    }

    /**
     * Marshals a Response object to XML, wraps it, and encodes as base64.
     */
    private static String marshalAndEncode(Response response) throws MarshallingException {
        Element element = XMLObjectSupport.marshall(response);
        String xml = org.opensaml.core.xml.util.XMLObjectSupport.prettyPrintXML(element);
        return Base64.getEncoder().encodeToString(xml.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Generates a self-signed X.509 certificate from a key pair.
     */
    private static X509Certificate generateSelfSignedCertificate(KeyPair keyPair) throws Exception {
        X509CertInfo info = new X509CertInfo();
        Date from = new Date();
        Date to = new Date(from.getTime() + 365 * 24 * 60 * 60 * 1000L);
        CertificateValidity interval = new CertificateValidity(from, to);
        BigInteger serialNumber = new java.math.BigInteger(64, new java.security.SecureRandom());
        X500Name owner = new X500Name("CN=test,O=test,C=US");

        info.set(X509CertInfo.VALIDITY, interval);
        info.set(X509CertInfo.SERIAL_NUMBER, new CertificateSerialNumber(serialNumber));
        info.set(X509CertInfo.SUBJECT, new CertificateSubjectName(owner));
        info.set(X509CertInfo.ISSUER, new CertificateIssuerName(owner));
        info.set(X509CertInfo.KEY, new CertificateX509Key(keyPair.getPublic()));
        info.set(X509CertInfo.VERSION, new CertificateVersion(CertificateVersion.V3));
        AlgorithmId algo = new AlgorithmId(AlgorithmId.sha256WithRSAEncryption_oid);
        info.set(X509CertInfo.ALGORITHM_ID, new CertificateAlgorithmId(algo));

        X509CertImpl cert = new X509CertImpl(info);
        cert.sign(keyPair.getPrivate(), "SHA256withRSA");

        return cert;
    }

    /**
     * Converts an X509Certificate to PEM format.
     */
    private static String toPem(X509Certificate certificate) throws CertificateEncodingException {
        String base64Cert = Base64.getEncoder().encodeToString(certificate.getEncoded());
        return "-----BEGIN CERTIFICATE-----\n" + base64Cert + "\n-----END CERTIFICATE-----";
    }
}
