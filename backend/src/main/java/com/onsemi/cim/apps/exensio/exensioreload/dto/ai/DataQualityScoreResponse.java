package com.onsemi.cim.apps.exensio.exensioreload.dto.ai;

import java.util.List;
import java.util.Map;

/**
 * Response for data quality score.
 */
public class DataQualityScoreResponse {
    private double overallScore;
    private String grade;
    private int totalRecords;
    private int passedRecords;
    private int failedRecords;
    private Map<String, Double> dimensionScores;
    private List<QualityIssue> issues;
    private List<String> recommendations;
    private boolean readyForExensio;

    public DataQualityScoreResponse() {}

    public double getOverallScore() { return overallScore; }
    public void setOverallScore(double overallScore) { this.overallScore = overallScore; }

    public String getGrade() { return grade; }
    public void setGrade(String grade) { this.grade = grade; }

    public int getTotalRecords() { return totalRecords; }
    public void setTotalRecords(int totalRecords) { this.totalRecords = totalRecords; }

    public int getPassedRecords() { return passedRecords; }
    public void setPassedRecords(int passedRecords) { this.passedRecords = passedRecords; }

    public int getFailedRecords() { return failedRecords; }
    public void setFailedRecords(int failedRecords) { this.failedRecords = failedRecords; }

    public Map<String, Double> getDimensionScores() { return dimensionScores; }
    public void setDimensionScores(Map<String, Double> dimensionScores) { this.dimensionScores = dimensionScores; }

    public List<QualityIssue> getIssues() { return issues; }
    public void setIssues(List<QualityIssue> issues) { this.issues = issues; }

    public List<String> getRecommendations() { return recommendations; }
    public void setRecommendations(List<String> recommendations) { this.recommendations = recommendations; }

    public boolean isReadyForExensio() { return readyForExensio; }
    public void setReadyForExensio(boolean readyForExensio) { this.readyForExensio = readyForExensio; }

    public static class QualityIssue {
        private String field;
        private String issueType;
        private int affectedCount;
        private String severity;
        private String description;
        private String suggestion;

        public String getField() { return field; }
        public void setField(String field) { this.field = field; }

        public String getIssueType() { return issueType; }
        public void setIssueType(String issueType) { this.issueType = issueType; }

        public int getAffectedCount() { return affectedCount; }
        public void setAffectedCount(int affectedCount) { this.affectedCount = affectedCount; }

        public String getSeverity() { return severity; }
        public void setSeverity(String severity) { this.severity = severity; }

        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }

        public String getSuggestion() { return suggestion; }
        public void setSuggestion(String suggestion) { this.suggestion = suggestion; }
    }
}