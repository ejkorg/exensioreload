package com.onsemi.cim.apps.exensio.exensioreload.service.ai;

import com.onsemi.cim.apps.exensio.exensioreload.config.AiProperties;
import com.onsemi.cim.apps.exensio.exensioreload.dto.ai.ShiftHandoffRequest;
import com.onsemi.cim.apps.exensio.exensioreload.dto.ai.ShiftHandoffResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Service for generating shift handoff summaries.
 */
@Service
public class ShiftHandoffService {

    private static final Logger log = LoggerFactory.getLogger(ShiftHandoffService.class);

    private final AiGatewayService gatewayService;
    private final AiProperties aiProperties;

    public ShiftHandoffService(AiGatewayService gatewayService, AiProperties aiProperties) {
        this.gatewayService = gatewayService;
        this.aiProperties = aiProperties;
    }

    public boolean isAvailable() {
        return aiProperties.isConfigured();
    }

    /**
     * Generate shift handoff summary.
     */
    public ShiftHandoffResponse generateHandoff(ShiftHandoffRequest request) {
        ShiftHandoffResponse response = new ShiftHandoffResponse();

        try {
            response.setShift(request.getShift());
            response.setOutgoingOperator(request.getOutgoingOperator());
            response.setShiftDate(new Date().toString());

            // Gather shift data
            Map<String, Object> shiftData = gatherShiftData(request);
            
            // Build summary sections
            response.setSummary(executeSummary(shiftData, request));
            response.setKeyAccomplishments(extractAccomplishments(shiftData));
            response.setOngoingIssues(extractOngoingIssues(shiftData));
            response.setPendingActions(extractPendingActions(shiftData));
            response.setHandoffNotes(generateHandoffNotes(shiftData, request));
            response.setCriticalAlerts(extractCriticalAlerts(shiftData));
            response.setSuccessMetrics(buildSuccessMetrics(shiftData));
            response.setRecommendationsForIncoming(generateRecommendations(shiftData));

            // Generate full briefing if AI available
            if (aiProperties.isConfigured()) {
                response.setFullBriefing(generateAIBriefing(response));
            }

            response.setHandoffGeneratedAt(System.currentTimeMillis());

        } catch (Exception e) {
            log.error("Shift handoff generation failed", e);
            response.setSummary("Handoff generation failed: " + e.getMessage());
        }

        return response;
    }

    private Map<String, Object> gatherShiftData(ShiftHandoffRequest request) {
        Map<String, Object> data = new HashMap<>();

        // Simulated shift data - in production would query database
        data.put("totalSessions", 15);
        data.put("totalRecords", 892);
        data.put("successRate", 94);
        data.put("errorCount", 12);
        data.put("alertsHandled", 8);
        data.put("mainIssues", List.of("Network timeout at 02:30", "Exensio API delay at 04:15"));
        data.put("resolvedIssues", List.of("Sender B reconnect issue resolved at 01:45"));
        data.put("pendingIssues", List.of("Investigating intermittent auth failures"));
        data.put("operatorsPresent", List.of("John D.", "Sarah M."));
        data.put("incidents", 3);
        data.put(" lotesProcessed", 145);

        return data;
    }

    private String executeSummary(Map<String, Object> data, ShiftHandoffRequest request) {
        int sessions = (Integer) data.getOrDefault("totalSessions", 0);
        int records = (Integer) data.getOrDefault("totalRecords", 0);
        int successRate = (Integer) data.getOrDefault("successRate", 0);
        
        return String.format("Shift %s completed successfully. Processed %d sessions " +
            "with %d total records. Success rate: %d%%. %d alerts handled, %d incidents recorded.",
            request.getShift(), sessions, records, successRate,
            (Integer) data.getOrDefault("alertsHandled", 0),
            (Integer) data.getOrDefault("incidents", 0));
    }

    private List<String> extractAccomplishments(Map<String, Object> data) {
        List<String> accomplishments = new ArrayList<>();
        accomplishments.add("Processed " + data.getOrDefault("lotesProcessed", 0) + " lots successfully");
        accomplishments.add("Handled " + data.getOrDefault("alertsHandled", 0) + " system alerts");
        accomplishments.add("Maintained " + data.getOrDefault("successRate", 0) + "% success rate");
        accomplishments.add("Completed " + data.getOrDefault("totalSessions", 0) + " staging sessions");
        return accomplishments;
    }

