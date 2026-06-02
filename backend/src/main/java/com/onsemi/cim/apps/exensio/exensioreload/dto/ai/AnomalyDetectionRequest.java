package com.onsemi.cim.apps.exensio.exensioreload.dto.ai;

import java.util.List;

/**
 * Request for anomaly detection.
 */
public class AnomalyDetectionRequest {
    private String site;
    private List<String> senderIds;
    private String timeRange;
    private String baselinePeriod;
    private List<String> metrics;

    public AnomalyDetectionRequest() {}

    public String getSite() { return site; }
    public void setSite(String site) { this.site = site; }

    public List<String> getSenderIds() { return senderIds; }
    public void setSenderIds(List<String> senderIds) { this.senderIds = senderIds; }

    public String getTimeRange() { return timeRange; }
    public void setTimeRange(String timeRange) { this.timeRange = timeRange; }

    public String getBaselinePeriod() { return baselinePeriod; }
    public void setBaselinePeriod(String baselinePeriod) { this.baselinePeriod = baselinePeriod; }

    public List<String> getMetrics() { return metrics; }
    public void setMetrics(List<String> metrics) { this.metrics = metrics; }
}