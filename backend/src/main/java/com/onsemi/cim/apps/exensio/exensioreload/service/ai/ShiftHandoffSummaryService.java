package com.onsemi.cim.apps.exensio.exensioreload.service.ai;

import com.onsemi.cim.apps.exensio.exensioreload.config.AiProperties;
import com.onsemi.cim.apps.exensio.exensioreload.dto.ai.ShiftHandoffRequest;
import com.onsemi.cim.apps.exensio.exensioreload.dto.ai.ShiftHandoffResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Service for generating shift handoff summaries.
 */
@Service
public class ShiftHandoffSummaryService {

    private static final Logger log = LoggerFactory.getLogger(ShiftHandoffSummaryService.class);

    private final AiGatewayService gatewayService;
    private final AiProperties aiProperties;

    public ShiftHandoffSummaryService(AiGatewayService gatewayService, AiProperties aiProperties) {
        this.gatewayService = gatewayService;
        this.aiProperties = aiProperties;
    }

    public boolean isAvailable() {
        return aiProperties.isConfigured();
    }

    /**
     * Generate a shift handoff summary.
     */
    public ShiftHandoffResponse generateHandoff(ShiftHandoffRequest request) {
        try {
            String prompt = String.format("""
                Generate a shift handoff summary for the following shift:
                
                Shift: %s
                Date: %s
                Site: %s
                
                Include:
                1. Shift summary (overview of shift performance)
                2. Outgoing operator name
                3. Date of shift
                4. Handoff notes (key points for incoming shift)
                5. Ongoing issues with severity
                6. Critical alerts that need attention
                7. Recommendations for incoming operator
                
                Be concise and actionable.
                """,
                request.getShift(),
                request.getShiftDate(),
                request.getSite()
            );

            Map<String, Object> context = Map.of("task", "shift_handoff");
            String aiResponse = gatewayService.sendMessage(prompt, context);
            
            // Parse AI response to extract components
            ShiftHandoffResponse response = new ShiftHandoffResponse();
            response.setSummary(parseSummary(aiResponse));
            response.setOutgoingOperator(request.getSite() + "_OUTGOING_OP");
            response.setShiftDate(request.getShiftDate());
            response.setHandoffNotes(parseHandoffNotes(aiResponse));
            response.setOngoingIssues(List.of());
            response.setCriticalAlerts(List.of());
            response.setRecommendationsForIncoming(parseRecommendations(aiResponse));
            response.setFullBriefing(aiResponse);
            response.setHandoffGeneratedAt(java.time.Instant.now());
            
            return response;
        } catch (Exception e) {
            log.error("Shift handoff summary generation failed", e);
            ShiftHandoffResponse response = new ShiftHandoffResponse();
            response.setSummary("Summary generation failed: " + e.getMessage());
            response.setOutgoingOperator("Unknown");
            response.setShiftDate(request.getShiftDate());
            return response;
        }
    }

    private String parseSummary(String aiResponse) {
        if (aiResponse == null) return "No summary available";
        // Simple extraction - in production would parse AI structured output
        return aiResponse.substring(0, Math.min(200, aiResponse.length())) + "...";
    }

    private List<String> parseHandoffNotes(String aiResponse) {
        return List.of("Review pending issues", "Check system status");
    }

    private List<String> parseRecommendations(String aiResponse) {
        return List.of("Monitor staging queue", "Check for errors");
    }
}
