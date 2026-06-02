package com.onsemi.cim.apps.exensio.exensioreload.dto.ai;

import java.util.List;

/**
 * Request for predictive maintenance analysis.
 */
public class PredictiveMaintenanceRequest {
    private String site;
    private List<String> componentIds;  // Optional filter for specific components
    private String timeRange;           // e.g., "7d", "30d"
    private boolean includeRecommendations;

    public String getSite() { return site; }
    public void setSite(String site) { this.site = site; }
    public List<String> getComponentIds() { return componentIds; }
    public void setComponentIds(List<String> componentIds) { this.componentIds = componentIds; }
    public String getTimeRange() { return timeRange; }
    public void setTimeRange(String timeRange) { this.timeRange = timeRange; }
    public boolean isIncludeRecommendations() { return includeRecommendations; }
    public void setIncludeRecommendations(boolean includeRecommendations) { this.includeRecommendations = includeRecommendations; }
}