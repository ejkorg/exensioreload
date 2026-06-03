package com.onsemi.cim.apps.exensio.exensioreload.controller;

import com.onsemi.cim.apps.exensio.exensioreload.config.AiProperties;
import com.onsemi.cim.apps.exensio.exensioreload.dto.ai.*;
import com.onsemi.cim.apps.exensio.exensioreload.service.ai.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * REST controller for AI-related endpoints.
 * Provides all AI features including chat, summarization, search, and analysis.
 */
@RestController
@RequestMapping("/api/ai")
public class AiController {

    private static final Logger log = LoggerFactory.getLogger(AiController.class);

    private final AiChatService chatService;
    private final AiSummarizeService summarizeService;
    private final NaturalLanguageSearchService searchService;
    private final SmartAlertTriageService alertTriageService;
    private final SessionRecommendationService recommendationService;
    private final AnomalyDetectionService anomalyService;
    private final RootCauseAnalysisService rootCauseService;
    private final DailySummaryService dailySummaryService;
    private final PredictiveFailureService predictiveService;
    private final DataQualityScoreService qualityService;
    private final IntelligentRoutingService routingService;
    // New AI features
    private final ShiftHandoffService shiftHandoffService;
    private final PredictiveMaintenanceService predictiveMaintenanceService;
    private final CrossSiteComparisonService crossSiteService;
    private final TrendForecastingService trendForecastingService;
    private final AutoIncidentReportService autoIncidentReportService;
    private final OptimalBatchSizingService optimalBatchSizingService;
    private final CostAnalysisService costAnalysisService;
    private final KnowledgeBaseSearchService knowledgeBaseSearchService;
    private final NotificationIntegrationService notificationService;
    private final ScheduledReportService scheduledReportService;
    private final ExportService exportService;
    private final FavoriteQueryService favoriteQueryService;
    private final VoiceCommandService voiceCommandService;
    private final AiProperties aiProperties;

    public AiController(
            AiChatService chatService,
            AiSummarizeService summarizeService,
            NaturalLanguageSearchService searchService,
            SmartAlertTriageService alertTriageService,
            SessionRecommendationService recommendationService,
            AnomalyDetectionService anomalyService,
            RootCauseAnalysisService rootCauseService,
            DailySummaryService dailySummaryService,
            PredictiveFailureService predictiveService,
            DataQualityScoreService qualityService,
            IntelligentRoutingService routingService,
            ShiftHandoffService shiftHandoffService,
            PredictiveMaintenanceService predictiveMaintenanceService,
            CrossSiteComparisonService crossSiteService,
            TrendForecastingService trendForecastingService,
            AutoIncidentReportService autoIncidentReportService,
            OptimalBatchSizingService optimalBatchSizingService,
            CostAnalysisService costAnalysisService,
            KnowledgeBaseSearchService knowledgeBaseSearchService,
            NotificationIntegrationService notificationService,
            ScheduledReportService scheduledReportService,
            ExportService exportService,
            FavoriteQueryService favoriteQueryService,
            VoiceCommandService voiceCommandService,
            AiProperties aiProperties) {
        this.chatService = chatService;
        this.summarizeService = summarizeService;
        this.searchService = searchService;
        this.alertTriageService = alertTriageService;
        this.recommendationService = recommendationService;
        this.anomalyService = anomalyService;
        this.rootCauseService = rootCauseService;
        this.dailySummaryService = dailySummaryService;
        this.predictiveService = predictiveService;
        this.qualityService = qualityService;
        this.routingService = routingService;
        this.shiftHandoffService = shiftHandoffService;
        this.predictiveMaintenanceService = predictiveMaintenanceService;
        this.crossSiteService = crossSiteService;
        this.trendForecastingService = trendForecastingService;
        this.autoIncidentReportService = autoIncidentReportService;
        this.optimalBatchSizingService = optimalBatchSizingService;
        this.costAnalysisService = costAnalysisService;
        this.knowledgeBaseSearchService = knowledgeBaseSearchService;
        this.notificationService = notificationService;
        this.scheduledReportService = scheduledReportService;
        this.exportService = exportService;
        this.favoriteQueryService = favoriteQueryService;
        this.voiceCommandService = voiceCommandService;
        this.aiProperties = aiProperties;
    }

    // ==================== Chat & Summarization ====================

    /**
     * Chat with the AI assistant.
     */
    @PostMapping("/chat")
    public ResponseEntity<AiChatResponse> chat(@RequestBody AiChatRequest request) {
        log.info("AI chat request: {}", truncate(request.getMessage(), 100));
        
        if (!aiProperties.isEnabled()) {
            return ResponseEntity.status(503).body(createDisabledResponse("AI features are disabled"));
        }
        
        if (!chatService.isAvailable()) {
            return ResponseEntity.status(503).body(createDisabledResponse("AI service is not configured"));
        }
        
        return ResponseEntity.ok(chatService.process(request));
    }

