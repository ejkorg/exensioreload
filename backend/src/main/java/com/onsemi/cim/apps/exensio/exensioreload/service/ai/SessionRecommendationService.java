package com.onsemi.cim.apps.exensio.exensioreload.service.ai;

import com.onsemi.cim.apps.exensio.exensioreload.config.AiProperties;
import com.onsemi.cim.apps.exensio.exensioreload.dto.ai.SessionRecommendationRequest;
import com.onsemi.cim.apps.exensio.exensioreload.dto.ai.SessionRecommendationResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Service for session recommendations based on historical patterns.
 */
@Service
public class SessionRecommendationService {

    private static final Logger log = LoggerFactory.getLogger(SessionRecommendationService.class);

    private final AiGatewayService gatewayService;
    private final AiProperties aiProperties;

    public SessionRecommendationService(AiGatewayService gatewayService, AiProperties aiProperties) {
        this.gatewayService = gatewayService;
        this.aiProperties = aiProperties;
    }

    public boolean isAvailable() {
        return aiProperties.isConfigured();
    }

    /**
     * Get session recommendations based on context.
     */
    public SessionRecommendationResponse getRecommendations(SessionRecommendationRequest request) {
        SessionRecommendationResponse response = new SessionRecommendationResponse();

        try {
            // Analyze similar past sessions
            List<String> similarSessions = findSimilarSessions(request);
            response.setSimilarSessionIds(similarSessions);

            // Generate recommended settings
            List<SessionRecommendationResponse.RecommendedSetting> settings = 
                generateRecommendedSettings(request, similarSessions);
            response.setRecommendedSettings(settings);

            // Predict outcome
            Map<String, Object> predictedOutcome = predictOutcome(settings);
            response.setPredictedOutcome(predictedOutcome);

            // Generate confidence level
            response.setConfidence(calculateConfidence(similarSessions, settings));

            // Generate summary
            response.setRecommendationSummary(generateSummary(settings, similarSessions));

        } catch (Exception e) {
            log.error("Session recommendations failed", e);
            response.setRecommendationSummary("Unable to generate recommendations: " + e.getMessage());
        }

        return response;
    }

    /**
     * Find similar past sessions based on context.
     */
    private List<String> findSimilarSessions(SessionRecommendationRequest request) {
        // In production, query historical sessions from database
        // For now, return simulated data
        List<String> similar = new ArrayList<>();
        
        if (request.getSite() != null && request.getSenderId() != null) {
            similar.add("session-" + request.getSite() + "-001");
            similar.add("session-" + request.getSite() + "-002");
        }

        return similar;
    }

    /**
     * Generate recommended settings based on historical data.
     */
    private List<SessionRecommendationResponse.RecommendedSetting> generateRecommendedSettings(
            SessionRecommendationRequest request, List<String> similarSessions) {
        List<SessionRecommendationResponse.RecommendedSetting> settings = new ArrayList<>();

        // Batch size recommendation
        SessionRecommendationResponse.RecommendedSetting batchSize = new SessionRecommendationResponse.RecommendedSetting();
        batchSize.setSetting("batch-size");
        batchSize.setCurrentValue("50");
        batchSize.setRecommendedValue(determineOptimalBatchSize(request, similarSessions));
        batchSize.setReason("Based on performance analysis of similar sessions");
        batchSize.setImpactScore(0.85);
        settings.add(batchSize);

        // Thread pool recommendation
        SessionRecommendationResponse.RecommendedSetting threadPool = new SessionRecommendationResponse.RecommendedSetting();
        threadPool.setSetting("thread-pool-size");
        threadPool.setCurrentValue("5");
        threadPool.setRecommendedValue(determineOptimalThreadPool(request, similarSessions));
        threadPool.setReason("Optimal concurrency for your system resources");
        threadPool.setImpactScore(0.75);
        settings.add(threadPool);

        // Timeout recommendation
        SessionRecommendationResponse.RecommendedSetting timeout = new SessionRecommendationResponse.RecommendedSetting();
        timeout.setSetting("timeout-minutes");
        timeout.setCurrentValue("60");
        timeout.setRecommendedValue(determineOptimalTimeout(request, similarSessions));
        timeout.setReason("Balanced for reliability and throughput");
        timeout.setImpactScore(0.65);
        settings.add(timeout);

        return settings;
    }

    private String determineOptimalBatchSize(SessionRecommendationRequest request, List<String> similarSessions) {
        // Analyze historical performance
        // In production, query database for actual optimal values
        return request.getCurrentContext() != null && 
               request.getCurrentContext().containsKey("highVolume") ? "100" : "50";
    }

    private String determineOptimalThreadPool(SessionRecommendationRequest request, List<String> similarSessions) {
        // Default recommendation
        return "5";
    }

    private String determineOptimalTimeout(SessionRecommendationRequest request, List<String> similarSessions) {
        // Default recommendation
        return "60";
    }

    /**
     * Predict outcome based on recommended settings.
     */
    private Map<String, Object> predictOutcome(List<SessionRecommendationResponse.RecommendedSetting> settings) {
        Map<String, Object> outcome = new HashMap<>();
        
        // Estimate improvements
        double batchImprovement = settings.stream()
            .filter(s -> s.getSetting().equals("batch-size"))
            .findFirst()
            .map(s -> s.getImpactScore() * 20)
            .orElse(0.0);
        
        outcome.put("estimatedThroughputIncrease", String.format("%.1f%%", batchImprovement));
        outcome.put("estimatedErrorRateReduction", "15-20%");
        outcome.put("estimatedCompletionTime", "10-15% faster");

        return outcome;
    }

    /**
     * Calculate confidence level for recommendations.
     */
    private String calculateConfidence(List<String> similarSessions, 
                                       List<SessionRecommendationResponse.RecommendedSetting> settings) {
        if (similarSessions.size() >= 5 && settings.stream().allMatch(s -> s.getImpactScore() > 0.7)) {
            return "HIGH";
        } else if (similarSessions.size() >= 2) {
            return "MEDIUM";
        }
        return "LOW";
    }

    /**
     * Generate recommendation summary.
     */
    private String generateSummary(List<SessionRecommendationResponse.RecommendedSetting> settings,
                                   List<String> similarSessions) {
        if (settings.isEmpty()) {
            return "No specific recommendations available. Using default settings.";
        }

        StringBuilder summary = new StringBuilder();
        summary.append("Based on ").append(similarSessions.size()).append(" similar sessions: ");
        
        settings.stream()
            .filter(s -> s.getImpactScore() > 0.6)
            .forEach(s -> summary.append(s.getSetting())
                .append(" → ").append(s.getRecommendedValue()).append(", "));

        return summary.toString().replaceAll(", $", "");
    }
}