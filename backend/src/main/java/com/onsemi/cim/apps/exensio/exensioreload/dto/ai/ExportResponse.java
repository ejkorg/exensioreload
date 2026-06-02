package com.onsemi.cim.apps.exensio.exensioreload.dto.ai;

import java.util.List;

/**
 * Response for AI-enhanced data export.
 */
public class ExportResponse {
    private String exportId;
    private boolean success;
    private String errorMessage;
    private String format;
    private List<String> columns;
    private List<List<Object>> data;
    private int rowCount;
    private String aiContextSummary;
    private List<String> aiInsights;
    private List<ChartSuggestion> chartSuggestions;
    private long generatedAt;

    public static class ChartSuggestion {
        private String chartType;  // LINE, BAR, PIE, HISTOGRAM
        private String title;
        private List<String> suggestedColumns;
        private String description;

        public String getChartType() { return chartType; }
        public void setChartType(String chartType) { this.chartType = chartType; }
        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        public List<String> getSuggestedColumns() { return suggestedColumns; }
        public void setSuggestedColumns(List<String> suggestedColumns) { this.suggestedColumns = suggestedColumns; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
    }

    // Getters and setters
    public String getExportId() { return exportId; }
    public void setExportId(String exportId) { this.exportId = exportId; }
    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public String getFormat() { return format; }
    public void setFormat(String format) { this.format = format; }
    public List<String> getColumns() { return columns; }
    public void setColumns(List<String> columns) { this.columns = columns; }
    public List<List<Object>> getData() { return data; }
    public void setData(List<List<Object>> data) { this.data = data; }
    public int getRowCount() { return rowCount; }
    public void setRowCount(int rowCount) { this.rowCount = rowCount; }
    public String getAiContextSummary() { return aiContextSummary; }
    public void setAiContextSummary(String aiContextSummary) { this.aiContextSummary = aiContextSummary; }
    public List<String> getAiInsights() { return aiInsights; }
    public void setAiInsights(List<String> aiInsights) { this.aiInsights = aiInsights; }
    public List<ChartSuggestion> getChartSuggestions() { return chartSuggestions; }
    public void setChartSuggestions(List<ChartSuggestion> chartSuggestions) { this.chartSuggestions = chartSuggestions; }
    public long getGeneratedAt() { return generatedAt; }
    public void setGeneratedAt(long generatedAt) { this.generatedAt = generatedAt; }
}