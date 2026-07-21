package com.onsemi.cim.apps.exensio.exensioreload.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for SchemaFallbackConfig.
 *
 * <p>Tests configuration parsing, validation, and behavior according to Requirements 5.1, 5.2, 5.4</p>
 */
class SchemaFallbackConfigTest {

    private SchemaFallbackConfig config;

    @BeforeEach
    void setUp() {
        config = new SchemaFallbackConfig();
    }

    // --- Tests for parsing comma-separated schema list ---

    @Test
    void resolveSchemaPriorityList_shouldParse_PRODUCTION_SANDBOX() {
        config.setSchemaFallbackEnabled(true);
        config.setSchemaFallbackPriorityList("PRODUCTION,SANDBOX");
        config.validate();

        List<String> schemas = config.resolveSchemaPriorityList();

        assertThat(schemas).hasSize(2);
        assertThat(schemas).containsExactly("PRODUCTION", "SANDBOX");
    }

    @Test
    void resolveSchemaPriorityList_shouldParse_SANDBOX_PRODUCTION() {
        config.setSchemaFallbackEnabled(true);
        config.setSchemaFallbackPriorityList("SANDBOX,PRODUCTION");
        config.validate();

        List<String> schemas = config.resolveSchemaPriorityList();

        assertThat(schemas).hasSize(2);
        assertThat(schemas).containsExactly("SANDBOX", "PRODUCTION");
    }

    @Test
    void resolveSchemaPriorityList_shouldHandleWhitespace() {
        config.setSchemaFallbackEnabled(true);
        config.setSchemaFallbackPriorityList("  PRODUCTION  ,  SANDBOX  ");
        config.validate();

        List<String> schemas = config.resolveSchemaPriorityList();

        assertThat(schemas).containsExactly("PRODUCTION", "SANDBOX");
    }

    @Test
    void resolveSchemaPriorityList_shouldHandleTripleSchemas() {
        config.setSchemaFallbackEnabled(true);
        config.setSchemaFallbackPriorityList("PRODUCTION,SANDBOX,DEV");
        config.validate();

        List<String> schemas = config.resolveSchemaPriorityList();

        assertThat(schemas).hasSize(3);
        assertThat(schemas).containsExactly("PRODUCTION", "SANDBOX", "DEV");
    }

    @Test
    void resolveSchemaPriorityList_shouldUseDefault_whenEmpty() {
        config.setSchemaFallbackEnabled(true);
        config.setSchemaFallbackPriorityList("");
        config.validate();

        List<String> schemas = config.resolveSchemaPriorityList();

        assertThat(schemas).containsExactly("PRODUCTION", "SANDBOX");
    }

    @Test
    void resolveSchemaPriorityList_shouldUseDefault_whenNull() {
        config.setSchemaFallbackEnabled(true);
        config.setSchemaFallbackPriorityList(null);
        config.validate();

        List<String> schemas = config.resolveSchemaPriorityList();

        assertThat(schemas).containsExactly("PRODUCTION", "SANDBOX");
    }

    // --- Tests for validation ---

    @Test
    void validate_shouldThrow_whenEmptySchemaInList() {
        config.setSchemaFallbackEnabled(true);
        config.setSchemaFallbackPriorityList("PRODUCTION,,SANDBOX");

        assertThatThrownBy(() -> config.validate())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be empty");
    }

    @Test
    void validate_shouldThrow_whenInvalidSchemaName() {
        config.setSchemaFallbackEnabled(true);
        config.setSchemaFallbackPriorityList("PRODUCTION,SAND@BOX");

        assertThatThrownBy(() -> config.validate())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid schema name");
    }

    @Test
    void validate_shouldThrow_whenInvalidSchemaName_withSpaces() {
        config.setSchemaFallbackEnabled(true);
        config.setSchemaFallbackPriorityList("PRODUCTION,SAND BOX");

        assertThatThrownBy(() -> config.validate())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid schema name");
    }

    @Test
    void validate_shouldAllowSchemaName_withUnderscores() {
        config.setSchemaFallbackEnabled(true);
        config.setSchemaFallbackPriorityList("PRODUCTION_MAIN,SANDBOX_TEST");
        config.validate();

        List<String> schemas = config.resolveSchemaPriorityList();
        assertThat(schemas).containsExactly("PRODUCTION_MAIN", "SANDBOX_TEST");
    }

    @Test
    void validate_shouldAllowSchemaName_withHyphens() {
        config.setSchemaFallbackEnabled(true);
        config.setSchemaFallbackPriorityList("PRODUCTION-MAIN,SANDBOX-TEST");
        config.validate();

        List<String> schemas = config.resolveSchemaPriorityList();
        assertThat(schemas).containsExactly("PRODUCTION-MAIN", "SANDBOX-TEST");
    }

    @Test
    void validate_shouldThrow_whenMaxAttemptsOutOfRange() {
        config.setSchemaFallbackEnabled(true);
        config.setSchemaFallbackPriorityList("PRODUCTION,SANDBOX");
        config.setSchemaFallbackMaxAttempts(0);

        assertThatThrownBy(() -> config.validate())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("schema-fallback-max-attempts");
    }

