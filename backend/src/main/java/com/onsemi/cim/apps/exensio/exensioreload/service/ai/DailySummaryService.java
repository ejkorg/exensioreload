package com.onsemi.cim.apps.exensio.exensioreload.service.ai;

import com.onsemi.cim.apps.exensio.exensioreload.config.AiProperties;
import com.onsemi.cim.apps.exensio.exensioreload.dto.ai.DailySummaryRequest;
import com.onsemi.cim.apps.exensio.exensioreload.dto.ai.DailySummaryResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Service for generating daily summary reports.
 */
@Service
public class DailySummaryService {

    private static final Logger log = LoggerFactory.getLogger(DailySummaryService.class);

    private final AiGatewayService gatewayService;
    private final AiProperties aiProperties;

    public DailySummaryService(AiGatewayService gatewayService, AiProperties aiProperties) {
        this.gatewayService = gatewayService;
        this.aiProperties = aiProperties;
    }

    public boolean isAvailable() {
        return aiProperties.isConfigured();
    }

    /**
     * Generate daily summary report.
     */
    public DailySummaryResponse generateSummary(DailySummaryRequest request) {
        DailySummaryResponse response = new DailySummaryResponse();

        try {
            response.setDate(request.getDate() != null ? request.getDate() : new Date().toString());

            // Gather metrics
            Map<String, Object> metrics = gatherMetrics(request);
            
            response.setTotalSessions((Integer) metrics.getOrDefault("totalSessions", 0));
            response.setTotalRecords((Integer) metrics.getOrDefault("totalRecords", 0));
            response.setSuccessRate((Integer) metrics.getOrDefault("successRate", 0));
            response.setErrorRate((Integer) metrics.getOrDefault("errorRate", 100));
            
            // Status breakdown
            response.setStatusBreakdown((Map<String, Integer>) metrics.getOrDefault("statusBreakdown", new HashMap<>()));

            // Top issues
            response.setTopIssues((List<DailySummaryResponse.TopIssue>) metrics.getOrDefault("topIssues", new ArrayList<>()));

            // Trends
            response.setTrends((List<DailySummaryResponse.TrendItem>) metrics.getOrDefault("trends", new ArrayList<>()));

            // Highlights
            response.setHighlights(generateHighlights(metrics));

            // Recommendations
            response.setRecommendations(generateRecommendations(metrics));

            // Generate summary with AI
            response.setSummary(aiProperties.isConfigured() ? 
                generateAISummary(response) : generateBasicSummary(response));

            // Operator briefing
            response.setOperatorBriefing(generateOperatorBriefing(response));

        } catch (Exception e) {
            log.error("Daily summary generation failed", e);
            response.setSummary("Summary unavailable: " + e.getMessage());
        }

        return response;
    }

    /**
     * Gather metrics from database.
     */
    private Map<String, Object> gatherMetrics(DailySummaryRequest request) {
        Map<String, Object> metrics = new HashMap<>();

        // Simulated metrics - in production, query database
        metrics.put("totalSessions", 25);
        metrics.put("totalRecords", 1547);
        metrics.put("successRate", 94);
        metrics.put("errorRate", 6);

        // Status breakdown
        Map<String, Integer> statusBreakdown = new HashMap<>();
        statusBreakdown.put("COMPLETED", 1454);
        statusBreakdown.put("FAILED", 52);
        statusBreakdown.put("PROCESSING", 41);
        metrics.put("statusBreakdown", statusBreakdown);

        // Top issues
        List<DailySummaryResponse.TopIssue> topIssues = new ArrayList<>();
        
        DailySummaryResponse.TopIssue timeout = new DailySummaryResponse.TopIssue();
        timeout.setIssue("Connection timeouts");
        timeout.setCount(28);
        timeout.setTrend("Increasing");
        timeout.setImpact("High - affected 3 senders");
        topIssues.add(timeout);

        DailySummaryResponse.TopIssue auth = new DailySummaryResponse.TopIssue();
        auth.setIssue("Authentication failures");
        auth.setCount(15);
        auth.setTrend("Stable");
        auth.setImpact("Medium - 1 sender");
        topIssues.add(auth);

        DailySummaryResponse.TopIssue validation = new DailySummaryResponse.TopIssue();
        validation.setIssue("Data validation errors");
        validation.setCount(9);
        validation.setTrend("Decreasing");
        validation.setImpact("Low - isolated cases");
        topIssues.add(validation);

        metrics.put("topIssues", topIssues);

        // Trends
        List<DailySummaryResponse.TrendItem> trends = new ArrayList<>();
        
        DailySummaryResponse.TrendItem successTrend = new DailySummaryResponse.TrendItem();
        successTrend.setMetric("Success Rate");
        successTrend.setDirection("up");
        successTrend.setChange(2.5);
        successTrend.setDescription("Improved from 91.5% yesterday");
        trends.add(successTrend);

        DailySummaryResponse.TrendItem throughputTrend = new DailySummaryResponse.TrendItem();
        throughputTrend.setMetric("Processing Throughput");
        throughputTrend.setDirection("up");
        throughputTrend.setChange(15.0);
        throughputTrend.setDescription("Batch optimization showing results");
        trends.add(throughputTrend);

        metrics.put("trends", trends);

        return metrics;
    }

