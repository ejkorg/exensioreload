package com.onsemi.cim.apps.exensio.exensioreload.dto.ai;

/**
 * Request for AI-enhanced data export.
 */
public class ExportRequest {
    private String dataType;  // sessions, errors, senders, performance, custom
    private String format;  // CSV, EXCEL, JSON
    private String site;
    private String timeRange = "7d";
    private int maxRows = 1000;
    private boolean includeAiContext = true;
    private boolean includeCharts = false;
    private String customQuery;

    public String getDataType() { return dataType; }
    public void setDataType(String dataType) { this.dataType = dataType; }
    public String getFormat() { return format; }
    public void setFormat(String format) { this.format = format; }
    public String getSite() { return site; }
    public void setSite(String site) { this.site = site; }
    public String getTimeRange() { return timeRange; }
    public void setTimeRange(String timeRange) { this.timeRange = timeRange; }
    public int getMaxRows() { return maxRows; }
    public void setMaxRows(int maxRows) { this.maxRows = maxRows; }
    public boolean isIncludeAiContext() { return includeAiContext; }
    public void setIncludeAiContext(boolean includeAiContext) { this.includeAiContext = includeAiContext; }
    public boolean isIncludeCharts() { return includeCharts; }
    public void setIncludeCharts(boolean includeCharts) { this.includeCharts = includeCharts; }
    public String getCustomQuery() { return customQuery; }
    public void setCustomQuery(String customQuery) { this.customQuery = customQuery; }
}