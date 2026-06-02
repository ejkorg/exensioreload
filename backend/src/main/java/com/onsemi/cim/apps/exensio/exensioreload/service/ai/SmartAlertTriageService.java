package com.onsemi.cim.apps.exensio.exensioreload.service.ai;

import com.onsemi.cim.apps.exensio.exensioreload.config.AiProperties;
import com.onsemi.cim.apps.exensio.exensioreload.dto.ai.AlertTriageRequest;
import com.onsemi.cim.apps.exensio.exensioreload.dto.ai.AlertTriageResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Service for smart alert triage and prioritization.
 * Groups similar alerts, identifies root causes, and recommends actions.
 */
@Service
public class SmartAlertTriageService {

    private static final Logger log = LoggerFactory.getLogger(SmartAlertTriageService.class);

    private final AiGatewayService gatewayService;
    private final AiProperties aiProperties;

    // Error pattern matching for categorization
    private static final Map<String, String> ERROR_PATTERNS = Map.ofEntries(
        Map.entry("timeout", "TIMEOUT"),
        Map.entry("connection", "CONNECTION"),
        Map.entry("auth", "AUTHENTICATION"),
        Map.entry("validation", "VALIDATION"),
        Map.entry("schema", "SCHEMA"),
        Map.entry("duplicate", "DUPLICATE"),
        Map.entry("network", "NETWORK"),
        Map.entry("database", "DATABASE"),
        Map.entry("exensio", "EXENSIO_API"),
        Map.entry("lot", "LOT_KEY")
    );

    // Severity mapping
    private static final Map<String, Integer> SEVERITY_SCORES = Map.of(
        "CRITICAL", 4,
        "HIGH", 3,
        "MEDIUM", 2,
        "LOW", 1
    );

    public SmartAlertTriageService(AiGatewayService gatewayService, AiProperties aiProperties) {
        this.gatewayService = gatewayService;
        this.aiProperties = aiProperties;
    }

    public boolean isAvailable() {
        return aiProperties.isConfigured();
    }

    /**
     * Perform smart triage on alerts.
     */
    public AlertTriageResponse triage(AlertTriageRequest request) {
        AlertTriageResponse response = new AlertTriageResponse();

        try {
            List<AlertTriageRequest.Alert> alerts = request.getAlerts();
            if (alerts == null || alerts.isEmpty()) {
                response.setTriageSummary("No alerts to triage.");
                return response;
            }

            // Count by severity
            int critical = 0, high = 0, medium = 0, low = 0;
            for (AlertTriageRequest.Alert alert : alerts) {
                String sev = alert.getSeverity() != null ? alert.getSeverity().toUpperCase() : "MEDIUM";
                switch (sev) {
                    case "CRITICAL": critical++; break;
                    case "HIGH": high++; break;
                    case "MEDIUM": medium++; break;
                    default: low++;
                }
            }
            response.setCriticalCount(critical);
            response.setHighCount(high);
            response.setMediumCount(medium);
            response.setLowCount(low);
            response.setTotalAlerts(alerts.size());

            // Determine overall priority
            response.setOverallPriority(determineOverallPriority(critical, high, medium));

            // Group alerts by error type
            List<AlertTriageResponse.AlertGroup> groups = groupAlerts(alerts);
            response.setGroups(groups);

            // Generate recommended actions
            List<AlertTriageResponse.RecommendedAction> actions = generateRecommendedActions(groups);
            response.setRecommendedActions(actions);

            // Estimate resolution time
            response.setEstimatedResolutionTime(estimateResolutionTime(groups));

            // Generate summary using AI
            response.setTriageSummary(generateTriageSummary(alerts, groups));

        } catch (Exception e) {
            log.error("Alert triage failed", e);
            response.setTriageSummary("Triage failed: " + e.getMessage());
        }

        return response;
    }

    /**
     * Determine overall priority based on alert counts.
     */
    private String determineOverallPriority(int critical, int high, int medium) {
        if (critical > 0) return "CRITICAL";
        if (high > 2) return "CRITICAL";
        if (high > 0) return "HIGH";
        if (medium > 5) return "HIGH";
        return "MEDIUM";
    }

