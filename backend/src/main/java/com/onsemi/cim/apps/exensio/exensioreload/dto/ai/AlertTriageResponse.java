package com.onsemi.cim.apps.exensio.exensioreload.dto.ai;

import java.util.List;

/**
 * Response for smart alert triage.
 */
public class AlertTriageResponse {
    private String triageSummary;
    private String overallPriority;
    private int totalAlerts;
    private int criticalCount;
    private int highCount;
    private int mediumCount;
    private int lowCount;
    private List<AlertGroup> groups;
    private List<RecommendedAction> recommendedActions;
    private long estimatedResolutionTime;

    public AlertTriageResponse() {}

    public String getTriageSummary() { return triageSummary; }
    public void setTriageSummary(String triageSummary) { this.triageSummary = triageSummary; }

    public String getOverallPriority() { return overallPriority; }
    public void setOverallPriority(String overallPriority) { this.overallPriority = overallPriority; }

    public int getTotalAlerts() { return totalAlerts; }
    public void setTotalAlerts(int totalAlerts) { this.totalAlerts = totalAlerts; }

    public int getCriticalCount() { return criticalCount; }
    public void setCriticalCount(int criticalCount) { this.criticalCount = criticalCount; }

    public int getHighCount() { return highCount; }
    public void setHighCount(int highCount) { this.highCount = highCount; }

    public int getMediumCount() { return mediumCount; }
    public void setMediumCount(int mediumCount) { this.mediumCount = mediumCount; }

    public int getLowCount() { return lowCount; }
    public void setLowCount(int lowCount) { this.lowCount = lowCount; }

    public List<AlertGroup> getGroups() { return groups; }
    public void setGroups(List<AlertGroup> groups) { this.groups = groups; }

    public List<RecommendedAction> getRecommendedActions() { return recommendedActions; }
    public void setRecommendedActions(List<RecommendedAction> recommendedActions) { this.recommendedActions = recommendedActions; }

    public long getEstimatedResolutionTime() { return estimatedResolutionTime; }
    public void setEstimatedResolutionTime(long estimatedResolutionTime) { this.estimatedResolutionTime = estimatedResolutionTime; }

    public static class AlertGroup {
        private String issueType;
        private int count;
        private List<String> affectedSenders;
        private List<String> affectedLots;
        private String rootCause;
        private String recommendation;

        public String getIssueType() { return issueType; }
        public void setIssueType(String issueType) { this.issueType = issueType; }

        public int getCount() { return count; }
        public void setCount(int count) { this.count = count; }

        public List<String> getAffectedSenders() { return affectedSenders; }
        public void setAffectedSenders(List<String> affectedSenders) { this.affectedSenders = affectedSenders; }

        public List<String> getAffectedLots() { return affectedLots; }
        public void setAffectedLots(List<String> affectedLots) { this.affectedLots = affectedLots; }

        public String getRootCause() { return rootCause; }
        public void setRootCause(String rootCause) { this.rootCause = rootCause; }

        public String getRecommendation() { return recommendation; }
        public void setRecommendation(String recommendation) { this.recommendation = recommendation; }
    }

    public static class RecommendedAction {
        private int priority;
        private String action;
        private String description;
        private List<String> affectedItems;
        private String estimatedTime;

        public int getPriority() { return priority; }
        public void setPriority(int priority) { this.priority = priority; }

        public String getAction() { return action; }
        public void setAction(String action) { this.action = action; }

        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }

        public List<String> getAffectedItems() { return affectedItems; }
        public void setAffectedItems(List<String> affectedItems) { this.affectedItems = affectedItems; }

        public String getEstimatedTime() { return estimatedTime; }
        public void setEstimatedTime(String estimatedTime) { this.estimatedTime = estimatedTime; }
    }
}