    /**
     * Summarize alerts using AI.
     */
    @PostMapping("/summarize/alerts")
    public ResponseEntity<AiSummarizeResponse> summarizeAlerts(@RequestBody AiSummarizeRequest request) {
        log.info("AI alert summarization request: {} alerts", 
                 request.getAlerts() != null ? request.getAlerts().size() : 0);
        
        if (!aiProperties.isEnabled()) {
            return ResponseEntity.status(503).body(createDisabledSummarizeResponse("AI features are disabled"));
        }
        
        if (!summarizeService.isAvailable()) {
            return ResponseEntity.status(503).body(createDisabledSummarizeResponse("AI service is not configured"));
        }
        
        return ResponseEntity.ok(summarizeService.summarizeAlerts(request));
    }

    // ==================== Natural Language Search ====================

    /**
     * Natural language search across staging data.
     */
    @PostMapping("/search")
    public ResponseEntity<NaturalLanguageSearchResponse> search(@RequestBody NaturalLanguageSearchRequest request) {
        log.info("Natural language search: {}", truncate(request.getQuery(), 50));
        
        if (!aiProperties.isEnabled()) {
            return ResponseEntity.status(503).body(createDisabledSearchResponse());
        }
        
        if (!searchService.isAvailable()) {
            return ResponseEntity.status(503).body(createDisabledSearchResponse());
        }
        
        return ResponseEntity.ok(searchService.search(request));
    }

    // ==================== Alert Triage ====================

    /**
     * Smart alert triage and prioritization.
     */
    @PostMapping("/alerts/triage")
    public ResponseEntity<AlertTriageResponse> triageAlerts(@RequestBody AlertTriageRequest request) {
        log.info("Alert triage request: {} alerts", 
                 request.getAlerts() != null ? request.getAlerts().size() : 0);
        
        if (!aiProperties.isEnabled()) {
            return ResponseEntity.status(503).body(createDisabledTriageResponse());
        }
        
        return ResponseEntity.ok(alertTriageService.triage(request));
    }

    // ==================== Session Recommendations ====================

    /**
     * Get session configuration recommendations.
     */
    @PostMapping("/recommendations/session")
    public ResponseEntity<SessionRecommendationResponse> getSessionRecommendations(
            @RequestBody SessionRecommendationRequest request) {
        log.info("Session recommendation request for site: {}", request.getSite());
        
        if (!aiProperties.isEnabled()) {
            return ResponseEntity.status(503).body(createDisabledRecommendationResponse());
        }
        
        return ResponseEntity.ok(recommendationService.getRecommendations(request));
    }

    // ==================== Anomaly Detection ====================

    /**
     * Detect anomalies in staging patterns.
     */
    @PostMapping("/anomaly/detect")
    public ResponseEntity<AnomalyDetectionResponse> detectAnomalies(@RequestBody AnomalyDetectionRequest request) {
        log.info("Anomaly detection request for site: {}", request.getSite());
        
        if (!aiProperties.isEnabled()) {
            return ResponseEntity.status(503).body(createDisabledAnomalyResponse());
        }
        
        return ResponseEntity.ok(anomalyService.detect(request));
    }

    // ==================== Root Cause Analysis ====================

    /**
     * Perform root cause analysis on failures.
     */
    @PostMapping("/analysis/root-cause")
    public ResponseEntity<RootCauseAnalysisResponse> analyzeRootCause(@RequestBody RootCauseAnalysisRequest request) {
        log.info("Root cause analysis for error: {}", request.getErrorCode());
        
        if (!aiProperties.isEnabled()) {
            return ResponseEntity.status(503).body(createDisabledRcaResponse());
        }
        
        return ResponseEntity.ok(rootCauseService.analyze(request));
    }

    // ==================== Daily Summary ====================

    /**
     * Generate daily summary report.
     */
    @PostMapping("/summary/daily")
    public ResponseEntity<DailySummaryResponse> getDailySummary(@RequestBody DailySummaryRequest request) {
        log.info("Daily summary request for date: {}", request.getDate());
        
        if (!aiProperties.isEnabled()) {
            return ResponseEntity.status(503).body(createDisabledSummaryResponse());
        }
        
        return ResponseEntity.ok(dailySummaryService.generateSummary(request));
    }

    // ==================== Predictive Failure ====================

    /**
     * Predict potential failures based on patterns.
     */
    @PostMapping("/predict/failure")
    public ResponseEntity<PredictiveFailureResponse> predictFailures(@RequestBody PredictiveFailureRequest request) {
        log.info("Predictive failure request for {} lots", 
                 request.getLotIds() != null ? request.getLotIds().size() : 0);
        
        if (!aiProperties.isEnabled()) {
            return ResponseEntity.status(503).body(createDisabledPredictiveResponse());
        }
        
        return ResponseEntity.ok(predictiveService.predict(request));
    }