    /**
     * Group alerts by error type and common factors.
     */
    private List<AlertTriageResponse.AlertGroup> groupAlerts(List<AlertTriageRequest.Alert> alerts) {
        Map<String, List<AlertTriageRequest.Alert>> grouped = new HashMap<>();

        // Group by error pattern
        for (AlertTriageRequest.Alert alert : alerts) {
            String errorType = categorizeError(alert.getError());
            grouped.computeIfAbsent(errorType, k -> new ArrayList<>()).add(alert);
        }

        return grouped.entrySet().stream()
            .map(entry -> createAlertGroup(entry.getKey(), entry.getValue()))
            .sorted((a, b) -> Integer.compare(b.getCount(), a.getCount()))
            .collect(Collectors.toList());
    }

    /**
     * Categorize error message into error type.
     */
    private String categorizeError(String errorMessage) {
        if (errorMessage == null || errorMessage.isBlank()) {
            return "UNKNOWN";
        }

        String lower = errorMessage.toLowerCase();
        for (Map.Entry<String, String> pattern : ERROR_PATTERNS.entrySet()) {
            if (lower.contains(pattern.getKey())) {
                return pattern.getValue();
            }
        }

        return "GENERAL";
    }

    /**
     * Create alert group from grouped alerts.
     */
    private AlertTriageResponse.AlertGroup createAlertGroup(String issueType, List<AlertTriageRequest.Alert> alerts) {
        AlertTriageResponse.AlertGroup group = new AlertTriageResponse.AlertGroup();
        group.setIssueType(issueType);
        group.setCount(alerts.size());

        // Extract unique senders
        List<String> senders = alerts.stream()
            .map(AlertTriageRequest.Alert::getSender)
            .filter(Objects::nonNull)
            .distinct()
            .collect(Collectors.toList());
        group.setAffectedSenders(senders);

        // Extract unique lots
        List<String> lots = alerts.stream()
            .map(AlertTriageRequest.Alert::getLotId)
            .filter(Objects::nonNull)
            .distinct()
            .limit(10)
            .collect(Collectors.toList());
        group.setAffectedLots(lots);

        // Generate root cause and recommendation using AI if available
        if (aiProperties.isConfigured() && !alerts.isEmpty()) {
            String firstError = alerts.get(0).getError();
            String rootCause = analyzeRootCause(issueType, firstError);
            group.setRootCause(rootCause);
            group.setRecommendation(generateRecommendation(issueType, rootCause));
        } else {
            group.setRootCause("Unknown - requires investigation");
            group.setRecommendation("Review logs for detailed error information");
        }

        return group;
    }

    /**
     * Analyze root cause of error type.
     */
    private String analyzeRootCause(String issueType, String errorMessage) {
        try {
            String prompt = String.format("""
                Analyze this error pattern for semiconductor manufacturing data loading:
                
                Error Type: %s
                Sample Error: %s
                
                Identify the most likely root cause. Consider:
                - Network/connection issues
                - Authentication problems
                - Data validation failures
                - External system (Exensio) issues
                - Database problems
                
                Return a brief root cause summary.
                """, issueType, errorMessage);

            Map<String, Object> context = Map.of("task", "root_cause_analysis");
            return gatewayService.sendMessage(prompt, context);
        } catch (Exception e) {
            log.warn("Failed to analyze root cause with AI", e);
            return getDefaultRootCause(issueType);
        }
    }

    /**
     * Get default root cause for common error types.
     */
    private String getDefaultRootCause(String issueType) {
        return switch (issueType) {
            case "TIMEOUT" -> "Network latency or remote system not responding";
            case "CONNECTION" -> "Sender endpoint unavailable or firewall blocking";
            case "AUTHENTICATION" -> "Invalid credentials or expired tokens";
            case "VALIDATION" -> "Data format does not match expected schema";
            case "DUPLICATE" -> "Record already exists in target system";
            case "EXENSIO_API" -> "Exensio API returning error response";
            case "LOT_KEY" -> "Lot not found in Exensio master data";
            default -> "Requires investigation";
        };
    }

    /**
     * Generate recommendation for error type.
     */
    private String generateRecommendation(String issueType, String rootCause) {
        return switch (issueType) {
            case "TIMEOUT" -> "Check network connectivity and increase timeout settings";
            case "CONNECTION" -> "Verify sender endpoint is online and firewall rules";
            case "AUTHENTICATION" -> "Update credentials in sender configuration";
            case "VALIDATION" -> "Review data format and update validation rules";
            case "DUPLICATE" -> "Enable duplicate detection or clean existing records";
            case "EXENSIO_API" -> "Check Exensio system status and API quotas";
            case "LOT_KEY" -> "Verify lot exists in Exensio or add via master data";
            default -> "Investigate error details in system logs";
        };
    }

