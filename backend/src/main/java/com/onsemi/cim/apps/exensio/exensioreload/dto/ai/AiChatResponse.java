package com.onsemi.cim.apps.exensio.exensioreload.dto.ai;

import java.util.List;
import java.util.Map;

/**
 * Response from AI chat conversation.
 */
public class AiChatResponse {
    
    private String reply;
    private List<SuggestedAction> suggestedActions;
    private double confidence;
    private String conversationId;
    private Map<String, Object> metadata;

    public AiChatResponse() {}

    public static class SuggestedAction {
        private String label;
        private String action;
        private Map<String, Object> params;

        public SuggestedAction() {}

        public SuggestedAction(String label, String action) {
            this.label = label;
            this.action = action;
        }

        public String getLabel() { return label; }
        public void setLabel(String label) { this.label = label; }

        public String getAction() { return action; }
        public void setAction(String action) { this.action = action; }

        public Map<String, Object> getParams() { return params; }
        public void setParams(Map<String, Object> params) { this.params = params; }
    }

    public String getReply() { return reply; }
    public void setReply(String reply) { this.reply = reply; }

    public List<SuggestedAction> getSuggestedActions() { return suggestedActions; }
    public void setSuggestedActions(List<SuggestedAction> suggestedActions) { this.suggestedActions = suggestedActions; }

    public double getConfidence() { return confidence; }
    public void setConfidence(double confidence) { this.confidence = confidence; }

    public String getConversationId() { return conversationId; }
    public void setConversationId(String conversationId) { this.conversationId = conversationId; }

    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }
}