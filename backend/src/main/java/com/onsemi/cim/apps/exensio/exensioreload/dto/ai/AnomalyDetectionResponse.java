package com.onsemi.cim.apps.exensio.exensioreload.dto.ai;

import java.util.List;
import java.util.Map;

/**
 * Response for anomaly detection.
 */
public class AnomalyDetectionResponse {
    private boolean anomaliesDetected;
    private int totalAnomalies;
    private List<Anomaly> anomalies;
    private Map<String, Double> baselineMetrics;
    private String overallRiskLevel;
    private List<String> recommendations;

    public AnomalyDetectionResponse() {}

    public boolean isAnomaliesDetected() { return anomaliesDetected; }
    public void setAnomaliesDetected(boolean anomaliesDetected) { this.anomaliesDetected = anomaliesDetected; }

    public int getTotalAnomalies() { return totalAnomalies; }
    public void setTotalAnomalies(int totalAnomalies) { this.totalAnomalies = totalAnomalies; }

    public List<Anomaly> getAnomalies() { return anomalies; }
    public void setAnomalies(List<Anomaly> anomalies) { this.anomalies = anomalies; }

    public Map<String, Double> getBaselineMetrics() { return baselineMetrics; }
    public void setBaselineMetrics(Map<String, Double> baselineMetrics) { this.baselineMetrics = baselineMetrics; }

    public String getOverallRiskLevel() { return overallRiskLevel; }
    public void setOverallRiskLevel(String overallRiskLevel) { this.overallRiskLevel = overallRiskLevel; }

    public List<String> getRecommendations() { return recommendations; }
    public void setRecommendations(List<String> recommendations) { this.recommendations = recommendations; }

    public static class Anomaly {
        private String type;
        private String description;
        private String severity;
        private String affectedEntity;
        private Double deviationFromBaseline;
        private String timestamp;
        private String probableCause;

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }

        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }

        public String getSeverity() { return severity; }
        public void setSeverity(String severity) { this.severity = severity; }

        public String getAffectedEntity() { return affectedEntity; }
        public void setAffectedEntity(String affectedEntity) { this.affectedEntity = affectedEntity; }

        public Double getDeviationFromBaseline() { return deviationFromBaseline; }
        public void setDeviationFromBaseline(Double deviationFromBaseline) { this.deviationFromBaseline = deviationFromBaseline; }

        public String getTimestamp() { return timestamp; }
        public void setTimestamp(String timestamp) { this.timestamp = timestamp; }

        public String getProbableCause() { return probableCause; }
        public void setProbableCause(String probableCause) { this.probableCause = probableCause; }
    }
}