    /**
     * Generate recommended actions based on groups.
     */
    private List<AlertTriageResponse.RecommendedAction> generateRecommendedActions(List<AlertTriageResponse.AlertGroup> groups) {
        List<AlertTriageResponse.RecommendedAction> actions = new ArrayList<>();
        int priority = 1;

        for (AlertTriageResponse.AlertGroup group : groups) {
            if (group.getCount() >= 2) {  // Only recommend for repeated issues
                AlertTriageResponse.RecommendedAction action = new AlertTriageResponse.RecommendedAction();
                action.setPriority(priority++);
                action.setAction("Investigate " + group.getIssueType() + " errors");
                action.setDescription("Group of " + group.getCount() + " " + group.getIssueType() + 
                    " errors affecting " + group.getAffectedSenders().size() + " senders");
                action.setAffectedItems(group.getAffectedSenders());
                action.setEstimatedTime(estimateActionTime(group));
                actions.add(action);
            }
        }

        return actions;
    }

    /**
     * Estimate time to resolve issue group.
     */
    private String estimateActionTime(AlertTriageResponse.AlertGroup group) {
        return switch (group.getIssueType()) {
            case "TIMEOUT", "CONNECTION" -> "15-30 minutes (check connectivity)";
            case "AUTHENTICATION" -> "5-10 minutes (update credentials)";
            case "VALIDATION" -> "30-60 minutes (review data format)";
            case "DUPLICATE" -> "10-20 minutes (cleanup + retry)";
            case "EXENSIO_API" -> "Varies (depends on Exensio support)";
            default -> "30-60 minutes";
        };
    }

    /**
     * Estimate total resolution time.
     */
    private long estimateResolutionTime(List<AlertTriageResponse.AlertGroup> groups) {
        // Base time in minutes
        long totalMinutes = groups.stream()
            .mapToLong(g -> estimateGroupTime(g.getIssueType(), g.getCount()))
            .sum();

        return totalMinutes * 60 * 1000; // Convert to milliseconds
    }

    private long estimateGroupTime(String issueType, int count) {
        long baseTime = switch (issueType) {
            case "AUTHENTICATION" -> 10;
            case "TIMEOUT", "CONNECTION" -> 20;
            case "VALIDATION" -> 30;
            case "DUPLICATE" -> 15;
            case "EXENSIO_API" -> 45;
            default -> 25;
        };
        return baseTime * Math.min(count, 5); // Cap at 5x base time
    }

    /**
     * Generate triage summary using AI.
     */
    private String generateTriageSummary(List<AlertTriageRequest.Alert> alerts, 
                                         List<AlertTriageResponse.AlertGroup> groups) {
        if (!aiProperties.isConfigured()) {
            return generateBasicSummary(alerts, groups);
        }

        try {
            String prompt = String.format("""
                Generate a brief triage summary for these alerts:
                
                Total Alerts: %d
                Groups: %s
                
                Provide a concise summary that helps operators understand the situation and priority.
                """, alerts.size(), groups.stream()
                    .map(g -> g.getIssueType() + "(" + g.getCount() + ")")
                    .collect(Collectors.joining(", ")));

            Map<String, Object> context = Map.of("task", "alert_summary");
            return gatewayService.sendMessage(prompt, context);
        } catch (Exception e) {
            log.warn("Failed to generate summary with AI", e);
            return generateBasicSummary(alerts, groups);
        }
    }

    /**
     * Generate basic summary without AI.
     */
    private String generateBasicSummary(List<AlertTriageRequest.Alert> alerts,
                                        List<AlertTriageResponse.AlertGroup> groups) {
        long critical = groups.stream()
            .filter(g -> g.getCount() >= 5)
            .count();

        if (critical > 0) {
            return "URGENT: Multiple alert groups detected requiring immediate attention.";
        }

        return String.format("Triage complete: %d alerts grouped into %d categories. " +
            "Top issues: %s", alerts.size(), groups.size(),
            groups.stream().findFirst().map(g -> g.getIssueType()).orElse("None"));
    }
}