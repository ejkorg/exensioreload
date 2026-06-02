package com.onsemi.cim.apps.exensio.exensioreload.dto.ai;

import java.util.List;
import java.util.Map;

/**
 * Request for root cause analysis.
 */
public class RootCauseAnalysisRequest {
    private String errorCode;
    private String errorMessage;
    private List<Map<String, String>> failedRecords;
    private String timeRange;
    private String site;

    public RootCauseAnalysisRequest() {}

    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String errorCode) { this.errorCode = errorCode; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public List<Map<String, String>> getFailedRecords() { return failedRecords; }
    public void setFailedRecords(List<Map<String, String>> failedRecords) { this.failedRecords = failedRecords; }

    public String getTimeRange() { return timeRange; }
    public void setTimeRange(String timeRange) { this.timeRange = timeRange; }

    public String getSite() { return site; }
    public void setSite(String site) { this.site = site; }
}