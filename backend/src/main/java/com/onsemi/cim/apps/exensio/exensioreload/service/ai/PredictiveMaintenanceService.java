package com.onsemi.cim.apps.exensio.exensioreload.service.ai;

import com.onsemi.cim.apps.exensio.exensioreload.config.AiProperties;
import com.onsemi.cim.apps.exensio.exensioreload.dto.ai.PredictiveMaintenanceRequest;
import com.onsemi.cim.apps.exensio.exensioreload.dto.ai.PredictiveMaintenanceResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Service for predictive maintenance of senders and systems.
 */
@Service
public class PredictiveMaintenanceService {

    private static final Logger log = LoggerFactory.getLogger(PredictiveMaintenanceService.class);

    private final AiGatewayService gatewayService;
    private final AiProperties aiProperties;

    // Thresholds for maintenance prediction
    private static final double ERROR_RATE_THRESHOLD = 0.05;
    private static final double RESPONSE_TIME_THRESHOLD = 2.0; // seconds
    private static final double FAILURE_PROBABILITY_HIGH = 0.7;

    public PredictiveMaintenanceService(AiGatewayService gatewayService, AiProperties aiProperties) {
        this.gatewayService = gatewayService;
        this.aiProperties = aiProperties;
    }

    public boolean isAvailable() {
        return aiProperties.isConfigured();
    }

    /**
     * Analyze and predict maintenance needs.
     */
    public PredictiveMaintenanceResponse analyze(PredictiveMaintenanceRequest request) {
        PredictiveMaintenanceResponse response = new PredictiveMaintenanceResponse();

        try {
            // Gather component metrics
            List<PredictiveMaintenanceResponse.ComponentHealth> components = analyzeComponents(request);
            response.setComponents(components);

            // Identify components needing attention
            List<PredictiveMaintenanceResponse.MaintenanceAlert> alerts = generateMaintenanceAlerts(components);
            response.setMaintenanceAlerts(alerts);

            // Calculate overall health
            response.setOverallHealthScore(calculateOverallHealth(components));
            response.setMaintenanceDueCount(countMaintenanceDue(components));
            response.setCriticalPredictions(countCriticalPredictions(alerts));

            // Generate recommendations
            response.setRecommendations(generateRecommendations(alerts, components));

            // Estimate costs/savings
            response.setEstimatedDowntimePrevention(estimateDowntimePrevention(components));
            response.setCostSavingsEstimate(estimateCostSavings(alerts));

            response.setAnalysisTimestamp(System.currentTimeMillis());

            // AI-powered insights
            if (aiProperties.isConfigured()) {
                response.setInsights(generateAIInsights(components, alerts));
            }

        } catch (Exception e) {
            log.error("Predictive maintenance analysis failed", e);
        }

        return response;
    }

    /**
     * Analyze individual components.
     */
    private List<PredictiveMaintenanceResponse.ComponentHealth> analyzeComponents(PredictiveMaintenanceRequest request) {
        List<PredictiveMaintenanceResponse.ComponentHealth> components = new ArrayList<>();

        // Simulated component data - in production would query SENDER_QUEUE, SENDER_STAGE, etc.
        String[] senderIds = {"SENDER_A", "SENDER_B", "SENDER_C", "SENDER_D"};

        for (String senderId : senderIds) {
            PredictiveMaintenanceResponse.ComponentHealth component = new PredictiveMaintenanceResponse.ComponentHealth();
            component.setComponentId(senderId);
            component.setComponentType("SENDER");

            // Simulate metrics with some variation
            double errorRate = Math.random() * 0.15;
            double avgResponseTime = 0.5 + Math.random() * 3;
            double uptime = 95 + Math.random() * 5;

            component.setErrorRate(errorRate);
            component.setAverageResponseTime(avgResponseTime);
            component.setUptimePercentage(uptime);
            component.setLastMaintenance(new Date().toString());
            component.setDaysSinceMaintenance((int)(Math.random() * 30));

            // Calculate health score
            double healthScore = calculateComponentHealthScore(errorRate, avgResponseTime, uptime);
            component.setHealthScore(healthScore);

            // Determine status
            component.setStatus(determineStatus(healthScore, errorRate, avgResponseTime));

            // Predict failure probability
            component.setFailureProbability(calculateFailureProbability(errorRate, avgResponseTime));
            component.setPredictedFailureTime(estimateFailureTime(component.getFailureProbability()));

            // Generate recommendations
            component.setMaintenanceRecommendations(List.of(
                healthScore < 0.7 ? "Schedule maintenance within 48 hours" : "No immediate action required",
                errorRate > ERROR_RATE_THRESHOLD ? "Investigate error rate increase" : "Error rate normal",
                avgResponseTime > RESPONSE_TIME_THRESHOLD ? "Check network latency" : "Response time acceptable"
            ));

            components.add(component);
        }

        // Add Exensio API component
        PredictiveMaintenanceResponse.ComponentHealth exensio = new PredictiveMaintenanceResponse.ComponentHealth();
        exensio.setComponentId("EXENSIO_API");
        exensio.setComponentType("API");
        exensio.setErrorRate(0.02);
        exensio.setAverageResponseTime(1.2);
        exensio.setUptimePercentage(99.5);
        exensio.setHealthScore(0.92);
        exensio.setStatus("HEALTHY");
        exensio.setFailureProbability(0.1);
        exensio.setLastMaintenance("2024-05-15");
        exensio.setDaysSinceMaintenance(18);
        exensio.setMaintenanceRecommendations(List.of("API healthy - no action required"));
        exensio.setPredictedFailureTime("Unknown");
        components.add(exensio);

        // Add Database component
        PredictiveMaintenanceResponse.ComponentHealth db = new PredictiveMaintenanceResponse.ComponentHealth();
        db.setComponentId("ORACLE_DB");
        db.setComponentType("DATABASE");
        db.setErrorRate(0.01);
        db.setAverageResponseTime(0.3);
        db.setUptimePercentage(99.9);
        db.setHealthScore(0.96);
        db.setStatus("HEALTHY");
        db.setFailureProbability(0.05);
        db.setLastMaintenance("2024-05-20");
        db.setDaysSinceMaintenance(13);
        db.setMaintenanceRecommendations(List.of("Database performing optimally"));
        db.setPredictedFailureTime("Unknown");
        components.add(db);

        return components;
    }

