package com.onsemi.cim.apps.exensio.exensioreload.dto.ai;

import java.util.Map;

/**
 * Request for AI chat conversation.
 */
public class AiChatRequest {
    
    private String message;
    private Map<String, Object> context;
    private String conversationId;

    public AiChatRequest() {}

    public AiChatRequest(String message) {
        this.message = message;
    }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public Map<String, Object> getContext() { return context; }
    public void setContext(Map<String, Object> context) { this.context = context; }

    public String getConversationId() { return conversationId; }
    public void setConversationId(String conversationId) { this.conversationId = conversationId; }
}