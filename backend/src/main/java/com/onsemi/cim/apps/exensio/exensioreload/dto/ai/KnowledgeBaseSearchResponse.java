package com.onsemi.cim.apps.exensio.exensioreload.dto.ai;

import java.util.List;

/**
 * Response for knowledge base search.
 */
public class KnowledgeBaseSearchResponse {
    private List<KnowledgeResult> results;
    private int totalResults;
    private String aiSummary;
    private List<String> relatedTopics;
    private long searchTimestamp;

    public static class KnowledgeResult {
        private String id;
        private String title;
        private String category;
        private double relevanceScore;
        private String summary;
        private String content;
        private List<String> relatedActions;

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        public String getCategory() { return category; }
        public void setCategory(String category) { this.category = category; }
        public double getRelevanceScore() { return relevanceScore; }
        public void setRelevanceScore(double relevanceScore) { this.relevanceScore = relevanceScore; }
        public String getSummary() { return summary; }
        public void setSummary(String summary) { this.summary = summary; }
        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }
        public List<String> getRelatedActions() { return relatedActions; }
        public void setRelatedActions(List<String> relatedActions) { this.relatedActions = relatedActions; }
    }

    // Getters and setters
    public List<KnowledgeResult> getResults() { return results; }
    public void setResults(List<KnowledgeResult> results) { this.results = results; }
    public int getTotalResults() { return totalResults; }
    public void setTotalResults(int totalResults) { this.totalResults = totalResults; }
    public String getAiSummary() { return aiSummary; }
    public void setAiSummary(String aiSummary) { this.aiSummary = aiSummary; }
    public List<String> getRelatedTopics() { return relatedTopics; }
    public void setRelatedTopics(List<String> relatedTopics) { this.relatedTopics = relatedTopics; }
    public long getSearchTimestamp() { return searchTimestamp; }
    public void setSearchTimestamp(long searchTimestamp) { this.searchTimestamp = searchTimestamp; }
}