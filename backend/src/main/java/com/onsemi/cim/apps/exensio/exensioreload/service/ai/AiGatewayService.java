package com.onsemi.cim.apps.exensio.exensioreload.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.onsemi.cim.apps.exensio.exensioreload.config.AiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gateway service for AI providers (Anthropic Claude, OpenAI, Groq, Ollama).
 * 
 * <p>Handles API communication, response parsing, caching, and error handling.
 * Uses presets from AiProperties for quick configuration.</p>
 */
@Service
public class AiGatewayService {

    private static final Logger log = LoggerFactory.getLogger(AiGatewayService.class);

    private final AiProperties props;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    
    // Simple in-memory cache for responses
    private final Map<String, CacheEntry> responseCache = new ConcurrentHashMap<>();

    public AiGatewayService(AiProperties props, HttpClient httpClient, ObjectMapper objectMapper) {
        this.props = props;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    /**
     * Send a message to the AI and get a response.
     */
    public String sendMessage(String userMessage, Map<String, Object> context, 
                              List<ConversationMessage> conversationHistory) {
        if (!props.isConfigured()) {
            throw new AiServiceException("AI is not configured. Set ai.enabled=true and ai.api-key (or use 'ollama' preset for local).");
        }

        // Check cache first
        String cacheKey = generateCacheKey(userMessage, context);
        if (props.isCacheEnabled()) {
            String cached = getCachedResponse(cacheKey);
            if (cached != null) {
                log.debug("Returning cached response for key: {}", cacheKey);
                return cached;
            }
        }

        // Call appropriate provider
        String response;
        if (props.isAnthropic()) {
            response = callAnthropic(userMessage, context, conversationHistory);
        } else {
            // OpenAI-compatible API (Groq, Ollama, Gemini, etc.)
            response = callOpenAiCompatible(userMessage, context, conversationHistory);
        }

        // Cache the response
        if (props.isCacheEnabled() && response != null) {
            cacheResponse(cacheKey, response);
        }

        return response;
    }

    /**
     * Simple single-message send without conversation history.
     */
    public String sendMessage(String userMessage, Map<String, Object> context) {
        return sendMessage(userMessage, context, Collections.emptyList());
    }

    /**
     * Call Anthropic Claude API.
     */
    private String callAnthropic(String userMessage, Map<String, Object> context,
                                 List<ConversationMessage> history) {
        int retries = 0;
        Exception lastException = null;

        while (retries <= props.getMaxRetries()) {
            try {
                return executeAnthropicRequest(userMessage, context, history);
            } catch (AiRetryableException e) {
                retries++;
                lastException = e;
                if (retries <= props.getMaxRetries()) {
                    log.warn("Retrying Anthropic API call (attempt {}/{}): {}", 
                             retries, props.getMaxRetries(), e.getMessage());
                    try {
                        Thread.sleep(1000L * retries);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new AiServiceException("Request interrupted during retry", ie);
                    }
                }
            } catch (Exception e) {
                throw new AiServiceException("Anthropic API call failed: " + e.getMessage(), e);
            }
        }

        throw new AiServiceException("Max retries exceeded", lastException);
    }

    private String executeAnthropicRequest(String userMessage, Map<String, Object> context,
                                          List<ConversationMessage> history) throws Exception {
        List<Map<String, String>> messages = new ArrayList<>();
        
        for (ConversationMessage msg : history) {
            messages.add(Map.of("role", msg.role, "content", msg.content));
        }
        
        String fullMessage = buildContextualMessage(userMessage, context);
        messages.add(Map.of("role", "user", "content", fullMessage));

        Map<String, Object> body = new HashMap<>();
        body.put("model", props.getModel());
        body.put("max_tokens", props.getMaxTokens());
        body.put("temperature", props.getTemperature());
        body.put("system", props.getSystemPrompt());
        body.put("messages", messages);

        String requestBody = objectMapper.writeValueAsString(body);

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(props.getBaseUrl()))
            .timeout(Duration.ofMillis(props.getTimeoutMs()))
            .header("Content-Type", "application/json")
            .header("x-api-key", props.getApiKey())
            .header("anthropic-version", "2023-06-01")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        
        return parseAnthropicResponse(response);
    }

    private String parseAnthropicResponse(HttpResponse<String> response) throws Exception {
        if (response.statusCode() >= 500) {
            throw new AiRetryableException("Anthropic API server error: " + response.statusCode());
        }
        
        if (response.statusCode() >= 400) {
            throw new AiServiceException("Anthropic API error: " + response.statusCode() + " - " + response.body());
        }

        JsonNode jsonResponse = objectMapper.readTree(response.body());
        
        if (jsonResponse.has("error")) {
            throw new AiServiceException("Anthropic API error: " + jsonResponse.get("error").asText());
        }

        JsonNode content = jsonResponse.get("content");
        if (content != null && content.isArray() && content.size() > 0) {
            JsonNode firstContent = content.get(0);
            if (firstContent.has("text")) {
                return firstContent.get("text").asText();
            }
        }

        return jsonResponse.path("content").path(0).path("text").asText("");
    }

