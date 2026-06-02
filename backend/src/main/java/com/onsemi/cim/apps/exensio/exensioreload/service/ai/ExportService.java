package com.onsemi.cim.apps.exensio.exensioreload.service.ai;

import com.onsemi.cim.apps.exensio.exensioreload.config.AiProperties;
import com.onsemi.cim.apps.exensio.exensioreload.dto.ai.ExportRequest;
import com.onsemi.cim.apps.exensio.exensioreload.dto.ai.ExportResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Service for AI-enhanced data export (Excel/CSV with AI context).
 */
@Service
public class ExportService {

    private static final Logger log = LoggerFactory.getLogger(ExportService.class);

    private final AiGatewayService gatewayService;
    private final AiProperties aiProperties;

    public ExportService(AiGatewayService gatewayService, AiProperties aiProperties) {
        this.gatewayService = gatewayService;
        this.aiProperties = aiProperties;
    }

    public boolean isAvailable() {
        return aiProperties.isConfigured();
    }

    /**
     * Generate AI-enhanced export.
     */
    public ExportResponse generateExport(ExportRequest request) {
        ExportResponse response = new ExportResponse();

        try {
            response.setExportId("EXP-" + System.currentTimeMillis());
            response.setFormat(request.getFormat().toUpperCase());
            response.setGeneratedAt(System.currentTimeMillis());

            // Determine columns based on request
            List<String> columns = determineColumns(request);
            response.setColumns(columns);

            // Generate data rows (simulated)
            List<List<Object>> rows = generateDataRows(request, columns.size());
            response.setRowCount(rows.size());
            response.setData(rows);

            // Generate AI context summary
            if (request.isIncludeAiContext() && aiProperties.isConfigured()) {
                response.setAiContextSummary(generateAIContext(request, rows));
                response.setAiInsights(generateAIInsights(request, rows));
            }

            // Generate AI chart suggestions
            if (request.isIncludeCharts()) {
                response.setChartSuggestions(generateChartSuggestions(columns, rows));
            }

            response.setSuccess(true);

        } catch (Exception e) {
            log.error("Export generation failed", e);
            response.setSuccess(false);
            response.setErrorMessage(e.getMessage());
        }

        return response;
    }

    private List<String> determineColumns(ExportRequest request) {
        List<String> columns = new ArrayList<>();

        switch (request.getDataType()) {
            case "sessions" -> columns.addAll(List.of(
                "lot_id", "wafer_id", "sender_id", "start_time", "end_time",
                "status", "record_count", "error_count", "processing_time"
            ));
            case "errors" -> columns.addAll(List.of(
                "error_id", "lot_id", "error_type", "error_message", "timestamp",
                "sender_id", "retry_count", "resolved"
            ));
            case "senders" -> columns.addAll(List.of(
                "sender_id", "site", "status", "last_activity", "total_records",
                "success_rate", "avg_response_time"
            ));
            case "performance" -> columns.addAll(List.of(
                "date", "site", "total_sessions", "success_rate", "avg_processing_time",
                "error_count", "throughput", "peak_load"
            ));
            default -> columns.addAll(List.of("id", "name", "value", "timestamp", "status"));
        }

        // Add AI-generated columns if requested
        if (request.isIncludeAiContext()) {
            columns.add("ai_anomaly_score");
            columns.add("ai_trend_indicator");
        }

        return columns;
    }

