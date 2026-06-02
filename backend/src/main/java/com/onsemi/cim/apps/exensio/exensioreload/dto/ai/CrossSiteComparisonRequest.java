package com.onsemi.cim.apps.exensio.exensioreload.dto.ai;

import java.util.List;

/**
 * Request for cross-site comparison.
 */
public class CrossSiteComparisonRequest {
    private List<String> sites;        // e.g., ["SLN2", "SLN3"]
    private String timeRange;          // e.g., "7d", "30d"
    private boolean includeBestPractices;
    private boolean includeRecommendations;

    public List<String> getSites() { return sites; }
    public void setSites(List<String> sites) { this.sites = sites; }
    public String getTimeRange() { return timeRange; }
    public void setTimeRange(String timeRange) { this.timeRange = timeRange; }
    public boolean isIncludeBestPractices() { return includeBestPractices; }
    public void setIncludeBestPractices(boolean includeBestPractices) { this.includeBestPractices = includeBestPractices; }
    public boolean isIncludeRecommendations() { return includeRecommendations; }
    public void setIncludeRecommendations(boolean includeRecommendations) { this.includeRecommendations = includeRecommendations; }
}