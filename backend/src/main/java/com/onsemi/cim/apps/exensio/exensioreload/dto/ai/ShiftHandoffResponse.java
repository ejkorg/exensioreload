package com.onsemi.cim.apps.exensio.exensioreload.dto.ai;

import java.util.List;
import java.util.Map;

/**
 * Response for shift handoff summary.
 */
public class ShiftHandoffResponse {
    private String shift;
    private String shiftDate;
    private String outgoingOperator;
    private String incomingOperator;
    private String summary;
    private List<String> keyAccomplishments;
    private List<IssueEntry> ongoingIssues;
    private List<ActionItem> pendingActions;
    private List<String> handoffNotes;
    private List<AlertEntry> criticalAlerts;
    private Map<String, Object> successMetrics;
    private List<String> recommendationsForIncoming;
    private String fullBriefing;
    private long handoffGeneratedAt;

    // Inner classes
    public static class IssueEntry {
        private String issue;
        private String severity;
        private String status;
        private String timeLogged;

        public String getIssue() { return issue; }
        public void setIssue(String issue) { this.issue = issue; }
        public String getSeverity() { return severity; }
        public void setSeverity(String severity) { this.severity = severity; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public String getTimeLogged() { return timeLogged; }
        public void setTimeLogged(String timeLogged) { this.timeLogged = timeLogged; }
    }

    public static class ActionItem {
        private String action;
        private String assignedTo;
        private String priority;

        public String getAction() { return action; }
        public void setAction(String action) { this.action = action; }
        public String getAssignedTo() { return assignedTo; }
        public void setAssignedTo(String assignedTo) { this.assignedTo = assignedTo; }
        public String getPriority() { return priority; }
        public void setPriority(String priority) { this.priority = priority; }
    }

    public static class AlertEntry {
        private String alertId;
        private String description;
        private String time;
        private String status;
        private String resolution;

        public String getAlertId() { return alertId; }
        public void setAlertId(String alertId) { this.alertId = alertId; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public String getTime() { return time; }
        public void setTime(String time) { this.time = time; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public String getResolution() { return resolution; }
        public void setResolution(String resolution) { this.resolution = resolution; }
    }

    // Getters and setters
    public String getShift() { return shift; }
    public void setShift(String shift) { this.shift = shift; }
    public String getShiftDate() { return shiftDate; }
    public void setShiftDate(String shiftDate) { this.shiftDate = shiftDate; }
    public String getOutgoingOperator() { return outgoingOperator; }
    public void setOutgoingOperator(String outgoingOperator) { this.outgoingOperator = outgoingOperator; }
    public String getIncomingOperator() { return incomingOperator; }
    public void setIncomingOperator(String incomingOperator) { this.incomingOperator = incomingOperator; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public List<String> getKeyAccomplishments() { return keyAccomplishments; }
    public void setKeyAccomplishments(List<String> keyAccomplishments) { this.keyAccomplishments = keyAccomplishments; }
    public List<IssueEntry> getOngoingIssues() { return ongoingIssues; }
    public void setOngoingIssues(List<IssueEntry> ongoingIssues) { this.ongoingIssues = ongoingIssues; }
    public List<ActionItem> getPendingActions() { return pendingActions; }
    public void setPendingActions(List<ActionItem> pendingActions) { this.pendingActions = pendingActions; }
    public List<String> getHandoffNotes() { return handoffNotes; }
    public void setHandoffNotes(List<String> handoffNotes) { this.handoffNotes = handoffNotes; }
    public List<AlertEntry> getCriticalAlerts() { return criticalAlerts; }
    public void setCriticalAlerts(List<AlertEntry> criticalAlerts) { this.criticalAlerts = criticalAlerts; }
    public Map<String, Object> getSuccessMetrics() { return successMetrics; }
    public void setSuccessMetrics(Map<String, Object> successMetrics) { this.successMetrics = successMetrics; }
    public List<String> getRecommendationsForIncoming() { return recommendationsForIncoming; }
    public void setRecommendationsForIncoming(List<String> recommendationsForIncoming) { this.recommendationsForIncoming = recommendationsForIncoming; }
    public String getFullBriefing() { return fullBriefing; }
    public void setFullBriefing(String fullBriefing) { this.fullBriefing = fullBriefing; }
    public long getHandoffGeneratedAt() { return handoffGeneratedAt; }
    public void setHandoffGeneratedAt(long handoffGeneratedAt) { this.handoffGeneratedAt = handoffGeneratedAt; }
}