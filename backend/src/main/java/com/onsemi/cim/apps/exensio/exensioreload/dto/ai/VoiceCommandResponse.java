package com.onsemi.cim.apps.exensio.exensioreload.dto.ai;

import java.util.Map;

/**
 * Response for voice command processing.
 */
public class VoiceCommandResponse {
    private boolean success;
    private String responseMessage;
    private CommandIntent intent;
    private Map<String, String> entities;
    private String actionUrl;
    private String actionConfirmation;

    public static class CommandIntent {
        private String intentType;
        private String action;
        private String target;
        private Map<String, String> parameters;

        public String getIntentType() { return intentType; }
        public void setIntentType(String intentType) { this.intentType = intentType; }
        public String getAction() { return action; }
        public void setAction(String action) { this.action = action; }
        public String getTarget() { return target; }
        public void setTarget(String target) { this.target = target; }
        public Map<String, String> getParameters() { return parameters; }
        public void setParameters(Map<String, String> parameters) { this.parameters = parameters; }
    }

    // Getters and setters
    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }
    public String getResponseMessage() { return responseMessage; }
    public void setResponseMessage(String responseMessage) { this.responseMessage = responseMessage; }
    public CommandIntent getIntent() { return intent; }
    public void setIntent(CommandIntent intent) { this.intent = intent; }
    public Map<String, String> getEntities() { return entities; }
    public void setEntities(Map<String, String> entities) { this.entities = entities; }
    public String getActionUrl() { return actionUrl; }
    public void setActionUrl(String actionUrl) { this.actionUrl = actionUrl; }
    public String getActionConfirmation() { return actionConfirmation; }
    public void setActionConfirmation(String actionConfirmation) { this.actionConfirmation = actionConfirmation; }
}