package com.onsemi.cim.apps.exensio.exensioreload.util;

import java.util.regex.Pattern;

/**
 * Utility class for sanitizing passwords from logs and responses.
 * <p>
 * This class provides methods to redact password values from strings
 * to ensure sensitive credentials are never exposed in logs or API responses.
 * <p>
 * Requirements: 8.1, 8.2
 */
public class PasswordSanitizer {

    private static final Pattern PASSWORD_PATTERN = Pattern.compile(
            "(?i)(password[\"']?\\s*[:=]\\s*[\"']?)([^\"'\\s,]+)",
            Pattern.CASE_INSENSITIVE
    );

    private static final String REDACTED_VALUE = "[REDACTED]";

    /**
     * Sanitizes a string by replacing password values with [REDACTED].
     * <p>
     * This method searches for common password patterns in the input string
     * and replaces the actual password value with [REDACTED].
     *
     * @param input The string to sanitize
     * @return The sanitized string with passwords redacted
     */
    public static String sanitize(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        return PASSWORD_PATTERN.matcher(input).replaceAll("$1" + REDACTED_VALUE);
    }

    /**
     * Sanitizes an ETL server config by redacting the password field.
     * <p>
     * This method creates a sanitized string representation of the config
     * with the password field replaced by [REDACTED].
     *
     * @param config The ETL server config to sanitize
     * @return A sanitized string representation of the config
     */
    public static String sanitizeConfig(com.onsemi.cim.apps.exensio.exensioreload.config.EtlServerConfig config) {
        if (config == null) {
            return "EtlServerConfig(null)";
        }

        String sanitizedPassword = (config.getPassword() != null) ? REDACTED_VALUE : "null";

        return String.format(
                "EtlServerConfig{name='%s', host='%s', port=%d, user='%s', password=%s, timeoutMs=%d}",
                config.getName(),
                config.getHost(),
                config.getPort(),
                config.getUser(),
                sanitizedPassword,
                config.getTimeoutMs()
        );
    }

    /**
     * Sanitizes an error message by removing any password values.
     * <p>
     * This method is specifically designed for sanitizing error messages
     * that may contain password information from exception messages or stack traces.
     *
     * @param errorMessage The error message to sanitize
     * @return The sanitized error message
     */
    public static String sanitizeErrorMessage(String errorMessage) {
        if (errorMessage == null || errorMessage.isEmpty()) {
            return errorMessage;
        }
        return sanitize(errorMessage);
    }
}
