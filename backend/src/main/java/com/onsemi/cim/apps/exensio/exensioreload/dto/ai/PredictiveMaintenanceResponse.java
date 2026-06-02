package com.onsemi.cim.apps.exensio.exensioreload.dto.ai;

import java.util.List;

/**
 * Response for predictive maintenance analysis.
 */
public class PredictiveMaintenanceResponse {
    private double overallHealthScore;
    private int maintenanceDueCount;
    private int criticalPredictions;
    private List<ComponentHealth> components;
    private List<MaintenanceAlert> maintenanceAlerts;
    private List<String> recommendations;
    private int estimatedDowntimePrevention;  // hours
    private double costSavingsEstimate;
    private String insights;
    private long analysisTimestamp;

    // Inner classes
    public static class ComponentHealth {
        private String componentId;
        private String componentType;
        private double healthScore;           // 0.0 - 1.0
        private String status;                // HEALTHY, CAUTION, WARNING, CRITICAL
        private double errorRate;
        private double averageResponseTime;
        private double uptimePercentage;
        private double failureProbability;    // 0.0 - 1.0
        private String predictedFailureTime;
        private String lastMaintenance;
        private int daysSinceMaintenance;
        private List<String> maintenanceRecommendations;

        public String getComponentId() { return componentId; }
        public void setComponentId(String componentId) { this.componentId = componentId; }
        public String getComponentType() { return componentType; }
        public void setComponentType(String componentType) { this.componentType = componentType; }
        public double getHealthScore() { return healthScore; }
        public void setHealthScore(double healthScore) { this.healthScore = healthScore; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public double getErrorRate() { return errorRate; }
        public void setErrorRate(double errorRate) { this.errorRate = errorRate; }
        public double getAverageResponseTime() { return averageResponseTime; }
        public void setAverageResponseTime(double averageResponseTime) { this.averageResponseTime = averageResponseTime; }
        public double getUptimePercentage() { return uptimePercentage; }
        public void setUptimePercentage(double uptimePercentage) { this.uptimePercentage = uptimePercentage; }
        public double getFailureProbability() { return failureProbability; }
        public void setFailureProbability(double failureProbability) { this.failureProbability = failureProbability; }
        public String getPredictedFailureTime() { return predictedFailureTime; }
        public void setPredictedFailureTime(String predictedFailureTime) { this.predictedFailureTime = predictedFailureTime; }
        public String getLastMaintenance() { return lastMaintenance; }
        public void setLastMaintenance(String lastMaintenance) { this.lastMaintenance = lastMaintenance; }
        public int getDaysSinceMaintenance() { return daysSinceMaintenance; }
        public void setDaysSinceMaintenance(int daysSinceMaintenance) { this.daysSinceMaintenance = daysSinceMaintenance; }
        public List<String> getMaintenanceRecommendations() { return maintenanceRecommendations; }
        public void setMaintenanceRecommendations(List<String> maintenanceRecommendations) { this.maintenanceRecommendations = maintenanceRecommendations; }
    }

    public static class MaintenanceAlert {
        private String alertId;
        private String componentId;
        private String componentType;
        private String alertType;
        private String severity;
        private String description;
        private String recommendedAction;
        private int estimatedDowntime;  // hours
        private String createdAt;

        public String getAlertId() { return alertId; }
        public void setAlertId(String alertId) { this.alertId = alertId; }
        public String getComponentId() { return componentId; }
        public void setComponentId(String componentId) { this.componentId = componentId; }
        public String getComponentType() { return componentType; }
        public void setComponentType(String componentType) { this.componentType = componentType; }
        public String getAlertType() { return alertType; }
        public void setAlertType(String alertType) { this.alertType = alertType; }
        public String getSeverity() { return severity; }
        public void setSeverity(String severity) { this.severity = severity; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public String getRecommendedAction() { return recommendedAction; }
        public void setRecommendedAction(String recommendedAction) { this.recommendedAction = recommendedAction; }
        public int getEstimatedDowntime() { return estimatedDowntime; }
        public void setEstimatedDowntime(int estimatedDowntime) { this.estimatedDowntime = estimatedDowntime; }
        public String getCreatedAt() { return createdAt; }
        public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
    }

    // Getters and setters
    public double getOverallHealthScore() { return overallHealthScore; }
    public void setOverallHealthScore(double overallHealthScore) { this.overallHealthScore = overallHealthScore; }
    public int getMaintenanceDueCount() { return maintenanceDueCount; }
    public void setMaintenanceDueCount(int maintenanceDueCount) { this.maintenanceDueCount = maintenanceDueCount; }
    public int getCriticalPredictions() { return criticalPredictions; }
    public void setCriticalPredictions(int criticalPredictions) { this.criticalPredictions = criticalPredictions; }
    public List<ComponentHealth> getComponents() { return components; }
    public void setComponents(List<ComponentHealth> components) { this.components = components; }
    public List<MaintenanceAlert> getMaintenanceAlerts() { return maintenanceAlerts; }
    public void setMaintenanceAlerts(List<MaintenanceAlert> maintenanceAlerts) { this.maintenanceAlerts = maintenanceAlerts; }
    public List<String> getRecommendations() { return recommendations; }
    public void setRecommendations(List<String> recommendations) { this.recommendations = recommendations; }
    public int getEstimatedDowntimePrevention() { return estimatedDowntimePrevention; }
    public void setEstimatedDowntimePrevention(int estimatedDowntimePrevention) { this.estimatedDowntimePrevention = estimatedDowntimePrevention; }
    public double getCostSavingsEstimate() { return costSavingsEstimate; }
    public void setCostSavingsEstimate(double costSavingsEstimate) { this.costSavingsEstimate = costSavingsEstimate; }
    public String getInsights() { return insights; }
    public void setInsights(String insights) { this.insights = insights; }
    public long getAnalysisTimestamp() { return analysisTimestamp; }
    public void setAnalysisTimestamp(long analysisTimestamp) { this.analysisTimestamp = analysisTimestamp; }
}