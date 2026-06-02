package com.onsemi.cim.apps.exensio.exensioreload.dto.ai;

import java.util.List;
import java.util.Map;

/**
 * Response for cross-site comparison.
 */
public class CrossSiteComparisonResponse {
    private Map<String, Map<String, Object>> siteMetrics;
    private List<MetricComparison> metricComparisons;
    private List<Difference> identifiedDifferences;
    private List<String> bestPractices;
    private List<String> recommendations;
    private String insights;
    private long analysisTimestamp;

    public static class MetricComparison {
        private String metric;
        private double site1Value;
        private double site2Value;
        private double difference;
        private double percentageDifference;
        private String betterSite;
        private boolean differenceSignificant;

        public String getMetric() { return metric; }
        public void setMetric(String metric) { this.metric = metric; }
        public double getSite1Value() { return site1Value; }
        public void setSite1Value(double site1Value) { this.site1Value = site1Value; }
        public double getSite2Value() { return site2Value; }
        public void setSite2Value(double site2Value) { this.site2Value = site2Value; }
        public double getDifference() { return difference; }
        public void setDifference(double difference) { this.difference = difference; }
        public double getPercentageDifference() { return percentageDifference; }
        public void setPercentageDifference(double percentageDifference) { this.percentageDifference = percentageDifference; }
        public String getBetterSite() { return betterSite; }
        public void setBetterSite(String betterSite) { this.betterSite = betterSite; }
        public boolean isDifferenceSignificant() { return differenceSignificant; }
        public void setDifferenceSignificant(boolean differenceSignificant) { this.differenceSignificant = differenceSignificant; }
    }

    public static class Difference {
        private String type;
        private String description;
        private String siteWithIssue;
        private String possibleCause;
        private String recommendation;

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public String getSiteWithIssue() { return siteWithIssue; }
        public void setSiteWithIssue(String siteWithIssue) { this.siteWithIssue = siteWithIssue; }
        public String getPossibleCause() { return possibleCause; }
        public void setPossibleCause(String possibleCause) { this.possibleCause = possibleCause; }
        public String getRecommendation() { return recommendation; }
        public void setRecommendation(String recommendation) { this.recommendation = recommendation; }
    }

    // Getters and setters
    public Map<String, Map<String, Object>> getSiteMetrics() { return siteMetrics; }
    public void setSiteMetrics(Map<String, Map<String, Object>> siteMetrics) { this.siteMetrics = siteMetrics; }
    public List<MetricComparison> getMetricComparisons() { return metricComparisons; }
    public void setMetricComparisons(List<MetricComparison> metricComparisons) { this.metricComparisons = metricComparisons; }
    public List<Difference> getIdentifiedDifferences() { return identifiedDifferences; }
    public void setIdentifiedDifferences(List<Difference> identifiedDifferences) { this.identifiedDifferences = identifiedDifferences; }
    public List<String> getBestPractices() { return bestPractices; }
    public void setBestPractices(List<String> bestPractices) { this.bestPractices = bestPractices; }
    public List<String> getRecommendations() { return recommendations; }
    public void setRecommendations(List<String> recommendations) { this.recommendations = recommendations; }
    public String getInsights() { return insights; }
    public void setInsights(String insights) { this.insights = insights; }
    public long getAnalysisTimestamp() { return analysisTimestamp; }
    public void setAnalysisTimestamp(long analysisTimestamp) { this.analysisTimestamp = analysisTimestamp; }
}