    // ==================== Data Quality Score ====================

    /**
     * Score data quality before Exensio loading.
     */
    @PostMapping("/quality/score")
    public ResponseEntity<DataQualityScoreResponse> scoreDataQuality(@RequestBody DataQualityScoreRequest request) {
        log.info("Data quality scoring request for {} records", 
                 request.getRecords() != null ? request.getRecords().size() : 0);
        
        if (!aiProperties.isEnabled()) {
            return ResponseEntity.status(503).body(createDisabledQualityResponse());
        }
        
        return ResponseEntity.ok(qualityService.score(request));
    }

    // ==================== Intelligent Routing ====================

    /**
     * Get optimal routing recommendation.
     */
    @PostMapping("/routing/optimal")
    public ResponseEntity<IntelligentRoutingResponse> getOptimalRoute(@RequestBody IntelligentRoutingRequest request) {
        log.info("Intelligent routing request for site: {}", request.getSite());
        
        if (!aiProperties.isEnabled()) {
            return ResponseEntity.status(503).body(createDisabledRoutingResponse());
        }
        
        return ResponseEntity.ok(routingService.getOptimalRoute(request));
    }

    // ==================== Shift Handoff Summary ====================

    /**
     * Generate shift handoff summary.
     */
    @PostMapping("/handoff/summary")
    public ResponseEntity<Map<String, Object>> getShiftHandoffSummary(@RequestBody ShiftHandoffRequest request) {
        log.info("Shift handoff summary request for site: {}", request.getSite());
        
        if (!aiProperties.isEnabled()) {
            return ResponseEntity.status(503).body(Map.of("error", "AI features are disabled"));
        }
        
        ShiftHandoffResponse response = shiftHandoffService.generateHandoff(request);
        
        // Transform to frontend-friendly format
        Map<String, Object> result = new HashMap<>();
        result.put("shiftSummary", response.getSummary());
        result.put("outgoingOperator", response.getOutgoingOperator());
        result.put("shiftDate", response.getShiftDate());
        
        List<String> handoffNotes = new ArrayList<>();
        if (response.getHandoffNotes() != null) {
            handoffNotes.addAll(response.getHandoffNotes());
        }
        result.put("handoffNotes", handoffNotes);
        
        List<String> openIssues = new ArrayList<>();
        if (response.getOngoingIssues() != null) {
            for (var issue : response.getOngoingIssues()) {
                openIssues.add(issue.getIssue() + " [" + issue.getSeverity() + "]");
            }
        }
        result.put("openIssues", openIssues);
        
        List<String> criticalItems = new ArrayList<>();
        if (response.getCriticalAlerts() != null) {
            for (var alert : response.getCriticalAlerts()) {
                criticalItems.add(alert.getDescription() + " - " + alert.getStatus());
            }
        }
        if (response.getRecommendationsForIncoming() != null) {
            criticalItems.addAll(response.getRecommendationsForIncoming());
        }
        result.put("criticalItems", criticalItems);
        
        if (response.getFullBriefing() != null) {
            result.put("aiBriefing", response.getFullBriefing());
        }
        
        result.put("generatedAt", response.getHandoffGeneratedAt());
        
        return ResponseEntity.ok(result);
    }

    // ==================== Predictive Maintenance ====================

    /**
     * Get predictive maintenance recommendations.
     */
    @PostMapping("/maintenance/predict")
    public ResponseEntity<Map<String, Object>> getMaintenancePrediction(@RequestBody PredictiveMaintenanceRequest request) {
        log.info("Predictive maintenance request");
        
        if (!aiProperties.isEnabled()) {
            return ResponseEntity.status(503).body(Map.of("error", "AI features are disabled"));
        }
        
        PredictiveMaintenanceResponse maintResponse = predictiveMaintenanceService.analyze(request);
        Map<String, Object> maintResult = new HashMap<>();
        maintResult.put("riskAssessment", maintResponse.getInsights());
        
        List<Map<String, Object>> predictions = new ArrayList<>();
        if (maintResponse.getComponents() != null) {
            for (var comp : maintResponse.getComponents()) {
                Map<String, Object> pred = new HashMap<>();
                pred.put("equipmentId", comp.getComponentId());
                pred.put("equipmentType", comp.getComponentType());
                pred.put("predictedFailureDate", comp.getPredictedFailureTime());
                pred.put("confidence", comp.getFailureProbability());
                pred.put("riskLevel", comp.getStatus());
                pred.put("estimatedDowntime", "N/A");
                pred.put("recommendedAction", comp.getMaintenanceRecommendations() != null && !comp.getMaintenanceRecommendations().isEmpty() ? comp.getMaintenanceRecommendations().get(0) : "Monitor");
                pred.put("indicators", comp.getMaintenanceRecommendations() != null ? comp.getMaintenanceRecommendations() : List.of());
                predictions.add(pred);
            }
        }
        maintResult.put("predictions", predictions);
        maintResult.put("recommendations", maintResponse.getRecommendations() != null ? maintResponse.getRecommendations() : List.of());
        maintResult.put("priorityEquipment", List.of());
        maintResult.put("generatedAt", maintResponse.getAnalysisTimestamp());
        
        return ResponseEntity.ok(maintResult);
    }

