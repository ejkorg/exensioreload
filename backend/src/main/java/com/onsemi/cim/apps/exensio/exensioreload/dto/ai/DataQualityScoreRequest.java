package com.onsemi.cim.apps.exensio.exensioreload.dto.ai;

import java.util.List;
import java.util.Map;

/**
 * Request for data quality score.
 */
public class DataQualityScoreRequest {
    private List<Map<String, Object>> records;
    private String site;
    private String senderId;
    private boolean includeDetails;

    public DataQualityScoreRequest() {}

    public List<Map<String, Object>> getRecords() { return records; }
    public void setRecords(List<Map<String, Object>> records) { this.records = records; }

    public String getSite() { return site; }
    public void setSite(String site) { this.site = site; }

    public String getSenderId() { return senderId; }
    public void setSenderId(String senderId) { this.senderId = senderId; }

    public boolean isIncludeDetails() { return includeDetails; }
    public void setIncludeDetails(boolean includeDetails) { this.includeDetails = includeDetails; }
}