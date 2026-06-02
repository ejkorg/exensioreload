package com.onsemi.cim.apps.exensio.exensioreload.service.ai;

import com.onsemi.cim.apps.exensio.exensioreload.config.AiProperties;
import com.onsemi.cim.apps.exensio.exensioreload.dto.ai.DataQualityScoreRequest;
import com.onsemi.cim.apps.exensio.exensioreload.dto.ai.DataQualityScoreResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Service for scoring data quality before Exensio loading.
 */
@Service
public class DataQualityScoreService {

    private static final Logger log = LoggerFactory.getLogger(DataQualityScoreService.class);

    private final AiGatewayService gatewayService;
    private final AiProperties aiProperties;

    // Quality thresholds
    private static final double PASS_THRESHOLD = 0.85;
    private static final double WARNING_THRESHOLD = 0.70;

    public DataQualityScoreService(AiGatewayService gatewayService, AiProperties aiProperties) {
        this.gatewayService = gatewayService;
        this.aiProperties = aiProperties;
    }

    public boolean isAvailable() {
        return aiProperties.isConfigured();
    }

    /**
     * Score data quality of provided records.
     */
    public DataQualityScoreResponse score(DataQualityScoreRequest request) {
        DataQualityScoreResponse response = new DataQualityScoreResponse();

        try {
            List<Map<String, Object>> records = request.getRecords();
            response.setTotalRecords(records != null ? records.size() : 0);

            if (records == null || records.isEmpty()) {
                response.setOverallScore(0.0);
                response.setGrade("N/A");
                response.setSummary("No records to score");
                return response;
            }

            // Score each dimension
            Map<String, Double> dimensionScores = new HashMap<>();
            dimensionScores.put("completeness", scoreCompleteness(records));
            dimensionScores.put("validity", scoreValidity(records));
            dimensionScores.put("consistency", scoreConsistency(records));
            dimensionScores.put("accuracy", scoreAccuracy(records));
            dimensionScores.put("timeliness", scoreTimeliness(records));
            response.setDimensionScores(dimensionScores);

            // Calculate overall score
            double overallScore = dimensionScores.values().stream()
                .mapToDouble(Double::doubleValue)
                .average()
                .orElse(0.0);
            response.setOverallScore(overallScore);

            // Determine grade
            response.setGrade(determineGrade(overallScore));

            // Detect issues
            List<DataQualityScoreResponse.QualityIssue> issues = detectIssues(records, dimensionScores);
            response.setIssues(issues);

            // Count passed/failed
            int passed = (int) records.stream()
                .filter(r -> calculateRecordScore(r) >= PASS_THRESHOLD)
                .count();
            response.setPassedRecords(passed);
            response.setFailedRecords(response.getTotalRecords() - passed);

            // Generate recommendations
            response.setRecommendations(generateRecommendations(issues, dimensionScores));

            // Determine if ready for Exensio
            response.setReadyForExensio(overallScore >= PASS_THRESHOLD && issues.stream()
                .noneMatch(i -> "HIGH".equals(i.getSeverity())));

        } catch (Exception e) {
            log.error("Data quality scoring failed", e);
            response.setOverallScore(0.0);
            response.setGrade("ERROR");
            response.setReadyForExensio(false);
        }

        return response;
    }

    /**
     * Score completeness (required fields present).
     */
    private double scoreCompleteness(List<Map<String, Object>> records) {
        if (records.isEmpty()) return 0.0;

        String[] requiredFields = {"lot", "wafer", "sender_id"};
        int completeCount = 0;

        for (Map<String, Object> record : records) {
            boolean complete = true;
            for (String field : requiredFields) {
                if (!record.containsKey(field) || record.get(field) == null || 
                    record.get(field).toString().isBlank()) {
                    complete = false;
                    break;
                }
            }
            if (complete) completeCount++;
        }

        return (double) completeCount / records.size();
    }

    /**
     * Score validity (fields match expected format).
     */
    private double scoreValidity(List<Map<String, Object>> records) {
        if (records.isEmpty()) return 0.0;

        int validCount = 0;
        for (Map<String, Object> record : records) {
            boolean valid = true;
            
            // Check lot format (alphanumeric, 3-20 chars)
            Object lot = record.get("lot");
            if (lot != null && !lot.toString().matches("^[A-Za-z0-9]{3,20}$")) {
                valid = false;
            }

            // Check wafer format (W followed by 2 digits)
            Object wafer = record.get("wafer");
            if (wafer != null && !wafer.toString().matches("^W\\d{2}$")) {
                valid = false;
            }

            if (valid) validCount++;
        }

        return (double) validCount / records.size();
    }

