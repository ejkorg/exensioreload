package com.onsemi.cim.apps.exensio.exensioreload.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.encrypt.Encryptors;
import org.springframework.security.crypto.encrypt.StandardEncryptor;
import org.springframework.security.crypto.keygen.KeyGenerators;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;

/**
 * Service for encrypting and decrypting sensitive credentials.
 * Uses Spring Security's StandardEncryptor with AES-256 encryption.
 * 
 * Requirements: 8.5, 9.5
 */
@Service
@Slf4j
public class PasswordEncryptionService {

    @Value("${security.encryption.key}")
    private String encryptionKey;

    private StandardEncryptor encryptor;

    /**
     * Initializes the encryptor with the configured encryption key.
     * Called automatically after bean construction.
     */
    @PostConstruct
    public void init() {
        if (encryptionKey == null || encryptionKey.isEmpty()) {
            log.warn("Encryption key not configured. Please set security.encryption.key in application properties.");
            throw new IllegalStateException("Encryption key must be configured via security.encryption.key property");
        }

        // Initialize Spring Security's StandardEncryptor with AES-256
        // KeyGenerators.string() generates a random salt for each encryption
        this.encryptor = Encryptors.text(encryptionKey, KeyGenerators.string().generateKey());
        log.info("PasswordEncryptionService initialized with AES-256 encryption");
    }

    /**
     * Encrypts plaintext password.
     *
     * @param plaintext the plaintext password to encrypt
     * @return encrypted password, or plaintext if null/empty
     */
    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) {
            return plaintext;
        }

        try {
            String encrypted = encryptor.encrypt(plaintext);
            log.debug("Password encrypted successfully");
            return encrypted;
        } catch (Exception e) {
            log.error("Failed to encrypt password", e);
            throw new RuntimeException("Failed to encrypt password", e);
        }
    }

    /**
     * Decrypts encrypted password.
     *
     * @param encrypted the encrypted password to decrypt
     * @return decrypted password, or encrypted if null/empty
     */
    public String decrypt(String encrypted) {
        if (encrypted == null || encrypted.isEmpty()) {
            return encrypted;
        }

        try {
            String decrypted = encryptor.decrypt(encrypted);
            log.debug("Password decrypted successfully");
            return decrypted;
        } catch (Exception e) {
            log.error("Failed to decrypt password", e);
            throw new RuntimeException("Failed to decrypt password", e);
        }
    }
}
