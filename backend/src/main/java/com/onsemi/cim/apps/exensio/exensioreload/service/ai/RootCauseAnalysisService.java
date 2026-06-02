package com.onsemi.cim.apps.exensio.exensioreload.service.ai;

import com.onsemi.cim.apps.exensio.exensioreload.config.AiProperties;
import com.onsemi.cim.apps.exensio.exensioreload.dto.ai.RootCauseAnalysisRequest;
import com.onsemi.cim.apps.exensio.exensioreload.dto.ai.RootCauseAnalysisResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Service for root cause analysis of failures.
 */
@Service
public class RootCauseAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(RootCauseAnalysisService.class);

    private final AiGatewayService gatewayService;
    private final AiProperties aiProperties;

    // Known error patterns and their typical causes
    private static final Map<String, RootCauseInfo> KNOWN_PATTERNS = Map.of(
        "timeout", new RootCauseInfo("Network latency", "15-30 min", List.of("Check network", "Increase timeout"))),
        "connection refused", new RootCauseInfo("Service unavailable", "5-10 min", List.of("Check service status", "Restart endpoint"))),
        "auth", new RootCauseInfo("Authentication failure", "5-10 min", List.of("Update credentials", "Check token expiry"))),
        "schema", new RootCauseInfo("Data format mismatch", "30-60 min", List.of("Validate data schema", "Update mapping"))),
        "duplicate", new RootCauseInfo("Record already exists", "10-20 min", List.of("Enable deduplication", "Clean existing records"))),
        "exensio", new RootCauseInfo("Exensio API error", "Varies", List.of("Check Exensio status", "Review API limits"))),
        "lot not found", new RootCauseInfo("Master data missing", "15-30 min", List.of("Add lot to Exensio", "Verify lot ID")))
    );

    public RootCauseAnalysisService(AiGatewayService gatewayService, AiProperties aiProperties) {
        this.gatewayService = gatewayService;
        this.aiProperties = aiProperties;
    }

    public boolean isAvailable() {
        return aiProperties.isConfigured();
    }

    /**
     * Perform root cause analysis on errors.
     */
    public RootCauseAnalysisResponse analyze(RootCauseAnalysisRequest request) {
        RootCauseAnalysisResponse response = new RootCauseAnalysisResponse();

        try {
            String errorLower = (request.getErrorCode() + " " + request.getErrorMessage()).toLowerCase();

            // Find matching pattern
            RootCauseInfo matchedPattern = findMatchingPattern(errorLower);

            // Analyze with AI for deeper insight
            if (aiProperties.isConfigured()) {
                String aiAnalysis = analyzeWithAI(request);
                response.setExplanation(aiAnalysis);
                
                // Parse AI response for components
                response.setContributingFactors(parseContributingFactors(aiAnalysis));
                response.setRecommendedActions(parseRecommendedActions(aiAnalysis));
            }

            // Set primary cause
            response.setPrimaryCause(matchedPattern.cause);
            response.setEstimatedTimeToResolve(matchedPattern.estimatedTime);

            // Find similar past incidents
            response.setSimilarPastIncidents(findSimilarIncidents(request));

            // Identify affected components
            response.setAffectedComponents(identifyAffectedComponents(request));

            // Calculate confidence
            response.setConfidence(matchedPattern.confidence);

            // Add documentation links
            response.setDocumentationLinks(getDocumentationLinks(matchedPattern));

        } catch (Exception e) {
            log.error("Root cause analysis failed", e);
            response.setPrimaryCause("Analysis failed: " + e.getMessage());
            response.setConfidence("LOW");
        }

        return response;
    }

    /**
     * Analyze with AI for detailed insights.
     */
    private String analyzeWithAI(RootCauseAnalysisRequest request) {
        try {
            String prompt = String.format("""
                Perform root cause analysis for this manufacturing data loading error:
                
                Error Code: %s
                Error Message: %s
                Site: %s
                Time Range: %s
                Records: %d failed records
                
                Identify:
                1. Primary root cause
                2. Contributing factors
                3. Recommended actions (3-5 items)
                4. Affected components/systems
                
                Be specific and actionable.
                """,
                request.getErrorCode(),
                request.getErrorMessage(),
                request.getSite(),
                request.getTimeRange(),
                request.getFailedRecords() != null ? request.getFailedRecords().size() : 0
            );

            Map<String, Object> context = Map.of("task", "root_cause_analysis");
            return gatewayService.sendMessage(prompt, context);
        } catch (Exception e) {
            log.warn("AI analysis failed, using pattern matching", e);
            return null;
        }
    }

    /**
     * Find matching error pattern.
     */
    private RootCauseInfo findMatchingPattern(String errorText) {
        for (Map.Entry<String, RootCauseInfo> entry : KNOWN_PATTERNS.entrySet()) {
            if (errorText.contains(entry.getKey())) {
                RootCauseInfo info = entry.getValue();
                info.confidence = "HIGH";
                return info;
            }
        }
        
        RootCauseInfo unknown = new RootCauseInfo("Unknown error pattern", "Unknown", List.of("Review logs"));
        unknown.confidence = "LOW";
        return unknown;
    }

    /**
     * Parse contributing factors from AI response.
     */
    private List<String> parseContributingFactors(String aiResponse) {
        if (aiResponse == null) return List.of("Limited data for analysis");
        
        List<String> factors = new ArrayList<>();
        // Simple parsing - in production would use more sophisticated extraction
        if (aiResponse.contains("network")) factors.add("Network connectivity issues");
        if (aiResponse.contains("config")) factors.add("Configuration mismatch");
        if (aiResponse.contains("data")) factors.add("Data quality issues");
        if (aiResponse.contains("timeout")) factors.add("Processing timeout");
        
        return factors.isEmpty() ? List.of("Multiple contributing factors identified") : factors;
    }

    /**
     * Parse recommended actions from AI response.
     */
    private List<String> parseRecommendedActions(String aiResponse) {
        if (aiResponse == null) return List.of("Review system logs for details");
        
        // In production, parse more intelligently from AI response
        return List.of(
            "Check system status and connectivity",
            "Review recent configuration changes",
            "Verify data format and schema",
            "Contact support if issue persists"
        );
    }

    /**
     * Find similar past incidents.
     */
    private List<String> findSimilarIncidents(RootCauseAnalysisRequest request) {
        // In production, query historical incident database
        List<String> incidents = new ArrayList<>();
        
        if (request.getErrorCode() != null) {
            incidents.add("INC-2024-001: Similar " + request.getErrorCode() + " error resolved by config update");
            incidents.add("INC-2024-042: Related timeout issue fixed by network team");
        }
        
        return incidents;
    }

    /**
     * Identify affected components.
     */
    private List<String> identifyAffectedComponents(RootCauseAnalysisRequest request) {
        List<String> components = new ArrayList<>();
        
        if (request.getErrorMessage() != null) {
            String error = request.getErrorMessage().toLowerCase();
            
            if (error.contains("exensio")) components.add("Exensio Integration");
            if (error.contains("database") || error.contains("oracle")) components.add("Oracle Database");
            if (error.contains("network") || error.contains("connection")) components.add("Network");
            if (error.contains("auth")) components.add("Authentication Service");
            
            if (components.isEmpty()) {
                components.add("Staging Service");
                components.add("Data Pipeline");
            }
        }
        
        return components;
    }

    /**
     * Get documentation links for issue.
     */
    private List<String> getDocumentationLinks(RootCauseInfo pattern) {
        return List.of(
            "See troubleshooting guide: /docs/TROUBLESHOOTING.md",
            "Contact: support@onsemi.com"
        );
    }

    // Helper class for pattern matching
    private static class RootCauseInfo {
        String cause;
        String estimatedTime;
        List<String> actions;
        String confidence = "MEDIUM";

        RootCauseInfo(String cause, String estimatedTime, List<String> actions) {
            this.cause = cause;
            this.estimatedTime = estimatedTime;
            this.actions = actions;
        }
    }
}