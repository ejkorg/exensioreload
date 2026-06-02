package com.onsemi.cim.apps.exensio.exensioreload.dto.ai;

import java.util.List;

/**
 * Request for smart alert triage.
 */
public class AlertTriageRequest {
    private List<Alert> alerts;

    public AlertTriageRequest() {}

    public List<Alert> getAlerts() { return alerts; }
    public void setAlerts(List<Alert> alerts) { this.alerts = alerts; }

    public static class Alert {
        private String sender;
        private String error;
        private String severity;
        private String timestamp;
        private String lotId;
        private String site;
        private String status;

        public String getSender() { return sender; }
        public void setSender(String sender) { this.sender = sender; }

        public String getError() { return error; }
        public void setError(String error) { this.error = error; }

        public String getSeverity() { return severity; }
        public void setSeverity(String severity) { this.severity = severity; }

        public String getTimestamp() { return timestamp; }
        public void setTimestamp(String timestamp) { this.timestamp = timestamp; }

        public String getLotId() { return lotId; }
        public void setLotId(String lotId) { this.lotId = lotId; }

        public String getSite() { return site; }
        public void setSite(String site) { this.site = site; }

        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
    }
}