    // ==================== Cross-Site Comparison ====================

    /**
     * Compare performance across sites.
     */
    @PostMapping("/comparison/sites")
    public ResponseEntity<Map<String, Object>> compareSites(@RequestBody CrossSiteComparisonRequest request) {
        log.info("Cross-site comparison request");
        
        if (!aiProperties.isEnabled()) {
            return ResponseEntity.status(503).body(Map.of("error", "AI features are disabled"));
        }
        
        var response = crossSiteService.compare(request);
        Map<String, Object> result = new HashMap<>();
        
        List<Map<String, Object>> comparison = new ArrayList<>();
        if (response.getSiteMetrics() != null) {
            int rank = 1;
            for (var entry : response.getSiteMetrics().entrySet()) {
                Map<String, Object> s = new HashMap<>();
                s.put("site", entry.getKey());
                s.put("metrics", entry.getValue());
                
                // Calculate performance score
                Map<String, Object> metrics = entry.getValue();
                double successRate = metrics.get("successRate") instanceof Number n ? n.doubleValue() : 0;
                double errorRate = metrics.get("errorRate") instanceof Number n ? n.doubleValue() : 0;
                double uptime = metrics.get("uptimePercentage") instanceof Number n ? n.doubleValue() : 0;
                double score = (successRate * 0.5 + (100 - errorRate) * 0.3 + uptime * 0.2);
                s.put("performanceScore", Math.min(100, score));
                s.put("rank", rank++);
                s.put("strengths", List.of("High throughput", "Stable operations"));
                s.put("weaknesses", List.of());
                comparison.add(s);
            }
        }
        result.put("comparison", comparison);
        result.put("overallBestPerformer", comparison.isEmpty() ? "" : ((Map<String, Object>) comparison.get(0)).get("site"));
        result.put("insights", response.getInsights());
        result.put("recommendations", response.getRecommendations() != null ? response.getRecommendations() : List.of());
        result.put("benchmarkMetrics", response.getSiteMetrics() != null ? new HashMap<>(response.getSiteMetrics()) : Map.of());
        result.put("generatedAt", response.getAnalysisTimestamp());
        
        return ResponseEntity.ok(result);
    }

    // ==================== Trend Forecasting ====================

    /**
     * Forecast trends for metrics.
     */
    @PostMapping("/trends/forecast")
    public ResponseEntity<Map<String, Object>> forecastTrends(@RequestBody TrendForecastingRequest request) {
        log.info("Trend forecasting request");
        
        if (!aiProperties.isEnabled()) {
            return ResponseEntity.status(503).body(Map.of("error", "AI features are disabled"));
        }
        
        var response = trendForecastingService.forecast(request);
        Map<String, Object> result = new HashMap<>();
        
        // Trends
        List<Map<String, Object>> trends = new ArrayList<>();
        if (response.getTrends() != null) {
            for (var t : response.getTrends()) {
                Map<String, Object> tr = new HashMap<>();
                tr.put("metric", t.getMetric());
                tr.put("direction", t.getDirection());
                tr.put("changePercent", t.getChangePercent());
                tr.put("averageValue", t.getAverageValue());
                tr.put("confidence", t.getConfidence());
                trends.add(tr);
            }
        }
        result.put("trends", trends);
        
        // Forecasts
        List<Map<String, Object>> forecasts = new ArrayList<>();
        if (response.getForecasts() != null) {
            for (var f : response.getForecasts()) {
                Map<String, Object> fc = new HashMap<>();
                fc.put("metric", f.getMetric());
                List<Map<String, Object>> points = new ArrayList<>();
                if (f.getPoints() != null) {
                    for (var p : f.getPoints()) {
                        Map<String, Object> pt = new HashMap<>();
                        pt.put("day", p.getDay());
                        pt.put("predictedValue", p.getPredictedValue());
                        pt.put("lowerBound", p.getLowerBound());
                        pt.put("upperBound", p.getUpperBound());
                        points.add(pt);
                    }
                }
                fc.put("points", points);
                fc.put("confidence", f.getConfidence());
                fc.put("method", f.getMethod());
                forecasts.add(fc);
            }
        }
        result.put("forecasts", forecasts);
        
        // Patterns
        List<Map<String, Object>> patterns = new ArrayList<>();
        if (response.getPatterns() != null) {
            for (var p : response.getPatterns()) {
                Map<String, Object> pat = new HashMap<>();
                pat.put("patternType", p.getPatternType());
                pat.put("description", p.getDescription());
                pat.put("confidence", p.getConfidence());
                pat.put("implication", p.getImplication());
                patterns.add(pat);
            }
        }
        result.put("patterns", patterns);
        
        // Peak predictions
        List<Map<String, Object>> peaks = new ArrayList<>();
        if (response.getPeakPredictions() != null) {
            for (var pp : response.getPeakPredictions()) {
                Map<String, Object> pk = new HashMap<>();
                pk.put("timeWindow", pp.getTimeWindow());
                pk.put("predictedLoad", pp.getPredictedLoad());
                pk.put("confidence", pp.getConfidence());
                pk.put("recommendations", pp.getRecommendations() != null ? pp.getRecommendations() : List.of());
                peaks.add(pk);
            }
        }
        result.put("peakPredictions", peaks);
        result.put("staffingRecommendations", response.getStaffingRecommendations() != null ? response.getStaffingRecommendations() : List.of());
        result.put("insights", response.getInsights());
        result.put("generatedAt", response.getGeneratedAt());
        
        return ResponseEntity.ok(result);
    }

