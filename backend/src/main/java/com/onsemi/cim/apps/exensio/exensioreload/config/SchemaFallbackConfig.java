package com.onsemi.cim.apps.exensio.exensioreload.config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for schema fallback in preflight checks.
 * Bound from the {@code exensio} prefix in application.yml.
 *
 * <p>Manages schema fallback behavior for lot existence verification:
 * - Primary: Query PRODUCTION schema
 * - Fallback: Query SANDBOX schema if PRODUCTION returns empty
 * - Secondary: Query Snowflake if HTTP exhausts all schemas
 *
 * <p>Requirements: 5.1, 5.2, 5.4, 5.5</p>
 */
@Component
@ConfigurationProperties(prefix = "exensio")
public class SchemaFallbackConfig {

    private static final Logger log = LoggerFactory.getLogger(SchemaFallbackConfig.class);

    /** Master switch: enable/disable schema fallback feature entirely. Default: true */
    private boolean schemaFallbackEnabled = true;

    /** Comma-separated list of schemas to query in priority order. Default: "PRODUCTION,SANDBOX" */
    private String schemaFallbackPriorityList = "PRODUCTION,SANDBOX";

    /** Enable/disable Snowflake as secondary fallback when HTTP returns no results. Default: true */
    private boolean enableSnowflakeSecondary = true;

    /** Maximum number of schema attempts before returning error. Default: 3 */
    private int schemaFallbackMaxAttempts = 3;

    /** Exponential backoff base for HTTP retries in milliseconds. Default: 100 */
    private long schemaFallbackBackoffBaseMs = 100L;

    /** Exponential backoff maximum delay in milliseconds. Default: 5000 */
    private long schemaFallbackBackoffMaxMs = 5000L;

    // Derived field: parsed schema priority list
    private List<String> parsedSchemaPriorityList = new ArrayList<>();

    // --- getters and setters ---

    public boolean isSchemaFallbackEnabled() {
        return schemaFallbackEnabled;
    }

    public void setSchemaFallbackEnabled(boolean schemaFallbackEnabled) {
        this.schemaFallbackEnabled = schemaFallbackEnabled;
    }

    public String getSchemaFallbackPriorityList() {
        return schemaFallbackPriorityList;
    }

    public void setSchemaFallbackPriorityList(String schemaFallbackPriorityList) {
        this.schemaFallbackPriorityList = schemaFallbackPriorityList;
    }

    public boolean isEnableSnowflakeSecondary() {
        return enableSnowflakeSecondary;
    }

    public void setEnableSnowflakeSecondary(boolean enableSnowflakeSecondary) {
        this.enableSnowflakeSecondary = enableSnowflakeSecondary;
    }

    public int getSchemaFallbackMaxAttempts() {
        return schemaFallbackMaxAttempts;
    }

    public void setSchemaFallbackMaxAttempts(int schemaFallbackMaxAttempts) {
        this.schemaFallbackMaxAttempts = schemaFallbackMaxAttempts;
    }

    public long getSchemaFallbackBackoffBaseMs() {
        return schemaFallbackBackoffBaseMs;
    }

    public void setSchemaFallbackBackoffBaseMs(long schemaFallbackBackoffBaseMs) {
        this.schemaFallbackBackoffBaseMs = schemaFallbackBackoffBaseMs;
    }

    public long getSchemaFallbackBackoffMaxMs() {
        return schemaFallbackBackoffMaxMs;
    }

    public void setSchemaFallbackBackoffMaxMs(long schemaFallbackBackoffMaxMs) {
        this.schemaFallbackBackoffMaxMs = schemaFallbackBackoffMaxMs;
    }

    // --- derived helper methods ---

    /**
     * Returns the parsed list of schemas in priority order.
     * Lazily parses the comma-separated priority list on first call.
     *
     * @return List of schema names in priority order
     */
    public List<String> resolveSchemaPriorityList() {
        if (parsedSchemaPriorityList.isEmpty() && schemaFallbackEnabled) {
            synchronized (this) {
                if (parsedSchemaPriorityList.isEmpty()) {
                    parsedSchemaPriorityList = parseSchemaPriorityList();
                }
            }
        }
        return new ArrayList<>(parsedSchemaPriorityList);
    }

    /**
     * Parses the comma-separated schema priority list and validates each schema name.
     * Returns default [PRODUCTION, SANDBOX] if property is empty or null.
     *
     * @return List of validated schema names
     * @throws IllegalArgumentException if schema name is empty or contains invalid characters
     */
    private List<String> parseSchemaPriorityList() {
        if (schemaFallbackPriorityList == null || schemaFallbackPriorityList.isBlank()) {
            log.debug("Schema priority list is empty, using default: PRODUCTION,SANDBOX");
            return Arrays.asList("PRODUCTION", "SANDBOX");
        }

        List<String> schemas = new ArrayList<>();
        String[] parts = schemaFallbackPriorityList.split(",");
        
        for (String part : parts) {
            String schema = part.trim();
            
            if (schema.isBlank()) {
                throw new IllegalArgumentException(
                    "Schema name cannot be empty in schema-fallback-priority-list: " + schemaFallbackPriorityList);
            }
            
            // Validate schema name: alphanumeric, underscore, hyphen only
            if (!schema.matches("^[A-Za-z0-9_-]+$")) {
                throw new IllegalArgumentException(
                    "Invalid schema name: '" + schema + "'. Must contain only alphanumeric, underscore, or hyphen characters.");
            }
            
            schemas.add(schema);
        }

        if (schemas.isEmpty()) {
            throw new IllegalArgumentException("Schema priority list must contain at least one schema");
        }

        return schemas;
    }

    // --- validation ---

    /**
     * Validates configuration at application startup.
     * Called by Spring after all properties are bound.
     *
     * @throws IllegalArgumentException if configuration is invalid
     */
    @jakarta.annotation.PostConstruct
    public void validate() {
        log.info("Schema Fallback Configuration: enabled={}, priorityList={}, snowflakeSecondary={}, maxAttempts={}",
            schemaFallbackEnabled, schemaFallbackPriorityList, enableSnowflakeSecondary, schemaFallbackMaxAttempts);

        if (schemaFallbackMaxAttempts < 1 || schemaFallbackMaxAttempts > 10) {
            throw new IllegalArgumentException(
                "exensio.schema-fallback-max-attempts must be between 1 and 10, got: " + schemaFallbackMaxAttempts);
        }

        if (schemaFallbackBackoffBaseMs < 0 || schemaFallbackBackoffBaseMs > 5000) {
            throw new IllegalArgumentException(
                "exensio.schema-fallback-backoff-base-ms must be between 0 and 5000, got: " + schemaFallbackBackoffBaseMs);
        }

        if (schemaFallbackBackoffMaxMs < schemaFallbackBackoffBaseMs || schemaFallbackBackoffMaxMs > 60000) {
            throw new IllegalArgumentException(
                "exensio.schema-fallback-backoff-max-ms must be >= backoff-base-ms and <= 60000, got: " + schemaFallbackBackoffMaxMs);
        }

        // Parse and validate schema priority list if fallback is enabled
        if (schemaFallbackEnabled) {
            try {
                List<String> schemas = parseSchemaPriorityList();
                parsedSchemaPriorityList = schemas;
                log.info("Schema Fallback Priority List validated: {}", schemas);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Invalid schema-fallback-priority-list configuration: " + e.getMessage(), e);
            }
        }

        log.info("Schema Fallback Configuration initialized successfully");
    }
}
