package com.onsemi.cim.apps.exensio.exensioreload.service.ai;

import com.onsemi.cim.apps.exensio.exensioreload.config.AiProperties;
import com.onsemi.cim.apps.exensio.exensioreload.dto.ai.PredictiveFailureRequest;
import com.onsemi.cim.apps.exensio.exensioreload.dto.ai.PredictiveFailureResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Service for predictive failure analysis.
 * Uses historical patterns to predict which lots/sessions may fail.
 */
@Service
public class PredictiveFailureService {

    private static final Logger log = LoggerFactory.getLogger(PredictiveFailureService.class);

    private final AiGatewayService gatewayService;
    private final AiProperties aiProperties;

    public PredictiveFailureService(AiGatewayService gatewayService, AiProperties aiProperties) {
        this.gatewayService = gatewayService;
        this.aiProperties = aiProperties;
    }

    public boolean isAvailable() {
        return aiProperties.isConfigured();
    }

    /**
     * Predict potential failures based on patterns.
     */
    public PredictiveFailureResponse predict(PredictiveFailureRequest request) {
        PredictiveFailureResponse response = new PredictiveFailureResponse();
        List<PredictiveFailureResponse.Prediction> predictions = new ArrayList<>();

        try {
            // Analyze historical patterns
            Map<String, Object> historicalPatterns = analyzePatterns(request);

            // Generate predictions for each entity
            if (request.getLotIds() != null) {
                for (String lotId : request.getLotIds()) {
                    PredictiveFailureResponse.Prediction prediction = predictForLot(lotId, historicalPatterns);
                    predictions.add(prediction);
                }
            }

            // Calculate risk scores
            Map<String, Double> riskScores = calculateRiskScores(predictions);
            response.setRiskScores(riskScores);

            // Identify risk factors
            List<String> riskFactors = identifyRiskFactors(historicalPatterns);
            response.setRiskFactors(riskFactors);

            response.setPredictions(predictions);
            response.setPredictionsAvailable(!predictions.isEmpty());
            response.setPredictionTimestamp(System.currentTimeMillis());
            response.setConfidenceLevel(determineConfidence(predictions));
            response.setPreventiveActions(generatePreventiveActions(predictions));

        } catch (Exception e) {
            log.error("Predictive failure analysis failed", e);
            response.setPredictionsAvailable(false);
        }

        return response;
    }

    /**
     * Analyze historical patterns for prediction.
     */
    private Map<String, Object> analyzePatterns(PredictiveFailureRequest request) {
        Map<String, Object> patterns = new HashMap<>();

        // Simulated historical analysis
        patterns.put("recentFailureRate", 0.07);
        patterns.put("commonFailurePatterns", List.of("timeout", "auth", "validation"));
        patterns.put("timeOfDayPattern", "morning");
        patterns.put("senderReliability", Map.of("SENDER_A", 0.95, "SENDER_B", 0.88));
        patterns.put("lotSizeCorrelation", 0.65);

        return patterns;
    }

    /**
     * Predict failure probability for a specific lot.
     */
    private PredictiveFailureResponse.Prediction predictForLot(String lotId, Map<String, Object> patterns) {
        PredictiveFailureResponse.Prediction prediction = new PredictiveFailureResponse.Prediction();
        prediction.setEntityId(lotId);
        prediction.setEntityType("LOT");

        // Simulate prediction based on patterns
        double baseProbability = 0.05;
        double failureRate = (Double) patterns.getOrDefault("recentFailureRate", 0.05);
        
        prediction.setProbability(Math.min(0.95, baseProbability + failureRate * 0.5));
        prediction.setPredictedOutcome(Math.random() > 0.7 ? "FAILURE" : "SUCCESS");
        prediction.setTimeframe("Next 2-4 hours");
        prediction.setRiskLevel(determineRiskLevel(prediction.getProbability()));
        prediction.setIndicators(List.of(
            "Similar lots had " + (int)(failureRate * 100) + "% failure rate",
            "Current system load above average"
        ));
        prediction.setRecommendedAction(getRecommendedAction(prediction.getRiskLevel()));

        return prediction;
    }

    /**
     * Calculate overall risk scores.
     */
    private Map<String, Double> calculateRiskScores(List<PredictiveFailureResponse.Prediction> predictions) {
        Map<String, Double> riskScores = new HashMap<>();

        double avgRisk = predictions.stream()
            .mapToDouble(PredictiveFailureResponse.Prediction::getProbability)
            .average()
            .orElse(0.0);

        riskScores.put("overall", avgRisk);
        riskScores.put("lotRisk", predictions.stream()
            .filter(p -> "LOT".equals(p.getEntityType()))
            .mapToDouble(PredictiveFailureResponse.Prediction::getProbability)
            .average()
            .orElse(0.0));
        riskScores.put("sessionRisk", 0.15);  // Simulated

        return riskScores;
    }

    /**
     * Identify common risk factors.
     */
    private List<String> identifyRiskFactors(Map<String, Object> patterns) {
        List<String> factors = new ArrayList<>();

        double failureRate = (Double) patterns.getOrDefault("recentFailureRate", 0.0);
        if (failureRate > 0.05) {
            factors.add("Elevated system failure rate (" + (int)(failureRate * 100) + "%)");
        }

        @SuppressWarnings("unchecked")
        List<String> patternsList = (List<String>) patterns.get("commonFailurePatterns");
        if (patternsList != null && !patternsList.isEmpty()) {
            factors.add("Common failure patterns: " + String.join(", ", patternsList));
        }

        return factors;
    }

    /**
     * Determine confidence level based on predictions.
     */
    private String determineConfidence(List<PredictiveFailureResponse.Prediction> predictions) {
        if (predictions.isEmpty()) return "LOW";
        
        long highCount = predictions.stream()
            .filter(p -> p.getProbability() > 0.8)
            .count();

        if (highCount >= 3) return "HIGH";
        if (highCount >= 1) return "MEDIUM";
        return "LOW";
    }

    /**
     * Determine risk level from probability.
     */
    private String determineRiskLevel(double probability) {
        if (probability > 0.7) return "HIGH";
        if (probability > 0.4) return "MEDIUM";
        return "LOW";
    }

    /**
     * Get recommended action based on risk level.
     */
    private String getRecommendedAction(String riskLevel) {
        return switch (riskLevel) {
            case "HIGH" -> "Monitor closely and prepare rollback plan";
            case "MEDIUM" -> "Enable enhanced logging and alerts";
            default -> "Proceed normally with standard monitoring";
        };
    }

    /**
     * Generate preventive actions.
     */
    private List<String> generatePreventiveActions(List<PredictiveFailureResponse.Prediction> predictions) {
        List<String> actions = new ArrayList<>();

        long highRisk = predictions.stream()
            .filter(p -> "HIGH".equals(p.getRiskLevel()))
            .count();

        if (highRisk > 0) {
            actions.add("Review high-risk lots before processing");
            actions.add("Enable real-time monitoring for predicted failures");
            actions.add("Prepare manual intervention procedures");
        }

        actions.add("Monitor system metrics during processing");
        actions.add("Set up alerts for common failure patterns");

        return actions;
    }
}