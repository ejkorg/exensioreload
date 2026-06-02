package com.onsemi.cim.apps.exensio.exensioreload.dto.ai;

import java.util.List;

/**
 * Request for alert summarization.
 */
public class AiSummarizeRequest {
    
    private List<AlertData> alerts;
    private String summaryType; // "alerts", "sessions", "failures"

    public AiSummarizeRequest() {}

    public static class AlertData {
        private String sender;
        private String error;
        private String timestamp;
        private String severity; // "LOW", "MEDIUM", "HIGH", "CRITICAL"
        private String lotId;
        private String waferId;

        public AlertData() {}

        public String getSender() { return sender; }
        public void setSender(String sender) { this.sender = sender; }

        public String getError() { return error; }
        public void setError(String error) { this.error = error; }

        public String getTimestamp() { return timestamp; }
        public void setTimestamp(String timestamp) { this.timestamp = timestamp; }

        public String getSeverity() { return severity; }
        public void setSeverity(String severity) { this.severity = severity; }

        public String getLotId() { return lotId; }
        public void setLotId(String lotId) { this.lotId = lotId; }

        public String getWaferId() { return waferId; }
        public void setWaferId(String waferId) { this.waferId = waferId; }
    }

    public List<AlertData> getAlerts() { return alerts; }
    public void setAlerts(List<AlertData> alerts) { this.alerts = alerts; }

    public String getSummaryType() { return summaryType; }
    public void setSummaryType(String summaryType) { this.summaryType = summaryType; }
}