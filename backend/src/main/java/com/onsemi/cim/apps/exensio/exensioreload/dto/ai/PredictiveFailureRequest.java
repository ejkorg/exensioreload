package com.onsemi.cim.apps.exensio.exensioreload.dto.ai;

import java.util.List;

/**
 * Request for predictive failure analysis.
 */
public class PredictiveFailureRequest {
    private String site;
    private List<String> lotIds;
    private List<String> senderIds;
    private String timeWindow;
    private boolean includeHistorical;

    public PredictiveFailureRequest() {}

    public String getSite() { return site; }
    public void setSite(String site) { this.site = site; }

    public List<String> getLotIds() { return lotIds; }
    public void setLotIds(List<String> lotIds) { this.lotIds = lotIds; }

    public List<String> getSenderIds() { return senderIds; }
    public void setSenderIds(List<String> senderIds) { this.senderIds = senderIds; }

    public String getTimeWindow() { return timeWindow; }
    public void setTimeWindow(String timeWindow) { this.timeWindow = timeWindow; }

    public boolean isIncludeHistorical() { return includeHistorical; }
    public void setIncludeHistorical(boolean includeHistorical) { this.includeHistorical = includeHistorical; }
}