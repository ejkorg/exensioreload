package com.onsemi.cim.apps.exensio.exensioreload.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.onsemi.cim.apps.exensio.exensioreload.config.AiProperties;
import com.onsemi.cim.apps.exensio.exensioreload.dto.ai.AiChatRequest;
import com.onsemi.cim.apps.exensio.exensioreload.dto.ai.AiChatResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service for handling AI chat conversations.
 * 
 * <p>Manages conversation history, parses AI responses, and extracts
 * structured data like suggested actions.</p>
 */
@Service
public class AiChatService {

    private static final Logger log = LoggerFactory.getLogger(AiChatService.class);

    private final AiGatewayService gatewayService;
    private final ObjectMapper objectMapper;
    
    // Store conversation histories by conversation ID
    private final Map<String, List<AiGatewayService.ConversationMessage>> conversations = new ConcurrentHashMap<>();
    
    // Configuration for what actions to recognize
    private static final Set<String> KNOWN_ACTIONS = Set.of(
        "retry", "view", "resend", "cancel", "filter", "search", "navigate", "refresh"
    );

    public AiChatService(AiGatewayService gatewayService, ObjectMapper objectMapper) {
        this.gatewayService = gatewayService;
        this.objectMapper = objectMapper;
    }

    /**
     * Process a chat request and return a structured response.
     */
    public AiChatResponse process(AiChatRequest request) {
        AiChatResponse response = new AiChatResponse();
        
        try {
            String conversationId = request.getConversationId();
            if (conversationId == null) {
                conversationId = UUID.randomUUID().toString();
            }
            
            // Get or create conversation history
            List<AiGatewayService.ConversationMessage> history = 
                conversations.computeIfAbsent(conversationId, k -> new ArrayList<>());
            
            // Trim history if too long (keep last 20 messages)
            while (history.size() > 40) {
                history.remove(0);
            }
            
            // Call the gateway
            String reply = gatewayService.sendMessage(
                request.getMessage(),
                request.getContext(),
                history
            );
            
            // Add user message to history
            history.add(new AiGatewayService.ConversationMessage("user", request.getMessage()));
            // Add AI response to history
            history.add(new AiGatewayService.ConversationMessage("assistant", reply));
            
            // Parse the response
            response.setReply(reply);
            response.setConversationId(conversationId);
            response.setSuggestedActions(parseSuggestedActions(reply));
            response.setConfidence(estimateConfidence(reply));
            response.setMetadata(buildMetadata(reply));
            
            log.info("Chat processed for conversation {}: {} chars response", 
                     conversationId, reply.length());
            
        } catch (AiGatewayService.AiServiceException e) {
            log.error("AI service error: {}", e.getMessage());
            response.setReply("I'm sorry, I encountered an error: " + e.getMessage() + 
                             ". Please try again or contact support if the issue persists.");
            response.setConfidence(0.0);
        } catch (Exception e) {
            log.error("Unexpected error processing chat: {}", e.getMessage(), e);
            response.setReply("An unexpected error occurred. Please try again.");
            response.setConfidence(0.0);
        }
        
        return response;
    }

    /**
     * Parse suggested actions from the AI response.
     * Looks for structured format like: [ACTION: retry, lotId=LOT123]
     */
    private List<AiChatResponse.SuggestedAction> parseSuggestedActions(String reply) {
        List<AiChatResponse.SuggestedAction> actions = new ArrayList<>();
        
        // Look for action markers in the response
        if (reply.contains("[ACTION:")) {
            int start = reply.indexOf("[ACTION:");
            int end = reply.indexOf("]", start);
            
            while (start != -1 && end != -1) {
                String actionBlock = reply.substring(start + 8, end).trim();
                actions.add(parseActionBlock(actionBlock));
                
                start = reply.indexOf("[ACTION:", end);
                end = reply.indexOf("]", start);
            }
        }
        
        return actions.isEmpty() ? null : actions;
    }

    private AiChatResponse.SuggestedAction parseActionBlock(String block) {
        AiChatResponse.SuggestedAction action = new AiChatResponse.SuggestedAction();
        Map<String, Object> params = new HashMap<>();
        
        String[] parts = block.split(",");
        for (String part : parts) {
            String[] keyValue = part.split("=", 2);
            if (keyValue.length == 2) {
                String key = keyValue[0].trim();
                String value = keyValue[1].trim();
                params.put(key, value);
                
                if ("action".equalsIgnoreCase(key)) {
                    action.setAction(value);
                } else if ("label".equalsIgnoreCase(key)) {
                    action.setLabel(value);
                }
            }
        }
        
        // Set defaults if not provided
        if (action.getLabel() == null && action.getAction() != null) {
            action.setLabel(capitalize(action.getAction()));
        }
        
        action.setParams(params.isEmpty() ? null : params);
        return action;
    }

    private String capitalize(String str) {
        if (str == null || str.isEmpty()) return str;
        return str.substring(0, 1).toUpperCase() + str.substring(1);
    }

    /**
     * Estimate confidence in the response quality.
     */
    private double estimateConfidence(String reply) {
        double confidence = 0.8;
        
        // Lower confidence for uncertain language
        if (reply.contains("I don't know") || reply.contains("I'm not sure")) {
            confidence -= 0.3;
        }
        
        // Higher confidence for structured responses
        if (reply.contains("•") || reply.contains("-") || reply.contains(":")) {
            confidence += 0.1;
        }
        
        // Lower for very short responses
        if (reply.length() < 20) {
            confidence -= 0.2;
        }
        
        // Higher for specific data (lot IDs, timestamps)
        if (reply.matches(".*LOT\\d+.*") || reply.matches(".*\\d{4}-\\d{2}-\\d{2}.*")) {
            confidence += 0.1;
        }
        
        return Math.max(0.0, Math.min(1.0, confidence));
    }

    /**
     * Build metadata about the response.
     */
    private Map<String, Object> buildMetadata(String reply) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("length", reply.length());
        metadata.put("timestamp", System.currentTimeMillis());
        
        // Count key elements
        int lotMentions = countOccurrences(reply, "LOT");
        int senderMentions = countOccurrences(reply, "Sender");
        
        metadata.put("lotMentions", lotMentions);
        metadata.put("senderMentions", senderMentions);
        
        return metadata;
    }

    private int countOccurrences(String text, String pattern) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(pattern, index)) != -1) {
            count++;
            index += pattern.length();
        }
        return count;
    }

    /**
     * Clear conversation history for a specific conversation.
     */
    public void clearConversation(String conversationId) {
        conversations.remove(conversationId);
        log.info("Cleared conversation history: {}", conversationId);
    }

    /**
     * Get current history size for monitoring.
     */
    public int getHistorySize(String conversationId) {
        List<AiGatewayService.ConversationMessage> history = conversations.get(conversationId);
        return history != null ? history.size() : 0;
    }

    /**
     * Check if AI is properly configured and ready.
     */
    public boolean isAvailable() {
        return gatewayService.isHealthy();
    }
}