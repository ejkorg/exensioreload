package com.onsemi.cim.apps.exensio.exensioreload.dto.ai;

import java.util.List;
import java.util.Map;

/**
 * Response for daily summary.
 */
public class DailySummaryResponse {
    private String date;
    private String summary;
    private int totalSessions;
    private int totalRecords;
    private int successRate;
    private int errorRate;
    private Map<String, Integer> statusBreakdown;
    private List<TopIssue> topIssues;
    private List<TrendItem> trends;
    private List<String> highlights;
    private List<String> recommendations;
    private String operatorBriefing;

    public DailySummaryResponse() {}

    public String getDate() { return date; }
    public void setDate(String date) { this.date = date; }

    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }

    public int getTotalSessions() { return totalSessions; }
    public void setTotalSessions(int totalSessions) { this.totalSessions = totalSessions; }

    public int getTotalRecords() { return totalRecords; }
    public void setTotalRecords(int totalRecords) { this.totalRecords = totalRecords; }

    public int getSuccessRate() { return successRate; }
    public void setSuccessRate(int successRate) { this.successRate = successRate; }

    public int getErrorRate() { return errorRate; }
    public void setErrorRate(int errorRate) { this.errorRate = errorRate; }

    public Map<String, Integer> getStatusBreakdown() { return statusBreakdown; }
    public void setStatusBreakdown(Map<String, Integer> statusBreakdown) { this.statusBreakdown = statusBreakdown; }

    public List<TopIssue> getTopIssues() { return topIssues; }
    public void setTopIssues(List<TopIssue> topIssues) { this.topIssues = topIssues; }

    public List<TrendItem> getTrends() { return trends; }
    public void setTrends(List<TrendItem> trends) { this.trends = trends; }

    public List<String> getHighlights() { return highlights; }
    public void setHighlights(List<String> highlights) { this.highlights = highlights; }

    public List<String> getRecommendations() { return recommendations; }
    public void setRecommendations(List<String> recommendations) { this.recommendations = recommendations; }

    public String getOperatorBriefing() { return operatorBriefing; }
    public void setOperatorBriefing(String operatorBriefing) { this.operatorBriefing = operatorBriefing; }

    public static class TopIssue {
        private String issue;
        private int count;
        private String trend;
        private String impact;

        public String getIssue() { return issue; }
        public void setIssue(String issue) { this.issue = issue; }

        public int getCount() { return count; }
        public void setCount(int count) { this.count = count; }

        public String getTrend() { return trend; }
        public void setTrend(String trend) { this.trend = trend; }

        public String getImpact() { return impact; }
        public void setImpact(String impact) { this.impact = impact; }
    }

    public static class TrendItem {
        private String metric;
        private String direction;
        private double change;
        private String description;

        public String getMetric() { return metric; }
        public void setMetric(String metric) { this.metric = metric; }

        public String getDirection() { return direction; }
        public void setDirection(String direction) { this.direction = direction; }

        public double getChange() { return change; }
        public void setChange(double change) { this.change = change; }

        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
    }
}