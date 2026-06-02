package com.onsemi.cim.apps.exensio.exensioreload.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.onsemi.cim.apps.exensio.exensioreload.dto.ai.AiSummarizeRequest;
import com.onsemi.cim.apps.exensio.exensioreload.dto.ai.AiSummarizeResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Service for AI-powered alert and data summarization.
 * 
 * <p>Analyzes alerts, sessions, and failure patterns to provide
 * concise summaries and actionable recommendations.</p>
 */
@Service
public class AiSummarizeService {

    private static final Logger log = LoggerFactory.getLogger(AiSummarizeService.class);

    private final AiGatewayService gatewayService;
    private final ObjectMapper objectMapper;

    public AiSummarizeService(AiGatewayService gatewayService, ObjectMapper objectMapper) {
        this.gatewayService = gatewayService;
        this.objectMapper = objectMapper;
    }

    /**
     * Summarize a list of alerts.
     */
    public AiSummarizeResponse summarizeAlerts(AiSummarizeRequest request) {
        AiSummarizeResponse response = new AiSummarizeResponse();
        
        try {
            List<AiSummarizeRequest.AlertData> alerts = request.getAlerts();
            if (alerts == null || alerts.isEmpty()) {
                response.setSummary("No alerts to summarize.");
                response.setPriority("LOW");
                response.setTotalAlerts(0);
                return response;
            }
            
            response.setTotalAlerts(alerts.size());
            
            // Build summary prompt
            String prompt = buildAlertSummaryPrompt(alerts);
            
            // Call AI
            String aiResponse = gatewayService.sendMessage(prompt, null, Collections.emptyList());
            
            // Parse response
            response.setSummary(aiResponse);
            response.setGroups(groupAlertsByType(alerts));
            response.setPriority(determinePriority(alerts));
            response.setRecommendations(generateRecommendations(alerts));
            
            log.info("Summarized {} alerts", alerts.size());
            
        } catch (AiGatewayService.AiServiceException e) {
            log.error("Failed to summarize alerts: {}", e.getMessage());
            // Fallback to simple statistical summary
            response = generateFallbackSummary(request.getAlerts());
        } catch (Exception e) {
            log.error("Unexpected error summarizing alerts: {}", e.getMessage(), e);
            response.setSummary("Failed to generate summary. Please try again.");
            response.setPriority("MEDIUM");
        }
        
        return response;
    }

    private String buildAlertSummaryPrompt(List<AiSummarizeRequest.AlertData> alerts) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Analyze these manufacturing alerts and provide:\n\n");
        prompt.append("1. A brief summary (2-3 sentences)\n");
        prompt.append("2. Groups of similar issues\n");
        prompt.append("3. Likely root causes\n");
        prompt.append("4. Recommended actions\n\n");
        prompt.append("Format your response as:\n");
        prompt.append("SUMMARY: <brief summary>\n");
        prompt.append("GROUPS:\n");
        prompt.append("- Issue: <type>, Count: <n>, Senders: <list>, Cause: <cause>\n");
        prompt.append("RECOMMENDATIONS:\n");
        prompt.append("- <action 1>\n");
        prompt.append("- <action 2>\n\n");
        prompt.append("ALERTS:\n");
        
        for (AiSummarizeRequest.AlertData alert : alerts) {
            prompt.append(String.format("- Sender: %s, Error: %s, Time: %s, Severity: %s, Lot: %s%n",
                alert.getSender() != null ? alert.getSender() : "Unknown",
                alert.getError() != null ? alert.getError() : "Unknown",
                alert.getTimestamp() != null ? alert.getTimestamp() : "Unknown",
                alert.getSeverity() != null ? alert.getSeverity() : "Unknown",
                alert.getLotId() != null ? alert.getLotId() : "N/A"));
        }
        
