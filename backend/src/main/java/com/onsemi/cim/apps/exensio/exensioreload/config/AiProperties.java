package com.onsemi.cim.apps.exensio.exensioreload.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for AI integration.
 * Bound from the {@code ai} prefix in application.yml.
 *
 * <p>Supports multiple providers: Anthropic Claude, OpenAI, Groq, Ollama (local).
 * Use the {@code preset} option for quick configuration.</p>
 */
@Component
@ConfigurationProperties(prefix = "ai")
public class AiProperties {

    private static final Logger log = LoggerFactory.getLogger(AiProperties.class);

    /** Master switch - set AI_ENABLED=true to activate AI features. */
    private boolean enabled = false;

    /**
     * Provider preset for quick configuration.
     * Options: "anthropic", "openai", "groq", "ollama"
     * When set, automatically configures baseUrl and default model.
     */
    private String preset = "anthropic";

    /** Manual provider override: "anthropic", "openai", "groq", "ollama" */
    private String provider = "anthropic";

    /** API key - set via AI_API_KEY environment variable */
    private String apiKey = "";

    /** Model identifier */
    private String model = "claude-sonnet-4-20250514";

    /** Base URL for API calls */
    private String baseUrl = "https://api.anthropic.com/v1/messages";

    /** Maximum tokens in response */
    private int maxTokens = 1024;

    /** Temperature for response generation (0.0 - 1.0) */
    private double temperature = 0.7;

    /** Request timeout in milliseconds */
    private int timeoutMs = 30000;

    /** Maximum retry attempts for transient failures */
    private int maxRetries = 3;

    /** Rate limit: requests per minute per user */
    private int rateLimitPerMinute = 20;

    /** Rate limit: requests per hour per user */
    private int rateLimitPerHour = 200;

    /** Enable response caching */
    private boolean cacheEnabled = true;

    /** Cache TTL in minutes */
    private int cacheTtlMinutes = 15;

    /** System prompt for the AI assistant */
    private String systemPrompt = """
        You are an AI assistant for ExensioReload, a manufacturing data staging application.
        
        You help operators:
        - Monitor lot processing and staging sessions
        - Search and filter data across sessions, lots, and senders
        - Understand alert patterns and failure causes
        - Troubleshoot data loading issues
        
        Key concepts:
        - Lots: Manufacturing lot identifiers (e.g., "LOT12345")
        - Wafers: Individual wafers within lots (e.g., "W01", "W02")
        - Senders: Data transmission endpoints that send data to Exensio
        - Staging Sessions: Data filtering and staging workflows
        - Exensio: Target data management system for semiconductor manufacturing
        - CP (Cursor Probe) Log: Test data from wafer probing
        
        When asked to list items, be concise with bullet points.
        When asked about failures, provide context about possible causes.
        When providing suggestions, be specific and actionable.
        Always maintain confidentiality - don't expose raw data unless requested.
        """;

    // --- Preset configurations ---

    private static class PresetConfig {
        String provider;
        String baseUrl;
        String defaultModel;

        PresetConfig(String provider, String baseUrl, String defaultModel) {
            this.provider = provider;
            this.baseUrl = baseUrl;
            this.defaultModel = defaultModel;
        }
    }

    private static final java.util.Map<String, PresetConfig> PRESETS = java.util.Map.of(
        "anthropic", new PresetConfig(
            "anthropic",
            "https://api.anthropic.com/v1/messages",
            "claude-sonnet-4-20250514"
        ),
        "openai", new PresetConfig(
            "openai",
            "https://api.openai.com/v1/chat/completions",
            "gpt-4o-mini"
        ),
        "groq", new PresetConfig(
            "openai",  // Groq uses OpenAI-compatible API
            "https://api.groq.com/openai/v1/chat/completions",
            "llama-3.3-70b-versatile"
        ),
        "ollama", new PresetConfig(
            "openai",  // Ollama uses OpenAI-compatible API
            "http://localhost:11434/v1/chat/completions",
            "llama3"
        ),
        "gemini", new PresetConfig(
            "openai",  // Gemini uses OpenAI-compatible API via proxy
            "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
            "gemini-2.0-flash"
        )
    );

    // --- validation ---

