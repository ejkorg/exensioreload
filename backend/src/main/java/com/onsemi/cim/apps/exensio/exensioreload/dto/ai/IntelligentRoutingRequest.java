package com.onsemi.cim.apps.exensio.exensioreload.dto.ai;

import java.util.Map;

/**
 * Request for intelligent routing.
 */
public class IntelligentRoutingRequest {
    private String site;
    private String senderId;
    private Map<String, Object> recordData;
    private String targetEnvironment;

    public IntelligentRoutingRequest() {}

    public String getSite() { return site; }
    public void setSite(String site) { this.site = site; }

    public String getSenderId() { return senderId; }
    public void setSenderId(String senderId) { this.senderId = senderId; }

    public Map<String, Object> getRecordData() { return recordData; }
    public void setRecordData(Map<String, Object> recordData) { this.recordData = recordData; }

    public String getTargetEnvironment() { return targetEnvironment; }
    public void setTargetEnvironment(String targetEnvironment) { this.targetEnvironment = targetEnvironment; }
}