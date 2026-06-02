package com.onsemi.cim.apps.exensio.exensioreload.dto.ai;

import java.util.List;
import java.util.Map;

/**
 * Response for auto-incident report generation.
 */
public class AutoIncidentReportResponse {
    private String incidentId;
    private String reportDate;
    private String severity;
    private String executiveSummary;
    private List<TimelineEvent> incidentTimeline;
    private Map<String, Object> impactAnalysis;
    private String rootCauseDescription;
    private List<ResolutionStep> resolutionSteps;
    private List<String> lessonsLearned;
    private List<ActionItem> actionItems;
    private List<String> preventionRecommendations;
    private String complianceNotes;
    private String fullReportText;
    private long reportGeneratedAt;

    public static class TimelineEvent {
        private String time;
        private String event;
        private String type;
        private String details;

        public String getTime() { return time; }
        public void setTime(String time) { this.time = time; }
        public String getEvent() { return event; }
        public void setEvent(String event) { this.event = event; }
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public String getDetails() { return details; }
        public void setDetails(String details) { this.details = details; }
    }

    public static class ResolutionStep {
        private int step;
        private String action;
        private String result;
        private String timeToComplete;

        public int getStep() { return step; }
        public void setStep(int step) { this.step = step; }
        public String getAction() { return action; }
        public void setAction(String action) { this.action = action; }
        public String getResult() { return result; }
        public void setResult(String result) { this.result = result; }
        public String getTimeToComplete() { return timeToComplete; }
        public void setTimeToComplete(String timeToComplete) { this.timeToComplete = timeToComplete; }
    }

    public static class ActionItem {
        private String action;
        private String owner;
        private String dueDate;
        private String priority;

        public String getAction() { return action; }
        public void setAction(String action) { this.action = action; }
        public String getOwner() { return owner; }
        public void setOwner(String owner) { this.owner = owner; }
        public String getDueDate() { return dueDate; }
        public void setDueDate(String dueDate) { this.dueDate = dueDate; }
        public String getPriority() { return priority; }
        public void setPriority(String priority) { this.priority = priority; }
    }

    // Getters and setters
    public String getIncidentId() { return incidentId; }
    public void setIncidentId(String incidentId) { this.incidentId = incidentId; }
    public String getReportDate() { return reportDate; }
    public void setReportDate(String reportDate) { this.reportDate = reportDate; }
    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }
    public String getExecutiveSummary() { return executiveSummary; }
    public void setExecutiveSummary(String executiveSummary) { this.executiveSummary = executiveSummary; }
    public List<TimelineEvent> getIncidentTimeline() { return incidentTimeline; }
    public void setIncidentTimeline(List<TimelineEvent> incidentTimeline) { this.incidentTimeline = incidentTimeline; }
    public Map<String, Object> getImpactAnalysis() { return impactAnalysis; }
    public void setImpactAnalysis(Map<String, Object> impactAnalysis) { this.impactAnalysis = impactAnalysis; }
    public String getRootCauseDescription() { return rootCauseDescription; }
    public void setRootCauseDescription(String rootCauseDescription) { this.rootCauseDescription = rootCauseDescription; }
    public List<ResolutionStep> getResolutionSteps() { return resolutionSteps; }
    public void setResolutionSteps(List<ResolutionStep> resolutionSteps) { this.resolutionSteps = resolutionSteps; }
    public List<String> getLessonsLearned() { return lessonsLearned; }
    public void setLessonsLearned(List<String> lessonsLearned) { this.lessonsLearned = lessonsLearned; }
    public List<ActionItem> getActionItems() { return actionItems; }
    public void setActionItems(List<ActionItem> actionItems) { this.actionItems = actionItems; }
    public List<String> getPreventionRecommendations() { return preventionRecommendations; }
    public void setPreventionRecommendations(List<String> preventionRecommendations) { this.preventionRecommendations = preventionRecommendations; }
    public String getComplianceNotes() { return complianceNotes; }
    public void setComplianceNotes(String complianceNotes) { this.complianceNotes = complianceNotes; }
    public String getFullReportText() { return fullReportText; }
    public void setFullReportText(String fullReportText) { this.fullReportText = fullReportText; }
    public long getReportGeneratedAt() { return reportGeneratedAt; }
    public void setReportGeneratedAt(long reportGeneratedAt) { this.reportGeneratedAt = reportGeneratedAt; }
}