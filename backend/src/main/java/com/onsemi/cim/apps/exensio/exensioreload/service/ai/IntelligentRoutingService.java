package com.onsemi.cim.apps.exensio.exensioreload.service.ai;

import com.onsemi.cim.apps.exensio.exensioreload.config.AiProperties;
import com.onsemi.cim.apps.exensio.exensioreload.dto.ai.IntelligentRoutingRequest;
import com.onsemi.cim.apps.exensio.exensioreload.dto.ai.IntelligentRoutingResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Service for intelligent routing of data to optimal targets.
 */
@Service
public class IntelligentRoutingService {

    private static final Logger log = LoggerFactory.getLogger(IntelligentRoutingService.class);

    private final AiGatewayService gatewayService;
    private final AiProperties aiProperties;

    // Known routing rules
    private static final Map<String, RouteRule> ROUTE_RULES = new HashMap<>();

    static {
        ROUTE_RULES.put("highVolume", new RouteRule("BATCH", 0.9, "High volume optimized route"));
        ROUTE_RULES.put("critical", new RouteRule("PRIORITY", 0.95, "Critical path with dedicated resources"));
        ROUTE_RULES.put("test", new RouteRule("QA", 0.85, "QA environment for testing"));
        ROUTE_RULES.put("retry", new RouteRule("RETRY", 0.7, "Retry path with extended timeout"));
    }

    public IntelligentRoutingService(AiGatewayService gatewayService, AiProperties aiProperties) {
        this.gatewayService = gatewayService;
        this.aiProperties = aiProperties;
    }

    public boolean isAvailable() {
        return aiProperties.isConfigured();
    }

    /**
     * Determine optimal routing for a record.
     */
    public IntelligentRoutingResponse getOptimalRoute(IntelligentRoutingRequest request) {
        IntelligentRoutingResponse response = new IntelligentRoutingResponse();

        try {
            // Analyze record data
            Map<String, Object> recordData = request.getRecordData();
            String characteristics = analyzeCharacteristics(recordData);

            // Determine best route
            RouteRule optimalRule = determineOptimalRoute(characteristics, recordData);
            response.setRecommendedRoute(optimalRule.route);
            response.setConfidence(optimalRule.confidence);
            response.setReason(optimalRule.reason);

            // Determine target endpoint
            response.setTargetEndpoint(determineEndpoint(optimalRule.route, request));

            // Generate alternatives
            response.setAlternativeRoutes(generateAlternatives(optimalRule, recordData));

            // Estimate processing time
            response.setEstimatedProcessingTime(estimateProcessingTime(optimalRule, recordData));

            // Generate optimizations
            response.setOptimizations(generateOptimizations(optimalRule, recordData));

            response.setAutoRouteEnabled(true);

        } catch (Exception e) {
            log.error("Intelligent routing failed", e);
            response.setRecommendedRoute("DEFAULT");
            response.setConfidence(0.5);
            response.setReason("Default routing due to analysis error");
            response.setAutoRouteEnabled(false);
        }

        return response;
    }

    /**
     * Analyze record characteristics for routing decision.
     */
    private String analyzeCharacteristics(Map<String, Object> recordData) {
        StringBuilder characteristics = new StringBuilder();

        // Analyze volume indicators
        if (recordData.containsKey("batchSize")) {
            int batchSize = ((Number) recordData.get("batchSize")).intValue();
            if (batchSize > 100) {
                characteristics.append("highVolume,");
            } else if (batchSize < 10) {
                characteristics.append("lowVolume,");
            }
        }

        // Analyze priority indicators
        if (recordData.containsKey("priority")) {
            String priority = recordData.get("priority").toString().toUpperCase();
            if ("CRITICAL".equals(priority) || "HIGH".equals(priority)) {
                characteristics.append("critical,");
            }
        }

        // Analyze environment
        if (recordData.containsKey("environment")) {
            String env = recordData.get("environment").toString().toLowerCase();
            if (env.contains("test") || env.contains("qa")) {
                characteristics.append("test,");
            }
        }

        // Analyze retry indicators
        if (recordData.containsKey("retryCount")) {
            int retryCount = ((Number) recordData.get("retryCount")).intValue();
            if (retryCount > 0) {
                characteristics.append("retry,");
            }
        }

        return characteristics.length() > 0 ? 
            characteristics.toString().replaceAll(",$", "") : "default";
    }

    /**
     * Determine optimal route based on characteristics.
     */
    private RouteRule determineOptimalRoute(String characteristics, Map<String, Object> recordData) {
        // Check for matching rules
        for (Map.Entry<String, RouteRule> entry : ROUTE_RULES.entrySet()) {
            if (characteristics.contains(entry.getKey())) {
                return entry.getValue();
            }
        }

        // Default rule
        return new RouteRule("STANDARD", 0.75, "Standard processing route");
    }

    /**
     * Determine target endpoint for route.
     */
    private String determineEndpoint(String route, IntelligentRoutingRequest request) {
        return switch (route) {
            case "BATCH" -> "/v1/key/lot-wafer-lookup/batch";
            case "PRIORITY" -> "/v1/key/lot-wafer-lookup/priority";
            case "QA" -> "/v1/key/lot-wafer-lookup/qa";
            case "RETRY" -> "/v1/key/lot-wafer-lookup/retry";
            default -> "/v1/key/lot-wafer-lookup";
        };
    }

    /**
     * Generate alternative routes.
     */
    private List<String> generateAlternatives(RouteRule optimal, Map<String, Object> recordData) {
        List<String> alternatives = new ArrayList<>();

        // Always include default
        if (!"DEFAULT".equals(optimal.route)) {
            alternatives.add("DEFAULT - Standard processing");
        }

        // Add alternatives based on characteristics
        if (recordData.containsKey("retryCount")) {
            alternatives.add("FALLBACK - Reduced timeout for retries");
        }

        alternatives.add("PARALLEL - Split batch across multiple workers");

        return alternatives;
    }

    /**
     * Estimate processing time for route.
     */
    private Map<String, Object> estimateProcessingTime(RouteRule route, Map<String, Object> recordData) {
        Map<String, Object> estimates = new HashMap<>();

        double baseTime = switch (route.route) {
            case "BATCH" -> 0.8;  // 20% faster
            case "PRIORITY" -> 0.5;  // 50% faster
            case "QA" -> 1.5;  // 50% slower (more validation)
            default -> 1.0;
        };

        estimates.put("estimatedTime", String.format("%.1fx baseline", baseTime));
        estimates.put("minTime", "30 seconds");
        estimates.put("maxTime", "5 minutes");

        return estimates;
    }

    /**
     * Generate optimizations for route.
     */
    private List<String> generateOptimizations(RouteRule route, Map<String, Object> recordData) {
        List<String> optimizations = new ArrayList<>();

        switch (route.route) {
            case "BATCH":
                optimizations.add("Enable batch compression for high volume");
                optimizations.add("Use connection pooling for efficiency");
                break;
            case "PRIORITY":
                optimizations.add("Reserve dedicated processing thread");
                optimizations.add("Enable priority queue");
                break;
            case "QA":
                optimizations.add("Enable detailed logging");
                optimizations.add("Perform additional validation");
                break;
            default:
                optimizations.add("Use standard processing parameters");
        }

        return optimizations;
    }

    // Helper class for routing rules
    private static class RouteRule {
        String route;
        double confidence;
        String reason;

        RouteRule(String route, double confidence, String reason) {
            this.route = route;
            this.confidence = confidence;
            this.reason = reason;
        }
    }
}