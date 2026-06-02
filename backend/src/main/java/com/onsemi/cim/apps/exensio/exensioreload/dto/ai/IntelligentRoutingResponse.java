package com.onsemi.cim.apps.exensio.exensioreload.dto.ai;

import java.util.List;
import java.util.Map;

/**
 * Response for intelligent routing.
 */
public class IntelligentRoutingResponse {
    private String recommendedRoute;
    private String targetEndpoint;
    private double confidence;
    private String reason;
    private List<String> alternativeRoutes;
    private Map<String, Object> estimatedProcessingTime;
    private List<String> optimizations;
    private boolean autoRouteEnabled;

    public IntelligentRoutingResponse() {}

    public String getRecommendedRoute() { return recommendedRoute; }
    public void setRecommendedRoute(String recommendedRoute) { this.recommendedRoute = recommendedRoute; }

    public String getTargetEndpoint() { return targetEndpoint; }
    public void setTargetEndpoint(String targetEndpoint) { this.targetEndpoint = targetEndpoint; }

    public double getConfidence() { return confidence; }
    public void setConfidence(double confidence) { this.confidence = confidence; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public List<String> getAlternativeRoutes() { return alternativeRoutes; }
    public void setAlternativeRoutes(List<String> alternativeRoutes) { this.alternativeRoutes = alternativeRoutes; }

    public Map<String, Object> getEstimatedProcessingTime() { return estimatedProcessingTime; }
    public void setEstimatedProcessingTime(Map<String, Object> estimatedProcessingTime) { this.estimatedProcessingTime = estimatedProcessingTime; }

    public List<String> getOptimizations() { return optimizations; }
    public void setOptimizations(List<String> optimizations) { this.optimizations = optimizations; }

    public boolean isAutoRouteEnabled() { return autoRouteEnabled; }
    public void setAutoRouteEnabled(boolean autoRouteEnabled) { this.autoRouteEnabled = autoRouteEnabled; }
}