package com.onsemi.cim.apps.exensio.exensioreload.dto.ai;

import java.util.List;
import java.util.Map;

/**
 * Response for session recommendations.
 */
public class SessionRecommendationResponse {
    private String recommendationSummary;
    private List<RecommendedSetting> recommendedSettings;
    private List<String> similarSessionIds;
    private String confidence;
    private Map<String, Object> predictedOutcome;

    public SessionRecommendationResponse() {}

    public String getRecommendationSummary() { return recommendationSummary; }
    public void setRecommendationSummary(String recommendationSummary) { this.recommendationSummary = recommendationSummary; }

    public List<RecommendedSetting> getRecommendedSettings() { return recommendedSettings; }
    public void setRecommendedSettings(List<RecommendedSetting> recommendedSettings) { this.recommendedSettings = recommendedSettings; }

    public List<String> getSimilarSessionIds() { return similarSessionIds; }
    public void setSimilarSessionIds(List<String> similarSessionIds) { this.similarSessionIds = similarSessionIds; }

    public String getConfidence() { return confidence; }
    public void setConfidence(String confidence) { this.confidence = confidence; }

    public Map<String, Object> getPredictedOutcome() { return predictedOutcome; }
    public void setPredictedOutcome(Map<String, Object> predictedOutcome) { this.predictedOutcome = predictedOutcome; }

    public static class RecommendedSetting {
        private String setting;
        private String currentValue;
        private String recommendedValue;
        private String reason;
        private Double impactScore;

        public String getSetting() { return setting; }
        public void setSetting(String setting) { this.setting = setting; }

        public String getCurrentValue() { return currentValue; }
        public void setCurrentValue(String currentValue) { this.currentValue = currentValue; }

        public String getRecommendedValue() { return recommendedValue; }
        public void setRecommendedValue(String recommendedValue) { this.recommendedValue = recommendedValue; }

        public String getReason() { return reason; }
        public void setReason(String reason) { this.reason = reason; }

        public Double getImpactScore() { return impactScore; }
        public void setImpactScore(Double impactScore) { this.impactScore = impactScore; }
    }
}