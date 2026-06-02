package com.onsemi.cim.apps.exensio.exensioreload.dto.ai;

import java.util.List;
import java.util.Map;

/**
 * Response for cost analysis.
 */
public class CostAnalysisResponse {
    private double totalProcessingCost;
    private double totalErrorCost;
    private double totalRetryCost;
    private double totalLaborCost;
    private double totalEstimatedCost;
    private double costPerLot;
    private double costPerWafer;
    private Map<String, Object> costBreakdown;
    private List<CostTrend> costTrends;
    private List<String> majorCostDrivers;
    private List<SavingsOpportunity> savingsOpportunities;
    private String insights;
    private long analysisTimestamp;

    public static class CostTrend {
        private String day;
        private double amount;
        private String category;

        public String getDay() { return day; }
        public void setDay(String day) { this.day = day; }
        public double getAmount() { return amount; }
        public void setAmount(double amount) { this.amount = amount; }
        public String getCategory() { return category; }
        public void setCategory(String category) { this.category = category; }
    }

    public static class SavingsOpportunity {
        private String category;
        private String description;
        private double potentialSavings;
        private String effort;  // LOW, MEDIUM, HIGH
        private String recommendation;

        public String getCategory() { return category; }
        public void setCategory(String category) { this.category = category; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public double getPotentialSavings() { return potentialSavings; }
        public void setPotentialSavings(double potentialSavings) { this.potentialSavings = potentialSavings; }
        public String getEffort() { return effort; }
        public void setEffort(String effort) { this.effort = effort; }
        public String getRecommendation() { return recommendation; }
        public void setRecommendation(String recommendation) { this.recommendation = recommendation; }
    }

    // Getters and setters
    public double getTotalProcessingCost() { return totalProcessingCost; }
    public void setTotalProcessingCost(double totalProcessingCost) { this.totalProcessingCost = totalProcessingCost; }
    public double getTotalErrorCost() { return totalErrorCost; }
    public void setTotalErrorCost(double totalErrorCost) { this.totalErrorCost = totalErrorCost; }
    public double getTotalRetryCost() { return totalRetryCost; }
    public void setTotalRetryCost(double totalRetryCost) { this.totalRetryCost = totalRetryCost; }
    public double getTotalLaborCost() { return totalLaborCost; }
    public void setTotalLaborCost(double totalLaborCost) { this.totalLaborCost = totalLaborCost; }
    public double getTotalEstimatedCost() { return totalEstimatedCost; }
    public void setTotalEstimatedCost(double totalEstimatedCost) { this.totalEstimatedCost = totalEstimatedCost; }
    public double getCostPerLot() { return costPerLot; }
    public void setCostPerLot(double costPerLot) { this.costPerLot = costPerLot; }
    public double getCostPerWafer() { return costPerWafer; }
    public void setCostPerWafer(double costPerWafer) { this.costPerWafer = costPerWafer; }
    public Map<String, Object> getCostBreakdown() { return costBreakdown; }
    public void setCostBreakdown(Map<String, Object> costBreakdown) { this.costBreakdown = costBreakdown; }
    public List<CostTrend> getCostTrends() { return costTrends; }
    public void setCostTrends(List<CostTrend> costTrends) { this.costTrends = costTrends; }
    public List<String> getMajorCostDrivers() { return majorCostDrivers; }
    public void setMajorCostDrivers(List<String> majorCostDrivers) { this.majorCostDrivers = majorCostDrivers; }
    public List<SavingsOpportunity> getSavingsOpportunities() { return savingsOpportunities; }
    public void setSavingsOpportunities(List<SavingsOpportunity> savingsOpportunities) { this.savingsOpportunities = savingsOpportunities; }
    public String getInsights() { return insights; }
    public void setInsights(String insights) { this.insights = insights; }
    public long getAnalysisTimestamp() { return analysisTimestamp; }
    public void setAnalysisTimestamp(long analysisTimestamp) { this.analysisTimestamp = analysisTimestamp; }
}