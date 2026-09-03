package com.onsemi.cim.apps.exensio.exensioreload.repository;

import com.onsemi.cim.apps.exensio.exensioreload.config.ExternalDbConfig;
import com.onsemi.cim.apps.exensio.exensioreload.util.DevicePatternUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;
import org.springframework.beans.factory.annotation.Value;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Repository
public class JdbcExternalMetadataRepository implements ExternalMetadataRepository {
    private final Logger log = LoggerFactory.getLogger(JdbcExternalMetadataRepository.class);
    private final ExternalDbConfig externalDbConfig;

    public JdbcExternalMetadataRepository(ExternalDbConfig externalDbConfig) {
        this.externalDbConfig = externalDbConfig;
    }

    /**
     * If true, always use the broad `all_metadata_view` and skip automatic
     * specialization to probe/ft/... views. This can be set via
     * application properties: `exensio.metadata.forceAllView=true` or
     * programmatically using `setForceAllMetadataView(true)`.
     */
    @Value("${exensio.metadata.forceAllView:false}")
    private boolean forceAllMetadataView;

    /**
     * Allow programmatic override (useful for admin endpoints or tests).
     */
    public void setForceAllMetadataView(boolean force) {
        this.forceAllMetadataView = force;
    }

    /**
     * Read-only accessor for the force flag so admin endpoints can report state.
     */
    public boolean isForceAllMetadataView() {
        return this.forceAllMetadataView;
    }

    // using top-level SenderCandidate DTO

    @Override
    public List<MetadataRow> findMetadata(String site, String environment, LocalDateTime start, LocalDateTime end, String dataType, String dataTypeExt, String testPhase, String testerType, String location, java.util.List<String> lots, java.util.List<String> wafers, java.util.List<String> devices, int limit,
                                          java.util.List<String> steps, java.util.List<String> recipes, java.util.List<String> equipmentIds,
                                          java.util.Map<String, java.util.List<String>> additionalWhereFilters) {
        List<MetadataRow> rows = new ArrayList<>();
        streamMetadata(site, environment, start, end, dataType, dataTypeExt, testPhase, testerType, location, lots, wafers, devices, limit, rows::add, steps, recipes, equipmentIds, additionalWhereFilters);
        return rows;
    }

    @Override
    public List<MetadataRow> findMetadataPage(String site, String environment, LocalDateTime start, LocalDateTime end,
                                              String dataType, String dataTypeExt, String testPhase, String testerType, String location, java.util.List<String> lots, java.util.List<String> wafers, java.util.List<String> devices,
                                              int offset, int limit,
                                              java.util.List<String> steps, java.util.List<String> recipes, java.util.List<String> equipmentIds,
                                              java.util.Map<String, java.util.List<String>> additionalWhereFilters) {
        // OPTIMIZATION: Use the optimized query builder if non-indexed filters are not provided
        // This significantly speeds up queries when users don't specify testerType, testPhase, location, dataTypeExt
        String viewName = getPreviewViewName(dataType);

        boolean hasOptionalFilters = (dataTypeExt != null && !dataTypeExt.isBlank()) ||
                (testPhase != null && !testPhase.isBlank()) ||
                (testerType != null && !testerType.isBlank()) ||
                (location != null && !location.isBlank()) ||
                (steps != null && !steps.isEmpty()) ||
                (recipes != null && !recipes.isEmpty()) ||
                (equipmentIds != null && !equipmentIds.isEmpty()) ||
                (additionalWhereFilters != null && !additionalWhereFilters.isEmpty());

        SqlWithParams sql;
        if (!hasOptionalFilters) {
            // Fast path: Use optimized query (indexed columns only)
            sql = buildPreviewDedupedPageQuery(viewName, true, start, end, dataType, null, null, null, null, lots, wafers, devices, steps, recipes, equipmentIds, additionalWhereFilters);
            if (log.isDebugEnabled()) {
                log.debug("Using optimized query path for findMetadataPage (no optional filters)");
            }
        } else {
            // Full path: Apply all filters if optional filters are provided
            sql = buildPreviewDedupedPageQuery(viewName, false, start, end, dataType, dataTypeExt, testPhase, testerType, location, lots, wafers, devices, steps, recipes, equipmentIds, additionalWhereFilters);
        }

        sql.append(" order by m.end_time desc");
        if (limit > 0) {
            sql.append(" offset ? rows fetch next ? rows only");
            sql.params.add(Math.max(offset, 0));
            sql.params.add(limit);
        }
        try (Connection c = externalDbConfig.getConnection(site, environment);
             PreparedStatement ps = prepareStatement(c, sql);
             ResultSet rs = ps.executeQuery()) {
            List<MetadataRow> rows = new ArrayList<>();
            while (rs.next()) {
                rows.add(mapMetadataRow(rs));
            }
            return rows;
        } catch (Exception ex) {
            log.error("Failed fetching metadata page for site {} env {}: {}", site, environment, ex.getMessage(), ex);
            throw new RuntimeException("External metadata read failed", ex);
        }
    }