    // ==================== Auto Incident Reports ====================

    /**
     * Generate incident report automatically.
     */
    @PostMapping("/incidents/generate")
    public ResponseEntity<Map<String, Object>> generateIncidentReport(@RequestBody AutoIncidentReportRequest request) {
        log.info("Auto incident report generation request");
        
        if (!aiProperties.isEnabled()) {
            return ResponseEntity.status(503).body(Map.of("error", "AI features are disabled"));
        }
        
        var response = autoIncidentReportService.generateReport(request);
        Map<String, Object> result = new HashMap<>();
        result.put("incidentId", response.getIncidentId());
        result.put("reportDate", response.getReportDate());
        result.put("severity", response.getSeverity());
        result.put("executiveSummary", response.getExecutiveSummary());
        
        List<Map<String, Object>> timeline = new ArrayList<>();
        if (response.getIncidentTimeline() != null) {
            for (var ev : response.getIncidentTimeline()) {
                Map<String, Object> e = new HashMap<>();
                e.put("time", ev.getTime());
                e.put("event", ev.getEvent());
                e.put("type", ev.getType());
                e.put("details", ev.getDetails());
                timeline.add(e);
            }
        }
        result.put("incidentTimeline", timeline);
        result.put("impactAnalysis", response.getImpactAnalysis() != null ? response.getImpactAnalysis() : Map.of());
        result.put("rootCauseDescription", response.getRootCauseDescription());
        
        List<Map<String, Object>> resolutionSteps = new ArrayList<>();
        if (response.getResolutionSteps() != null) {
            for (var rs : response.getResolutionSteps()) {
                Map<String, Object> s = new HashMap<>();
                s.put("step", rs.getStep());
                s.put("action", rs.getAction());
                s.put("result", rs.getResult());
                s.put("timeToComplete", rs.getTimeToComplete());
                resolutionSteps.add(s);
            }
        }
        result.put("resolutionSteps", resolutionSteps);
        result.put("lessonsLearned", response.getLessonsLearned() != null ? response.getLessonsLearned() : List.of());
        
        List<Map<String, Object>> actionItems = new ArrayList<>();
        if (response.getActionItems() != null) {
            for (var ai : response.getActionItems()) {
                Map<String, Object> a = new HashMap<>();
                a.put("action", ai.getAction());
                a.put("owner", ai.getOwner());
                a.put("dueDate", ai.getDueDate());
                a.put("priority", ai.getPriority());
                actionItems.add(a);
            }
        }
        result.put("actionItems", actionItems);
        result.put("preventionRecommendations", response.getPreventionRecommendations() != null ? response.getPreventionRecommendations() : List.of());
        result.put("complianceNotes", response.getComplianceNotes());
        result.put("reportGeneratedAt", response.getReportGeneratedAt());
        
        return ResponseEntity.ok(result);
    }

    // ==================== Optimal Batch Sizing ====================

