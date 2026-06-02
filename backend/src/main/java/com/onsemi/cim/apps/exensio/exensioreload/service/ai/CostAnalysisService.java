package com.onsemi.cim.apps.exensio.exensioreload.service.ai;

import com.onsemi.cim.apps.exensio.exensioreload.config.AiProperties;
import com.onsemi.cim.apps.exensio.exensioreload.dto.ai.CostAnalysisRequest;
import com.onsemi.cim.apps.exensio.exensioreload.dto.ai.CostAnalysisResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Service for cost analysis of operations.
 */
@Service
public class CostAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(CostAnalysisService.class);

    private final AiGatewayService gatewayService;
    private final AiProperties aiProperties;

    // Cost estimates (in production, these would be configurable)
    private static final double COST_PER_RECORD = 0.15;
    private static final double COST_PER_ERROR = 5.00;
    private static final double COST_PER_HOUR = 75.00;
    private static final double COST_PER_RETRY = 0.50;

    public CostAnalysisService(AiGatewayService gatewayService, AiProperties aiProperties) {
        this.gatewayService = gatewayService;
        this.aiProperties = aiProperties;
    }

    public boolean isAvailable() {
        return aiProperties.isConfigured();
    }

    /**
     * Analyze operation costs.
     */
    public CostAnalysisResponse analyze(CostAnalysisRequest request) {
        CostAnalysisResponse response = new CostAnalysisResponse();

        try {
            // Gather cost data
            Map<String, Object> costData = gatherCostData(request);

            // Calculate total costs
            response.setTotalProcessingCost((Double) costData.get("processingCost"));
            response.setTotalErrorCost((Double) costData.get("errorCost"));
            response.setTotalRetryCost((Double) costData.get("retryCost"));
            response.setTotalLaborCost((Double) costData.get("laborCost"));
            response.setTotalEstimatedCost((Double) costData.get("totalCost"));

            // Cost breakdown by category
            response.setCostBreakdown(calculateCostBreakdown(costData));

            // Cost trends
            response.setCostTrends(analyzeCostTrends(costData));

            // Cost per lot/wafer analysis
            response.setCostPerLot((Double) costData.get("costPerLot"));
            response.setCostPerWafer((Double) costData.get("costPerWafer"));

            // Identify cost drivers
            response.setMajorCostDrivers(identifyCostDrivers(costData));

            // Savings opportunities
            response.setSavingsOpportunities(findSavingsOpportunities(costData));

            // AI insights
            if (aiProperties.isConfigured()) {
                response.setInsights(generateAIInsights(response));
            }

            response.setAnalysisTimestamp(System.currentTimeMillis());

        } catch (Exception e) {
            log.error("Cost analysis failed", e);
        }

        return response;
    }

    private Map<String, Object> gatherCostData(CostAnalysisRequest request) {
        Map<String, Object> data = new HashMap<>();

        Random random = new Random(42);

        // Simulated operational data
        int recordsProcessed = 1500 + random.nextInt(500);
        int errors = 25 + random.nextInt(20);
        int retries = 40 + random.nextInt(30);
        double processingHours = 8.5 + random.nextDouble() * 2;

        double processingCost = recordsProcessed * COST_PER_RECORD;
        double errorCost = errors * COST_PER_ERROR;
        double retryCost = retries * COST_PER_RETRY;
        double laborCost = processingHours * COST_PER_HOUR;
        double totalCost = processingCost + errorCost + retryCost + laborCost;

        data.put("recordsProcessed", recordsProcessed);
        data.put("errors", errors);
        data.put("retries", retries);
        data.put("processingHours", processingHours);
        data.put("processingCost", processingCost);
        data.put("errorCost", errorCost);
        data.put("retryCost", retryCost);
        data.put("laborCost", laborCost);
        data.put("totalCost", totalCost);
        data.put("costPerLot", totalCost / (recordsProcessed / 50));
        data.put("costPerWafer", totalCost / recordsProcessed);

        return data;
    }

    private Map<String, Object> calculateCostBreakdown(Map<String, Object> data) {
        Map<String, Object> breakdown = new HashMap<>();
        double total = (Double) data.get("totalCost");

        breakdown.put("processing", Map.of(
            "amount", data.get("processingCost"),
            "percentage", ((Double) data.get("processingCost") / total) * 100,
            "label", "Record Processing"
        ));

        breakdown.put("errors", Map.of(
            "amount", data.get("errorCost"),
            "percentage", ((Double) data.get("errorCost") / total) * 100,
            "label", "Error Handling"
        ));

        breakdown.put("retries", Map.of(
            "amount", data.get("retryCost"),
            "percentage", ((Double) data.get("retryCost") / total) * 100,
            "label", "Retry Operations"
        ));

        breakdown.put("labor", Map.of(
            "amount", data.get("laborCost"),
            "percentage", ((Double) data.get("laborCost") / total) * 100,
            "label", "Labor/Overtime"
        ));

        return breakdown;
    }

    private List<CostAnalysisResponse.CostTrend> analyzeCostTrends(Map<String, Object> data) {
        List<CostAnalysisResponse.CostTrend> trends = new ArrayList<>();

        Random random = new Random(42);

        // Simulate trend data
        double[] costs = {850, 920, 780, 950, 890, 1020, 880};
        for (int i = 0; i < costs.length; i++) {
            CostAnalysisResponse.CostTrend trend = new CostAnalysisResponse.CostTrend();
            trend.setDay("Day " + (i + 1));
            trend.setAmount(costs[i] + random.nextDouble() * 50);
            trend.setCategory("Overall");
            trends.add(trend);
        }

        return trends;
    }

    private List<String> identifyCostDrivers(Map<String, Object> data) {
        List<String> drivers = new ArrayList<>();

        int errors = (Integer) data.get("errors");
        int retries = (Integer) data.get("retries");

        if (errors > 30) {
            drivers.add("High error rate is the primary cost driver (" + errors + " errors)");
        }
        if (retries > 35) {
            drivers.add("Excessive retries contributing to operational costs");
        }

        double laborCost = (Double) data.get("laborCost");
        double totalCost = (Double) data.get("totalCost");
        if (laborCost / totalCost > 0.5) {
            drivers.add("Labor costs exceed 50% of total operational cost");
        }

        drivers.add("Record processing volume drives baseline costs");
        drivers.add("Network reliability affects retry frequency");

        return drivers;
    }

    private List<CostAnalysisResponse.SavingsOpportunity> findSavingsOpportunities(Map<String, Object> data) {
        List<CostAnalysisResponse.SavingsOpportunity> opportunities = new ArrayList<>();

        CostAnalysisResponse.SavingsOpportunity opp1 = new CostAnalysisResponse.SavingsOpportunity();
        opp1.setCategory("Error Reduction");
        opp1.setDescription("Reducing error rate by 20% would save approximately $" +
            String.format("%.2f", (Integer) data.get("errors") * COST_PER_ERROR * 0.2) + " per day");
        opp1.setPotentialSavings((Integer) data.get("errors") * COST_PER_ERROR * 0.2);
        opp1.setEffort("MEDIUM");
        opp1.setRecommendation("Implement pre-validation checks before processing");
        opportunities.add(opp1);

        CostAnalysisResponse.SavingsOpportunity opp2 = new CostAnalysisResponse.SavingsOpportunity();
        opp2.setCategory("Retry Optimization");
        opp2.setDescription("Optimizing retry logic could reduce retry costs by 30%");
        opp2.setPotentialSavings((Integer) data.get("retries") * COST_PER_RETRY * 0.3);
        opp2.setEffort("LOW");
        opp2.setRecommendation("Implement exponential backoff and smarter retry logic");
        opportunities.add(opp2);

        CostAnalysisResponse.SavingsOpportunity opp3 = new CostAnalysisResponse.SavingsOpportunity();
        opp3.setCategory("Batch Optimization");
        opp3.setDescription("Larger batch sizes reduce per-record overhead");
        opp3.setPotentialSavings((Double) data.get("processingCost") * 0.15);
        opp3.setEffort("MEDIUM");
        opp3.setRecommendation("Review batch sizing configuration");
        opportunities.add(opp3);

        return opportunities;
    }

    private String generateAIInsights(CostAnalysisResponse response) {
        try {
            String prompt = String.format("""
                Analyze cost data and provide actionable insights:
                
                Total Cost: $%.2f
                Processing: $%.2f
                Errors: $%.2f
                Retries: $%.2f
                Labor: $%.2f
                
                Identify the top 2-3 cost reduction opportunities.
                """,
                response.getTotalEstimatedCost(),
                response.getTotalProcessingCost(),
                response.getTotalErrorCost(),
                response.getTotalRetryCost(),
                response.getTotalLaborCost()
            );

            Map<String, Object> context = Map.of("task", "cost_analysis");
            return gatewayService.sendMessage(prompt, context);
        } catch (Exception e) {
            return "Cost analysis complete. Review savings opportunities for optimization.";
        }
    }
}