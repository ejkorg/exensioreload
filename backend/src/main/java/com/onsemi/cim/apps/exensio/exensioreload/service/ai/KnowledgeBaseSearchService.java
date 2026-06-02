package com.onsemi.cim.apps.exensio.exensioreload.service.ai;

import com.onsemi.cim.apps.exensio.exensioreload.config.AiProperties;
import com.onsemi.cim.apps.exensio.exensioreload.dto.ai.KnowledgeBaseSearchRequest;
import com.onsemi.cim.apps.exensio.exensioreload.dto.ai.KnowledgeBaseSearchResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Service for searching knowledge base and documentation.
 */
@Service
public class KnowledgeBaseSearchService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseSearchService.class);

    private final AiGatewayService gatewayService;
    private final AiProperties aiProperties;

    // Simulated knowledge base
    private static final List<Map<String, String>> KNOWLEDGE_BASE = List.of(
        Map.of("id", "KB001", "title", "Connection Timeout Troubleshooting",
            "category", "Network", "content", "When experiencing connection timeouts, check: 1) Network connectivity 2) Firewall rules 3) Connection pool settings 4) Timeout values. Recommended timeout: 30 seconds for standard operations."),
        Map.of("id", "KB002", "title", "Authentication Failure Resolution",
            "category", "Security", "content", "Auth failures typically occur due to: expired tokens, incorrect credentials, or service account issues. Verify: 1) Token expiration 2) API key validity 3) Service account permissions"),
        Map.of("id", "KB003", "title", "Exensio Integration Best Practices",
            "category", "Integration", "content", "For optimal Exensio integration: 1) Batch sizes 100-150 records 2) Retry with exponential backoff 3) Monitor queue depth 4) Validate data before submission"),
        Map.of("id", "KB004", "title", "Lot Processing Error Codes",
            "category", "Reference", "content", "E001: Invalid lot format. E002: Lot not found in master. E003: Duplicate lot. E004: Missing wafer data. E005: Validation failed. E006: Exensio API error. E007: Network timeout."),
        Map.of("id", "KB005", "title", "Maintenance Window Procedures",
            "category", "Operations", "content", "During maintenance: 1) Notify operators 2) Pause new processing 3) Complete pending batches 4) Document configuration 5) Verify system health after restart"),
        Map.of("id", "KB006", "title", "Performance Optimization Guide",
            "category", "Performance", "content", "To improve performance: 1) Increase batch size (max 200) 2) Enable connection pooling 3) Optimize query indexes 4) Reduce log verbosity 5) Use compression for large transfers"),
        Map.of("id", "KB007", "title", "Data Validation Rules",
            "category", "Data Quality", "content", "Required fields: lot_id, wafer_id, sender_id, timestamp. Format rules: lot_id (3-20 alphanumeric), wafer_id (W followed by 2 digits), timestamp (ISO 8601)."),
        Map.of("id", "KB008", "title", "Incident Response Procedures",
            "category", "Operations", "content", "For incidents: 1) Assess severity 2) Notify on-call 3) Document timeline 4) Implement fix 5) Verify resolution 6) Generate report. Escalation matrix: P1->15min, P2->1hr, P3->4hr, P4->next day.")
    );

    public KnowledgeBaseSearchService(AiGatewayService gatewayService, AiProperties aiProperties) {
        this.gatewayService = gatewayService;
        this.aiProperties = aiProperties;
    }

    public boolean isAvailable() {
        return aiProperties.isConfigured();
    }

    /**
     * Search knowledge base.
     */
    public KnowledgeBaseSearchResponse search(KnowledgeBaseSearchRequest request) {
        KnowledgeBaseSearchResponse response = new KnowledgeBaseSearchResponse();

        try {
            String query = request.getQuery().toLowerCase();

            // Search knowledge base
            List<KnowledgeBaseSearchResponse.KnowledgeResult> results = searchKnowledgeBase(query, request.getCategory());
            response.setResults(results);
            response.setTotalResults(results.size());

            // Generate AI summary if query is complex
            if (aiProperties.isConfigured() && results.size() > 0) {
                response.setAiSummary(generateAISummary(query, results));
            }

            // Get related topics
            response.setRelatedTopics(findRelatedTopics(query, results));

            response.setSearchTimestamp(System.currentTimeMillis());

        } catch (Exception e) {
            log.error("Knowledge base search failed", e);
        }

        return response;
    }

    private List<KnowledgeBaseSearchResponse.KnowledgeResult> searchKnowledgeBase(String query, String category) {
        List<KnowledgeBaseSearchResponse.KnowledgeResult> results = new ArrayList<>();

        for (Map<String, String> kb : KNOWLEDGE_BASE) {
            // Filter by category if specified
            if (category != null && !kb.get("category").equalsIgnoreCase(category)) {
                continue;
            }

            // Score relevance
            String title = kb.get("title").toLowerCase();
            String content = kb.get("content").toLowerCase();

            int score = 0;
            if (title.contains(query)) score += 10;
            else if (title.contains(query.split(" ")[0])) score += 5;

            if (content.contains(query)) score += 3;
            for (String word : query.split(" ")) {
                if (content.contains(word)) score += 1;
            }

            if (score > 0) {
                KnowledgeBaseSearchResponse.KnowledgeResult result = new KnowledgeBaseSearchResponse.KnowledgeResult();
                result.setId(kb.get("id"));
                result.setTitle(kb.get("title"));
                result.setCategory(kb.get("category"));
                result.setRelevanceScore(score);
                result.setSummary(extractSummary(kb.get("content"), query));
                result.setContent(kb.get("content"));
                result.setRelatedActions(getRelatedActions(kb));
                results.add(result);
            }
        }

        // Sort by relevance
        results.sort((a, b) -> Integer.compare(b.getRelevanceScore(), a.getRelevanceScore()));

        return results;
    }

    private String extractSummary(String content, String query) {
        // Extract first relevant sentence
        String[] sentences = content.split("\\. ");
        for (String sentence : sentences) {
            if (sentence.toLowerCase().contains(query.split(" ")[0])) {
                return sentence + ".";
            }
        }
        return sentences[0] + ".";
    }

    private List<String> getRelatedActions(Map<String, String> kb) {
        List<String> actions = new ArrayList<>();
        String category = kb.get("category");

        actions.add("View full article");
        actions.add("Mark as helpful");

        switch (category) {
            case "Network":
                actions.add("Run diagnostics");
                break;
            case "Security":
                actions.add("Check credentials");
                break;
            case "Reference":
                actions.add("View error code");
                break;
            case "Operations":
                actions.add("Start procedure");
                break;
        }

        return actions;
    }

    private List<String> findRelatedTopics(String query, List<KnowledgeBaseSearchResponse.KnowledgeResult> results) {
        Set<String> topics = new LinkedHashSet<>();

        for (KnowledgeBaseSearchResponse.KnowledgeResult result : results) {
            topics.add(result.getCategory());
        }

        // Add common related topics
        if (query.contains("error") || query.contains("fail")) {
            topics.add("Troubleshooting");
            topics.add("Error Codes");
        }
        if (query.contains("timeout") || query.contains("slow")) {
            topics.add("Performance");
            topics.add("Network");
        }
        if (query.contains("auth") || query.contains("login")) {
            topics.add("Security");
        }

        return new ArrayList<>(topics);
    }

    private String generateAISummary(String query, List<KnowledgeBaseSearchResponse.KnowledgeResult> results) {
        try {
            String prompt = String.format("""
                Summarize the relevant knowledge for: "%s"
                
                Found %d relevant articles:
                %s
                
                Provide a brief 2-3 sentence summary with actionable advice.
                """,
                query,
                results.size(),
                results.stream()
                    .map(r -> "- " + r.getTitle() + ": " + r.getSummary())
                    .reduce((a, b) -> a + "\n" + b)
                    .orElse("")
            );

            Map<String, Object> context = Map.of("task", "knowledge_search");
            return gatewayService.sendMessage(prompt, context);
        } catch (Exception e) {
            return results.get(0).getSummary();
        }
    }
}