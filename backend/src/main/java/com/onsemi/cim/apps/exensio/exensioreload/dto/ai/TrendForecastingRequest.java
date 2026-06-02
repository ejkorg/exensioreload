package com.onsemi.cim.apps.exensio.exensioreload.dto.ai;

import java.util.List;

/**
 * Request for trend forecasting.
 */
public class TrendForecastingRequest {
    private String site;
    private int forecastDays = 7;
    private List<String> metrics;
    private String timeRange = "30d";

    public String getSite() { return site; }
    public void setSite(String site) { this.site = site; }
    public int getForecastDays() { return forecastDays; }
    public void setForecastDays(int forecastDays) { this.forecastDays = forecastDays; }
    public List<String> getMetrics() { return metrics; }
    public void setMetrics(List<String> metrics) { this.metrics = metrics; }
    public String getTimeRange() { return timeRange; }
    public void setTimeRange(String timeRange) { this.timeRange = timeRange; }
}