    @Test
    void validate_shouldThrow_whenMaxAttemptsExceedsLimit() {
        config.setSchemaFallbackEnabled(true);
        config.setSchemaFallbackPriorityList("PRODUCTION,SANDBOX");
        config.setSchemaFallbackMaxAttempts(11);

        assertThatThrownBy(() -> config.validate())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("schema-fallback-max-attempts");
    }

    @Test
    void validate_shouldThrow_whenBackoffBaseOutOfRange() {
        config.setSchemaFallbackEnabled(true);
        config.setSchemaFallbackPriorityList("PRODUCTION,SANDBOX");
        config.setSchemaFallbackBackoffBaseMs(-1);

        assertThatThrownBy(() -> config.validate())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("backoff-base-ms");
    }

    @Test
    void validate_shouldThrow_whenBackoffMaxLessThanBase() {
        config.setSchemaFallbackEnabled(true);
        config.setSchemaFallbackPriorityList("PRODUCTION,SANDBOX");
        config.setSchemaFallbackBackoffBaseMs(1000);
        config.setSchemaFallbackBackoffMaxMs(500);

        assertThatThrownBy(() -> config.validate())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("backoff-max-ms");
    }

    @Test
    void validate_shouldThrow_whenBackoffMaxExceedsLimit() {
        config.setSchemaFallbackEnabled(true);
        config.setSchemaFallbackPriorityList("PRODUCTION,SANDBOX");
        config.setSchemaFallbackBackoffMaxMs(61000);

        assertThatThrownBy(() -> config.validate())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("backoff-max-ms");
    }

    // --- Tests for getters/setters ---

    @Test
    void gettersAndSetters_shouldWorkCorrectly() {
        config.setSchemaFallbackEnabled(false);
        config.setSchemaFallbackPriorityList("TEST_SCHEMA");
        config.setEnableSnowflakeSecondary(false);
        config.setSchemaFallbackMaxAttempts(5);
        config.setSchemaFallbackBackoffBaseMs(200);
        config.setSchemaFallbackBackoffMaxMs(10000);

        assertThat(config.isSchemaFallbackEnabled()).isFalse();
        assertThat(config.getSchemaFallbackPriorityList()).isEqualTo("TEST_SCHEMA");
        assertThat(config.isEnableSnowflakeSecondary()).isFalse();
        assertThat(config.getSchemaFallbackMaxAttempts()).isEqualTo(5);
        assertThat(config.getSchemaFallbackBackoffBaseMs()).isEqualTo(200);
        assertThat(config.getSchemaFallbackBackoffMaxMs()).isEqualTo(10000);
    }

    // --- Tests for default values ---

    @Test
    void defaultValues_shouldBeCorrect() {
        SchemaFallbackConfig freshConfig = new SchemaFallbackConfig();

        assertThat(freshConfig.isSchemaFallbackEnabled()).isTrue();
        assertThat(freshConfig.getSchemaFallbackPriorityList()).isEqualTo("PRODUCTION,SANDBOX");
        assertThat(freshConfig.isEnableSnowflakeSecondary()).isTrue();
        assertThat(freshConfig.getSchemaFallbackMaxAttempts()).isEqualTo(3);
        assertThat(freshConfig.getSchemaFallbackBackoffBaseMs()).isEqualTo(100);
        assertThat(freshConfig.getSchemaFallbackBackoffMaxMs()).isEqualTo(5000);
    }

    // --- Tests for validation success ---

    @Test
    void validate_shouldSucceed_withDefaultConfiguration() {
        config.validate();
        // Should not throw
    }

    @Test
    void validate_shouldSucceed_withValidRanges() {
        config.setSchemaFallbackEnabled(true);
        config.setSchemaFallbackPriorityList("PRODUCTION,SANDBOX");
        config.setEnableSnowflakeSecondary(true);
        config.setSchemaFallbackMaxAttempts(5);
        config.setSchemaFallbackBackoffBaseMs(100);
        config.setSchemaFallbackBackoffMaxMs(5000);

        config.validate();
        // Should not throw
    }

    // --- Tests for edge cases ---

    @Test
    void resolveSchemaPriorityList_shouldReturnNewList_onEachCall() {
        config.setSchemaFallbackEnabled(true);
        config.setSchemaFallbackPriorityList("PRODUCTION,SANDBOX");
        config.validate();

        List<String> list1 = config.resolveSchemaPriorityList();
        List<String> list2 = config.resolveSchemaPriorityList();

        assertThat(list1).isNotSameAs(list2);
        assertThat(list1).isEqualTo(list2);
    }

    @Test
    void resolveSchemaPriorityList_shouldReturnEmptyList_whenFallbackDisabled() {
        config.setSchemaFallbackEnabled(false);
        config.setSchemaFallbackPriorityList("PRODUCTION,SANDBOX");

        List<String> schemas = config.resolveSchemaPriorityList();

        assertThat(schemas).isEmpty();
    }

    @Test
    void validate_shouldLogConfiguration_onSuccess() {
        // This test documents that validate() logs configuration at startup
        config.setSchemaFallbackEnabled(true);
        config.setSchemaFallbackPriorityList("PRODUCTION,SANDBOX");
        config.setEnableSnowflakeSecondary(true);

        // Should complete without exception and log configuration
        config.validate();
    }
}
