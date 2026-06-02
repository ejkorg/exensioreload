package com.onsemi.cim.apps.exensio.exensioreload.dto.ai;

import java.util.List;

/**
 * Response from alert summarization.
 */
public class AiSummarizeResponse {
    
    private String summary;
    private List<AlertGroup> groups;
    private String priority; // "LOW", "MEDIUM", "HIGH", "CRITICAL"
    private int totalAlerts;
    private List<String> recommendations;

    public AiSummarizeResponse() {}

    public static class AlertGroup {
        private String issue;
        private int count;
        private List<String> senders;
        private String recommendation;
        private String likelyCause;

        public AlertGroup() {}

        public String getIssue() { return issue; }
        public void setIssue(String issue) { this.issue = issue; }

        public int getCount() { return count; }
        public void setCount(int count) { this.count = count; }

        public List<String> getSenders() { return senders; }
        public void setSenders(List<String> senders) { this.senders = senders; }

        public String getRecommendation() { return recommendation; }
        public void setRecommendation(String recommendation) { this.recommendation = recommendation; }

        public String getLikelyCause() { return likelyCause; }
        public void setLikelyCause(String likelyCause) { this.likelyCause = likelyCause; }
    }

    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }

    public List<AlertGroup> getGroups() { return groups; }
    public void setGroups(List<AlertGroup> groups) { this.groups = groups; }

    public String getPriority() { return priority; }
    public void setPriority(String priority) { this.priority = priority; }

    public int getTotalAlerts() { return totalAlerts; }
    public void setTotalAlerts(int totalAlerts) { this.totalAlerts = totalAlerts; }

    public List<String> getRecommendations() { return recommendations; }
    public void setRecommendations(List<String> recommendations) { this.recommendations = recommendations; }
}