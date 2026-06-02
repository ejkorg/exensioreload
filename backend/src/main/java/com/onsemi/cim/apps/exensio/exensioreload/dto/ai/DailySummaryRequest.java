package com.onsemi.cim.apps.exensio.exensioreload.dto.ai;

import java.util.List;

/**
 * Request for daily summary.
 */
public class DailySummaryRequest {
    private String date;
    private List<String> sites;
    private String includeSections;

    public DailySummaryRequest() {}

    public String getDate() { return date; }
    public void setDate(String date) { this.date = date; }

    public List<String> getSites() { return sites; }
    public void setSites(List<String> sites) { this.sites = sites; }

    public String getIncludeSections() { return includeSections; }
    public void setIncludeSections(String includeSections) { this.includeSections = includeSections; }
}