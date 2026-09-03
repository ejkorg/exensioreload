package com.onsemi.cim.apps.exensio.exensioreload.repository;

import java.time.LocalDateTime;
import java.util.List;

public interface ExternalMetadataRepository {
    List<MetadataRow> findMetadata(String site, String environment, LocalDateTime start, LocalDateTime end,
                                   String dataType, String dataTypeExt, String testPhase, String testerType, String location, java.util.List<String> lots, java.util.List<String> wafers, java.util.List<String> devices, int limit,
                                   java.util.List<String> steps, java.util.List<String> recipes, java.util.List<String> equipmentIds,
                                   java.util.Map<String, java.util.List<String>> additionalWhereFilters);

    List<MetadataRow> findMetadataPage(String site, String environment, LocalDateTime start, LocalDateTime end,
                                       String dataType, String dataTypeExt, String testPhase, String testerType, String location, java.util.List<String> lots, java.util.List<String> wafers, java.util.List<String> devices,
                                       int offset, int limit,
                                       java.util.List<String> steps, java.util.List<String> recipes, java.util.List<String> equipmentIds,
                                       java.util.Map<String, java.util.List<String>> additionalWhereFilters);

    /**
     * Optimized single-query method that returns both the paginated rows and total count.
     * Uses COUNT(*) OVER() window function to avoid two separate DB round-trips.
     * @return MetadataPageResult containing items and total count
     */
    default MetadataPageResult findMetadataPageWithCount(String site, String environment, LocalDateTime start, LocalDateTime end,
                                                         String dataType, String dataTypeExt, String testPhase, String testerType, String location,
                                                         java.util.List<String> lots, java.util.List<String> wafers, java.util.List<String> devices,
                                                         int offset, int limit,
                                                         java.util.List<String> steps, java.util.List<String> recipes, java.util.List<String> equipmentIds,
                                                         java.util.Map<String, java.util.List<String>> additionalWhereFilters) {
        // Default implementation falls back to two queries for backwards compatibility
        long total = countMetadata(site, environment, start, end, dataType, dataTypeExt, testPhase, testerType, location, lots, wafers, devices, steps, recipes, equipmentIds, additionalWhereFilters);
        List<MetadataRow> rows = findMetadataPage(site, environment, start, end, dataType, dataTypeExt, testPhase, testerType, location, lots, wafers, devices, offset, limit, steps, recipes, equipmentIds, additionalWhereFilters);
        return new MetadataPageResult(rows, total);
    }

    long countMetadata(String site, String environment, LocalDateTime start, LocalDateTime end,
                       String dataType, String dataTypeExt, String testPhase, String testerType, String location, java.util.List<String> lots, java.util.List<String> wafers, java.util.List<String> devices,
                       java.util.List<String> steps, java.util.List<String> recipes, java.util.List<String> equipmentIds,
                       java.util.Map<String, java.util.List<String>> additionalWhereFilters);

    /**
     * Lightweight aggregate for preview-like requests. Returns total row count and the
     * oldest/newest end_time values that match the provided filters.
     */
    MetadataSummary summarizeMetadata(String site, String environment, LocalDateTime start, LocalDateTime end,
                                      String dataType, String dataTypeExt, String testPhase, String testerType, String location,
                                      java.util.List<String> lots, java.util.List<String> wafers, java.util.List<String> devices,
                                      java.util.List<String> steps, java.util.List<String> recipes, java.util.List<String> equipmentIds,
                                      java.util.Map<String, java.util.List<String>> additionalWhereFilters);

    default String describePreviewQuery(LocalDateTime start,
                                        LocalDateTime end,
                                        String dataType,
                                        String dataTypeExt,
                                        String testPhase,
                                        String testerType,
                                        String location,
                                        java.util.List<String> lots,
                                        java.util.List<String> wafers,
                                        java.util.List<String> devices,
                                        int offset,
                                        int limit,
                                        java.util.List<String> steps,
                                        java.util.List<String> recipes,
                                        java.util.List<String> equipmentIds) {
        return null;
    }

    /**
     * Stream rows; consumer should be fast. This will use JDBC ResultSet iteration.
     */
    void streamMetadata(String site, String environment, LocalDateTime start, LocalDateTime end,
                        String dataType, String dataTypeExt, String testPhase, String testerType, String location, java.util.List<String> lots, java.util.List<String> wafers, java.util.List<String> devices, int limit,
                        java.util.function.Consumer<MetadataRow> consumer,
                        java.util.List<String> steps, java.util.List<String> recipes, java.util.List<String> equipmentIds);

    /**
     * Stream rows using an existing JDBC Connection (caller is responsible for lifecycle).
     */
    void streamMetadataWithConnection(java.sql.Connection conn, LocalDateTime start, LocalDateTime end,
                                      String dataType, String dataTypeExt, String testPhase, String testerType, String location, java.util.List<String> lots, java.util.List<String> wafers, java.util.List<String> devices, int limit,
                                      java.util.function.Consumer<MetadataRow> consumer,
                                      java.util.List<String> steps, java.util.List<String> recipes, java.util.List<String> equipmentIds);

    /**
     * Find candidate senders from the external metadata DB using an existing Connection.
     * Returns list of pairs (id_sender, name).
     */
    java.util.List<SenderCandidate> findSendersWithConnection(java.sql.Connection conn,
                                                              String location,
                                                              String dataType,
                                                              String testerType,
                                                              String dataTypeExt,
                                                              String testPhase);

    /**
     * @deprecated since v3.1 — no implementation exists on any backend.
     *             Kept to track migration progress. Remove once implemented or retired.
     */
    @Deprecated
    default java.util.List<SenderCandidate> findHistoricalSendersWithConnection(java.sql.Connection conn, String dataType) {
        throw new UnsupportedOperationException("Historical sender lookup not implemented");
    }

    /**
     * Describe the SQL that would be used for sender lookup (for debugging/logging).
     * Implementations may return the SQL text or null if not available.
     */
    default String describeSenderLookupQueryWithConnection(java.sql.Connection conn, String location, String dataType, String testerType, String dataTypeExt, String testPhase) {
        return null;
    }
    java.util.List<SenderCandidate> findAllSendersWithConnection(java.sql.Connection conn);

    // Distinct value helpers (use existing Connection lifecycle)
    java.util.List<String> findDistinctLocationsWithConnection(java.sql.Connection conn, String dataType, String testerType, String testPhase);

    java.util.List<String> findDistinctDataTypesWithConnection(java.sql.Connection conn, String location, String testerType, String testPhase);

    java.util.List<String> findDistinctTesterTypesWithConnection(java.sql.Connection conn, String location, String dataType, String testPhase);

    java.util.List<String> findDistinctDataTypeExtsWithConnection(java.sql.Connection conn, String location, String dataType, String testerType);

    java.util.List<String> findDistinctTestPhasesWithConnection(java.sql.Connection conn,
                                                                String location,
                                                                String dataType,
                                                                String dataTypeExt,
                                                                String testerType,
                                                                Integer senderId,
                                                                String senderName);

    java.util.List<String> findDistinctDevicesWithConnection(java.sql.Connection conn,
                                                             String dataType,
                                                             String testerType);
}
