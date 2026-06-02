package com.onsemi.cim.apps.exensio.exensioreload.dto.ai;

import java.util.List;

/**
 * Response for favorite query operations.
 */
public class FavoriteQueryResponse {
    private String id;
    private boolean success;
    private String message;
    private List<FavoriteQuery> favorites;

    public static class FavoriteQuery {
        private String id;
        private String name;
        private String description;
        private String query;
        private boolean aiEnhanced;
        private String createdAt;
        private int usageCount;
        private List<String> tags;

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public String getQuery() { return query; }
        public void setQuery(String query) { this.query = query; }
        public boolean isAiEnhanced() { return aiEnhanced; }
        public void setAiEnhanced(boolean aiEnhanced) { this.aiEnhanced = aiEnhanced; }
        public String getCreatedAt() { return createdAt; }
        public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
        public int getUsageCount() { return usageCount; }
        public void setUsageCount(int usageCount) { this.usageCount = usageCount; }
        public List<String> getTags() { return tags; }
        public void setTags(List<String> tags) { this.tags = tags; }
    }

    // Getters and setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public List<FavoriteQuery> getFavorites() { return favorites; }
    public void setFavorites(List<FavoriteQuery> favorites) { this.favorites = favorites; }
}