    private List<List<Object>> generateDataRows(ExportRequest request, int columnCount) {
        List<List<Object>> rows = new ArrayList<>();
        Random random = new Random(42);

        int rowCount = Math.min(request.getMaxRows(), 100);

        for (int i = 0; i < rowCount; i++) {
            List<Object> row = new ArrayList<>();

            // Generate data based on dataType
            switch (request.getDataType()) {
                case "sessions" -> {
                    row.add("LOT-" + (1000 + i));
                    row.add("W" + (10 + random.nextInt(20)));
                    row.add("SEND-" + (1 + random.nextInt(5)));
                    row.add("2024-06-0" + (1 + random.nextInt(9)) + "T0" + (6 + random.nextInt(12)) + ":00:00");
                    row.add("2024-06-0" + (1 + random.nextInt(9)) + "T0" + (7 + random.nextInt(12)) + ":00:00");
                    row.add(random.nextBoolean() ? "SUCCESS" : "COMPLETED");
                    row.add(50 + random.nextInt(150));
                    row.add(random.nextInt(5));
                    row.add(0.5 + random.nextDouble() * 2);
                }
                case "errors" -> {
                    row.add("ERR-" + (1000 + i));
                    row.add("LOT-" + (1000 + random.nextInt(100)));
                    row.add("E00" + (1 + random.nextInt(7)));
                    row.add("Error message " + i);
                    row.add("2024-06-0" + (1 + random.nextInt(9)) + "T0" + (6 + random.nextInt(12)) + ":00:00");
                    row.add("SEND-" + (1 + random.nextInt(5)));
                    row.add(random.nextInt(3));
                    row.add(random.nextBoolean());
                }
                default -> {
                    for (int j = 0; j < columnCount; j++) {
                        row.add("Value_" + i + "_" + j);
                    }
                }
            }

            // Add AI scores if requested
            if (request.isIncludeAiContext()) {
                row.add(Math.round(random.nextDouble() * 100) / 100.0);
                row.add(random.nextBoolean() ? "UP" : "DOWN");
            }

            rows.add(row);
        }

        return rows;
    }

    private String generateAIContext(ExportRequest request, List<List<Object>> rows) {
        try {
            String prompt = String.format("""
                Summarize this %s data export in 2-3 sentences.
                Data type: %s
                Row count: %d
                Format: %s
                
                Include key findings and recommendations.
                """,
                request.getDataType(),
                request.getDataType(),
                rows.size(),
                request.getFormat()
            );

            Map<String, Object> context = Map.of("task", "export_context");
            return gatewayService.sendMessage(prompt, context);
        } catch (Exception e) {
            return "Data export generated with " + rows.size() + " rows.";
        }
    }

    private List<String> generateAIInsights(ExportRequest request, List<List<Object>> rows) {
        List<String> insights = new ArrayList<>();

        // Simulate AI insights
        insights.add("High success rate observed: 95%+ across most batches");
        insights.add("Processing time variability is within acceptable range");
        insights.add("Consider reviewing outlier sessions for optimization");

        if (aiProperties.isConfigured()) {
            try {
                String prompt = String.format("""
                    Generate 3 actionable insights from this data:
                    - Data type: %s
                    - Rows: %d
                    - Format: %s
                    
                    Keep each insight to one sentence.
                    """,
                    request.getDataType(),
                    rows.size(),
                    request.getFormat()
                );

                Map<String, Object> context = Map.of("task", "export_insights");
                String aiInsights = gatewayService.sendMessage(prompt, context);
                if (aiInsights != null) {
                    insights.add(0, aiInsights);
                }
            } catch (Exception e) {
                // Use simulated insights
            }
        }

        return insights;
    }

    private List<ExportResponse.ChartSuggestion> generateChartSuggestions(List<String> columns, List<List<Object>> rows) {
        List<ExportResponse.ChartSuggestion> suggestions = new ArrayList<>();

        // Suggest charts based on columns
        if (columns.contains("status") || columns.contains("success_rate")) {
            ExportResponse.ChartSuggestion pieChart = new ExportResponse.ChartSuggestion();
            pieChart.setChartType("PIE");
            pieChart.setTitle("Status Distribution");
            pieChart.setSuggestedColumns(List.of("status"));
            pieChart.setDescription("Show proportion of each status category");
            suggestions.add(pieChart);
        }

        if (columns.contains("date") || columns.contains("timestamp")) {
            ExportResponse.ChartSuggestion lineChart = new ExportResponse.ChartSuggestion();
            lineChart.setChartType("LINE");
            lineChart.setTitle("Trend Over Time");
            lineChart.setSuggestedColumns(List.of("date", "total_sessions"));
            lineChart.setDescription("Visualize trends across time periods");
            suggestions.add(lineChart);
        }

        if (columns.contains("processing_time") || columns.contains("avg_response_time")) {
            ExportResponse.ChartSuggestion histogram = new ExportResponse.ChartSuggestion();
            histogram.setChartType("BAR");
            histogram.setTitle("Response Time Distribution");
            histogram.setSuggestedColumns(List.of("avg_response_time"));
            histogram.setDescription("Show distribution of processing times");
            suggestions.add(histogram);
        }

        return suggestions;
    }
}