package com.onsemi.cim.apps.exensio.exensioreload.service;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for SenderPortExtractor — verifies port extraction from cpConfig field values (Requirements 4.1, 4.2).
 * <p>
 * The cpConfig field can contain various formats like:
 * <ul>
 *   <li>"64000_POWERCHIP_WAT_TO_COLO_SENDER" (port at start)</li>
 *   <li>"POWERCHIP_64000_WAT_TO_COLO_SENDER" (port in middle)</li>
 *   <li>"POWERCHIP_WAT_TO_COLO_SENDER_64000" (port at end)</li>
 * </ul>
 */
class SenderPortExtractorTest {

    private SenderPortExtractor extractor = new SenderPortExtractor();

    @Test
    void extractPort_returnsPort_whenConfigNameHasPortAtStart() {
        // Port at the start of the string
        assertThat(extractor.extractPort("64000_POWERCHIP_WAT_TO_COLO_SENDER")).isEqualTo(Optional.of(64000));
        assertThat(extractor.extractPort("8080_SENDER_CONFIG")).isEqualTo(Optional.of(8080));
        assertThat(extractor.extractPort("9090_POWERCHIP")).isEqualTo(Optional.of(9090));
    }

    @Test
    void extractPort_returnsPort_whenConfigNameHasPortInMiddle() {
        // Port in the middle of the string
        assertThat(extractor.extractPort("POWERCHIP_64000_WAT_TO_COLO_SENDER")).isEqualTo(Optional.of(64000));
        assertThat(extractor.extractPort("SENDER_8080_CONFIG")).isEqualTo(Optional.of(8080));
    }

    @Test
    void extractPort_returnsPort_whenConfigNameHasPortAtEnd() {
        // Port at the end of the string
        assertThat(extractor.extractPort("POWERCHIP_WAT_TO_COLO_SENDER_64000")).isEqualTo(Optional.of(64000));
        assertThat(extractor.extractPort("SENDER_CONFIG_8080")).isEqualTo(Optional.of(8080));
    }

    @Test
    void extractPort_returnsPort_whenConfigNameHasLegacyFormat() {
        // Legacy format with hyphen
        assertThat(extractor.extractPort("sender-8080")).isEqualTo(Optional.of(8080));
        assertThat(extractor.extractPort("sender-9090")).isEqualTo(Optional.of(9090));
        assertThat(extractor.extractPort("sender-12345")).isEqualTo(Optional.of(12345));
    }

    @Test
    void extractPort_returnsEmpty_whenConfigNameHasNoPort() {
        // No port number in the string
        assertThat(extractor.extractPort("sender")).isEmpty();
        assertThat(extractor.extractPort("sender-abc")).isEmpty();
        assertThat(extractor.extractPort("cp-8080")).isEmpty();
        assertThat(extractor.extractPort("POWERCHIP_WAT_TO_COLO_SENDER")).isEmpty();
    }

    @Test
    void extractPort_returnsEmpty_whenConfigNameIsNull() {
        assertThat(extractor.extractPort(null)).isEmpty();
    }

    @Test
    void extractPort_returnsEmpty_whenConfigNameIsEmpty() {
        assertThat(extractor.extractPort("")).isEmpty();
        assertThat(extractor.extractPort("   ")).isEmpty();
    }

    @Test
    void extractPort_ignoresWhitespace() {
        assertThat(extractor.extractPort("  64000_POWERCHIP_WAT_TO_COLO_SENDER  ")).isEqualTo(Optional.of(64000));
        assertThat(extractor.extractPort("  sender-8080  ")).isEqualTo(Optional.of(8080));
    }

    @Test
    void extractPort_caseInsensitive() {
        // Test case variations
        assertThat(extractor.extractPort("64000_POWERCHIP")).isEqualTo(Optional.of(64000));
        assertThat(extractor.extractPort("POWERCHIP_64000")).isEqualTo(Optional.of(64000));
        assertThat(extractor.extractPort("POWERCHIP_64000_SENDER")).isEqualTo(Optional.of(64000));
    }
}
