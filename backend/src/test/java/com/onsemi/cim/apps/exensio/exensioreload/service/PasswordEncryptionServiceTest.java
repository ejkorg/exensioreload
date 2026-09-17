package com.onsemi.cim.apps.exensio.exensioreload.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for PasswordEncryptionService.
 * Verifies encryption/decryption roundtrip and security properties.
 * 
 * Requirements: 8.5, 9.5
 */
@SpringBootTest
@ActiveProfiles("test")
class PasswordEncryptionServiceTest {

    @Autowired
    private PasswordEncryptionService encryptionService;

    @Test
    void encrypt_returnsEncryptedString_whenGivenPlaintext() {
        String plaintext = "mySecurePassword123!";
        
        String encrypted = encryptionService.encrypt(plaintext);
        
        assertThat(encrypted).isNotNull();
        assertThat(encrypted).isNotEqualTo(plaintext);
        assertThat(encrypted).isNotEmpty();
    }

    @Test
    void decrypt_returnsOriginalPassword_whenGivenEncryptedString() {
        String plaintext = "mySecurePassword123!";
        String encrypted = encryptionService.encrypt(plaintext);
        
        String decrypted = encryptionService.decrypt(encrypted);
        
        assertThat(decrypted).isEqualTo(plaintext);
    }

    @Test
    void encryptDecrypt_roundTrip_producesOriginalValue() {
        String original = "TestPassword@2024#Complex";
        
        String encrypted = encryptionService.encrypt(original);
        String decrypted = encryptionService.decrypt(encrypted);
        
        assertThat(decrypted).isEqualTo(original);
    }

    @Test
    void encrypt_returnsNull_whenGivenNull() {
        String encrypted = encryptionService.encrypt(null);
        
        assertThat(encrypted).isNull();
    }

    @Test
    void encrypt_returnsEmpty_whenGivenEmptyString() {
        String encrypted = encryptionService.encrypt("");
        
        assertThat(encrypted).isEmpty();
    }

    @Test
    void decrypt_returnsNull_whenGivenNull() {
        String decrypted = encryptionService.decrypt(null);
        
        assertThat(decrypted).isNull();
    }

    @Test
    void decrypt_returnsEmpty_whenGivenEmptyString() {
        String decrypted = encryptionService.decrypt("");
        
        assertThat(decrypted).isEmpty();
    }

    @Test
    void encrypt_handlesDifferentPasswordLengths() {
        // Test various password lengths
        String short_pass = "short";
        String medium_pass = "mediumPasswordLength";
        String long_pass = "thisIsAVeryLongPasswordWithManyCharactersIncludingSpecialChars!@#$%^&*()";
        
        assertThat(encryptionService.decrypt(encryptionService.encrypt(short_pass)))
            .isEqualTo(short_pass);
        assertThat(encryptionService.decrypt(encryptionService.encrypt(medium_pass)))
            .isEqualTo(medium_pass);
        assertThat(encryptionService.decrypt(encryptionService.encrypt(long_pass)))
            .isEqualTo(long_pass);
    }

    @Test
    void encrypt_handleSpecialCharacters() {
        // Test passwords with special characters
        String specialChars = "P@ssw0rd!#$%^&*()_+-={}[]|:;<>?,./";
        
        String encrypted = encryptionService.encrypt(specialChars);
        String decrypted = encryptionService.decrypt(encrypted);
        
        assertThat(decrypted).isEqualTo(specialChars);
    }

    @Test
    void encrypt_samePasswordProducesDifferentCiphertext() {
        String password = "myPassword123";
        
        String encrypted1 = encryptionService.encrypt(password);
        String encrypted2 = encryptionService.encrypt(password);
        
        // Different encryptions due to random salt generation
        assertThat(encrypted1).isNotEqualTo(encrypted2);
        // But both should decrypt to same password
        assertThat(encryptionService.decrypt(encrypted1))
            .isEqualTo(encryptionService.decrypt(encrypted2))
            .isEqualTo(password);
    }

    @Test
    void encrypt_handlesDatabaseCredentialPatterns() {
        // Test common database password patterns
        String oraclePassword = "Oracle@2024#DBUser";
        String postgresPassword = "Postgres_Pass$123";
        String mysqlPassword = "MySQL!Password2024";
        
        assertThat(encryptionService.decrypt(encryptionService.encrypt(oraclePassword)))
            .isEqualTo(oraclePassword);
        assertThat(encryptionService.decrypt(encryptionService.encrypt(postgresPassword)))
            .isEqualTo(postgresPassword);
        assertThat(encryptionService.decrypt(encryptionService.encrypt(mysqlPassword)))
            .isEqualTo(mysqlPassword);
    }

    @Test
    void encrypt_handlesUnicodeCharacters() {
        String unicodePassword = "Pässwörd@2024€";
        
        String encrypted = encryptionService.encrypt(unicodePassword);
        String decrypted = encryptionService.decrypt(encrypted);
        
        assertThat(decrypted).isEqualTo(unicodePassword);
    }
}
