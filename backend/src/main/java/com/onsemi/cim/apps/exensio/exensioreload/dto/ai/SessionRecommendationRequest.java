package com.onsemi.cim.apps.exensio.exensioreload.dto.ai;

import java.util.Map;

/**
 * Request for session recommendations.
 */
public class SessionRecommendationRequest {
    private String site;
    private String senderId;
    private String userId;
    private Map<String, Object> currentContext;

    public SessionRecommendationRequest() {}

    public String getSite() { return site; }
    public void setSite(String site) { this.site = site; }

    public String getSenderId() { return senderId; }
    public void setSenderId(String senderId) { this.senderId = senderId; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public Map<String, Object> getCurrentContext() { return currentContext; }
    public void setCurrentContext(Map<String, Object> currentContext) { this.currentContext = currentContext; }
}