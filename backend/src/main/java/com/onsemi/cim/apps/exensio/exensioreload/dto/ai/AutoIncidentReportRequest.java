package com.onsemi.cim.apps.exensio.exensioreload.dto.ai;

import java.util.List;

/**
 * Request for auto-incident report generation.
 */
public class AutoIncidentReportRequest {
    private String incidentId;
    private String severity;          // CRITICAL, HIGH, MEDIUM, LOW
    private String startTime;
    private String endTime;
    private List<String> affectedComponents;
    private List<String> errorMessages;
    private String site;
    private boolean includeActionItems;

    public String getIncidentId() { return incidentId; }
    public void setIncidentId(String incidentId) { this.incidentId = incidentId; }
    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }
    public String getStartTime() { return startTime; }
    public void setStartTime(String startTime) { this.startTime = startTime; }
    public String getEndTime() { return endTime; }
    public void setEndTime(String endTime) { this.endTime = endTime; }
    public List<String> getAffectedComponents() { return affectedComponents; }
    public void setAffectedComponents(List<String> affectedComponents) { this.affectedComponents = affectedComponents; }
    public List<String> getErrorMessages() { return errorMessages; }
    public void setErrorMessages(List<String> errorMessages) { this.errorMessages = errorMessages; }
    public String getSite() { return site; }
    public void setSite(String site) { this.site = site; }
    public boolean isIncludeActionItems() { return includeActionItems; }
    public void setIncludeActionItems(boolean includeActionItems) { this.includeActionItems = includeActionItems; }
}