        return prompt.toString();
    }

    private List<AiSummarizeResponse.AlertGroup> groupAlertsByType(List<AiSummarizeRequest.AlertData> alerts) {
        Map<String, List<AiSummarizeRequest.AlertData>> grouped = alerts.stream()
            .collect(Collectors.groupingBy(a -> 
                a.getError() != null ? a.getError() : "Unknown Error"));
        
        List<AiSummarizeResponse.AlertGroup> groups = new ArrayList<>();
        
        for (Map.Entry<String, List<AiSummarizeRequest.AlertData>> entry : grouped.entrySet()) {
            AiSummarizeResponse.AlertGroup group = new AiSummarizeResponse.AlertGroup();
            group.setIssue(entry.getKey());
            group.setCount(entry.getValue().size());
            
            List<String> senders = entry.getValue().stream()
                .map(AiSummarizeRequest.AlertData::getSender)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
            group.setSenders(senders);
            
            group.setLikelyCause(inferLikelyCause(entry.getKey()));
            group.setRecommendation(getRecommendationForError(entry.getKey()));
            
            groups.add(group);
        }
        
        // Sort by count descending
        groups.sort((a, b) -> Integer.compare(b.getCount(), a.getCount()));
        
        return groups;
    }

    private String inferLikelyCause(String error) {
        String lowerError = error.toLowerCase();
        
        if (lowerError.contains("timeout") || lowerError.contains("connection")) {
            return "Network connectivity issues or server overload";
        }
        if (lowerError.contains("auth") || lowerError.contains("credential") || lowerError.contains("unauthorized")) {
            return "Invalid or expired credentials";
        }
        if (lowerError.contains("not found") || lowerError.contains("404")) {
            return "Data reference no longer exists in Exensio";
        }
        if (lowerError.contains("duplicate") || lowerError.contains("already exist")) {
            return "Data has already been loaded, no action needed";
        }
        if (lowerError.contains("validation") || lowerError.contains("invalid")) {
            return "Data format or schema mismatch";
        }
        if (lowerError.contains("rate") || lowerError.contains("throttle")) {
            return "API rate limit exceeded, retry later";
        }
        
        return "Unknown - requires manual investigation";
    }

    private String getRecommendationForError(String error) {
        String lowerError = error.toLowerCase();
        
        if (lowerError.contains("timeout") || lowerError.contains("connection")) {
            return "Check network connectivity and Exensio server status";
        }
        if (lowerError.contains("auth") || lowerError.contains("credential")) {
            return "Re-authenticate and verify API credentials";
        }
        if (lowerError.contains("not found")) {
            return "Verify lot/wafer exists in Exensio or check data source";
        }
        if (lowerError.contains("duplicate")) {
            return "No action needed - data already loaded";
        }
        if (lowerError.contains("validation")) {
            return "Review data format and schema requirements";
        }
        
        return "Investigate logs for detailed error information";
    }

    private String determinePriority(List<AiSummarizeRequest.AlertData> alerts) {
        // Check for critical severity
        boolean hasCritical = alerts.stream()
            .anyMatch(a -> "CRITICAL".equalsIgnoreCase(a.getSeverity()));
        if (hasCritical) return "CRITICAL";
        
        // Check for high severity
        boolean hasHigh = alerts.stream()
            .anyMatch(a -> "HIGH".equalsIgnoreCase(a.getSeverity()));
        if (hasHigh) return "HIGH";
        
        // Check for medium severity
        boolean hasMedium = alerts.stream()
            .anyMatch(a -> "MEDIUM".equalsIgnoreCase(a.getSeverity()));
        if (hasMedium) return "MEDIUM";
        
        // Check alert count - many alerts suggests systemic issue
        if (alerts.size() > 10) return "HIGH";
        if (alerts.size() > 5) return "MEDIUM";
        
        return "LOW";
    }

    private List<String> generateRecommendations(List<AiSummarizeRequest.AlertData> alerts) {
        List<String> recommendations = new ArrayList<>();
        
        // Group by sender
        Map<String, Long> senderCounts = alerts.stream()
            .filter(a -> a.getSender() != null)
            .collect(Collectors.groupingBy(AiSummarizeRequest.AlertData::getSender, Collectors.counting()));
        
        // Find senders with multiple issues
        senderCounts.entrySet().stream()
            .filter(e -> e.getValue() > 1)
            .forEach(e -> recommendations.add(
                String.format("Investigate %s - %d alerts in the last period", e.getKey(), e.getValue())));
        
        // Common recommendations
        if (recommendations.isEmpty()) {
            recommendations.add("Monitor for additional alerts");
            recommendations.add("Review Exensio system status");
        }
        
        return recommendations;
    }

    /**
     * Fallback summary when AI is unavailable.
     */
    private AiSummarizeResponse generateFallbackSummary(List<AiSummarizeRequest.AlertData> alerts) {
        AiSummarizeResponse response = new AiSummarizeResponse();
        
        int count = alerts != null ? alerts.size() : 0;
        response.setTotalAlerts(count);
        response.setPriority(determinePriority(alerts));
        response.setGroups(groupAlertsByType(alerts));
        
        // Simple summary
        Map<String, Long> byError = alerts.stream()
            .collect(Collectors.groupingBy(
                a -> a.getError() != null ? a.getError() : "Unknown",
                Collectors.counting()));
        
        StringBuilder summary = new StringBuilder();
        summary.append(String.format("Total of %d alerts across %d distinct issues. ", count, byError.size()));
        
        byError.entrySet().stream()
            .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
            .limit(3)
            .forEach(e -> summary.append(e.getKey()).append(" (").append(e.getValue()).append("). "));
        
        response.setSummary(summary.toString());
        response.setRecommendations(generateRecommendations(alerts));
        
        return response;
    }

    /**
     * Summarize session statistics.
     */
    public String summarizeSessionStats(Map<String, Object> sessionStats) {
        try {
            StringBuilder prompt = new StringBuilder();
            prompt.append("Summarize this staging session status in 2-3 sentences:\n\n");
            
            for (Map.Entry<String, Object> entry : sessionStats.entrySet()) {
                prompt.append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
            }
            
            return gatewayService.sendMessage(prompt.toString(), null, Collections.emptyList());
        } catch (Exception e) {
            log.error("Failed to summarize session stats: {}", e.getMessage());
            return "Session data unavailable.";
        }
    }

    /**
     * Check if AI summarization is available.
     */
    public boolean isAvailable() {
        return gatewayService.isHealthy();
    }
}