    private List<ShiftHandoffResponse.IssueEntry> extractOngoingIssues(Map<String, Object> data) {
        List<ShiftHandoffResponse.IssueEntry> issues = new ArrayList<>();
        
        @SuppressWarnings("unchecked")
        List<String> mainIssues = (List<String>) data.get("mainIssues");
        if (mainIssues != null) {
            for (String issue : mainIssues) {
                ShiftHandoffResponse.IssueEntry entry = new ShiftHandoffResponse.IssueEntry();
                entry.setIssue(issue);
                entry.setSeverity("MEDIUM");
                entry.setStatus("INVESTIGATING");
                entry.setTimeLogged("During shift");
                issues.add(entry);
            }
        }

        // Add ongoing issue
        ShiftHandoffResponse.IssueEntry authIssue = new ShiftHandoffResponse.IssueEntry();
        authIssue.setIssue("Intermittent authentication failures on Sender C");
        authIssue.setSeverity("LOW");
        authIssue.setStatus("MONITORING");
        authIssue.setTimeLogged("04:00 - Present");
        issues.add(authIssue);

        return issues;
    }

    private List<ShiftHandoffResponse.ActionItem> extractPendingActions(Map<String, Object> data) {
        List<ShiftHandoffResponse.ActionItem> actions = new ArrayList<>();

        ShiftHandoffResponse.ActionItem action1 = new ShiftHandoffResponse.ActionItem();
        action1.setAction("Monitor Sender C for auth failures");
        action1.setAssignedTo("Incoming operator");
        action1.setPriority("MEDIUM");
        actions.add(action1);

        ShiftHandoffResponse.ActionItem action2 = new ShiftHandoffResponse.ActionItem();
        action2.setAction("Review Exensio API response times");
        action2.setAssignedTo("Operations team");
        action2.setPriority("LOW");
        actions.add(action2);

        ShiftHandoffResponse.ActionItem action3 = new ShiftHandoffResponse.ActionItem();
        action3.setAction("Follow up on incident INC-2024-156");
        action3.setAssignedTo("Shift supervisor");
        action3.setPriority("HIGH");
        actions.add(action3);

        return actions;
    }

    private List<String> generateHandoffNotes(Map<String, Object> data, ShiftHandoffRequest request) {
        List<String> notes = new ArrayList<>();
        notes.add("Shift " + request.getShift() + " started at scheduled time with all systems nominal");
        notes.add("Two minor incidents occurred - both resolved before shift end");
        notes.add("Network connectivity stable throughout shift");
        notes.add("Exensio integration performed within normal parameters");
        return notes;
    }

    private List<ShiftHandoffResponse.AlertEntry> extractCriticalAlerts(Map<String, Object> data) {
        List<ShiftHandoffResponse.AlertEntry> alerts = new ArrayList<>();

        ShiftHandoffResponse.AlertEntry alert1 = new ShiftHandoffResponse.AlertEntry();
        alert1.setAlertId("ALT-2024-0892");
        alert1.setDescription("Network timeout - Sender B");
        alert1.setTime("02:30 AM");
        alert1.setStatus("RESOLVED");
        alert1.setResolution("Network team reset connection");
        alerts.add(alert1);

        return alerts;
    }

    private Map<String, Object> buildSuccessMetrics(Map<String, Object> data) {
        Map<String, Object> metrics = new HashMap<>();
        metrics.put("sessionsCompleted", data.getOrDefault("totalSessions", 0));
        metrics.put("recordsProcessed", data.getOrDefault("totalRecords", 0));
        metrics.put("successRate", data.getOrDefault("successRate", 0) + "%");
        metrics.put("alertsResolved", data.getOrDefault("alertsHandled", 0));
        metrics.put("incidentsCount", data.getOrDefault("incidents", 0));
        metrics.put("lotsProcessed", data.getOrDefault("lotesProcessed", 0));
        return metrics;
    }

    private List<String> generateRecommendations(Map<String, Object> data) {
        List<String> recommendations = new ArrayList<>();
        recommendations.add("Continue monitoring Sender C for auth patterns");
        recommendations.add("Check network equipment at 06:00 for scheduled maintenance");
        recommendations.add("Review batch processing logs for optimization opportunities");
        return recommendations;
    }

    private String generateAIBriefing(ShiftHandoffResponse response) {
        try {
            String prompt = String.format("""
                Generate a concise shift handoff briefing for the incoming operator.
                
                Shift: %s
                Date: %s
                Outgoing Operator: %s
                
                Summary: %s
                
                Key Accomplishments:
                %s
                
                Ongoing Issues:
                %s
                
                Pending Actions:
                %s
                
                Provide a brief, actionable briefing in 3-4 sentences maximum.
                """,
                response.getShift(),
                response.getShiftDate(),
                response.getOutgoingOperator(),
                response.getSummary(),
                String.join("\n- ", response.getKeyAccomplishments()),
                response.getOngoingIssues().stream().map(i -> i.getIssue()).reduce((a, b) -> a + ", " + b).orElse("None"),
                response.getPendingActions().stream().map(a -> a.getAction()).reduce((a, b) -> a + ", " + b).orElse("None")
            );

            Map<String, Object> context = Map.of("task", "shift_handoff");
            return gatewayService.sendMessage(prompt, context);
        } catch (Exception e) {
            log.warn("AI briefing generation failed", e);
            return response.getSummary();
        }
    }
}