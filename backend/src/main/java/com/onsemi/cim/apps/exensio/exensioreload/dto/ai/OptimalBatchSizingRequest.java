package com.onsemi.cim.apps.exensio.exensioreload.dto.ai;

import java.util.List;

/**
 * Request for optimal batch sizing.
 */
public class OptimalBatchSizingRequest {
    private String senderId;
    private String site;
    private String timeRange = "30d";
    private List<Integer> currentBatchSizes;

    public String getSenderId() { return senderId; }
    public void setSenderId(String senderId) { this.senderId = senderId; }
    public String getSite() { return site; }
    public void setSite(String site) { this.site = site; }
    public String getTimeRange() { return timeRange; }
    public void setTimeRange(String timeRange) { this.timeRange = timeRange; }
    public List<Integer> getCurrentBatchSizes() { return currentBatchSizes; }
    public void setCurrentBatchSizes(List<Integer> currentBatchSizes) { this.currentBatchSizes = currentBatchSizes; }
}