    /**
     * Get optimal batch size recommendations.
     */
    @PostMapping("/batch/optimal")
    public ResponseEntity<Map<String, Object>> getOptimalBatchSize(@RequestBody OptimalBatchSizingRequest request) {
        log.info("Optimal batch sizing request for site: {}", request.getSite());
        
        if (!aiProperties.isEnabled()) {
            return ResponseEntity.status(503).body(Map.of("error", "AI features are disabled"));
        }
        
        var response = optimalBatchSizingService.getOptimalSize(request);
        Map<String, Object> result = new HashMap<>();
        result.put("optimalBatchSize", response.getOptimalBatchSize());
        result.put("minRecommendedSize", response.getMinRecommendedSize());
        result.put("maxRecommendedSize", response.getMaxRecommendedSize());
        result.put("currentAverageBatchSize", response.getCurrentAverageBatchSize());
        result.put("confidence", response.getConfidence());
        
        List<Map<String, Object>> sizeRecs = new ArrayList<>();
        if (response.getSizeRecommendations() != null) {
            for (var sr : response.getSizeRecommendations()) {
                Map<String, Object> s = new HashMap<>();
                s.put("label", sr.getLabel());
                s.put("batchSize", sr.getBatchSize());
                s.put("description", sr.getDescription());
                s.put("expectedSuccessRate", sr.getExpectedSuccessRate());
                sizeRecs.add(s);
            }
        }
        result.put("sizeRecommendations", sizeRecs);
        result.put("riskFactors", response.getRiskFactors() != null ? response.getRiskFactors() : List.of());
        
        return ResponseEntity.ok(result);
    }

    // ==================== Cost Analysis ====================

    /**
     * Analyze operation costs.
     */
    @PostMapping("/costs/analyze")
    public ResponseEntity<Map<String, Object>> analyzeCosts(@RequestBody CostAnalysisRequest request) {
        log.info("Cost analysis request for site: {}", request.getSite());
        
        if (!aiProperties.isEnabled()) {
            return ResponseEntity.status(503).body(Map.of("error", "AI features are disabled"));
        }
        
        var response = costAnalysisService.analyze(request);
        Map<String, Object> result = new HashMap<>();
        result.put("totalEstimatedCost", response.getTotalEstimatedCost());
        result.put("totalProcessingCost", response.getTotalProcessingCost());
        result.put("totalErrorCost", response.getTotalErrorCost());
        result.put("totalRetryCost", response.getTotalRetryCost());
        result.put("totalLaborCost", response.getTotalLaborCost());
        result.put("majorCostDrivers", response.getMajorCostDrivers() != null ? response.getMajorCostDrivers() : List.of());
        result.put("savingsOpportunities", response.getSavingsOpportunities() != null ? response.getSavingsOpportunities() : List.of());
        
        return ResponseEntity.ok(result);
    }

    // ==================== Knowledge Base Search ====================

    /**
     * Search knowledge base.
     */
    @PostMapping("/knowledge/search")
    public ResponseEntity<Map<String, Object>> searchKnowledge(@RequestBody KnowledgeBaseSearchRequest request) {
        log.info("Knowledge base search: {}", request.getQuery());
        
        if (!aiProperties.isEnabled()) {
            return ResponseEntity.status(503).body(Map.of("error", "AI features are disabled"));
        }
        
        var response = knowledgeBaseSearchService.search(request);
        Map<String, Object> result = new HashMap<>();
        result.put("aiSummary", response.getAiSummary());
        result.put("totalResults", response.getTotalResults());
        
        List<Map<String, Object>> results = new ArrayList<>();
        if (response.getResults() != null) {
            for (var r : response.getResults()) {
                Map<String, Object> res = new HashMap<>();
                res.put("title", r.getTitle());
                res.put("category", r.getCategory());
                res.put("summary", r.getSummary());
                res.put("relevanceScore", r.getRelevanceScore());
                results.add(res);
            }
        }
        result.put("results", results);
        
        return ResponseEntity.ok(result);
    }

    // ==================== Notifications ====================

    /**
     * Send notification to Slack/Teams/Email.
     */
    @PostMapping("/notifications/send")
    public ResponseEntity<Map<String, Object>> sendNotification(@RequestBody NotificationRequest request) {
        log.info("Notification send request: {}", request.getType());
        
        var response = notificationService.sendNotification(request);
        Map<String, Object> result = new HashMap<>();
        result.put("notificationId", response.getNotificationId());
        result.put("success", response.isSuccess());
        result.put("messagePreview", response.getMessagePreview());
        result.put("channelsConfigured", response.getChannelsConfigured() != null ? response.getChannelsConfigured() : List.of());
        
        return ResponseEntity.ok(result);
    }

    /**
     * Configure notification channels.
     */
    @PostMapping("/notifications/configure")
    public ResponseEntity<Map<String, Object>> configureNotifications(@RequestBody Map<String, Object> config) {
        log.info("Notification configuration request");
        
        return ResponseEntity.ok(notificationService.configureChannels(config));
    }

    // ==================== Scheduled Reports ====================

    /**
     * Create scheduled report.
     */
    @PostMapping("/reports/schedule")
    public ResponseEntity<Map<String, Object>> scheduleReport(@RequestBody ScheduledReportRequest request) {
        log.info("Scheduled report creation: {}", request.getReportName());
        
        return ResponseEntity.ok(scheduledReportService.createSchedule(request));
    }

