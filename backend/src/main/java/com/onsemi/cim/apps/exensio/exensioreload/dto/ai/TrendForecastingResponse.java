package com.onsemi.cim.apps.exensio.exensioreload.dto.ai;

import java.util.List;

/**
 * Response for trend forecasting.
 */
public class TrendForecastingResponse {
    private List<Trend> trends;
    private List<Forecast> forecasts;
    private List<Pattern> patterns;
    private List<PeakPrediction> peakPredictions;
    private List<String> staffingRecommendations;
    private String insights;
    private long generatedAt;

    public static class Trend {
        private String metric;
        private String direction;  // UP, DOWN, STABLE
        private double changePercent;
        private double averageValue;
        private double confidence;

        public String getMetric() { return metric; }
        public void setMetric(String metric) { this.metric = metric; }
        public String getDirection() { return direction; }
        public void setDirection(String direction) { this.direction = direction; }
        public double getChangePercent() { return changePercent; }
        public void setChangePercent(double changePercent) { this.changePercent = changePercent; }
        public double getAverageValue() { return averageValue; }
        public void setAverageValue(double averageValue) { this.averageValue = averageValue; }
        public double getConfidence() { return confidence; }
        public void setConfidence(double confidence) { this.confidence = confidence; }
    }

    public static class Forecast {
        private String metric;
        private List<ForecastPoint> points;
        private double confidence;
        private String method;

        public String getMetric() { return metric; }
        public void setMetric(String metric) { this.metric = metric; }
        public List<ForecastPoint> getPoints() { return points; }
        public void setPoints(List<ForecastPoint> points) { this.points = points; }
        public double getConfidence() { return confidence; }
        public void setConfidence(double confidence) { this.confidence = confidence; }
        public String getMethod() { return method; }
        public void setMethod(String method) { this.method = method; }
    }

    public static class ForecastPoint {
        private int day;
        private double predictedValue;
        private double lowerBound;
        private double upperBound;

        public int getDay() { return day; }
        public void setDay(int day) { this.day = day; }
        public double getPredictedValue() { return predictedValue; }
        public void setPredictedValue(double predictedValue) { this.predictedValue = predictedValue; }
        public double getLowerBound() { return lowerBound; }
        public void setLowerBound(double lowerBound) { this.lowerBound = lowerBound; }
        public double getUpperBound() { return upperBound; }
        public void setUpperBound(double upperBound) { this.upperBound = upperBound; }
    }

    public static class Pattern {
        private String patternType;
        private String description;
        private double confidence;
        private String implication;

        public String getPatternType() { return patternType; }
        public void setPatternType(String patternType) { this.patternType = patternType; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public double getConfidence() { return confidence; }
        public void setConfidence(double confidence) { this.confidence = confidence; }
        public String getImplication() { return implication; }
        public void setImplication(String implication) { this.implication = implication; }
    }

    public static class PeakPrediction {
        private String timeWindow;
        private double predictedLoad;
        private double confidence;
        private List<String> recommendations;

        public String getTimeWindow() { return timeWindow; }
        public void setTimeWindow(String timeWindow) { this.timeWindow = timeWindow; }
        public double getPredictedLoad() { return predictedLoad; }
        public void setPredictedLoad(double predictedLoad) { this.predictedLoad = predictedLoad; }
        public double getConfidence() { return confidence; }
        public void setConfidence(double confidence) { this.confidence = confidence; }
        public List<String> getRecommendations() { return recommendations; }
        public void setRecommendations(List<String> recommendations) { this.recommendations = recommendations; }
    }

    // Getters and setters
    public List<Trend> getTrends() { return trends; }
    public void setTrends(List<Trend> trends) { this.trends = trends; }
    public List<Forecast> getForecasts() { return forecasts; }
    public void setForecasts(List<Forecast> forecasts) { this.forecasts = forecasts; }
    public List<Pattern> getPatterns() { return patterns; }
    public void setPatterns(List<Pattern> patterns) { this.patterns = patterns; }
    public List<PeakPrediction> getPeakPredictions() { return peakPredictions; }
    public void setPeakPredictions(List<PeakPrediction> peakPredictions) { this.peakPredictions = peakPredictions; }
    public List<String> getStaffingRecommendations() { return staffingRecommendations; }
    public void setStaffingRecommendations(List<String> staffingRecommendations) { this.staffingRecommendations = staffingRecommendations; }
    public String getInsights() { return insights; }
    public void setInsights(String insights) { this.insights = insights; }
    public long getGeneratedAt() { return generatedAt; }
    public void setGeneratedAt(long generatedAt) { this.generatedAt = generatedAt; }
}