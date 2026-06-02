package com.onsemi.cim.apps.exensio.exensioreload.dto.ai;

import java.util.List;
import java.util.Map;

/**
 * Request for natural language search.
 */
public class NaturalLanguageSearchRequest {
    private String query;
    private List<String> sites;
    private Integer limit;
    private Map<String, Object> context;

    public NaturalLanguageSearchRequest() {}

    public String getQuery() { return query; }
    public void setQuery(String query) { this.query = query; }

    public List<String> getSites() { return sites; }
    public void setSites(List<String> sites) { this.sites = sites; }

    public Integer getLimit() { return limit; }
    public void setLimit(Integer limit) { this.limit = limit; }

    public Map<String, Object> getContext() { return context; }
    public void setContext(Map<String, Object> context) { this.context = context; }
}