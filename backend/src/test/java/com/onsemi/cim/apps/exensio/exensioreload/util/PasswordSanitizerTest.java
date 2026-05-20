package com.onsemi.cim.apps.exensio.exensioreload.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based tests for PasswordSanitizer.
 * <p>
 * Property 23: Password not in logs
 * Validates: Requirement 8.1 - SSH password SHALL NOT appear in log output
 */
class PasswordSanitizerTest {

    /**
     * Property 23: Password not in logs
     * For any input string containing a password pattern, the sanitized output
     * SHALL never contain the actual password value.
     */
    @Test
    void sanitize_shouldNeverIncludePasswordInOutput() {
        // Test various password patterns that might appear in logs
        String[] passwordValues = {
                "secret123",
                "myPassword!",
                "P@ssw0rd",
                "admin12345",
                "test_password"
        };

        for (String password : passwordValues) {
            // Test "password" pattern
            String input1 = "Config password=' " + password + " '";
            String sanitized1 = PasswordSanitizer.sanitize(input1);
            assertThat(sanitized1).doesNotContain(password);
            assertThat(sanitized1).contains("[REDACTED]");

            // Test "password:" pattern
            String input2 = "password: " + password;
            String sanitized2 = PasswordSanitizer.sanitize(input2);
            assertThat(sanitized2).doesNotContain(password);
            assertThat(sanitized2).contains("[REDACTED]");

            // Test "password=" pattern
            String input3 = "password=" + password;
            String sanitized3 = PasswordSanitizer.sanitize(input3);
            assertThat(sanitized3).doesNotContain(password);
            assertThat(sanitized3).contains("[REDACTED]");

            // Test "password" pattern (case insensitive)
            String input4 = "PASSWORD: " + password;
            String sanitized4 = PasswordSanitizer.sanitize(input4);
            assertThat(sanitized4).doesNotContain(password);
            assertThat(sanitized4).contains("[REDACTED]");
        }
    }

    /**
     * Property 23: Password not in logs
     * For any error message containing password patterns, the sanitized error message
     * SHALL never contain the actual password value.
     */
    @Test
    void sanitizeErrorMessage_shouldNeverIncludePasswordInOutput() {
        String[] passwordValues = {
                "secret123",
                "myPassword!",
                "P@ssw0rd"
        };

        for (String password : passwordValues) {
            // Test error message with password
            String errorMessage = "Connection failed: password=" + password;
            String sanitized = PasswordSanitizer.sanitizeErrorMessage(errorMessage);
            assertThat(sanitized).doesNotContain(password);
            assertThat(sanitized).contains("[REDACTED]");
        }
    }

    /**
     * Property 23: Password not in logs
     * For any ETL server config, the sanitized config string
     * SHALL never contain the actual password value.
     */
    @Test
    void sanitizeConfig_shouldNeverIncludePasswordInOutput() {
        // Create a mock EtlServerConfig using reflection
        com.onsemi.cim.apps.exensio.exensioreload.config.EtlServerConfig config =
                new com.onsemi.cim.apps.exensio.exensioreload.config.EtlServerConfig();
        config.setName("test-server");
        config.setHost("localhost");
        config.setUser("testuser");
        config.setPassword("secretPassword123");
        config.setTimeoutMs(30000);

        String sanitized = PasswordSanitizer.sanitizeConfig(config);

        // Verify password is redacted
        assertThat(sanitized).doesNotContain("secretPassword123");
        assertThat(sanitized).contains("[REDACTED]");

        // Verify other fields are present
        assertThat(sanitized).contains("test-server");
        assertThat(sanitized).contains("localhost");
        assertThat(sanitized).contains("testuser");
    }

    /**
     * Property 23: Password not in logs
     * For any null or empty input, the sanitizer should handle gracefully
     * without throwing exceptions.
     */
    @Test
    void sanitize_shouldHandleNullAndEmptyInputs() {
        // Test null input
        String result1 = PasswordSanitizer.sanitize(null);
        assertThat(result1).isNull();

        // Test empty input
        String result2 = PasswordSanitizer.sanitize("");
        assertThat(result2).isEmpty();

        // Test empty string with whitespace
        String result3 = PasswordSanitizer.sanitize("   ");
        assertThat(result3).isEqualTo("   ");
    }

    /**
     * Property 23: Password not in logs
     * For any string without password patterns, the sanitizer should
     * return the original string unchanged.
     */
    @Test
    void sanitize_shouldLeaveNonPasswordStringsUnchanged() {
        String[] nonPasswordInputs = {
                "This is a regular log message",
                "User logged in successfully",
                "Connection established to localhost:8080",
                "Processing request id: abc123"
        };

        for (String input : nonPasswordInputs) {
            String sanitized = PasswordSanitizer.sanitize(input);
            assertThat(sanitized).isEqualTo(input);
        }
    }

    /**
     * Property 23: Password not in logs
     * For any config with null password, the sanitized config should
     * show "null" for password field.
     */
    @Test
    void sanitizeConfig_shouldHandleNullPassword() {
        com.onsemi.cim.apps.exensio.exensioreload.config.EtlServerConfig config =
                new com.onsemi.cim.apps.exensio.exensioreload.config.EtlServerConfig();
        config.setName("test-server");
        config.setHost("localhost");
        config.setUser("testuser");
        config.setPassword(null);
        config.setTimeoutMs(30000);

        String sanitized = PasswordSanitizer.sanitizeConfig(config);

        // Verify null password is handled
        assertThat(sanitized).contains("password=null");
    }

    /**
     * Property 23: Password not in logs
     * For any config with null config object, the sanitizer should
     * return a safe string representation.
     */
    @Test
    void sanitizeConfig_shouldHandleNullConfig() {
        String sanitized = PasswordSanitizer.sanitizeConfig(null);
        assertThat(sanitized).contains("null");
    }
}
