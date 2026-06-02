package com.onsemi.cim.apps.exensio.exensioreload.dto.ai;

import java.util.List;
import java.util.Map;

/**
 * Response for natural language search.
 */
public class NaturalLanguageSearchResponse {
    private String interpretedQuery;
    private String sqlGenerated;
    private int totalResults;
    private List<Map<String, Object>> results;
    private String summary;
    private List<String> suggestions;
    private long queryTimeMs;

    public NaturalLanguageSearchResponse() {}

    public String getInterpretedQuery() { return interpretedQuery; }
    public void setInterpretedQuery(String interpretedQuery) { this.interpretedQuery = interpretedQuery; }

    public String getSqlGenerated() { return sqlGenerated; }
    public void setSqlGenerated(String sqlGenerated) { this.sqlGenerated = sqlGenerated; }

    public int getTotalResults() { return totalResults; }
    public void setTotalResults(int totalResults) { this.totalResults = totalResults; }

    public List<Map<String, Object>> getResults() { return results; }
    public void setResults(List<Map<String, Object>> results) { this.results = results; }

    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }

    public List<String> getSuggestions() { return suggestions; }
    public void setSuggestions(List<String> suggestions) { this.suggestions = suggestions; }

    public long getQueryTimeMs() { return queryTimeMs; }
    public void setQueryTimeMs(long queryTimeMs) { this.queryTimeMs = queryTimeMs; }
}