package com.onsemi.cim.apps.exensio.exensioreload.service.ai;

import com.onsemi.cim.apps.exensio.exensioreload.config.AiProperties;
import com.onsemi.cim.apps.exensio.exensioreload.dto.ai.NaturalLanguageSearchRequest;
import com.onsemi.cim.apps.exensio.exensioreload.dto.ai.NaturalLanguageSearchResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Service for natural language search across staging data.
 * Converts natural language queries into structured SQL and returns formatted results.
 */
@Service
public class NaturalLanguageSearchService {

    private static final Logger log = LoggerFactory.getLogger(NaturalLanguageSearchService.class);

    private final AiGatewayService gatewayService;
    private final AiProperties aiProperties;

    // Query patterns for common manufacturing data queries
    private static final Map<String, String> STATUS_KEYWORDS = Map.ofEntries(
        Map.entry("failed", "FAILED"),
        Map.entry("error", "FAILED"),
        Map.entry("success", "DONE"),
        Map.entry("completed", "DONE"),
        Map.entry("pending", "ENQUEUED_LOCAL"),
        Map.entry("processing", "EXENSIO_LOADING"),
        Map.entry("pending", "pending"),
        Map.entry("staging", "ENRICHMENT")
    );

    private static final Map<String, String> TIME_KEYWORDS = Map.ofEntries(
        Map.entry("today", "TRUNC(SYSDATE)"),
        Map.entry("yesterday", "TRUNC(SYSDATE) - 1"),
        Map.entry("last 24 hours", "SYSDATE - 1"),
        Map.entry("last week", "SYSDATE - 7"),
        Map.entry("last month", "SYSDATE - 30"),
        Map.entry("this week", "TRUNC(SYSDATE, 'IW')"),
        Map.entry("this month", "TRUNC(SYSDATE, 'MM')")
    );

    public NaturalLanguageSearchService(AiGatewayService gatewayService, AiProperties aiProperties) {
        this.gatewayService = gatewayService;
        this.aiProperties = aiProperties;
    }

    public boolean isAvailable() {
        return aiProperties.isConfigured();
    }

    /**
     * Process natural language search query.
     */
    public NaturalLanguageSearchResponse search(NaturalLanguageSearchRequest request) {
        long startTime = System.currentTimeMillis();
        NaturalLanguageSearchResponse response = new NaturalLanguageSearchResponse();

        try {
            // Interpret the query using AI
            String interpretedQuery = interpretQuery(request.getQuery());
            response.setInterpretedQuery(interpretedQuery);

            // Generate SQL from interpreted query
            String sql = generateSql(interpretedQuery, request);
            response.setSqlGenerated(sql);

            // Execute the search (simplified - actual implementation would query database)
            List<Map<String, Object>> results = executeSearch(sql, request);
            response.setResults(results);
            response.setTotalResults(results.size());

            // Generate summary
            String summary = generateSummary(results, interpretedQuery);
            response.setSummary(summary);

            // Generate suggestions
            List<String> suggestions = generateSuggestions(results, request.getQuery());
            response.setSuggestions(suggestions);

            response.setQueryTimeMs(System.currentTimeMillis() - startTime);

        } catch (Exception e) {
            log.error("Natural language search failed", e);
            response.setSummary("Search failed: " + e.getMessage());
            response.setSuggestions(List.of("Try rephrasing your question", "Use specific lot or sender IDs"));
        }

        return response;
    }

    /**
     * Interpret natural language query into structured parameters.
     */
    private String interpretQuery(String query) {
        if (!aiProperties.isConfigured()) {
            return query;
        }

        String systemPrompt = """
            You are a query interpreter for a semiconductor manufacturing data system.
            
            Interpret the user's natural language query and extract:
            1. Intent (search, filter, aggregate, etc.)
            2. Entity types (lot, wafer, sender, session)
            3. Filters (status, date range, site)
            4. Output format expected
            
            Return a structured interpretation in plain text.
            
            Example:
            Input: "show me all failed lots from fab A yesterday"
            Output: "SEARCH lots WHERE site='FAB_A' AND status='FAILED' AND date='yesterday'"
            
            Input: """ + query + """
            Output:
            """;

        try {
            Map<String, Object> context = Map.of("intent", "interpret_query");
            String result = gatewayService.sendMessage(systemPrompt, context);
            return result != null ? result : query;
        } catch (Exception e) {
            log.warn("Failed to interpret query with AI, using raw query", e);
            return query;
        }
    }

