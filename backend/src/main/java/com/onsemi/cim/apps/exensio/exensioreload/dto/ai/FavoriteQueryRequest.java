package com.onsemi.cim.apps.exensio.exensioreload.dto.ai;

import java.util.List;

/**
 * Request for favorite query operations.
 */
public class FavoriteQueryRequest {
    private String name;
    private String description;
    private String query;
    private boolean aiEnhanced = true;
    private List<String> tags;
    private String userId = "default";

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getQuery() { return query; }
    public void setQuery(String query) { this.query = query; }
    public boolean isAiEnhanced() { return aiEnhanced; }
    public void setAiEnhanced(boolean aiEnhanced) { this.aiEnhanced = aiEnhanced; }
    public List<String> getTags() { return tags; }
    public void setTags(List<String> tags) { this.tags = tags; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
}