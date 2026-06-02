package com.onsemi.cim.apps.exensio.exensioreload.dto.ai;

import java.util.List;
import java.util.Map;

/**
 * Response for predictive failure analysis.
 */
public class PredictiveFailureResponse {
    private boolean predictionsAvailable;
    private List<Prediction> predictions;
    private Map<String, Double> riskScores;
    private List<String> riskFactors;
    private String confidenceLevel;
    private List<String> preventiveActions;
    private long predictionTimestamp;

    public PredictiveFailureResponse() {}

    public boolean isPredictionsAvailable() { return predictionsAvailable; }
    public void setPredictionsAvailable(boolean predictionsAvailable) { this.predictionsAvailable = predictionsAvailable; }

    public List<Prediction> getPredictions() { return predictions; }
    public void setPredictions(List<Prediction> predictions) { this.predictions = predictions; }

    public Map<String, Double> getRiskScores() { return riskScores; }
    public void setRiskScores(Map<String, Double> riskScores) { this.riskScores = riskScores; }

    public List<String> getRiskFactors() { return riskFactors; }
    public void setRiskFactors(List<String> riskFactors) { this.riskFactors = riskFactors; }

    public String getConfidenceLevel() { return confidenceLevel; }
    public void setConfidenceLevel(String confidenceLevel) { this.confidenceLevel = confidenceLevel; }

    public List<String> getPreventiveActions() { return preventiveActions; }
    public void setPreventiveActions(List<String> preventiveActions) { this.preventiveActions = preventiveActions; }

    public long getPredictionTimestamp() { return predictionTimestamp; }
    public void setPredictionTimestamp(long predictionTimestamp) { this.predictionTimestamp = predictionTimestamp; }

    public static class Prediction {
        private String entityId;
        private String entityType;
        private String predictedOutcome;
        private Double probability;
        private String timeframe;
        private String riskLevel;
        private List<String> indicators;
        private String recommendedAction;

        public String getEntityId() { return entityId; }
        public void setEntityId(String entityId) { this.entityId = entityId; }

        public String getEntityType() { return entityType; }
        public void setEntityType(String entityType) { this.entityType = entityType; }

        public String getPredictedOutcome() { return predictedOutcome; }
        public void setPredictedOutcome(String predictedOutcome) { this.predictedOutcome = predictedOutcome; }

        public Double getProbability() { return probability; }
        public void setProbability(Double probability) { this.probability = probability; }

        public String getTimeframe() { return timeframe; }
        public void setTimeframe(String timeframe) { this.timeframe = timeframe; }

        public String getRiskLevel() { return riskLevel; }
        public void setRiskLevel(String riskLevel) { this.riskLevel = riskLevel; }

        public List<String> getIndicators() { return indicators; }
        public void setIndicators(List<String> indicators) { this.indicators = indicators; }

        public String getRecommendedAction() { return recommendedAction; }
        public void setRecommendedAction(String recommendedAction) { this.recommendedAction = recommendedAction; }
    }
}