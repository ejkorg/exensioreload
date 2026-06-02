package com.onsemi.cim.apps.exensio.exensioreload.dto.ai;

import java.util.List;
import java.util.Map;

/**
 * Request for sending notifications.
 */
public class NotificationRequest {
    private String type;  // ALERT, SUMMARY, INCIDENT, RECOMMENDATION, CUSTOM
    private String severity;  // INFO, WARNING, CRITICAL
    private String title;
    private String message;
    private List<String> channels;  // SLACK, TEAMS, EMAIL, SMS, PAGERDUTY
    private Map<String, String> metadata;
    private boolean aiEnhanced = true;

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public List<String> getChannels() { return channels; }
    public void setChannels(List<String> channels) { this.channels = channels; }
    public Map<String, String> getMetadata() { return metadata; }
    public void setMetadata(Map<String, String> metadata) { this.metadata = metadata; }
    public boolean isAiEnhanced() { return aiEnhanced; }
    public void setAiEnhanced(boolean aiEnhanced) { this.aiEnhanced = aiEnhanced; }
}