package com.onsemi.cim.apps.exensio.exensioreload.dto.ai;

/**
 * Request for knowledge base search.
 */
public class KnowledgeBaseSearchRequest {
    private String query;
    private String category;  // Network, Security, Integration, Reference, Operations, Performance, Data Quality
    private int limit = 10;

    public String getQuery() { return query; }
    public void setQuery(String query) { this.query = query; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public int getLimit() { return limit; }
    public void setLimit(int limit) { this.limit = limit; }
}