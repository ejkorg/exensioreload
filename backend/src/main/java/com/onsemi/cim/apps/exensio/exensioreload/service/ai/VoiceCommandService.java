package com.onsemi.cim.apps.exensio.exensioreload.service.ai;

import com.onsemi.cim.apps.exensio.exensioreload.config.AiProperties;
import com.onsemi.cim.apps.exensio.exensioreload.dto.ai.VoiceCommandRequest;
import com.onsemi.cim.apps.exensio.exensioreload.dto.ai.VoiceCommandResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Service for voice command processing.
 */
@Service
public class VoiceCommandService {

    private static final Logger log = LoggerFactory.getLogger(VoiceCommandService.class);

    private final AiGatewayService gatewayService;
    private final AiProperties aiProperties;

    // Command patterns
    private static final Map<String, List<String>> COMMAND_PATTERNS = Map.of(
        "show", List.of("show", "display", "view", "open", "load"),
        "generate", List.of("generate", "create", "make", "build"),
        "export", List.of("export", "download", "save", "export to"),
        "analyze", List.of("analyze", "review", "check", "examine"),
        "summary", List.of("summary", "overview", "report"),
        "alert", List.of("alert", "notify", "warn", "tell me about"),
        "search", List.of("search", "find", "lookup", "look up")
    );

    // Intent to action mapping
    private static final Map<String, String> INTENT_ACTIONS = Map.of(
        "daily_summary", "/api/ai/summary/daily",
        "error_analysis", "/api/ai/errors/analyze",
        "performance", "/api/ai/performance",
        "generate_report", "/api/ai/reports/generate",
        "export_data", "/api/ai/export",
        "search_kb", "/api/ai/knowledge/search"
    );

    public VoiceCommandService(AiGatewayService gatewayService, AiProperties aiProperties) {
        this.gatewayService = gatewayService;
        this.aiProperties = aiProperties;
    }

    public boolean isAvailable() {
        return aiProperties.isConfigured();
    }

    /**
     * Process voice command.
     */
    public VoiceCommandResponse processCommand(VoiceCommandRequest request) {
        VoiceCommandResponse response = new VoiceCommandResponse();

        try {
            String command = request.getCommand().toLowerCase();

            // Parse intent
            VoiceCommandResponse.CommandIntent intent = parseIntent(command);
            response.setIntent(intent);

            // Extract entities
            response.setEntities(extractEntities(command, intent));

            // Determine action
            response.setActionUrl(determineAction(intent));

            // Generate response message
            response.setResponseMessage(generateResponseMessage(intent, response.getEntities()));

            // Confirm action
            response.setActionConfirmation(String.format(
                "I'll %s %s for you. Say 'confirm' to proceed or 'cancel' to abort.",
                intent.getAction(),
                intent.getTarget()
            ));

            response.setSuccess(true);

        } catch (Exception e) {
            log.error("Voice command processing failed", e);
            response.setSuccess(false);
            response.setResponseMessage("Sorry, I didn't understand that command. Try saying 'help' for available commands.");
        }

        return response;
    }

    private VoiceCommandResponse.CommandIntent parseIntent(String command) {
        VoiceCommandResponse.CommandIntent intent = new VoiceCommandResponse.CommandIntent();

        // Detect command type
        String primaryAction = null;
        for (Map.Entry<String, List<String>> entry : COMMAND_PATTERNS.entrySet()) {
            for (String pattern : entry.getValue()) {
                if (command.contains(pattern)) {
                    primaryAction = entry.getKey();
                    break;
                }
            }
            if (primaryAction != null) break;
        }

        intent.setAction(primaryAction != null ? primaryAction : "unknown");

        // Detect target
        if (command.contains("summary") || command.contains("overview")) {
            intent.setTarget("summary");
            intent.setIntentType("daily_summary");
        } else if (command.contains("error") || command.contains("failures")) {
            intent.setTarget("errors");
            intent.setIntentType("error_analysis");
        } else if (command.contains("performance") || command.contains("metrics")) {
            intent.setTarget("performance");
            intent.setIntentType("performance");
        } else if (command.contains("report")) {
            intent.setTarget("report");
            intent.setIntentType("generate_report");
        } else if (command.contains("export") || command.contains("download")) {
            intent.setTarget("data");
            intent.setIntentType("export_data");
        } else if (command.contains("search") || command.contains("help")) {
            intent.setTarget("knowledge");
            intent.setIntentType("search_kb");
        } else {
            intent.setTarget("general");
            intent.setIntentType("unknown");
        }

        // Initialize parameters map if null
        if (intent.getParameters() == null) {
            intent.setParameters(new HashMap<>());
        }

        // Extract parameters
        if (command.contains("site") || command.contains("location")) {
            intent.getParameters().put("site", extractSite(command));
        }

        if (command.contains("today") || command.contains("this week") || command.contains("yesterday")) {
            intent.getParameters().put("timeRange", extractTimeRange(command));
        }

        return intent;
    }

    private Map<String, String> extractEntities(String command, VoiceCommandResponse.CommandIntent intent) {
        Map<String, String> entities = new HashMap<>();

        // Extract site
        if (command.contains("onsemi")) {
            entities.put("site", "ONSEMI");
        }

        // Extract time range
        if (command.contains("today")) {
            entities.put("timeRange", "1d");
        } else if (command.contains("this week")) {
            entities.put("timeRange", "7d");
        } else if (command.contains("yesterday")) {
            entities.put("timeRange", "yesterday");
        }

        // Extract format for export
        if (command.contains("csv")) {
            entities.put("format", "CSV");
        } else if (command.contains("excel") || command.contains("spreadsheet")) {
            entities.put("format", "EXCEL");
        }

        return entities;
    }

    private String determineAction(VoiceCommandResponse.CommandIntent intent) {
        return INTENT_ACTIONS.getOrDefault(intent.getIntentType(), "/api/ai/chat");
    }

    private String extractSite(String command) {
        // Extract site from command
        if (command.contains("onsemi")) return "ONSEMI";
        return "DEFAULT";
    }

    private String extractTimeRange(String command) {
        if (command.contains("today")) return "1d";
        if (command.contains("this week")) return "7d";
        if (command.contains("yesterday")) return "yesterday";
        return "7d";
    }

    private String generateResponseMessage(VoiceCommandResponse.CommandIntent intent, Map<String, String> entities) {
        String target = intent.getTarget();
        String action = intent.getAction();

        return String.format("Understood. I'll %s the %s for you.", action, target);
    }

    /**
     * Get available voice commands help.
     */
    public VoiceCommandResponse getHelp() {
        VoiceCommandResponse response = new VoiceCommandResponse();
        response.setSuccess(true);

        List<String> availableCommands = List.of(
            "Show me today's summary",
            "Generate performance report",
            "Analyze recent errors",
            "Export data to CSV",
            "Search knowledge base for [topic]",
            "Alert me about [something]"
        );

        response.setResponseMessage("Available voice commands:\n" +
            String.join("\n", availableCommands) +
            "\n\nSay 'cancel' to abort any action.");

        return response;
    }
}