    /**
     * Get all scheduled reports.
     */
    @GetMapping("/reports/schedules")
    public ResponseEntity<Map<String, Object>> getSchedules() {
        log.info("Get all scheduled reports");
        
        Map<String, Object> response = new HashMap<>();
        response.put("schedules", scheduledReportService.getAllSchedules());
        response.put("total", scheduledReportService.getAllSchedules().size());
        return ResponseEntity.ok(response);
    }

    /**
     * Generate report now.
     */
    @PostMapping("/reports/generate/{scheduleId}")
    public ResponseEntity<Map<String, Object>> generateReportNow(@PathVariable String scheduleId) {
        log.info("Generate report now: {}", scheduleId);
        
        return ResponseEntity.ok(scheduledReportService.generateNow(scheduleId));
    }

    // ==================== Export ====================

    /**
     * Export data with AI context.
     */
    @PostMapping("/export")
    public ResponseEntity<Map<String, Object>> exportData(@RequestBody ExportRequest request) {
        log.info("Export request: {} format", request.getFormat());
        
        var response = exportService.generateExport(request);
        Map<String, Object> result = new HashMap<>();
        result.put("exportId", response.getExportId());
        result.put("rowCount", response.getRowCount());
        result.put("aiContextSummary", response.getAiContextSummary());
        result.put("aiInsights", response.getAiInsights() != null ? response.getAiInsights() : List.of());
        result.put("chartSuggestions", response.getChartSuggestions() != null ? response.getChartSuggestions() : List.of());
        result.put("columns", response.getColumns() != null ? response.getColumns() : List.of());
        
        return ResponseEntity.ok(result);
    }

    // ==================== Favorite Queries ====================

    /**
     * Save a query as favorite.
     */
    @PostMapping("/favorites/save")
    public ResponseEntity<Map<String, Object>> saveFavorite(@RequestBody FavoriteQueryRequest request) {
        log.info("Save favorite query: {}", request.getName());
        
        return ResponseEntity.ok(favoriteQueryService.saveFavorite(request));
    }

    /**
     * Get all favorites.
     */
    @GetMapping("/favorites/{userId}")
    public ResponseEntity<Map<String, Object>> getFavorites(@PathVariable String userId) {
        log.info("Get favorites for user: {}", userId);
        
        Map<String, Object> response = new HashMap<>();
        response.put("favorites", favoriteQueryService.getFavorites(userId));
        return ResponseEntity.ok(response);
    }

    /**
     * Delete a favorite.
     */
    @DeleteMapping("/favorites/{userId}/{queryId}")
    public ResponseEntity<Map<String, Object>> deleteFavorite(@PathVariable String userId, @PathVariable String queryId) {
        log.info("Delete favorite: {} for user: {}", queryId, userId);
        
        boolean deleted = favoriteQueryService.deleteFavorite(userId, queryId);
        Map<String, Object> response = new HashMap<>();
        response.put("success", deleted);
        response.put("message", deleted ? "Favorite deleted" : "Favorite not found");
        return ResponseEntity.ok(response);
    }

    // ==================== Voice Commands ====================

    /**
     * Process voice command.
     */
    @PostMapping("/voice/command")
    public ResponseEntity<Map<String, Object>> processVoiceCommand(@RequestBody VoiceCommandRequest request) {
        log.info("Voice command: {}", truncate(request.getCommand(), 50));
        
        return ResponseEntity.ok(voiceCommandService.processCommand(request));
    }

    /**
     * Get voice command help.
     */
    @GetMapping("/voice/help")
    public ResponseEntity<Map<String, Object>> getVoiceHelp() {
        return ResponseEntity.ok(voiceCommandService.getHelp());
    }

    // ==================== Status & Health ====================

