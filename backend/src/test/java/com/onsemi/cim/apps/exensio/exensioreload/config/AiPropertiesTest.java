package com.onsemi.cim.apps.exensio.exensioreload.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for AiProperties.
 */
class AiPropertiesTest {

    private AiProperties props;

    @BeforeEach
    void setUp() {
        props = new AiProperties();
    }

    @Test
    @DisplayName("Should return false for isConfigured when disabled")
    void shouldReturnFalseWhenDisabled() {
        props.setEnabled(false);
        props.setApiKey("test-key");
        
        assertFalse(props.isConfigured());
    }

    @Test
    @DisplayName("Should return false for isConfigured when no API key")
    void shouldReturnFalseWhenNoApiKey() {
        props.setEnabled(true);
        props.setApiKey("");
        
        assertFalse(props.isConfigured());
    }

    @Test
    @DisplayName("Should return true for isConfigured with Ollama (no API key needed)")
    void shouldReturnTrueForOllama() {
        props.setEnabled(true);
        props.setPreset("ollama");
        props.setBaseUrl("http://localhost:11434");
        
        assertTrue(props.isConfigured());
    }

    @Test
    @DisplayName("Should return true for isConfigured when enabled with API key")
    void shouldReturnTrueWhenEnabledWithKey() {
        props.setEnabled(true);
        props.setApiKey("test-key");
        
        assertTrue(props.isConfigured());
    }

    @Test
    @DisplayName("Should detect Anthropic provider")
    void shouldDetectAnthropicProvider() {
        props.setPreset("anthropic");
        
        assertTrue(props.isAnthropic());
        assertFalse(props.isOpenAiCompatible());
    }

    @Test
    @DisplayName("Should detect OpenAI-compatible providers")
    void shouldDetectOpenAiCompatibleProviders() {
        props.setPreset("groq");
        
        assertFalse(props.isAnthropic());
        assertTrue(props.isOpenAiCompatible());
    }

    @Test
    @DisplayName("Should apply Groq preset correctly")
    void shouldApplyGroqPreset() {
        props.setPreset("groq");
        
        assertEquals("openai", props.getProvider());
        assertEquals("https://api.groq.com/openai/v1/chat/completions", props.getBaseUrl());
        assertEquals("llama-3.3-70b-versatile", props.getModel());
    }

    @Test
    @DisplayName("Should apply Ollama preset correctly")
    void shouldApplyOllamaPreset() {
        props.setPreset("ollama");
        
        assertEquals("openai", props.getProvider());
        assertEquals("http://localhost:11434/v1/chat/completions", props.getBaseUrl());
        assertEquals("llama3", props.getModel());
    }

    @Test
    @DisplayName("Should apply Gemini preset correctly")
    void shouldApplyGeminiPreset() {
        props.setPreset("gemini");
        
        assertEquals("openai", props.getProvider());
        assertTrue(props.getBaseUrl().contains("generativelanguage.googleapis.com"));
    }

    @Test
    @DisplayName("Should resolve base URL for Anthropic")
    void shouldResolveBaseUrlForAnthropic() {
        props.setPreset("anthropic");
        
        assertEquals("https://api.anthropic.com/v1/messages", props.getResolvedBaseUrl());
    }

    @Test
    @DisplayName("Should resolve base URL for OpenAI-compatible")
    void shouldResolveBaseUrlForOpenAi() {
        props.setPreset("groq");
        
        assertEquals("https://api.groq.com/openai/v1/chat/completions", props.getResolvedBaseUrl());
    }
}