package com.onsemi.cim.apps.exensio.exensioreload.service.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.onsemi.cim.apps.exensio.exensioreload.config.AiProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.net.http.HttpClient;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for AiGatewayService.
 */
class AiGatewayServiceTest {

    private AiProperties props;
    private AiGatewayService service;

    @BeforeEach
    void setUp() {
        props = new AiProperties();
        HttpClient httpClient = HttpClient.newHttpClient();
        ObjectMapper objectMapper = new ObjectMapper();
        service = new AiGatewayService(props, httpClient, objectMapper);
    }

    @Test
    @DisplayName("Should return false for isHealthy when not configured")
    void shouldReturnFalseWhenNotConfigured() {
        props.setEnabled(true);
        props.setApiKey("");
        
        assertFalse(service.isHealthy());
    }

    @Test
    @DisplayName("Should return true for isHealthy when configured")
    void shouldReturnTrueWhenConfigured() {
        props.setEnabled(true);
        props.setPreset("ollama");
        props.setBaseUrl("http://localhost:11434/v1/chat/completions");
        
        assertTrue(service.isHealthy());
    }

    @Test
    @DisplayName("Should throw exception when AI not configured")
    void shouldThrowExceptionWhenNotConfigured() {
        props.setEnabled(false);
        
        assertThrows(AiGatewayService.AiServiceException.class, () -> {
            service.sendMessage("test", null, Collections.emptyList());
        });
    }

    @Test
    @DisplayName("Should clear cache successfully")
    void shouldClearCacheSuccessfully() {
        service.clearCache();
        // No exception means success
    }
}