    /**
     * Get AI service status.
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("enabled", aiProperties.isEnabled());
        status.put("configured", aiProperties.isConfigured());
        status.put("provider", aiProperties.getProvider());
        status.put("model", aiProperties.getModel());
        status.put("chatAvailable", chatService.isAvailable());
        status.put("searchAvailable", searchService.isAvailable());
        status.put("alertTriageAvailable", alertTriageService.isAvailable());
        status.put("recommendationAvailable", recommendationService.isAvailable());
        status.put("anomalyAvailable", anomalyService.isAvailable());
        status.put("rootCauseAvailable", rootCauseService.isAvailable());
        status.put("dailySummaryAvailable", dailySummaryService.isAvailable());
        status.put("predictiveAvailable", predictiveService.isAvailable());
        status.put("qualityScoreAvailable", qualityService.isAvailable());
        status.put("routingAvailable", routingService.isAvailable());
        // New feature availability
        status.put("shiftHandoffAvailable", shiftHandoffService.isAvailable());
        status.put("predictiveMaintenanceAvailable", predictiveMaintenanceService.isAvailable());
        status.put("crossSiteComparisonAvailable", crossSiteService.isAvailable());
        status.put("trendForecastingAvailable", trendForecastingService.isAvailable());
        status.put("autoIncidentReportAvailable", autoIncidentReportService.isAvailable());
        status.put("optimalBatchSizingAvailable", optimalBatchSizingService.isAvailable());
        status.put("costAnalysisAvailable", costAnalysisService.isAvailable());
        status.put("knowledgeBaseSearchAvailable", knowledgeBaseSearchService.isAvailable());
        status.put("notificationAvailable", notificationService.isAvailable());
        status.put("scheduledReportsAvailable", scheduledReportService.isAvailable());
        status.put("exportAvailable", exportService.isAvailable());
        status.put("favoriteQueriesAvailable", favoriteQueryService != null);
        status.put("voiceCommandsAvailable", voiceCommandService.isAvailable());
        
        return ResponseEntity.ok(status);
    }

    /**
     * Health check endpoint.
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> health = new HashMap<>();
        boolean healthy = aiProperties.isEnabled() && aiProperties.isConfigured();
        
        health.put("status", healthy ? "UP" : "DOWN");
        health.put("aiEnabled", aiProperties.isEnabled());
        health.put("aiConfigured", aiProperties.isConfigured());
        health.put("provider", aiProperties.getProvider());
        
        if (!healthy) {
            health.put("message", "AI is not properly configured. Set ai.enabled=true and ai.api-key environment variable.");
        }
        
        return ResponseEntity.ok(health);
    }

    /**
     * Clear conversation history.
     */
    @DeleteMapping("/conversation/{conversationId}")
    public ResponseEntity<Map<String, Object>> clearConversation(@PathVariable String conversationId) {
        log.info("Clearing conversation: {}", conversationId);
        chatService.clearConversation(conversationId);
        
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("conversationId", conversationId);
        response.put("message", "Conversation history cleared");
        
        return ResponseEntity.ok(response);
    }

    // ==================== Helper Methods ====================

    private AiChatResponse createDisabledResponse(String message) {
        AiChatResponse response = new AiChatResponse();
        response.setReply(message + " Please contact your administrator to enable AI features.");
        response.setConfidence(0.0);
        return response;
    }

    private AiSummarizeResponse createDisabledSummarizeResponse(String message) {
        AiSummarizeResponse response = new AiSummarizeResponse();
        response.setSummary(message + " Please contact your administrator to enable AI features.");
        response.setPriority("UNKNOWN");
        response.setTotalAlerts(0);
        return response;
    }

    private NaturalLanguageSearchResponse createDisabledSearchResponse() {
        NaturalLanguageSearchResponse response = new NaturalLanguageSearchResponse();
        response.setSummary("Search is not available. Please enable AI features.");
        response.setTotalResults(0);
        return response;
    }

    private AlertTriageResponse createDisabledTriageResponse() {
        AlertTriageResponse response = new AlertTriageResponse();
        response.setTriageSummary("Alert triage is not available.");
        response.setOverallPriority("UNKNOWN");
        return response;
    }

    private SessionRecommendationResponse createDisabledRecommendationResponse() {
        SessionRecommendationResponse response = new SessionRecommendationResponse();
        response.setRecommendationSummary("Recommendations are not available.");
        return response;
    }

    private AnomalyDetectionResponse createDisabledAnomalyResponse() {
        AnomalyDetectionResponse response = new AnomalyDetectionResponse();
        response.setAnomaliesDetected(false);
        return response;
    }

    private RootCauseAnalysisResponse createDisabledRcaResponse() {
        RootCauseAnalysisResponse response = new RootCauseAnalysisResponse();
        response.setPrimaryCause("Analysis not available");
        return response;
    }

    private DailySummaryResponse createDisabledSummaryResponse() {
        DailySummaryResponse response = new DailySummaryResponse();
        response.setSummary("Daily summary is not available.");
        return response;
    }

    private PredictiveFailureResponse createDisabledPredictiveResponse() {
        PredictiveFailureResponse response = new PredictiveFailureResponse();
        response.setPredictionsAvailable(false);
        return response;
    }

    private DataQualityScoreResponse createDisabledQualityResponse() {
        DataQualityScoreResponse response = new DataQualityScoreResponse();
        response.setGrade("N/A");
        response.setOverallScore(0.0);
        return response;
    }

    private IntelligentRoutingResponse createDisabledRoutingResponse() {
        IntelligentRoutingResponse response = new IntelligentRoutingResponse();
        response.setRecommendedRoute("DEFAULT");
        response.setAutoRouteEnabled(false);
        return response;
    }

    private String truncate(String text, int maxLength) {
        if (text == null) return null;
        return text.length() > maxLength ? text.substring(0, maxLength) + "..." : text;
    }
}