    private double calculateComponentHealthScore(double errorRate, double responseTime, double uptime) {
        double score = 1.0;

        // Deduct for high error rate
        if (errorRate > ERROR_RATE_THRESHOLD) {
            score -= (errorRate - ERROR_RATE_THRESHOLD) * 2;
        }

        // Deduct for slow response
        if (responseTime > RESPONSE_TIME_THRESHOLD) {
            score -= (responseTime - RESPONSE_TIME_THRESHOLD) * 0.1;
        }

        // Deduct for low uptime
        if (uptime < 99.0) {
            score -= (99.0 - uptime) * 0.1;
        }

        return Math.max(0.0, Math.min(1.0, score));
    }

    private String determineStatus(double healthScore, double errorRate, double responseTime) {
        if (healthScore < 0.5 || errorRate > 0.1) return "CRITICAL";
        if (healthScore < 0.7 || errorRate > ERROR_RATE_THRESHOLD) return "WARNING";
        if (responseTime > RESPONSE_TIME_THRESHOLD * 1.5) return "CAUTION";
        return "HEALTHY";
    }

    private double calculateFailureProbability(double errorRate, double responseTime) {
        double probability = errorRate * 2 + (responseTime / 10.0);
        return Math.min(0.95, Math.max(0.0, probability));
    }

    private String estimateFailureTime(double probability) {
        if (probability < 0.2) return "Not expected soon";
        if (probability < 0.5) return "Within 2-4 weeks";
        if (probability < 0.7) return "Within 1-2 weeks";
        return "Within 24-48 hours";
    }

    private List<PredictiveMaintenanceResponse.MaintenanceAlert> generateMaintenanceAlerts(
            List<PredictiveMaintenanceResponse.ComponentHealth> components) {
        List<PredictiveMaintenanceResponse.MaintenanceAlert> alerts = new ArrayList<>();

        for (PredictiveMaintenanceResponse.ComponentHealth component : components) {
            if ("WARNING".equals(component.getStatus()) || "CRITICAL".equals(component.getStatus())) {
                PredictiveMaintenanceResponse.MaintenanceAlert alert = new PredictiveMaintenanceResponse.MaintenanceAlert();
                alert.setAlertId("MAINT-" + component.getComponentId() + "-" + System.currentTimeMillis());
                alert.setComponentId(component.getComponentId());
                alert.setComponentType(component.getComponentType());
                alert.setAlertType(determineAlertType(component));
                alert.setSeverity(component.getStatus());
                alert.setDescription(String.format("%s showing signs of degradation. Error rate: %.1f%%, Health: %.0f%%",
                    component.getComponentId(), component.getErrorRate() * 100, component.getHealthScore() * 100));
                alert.setRecommendedAction(getRecommendedAction(component));
                alert.setEstimatedDowntime(savedDowntime(component));
                alert.setCreatedAt(new Date().toString());
                alerts.add(alert);
            }
        }

        return alerts;
    }

