package com.onsemi.cim.apps.exensio.exensioreload.service.ai;

import com.onsemi.cim.apps.exensio.exensioreload.config.AiProperties;
import com.onsemi.cim.apps.exensio.exensioreload.dto.ai.AnomalyDetectionRequest;
import com.onsemi.cim.apps.exensio.exensioreload.dto.ai.AnomalyDetectionResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Service for detecting anomalies in staging data patterns.
 */
@Service
public class AnomalyDetectionService {

    private static final Logger log = LoggerFactory.getLogger(AnomalyDetectionService.class);

    private final AiGatewayService gatewayService;
    private final AiProperties aiProperties;

    // Baseline thresholds (in production, these would be calculated from historical data)
    private static final Map<String, Double> BASELINE_THRESHOLDS = Map.of(
        "errorRate", 0.05,
        "processingTime", 30.0,
        "queueDepth", 100.0,
        "failureRate", 0.10
    );

    public AnomalyDetectionService(AiGatewayService gatewayService, AiProperties aiProperties) {
        this.gatewayService = gatewayService;
        this.aiProperties = aiProperties;
    }

    public boolean isAvailable() {
        return aiProperties.isConfigured();
    }

    /**
     * Detect anomalies in the given context.
     */
    public AnomalyDetectionResponse detect(AnomalyDetectionRequest request) {
        AnomalyDetectionResponse response = new AnomalyDetectionResponse();
        List<AnomalyDetectionResponse.Anomaly> anomalies = new ArrayList<>();

        try {
            // Calculate baseline metrics
            Map<String, Double> baseline = calculateBaseline(request);
            response.setBaselineMetrics(baseline);

            // Analyze current metrics
            Map<String, Double> currentMetrics = getCurrentMetrics(request);

            // Detect anomalies
            for (Map.Entry<String, Double> entry : currentMetrics.entrySet()) {
                String metric = entry.getKey();
                Double currentValue = entry.getValue();
                Double baselineValue = baseline.getOrDefault(metric, 0.0);
                
                if (baselineValue > 0) {
                    double deviation = (currentValue - baselineValue) / baselineValue;
                    
                    if (Math.abs(deviation) > 0.5) {  // 50% deviation threshold
                        AnomalyDetectionResponse.Anomaly anomaly = new AnomalyDetectionResponse.Anomaly();
                        anomaly.setType(metric);
                        anomaly.setDescription(String.format("%s deviates by %.1f%% from baseline", metric, deviation * 100));
                        anomaly.setSeverity(determineSeverity(deviation));
                        anomaly.setAffectedEntity(request.getSite() != null ? request.getSite() : "SYSTEM");
                        anomaly.setDeviationFromBaseline(deviation);
                        anomaly.setTimestamp(new Date().toString());
                        anomaly.setProbableCause(inferCause(metric, deviation));
                        
                        anomalies.add(anomaly);
                    }
                }
            }

            response.setAnomalies(anomalies);
            response.setTotalAnomalies(anomalies.size());
            response.setAnomaliesDetected(!anomalies.isEmpty());
            response.setOverallRiskLevel(determineOverallRisk(anomalies));
            response.setRecommendations(generateRecommendations(anomalies));

        } catch (Exception e) {
            log.error("Anomaly detection failed", e);
            response.setAnomaliesDetected(false);
            response.setTotalAnomalies(0);
        }

        return response;
    }

    /**
     * Calculate baseline metrics from historical data.
     */
    private Map<String, Double> calculateBaseline(AnomalyDetectionRequest request) {
        // In production, query historical data for baseline
        // For now, return default baselines
        Map<String, Double> baseline = new HashMap<>();
        baseline.put("errorRate", 0.03);
        baseline.put("processingTime", 25.0);
        baseline.put("successRate", 0.97);
        baseline.put("failureRate", 0.03);
        baseline.put("avgQueueDepth", 50.0);
        return baseline;
    }

    /**
     * Get current metrics for analysis.
     */
    private Map<String, Double> getCurrentMetrics(AnomalyDetectionRequest request) {
        // In production, query current staging data
        // For now, simulate some metrics
        Map<String, Double> metrics = new HashMap<>();
        
        // Simulate some variance
        metrics.put("errorRate", 0.07);  // Elevated
        metrics.put("processingTime", 35.0);  // Elevated
        metrics.put("successRate", 0.93);  // Lower
        metrics.put("failureRate", 0.07);  // Higher
        metrics.put("avgQueueDepth", 80.0);  // Higher

        return metrics;
    }

    /**
     * Determine severity of anomaly.
     */
    private String determineSeverity(double deviation) {
        if (deviation > 1.0) return "CRITICAL";
        if (deviation > 0.5) return "HIGH";
        if (deviation > 0.25) return "MEDIUM";
        return "LOW";
    }

    /**
     * Infer probable cause of anomaly.
     */
    private String inferCause(String metric, double deviation) {
        return switch (metric) {
            case "errorRate" -> deviation > 0 ? 
                "Possible network issues or upstream system problems" :
                "System returning to normal operation";
            case "processingTime" -> deviation > 0 ?
                "High load or resource contention" :
                "System optimized or reduced load";
            case "failureRate" -> deviation > 0 ?
                "Configuration changes or external system issues" :
                "Recent fixes taking effect";
            case "avgQueueDepth" -> deviation > 0 ?
                "Production rate exceeds processing capacity" :
                "Backlog being cleared";
            default -> "Requires further investigation";
        };
    }

    /**
     * Determine overall risk level.
     */
    private String determineOverallRisk(List<AnomalyDetectionResponse.Anomaly> anomalies) {
        if (anomalies.isEmpty()) return "LOW";
        
        long critical = anomalies.stream()
            .filter(a -> "CRITICAL".equals(a.getSeverity()))
            .count();
        
        if (critical > 0) return "CRITICAL";
        
        long high = anomalies.stream()
            .filter(a -> "HIGH".equals(a.getSeverity()))
            .count();
        
        if (high > 1) return "HIGH";
        
        return anomalies.stream()
            .anyMatch(a -> "HIGH".equals(a.getSeverity())) ? "MEDIUM" : "LOW";
    }

    /**
     * Generate recommendations based on detected anomalies.
     */
    private List<String> generateRecommendations(List<AnomalyDetectionResponse.Anomaly> anomalies) {
        List<String> recommendations = new ArrayList<>();

        for (AnomalyDetectionResponse.Anomaly anomaly : anomalies) {
            switch (anomaly.getType()) {
                case "errorRate":
                    recommendations.add("Review recent deployment changes and check external system status");
                    break;
                case "processingTime":
                    recommendations.add("Consider scaling resources or optimizing batch processing");
                    break;
                case "failureRate":
                    recommendations.add("Run root cause analysis on recent failures");
                    break;
                case "avgQueueDepth":
                    recommendations.add("Review sender throughput and processing capacity");
                    break;
                default:
                    recommendations.add("Investigate " + anomaly.getType() + " metrics");
            }
        }

        return recommendations;
    }
}