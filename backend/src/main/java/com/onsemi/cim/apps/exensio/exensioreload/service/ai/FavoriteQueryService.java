package com.onsemi.cim.apps.exensio.exensioreload.service.ai;

import com.onsemi.cim.apps.exensio.exensioreload.dto.ai.FavoriteQueryRequest;
import com.onsemi.cim.apps.exensio.exensioreload.dto.ai.FavoriteQueryResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service for managing favorite/saved queries.
 */
@Service
public class FavoriteQueryService {

    private static final Logger log = LoggerFactory.getLogger(FavoriteQueryService.class);

    // Simulated storage - in production would use database
    private static final Map<String, List<FavoriteQueryResponse.FavoriteQuery>> FAVORITE_QUERIES = new ConcurrentHashMap<>();

    static {
        // Initialize with some example favorites
        FAVORITE_QUERIES.put("default", List.of(
            createFavorite("SCHED001", "Daily Summary", "Get today's operations summary", "daily_summary", true),
            createFavorite("SCHED002", "Error Analysis", "Analyze recent errors and patterns", "error_analysis", true),
            createFavorite("SCHED003", "Performance Report", "Generate performance metrics", "performance_report", false)
        ));
    }

    private static FavoriteQueryResponse.FavoriteQuery createFavorite(String id, String name, String description, String query, boolean isAiEnhanced) {
        FavoriteQueryResponse.FavoriteQuery query_obj = new FavoriteQueryResponse.FavoriteQuery();
        query_obj.setId(id);
        query_obj.setName(name);
        query_obj.setDescription(description);
        query_obj.setQuery(query);
        query_obj.setAiEnhanced(isAiEnhanced);
        query_obj.setCreatedAt(new Date().toString());
        query_obj.setUsageCount(0);
        query_obj.setTags(List.of("summary", "daily"));
        return query_obj;
    }

    /**
     * Save a query as favorite.
     */
    public FavoriteQueryResponse saveFavorite(FavoriteQueryRequest request) {
        FavoriteQueryResponse response = new FavoriteQueryResponse();

        try {
            String queryId = "FAV-" + System.currentTimeMillis();
            FavoriteQueryResponse.FavoriteQuery favorite = new FavoriteQueryResponse.FavoriteQuery();

            favorite.setId(queryId);
            favorite.setName(request.getName());
            favorite.setDescription(request.getDescription());
            favorite.setQuery(request.getQuery());
            favorite.setAiEnhanced(request.isAiEnhanced());
            favorite.setCreatedAt(new Date().toString());
            favorite.setUsageCount(0);
            favorite.setTags(request.getTags() != null ? request.getTags() : List.of());

            List<FavoriteQueryResponse.FavoriteQuery> userQueries =
                FAVORITE_QUERIES.computeIfAbsent(request.getUserId(), k -> new ArrayList<>());
            userQueries.add(favorite);

            response.setId(queryId);
            response.setSuccess(true);
            response.setMessage("Query saved to favorites");

        } catch (Exception e) {
            log.error("Save favorite failed", e);
            response.setSuccess(false);
            response.setMessage(e.getMessage());
        }

        return response;
    }

    /**
     * Get all favorites for a user.
     */
    public List<FavoriteQueryResponse.FavoriteQuery> getFavorites(String userId) {
        return FAVORITE_QUERIES.getOrDefault(userId, FAVORITE_QUERIES.getOrDefault("default", new ArrayList<>()));
    }

    /**
     * Delete a favorite.
     */
    public boolean deleteFavorite(String userId, String queryId) {
        List<FavoriteQueryResponse.FavoriteQuery> queries = FAVORITE_QUERIES.get(userId);
        if (queries != null) {
            return queries.removeIf(q -> q.getId().equals(queryId));
        }
        return false;
    }

    /**
     * Increment usage count.
     */
    public void incrementUsage(String userId, String queryId) {
        List<FavoriteQueryResponse.FavoriteQuery> queries = FAVORITE_QUERIES.get(userId);
        if (queries != null) {
            queries.stream()
                .filter(q -> q.getId().equals(queryId))
                .findFirst()
                .ifPresent(q -> q.setUsageCount(q.getUsageCount() + 1));
        }
    }

    /**
     * Update favorite.
     */
    public FavoriteQueryResponse updateFavorite(String userId, String queryId, FavoriteQueryRequest request) {
        FavoriteQueryResponse response = new FavoriteQueryResponse();

        List<FavoriteQueryResponse.FavoriteQuery> queries = FAVORITE_QUERIES.get(userId);
        if (queries != null) {
            Optional<FavoriteQueryResponse.FavoriteQuery> query_opt = queries.stream()
                .filter(q -> q.getId().equals(queryId))
                .findFirst();

            if (query_opt.isPresent()) {
                FavoriteQueryResponse.FavoriteQuery query_obj = query_opt.get();
                if (request.getName() != null) query_obj.setName(request.getName());
                if (request.getDescription() != null) query_obj.setDescription(request.getDescription());
                if (request.getTags() != null) query_obj.setTags(request.getTags());

                response.setId(queryId);
                response.setSuccess(true);
                response.setMessage("Favorite updated");
                return response;
            }
        }

        response.setSuccess(false);
        response.setMessage("Favorite not found");
        return response;
    }

    /**
     * Search favorites.
     */
    public List<FavoriteQueryResponse.FavoriteQuery> searchFavorites(String userId, String searchTerm) {
        List<FavoriteQueryResponse.FavoriteQuery> favorites = getFavorites(userId);
        String term = searchTerm.toLowerCase();

        return favorites.stream()
            .filter(f -> f.getName().toLowerCase().contains(term) ||
                        f.getDescription().toLowerCase().contains(term) ||
                        f.getTags().stream().anyMatch(t -> t.toLowerCase().contains(term)))
            .toList();
    }
}