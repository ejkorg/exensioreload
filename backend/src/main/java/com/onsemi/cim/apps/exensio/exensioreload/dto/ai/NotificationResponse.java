package com.onsemi.cim.apps.exensio.exensioreload.dto.ai;

import java.util.List;
import java.util.Map;

/**
 * Response for notification sending.
 */
public class NotificationResponse {
    private String notificationId;
    private boolean success;
    private String errorMessage;
    private String timestamp;
    private List<String> channelsConfigured;
    private Map<String, ChannelStatus> channelStatuses;
    private String messagePreview;
    private boolean preferencesSaved;

    public static class ChannelStatus {
        private String channel;
        private String status;  // PENDING, DELIVERED, FAILED
        private String deliveredAt;
        private List<String> recipients;

        public String getChannel() { return channel; }
        public void setChannel(String channel) { this.channel = channel; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public String getDeliveredAt() { return deliveredAt; }
        public void setDeliveredAt(String deliveredAt) { this.deliveredAt = deliveredAt; }
        public List<String> getRecipients() { return recipients; }
        public void setRecipients(List<String> recipients) { this.recipients = recipients; }
    }

    // Getters and setters
    public String getNotificationId() { return notificationId; }
    public void setNotificationId(String notificationId) { this.notificationId = notificationId; }
    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public String getTimestamp() { return timestamp; }
    public void setTimestamp(String timestamp) { this.timestamp = timestamp; }
    public List<String> getChannelsConfigured() { return channelsConfigured; }
    public void setChannelsConfigured(List<String> channelsConfigured) { this.channelsConfigured = channelsConfigured; }
    public Map<String, ChannelStatus> getChannelStatuses() { return channelStatuses; }
    public void setChannelStatuses(Map<String, ChannelStatus> channelStatuses) { this.channelStatuses = channelStatuses; }
    public String getMessagePreview() { return messagePreview; }
    public void setMessagePreview(String messagePreview) { this.messagePreview = messagePreview; }
    public boolean isPreferencesSaved() { return preferencesSaved; }
    public void setPreferencesSaved(boolean preferencesSaved) { this.preferencesSaved = preferencesSaved; }
}