    /**
     * Generate SQL from interpreted query.
     */
    private String generateSql(String interpretedQuery, NaturalLanguageSearchRequest request) {
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT * FROM SENDER_STAGE WHERE 1=1");

        // Parse common patterns
        String query = request.getQuery().toLowerCase();

        // Status filter
        for (Map.Entry<String, String> entry : STATUS_KEYWORDS.entrySet()) {
            if (query.contains(entry.getKey())) {
                sql.append(" AND STATUS = '").append(entry.getValue()).append("'");
                break;
            }
        }

        // Time filter
        for (Map.Entry<String, String> entry : TIME_KEYWORDS.entrySet()) {
            if (query.contains(entry.getKey())) {
                sql.append(" AND END_TIME >= ").append(entry.getValue());
                break;
            }
        }

        // Site filter
        if (query.contains("site") || query.contains("fab")) {
            sql.append(" AND SITE = '").append(extractSite(query)).append("'");
        }

        // Lot filter
        if (query.contains("lot")) {
            String lot = extractLot(query);
            if (lot != null) {
                sql.append(" AND LOT LIKE '%").append(lot).append("%'");
            }
        }

        // Sender filter
        if (query.contains("sender")) {
            sql.append(" AND SENDER_ID = '").append(extractSender(query)).append("'");
        }

        // Limit
        sql.append(" AND ROWNUM <= ").append(request.getLimit() != null ? request.getLimit() : 100);

        return sql.toString();
    }

    /**
     * Execute search query against database.
     */
    private List<Map<String, Object>> executeSearch(String sql, NaturalLanguageSearchRequest request) {
        // This would typically use JdbcTemplate or JPA to execute the query
        // For now, return simulated results based on the query
        List<Map<String, Object>> results = new ArrayList<>();

        // In production, this would query the actual database
        // Example with JdbcTemplate:
        // return jdbcTemplate.queryForList(sql);

        log.info("Executing search: {}", sql);
        return results;
    }

    /**
     * Generate human-readable summary of results.
     */
    private String generateSummary(List<Map<String, Object>> results, String interpretedQuery) {
        if (results.isEmpty()) {
            return "No records found matching your criteria.";
        }

        int count = results.size();
        String entityType = interpretedQuery.contains("lot") ? "lots" : "records";

        return String.format("Found %d %s matching your search criteria.", count, entityType);
    }

    /**
     * Generate follow-up suggestions.
     */
    private List<String> generateSuggestions(List<Map<String, Object>> results, String originalQuery) {
        List<String> suggestions = new ArrayList<>();

        if (results.size() > 10) {
            suggestions.add("Try adding a date range to narrow results");
        }

        if (originalQuery.toLowerCase().contains("failed")) {
            suggestions.add("Run root cause analysis on failed records");
        }

        suggestions.add("Export results to CSV for further analysis");
        suggestions.add("Set up alerts for similar patterns");

        return suggestions;
    }

    private String extractSite(String query) {
        // Simple extraction - in production would use AI
        if (query.contains("fab a")) return "FAB_A";
        if (query.contains("fab b")) return "FAB_B";
        return "ALL";
    }

    private String extractLot(String query) {
        // Extract lot ID pattern - in production would use AI
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("lot\\s*(\\w+)", java.util.regex.Pattern.CASE_INSENSITIVE);
        java.util.regex.Matcher matcher = pattern.matcher(query);
        return matcher.find() ? matcher.group(1) : null;
    }

    private String extractSender(String query) {
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("sender\\s*(\\w+)", java.util.regex.Pattern.CASE_INSENSITIVE);
        java.util.regex.Matcher matcher = pattern.matcher(query);
        return matcher.find() ? matcher.group(1) : null;
    }
}