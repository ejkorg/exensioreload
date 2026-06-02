package com.onsemi.cim.apps.exensio.exensioreload.dto.ai;

/**
 * Request for cost analysis.
 */
public class CostAnalysisRequest {
    private String site;
    private String timeRange = "7d";  // 1d, 7d, 30d, custom
    private String startDate;
    private String endDate;
    private boolean includeProjections;

    public String getSite() { return site; }
    public void setSite(String site) { this.site = site; }
    public String getTimeRange() { return timeRange; }
    public void setTimeRange(String timeRange) { this.timeRange = timeRange; }
    public String getStartDate() { return startDate; }
    public void setStartDate(String startDate) { this.startDate = startDate; }
    public String getEndDate() { return endDate; }
    public void setEndDate(String endDate) { this.endDate = endDate; }
    public boolean isIncludeProjections() { return includeProjections; }
    public void setIncludeProjections(boolean includeProjections) { this.includeProjections = includeProjections; }
}