    private String determineAlertType(PredictiveMaintenanceResponse.ComponentHealth component) {
        if (component.getErrorRate() > ERROR_RATE_THRESHOLD * 2) return "HIGH_ERROR_RATE";
        if (component.getAverageResponseTime() > RESPONSE_TIME_THRESHOLD * 2) return "SLOW_RESPONSE";
        if (component.getFailureProbability() > FAILURE_PROBABILITY_HIGH) return "HIGH_FAILURE_RISK";
        return "PERFORMANCE_DEGRADATION";
    }

    private String getRecommendedAction(PredictiveMaintenanceResponse.ComponentHealth component) {
        return switch (component.getStatus()) {
            case "CRITICAL" -> "Schedule immediate maintenance. Consider failover to backup.";
            case "WARNING" -> "Plan maintenance within 48 hours. Increase monitoring frequency.";
            case "CAUTION" -> "Monitor for 24-48 hours. Schedule routine maintenance.";
            default -> "Continue normal monitoring.";
        };
    }

    private int savedDowntime(PredictiveMaintenanceResponse.ComponentHealth component) {
        // Estimate hours of downtime that can be prevented
        return (int)(component.getFailureProbability() * 8);
    }

    private double calculateOverallHealth(List<PredictiveMaintenanceResponse.ComponentHealth> components) {
        return components.stream()
            .mapToDouble(PredictiveMaintenanceResponse.ComponentHealth::getHealthScore)
            .average()
            .orElse(0.0);
    }

    private int countMaintenanceDue(List<PredictiveMaintenanceResponse.ComponentHealth> components) {
        return (int) components.stream()
            .filter(c -> c.getHealthScore() < 0.8)
            .count();
    }

    private int countCriticalPredictions(List<PredictiveMaintenanceResponse.MaintenanceAlert> alerts) {
        return (int) alerts.stream()
            .filter(a -> "CRITICAL".equals(a.getSeverity()))
            .count();
    }

    private List<String> generateRecommendations(
            List<PredictiveMaintenanceResponse.MaintenanceAlert> alerts,
            List<PredictiveMaintenanceResponse.ComponentHealth> components) {
        List<String> recommendations = new ArrayList<>();

        long criticalCount = alerts.stream().filter(a -> "CRITICAL".equals(a.getSeverity())).count();
        if (criticalCount > 0) {
            recommendations.add(String.format("URGENT: %d components require immediate attention", criticalCount));
        }

        components.stream()
            .filter(c -> "WARNING".equals(c.getStatus()))
            .forEach(c -> recommendations.add("Schedule maintenance for " + c.getComponentId() + " soon"));

        if (recommendations.isEmpty()) {
            recommendations.add("All systems operating within normal parameters");
        }

        return recommendations;
    }

    private int estimateDowntimePrevention(List<PredictiveMaintenanceResponse.ComponentHealth> components) {
        return components.stream()
            .filter(c -> c.getFailureProbability() > 0.3)
            .mapToInt(c -> (int)(c.getFailureProbability() * 10))
            .sum();
    }

    private double estimateCostSavings(List<PredictiveMaintenanceResponse.MaintenanceAlert> alerts) {
        return alerts.stream()
            .filter(a -> "CRITICAL".equals(a.getSeverity()) || "WARNING".equals(a.getSeverity()))
            .mapToDouble(a -> a.getEstimatedDowntime() * 500) // $500/hour estimate
            .sum();
    }

    private String generateAIInsights(
            List<PredictiveMaintenanceResponse.ComponentHealth> components,
            List<PredictiveMaintenanceResponse.MaintenanceAlert> alerts) {
        try {
            String prompt = String.format("""
                Analyze predictive maintenance data and provide insights:
                
                Components Analyzed: %d
                Maintenance Alerts: %d
                Critical Predictions: %d
                Overall Health: %.0f%%
                
                Components:
                %s
                
                Provide 2-3 actionable insights for the operations team.
                """,
                components.size(),
                alerts.size(),
                countCriticalPredictions(alerts),
                calculateOverallHealth(components) * 100,
                components.stream()
                    .map(c -> String.format("- %s (%s): %.0f%% health, %.0f%% failure risk",
                        c.getComponentId(), c.getStatus(), c.getHealthScore() * 100, c.getFailureProbability() * 100))
                    .reduce((a, b) -> a + "\n" + b)
                    .orElse("")
            );

            Map<String, Object> context = Map.of("task", "predictive_maintenance");
            return gatewayService.sendMessage(prompt, context);
        } catch (Exception e) {
            return "Analysis complete. Review individual component status for details.";
        }
    }
}