    /**
     * Generate highlights based on metrics.
     */
    private List<String> generateHighlights(Map<String, Object> metrics) {
        List<String> highlights = new ArrayList<>();

        int successRate = (Integer) metrics.getOrDefault("successRate", 0);
        if (successRate >= 95) {
            highlights.add("Excellent performance: " + successRate + "% success rate");
        } else if (successRate >= 90) {
            highlights.add("Good performance: Above 90% success rate");
        }

        int totalRecords = (Integer) metrics.getOrDefault("totalRecords", 0);
        if (totalRecords > 1000) {
            highlights.add("High volume day: Processed " + totalRecords + " records");
        }

        return highlights;
    }

    /**
     * Generate recommendations based on metrics.
     */
    private List<String> generateRecommendations(Map<String, Object> metrics) {
        List<String> recommendations = new ArrayList<>();

        int errorRate = (Integer) metrics.getOrDefault("errorRate", 0);
        if (errorRate > 5) {
            recommendations.add("Investigate timeout errors - check network connectivity");
        }

        List<DailySummaryResponse.TopIssue> topIssues = 
            (List<DailySummaryResponse.TopIssue>) metrics.getOrDefault("topIssues", new ArrayList<>());
        
        if (!topIssues.isEmpty()) {
            recommendations.add("Review " + topIssues.get(0).getIssue() + " - top issue of the day");
        }

        if (recommendations.isEmpty()) {
            recommendations.add("System operating normally - no immediate action required");
        }

        return recommendations;
    }

    /**
     * Generate summary using AI.
     */
    private String generateAISummary(DailySummaryResponse response) {
        try {
            String prompt = String.format("""
                Generate a brief executive summary for this daily report:
                
                - Total Sessions: %d
                - Total Records: %d
                - Success Rate: %d%%
                - Top Issues: %s
                - Trends: %s
                
                Keep it concise, 2-3 sentences max.
                """,
                response.getTotalSessions(),
                response.getTotalRecords(),
                response.getSuccessRate(),
                response.getTopIssues().stream()
                    .map(i -> i.getIssue() + "(" + i.getCount() + ")")
                    .reduce((a, b) -> a + ", " + b)
                    .orElse("None"),
                response.getTrends().stream()
                    .map(t -> t.getMetric() + " " + t.getDirection())
                    .reduce((a, b) -> a + ", " + b)
                    .orElse("Stable")
            );

            Map<String, Object> context = Map.of("task", "daily_summary");
            return gatewayService.sendMessage(prompt, context);
        } catch (Exception e) {
            log.warn("AI summary generation failed", e);
            return generateBasicSummary(response);
        }
    }

    /**
     * Generate basic summary without AI.
     */
    private String generateBasicSummary(DailySummaryResponse response) {
        return String.format("Daily Summary: %d sessions, %d records processed. " +
            "Success rate: %d%%. %d errors detected, top issue: %s.",
            response.getTotalSessions(),
            response.getTotalRecords(),
            response.getSuccessRate(),
            response.getErrorRate(),
            response.getTopIssues().isEmpty() ? "None" : response.getTopIssues().get(0).getIssue());
    }

    /**
     * Generate operator briefing.
     */
    private String generateOperatorBriefing(DailySummaryResponse response) {
        StringBuilder briefing = new StringBuilder();
        briefing.append("Good morning! Here's your daily briefing for ");
        briefing.append(response.getDate());
        briefing.append(".\n\n");

        briefing.append("Overview: ");
        briefing.append(response.getSummary());
        briefing.append("\n\n");

        if (!response.getTopIssues().isEmpty()) {
            briefing.append("Action Items:\n");
            response.getTopIssues().stream()
                .limit(3)
                .forEach(issue -> {
                    briefing.append("- ").append(issue.getIssue());
                    briefing.append(" (").append(issue.getCount()).append(" occurrences)\n");
                });
        }

        if (!response.getRecommendations().isEmpty()) {
            briefing.append("\nRecommendations:\n");
            response.getRecommendations().stream()
                .limit(3)
                .forEach(rec -> briefing.append("- ").append(rec).append("\n"));
        }

        return briefing.toString();
    }
}