    /**
     * Call OpenAI-compatible API (Groq, Ollama, Gemini, etc.).
     */
    private String callOpenAiCompatible(String userMessage, Map<String, Object> context,
                                        List<ConversationMessage> history) {
        int retries = 0;
        Exception lastException = null;

        while (retries <= props.getMaxRetries()) {
            try {
                return executeOpenAiCompatibleRequest(userMessage, context, history);
            } catch (AiRetryableException e) {
                retries++;
                lastException = e;
                if (retries <= props.getMaxRetries()) {
                    log.warn("Retrying OpenAI-compatible API call (attempt {}/{}): {}", 
                             retries, props.getMaxRetries(), e.getMessage());
                    try {
                        Thread.sleep(500L * retries); // Faster retry for OpenAI-compatible
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new AiServiceException("Request interrupted during retry", ie);
                    }
                }
            } catch (Exception e) {
                throw new AiServiceException(props.getPreset() + " API call failed: " + e.getMessage(), e);
            }
        }

        throw new AiServiceException("Max retries exceeded", lastException);
    }

    private String executeOpenAiCompatibleRequest(String userMessage, Map<String, Object> context,
                                                  List<ConversationMessage> history) throws Exception {
        List<Map<String, String>> messages = new ArrayList<>();
        
        // System message
        messages.add(Map.of(
            "role", "system",
            "content", props.getSystemPrompt()
        ));
        
        // Conversation history
        for (ConversationMessage msg : history) {
            messages.add(Map.of("role", msg.role, "content", msg.content));
        }
        
        // Current message
        messages.add(Map.of(
            "role", "user",
            "content", buildContextualMessage(userMessage, context)
        ));

        Map<String, Object> body = new HashMap<>();
        body.put("model", props.getModel());
        body.put("max_tokens", props.getMaxTokens());
        body.put("temperature", props.getTemperature());
        body.put("messages", messages);

        String requestBody = objectMapper.writeValueAsString(body);

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
            .uri(URI.create(props.getBaseUrl()))
            .timeout(Duration.ofMillis(props.getTimeoutMs()))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody));

        // Add auth header (not needed for Ollama local)
        if (!"ollama".equalsIgnoreCase(props.getPreset())) {
            requestBuilder.header("Authorization", "Bearer " + props.getApiKey());
        }

        HttpResponse<String> response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
        
        return parseOpenAiCompatibleResponse(response);
    }

    private String parseOpenAiCompatibleResponse(HttpResponse<String> response) throws Exception {
        if (response.statusCode() >= 500) {
            throw new AiRetryableException(props.getPreset() + " API server error: " + response.statusCode());
        }
        
        if (response.statusCode() >= 400) {
            throw new AiServiceException(props.getPreset() + " API error: " + response.statusCode() + " - " + response.body());
        }

        JsonNode jsonResponse = objectMapper.readTree(response.body());
        
        // Check for error field
        if (jsonResponse.has("error")) {
            JsonNode error = jsonResponse.get("error");
            String errorMsg = error.isObject() ? error.get("message").asText() : error.asText();
            throw new AiServiceException(props.getPreset() + " error: " + errorMsg);
        }

        JsonNode choices = jsonResponse.get("choices");
        if (choices != null && choices.isArray() && choices.size() > 0) {
            JsonNode firstChoice = choices.get(0);
            JsonNode message = firstChoice.get("message");
            if (message != null && message.has("content")) {
                return message.get("content").asText();
            }
        }

        return "";
    }

    /**
     * Build contextual message with additional context data.
     */
    private String buildContextualMessage(String userMessage, Map<String, Object> context) {
        if (context == null || context.isEmpty()) {
            return userMessage;
        }
        
        StringBuilder sb = new StringBuilder();
        sb.append(userMessage);
        sb.append("\n\n[Additional Context]\n");
        
        for (Map.Entry<String, Object> entry : context.entrySet()) {
            sb.append("- ").append(entry.getKey()).append(": ");
            sb.append(formatContextValue(entry.getValue()));
            sb.append("\n");
        }
        
        return sb.toString();
    }

    private String formatContextValue(Object value) {
        if (value == null) return "null";
        if (value instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) value;
            List<String> parts = new ArrayList<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                parts.add(entry.getKey() + "=" + entry.getValue());
            }
            return String.join(", ", parts);
        }
        if (value instanceof Collection) {
            return String.join(", ", value.toString().replaceAll("[\\[\\]]", "").split(", "));
        }
        return value.toString();
    }

    private String generateCacheKey(String message, Map<String, Object> context) {
        StringBuilder key = new StringBuilder(message.hashCode());
        if (context != null) {
            key.append("-").append(context.hashCode());
        }
        key.append("-").append(props.getPreset());
        return key.toString();
    }

    private String getCachedResponse(String key) {
        CacheEntry entry = responseCache.get(key);
        if (entry != null && !entry.isExpired(props.getCacheTtlMinutes())) {
            return entry.response;
        }
        responseCache.remove(key);
        return null;
    }

    private void cacheResponse(String key, String response) {
        responseCache.put(key, new CacheEntry(response, System.currentTimeMillis()));
    }

    public void clearCache() {
        responseCache.clear();
    }

    public boolean isHealthy() {
        return props.isConfigured();
    }

    // --- Inner classes ---

    public static class ConversationMessage {
        public String role;
        public String content;

        public ConversationMessage(String role, String content) {
            this.role = role;
            this.content = content;
        }
    }

    private static class CacheEntry {
        final String response;
        final long timestamp;

        CacheEntry(String response, long timestamp) {
            this.response = response;
            this.timestamp = timestamp;
        }

        boolean isExpired(int ttlMinutes) {
            return System.currentTimeMillis() - timestamp > ttlMinutes * 60 * 1000L;
        }
    }

    public static class AiRetryableException extends RuntimeException {
        public AiRetryableException(String message) {
            super(message);
        }
    }

    public static class AiServiceException extends RuntimeException {
        public AiServiceException(String message) {
            super(message);
        }

        public AiServiceException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}