    @Override
    public MetadataSummary summarizeMetadata(String site, String environment, LocalDateTime start, LocalDateTime end,
                                             String dataType, String dataTypeExt, String testPhase, String testerType, String location,
                                             java.util.List<String> lots, java.util.List<String> wafers, java.util.List<String> devices,
                                             java.util.List<String> steps, java.util.List<String> recipes, java.util.List<String> equipmentIds,
                                             java.util.Map<String, java.util.List<String>> additionalWhereFilters) {
        // OPTIMIZATION: Build a simpler query using ONLY indexed columns for summary queries
        // This avoids expensive filters on non-indexed columns (testerType, testPhase, dataTypeExt, location)
        // which were likely null anyway after the MetadataImporterService optimization.
        String viewName = getPreviewViewName(dataType);
        SqlWithParams sql = buildOptimizedSummaryQuery(
                "select count(*) as total_count, min(m.end_time) as min_end_time, max(m.end_time) as max_end_time from " + viewName + " m",
                start, end,
                dataType,
                lots, wafers, devices);
        try (Connection c = externalDbConfig.getConnection(site, environment);
             PreparedStatement ps = prepareStatement(c, sql);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                long total = 0L;
                try { total = rs.getLong("total_count"); } catch (Exception ignore) {}
                LocalDateTime oldest = readLocalDateTime(rs, "min_end_time");
                LocalDateTime latest = readLocalDateTime(rs, "max_end_time");
                return new MetadataSummary(total, oldest, latest);
            }
            return new MetadataSummary(0L, null, null);
        } catch (Exception ex) {
            log.error("Failed summarizing metadata for site {} env {}: {}", site, environment, ex.getMessage(), ex);
            throw new RuntimeException("External metadata summary failed", ex);
        }
    }

    /**
     * Optimized single-query method using COUNT(*) OVER() window function.
     * Returns both paginated rows and total count in a single DB round-trip.
     */
    @Override
    public MetadataPageResult findMetadataPageWithCount(String site, String environment, LocalDateTime start, LocalDateTime end,
                                                        String dataType, String dataTypeExt, String testPhase, String testerType, String location,
                                                        java.util.List<String> lots, java.util.List<String> wafers, java.util.List<String> devices,
                                                        int offset, int limit,
                                                        java.util.List<String> steps, java.util.List<String> recipes, java.util.List<String> equipmentIds,
                                                        java.util.Map<String, java.util.List<String>> additionalWhereFilters) {
        // Use COUNT(*) OVER() window function to get total count with each row
        String viewName = getPreviewViewName(dataType);
        SqlWithParams sql = buildMetadataQuery(
                "select DISTINCT m.lot, m.id as metadata_id, m.id_data, m.end_time, m.wafer, m.device, f.file_name as original_file_name, m.step, m.tester_id, m.test_program, COUNT(*) OVER() as total_count from " + viewName + " m left join dtp_file f on f.id = m.id_file",
                start, end, dataType, dataTypeExt, testPhase, testerType, location, lots, wafers, devices, steps, recipes, equipmentIds, additionalWhereFilters);
        sql.append(" order by m.end_time desc");
        if (limit > 0) {
            sql.append(" offset ? rows fetch next ? rows only");
            sql.params.add(Math.max(offset, 0));
            sql.params.add(limit);
        }

        try (Connection c = externalDbConfig.getConnection(site, environment);
             PreparedStatement ps = prepareStatement(c, sql);
             ResultSet rs = ps.executeQuery()) {
            List<MetadataRow> rows = new ArrayList<>();
            long totalCount = 0;
            boolean firstRow = true;
            while (rs.next()) {
                rows.add(mapMetadataRow(rs));
                // total_count is the same for all rows; capture it once
                if (firstRow) {
                    try {
                        totalCount = rs.getLong("total_count");
                    } catch (Exception e) {
                        log.warn("Could not read total_count from result set, falling back to row count");
                    }
                    firstRow = false;
                }
            }
            // If no rows returned but we need total, it means total is 0
            if (rows.isEmpty()) {
                totalCount = 0;
            }
            if (log.isDebugEnabled()) {
                log.debug("findMetadataPageWithCount returned {} rows, total={}", rows.size(), totalCount);
            }
            return new MetadataPageResult(rows, totalCount);
        } catch (Exception ex) {
            log.error("Failed fetching metadata page with count for site {} env {}: {}", site, environment, ex.getMessage(), ex);
            throw new RuntimeException("External metadata read failed", ex);
        }
    }

    @Override
    public String describePreviewQuery(String site, String environment,
                                       LocalDateTime start,
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
                                       java.util.List<String> equipmentIds,
                                       java.util.Map<String, java.util.List<String>> additionalWhereFilters) {
        // Use the internal builder variant with emitInfo=false so describing
        // the query doesn't produce the same INFO logs as the executing call.
        String viewName = getPreviewViewName(dataType);
        SqlWithParams sql = buildMetadataQueryInternal("select m.lot, m.id as metadata_id, m.id_data, m.end_time, m.wafer, m.device, f.file_name as original_file_name, m.step, m.tester_id, m.test_program from " + viewName + " m left join dtp_file f on f.id = m.id_file",
                start, end, null, /* dataTypeExt */ null, /* testPhase */ null, testerType, /* location */ null, lots, wafers, devices, false, this.forceAllMetadataView, steps, recipes, equipmentIds, additionalWhereFilters);
        sql.append(" order by m.end_time desc");
        if (limit > 0) {
            sql.append(" offset ? rows fetch next ? rows only");
            sql.params.add(Math.max(offset, 0));
            sql.params.add(limit);
        }
        return sql.format();
    }

    @Override
    public long countMetadata(String site, String environment, LocalDateTime start, LocalDateTime end, String dataType, String dataTypeExt, String testPhase, String testerType, String location, java.util.List<String> lots, java.util.List<String> wafers, java.util.List<String> devices,
                              java.util.List<String> steps, java.util.List<String> recipes, java.util.List<String> equipmentIds,
                              java.util.Map<String, java.util.List<String>> additionalWhereFilters) {
        String viewName = getPreviewViewName(dataType);
        boolean hasOptionalFilters = (dataTypeExt != null && !dataTypeExt.isBlank()) ||
                (testPhase != null && !testPhase.isBlank()) ||
                (testerType != null && !testerType.isBlank()) ||
                (location != null && !location.isBlank()) ||
                (steps != null && !steps.isEmpty()) ||
                (recipes != null && !recipes.isEmpty()) ||
                (equipmentIds != null && !equipmentIds.isEmpty()) ||
                (additionalWhereFilters != null && !additionalWhereFilters.isEmpty());
        SqlWithParams sql = buildPreviewDedupedCountQuery(viewName, !hasOptionalFilters, start, end, dataType, dataTypeExt, testPhase, testerType, location, lots, wafers, devices, steps, recipes, equipmentIds, additionalWhereFilters);
        try (Connection c = externalDbConfig.getConnection(site, environment);
             PreparedStatement ps = prepareStatement(c, sql);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 0L;
        } catch (Exception ex) {
            log.error("Failed counting metadata for site {} env {}: {}", site, environment, ex.getMessage(), ex);
            throw new RuntimeException("External metadata count failed", ex);
        }
    }

    @Override
    public void streamMetadata(String site, String environment, LocalDateTime start, LocalDateTime end, String dataType, String dataTypeExt, String testPhase, String testerType, String location, java.util.List<String> lots, java.util.List<String> wafers, java.util.List<String> devices, int limit, java.util.function.Consumer<MetadataRow> consumer,
                               java.util.List<String> steps, java.util.List<String> recipes, java.util.List<String> equipmentIds,
                               java.util.Map<String, java.util.List<String>> additionalWhereFilters) {
        try (Connection c = externalDbConfig.getConnection(site, environment)) {
            streamMetadataWithConnection(site, environment, c, start, end, dataType, dataTypeExt, testPhase, testerType, location, lots, wafers, devices, limit, consumer, steps, recipes, equipmentIds, additionalWhereFilters);
        } catch (Exception ex) {
            log.error("Failed streaming metadata for site {} env {}: {}", site, environment, ex.getMessage(), ex);
            throw new RuntimeException("External metadata read failed", ex);
        }
    }

    @Override
    public void streamMetadataWithConnection(String site, String environment, Connection c, LocalDateTime start, LocalDateTime end, String dataType, String dataTypeExt, String testPhase, String testerType, String location, java.util.List<String> lots, java.util.List<String> wafers, java.util.List<String> devices, int limit, java.util.function.Consumer<MetadataRow> consumer,
                                              java.util.List<String> steps, java.util.List<String> recipes, java.util.List<String> equipmentIds,
                                              java.util.Map<String, java.util.List<String>> additionalWhereFilters) {
        PreparedStatement ps = null;
        ResultSet rs = null;
        try {
            SqlWithParams sql = buildMetadataQuery("select DISTINCT m.lot, m.id as metadata_id, m.id_data, m.end_time, m.wafer, m.device, f.file_name as original_file_name, m.step, m.tester_id, m.test_program from all_metadata_view m left join dtp_file f on f.id = m.id_file",
                    start, end, dataType, dataTypeExt, testPhase, testerType, location, lots, wafers, devices, steps, recipes, equipmentIds, additionalWhereFilters);
            if (limit > 0) {
                sql.append(" fetch first ").append(String.valueOf(limit)).append(" rows only");
            }
            ps = prepareStatement(c, sql);
            rs = ps.executeQuery();
            while (rs.next()) {
                consumer.accept(mapMetadataRow(rs));
            }
        } catch (Exception ex) {
            log.error("Failed streaming metadata using provided connection: {}", ex.getMessage(), ex);
            throw new RuntimeException("External metadata read failed", ex);
        } finally {
            try { if (rs != null) rs.close(); } catch (Exception ignore) {}
            try { if (ps != null) ps.close(); } catch (Exception ignore) {}
        }
    }

    @Override
    public java.util.List<SenderCandidate> findSendersWithConnection(Connection c, String location, String dataType, String testerType, String dataTypeExt, String testPhase) {
        StringBuilder sb = new StringBuilder();
        // Query structure using subqueries with IN clauses as requested
        // Note: Modern database optimizers (Oracle, PostgreSQL) typically convert these subqueries to JOINs automatically
        // Performance should be good if indexes exist on: dtp_location.location, dtp_data_type.data_type, dtp_tester_type.type
        sb.append("SELECT s.id, s.name, d.where_condition ");
        sb.append("FROM dtp_sender s ");
        sb.append("INNER JOIN dtp_dist_conf d ON s.id = d.id_sender ");
        sb.append("WHERE 1=1 ");
        List<Object> params = new ArrayList<>();

        // Location filter: if location is NULL, don't filter (allow any). If provided, match location=param OR id_location IS NULL
        String locParam = (location == null || location.isBlank()) ? null : location.trim();
        if (locParam != null) {
            sb.append(" AND (d.id_location IN (SELECT id FROM dtp_location WHERE location = ?) OR d.id_location IS NULL)");
            params.add(locParam);
        }

        // Data type filter: if dataType is NULL, don't filter (allow any). If provided, match dataType=param OR id_data_type IS NULL
        String dtParam = (dataType == null || dataType.isBlank()) ? null : dataType.trim();
        if (dtParam != null) {
            sb.append(" AND (d.id_data_type IN (SELECT id FROM dtp_data_type WHERE data_type = ?) OR d.id_data_type IS NULL)");
            params.add(dtParam);
        }

        // Tester type filter: if testerType is NULL, don't filter. If provided, match testerType=param OR id_tester_type IS NULL
        String ttParam = (testerType == null || testerType.isBlank()) ? null : testerType.trim();
        if (ttParam != null) {
            sb.append(" AND (d.id_tester_type IN (SELECT id FROM dtp_tester_type WHERE type = ?) OR d.id_tester_type IS NULL)");
            params.add(ttParam);
        }

        // Data type extension (test-phase stored as id_data_type_ext) filter
        String dteParam = (dataTypeExt == null || dataTypeExt.isBlank()) ? null : dataTypeExt.trim();
        if (dteParam != null) {
            sb.append(" AND (d.id_data_type_ext IN (SELECT id FROM dtp_data_type_ext WHERE data_type_ext = ?) OR d.id_data_type_ext IS NULL)");
            params.add(dteParam);
        }

        // Test phase filter: if testPhase is provided (manual input), filter where_condition using LIKE
        String tpParam = (testPhase == null || testPhase.isBlank()) ? null : testPhase.trim();
        if (tpParam != null) {
            sb.append(" AND (d.where_condition IS NULL OR d.where_condition LIKE ?)");
            params.add("%" + tpParam + "%");
        }

        // Filter out where_condition that contains '1=0' or '2=1'
        sb.append(" AND (d.where_condition IS NULL OR ");
        sb.append("(d.where_condition IS NOT NULL AND d.where_condition NOT LIKE '%1=0%' AND d.where_condition NOT LIKE '%2=1%'))");

        // Order by sender name for stable results
        sb.append(" ORDER BY s.name");

        PreparedStatement ps = null;
        ResultSet rs = null;
        try {
            // Use the centralized prepareStatement helper so parameter types (Integer/Long/Timestamp)
            // are bound correctly instead of coercing everything to String.
            // ensure where_condition is selected so controller can prefer candidates matching the manual testPhase
            SqlWithParams sql = new SqlWithParams(sb.toString());
            sql.params.addAll(params);
            ps = prepareStatement(c, sql);
            rs = ps.executeQuery();
            List<SenderCandidate> out = new ArrayList<>();
            while (rs.next()) {
                Integer id = null;
                try { id = rs.getInt("id"); if (rs.wasNull()) id = null; } catch (Exception ignore) {}
                String name = null;
                try { name = rs.getString("name"); } catch (Exception ignore) {}
                String where = null;
                try { where = rs.getString("where_condition"); } catch (Exception ignore) {}
                if (id != null || (name != null && !name.isBlank())) {
                    out.add(new SenderCandidate(id, name, where));
                }
            }
            return out;
        } catch (Exception ex) {
            log.error("Failed running sender lookup: {}", ex.getMessage(), ex);
            throw new RuntimeException("Sender lookup failed", ex);
        } finally {
            try { if (rs != null) rs.close(); } catch (Exception ignore) {}
            try { if (ps != null) ps.close(); } catch (Exception ignore) {}
        }
    }

    @Override
    public java.util.List<SenderCandidate> findHistoricalSendersWithConnection(Connection c, String dataType) {
        SqlWithParams sql = new SqlWithParams("select id, name from dtp_sender where resender = 'N'");
        // Match any case of HIST in the sender name
        sql.append(" and regexp_like(name, ?, 'i')");
        sql.params.add("HIST");
        if (dataType != null && !dataType.isBlank()) {
            sql.append(" and id_data_type in (select id from dtp_data_type where data_type = ?)");
            sql.params.add(dataType.trim());
        }
        sql.append(" order by name");

        PreparedStatement ps = null;
        ResultSet rs = null;
        try {
            ps = prepareStatement(c, sql);
            rs = ps.executeQuery();
            java.util.List<SenderCandidate> out = new ArrayList<>();
            while (rs.next()) {
                Integer id = null;
                try { id = rs.getInt("id"); if (rs.wasNull()) id = null; } catch (Exception ignore) {}
                String name = null;
                try { name = rs.getString("name"); } catch (Exception ignore) {}
                if (id != null || (name != null && !name.isBlank())) {
                    out.add(new SenderCandidate(id, name));
                }
            }
            return out;
        } catch (Exception ex) {
            log.error("Failed running historical sender lookup: {}", ex.getMessage(), ex);
            throw new RuntimeException("Historical sender lookup failed", ex);
        } finally {
            try { if (rs != null) rs.close(); } catch (Exception ignore) {}
            try { if (ps != null) ps.close(); } catch (Exception ignore) {}
        }
    }

    @Override
    public String describeSenderLookupQueryWithConnection(Connection c, String location, String dataType, String testerType, String dataTypeExt, String testPhase) {
        // Delegate to the specific describe method for clarity. This preserves the
        // original behavior while allowing callers to use the more explicit helpers
        // added below.
        return describeSpecificSenderLookupQueryWithConnection(c, location, dataType, testerType, dataTypeExt, testPhase);
    }

    /**
     * Dev helper: return the SQL AND the bound parameter values that would be
     * used when executing the specific sender lookup. This mirrors the
     * logic in {@link #findSendersWithConnection} but returns a formatted
     * representation (SQL + params) suitable for debug output. Caller should
     * only invoke this in dev or when explicit debug is enabled.
     */
    public String describeSenderLookupQueryWithParamsWithConnection(Connection c, String location, String dataType, String testerType, String dataTypeExt, String testPhase) {
        // Build the same SQL that findSendersWithConnection executes, but capture params
        SqlWithParams sql = new SqlWithParams("SELECT s.id, s.name, d.where_condition ");
        sql.append("FROM dtp_sender s ");
        sql.append("INNER JOIN dtp_dist_conf d ON s.id = d.id_sender ");
        sql.append("WHERE 1=1 ");

        // Location filter
        String locParam = (location == null || location.isBlank()) ? null : location.trim();
        if (locParam != null) {
            sql.append(" AND (d.id_location IN (SELECT id FROM dtp_location WHERE location = ?) OR d.id_location IS NULL)");
            sql.params.add(locParam);
        }

        // Data type filter
        String dtParam = (dataType == null || dataType.isBlank()) ? null : dataType.trim();
        if (dtParam != null) {
            sql.append(" AND (d.id_data_type IN (SELECT id FROM dtp_data_type WHERE data_type = ?) OR d.id_data_type IS NULL)");
            sql.params.add(dtParam);
        }

        // Tester type filter
        String ttParam = (testerType == null || testerType.isBlank()) ? null : testerType.trim();
        if (ttParam != null) {
            sql.append(" AND (d.id_tester_type IN (SELECT id FROM dtp_tester_type WHERE type = ?) OR d.id_tester_type IS NULL)");
            sql.params.add(ttParam);
        }

        // Data type extension (test-phase stored as id_data_type_ext) filter
        String dteParam = (dataTypeExt == null || dataTypeExt.isBlank()) ? null : dataTypeExt.trim();
        if (dteParam != null) {
            sql.append(" AND (d.id_data_type_ext IN (SELECT id FROM dtp_data_type_ext WHERE data_type_ext = ?) OR d.id_data_type_ext IS NULL)");
            sql.params.add(dteParam);
        }

        // Test phase filter: if testPhase is provided, filter where_condition using LIKE
        String tpParam = (testPhase == null || testPhase.isBlank()) ? null : testPhase.trim();
        if (tpParam != null) {
            sql.append(" AND (d.where_condition IS NULL OR d.where_condition LIKE ?)");
            sql.params.add("%" + tpParam + "%");
        }

        // Filter out where_condition that contains '1=0' or '2=1'
        sql.append(" AND (d.where_condition IS NULL OR ");
        sql.append("(d.where_condition IS NOT NULL AND d.where_condition NOT LIKE '%1=0%' AND d.where_condition NOT LIKE '%2=1%'))");

        // Order by sender name
        sql.append(" ORDER BY s.name");
        return sql.format();
    }

    /**
     * Returns the SQL that will be used for the filtered (specific) sender lookup
     * — this mirrors the SQL built and executed by {@link #findSendersWithConnection}.
     * This should be used when describing/previewing the lookup that the UI will
     * perform based on location/dataType/testerType/etc.
     */
    private String describeSpecificSenderLookupQueryWithConnection(Connection c, String location, String dataType, String testerType, String dataTypeExt, String testPhase) {
        StringBuilder sb = new StringBuilder();
        sb.append("SELECT s.id, s.name, d.where_condition ");
        sb.append("FROM dtp_sender s ");
        sb.append("INNER JOIN dtp_dist_conf d ON s.id = d.id_sender ");
        sb.append("WHERE 1=1 ");

        // Location filter
        if (location != null && !location.isBlank()) {
            sb.append(" AND (d.id_location IN (SELECT id FROM dtp_location WHERE location = ?) OR d.id_location IS NULL)");
        }

        // Data type filter
        if (dataType != null && !dataType.isBlank()) {
            sb.append(" AND (d.id_data_type IN (SELECT id FROM dtp_data_type WHERE data_type = ?) OR d.id_data_type IS NULL)");
        }

        // Tester type filter
        if (testerType != null && !testerType.isBlank()) {
            sb.append(" AND (d.id_tester_type IN (SELECT id FROM dtp_tester_type WHERE type = ?) OR d.id_tester_type IS NULL)");
        }

        // Data type extension (test-phase stored as id_data_type_ext) filter
        if (dataTypeExt != null && !dataTypeExt.isBlank()) {
            sb.append(" AND (d.id_data_type_ext IN (SELECT id FROM dtp_data_type_ext WHERE data_type_ext = ?) OR d.id_data_type_ext IS NULL)");
        }

        // Test phase filter: if testPhase is provided (manual input), filter where_condition using LIKE
        if (testPhase != null && !testPhase.isBlank()) {
            sb.append(" AND (d.where_condition IS NULL OR d.where_condition LIKE ?)");
        }

        // Filter out where_condition that contains '1=0' or '2=1'
        sb.append(" AND (d.where_condition IS NULL OR ");
        sb.append("(d.where_condition IS NOT NULL AND d.where_condition NOT LIKE '%1=0%' AND d.where_condition NOT LIKE '%2=1%'))");

        // Order by sender name
        sb.append(" ORDER BY s.name");
        return sb.toString();
    }

    /**
     * Returns the SQL for the full sender list (no filters). This mirrors
     * {@link #findAllSendersWithConnection} and can be used by callers that need
     * to show or log the all-senders query.
     */
    // describeAllSendersQueryWithConnection removed - not used. Use
    // findAllSendersWithConnection() directly when you need to execute or log the
    // simple all-senders query.

    @Override
    public java.util.List<SenderCandidate> findAllSendersWithConnection(Connection c) {
        String sql = "select id, name from dtp_sender order by name";
        PreparedStatement ps = null;
        ResultSet rs = null;
        try {
            ps = c.prepareStatement(sql);
            rs = ps.executeQuery();
            List<SenderCandidate> out = new ArrayList<>();
            while (rs.next()) {
                Integer id = null;
                try { id = rs.getInt("id"); if (rs.wasNull()) id = null; } catch (Exception ignore) {}
                String name = null;
                try { name = rs.getString("name"); } catch (Exception ignore) {}
                if (id != null || (name != null && !name.isBlank())) {
                    out.add(new SenderCandidate(id, name));
                }
            }
            return out;
        } catch (Exception ex) {
            log.error("Failed fetching sender list: {}", ex.getMessage(), ex);
            throw new RuntimeException("Sender list query failed", ex);
        } finally {
            try { if (rs != null) rs.close(); } catch (Exception ignore) {}
            try { if (ps != null) ps.close(); } catch (Exception ignore) {}
        }
    }

    @Override
    public java.util.List<String> findDistinctLocationsWithConnection(Connection c, String dataType, String testerType, String testPhase) {
        // New source table: dtp_simple_client_setting
        String sql = "select distinct location from dtp_simple_client_setting where enabled = 'Y'";
        List<Object> params = new ArrayList<>();
        if (dataType != null && !dataType.isBlank()) { sql += " and UPPER(data_type) = ?"; params.add(dataType.trim().toUpperCase(Locale.ROOT)); }
        // Match provided testerType OR rows where tester_type IS NULL. Treat common UI tokens as omission.
        String _tt_loc = null;
        if (testerType != null) {
            String tmp = testerType.trim();
            if (!tmp.isEmpty() && !"ANY".equalsIgnoreCase(tmp) && !"ALL".equalsIgnoreCase(tmp) && !"NONE".equalsIgnoreCase(tmp) && !"NULL".equalsIgnoreCase(tmp)) {
                _tt_loc = tmp.toUpperCase(Locale.ROOT);
            }
        }
        if (_tt_loc != null) {
            sql += " and UPPER(tester_type) = ?";
            params.add(_tt_loc);
        }
        if (testPhase != null) {
            if (testPhase.isBlank() || "NULL".equalsIgnoreCase(testPhase) || "NONE".equalsIgnoreCase(testPhase)) {
                sql += " and (data_type_ext IS NULL or data_type_ext = '')";
            } else {
                sql += " and UPPER(data_type_ext) = ?"; params.add(testPhase.trim().toUpperCase(Locale.ROOT));
            }
        }
        // For DISTINCT selection Oracle requires ORDER BY expressions to be in the select list.
        // We only want distinct locations here so order only by location to avoid ORA-01791.
        sql += " order by location";

        PreparedStatement ps = null;
        ResultSet rs = null;
        try {
            SqlWithParams swp = new SqlWithParams(sql);
            swp.params.addAll(params);
            ps = prepareStatement(c, swp);
            rs = ps.executeQuery();
            List<String> out = new ArrayList<>();
            while (rs.next()) {
                String v = rs.getString(1);
                if (v != null && !v.isBlank()) out.add(v);
            }
            return out;
        } catch (Exception ex) {
            log.error("Failed fetching distinct locations from simple_client_setting: {}", ex.getMessage(), ex);
            throw new RuntimeException("Distinct locations query failed", ex);
        } finally {
            try { if (rs != null) rs.close(); } catch (Exception ignore) {}
            try { if (ps != null) ps.close(); } catch (Exception ignore) {}
        }
    }

    @Override
    public java.util.List<String> findDistinctDataTypesWithConnection(Connection c, String location, String testerType, String testPhase) {
        String sql = "select distinct data_type from dtp_simple_client_setting where enabled = 'Y'";
        List<Object> params = new ArrayList<>();
        if (location != null && !location.isBlank()) { sql += " and UPPER(location) = ?"; params.add(location.trim().toUpperCase(Locale.ROOT)); }
        // Match provided testerType OR rows where tester_type IS NULL. Treat common UI tokens as omission.
        String _tt_dt = null;
        if (testerType != null) {
            String tmp = testerType.trim();
            if (!tmp.isEmpty() && !"ANY".equalsIgnoreCase(tmp) && !"ALL".equalsIgnoreCase(tmp) && !"NONE".equalsIgnoreCase(tmp) && !"NULL".equalsIgnoreCase(tmp)) {
                _tt_dt = tmp.toUpperCase(Locale.ROOT);
            }
        }
        if (_tt_dt != null) {
            sql += " and UPPER(tester_type) = ?";
            params.add(_tt_dt);
        }
        if (testPhase != null) {
            if (testPhase.isBlank() || "NULL".equalsIgnoreCase(testPhase) || "NONE".equalsIgnoreCase(testPhase)) {
                sql += " and (data_type_ext IS NULL or data_type_ext = '')";
            } else {
                sql += " and UPPER(data_type_ext) = ?"; params.add(testPhase.trim().toUpperCase(Locale.ROOT));
            }
        }
        // Ordering for distinct data_type should only reference the selected column.
        sql += " order by data_type";

        PreparedStatement ps = null; ResultSet rs = null;
        try {
            SqlWithParams swp = new SqlWithParams(sql);
            swp.params.addAll(params);
            ps = prepareStatement(c, swp);
            rs = ps.executeQuery();
            List<String> out = new ArrayList<>();
            while (rs.next()) { String v = rs.getString(1); if (v != null && !v.isBlank()) out.add(v); }
            return out;
        } catch (Exception ex) { log.error("Failed fetching distinct data types from simple_client_setting: {}", ex.getMessage(), ex); throw new RuntimeException("Distinct data types query failed", ex); } finally { try { if (rs != null) rs.close(); } catch (Exception ignore) {} try { if (ps != null) ps.close(); } catch (Exception ignore) {} }
    }

    @Override
    public java.util.List<String> findDistinctTesterTypesWithConnection(Connection c, String location, String dataType, String testPhase) {
        String sql = "select distinct tester_type from dtp_simple_client_setting where enabled = 'Y'";
        List<Object> params = new ArrayList<>();
        if (location != null && !location.isBlank()) { sql += " and UPPER(location) = ?"; params.add(location.trim().toUpperCase(Locale.ROOT)); }
        if (dataType != null && !dataType.isBlank()) { sql += " and UPPER(data_type) = ?"; params.add(dataType.trim().toUpperCase(Locale.ROOT)); }
        if (testPhase != null) {
            if (testPhase.isBlank() || "NULL".equalsIgnoreCase(testPhase) || "NONE".equalsIgnoreCase(testPhase)) {
                sql += " and (data_type_ext IS NULL or data_type_ext = '')";
            } else {
                sql += " and data_type_ext = ?"; params.add(testPhase);
            }
        }
        // Ordering for distinct tester_type should only reference the selected column.
        sql += " order by tester_type";

        PreparedStatement ps = null; ResultSet rs = null;
        try {
            SqlWithParams swp = new SqlWithParams(sql);
            swp.params.addAll(params);
            ps = prepareStatement(c, swp);
            rs = ps.executeQuery();
            List<String> out = new ArrayList<>();
            boolean hasNull = false;
            while (rs.next()) {
                String v = rs.getString(1);
                if (v == null) {
                    hasNull = true;
                } else if (!v.isBlank()) {
                    out.add(v);
                }
            }
            if (hasNull) {
                // include an explicit empty string to represent NULL value so UI can show 'None' or blank option
                out.add(0, "");
            }
            return out;
        } catch (Exception ex) {
            log.error("Failed fetching distinct tester types from simple_client_setting: {}", ex.getMessage(), ex);
            throw new RuntimeException("Distinct tester types query failed", ex);
        } finally {
            try { if (rs != null) rs.close(); } catch (Exception ignore) {}
            try { if (ps != null) ps.close(); } catch (Exception ignore) {}
        }
    }

    @Override
    public java.util.List<String> findDistinctDataTypeExtsWithConnection(Connection c, String location, String dataType, String testerType) {
        String sql = "select distinct data_type_ext from dtp_simple_client_setting where enabled = 'Y'";
        List<Object> params = new ArrayList<>();
        if (location != null && !location.isBlank()) { sql += " and UPPER(location) = ?"; params.add(location.trim().toUpperCase(Locale.ROOT)); }
        if (dataType != null && !dataType.isBlank()) { sql += " and UPPER(data_type) = ?"; params.add(dataType.trim().toUpperCase(Locale.ROOT)); }
        // If caller provided a testerType, restrict extensions accordingly (allow NULL fallback).
        // Otherwise do not filter so UI can present extensions based on location + data_type.
        String _tt_dte = null;
        if (testerType != null) {
            String tmp = testerType.trim();
            if (!tmp.isEmpty() && !"ANY".equalsIgnoreCase(tmp) && !"ALL".equalsIgnoreCase(tmp) && !"NONE".equalsIgnoreCase(tmp) && !"NULL".equalsIgnoreCase(tmp)) {
                _tt_dte = tmp.toUpperCase(Locale.ROOT);
            }
        }
        if (_tt_dte != null) {
            sql += " and UPPER(tester_type) = ?";
            params.add(_tt_dte);
        }
        sql += " order by data_type_ext";

        PreparedStatement ps = null; ResultSet rs = null;
        try {
            SqlWithParams swp = new SqlWithParams(sql);
            swp.params.addAll(params);
            ps = prepareStatement(c, swp);
            rs = ps.executeQuery(); List<String> out = new ArrayList<>();
            boolean hasNull = false;
            while (rs.next()) {
                String v = rs.getString(1);
                if (v == null) {
                    hasNull = true;
                } else if (!v.isBlank()) {
                    out.add(v);
                }
            }
            if (hasNull) {
                // include an explicit empty string to represent NULL value so UI can show 'None' or blank option
                out.add(0, "");
            }
            return out;
        } catch (Exception ex) {
            log.error("Failed fetching distinct data_type_ext from simple_client_setting: {}", ex.getMessage(), ex);
            throw new RuntimeException("Distinct data_type_ext query failed", ex);
        } finally {
            try { if (rs != null) rs.close(); } catch (Exception ignore) {}
            try { if (ps != null) ps.close(); } catch (Exception ignore) {}
        }
    }

    @Override
    public java.util.List<String> findDistinctTestPhasesWithConnection(Connection c,
                                                                       String location,
                                                                       String dataType,
                                                                       String dataTypeExt,
                                                                       String testerType,
                                                                       Integer senderId,
                                                                       String senderName) {
        return findDistinctTestPhasesWithConnection(c, location, dataType, dataTypeExt, testerType, senderId, senderName, false);
    }

    public java.util.List<String> findDistinctTestPhasesWithConnection(Connection c,
                                                                       String location,
                                                                       String dataType,
                                                                       String dataTypeExt,
                                                                       String testerType,
                                                                       Integer senderId,
                                                                       String senderName,
                                                                       boolean exactTesterType) {
        java.util.LinkedHashSet<String> phases = new java.util.LinkedHashSet<>();
        // Only require location and dataType; testerType is optional.
        // By default when provided, include fallback rows where tester_type IS NULL.
        // If exactTesterType=true, enforce strict tester_type equality.
        if (location == null || location.isBlank() || dataType == null || dataType.isBlank()) {
            return new ArrayList<>();
        }

        // Use the appropriate metadata view based on dataType
        String viewName = getMetadataViewName(dataType);

        StringBuilder sb = new StringBuilder();
        sb.append("SELECT DISTINCT TEST_PHASE ");
        sb.append("FROM ").append(viewName).append(" ");
        sb.append("WHERE TEST_PHASE IS NOT NULL ");
        List<Object> params = new ArrayList<>();
        // Use case-insensitive location comparison to avoid mismatches due to casing
        sb.append("AND UPPER(location) = ?");
        params.add(location.trim().toUpperCase(Locale.ROOT));
        // Simple query format as requested: SELECT DISTINCT TEST_PHASE FROM view WHERE TEST_PHASE IS NOT NULL AND location = ? ORDER BY TEST_PHASE

        // Optionally filter by data_type_ext (which maps to TEST_PHASE in the legacy views) so dropdown honors the selected extension.
        // Allow NULLs to keep backward-compatible behavior when the column is not populated for some rows.
        if (dataTypeExt != null && !dataTypeExt.isBlank()) {
            String dte = dataTypeExt.trim().toUpperCase(Locale.ROOT);
            sb.append(" AND (UPPER(TEST_PHASE) = ? OR TEST_PHASE IS NULL)");
            params.add(dte);
        }

        // If testerType provided, use exact match or match+NULL fallback based on exactTesterType.
        if (testerType != null && !testerType.isBlank()) {
            String tt = testerType.trim();
            if (!tt.isEmpty() && !"ANY".equalsIgnoreCase(tt) && !"ALL".equalsIgnoreCase(tt) && !"NONE".equalsIgnoreCase(tt) && !"NULL".equalsIgnoreCase(tt)) {
                if (exactTesterType) {
                    sb.append(" AND UPPER(tester_type) = ?");
                } else {
                    sb.append(" AND (UPPER(tester_type) = ? OR tester_type IS NULL)");
                }
                params.add(tt.toUpperCase(Locale.ROOT));
            }
        }

        // If caller provided senderId or senderName, restrict phases to those that are associated
        // with a distribution config for that sender. We do this using an EXISTS subquery against
        // dtp_dist_conf + dtp_data_type_ext so phases are narrowed to what the sender supports.
        if (senderId != null || (senderName != null && !senderName.isBlank())) {
            sb.append(" AND EXISTS (SELECT 1 FROM dtp_dist_conf dc LEFT JOIN dtp_data_type_ext dte ON dc.id_data_type_ext = dte.id LEFT JOIN dtp_location dl ON dc.id_location = dl.id LEFT JOIN dtp_data_type ddt ON dc.id_data_type = ddt.id LEFT JOIN dtp_tester_type dtt ON dc.id_tester_type = dtt.id WHERE ");
            if (senderId != null) {
                sb.append(" dc.id_sender = ? AND ");
                params.add(senderId);
            } else {
                sb.append(" dc.id_sender IN (SELECT id FROM dtp_sender WHERE UPPER(name) = ?) AND ");
                params.add(senderName.trim().toUpperCase(Locale.ROOT));
            }
            // match location/dataType/testerType semantics similar to sender lookup
            sb.append(" (dl.location = ? OR dc.id_location IS NULL) AND (ddt.data_type = ? OR dc.id_data_type IS NULL)");
            params.add(location.trim());
            params.add(dataType.trim());
            if (testerType != null && !testerType.isBlank()) {
                sb.append(" AND (dtt.type = ? OR dc.id_tester_type IS NULL)");
                params.add(testerType.trim());
            }
            // ensure the distribution's declared extension matches the TEST_PHASE (or allow NULL mapping)
            sb.append(" AND (dte.data_type_ext = TEST_PHASE OR dc.id_data_type_ext IS NULL)");
            if (dataTypeExt != null && !dataTypeExt.isBlank()) {
                sb.append(" AND (UPPER(dte.data_type_ext) = ? OR dc.id_data_type_ext IS NULL)");
                params.add(dataTypeExt.trim().toUpperCase(Locale.ROOT));
            }
            sb.append(")");
        }

        sb.append(" ORDER BY TEST_PHASE");

        PreparedStatement ps = null;
        ResultSet rs = null;
        try {
            // Use helper to correctly bind Integer/Long/Timestamp types instead of coercing to String
            SqlWithParams sql = new SqlWithParams(sb.toString());
            sql.params.addAll(params);
            ps = prepareStatement(c, sql);
            rs = ps.executeQuery();
            while (rs.next()) {
                String phase = null;
                try { phase = rs.getString("TEST_PHASE"); } catch (Exception ignore) {}
                if (phase != null && !phase.isBlank()) {
                    phases.add(phase.trim());
                }
            }
            return new ArrayList<>(phases);
        } catch (Exception ex) {
            log.error("Failed fetching distinct test phases: {}", ex.getMessage(), ex);
            throw new RuntimeException("Distinct test phases query failed", ex);
        } finally {
            try { if (rs != null) rs.close(); } catch (Exception ignore) {}
            try { if (ps != null) ps.close(); } catch (Exception ignore) {}
        }
    }

    @Override
    public java.util.List<String> findDistinctDevicesWithConnection(Connection c,
                                                                     String dataType,
                                                                     String testerType) {
        if (dataType == null || dataType.isBlank()) {
            return new ArrayList<>();
        }
        String viewName = getPreviewViewName(dataType);
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT DISTINCT device FROM ").append(viewName).append(" WHERE device IS NOT NULL");
        List<Object> params = new ArrayList<>();
        if (testerType != null && !testerType.isBlank()) {
            String tt = testerType.trim();
            if (!tt.isEmpty() && !"ANY".equalsIgnoreCase(tt) && !"ALL".equalsIgnoreCase(tt) && !"NONE".equalsIgnoreCase(tt) && !"NULL".equalsIgnoreCase(tt)) {
                sql.append(" AND UPPER(tester_type) = ?");
                params.add(tt.toUpperCase(Locale.ROOT));
            }
        }
        sql.append(" ORDER BY device");
        PreparedStatement ps = null;
        ResultSet rs = null;
        try {
            SqlWithParams swp = new SqlWithParams(sql.toString());
            swp.params.addAll(params);
            ps = prepareStatement(c, swp);
            rs = ps.executeQuery();
            List<String> out = new ArrayList<>();
            while (rs.next()) {
                String dev = null;
                try { dev = rs.getString("device"); } catch (Exception ignore) {}
                if (dev != null && !dev.isBlank()) {
                    out.add(dev.trim());
                }
            }
            return out;
        } catch (Exception ex) {
            log.error("Failed fetching distinct devices: {}", ex.getMessage(), ex);
            throw new RuntimeException("Distinct devices query failed", ex);
        } finally {
            try { if (rs != null) rs.close(); } catch (Exception ignore) {}
            try { if (ps != null) ps.close(); } catch (Exception ignore) {}
        }
    }

    /**
     * Helper method to get the appropriate metadata view name based on dataType.
     * Returns the specific view for known data types, or all_metadata_view as fallback.
     */
    private String getMetadataViewName(String dataType) {
        if (dataType == null || dataType.isBlank()) {
            return "all_metadata_view";
        }
        String dtNorm = dataType.trim().toLowerCase(Locale.ROOT);
        switch (dtNorm) {
            case "probe":
                return "probe_metadata_view";
            case "ft":
            case "functional":
            case "functionaltest":
                return "ft_metadata_view";
            case "defect":
                return "defect_metadata_view";
            case "pcm":
                return "pcm_metadata_view";
            case "met":
                return "met_metadata_view";
            case "map":
                return "map_metadata_view";
            case "leh":
                return "leh_metadata_view";
            default:
                return "all_metadata_view";
        }
    }

    /**
     * Preview queries use the dtp_*_metadata tables instead of the legacy dtp_*_view sources.
     */
    private String getPreviewViewName(String dataType) {
        if (dataType == null || dataType.isBlank()) {
            return "dtp_all_metadata";
        }
        String dtNorm = dataType.trim().toLowerCase(Locale.ROOT);
        switch (dtNorm) {
            case "ft":
            case "functional":
            case "functionaltest":
                return "dtp_ft_metadata";
            case "probe":
                return "dtp_probe_metadata";
            case "defect":
                return "dtp_defect_metadata";
            case "pcm":
                return "dtp_pcm_metadata";
            case "met":
                return "dtp_met_metadata";
            case "map":
                return "dtp_map_metadata";
            case "leh":
                return "dtp_leh_metadata";
            case "historical":
                return "dtp_historical_metadata";
            default:
                return "dtp_" + dtNorm + "_metadata";
        }
    }

    /**
     * Sanitize wafer value by stripping all alpha characters, keeping only numeric digits.
     */
    private String sanitizeWafer(String wafer) {
        if (wafer == null) return null;
        return wafer.replaceAll("[^0-9]", "");
    }

    private SqlWithParams buildMetadataQuery(String select, LocalDateTime start, LocalDateTime end,
                                             String dataType, String dataTypeExt, String testPhase, String testerType, String location,
                                             java.util.List<String> lots, java.util.List<String> wafers,
                                             java.util.List<String> devices,
                                             java.util.List<String> steps,
                                             java.util.List<String> recipes,
                                             java.util.List<String> equipmentIds,
                                             java.util.Map<String, java.util.List<String>> additionalWhereFilters) {
        return buildMetadataQueryInternal(select, start, end, dataType, dataTypeExt, testPhase, testerType, location, lots, wafers, devices, true, this.forceAllMetadataView, steps, recipes, equipmentIds, additionalWhereFilters);
    }

    /**
     * OPTIMIZED: Build a query using ONLY indexed columns (date range, lots, wafers, dataType).
     * Avoids expensive filters on non-indexed columns (testerType, testPhase, dataTypeExt, location).
     * Used for both:
     * 1. Historical summary queries (fast count + date range)
     * 2. Non-historical preview queries when optional filters not provided
     */
    private SqlWithParams buildOptimizedMetadataQuery(String select, LocalDateTime start, LocalDateTime end,
                                                      String dataType, java.util.List<String> lots, java.util.List<String> wafers,
                                                      java.util.List<String> devices) {
        String effectiveSelect = select;
        String viewName = null;

        // Determine which view to use based on dataType
        try {
            if (!forceAllMetadataView && select != null && select.toLowerCase().contains("all_metadata_view")) {
                String chosenView = getMetadataViewName(dataType);
                viewName = chosenView;
                effectiveSelect = select.replaceAll("(?i)all_metadata_view", chosenView);
                // Alias 'id' to 'id_data' for specialized views
                if (!"all_metadata_view".equals(chosenView)) {
                    effectiveSelect = effectiveSelect.replaceAll("(?i)\\bid_data\\b", "id as id_data");
                    effectiveSelect = effectiveSelect.replaceAll("(?i)\\bid_file\\b", "id_file as id_data");
                }
                if (log.isInfoEnabled()) {
                    log.info("Using optimized query: view '{}' for dataType='{}'", chosenView, dataType);
                }
            }
        } catch (Exception ex) {
            if (log.isWarnEnabled()) {
                log.warn("Failed selecting specialized view for optimized query: {}", ex.getMessage());
            }
            effectiveSelect = select;
        }

        // Build time predicate
        SqlWithParams result;
        if (start == null && end == null) {
            result = new SqlWithParams(effectiveSelect + " where 1=1");
        } else if (start != null && end != null) {
            result = new SqlWithParams(effectiveSelect + " where m.end_time BETWEEN ? AND ?");
            result.params.add(Timestamp.valueOf(start));
            result.params.add(Timestamp.valueOf(end));
        } else if (start != null) {
            result = new SqlWithParams(effectiveSelect + " where m.end_time >= ?");
            result.params.add(Timestamp.valueOf(start));
        } else {
            result = new SqlWithParams(effectiveSelect + " where m.end_time <= ?");
            result.params.add(Timestamp.valueOf(end));
        }

        // Add dataType filter ONLY if using all_metadata_view
        boolean shouldFilterDataType = forceAllMetadataView || (effectiveSelect.toLowerCase().contains("all_metadata_view"));
        if (shouldFilterDataType && dataType != null && !dataType.isBlank()) {
            String dtUpper = dataType.trim().toUpperCase(Locale.ROOT);
            if (dataType.equals(dtUpper)) {
                result.append(" and m.data_type = ?");
                result.params.add(dtUpper);
            } else {
                result.append(" and UPPER(m.data_type) = ?");
                result.params.add(dtUpper);
            }
        }

        // Add lots filter only (indexed)
        boolean hasLots = lots != null && !lots.isEmpty();
        boolean hasWafers = wafers != null && !wafers.isEmpty();

        if (hasLots && !hasWafers) {
            int cap = Math.min(lots.size(), 100);
            List<String> vals = new ArrayList<>();
            boolean allUpper = true;
            for (int i = 0; i < cap; i++) {
                String v = lots.get(i);
                if (v != null && !v.isBlank()) {
                    String trimmed = v.trim();
                    String upper = trimmed.toUpperCase(Locale.ROOT);
                    if (!trimmed.equals(upper)) allUpper = false;
                    vals.add(upper);
                }
            }
            if (!vals.isEmpty()) {
                if (vals.size() == 1) {
                    result.append(allUpper ? " and m.lot = ?" : " and UPPER(m.lot) = ?");
                    result.params.add(vals.get(0));
                } else {
                    result.append(allUpper ? " and m.lot IN (" : " and UPPER(m.lot) IN (");
                    for (int i = 0; i < vals.size(); i++) {
                        if (i > 0) result.append(",");
                        result.append("?");
                        result.params.add(vals.get(i));
                    }
                    result.append(")");
                }
            }
        } else if (!hasLots && hasWafers) {
            int cap = Math.min(wafers.size(), 100);
            List<String> vals = new ArrayList<>();
            boolean allUpper = true;
            for (int i = 0; i < cap; i++) {
                String v = wafers.get(i);
                if (v != null && !v.isBlank()) {
                    String trimmed = v.trim();
                    String upper = trimmed.toUpperCase(Locale.ROOT);
                    if (!trimmed.equals(upper)) allUpper = false;
                    vals.add(upper);
                }
            }
            if (!vals.isEmpty()) {
                if (vals.size() == 1) {
                    result.append(allUpper ? " and m.wafer = ?" : " and UPPER(m.wafer) = ?");
                    result.params.add(vals.get(0));
                } else {
                    result.append(allUpper ? " and m.wafer IN (" : " and UPPER(m.wafer) IN (");
                    for (int i = 0; i < vals.size(); i++) {
                        if (i > 0) result.append(",");
                        result.append("?");
                        result.params.add(vals.get(i));
                    }
                    result.append(")");
                }
            }
        } else if (hasLots && hasWafers) {
            // Simple pair matching
            int numPairs = Math.min(Math.max(lots.size(), wafers.size()), 100);
            result.append(" and (");
            boolean first = true;
            for (int i = 0; i < numPairs; i++) {
                String lotRaw = i < lots.size() ? lots.get(i) : null;
                String waferRaw = i < wafers.size() ? wafers.get(i) : null;
                if ((lotRaw == null || lotRaw.isBlank()) && (waferRaw == null || waferRaw.isBlank())) continue;

                if (!first) result.append(" or ");
                result.append("(");

                if (lotRaw != null && !lotRaw.isBlank()) {
                    String lu = lotRaw.trim().toUpperCase(Locale.ROOT);
                    result.append("UPPER(m.lot) = ?");
                    result.params.add(lu);
                } else {
                    result.append("1=1");
                }

                result.append(" and ");

                if (waferRaw != null && !waferRaw.isBlank()) {
                    String wu = waferRaw.trim().toUpperCase(Locale.ROOT);
                    result.append("UPPER(m.wafer) = ?");
                    result.params.add(wu);
                } else {
                    result.append("1=1");
                }

                result.append(")");
                first = false;
            }
            result.append(")");
        }

        appendDeviceFilter(result, devices);

        if (log.isDebugEnabled()) {
            log.debug("Optimized query: {}", result.format());
        }
        return result;
    }

    private SqlWithParams buildOptimizedSummaryQuery(String select, LocalDateTime start, LocalDateTime end,
                                                     String dataType, java.util.List<String> lots, java.util.List<String> wafers,
                                                     java.util.List<String> devices) {
        // REFACTORED: Now reuses the generic buildOptimizedMetadataQuery() logic
        // Summary query is just a SELECT COUNT(...) using the same indexed-column-only approach
        return buildOptimizedMetadataQuery(select, start, end, dataType, lots, wafers, devices);
    }

    /**
     * Internal query builder that optionally suppresses informational logging.
     * When `emitInfo` is false the method will still construct the identical SQL
     * but won't write the info-level logs which previously caused duplicate
     * messages when the code built the query twice (describe + execute).
     */
    private SqlWithParams buildMetadataQueryInternal(String select, LocalDateTime start, LocalDateTime end,
                                                     String dataType, String dataTypeExt, String testPhase, String testerType, String location,
                                                     java.util.List<String> lots, java.util.List<String> wafers,
                                                     java.util.List<String> devices,
                                                     boolean emitInfo,
                                                     boolean forceAllView,
                                                     java.util.List<String> steps,
                                                     java.util.List<String> recipes,
                                                     java.util.List<String> equipmentIds,
                                                     java.util.Map<String, java.util.List<String>> additionalWhereFilters) {
        // Allow selecting a specialized view depending on dataType to improve
        // query performance. If callers passed a select that references
        // `all_metadata_view` we will replace it with a more specific view
        // when `dataType` indicates a specialized source.
        String effectiveSelect = select;
        String chosenView = null;
        boolean addDataTypePredicate = false;
        try {
            // If forceAllView is set, skip automatic specialization to per-type views
            if (!forceAllView && select != null && select.toLowerCase().contains("all_metadata_view")) {
                String viewName = getMetadataViewName(dataType);
                chosenView = viewName;
                effectiveSelect = select.replaceAll("(?i)all_metadata_view", viewName);
                // When using specialized views (probe/ft) ensure the result set contains
                // an `id_data` column expected by downstream code. For those views the
                // equivalent column is `id`, so alias it to `id_data` in the SELECT list.
                // If a specialized view is selected (anything other than
                // `all_metadata_view`) the identifier column may be named `id`.
                // Alias it to `id_data` so the rest of the code that expects
                // `id_data` continues to work with specialized views.
                if (!"all_metadata_view".equals(viewName)) {
                    try {
                        effectiveSelect = effectiveSelect.replaceAll("(?i)\\bid_data\\b", "id as id_data");
                        // Also support 'id_file' as an alias for 'id_data' if it's found in the select string
                        effectiveSelect = effectiveSelect.replaceAll("(?i)\\bid_file\\b", "id_file as id_data");
                    } catch (Exception ignore) {}
                }
                // (leave addDataTypePredicate decision to code after we determine the final
                // effectiveSelect so behavior is consistent whether or not specialization
                // happened — see below)
                if (emitInfo && log.isInfoEnabled()) {
                    log.info("Using metadata view '{}' for dataType='{}' (forceAllView={})", chosenView, dataType, forceAllView);
                }
            }
        } catch (Exception ex) {
            // Be tolerant: fall back to the supplied select on any unexpected error
            try { log.warn("Failed selecting specialized metadata view for dataType='{}': {}", dataType, ex.getMessage()); } catch (Exception ignore) {}
            effectiveSelect = select;
            addDataTypePredicate = dataType != null && !dataType.isBlank();
        }

        // Decide whether to add a data_type predicate: if the effective SELECT
        // references `all_metadata_view` (which will be the case when the
        // repository is configured to force the broad view) and a dataType was
        // provided, include the predicate so preview/describe queries are
        // filtered by data type when appropriate.
        try {
            String selLower = effectiveSelect == null ? "" : effectiveSelect.toLowerCase(Locale.ROOT);
            boolean isAllView = selLower.contains("all_metadata_view");
            addDataTypePredicate = isAllView && dataType != null && !dataType.isBlank();
        } catch (Exception ignore) {
            addDataTypePredicate = dataType != null && !dataType.isBlank();
        }

        // Build the time predicate only when one or both bounds are provided.
        // This avoids an unnecessary BETWEEN clause when callers want an unbounded query.
        // Use table alias 'm' for metadata columns since we join with dtp_file
        SqlWithParams result;
        if (start == null && end == null) {
            result = new SqlWithParams(effectiveSelect + " where 1=1");
        } else if (start != null && end != null) {
            result = new SqlWithParams(effectiveSelect + " where m.end_time BETWEEN ? AND ?");
            result.params.add(Timestamp.valueOf(start));
            result.params.add(Timestamp.valueOf(end));
        } else if (start != null) {
            result = new SqlWithParams(effectiveSelect + " where m.end_time >= ?");
            result.params.add(Timestamp.valueOf(start));
        } else {
            // end != null
            result = new SqlWithParams(effectiveSelect + " where m.end_time <= ?");
            result.params.add(Timestamp.valueOf(end));
        }
        // If still on all_metadata_view and a dataType was provided, add a predicate.
        if (addDataTypePredicate) {
            String dt = dataType == null ? "" : dataType.trim();
            String dtUpper = dt.toUpperCase(Locale.ROOT);
            // Prefer `data_type = ?` when the provided value is already uppercased
            // so we avoid applying functions on the column and preserve index usage.
            if (!dt.isEmpty() && dt.equals(dtUpper)) {
                result.append(" and m.data_type = ?");
                result.params.add(dtUpper);
                if (log.isInfoEnabled()) {
                    log.info("Applying dataType equality filter (no UPPER) to metadata query: dataType='{}'", dtUpper);
                }
            } else {
                result.append(" and UPPER(m.data_type) = ?");
                result.params.add(dtUpper);
                if (log.isInfoEnabled()) {
                    log.info("Applying dataType UPPER() filter to metadata query: dataType='{}'", dtUpper);
                }
            }
        }
        if (dataTypeExt != null && !dataTypeExt.isBlank() && !"NULL".equalsIgnoreCase(dataTypeExt) && !"NONE".equalsIgnoreCase(dataTypeExt) && !"ANY".equalsIgnoreCase(dataTypeExt)) {
            String dte = dataTypeExt.trim();
            String dteUpper = dte.toUpperCase(Locale.ROOT);
            // In metadata views, the parameter is called test_phase, but when navigating from modern models
            // we use dataTypeExt. If the query is against *_metadata_view, data_type_ext column might not exist.
            // Map it to test_phase if we are dealing with legacy views which all have test_phase instead of data_type_ext
            boolean isLegacyView = effectiveSelect != null && effectiveSelect.toLowerCase(Locale.ROOT).contains("_metadata_view");
            String colName = isLegacyView ? "m.test_phase" : "m.data_type_ext";
            
            if (dte.equals(dteUpper)) {
                result.append(" and " + colName + " = ?");
                result.params.add(dteUpper);
            } else {
                result.append(" and UPPER(" + colName + ") = ?");
                result.params.add(dteUpper);
            }
        }
        if (testPhase != null && !testPhase.isBlank() && !"NULL".equalsIgnoreCase(testPhase) && !"NONE".equalsIgnoreCase(testPhase) && !"ANY".equalsIgnoreCase(testPhase)) {
            String tp = testPhase.trim();
            String tpUpper = tp.toUpperCase(Locale.ROOT);
            if (tp.equals(tpUpper)) {
                result.append(" and m.test_phase = ?");
                result.params.add(tpUpper);
            } else {
                result.append(" and UPPER(m.test_phase) = ?");
                result.params.add(tpUpper);
            }
        }
        if (testerType != null && !testerType.isBlank() && !"NULL".equalsIgnoreCase(testerType) && !"NONE".equalsIgnoreCase(testerType) && !"ANY".equalsIgnoreCase(testerType)) {
            String tt = testerType.trim();
            String ttUpper = tt.toUpperCase(Locale.ROOT);
            if (tt.equals(ttUpper)) {
                result.append(" and m.tester_type = ?");
                result.params.add(ttUpper);
            } else {
                result.append(" and UPPER(m.tester_type) = ?");
                result.params.add(ttUpper);
            }
        }
        if (location != null && !location.isBlank()) {
            result.append(" and m.location = ?");
            result.params.add(location);
        }

        // New filter predicates for dtp_*_metadata tables
        if (steps != null && !steps.isEmpty()) {
            int cap = Math.min(steps.size(), 100);
            List<String> vals = new ArrayList<>();
            boolean allUpper = true;
            for (int i = 0; i < cap; i++) {
                String v = steps.get(i);
                if (v != null && !v.isBlank()) {
                    String trimmed = v.trim();
                    String upper = trimmed.toUpperCase(Locale.ROOT);
                    if (!trimmed.equals(upper)) allUpper = false;
                    vals.add(upper);
                }
            }
            if (!vals.isEmpty()) {
                if (vals.size() == 1) {
                    result.append(allUpper ? " and m.step = ?" : " and UPPER(m.step) = ?");
                    result.params.add(vals.get(0));
                } else {
                    result.append(allUpper ? " and m.step IN (" : " and UPPER(m.step) IN (");
                    for (int i = 0; i < vals.size(); i++) {
                        if (i > 0) result.append(",");
                        result.append("?");
                        result.params.add(vals.get(i));
                    }
                    result.append(")");
                }
            }
        }

        if (recipes != null && !recipes.isEmpty()) {
            int cap = Math.min(recipes.size(), 100);
            List<String> vals = new ArrayList<>();
            boolean allUpper = true;
            for (int i = 0; i < cap; i++) {
                String v = recipes.get(i);
                if (v != null && !v.isBlank()) {
                    String trimmed = v.trim();
                    String upper = trimmed.toUpperCase(Locale.ROOT);
                    if (!trimmed.equals(upper)) allUpper = false;
                    vals.add(upper);
                }
            }
            if (!vals.isEmpty()) {
                if (vals.size() == 1) {
                    result.append(allUpper ? " and m.test_program = ?" : " and UPPER(m.test_program) = ?");
                    result.params.add(vals.get(0));
                } else {
                    result.append(allUpper ? " and m.test_program IN (" : " and UPPER(m.test_program) IN (");
                    for (int i = 0; i < vals.size(); i++) {
                        if (i > 0) result.append(",");
                        result.append("?");
                        result.params.add(vals.get(i));
                    }
                    result.append(")");
                }
            }
        }

        if (equipmentIds != null && !equipmentIds.isEmpty()) {
            int cap = Math.min(equipmentIds.size(), 100);
            List<String> vals = new ArrayList<>();
            boolean allUpper = true;
            for (int i = 0; i < cap; i++) {
                String v = equipmentIds.get(i);
                if (v != null && !v.isBlank()) {
                    String trimmed = v.trim();
                    String upper = trimmed.toUpperCase(Locale.ROOT);
                    if (!trimmed.equals(upper)) allUpper = false;
                    vals.add(upper);
                }
            }
            if (!vals.isEmpty()) {
                if (vals.size() == 1) {
                    result.append(allUpper ? " and m.tester_id = ?" : " and UPPER(m.tester_id) = ?");
                    result.params.add(vals.get(0));
                } else {
                    result.append(allUpper ? " and m.tester_id IN (" : " and UPPER(m.tester_id) IN (");
                    for (int i = 0; i < vals.size(); i++) {
                        if (i > 0) result.append(",");
                        result.append("?");
                        result.params.add(vals.get(i));
                    }
                    result.append(")");
                }
            }
        }

        // Handle lots/wafer lists. Support up to 5 pairs; matching is case-insensitive.
        boolean hasLots = lots != null && !lots.isEmpty();
        boolean hasWafers = wafers != null && !wafers.isEmpty();
        if (hasLots && !hasWafers) {
            // If caller supplied a single lot value prefer '=' for clarity, otherwise use IN(...)
            int cap = Math.min(lots.size(), 100); // safety cap
            List<String> vals = new ArrayList<>();
            boolean allUpper = true;
            for (int i = 0; i < cap; i++) {
                String v = lots.get(i);
                if (v != null && !v.isBlank()) {
                    String trimmed = v.trim();
                    String upper = trimmed.toUpperCase(Locale.ROOT);
                    if (!trimmed.equals(upper)) allUpper = false;
                    vals.add(upper);
                }
            }
            if (!vals.isEmpty()) {
                if (vals.size() == 1) {
                    result.append(allUpper ? " and m.lot = ?" : " and UPPER(m.lot) = ?");
                    result.params.add(vals.get(0));
                } else {
                    result.append(allUpper ? " and m.lot IN (" : " and UPPER(m.lot) IN (");
                    for (int i = 0; i < vals.size(); i++) {
                        if (i > 0) result.append(",");
                        result.append("?");
                        result.params.add(vals.get(i));
                    }
                    result.append(")");
                }
            }
        } else if (!hasLots && hasWafers) {
            // If caller supplied a single wafer value prefer '=' for clarity, otherwise use IN(...)
            int cap = Math.min(wafers.size(), 100);
            List<String> vals = new ArrayList<>();
            boolean allUpper = true;
            for (int i = 0; i < cap; i++) {
                String v = wafers.get(i);
                if (v != null && !v.isBlank()) {
                    String trimmed = v.trim();
                    String upper = trimmed.toUpperCase(Locale.ROOT);
                    if (!trimmed.equals(upper)) allUpper = false;
                    vals.add(upper);
                }
            }
            if (!vals.isEmpty()) {
                if (vals.size() == 1) {
                    result.append(allUpper ? " and m.wafer = ?" : " and UPPER(m.wafer) = ?");
                    result.params.add(vals.get(0));
                } else {
                    result.append(allUpper ? " and m.wafer IN (" : " and UPPER(m.wafer) IN (");
                    for (int i = 0; i < vals.size(); i++) {
                        if (i > 0) result.append(",");
                        result.append("?");
                        result.params.add(vals.get(i));
                    }
                    result.append(")");
                }
            }
        } else if (hasLots && hasWafers) {
            // Pair-wise matching: for each pair support three cases:
            //  - both lot and wafer provided: (UPPER(lot)=? AND UPPER(wafer)=?)
            //  - only lot provided: (UPPER(lot)=?)
            //  - only wafer provided: (UPPER(wafer)=?)
            // Combine each pair clause with OR. Support up to 650 pairs.
            // iterate up to the maximum length provided (support differing list lengths)
            int numPairs = Math.min(Math.max(lots.size(), wafers.size()), 650);
            List<Object[]> pairData = new ArrayList<>();
            for (int i = 0; i < numPairs; i++) {
                String lotRaw = i < lots.size() ? lots.get(i) : null;
                String waferRaw = i < wafers.size() ? wafers.get(i) : null;

                // If both are empty, skip this entry
                if ((lotRaw == null || lotRaw.isBlank()) && (waferRaw == null || waferRaw.isBlank())) continue;

                // Split by comma to support multiple values in one field (Cartesian product)
                String[] lotSplit = (lotRaw == null || lotRaw.isBlank()) ? new String[]{null} : lotRaw.split(",");
                String[] waferSplit = (waferRaw == null || waferRaw.isBlank()) ? new String[]{null} : waferRaw.split(",");

                for (String l : lotSplit) {
                    for (String w : waferSplit) {
                        String lu = null;
                        boolean lIsUpper = false;
                        if (l != null && !l.isBlank()) {
                            String trimmed = l.trim();
                            lu = trimmed.toUpperCase(Locale.ROOT);
                            lIsUpper = trimmed.equals(lu);
                        }

                        String wu = null;
                        boolean wIsUpper = false;
                        if (w != null && !w.isBlank()) {
                            String trimmed = w.trim();
                            wu = trimmed.toUpperCase(Locale.ROOT);
                            wIsUpper = trimmed.equals(wu);
                        }

                        // Only add if not both null (null lot or null wafer alone is fine for wafer-only/lot-only matching)
                        if (lu != null || wu != null) {
                            pairData.add(new Object[]{lu, wu, lIsUpper, wIsUpper});
                        }

                        // Safety cap on total expanded pairs to avoid massive SQL or expression limit errors
                        if (pairData.size() >= 1000) break;
                    }
                    if (pairData.size() >= 1000) break;
                }
                if (pairData.size() >= 1000) break;
            }
            if (!pairData.isEmpty()) {
                if (pairData.size() == 1) {
                    // Single pair: emit simple AND-ed equality clauses where applicable
                    Object[] p = pairData.get(0);
                    String lu = (String) p[0];
                    String wu = (String) p[1];
                    boolean lUp = (Boolean) p[2];
                    boolean wUp = (Boolean) p[3];
                    if (lu != null && wu != null) {
                        result.append(" and " + (lUp ? "m.lot" : "UPPER(m.lot)") + " = ? and " + (wUp ? "m.wafer" : "UPPER(m.wafer)") + " = ?");
                        result.params.add(lu);
                        result.params.add(wu);
                    } else if (lu != null) {
                        result.append(" and " + (lUp ? "m.lot" : "UPPER(m.lot)") + " = ?");
                        result.params.add(lu);
                    } else {
                        result.append(" and " + (wUp ? "m.wafer" : "UPPER(m.wafer)") + " = ?");
                        result.params.add(wu);
                    }
                } else {
                    result.append(" and (");
                    int added = 0;
                    for (Object[] p : pairData) {
                        if (added > 0) result.append(" or ");
                        String lu = (String) p[0];
                        String wu = (String) p[1];
                        boolean lUp = (Boolean) p[2];
                        boolean wUp = (Boolean) p[3];
                        if (lu != null && wu != null) {
                            result.append("(" + (lUp ? "m.lot" : "UPPER(m.lot)") + " = ? and " + (wUp ? "m.wafer" : "UPPER(m.wafer)") + " = ?)");
                            result.params.add(lu);
                            result.params.add(wu);
                        } else if (lu != null) {
                            result.append("(" + (lUp ? "m.lot" : "UPPER(m.lot)") + " = ?)");
                            result.params.add(lu);
                        } else {
                            result.append("(" + (wUp ? "m.wafer" : "UPPER(m.wafer)") + " = ?)");
                            result.params.add(wu);
                        }
                        added++;
                    }
                    result.append(")");
                }
            }
        }

        appendDeviceFilter(result, devices);

        // Apply additional where_condition filters from DTP_DIST_CONF
        // These are sender-specific filters that must be applied to the metadata query
        applyWhereConditionFilters(result, additionalWhereFilters);

        if (log.isDebugEnabled()) {
            log.debug("Metadata query: {} params={} ", result.sql, result.params);
        }
        // Log which metadata view will be used so operators can see which underlying
        // table/view was chosen for the external query.
        try {
            String sel = effectiveSelect == null ? "" : effectiveSelect.toLowerCase(Locale.ROOT);
            String viewUsed;
            if (sel.contains("probe_metadata_view")) {
                viewUsed = "probe_metadata_view";
            } else if (sel.contains("ft_metadata_view")) {
                viewUsed = "ft_metadata_view";
            } else if (sel.contains("dtp_")) {
                // Extract the first dtp_* token for clearer logging
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("dtp_[a-z0-9_]+", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(sel);
                viewUsed = m.find() ? m.group(0) : "custom";
            } else if (sel.contains("all_metadata_view")) {
                viewUsed = "all_metadata_view";
            } else {
                viewUsed = "custom";
            }
            if (emitInfo && log.isInfoEnabled()) {
                log.info("Metadata query will use view='{}' dataType='{}'", viewUsed, dataType);
            }
        } catch (Exception ignore) {}
        return result;
    }

    private LocalDateTime readLocalDateTime(ResultSet rs, String column) {
        try {
            Timestamp ts = rs.getTimestamp(column);
            return ts == null ? null : ts.toLocalDateTime();
        } catch (Exception ignore) {
            return null;
        }
    }

    private PreparedStatement prepareStatement(Connection connection, SqlWithParams sql) throws Exception {
        PreparedStatement ps = connection.prepareStatement(sql.sql.toString());
        int idx = 1;
        for (Object param : sql.params) {
            if (param instanceof Timestamp ts) {
                ps.setTimestamp(idx++, ts);
            } else if (param instanceof Integer i) {
                ps.setInt(idx++, i);
            } else if (param instanceof Long l) {
                ps.setLong(idx++, l);
            } else {
                ps.setString(idx++, param == null ? null : param.toString());
            }
        }
        return ps;
    }

    private MetadataRow mapMetadataRow(ResultSet rs) throws Exception {
        String lot = rs.getString("lot");
        String metadataId;
        try {
            metadataId = rs.getString("metadata_id");
        } catch (Exception ex) {
            throw new IllegalStateException("Result set missing required column 'metadata_id'", ex);
        }
        if (metadataId == null || metadataId.isBlank()) {
            throw new IllegalStateException("Result set column 'metadata_id' was null or blank for lot=" + lot);
        }

        String idData;
        try {
            idData = rs.getString("id_data");
        } catch (Exception ex) {
            throw new IllegalStateException("Result set missing required column 'id_data'", ex);
        }
        if (idData == null || idData.isBlank()) {
            throw new IllegalStateException("Result set column 'id_data' was null or blank for lot=" + lot + ", metadata_id=" + metadataId);
        }
        Timestamp ts = rs.getTimestamp("end_time");
        LocalDateTime endTime = ts == null ? null : ts.toLocalDateTime();
        String wafer = null;
        try { wafer = rs.getString("wafer"); } catch (Exception ignore) {}
        String originalFileName = null;
        try { originalFileName = rs.getString("original_file_name"); } catch (Exception ignore) {}
        String device = null;
        try { device = rs.getString("device"); } catch (Exception ignore) {}
        String step = null;
        try { step = rs.getString("step"); } catch (Exception ignore) {}
        String testerId = null;
        try { testerId = rs.getString("tester_id"); } catch (Exception ignore) {}
        String testProgram = null;
        try { testProgram = rs.getString("test_program"); } catch (Exception ignore) {}
        return new MetadataRow(lot, metadataId, idData, endTime, wafer, originalFileName, device, step, testerId, testProgram);
    }

    private static final String PREVIEW_ROW_NUMBER =
            "ROW_NUMBER() OVER (PARTITION BY m.lot, NVL(TRIM(m.wafer), ' '), NVL(TRIM(f.file_name), ' ') "
                    + "ORDER BY m.end_time DESC NULLS LAST, m.id DESC) rn";

    /**
     * Preview rows are shown one line per lot+wafer+filename. External views can return multiple
     * metadata ids for the same file; keep only the newest row per business key.
     */
    private SqlWithParams buildPreviewDedupedPageQuery(String viewName,
                                                       boolean optimized,
                                                       LocalDateTime start,
                                                       LocalDateTime end,
                                                       String dataType,
                                                       String dataTypeExt,
                                                       String testPhase,
                                                       String testerType,
                                                       String location,
                                                       java.util.List<String> lots,
                                                       java.util.List<String> wafers,
                                                       java.util.List<String> devices,
                                                       java.util.List<String> steps,
                                                       java.util.List<String> recipes,
                                                       java.util.List<String> equipmentIds,
                                                       java.util.Map<String, java.util.List<String>> additionalWhereFilters) {
        String innerSelect = "select m.lot, m.id as metadata_id, m.id_data, m.end_time, m.wafer, m.device, f.file_name as original_file_name, m.step, m.tester_id, m.test_program, "
                + PREVIEW_ROW_NUMBER + " from " + viewName + " m left join dtp_file f on f.id = m.id_file";
        SqlWithParams ranked = optimized
                ? buildOptimizedMetadataQuery(innerSelect, start, end, dataType, lots, wafers, devices)
                : buildMetadataQuery(innerSelect, start, end, dataType, dataTypeExt, testPhase, testerType, location, lots, wafers, devices, steps, recipes, equipmentIds, additionalWhereFilters);
        ranked.append(") preview_ranked where rn = 1");

        SqlWithParams outer = new SqlWithParams(
                "select lot, metadata_id, id_data, end_time, wafer, device, original_file_name, step, tester_id, test_program from (");
        outer.sql.append(ranked.sql);
        outer.params.addAll(ranked.params);
        return outer;
    }

    private SqlWithParams buildPreviewDedupedCountQuery(String viewName,
                                                        boolean optimized,
                                                        LocalDateTime start,
                                                        LocalDateTime end,
                                                        String dataType,
                                                        String dataTypeExt,
                                                        String testPhase,
                                                        String testerType,
                                                        String location,
                                                        java.util.List<String> lots,
                                                        java.util.List<String> wafers,
                                                        java.util.List<String> devices,
                                                        java.util.List<String> steps,
                                                        java.util.List<String> recipes,
                                                        java.util.List<String> equipmentIds,
                                                        java.util.Map<String, java.util.List<String>> additionalWhereFilters) {
        String innerSelect = "select distinct m.lot, m.wafer, f.file_name as original_file_name from " + viewName + " m left join dtp_file f on f.id = m.id_file";
        SqlWithParams inner = optimized
                ? buildOptimizedMetadataQuery(innerSelect, start, end, dataType, lots, wafers, devices)
                : buildMetadataQuery(innerSelect, start, end, dataType, dataTypeExt, testPhase, testerType, location, lots, wafers, devices, steps, recipes, equipmentIds, additionalWhereFilters);

        SqlWithParams outer = new SqlWithParams("select count(*) from (");
        outer.sql.append(inner.sql);
        outer.params.addAll(inner.params);
        outer.append(")");
        return outer;
    }

    private void appendDeviceFilter(SqlWithParams result, List<String> devices) {
        if (devices == null || devices.isEmpty()) {
            return;
        }

        int cap = Math.min(devices.size(), 1000);
        List<String> vals = new ArrayList<>();
        boolean allUpper = true;
        for (int i = 0; i < cap; i++) {
            String v = devices.get(i);
            if (v != null && !v.isBlank()) {
                String trimmed = v.trim();
                String upper = trimmed.toUpperCase(Locale.ROOT);
                if (!trimmed.equals(upper)) {
                    allUpper = false;
                }
                vals.add(upper);
            }
        }
        if (vals.isEmpty()) {
            return;
        }

        String deviceExpr = allUpper ? "m.device" : "UPPER(m.device)";
        boolean hasWildcard = vals.stream().anyMatch(DevicePatternUtils::containsWildcard);

        if (vals.size() == 1 && !hasWildcard) {
            result.append(" and ").append(deviceExpr).append(" = ?");
            result.params.add(vals.get(0));
            return;
        }

        if (vals.size() == 1) {
            result.append(" and ").append(deviceExpr).append(" LIKE ? ESCAPE '\\'");
            result.params.add(DevicePatternUtils.toSqlLikePattern(vals.get(0)));
            return;
        }

        if (!hasWildcard) {
            result.append(" and ").append(deviceExpr).append(" IN (");
            for (int i = 0; i < vals.size(); i++) {
                if (i > 0) {
                    result.append(",");
                }
                result.append("?");
                result.params.add(vals.get(i));
            }
            result.append(")");
            return;
        }

        result.append(" and (");
        for (int i = 0; i < vals.size(); i++) {
            if (i > 0) {
                result.append(" OR ");
            }
            String val = vals.get(i);
            if (DevicePatternUtils.containsWildcard(val)) {
                result.append(deviceExpr).append(" LIKE ? ESCAPE '\\'");
                result.params.add(DevicePatternUtils.toSqlLikePattern(val));
            } else {
                result.append(deviceExpr).append(" = ?");
                result.params.add(val);
            }
        }
        result.append(")");
    }

    /**
     * Parse DTP_DIST_CONF.where_condition and extract field-level filters.
     * Returns a map where keys encode the filter type and field name.
     * 
     * Key format:
     * - "SUBSTR:field:pos:len" for SUBSTR patterns
     * - "IN:field" for IN patterns
     * - "LIKE:field" for LIKE patterns
     * - "EQ:field" for equality patterns
     * 
     * The where_condition can contain multiple EXISTS clauses, each with different field filters.
     * All field names in the where_condition match the column names in dtp_*_metadata tables.
     * 
     * Supported patterns:
     * - Simple equality: m.tester_id = 'X'
     * - SUBSTR patterns: SUBSTR(m.tester_id, 1, 1) IN ('C', 'D')
     * - IN clauses: m.field IN ('A', 'B', 'C')
     * - LIKE patterns: m.field like '%value%'
     * - Multiple EXISTS clauses separated by commas or AND
     * 
     * Example input: 
     * "EXISTS(SELECT * FROM dtp_defect_metadata m WHERE m.id = t.id_metadata AND SUBSTR(m.tester_id, 1, 1) IN ('C', 'D')), 
     *  EXISTS (SELECT * FROM all_metadata_view m WHERE m.id = t.id_metadata AND m.device like '%Eagle Test Systems Application%' and m.copy_status='PASS')"
     * Returns: {"SUBSTR:tester_id:1:1": ["C", "D"], "LIKE:device": ["%Eagle Test Systems Application%"], "EQ:copy_status": ["PASS"]}
     */
    public Map<String, List<String>> parseWhereCondition(String whereCondition) {
        Map<String, List<String>> filters = new LinkedHashMap<>();
        if (whereCondition == null || whereCondition.isBlank()) {
            return filters;
        }

        String wc = whereCondition.trim();

        // Split multiple EXISTS clauses
        // Pattern: EXISTS(...) possibly separated by commas or AND
        java.util.regex.Pattern existsPattern = java.util.regex.Pattern.compile(
                "EXISTS\\s*\\(\\s*SELECT\\s+\\*\\s+FROM\\s+\\w+\\s+m\\s+WHERE\\s+(.+?)\\)\\s*(?:,|AND|$)",
                java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.DOTALL);
        java.util.regex.Matcher existsMatcher = existsPattern.matcher(wc);

        while (existsMatcher.find()) {
            String whereClause = existsMatcher.group(1);
            if (whereClause != null) {
                parseWhereClauseFields(whereClause, filters);
            }
        }

        // If no EXISTS clauses found, try parsing the entire string as a simple WHERE clause
        if (filters.isEmpty()) {
            parseWhereClauseFields(wc, filters);
        }

        if (log.isDebugEnabled() && !filters.isEmpty()) {
            log.debug("Parsed where_condition filters: {}", filters);
        }
        return filters;
    }

    /**
     * Parse field filters from a WHERE clause string.
     * Handles all common Oracle SQL patterns:
     * - SUBSTR(m.field, pos, len) IN ('A', 'B')
     * - m.field IN ('A', 'B', 'C')
     * - m.field NOT IN ('A', 'B')
     * - m.field = 'value'
     * - m.field != 'value' or m.field <> 'value'
     * - m.field > 'value' or m.field >= 'value'
     * - m.field < 'value' or m.field <= 'value'
     * - m.field LIKE '%value%'
     * - m.field NOT LIKE '%value%'
     * - m.field BETWEEN 'a' AND 'b'
     * - m.field IS NULL / IS NOT NULL
     * - UPPER(m.field) = 'value' (case-insensitive comparisons)
     */
    private void parseWhereClauseFields(String whereClause, Map<String, List<String>> filters) {
        // Pattern 1: SUBSTR(m.field, pos, len) IN ('A', 'B', 'C')
        java.util.regex.Pattern substrInPattern = java.util.regex.Pattern.compile(
                "SUBSTR\\s*\\(\\s*m\\.(\\w+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*\\)\\s*IN\\s*\\(\\s*([^)]+)\\)",
                java.util.regex.Pattern.CASE_INSENSITIVE);
        java.util.regex.Matcher substrInMatcher = substrInPattern.matcher(whereClause);
        while (substrInMatcher.find()) {
            String fieldName = substrInMatcher.group(1);
            int pos = Integer.parseInt(substrInMatcher.group(2));
            int len = Integer.parseInt(substrInMatcher.group(3));
            String valuesStr = substrInMatcher.group(4);
            List<String> values = extractQuotedValues(valuesStr);
            if (!values.isEmpty()) {
                String key = "SUBSTR_IN:" + fieldName.toLowerCase() + ":" + pos + ":" + len;
                filters.putIfAbsent(key, values);
            }
        }

        // Pattern 2: SUBSTR(m.field, pos, len) = 'value'
        java.util.regex.Pattern substrEqPattern = java.util.regex.Pattern.compile(
                "SUBSTR\\s*\\(\\s*m\\.(\\w+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*\\)\\s*=\\s*'([^']+)'",
                java.util.regex.Pattern.CASE_INSENSITIVE);
        java.util.regex.Matcher substrEqMatcher = substrEqPattern.matcher(whereClause);
        while (substrEqMatcher.find()) {
            String fieldName = substrEqMatcher.group(1);
            int pos = Integer.parseInt(substrEqMatcher.group(2));
            int len = Integer.parseInt(substrEqMatcher.group(3));
            String value = substrEqMatcher.group(4);
            String key = "SUBSTR_EQ:" + fieldName.toLowerCase() + ":" + pos + ":" + len;
            filters.putIfAbsent(key, List.of(value));
        }

        // Pattern 3: UPPER(m.field) IN ('A', 'B', 'C')
        java.util.regex.Pattern upperInPattern = java.util.regex.Pattern.compile(
                "UPPER\\s*\\(\\s*m\\.(\\w+)\\s*\\)\\s*IN\\s*\\(\\s*([^)]+)\\)",
                java.util.regex.Pattern.CASE_INSENSITIVE);
        java.util.regex.Matcher upperInMatcher = upperInPattern.matcher(whereClause);
        while (upperInMatcher.find()) {
            String fieldName = upperInMatcher.group(1);
            String valuesStr = upperInMatcher.group(2);
            List<String> values = extractQuotedValues(valuesStr);
            if (!values.isEmpty()) {
                String key = "UPPER_IN:" + fieldName.toLowerCase();
                filters.putIfAbsent(key, values);
            }
        }

        // Pattern 4: UPPER(m.field) = 'value'
        java.util.regex.Pattern upperEqPattern = java.util.regex.Pattern.compile(
                "UPPER\\s*\\(\\s*m\\.(\\w+)\\s*\\)\\s*=\\s*'([^']+)'",
                java.util.regex.Pattern.CASE_INSENSITIVE);
        java.util.regex.Matcher upperEqMatcher = upperEqPattern.matcher(whereClause);
        while (upperEqMatcher.find()) {
            String fieldName = upperEqMatcher.group(1);
            String value = upperEqMatcher.group(2);
            String key = "UPPER_EQ:" + fieldName.toLowerCase();
            filters.putIfAbsent(key, List.of(value));
        }

        // Pattern 5: m.field NOT IN ('A', 'B', 'C')
        java.util.regex.Pattern notInPattern = java.util.regex.Pattern.compile(
                "m\\.(\\w+)\\s+NOT\\s+IN\\s*\\(\\s*([^)]+)\\)",
                java.util.regex.Pattern.CASE_INSENSITIVE);
        java.util.regex.Matcher notInMatcher = notInPattern.matcher(whereClause);
        while (notInMatcher.find()) {
            String fieldName = notInMatcher.group(1);
            String valuesStr = notInMatcher.group(2);
            List<String> values = extractQuotedValues(valuesStr);
            if (!values.isEmpty()) {
                String key = "NOT_IN:" + fieldName.toLowerCase();
                filters.putIfAbsent(key, values);
            }
        }

        // Pattern 6: m.field IN ('A', 'B', 'C')
        java.util.regex.Pattern inPattern = java.util.regex.Pattern.compile(
                "m\\.(\\w+)\\s+IN\\s*\\(\\s*([^)]+)\\)",
                java.util.regex.Pattern.CASE_INSENSITIVE);
        java.util.regex.Matcher inMatcher = inPattern.matcher(whereClause);
        while (inMatcher.find()) {
            String fieldName = inMatcher.group(1);
            String valuesStr = inMatcher.group(2);
            List<String> values = extractQuotedValues(valuesStr);
            if (!values.isEmpty()) {
                String key = "IN:" + fieldName.toLowerCase();
                filters.putIfAbsent(key, values);
            }
        }

        // Pattern 7: m.field BETWEEN 'a' AND 'b'
        java.util.regex.Pattern betweenPattern = java.util.regex.Pattern.compile(
                "m\\.(\\w+)\\s+BETWEEN\\s*'([^']+)'\\s+AND\\s*'([^']+)'",
                java.util.regex.Pattern.CASE_INSENSITIVE);
        java.util.regex.Matcher betweenMatcher = betweenPattern.matcher(whereClause);
        while (betweenMatcher.find()) {
            String fieldName = betweenMatcher.group(1);
            String value1 = betweenMatcher.group(2);
            String value2 = betweenMatcher.group(3);
            String key = "BETWEEN:" + fieldName.toLowerCase();
            filters.putIfAbsent(key, List.of(value1, value2));
        }

        // Pattern 8: m.field NOT LIKE '%value%'
        java.util.regex.Pattern notLikePattern = java.util.regex.Pattern.compile(
                "m\\.(\\w+)\\s+NOT\\s+LIKE\\s+'([^']+)'",
                java.util.regex.Pattern.CASE_INSENSITIVE);
        java.util.regex.Matcher notLikeMatcher = notLikePattern.matcher(whereClause);
        while (notLikeMatcher.find()) {
            String fieldName = notLikeMatcher.group(1);
            String value = notLikeMatcher.group(2);
            String key = "NOT_LIKE:" + fieldName.toLowerCase();
            filters.putIfAbsent(key, List.of(value));
        }

        // Pattern 9: m.field LIKE '%value%'
        java.util.regex.Pattern likePattern = java.util.regex.Pattern.compile(
                "m\\.(\\w+)\\s+LIKE\\s+'([^']+)'",
                java.util.regex.Pattern.CASE_INSENSITIVE);
        java.util.regex.Matcher likeMatcher = likePattern.matcher(whereClause);
        while (likeMatcher.find()) {
            String fieldName = likeMatcher.group(1);
            String value = likeMatcher.group(2);
            String key = "LIKE:" + fieldName.toLowerCase();
            filters.putIfAbsent(key, List.of(value));
        }

        // Pattern 10: m.field != 'value' or m.field <> 'value'
        java.util.regex.Pattern neqPattern = java.util.regex.Pattern.compile(
                "m\\.(\\w+)\\s*(?:!=|<>)\\s*'([^']+)'",
                java.util.regex.Pattern.CASE_INSENSITIVE);
        java.util.regex.Matcher neqMatcher = neqPattern.matcher(whereClause);
        while (neqMatcher.find()) {
            String fieldName = neqMatcher.group(1);
            String value = neqMatcher.group(2);
            String key = "NEQ:" + fieldName.toLowerCase();
            filters.putIfAbsent(key, List.of(value));
        }

        // Pattern 11: m.field >= 'value' or m.field > 'value'
        java.util.regex.Pattern gtPattern = java.util.regex.Pattern.compile(
                "m\\.(\\w+)\\s*(>=|>)\\s*'([^']+)'",
                java.util.regex.Pattern.CASE_INSENSITIVE);
        java.util.regex.Matcher gtMatcher = gtPattern.matcher(whereClause);
        while (gtMatcher.find()) {
            String fieldName = gtMatcher.group(1);
            String op = gtMatcher.group(2);
            String value = gtMatcher.group(3);
            String key = (">=".equals(op) ? "GTE:" : "GT:") + fieldName.toLowerCase();
            filters.putIfAbsent(key, List.of(value));
        }

        // Pattern 12: m.field <= 'value' or m.field < 'value'
        java.util.regex.Pattern ltPattern = java.util.regex.Pattern.compile(
                "m\\.(\\w+)\\s*(<=|<)\\s*'([^']+)'",
                java.util.regex.Pattern.CASE_INSENSITIVE);
        java.util.regex.Matcher ltMatcher = ltPattern.matcher(whereClause);
        while (ltMatcher.find()) {
            String fieldName = ltMatcher.group(1);
            String op = ltMatcher.group(2);
            String value = ltMatcher.group(3);
            String key = ("<=".equals(op) ? "LTE:" : "LT:") + fieldName.toLowerCase();
            filters.putIfAbsent(key, List.of(value));
        }

        // Pattern 13: m.field = 'value'
        java.util.regex.Pattern eqPattern = java.util.regex.Pattern.compile(
                "m\\.(\\w+)\\s*=\\s*'([^']+)'",
                java.util.regex.Pattern.CASE_INSENSITIVE);
        java.util.regex.Matcher eqMatcher = eqPattern.matcher(whereClause);
        while (eqMatcher.find()) {
            String fieldName = eqMatcher.group(1);
            String value = eqMatcher.group(2);
            String key = "EQ:" + fieldName.toLowerCase();
            filters.putIfAbsent(key, List.of(value));
        }

        // Pattern 14: m.field IS NOT NULL
        java.util.regex.Pattern isNotNullPattern = java.util.regex.Pattern.compile(
                "m\\.(\\w+)\\s+IS\\s+NOT\\s+NULL",
                java.util.regex.Pattern.CASE_INSENSITIVE);
        java.util.regex.Matcher isNotNullMatcher = isNotNullPattern.matcher(whereClause);
        while (isNotNullMatcher.find()) {
            String fieldName = isNotNullMatcher.group(1);
            String key = "IS_NOT_NULL:" + fieldName.toLowerCase();
            filters.putIfAbsent(key, List.of());
        }

        // Pattern 15: m.field IS NULL
        java.util.regex.Pattern isNullPattern = java.util.regex.Pattern.compile(
                "m\\.(\\w+)\\s+IS\\s+NULL",
                java.util.regex.Pattern.CASE_INSENSITIVE);
        java.util.regex.Matcher isNullMatcher = isNullPattern.matcher(whereClause);
        while (isNullMatcher.find()) {
            String fieldName = isNullMatcher.group(1);
            String key = "IS_NULL:" + fieldName.toLowerCase();
            filters.putIfAbsent(key, List.of());
        }
    }

    /**
     * Extract quoted values from an IN clause string.
     * Example: "'C', 'D'" -> ["C", "D"]
     */
    private List<String> extractQuotedValues(String valuesStr) {
        List<String> values = new ArrayList<>();
        java.util.regex.Pattern quotePattern = java.util.regex.Pattern.compile("'([^']+)'");
        java.util.regex.Matcher matcher = quotePattern.matcher(valuesStr);
        while (matcher.find()) {
            values.add(matcher.group(1));
        }
        return values;
    }

    /**
     * Apply parsed where_condition filters as AND predicates to the SQL builder.
     * This modifies the SqlWithParams in-place by appending AND conditions.
     * 
     * Key format in the map:
     * - "SUBSTR_IN:field:pos:len" → AND SUBSTR(m.field, pos, len) IN (?,?)
     * - "SUBSTR_EQ:field:pos:len" → AND SUBSTR(m.field, pos, len) = ?
     * - "UPPER_IN:field" → AND UPPER(m.field) IN (?,?)
     * - "UPPER_EQ:field" → AND UPPER(m.field) = ?
     * - "NOT_IN:field" → AND m.field NOT IN (?,?)
     * - "IN:field" → AND m.field IN (?,?)
     * - "BETWEEN:field" → AND m.field BETWEEN ? AND ?
     * - "NOT_LIKE:field" → AND m.field NOT LIKE ?
     * - "LIKE:field" → AND m.field LIKE ?
     * - "NEQ:field" → AND m.field != ?
     * - "GTE:field" → AND m.field >= ?
     * - "GT:field" → AND m.field > ?
     * - "LTE:field" → AND m.field <= ?
     * - "LT:field" → AND m.field < ?
     * - "EQ:field" → AND m.field = ?
     * - "IS_NOT_NULL:field" → AND m.field IS NOT NULL
     * - "IS_NULL:field" → AND m.field IS NULL
     * 
     * @param sql The SQL builder to modify
     * @param filters Map from parseWhereCondition
     */
    public void applyWhereConditionFilters(SqlWithParams sql, Map<String, List<String>> filters) {
        if (filters == null || filters.isEmpty()) {
            return;
        }

        for (Map.Entry<String, List<String>> entry : filters.entrySet()) {
            String key = entry.getKey();
            List<String> values = entry.getValue();

            String[] parts = key.split(":");
            String type = parts[0];
            String fieldName = parts[1];

            switch (type) {
                case "SUBSTR_IN":
                    // SUBSTR(m.field, pos, len) IN (?, ?)
                    int substrInPos = Integer.parseInt(parts[2]);
                    int substrInLen = Integer.parseInt(parts[3]);
                    sql.append(" and SUBSTR(m." + fieldName + ", " + substrInPos + ", " + substrInLen + ") in (");
                    for (int i = 0; i < values.size(); i++) {
                        if (i > 0) sql.append(",");
                        sql.append("?");
                        sql.params.add(values.get(i));
                    }
                    sql.append(")");
                    break;

                case "SUBSTR_EQ":
                    // SUBSTR(m.field, pos, len) = ?
                    int substrEqPos = Integer.parseInt(parts[2]);
                    int substrEqLen = Integer.parseInt(parts[3]);
                    sql.append(" and SUBSTR(m." + fieldName + ", " + substrEqPos + ", " + substrEqLen + ") = ?");
                    sql.params.add(values.get(0));
                    break;

                case "UPPER_IN":
                    // UPPER(m.field) IN (?, ?)
                    sql.append(" and UPPER(m." + fieldName + ") in (");
                    for (int i = 0; i < values.size(); i++) {
                        if (i > 0) sql.append(",");
                        sql.append("?");
                        sql.params.add(values.get(i));
                    }
                    sql.append(")");
                    break;

                case "UPPER_EQ":
                    // UPPER(m.field) = ?
                    sql.append(" and UPPER(m." + fieldName + ") = ?");
                    sql.params.add(values.get(0));
                    break;

                case "NOT_IN":
                    // m.field NOT IN (?, ?)
                    sql.append(" and m." + fieldName + " not in (");
                    for (int i = 0; i < values.size(); i++) {
                        if (i > 0) sql.append(",");
                        sql.append("?");
                        sql.params.add(values.get(i));
                    }
                    sql.append(")");
                    break;

                case "IN":
                    // m.field IN (?, ?)
                    sql.append(" and m." + fieldName + " in (");
                    for (int i = 0; i < values.size(); i++) {
                        if (i > 0) sql.append(",");
                        sql.append("?");
                        sql.params.add(values.get(i));
                    }
                    sql.append(")");
                    break;

                case "BETWEEN":
                    // m.field BETWEEN ? AND ?
                    sql.append(" and m." + fieldName + " between ? and ?");
                    sql.params.add(values.get(0));
                    sql.params.add(values.get(1));
                    break;

                case "NOT_LIKE":
                    // m.field NOT LIKE ?
                    sql.append(" and m." + fieldName + " not like ?");
                    sql.params.add(values.get(0));
                    break;

                case "LIKE":
                    // m.field LIKE ?
                    if (values.size() == 1) {
                        sql.append(" and m." + fieldName + " like ?");
                        sql.params.add(values.get(0));
                    } else {
                        sql.append(" and (");
                        for (int i = 0; i < values.size(); i++) {
                            if (i > 0) sql.append(" or ");
                            sql.append("m." + fieldName + " like ?");
                            sql.params.add(values.get(i));
                        }
                        sql.append(")");
                    }
                    break;

                case "NEQ":
                    // m.field != ?
                    sql.append(" and m." + fieldName + " != ?");
                    sql.params.add(values.get(0));
                    break;

                case "GTE":
                    // m.field >= ?
                    sql.append(" and m." + fieldName + " >= ?");
                    sql.params.add(values.get(0));
                    break;

                case "GT":
                    // m.field > ?
                    sql.append(" and m." + fieldName + " > ?");
                    sql.params.add(values.get(0));
                    break;

                case "LTE":
                    // m.field <= ?
                    sql.append(" and m." + fieldName + " <= ?");
                    sql.params.add(values.get(0));
                    break;

                case "LT":
                    // m.field < ?
                    sql.append(" and m." + fieldName + " < ?");
                    sql.params.add(values.get(0));
                    break;

                case "EQ":
                    // m.field = ?
                    sql.append(" and m." + fieldName + " = ?");
                    sql.params.add(values.get(0));
                    break;

                case "IS_NOT_NULL":
                    // m.field IS NOT NULL
                    sql.append(" and m." + fieldName + " is not null");
                    break;

                case "IS_NULL":
                    // m.field IS NULL
                    sql.append(" and m." + fieldName + " is null");
                    break;
            }
        }
    }

    private static class SqlWithParams {
        final StringBuilder sql;
        final List<Object> params = new ArrayList<>();

        SqlWithParams(String base) {
            this.sql = new StringBuilder(base);
        }

        SqlWithParams append(String fragment) {
            this.sql.append(fragment);
            return this;
        }

        String format() {
            if (params.isEmpty()) {
                return sql.toString();
            }
            return sql + " /* params=" + params + " */";
        }
    }
}
