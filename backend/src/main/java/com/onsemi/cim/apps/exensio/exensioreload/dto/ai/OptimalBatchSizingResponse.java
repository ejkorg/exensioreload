package com.onsemi.cim.apps.exensio.exensioreload.dto.ai;

import java.util.List;
import java.util.Map;

/**
 * Response for optimal batch sizing.
 */
public class OptimalBatchSizingResponse {
    private int currentAverageBatchSize;
    private int optimalBatchSize;
    private int minRecommendedSize;
    private int maxRecommendedSize;
    private double confidence;
    private String reason;
    private List<SizeRecommendation> sizeRecommendations;
    private List<HistoricalDataPoint> historicalAnalysis;
    private List<String> riskFactors;
    private Map<String, Object> expectedImprovements;
    private String aiExplanation;
    private long generatedAt;

    public static class SizeRecommendation {
        private int batchSize;
        private String label;
        private String description;
        private double expectedSuccessRate;
        private String expectedThroughput;

        public int getBatchSize() { return batchSize; }
        public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
        public String getLabel() { return label; }
        public void setLabel(String label) { this.label = label; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public double getExpectedSuccessRate() { return expectedSuccessRate; }
        public void setExpectedSuccessRate(double expectedSuccessRate) { this.expectedSuccessRate = expectedSuccessRate; }
        public String getExpectedThroughput() { return expectedThroughput; }
        public void setExpectedThroughput(String expectedThroughput) { this.expectedThroughput = expectedThroughput; }
    }

    public static class HistoricalDataPoint {
        private int batchSize;
        private double successRate;
        private double avgProcessingTime;
        private double errorRate;
        private int sampleCount;

        public int getBatchSize() { return batchSize; }
        public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
        public double getSuccessRate() { return successRate; }
        public void setSuccessRate(double successRate) { this.successRate = successRate; }
        public double getAvgProcessingTime() { return avgProcessingTime; }
        public void setAvgProcessingTime(double avgProcessingTime) { this.avgProcessingTime = avgProcessingTime; }
        public double getErrorRate() { return errorRate; }
        public void setErrorRate(double errorRate) { this.errorRate = errorRate; }
        public int getSampleCount() { return sampleCount; }
        public void setSampleCount(int sampleCount) { this.sampleCount = sampleCount; }
    }

    // Getters and setters
    public int getCurrentAverageBatchSize() { return currentAverageBatchSize; }
    public void setCurrentAverageBatchSize(int currentAverageBatchSize) { this.currentAverageBatchSize = currentAverageBatchSize; }
    public int getOptimalBatchSize() { return optimalBatchSize; }
    public void setOptimalBatchSize(int optimalBatchSize) { this.optimalBatchSize = optimalBatchSize; }
    public int getMinRecommendedSize() { return minRecommendedSize; }
    public void setMinRecommendedSize(int minRecommendedSize) { this.minRecommendedSize = minRecommendedSize; }
    public int getMaxRecommendedSize() { return maxRecommendedSize; }
    public void setMaxRecommendedSize(int maxRecommendedSize) { this.maxRecommendedSize = maxRecommendedSize; }
    public double getConfidence() { return confidence; }
    public void setConfidence(double confidence) { this.confidence = confidence; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public List<SizeRecommendation> getSizeRecommendations() { return sizeRecommendations; }
    public void setSizeRecommendations(List<SizeRecommendation> sizeRecommendations) { this.sizeRecommendations = sizeRecommendations; }
    public List<HistoricalDataPoint> getHistoricalAnalysis() { return historicalAnalysis; }
    public void setHistoricalAnalysis(List<HistoricalDataPoint> historicalAnalysis) { this.historicalAnalysis = historicalAnalysis; }
    public List<String> getRiskFactors() { return riskFactors; }
    public void setRiskFactors(List<String> riskFactors) { this.riskFactors = riskFactors; }
    public Map<String, Object> getExpectedImprovements() { return expectedImprovements; }
    public void setExpectedImprovements(Map<String, Object> expectedImprovements) { this.expectedImprovements = expectedImprovements; }
    public String getAiExplanation() { return aiExplanation; }
    public void setAiExplanation(String aiExplanation) { this.aiExplanation = aiExplanation; }
    public long getGeneratedAt() { return generatedAt; }
    public void setGeneratedAt(long generatedAt) { this.generatedAt = generatedAt; }
}