    @jakarta.annotation.PostConstruct
    public void validate() {
        // Apply preset if specified
        if (preset != null && !preset.isBlank() && !preset.equals("custom")) {
            applyPreset(preset);
        }

        if (maxTokens < 100 || maxTokens > 4096) {
            throw new IllegalArgumentException("ai.maxTokens must be between 100 and 4096");
        }
        if (temperature < 0.0 || temperature > 1.0) {
            throw new IllegalArgumentException("ai.temperature must be between 0.0 and 1.0");
        }
        if (timeoutMs < 5000 || timeoutMs > 120000) {
            throw new IllegalArgumentException("ai.timeoutMs must be between 5000 and 120000");
        }
        if (maxRetries < 0 || maxRetries > 5) {
            throw new IllegalArgumentException("ai.maxRetries must be between 0 and 5");
        }
        if (rateLimitPerMinute < 1 || rateLimitPerMinute > 100) {
            throw new IllegalArgumentException("ai.rateLimitPerMinute must be between 1 and 100");
        }
        if (rateLimitPerHour < 10 || rateLimitPerHour > 1000) {
            throw new IllegalArgumentException("ai.rateLimitPerHour must be between 10 and 1000");
        }
        if (cacheTtlMinutes < 1 || cacheTtlMinutes > 60) {
            throw new IllegalArgumentException("ai.cacheTtlMinutes must be between 1 and 60");
        }

        // Log configuration
        log.info("AI Configuration: enabled={}, provider={}, model={}, baseUrl={}",
            enabled, provider, model, baseUrl);
    }

    /**
     * Apply preset configuration.
     */
    public void applyPreset(String presetName) {
        PresetConfig config = PRESETS.get(presetName.toLowerCase());
        if (config != null) {
            this.provider = config.provider;
            this.baseUrl = config.baseUrl;
            // Only override model if it's still a known preset default (not user-specified via AI_MODEL)
            if (this.model == null || this.model.isBlank() || 
                this.model.equals("claude-sonnet-4-20250514") ||
                this.model.equals("gpt-4o-mini") ||
                this.model.equals("gemini-1.5-flash") ||
                this.model.equals("llama-3.3-70b-versatile") ||
                this.model.equals("llama3")) {
                this.model = config.defaultModel;
            }
            log.info("Applied AI preset: {} -> provider={}, model={}", presetName, this.provider, this.model);
        } else {
            log.warn("Unknown AI preset: {}. Available: {}", presetName, PRESETS.keySet());
        }
    }

    /**
     * Check if using Anthropic (requires special API format).
     */
    public boolean isAnthropic() {
        return "anthropic".equalsIgnoreCase(provider);
    }

    /**
     * Check if using OpenAI-compatible API (Groq, Ollama, Gemini).
     */
    public boolean isOpenAiCompatible() {
        return "openai".equalsIgnoreCase(provider);
    }

    // --- derived helpers ---

    /** Returns true when AI is enabled and properly configured. */
    public boolean isConfigured() {
        // For Ollama, API key is optional
        if ("ollama".equalsIgnoreCase(preset)) {
            return enabled && baseUrl != null && !baseUrl.isBlank();
        }
        return enabled && apiKey != null && !apiKey.isBlank() && baseUrl != null;
    }

    // --- getters / setters ---

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getPreset() { return preset; }
    public void setPreset(String preset) { this.preset = preset; }

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey == null ? "" : apiKey; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    /**
     * Get the resolved base URL (alias for getBaseUrl for test compatibility).
     */
    public String getResolvedBaseUrl() { return baseUrl; }

    public int getMaxTokens() { return maxTokens; }
    public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }

    public double getTemperature() { return temperature; }
    public void setTemperature(double temperature) { this.temperature = temperature; }

    public int getTimeoutMs() { return timeoutMs; }
    public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }

    public int getMaxRetries() { return maxRetries; }
    public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }

    public int getRateLimitPerMinute() { return rateLimitPerMinute; }
    public void setRateLimitPerMinute(int rateLimitPerMinute) { this.rateLimitPerMinute = rateLimitPerMinute; }

    public int getRateLimitPerHour() { return rateLimitPerHour; }
    public void setRateLimitPerHour(int rateLimitPerHour) { this.rateLimitPerHour = rateLimitPerHour; }

    public boolean isCacheEnabled() { return cacheEnabled; }
    public void setCacheEnabled(boolean cacheEnabled) { this.cacheEnabled = cacheEnabled; }

    public int getCacheTtlMinutes() { return cacheTtlMinutes; }
    public void setCacheTtlMinutes(int cacheTtlMinutes) { this.cacheTtlMinutes = cacheTtlMinutes; }

    public String getSystemPrompt() { return systemPrompt; }
    public void setSystemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; }
}