    /**
     * Score consistency (data is consistent across records).
     */
    private double scoreConsistency(List<Map<String, Object>> records) {
        // Check for duplicate records
        Set<String> uniqueRecords = new HashSet<>();
        int consistentCount = 0;

        for (Map<String, Object> record : records) {
            String key = record.get("lot") + "|" + record.get("wafer") + "|" + record.get("sender_id");
            if (!uniqueRecords.contains(key)) {
                uniqueRecords.add(key);
                consistentCount++;
            }
        }

        return (double) consistentCount / records.size();
    }

    /**
     * Score accuracy (data passes validation rules).
     */
    private double scoreAccuracy(List<Map<String, Object>> records) {
        // Simplified - would check against Exensio master data in production
        return 0.92;  // Simulated
    }

    /**
     * Score timeliness (data is recent and within acceptable range).
     */
    private double scoreTimeliness(List<Map<String, Object>> records) {
        // Simplified - would check timestamps in production
        return 0.95;  // Simulated
    }

    /**
     * Calculate score for individual record.
     */
    private double calculateRecordScore(Map<String, Object> record) {
        double score = 1.0;
        
        // Deduct for missing required fields
        String[] requiredFields = {"lot", "wafer", "sender_id"};
        for (String field : requiredFields) {
            if (!record.containsKey(field) || record.get(field) == null) {
                score -= 0.2;
            }
        }

        return Math.max(0.0, score);
    }

    /**
     * Determine grade from score.
     */
    private String determineGrade(double score) {
        if (score >= 0.95) return "A+";
        if (score >= 0.90) return "A";
        if (score >= 0.85) return "B";
        if (score >= 0.80) return "C";
        if (score >= 0.70) return "D";
        return "F";
    }

    /**
     * Detect quality issues.
     */
    private List<DataQualityScoreResponse.QualityIssue> detectIssues(
            List<Map<String, Object>> records, Map<String, Double> dimensionScores) {
        List<DataQualityScoreResponse.QualityIssue> issues = new ArrayList<>();

        for (Map.Entry<String, Double> entry : dimensionScores.entrySet()) {
            if (entry.getValue() < WARNING_THRESHOLD) {
                DataQualityScoreResponse.QualityIssue issue = new DataQualityScoreResponse.QualityIssue();
                issue.setField(entry.getKey());
                issue.setIssueType("Low quality score");
                issue.setAffectedCount((int) ((1 - entry.getValue()) * records.size()));
                issue.setSeverity(entry.getValue() < 0.5 ? "HIGH" : "MEDIUM");
                issue.setDescription(String.format("%.0f%% of records have %s issues", 
                    (1 - entry.getValue()) * 100, entry.getKey()));
                issue.setSuggestion(getSuggestionForField(entry.getKey()));
                issues.add(issue);
            }
        }

        return issues;
    }

    /**
     * Get suggestion for fixing field issues.
     */
    private String getSuggestionForField(String field) {
        return switch (field) {
            case "completeness" -> "Ensure all required fields (lot, wafer, sender_id) are populated";
            case "validity" -> "Verify data formats match expected patterns (e.g., W01 for wafer)";
            case "consistency" -> "Remove duplicate records before loading";
            case "accuracy" -> "Cross-reference with Exensio master data";
            case "timeliness" -> "Ensure data timestamps are current";
            default -> "Review and correct " + field + " data";
        };
    }

    /**
     * Generate recommendations based on issues.
     */
    private List<String> generateRecommendations(List<DataQualityScoreResponse.QualityIssue> issues,
                                                  Map<String, Double> dimensionScores) {
        List<String> recommendations = new ArrayList<>();

        for (DataQualityScoreResponse.QualityIssue issue : issues) {
            if ("completeness".equals(issue.getField())) {
                recommendations.add("Complete missing required fields before loading");
            }
            if ("validity".equals(issue.getField())) {
                recommendations.add("Validate data formats using pre-load validation");
            }
            if ("consistency".equals(issue.getField())) {
                recommendations.add("Enable duplicate detection in staging pipeline");
            }
        }

        if (recommendations.isEmpty()) {
            recommendations.add("Data quality is acceptable for Exensio loading");
        }

        return recommendations;
    }
}