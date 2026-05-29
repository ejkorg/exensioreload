package com.onsemi.cim.apps.exensio.exensioreload.repository;

import com.onsemi.cim.apps.exensio.exensioreload.config.ExternalDbConfig;
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
import java.util.List;
import java.util.Locale;

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
    public List<MetadataRow> findMetadata(String site, String environment, LocalDateTime start, LocalDateTime end, String dataType, String dataTypeExt, String testPhase, String testerType, String location, java.util.List<String> lots, java.util.List<String> wafers, int limit) {
        List<MetadataRow> rows = new ArrayList<>();
        streamMetadata(site, environment, start, end, dataType, dataTypeExt, testPhase, testerType, location, lots, wafers, limit, rows::add);
        return rows;
    }

    @Override
    public List<MetadataRow> findMetadataPage(String site, String environment, LocalDateTime start, LocalDateTime end,
                                              String dataType, String dataTypeExt, String testPhase, String testerType, String location, java.util.List<String> lots, java.util.List<String> wafers,
                                              int offset, int limit) {
        // OPTIMIZATION: Use the optimized query builder if non-indexed filters are not provided
        // This significantly speeds up queries when users don't specify testerType, testPhase, location, dataTypeExt
        String viewName = getPreviewViewName(dataType);

        boolean hasOptionalFilters = (dataTypeExt != null && !dataTypeExt.isBlank()) ||
                (testPhase != null && !testPhase.isBlank()) ||
                (testerType != null && !testerType.isBlank()) ||
                (location != null && !location.isBlank());

        SqlWithParams sql;
        if (!hasOptionalFilters) {
            // Fast path: Use optimized query (indexed columns only)
            sql = buildPreviewDedupedPageQuery(viewName, true, start, end, dataType, null, null, null, null, lots, wafers);
            if (log.isDebugEnabled()) {
                log.debug("Using optimized query path for findMetadataPage (no optional filters)");
            }
        } else {
            // Full path: Apply all filters if optional filters are provided
            sql = buildPreviewDedupedPageQuery(viewName, false, start, end, dataType, dataTypeExt, testPhase, testerType, location, lots, wafers);
        }

        sql.append(" order by end_time desc");
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
                                             java.util.List<String> lots, java.util.List<String> wafers) {
        // OPTIMIZATION: Build a simpler query using ONLY indexed columns for summary queries
        // This avoids expensive filters on non-indexed columns (testerType, testPhase, dataTypeExt, location)
        // which were likely null anyway after the MetadataImporterService optimization.
        String viewName = getPreviewViewName(dataType);
        SqlWithParams sql = buildOptimizedSummaryQuery(
                "select count(*) as total_count, min(end_time) as min_end_time, max(end_time) as max_end_time from " + viewName,
                start, end,
                dataType,
                lots, wafers);  // Only pass indexed columns
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
                                                        java.util.List<String> lots, java.util.List<String> wafers,
                                                        int offset, int limit) {
        // Use COUNT(*) OVER() window function to get total count with each row
        String viewName = getPreviewViewName(dataType);
        SqlWithParams sql = buildMetadataQuery(
                "select DISTINCT lot, id as metadata_id, id_data, end_time, wafer, original_file_name, COUNT(*) OVER() as total_count from " + viewName,
                start, end, dataType, dataTypeExt, testPhase, testerType, location, lots, wafers);
        sql.append(" order by end_time desc");
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
    public String describePreviewQuery(LocalDateTime start,
                                       LocalDateTime end,
                                       String dataType,
                                       String dataTypeExt,
                                       String testPhase,
                                       String testerType,
                                       String location,
                                       java.util.List<String> lots,
                                       java.util.List<String> wafers,
                                       int offset,
                                       int limit) {
        // Use the internal builder variant with emitInfo=false so describing
        // the query doesn't produce the same INFO logs as the executing call.
        String viewName = getPreviewViewName(dataType);
        SqlWithParams sql = buildMetadataQueryInternal("select lot, id as metadata_id, id_data, end_time, wafer, original_file_name from " + viewName,
                start, end, null, /* dataTypeExt */ null, /* testPhase */ null, testerType, /* location */ null, lots, wafers, false, this.forceAllMetadataView);
        sql.append(" order by end_time desc");
        if (limit > 0) {
            sql.append(" offset ? rows fetch next ? rows only");
            sql.params.add(Math.max(offset, 0));
            sql.params.add(limit);
        }
        return sql.format();
    }

    @Override
    public long countMetadata(String site, String environment, LocalDateTime start, LocalDateTime end, String dataType, String dataTypeExt, String testPhase, String testerType, String location, java.util.List<String> lots, java.util.List<String> wafers) {
        String viewName = getPreviewViewName(dataType);
        boolean hasOptionalFilters = (dataTypeExt != null && !dataTypeExt.isBlank()) ||
                (testPhase != null && !testPhase.isBlank()) ||
                (testerType != null && !testerType.isBlank()) ||
                (location != null && !location.isBlank());
        SqlWithParams sql = buildPreviewDedupedCountQuery(viewName, !hasOptionalFilters, start, end, dataType, dataTypeExt, testPhase, testerType, location, lots, wafers);
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
    public void streamMetadata(String site, String environment, LocalDateTime start, LocalDateTime end, String dataType, String dataTypeExt, String testPhase, String testerType, String location, java.util.List<String> lots, java.util.List<String> wafers, int limit, java.util.function.Consumer<MetadataRow> consumer) {
        try (Connection c = externalDbConfig.getConnection(site, environment)) {
            streamMetadataWithConnection(c, start, end, dataType, dataTypeExt, testPhase, testerType, location, lots, wafers, limit, consumer);
        } catch (Exception ex) {
            log.error("Failed streaming metadata for site {} env {}: {}", site, environment, ex.getMessage(), ex);
            throw new RuntimeException("External metadata read failed", ex);
        }
    }

    @Override
    public void streamMetadataWithConnection(Connection c, LocalDateTime start, LocalDateTime end, String dataType, String dataTypeExt, String testPhase, String testerType, String location, java.util.List<String> lots, java.util.List<String> wafers, int limit, java.util.function.Consumer<MetadataRow> consumer) {
        PreparedStatement ps = null;
        ResultSet rs = null;
        try {
            SqlWithParams sql = buildMetadataQuery("select DISTINCT lot, id as metadata_id, id_data, end_time, wafer, original_file_name from all_metadata_view",
                    start, end, dataType, dataTypeExt, testPhase, testerType, location, lots, wafers);
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
     * Preview queries use the dtp_* views instead of the legacy *_metadata_view sources.
     */
    private String getPreviewViewName(String dataType) {
        if (dataType == null || dataType.isBlank()) {
            return "dtp_all_view";
        }
        String dtNorm = dataType.trim().toLowerCase(Locale.ROOT);
        switch (dtNorm) {
            case "ft":
            case "functional":
            case "functionaltest":
                return "dtp_ft_view";
            case "probe":
                return "dtp_probe_view";
            case "defect":
                return "dtp_defect_view";
            case "pcm":
                return "dtp_pcm_view";
            case "met":
                return "dtp_met_view";
            case "map":
                return "dtp_map_view";
            case "leh":
                return "dtp_leh_view";
            case "historical":
                return "dtp_historical_view";
            default:
                return "dtp_" + dtNorm + "_view";
        }
    }

    private SqlWithParams buildMetadataQuery(String select, LocalDateTime start, LocalDateTime end,
                                             String dataType, String dataTypeExt, String testPhase, String testerType, String location,
                                             java.util.List<String> lots, java.util.List<String> wafers) {
        return buildMetadataQueryInternal(select, start, end, dataType, dataTypeExt, testPhase, testerType, location, lots, wafers, true, this.forceAllMetadataView);
    }

    /**
     * OPTIMIZED: Build a query using ONLY indexed columns (date range, lots, wafers, dataType).
     * Avoids expensive filters on non-indexed columns (testerType, testPhase, dataTypeExt, location).
     * Used for both:
     * 1. Historical summary queries (fast count + date range)
     * 2. Non-historical preview queries when optional filters not provided
     */
    private SqlWithParams buildOptimizedMetadataQuery(String select, LocalDateTime start, LocalDateTime end,
                                                      String dataType, java.util.List<String> lots, java.util.List<String> wafers) {
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
            result = new SqlWithParams(effectiveSelect + " where end_time BETWEEN ? AND ?");
            result.params.add(Timestamp.valueOf(start));
            result.params.add(Timestamp.valueOf(end));
        } else if (start != null) {
            result = new SqlWithParams(effectiveSelect + " where end_time >= ?");
            result.params.add(Timestamp.valueOf(start));
        } else {
            result = new SqlWithParams(effectiveSelect + " where end_time <= ?");
            result.params.add(Timestamp.valueOf(end));
        }

        // Add dataType filter ONLY if using all_metadata_view
        boolean shouldFilterDataType = forceAllMetadataView || (effectiveSelect.toLowerCase().contains("all_metadata_view"));
        if (shouldFilterDataType && dataType != null && !dataType.isBlank()) {
            String dtUpper = dataType.trim().toUpperCase(Locale.ROOT);
            if (dataType.equals(dtUpper)) {
                result.append(" and data_type = ?");
                result.params.add(dtUpper);
            } else {
                result.append(" and UPPER(data_type) = ?");
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
                    result.append(allUpper ? " and lot = ?" : " and UPPER(lot) = ?");
                    result.params.add(vals.get(0));
                } else {
                    result.append(allUpper ? " and lot IN (" : " and UPPER(lot) IN (");
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
                    result.append(allUpper ? " and wafer = ?" : " and UPPER(wafer) = ?");
                    result.params.add(vals.get(0));
                } else {
                    result.append(allUpper ? " and wafer IN (" : " and UPPER(wafer) IN (");
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
                    result.append("UPPER(lot) = ?");
                    result.params.add(lu);
                } else {
                    result.append("1=1");
                }

                result.append(" and ");

                if (waferRaw != null && !waferRaw.isBlank()) {
                    String wu = waferRaw.trim().toUpperCase(Locale.ROOT);
                    result.append("UPPER(wafer) = ?");
                    result.params.add(wu);
                } else {
                    result.append("1=1");
                }

                result.append(")");
                first = false;
            }
            result.append(")");
        }

        if (log.isDebugEnabled()) {
            log.debug("Optimized query: {}", result.format());
        }
        return result;
    }

    private SqlWithParams buildOptimizedSummaryQuery(String select, LocalDateTime start, LocalDateTime end,
                                                     String dataType, java.util.List<String> lots, java.util.List<String> wafers) {
        // REFACTORED: Now reuses the generic buildOptimizedMetadataQuery() logic
        // Summary query is just a SELECT COUNT(...) using the same indexed-column-only approach
        return buildOptimizedMetadataQuery(select, start, end, dataType, lots, wafers);
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
                                                     boolean emitInfo,
                                                     boolean forceAllView) {
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
        SqlWithParams result;
        if (start == null && end == null) {
            result = new SqlWithParams(effectiveSelect + " where 1=1");
        } else if (start != null && end != null) {
            result = new SqlWithParams(effectiveSelect + " where end_time BETWEEN ? AND ?");
            result.params.add(Timestamp.valueOf(start));
            result.params.add(Timestamp.valueOf(end));
        } else if (start != null) {
            result = new SqlWithParams(effectiveSelect + " where end_time >= ?");
            result.params.add(Timestamp.valueOf(start));
        } else {
            // end != null
            result = new SqlWithParams(effectiveSelect + " where end_time <= ?");
            result.params.add(Timestamp.valueOf(end));
        }
        // If still on all_metadata_view and a dataType was provided, add a predicate.
        if (addDataTypePredicate) {
            String dt = dataType == null ? "" : dataType.trim();
            String dtUpper = dt.toUpperCase(Locale.ROOT);
            // Prefer `data_type = ?` when the provided value is already uppercased
            // so we avoid applying functions on the column and preserve index usage.
            if (!dt.isEmpty() && dt.equals(dtUpper)) {
                result.append(" and data_type = ?");
                result.params.add(dtUpper);
                if (log.isInfoEnabled()) {
                    log.info("Applying dataType equality filter (no UPPER) to metadata query: dataType='{}'", dtUpper);
                }
            } else {
                result.append(" and UPPER(data_type) = ?");
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
            String colName = isLegacyView ? "test_phase" : "data_type_ext";
            
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
                result.append(" and test_phase = ?");
                result.params.add(tpUpper);
            } else {
                result.append(" and UPPER(test_phase) = ?");
                result.params.add(tpUpper);
            }
        }
        if (testerType != null && !testerType.isBlank() && !"NULL".equalsIgnoreCase(testerType) && !"NONE".equalsIgnoreCase(testerType) && !"ANY".equalsIgnoreCase(testerType)) {
            String tt = testerType.trim();
            String ttUpper = tt.toUpperCase(Locale.ROOT);
            if (tt.equals(ttUpper)) {
                result.append(" and tester_type = ?");
                result.params.add(ttUpper);
            } else {
                result.append(" and UPPER(tester_type) = ?");
                result.params.add(ttUpper);
            }
        }
        if (location != null && !location.isBlank()) {
            result.append(" and location = ?");
            result.params.add(location);
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
                    result.append(allUpper ? " and lot = ?" : " and UPPER(lot) = ?");
                    result.params.add(vals.get(0));
                } else {
                    result.append(allUpper ? " and lot IN (" : " and UPPER(lot) IN (");
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
                    result.append(allUpper ? " and wafer = ?" : " and UPPER(wafer) = ?");
                    result.params.add(vals.get(0));
                } else {
                    result.append(allUpper ? " and wafer IN (" : " and UPPER(wafer) IN (");
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
                        result.append(" and " + (lUp ? "lot" : "UPPER(lot)") + " = ? and " + (wUp ? "wafer" : "UPPER(wafer)") + " = ?");
                        result.params.add(lu);
                        result.params.add(wu);
                    } else if (lu != null) {
                        result.append(" and " + (lUp ? "lot" : "UPPER(lot)") + " = ?");
                        result.params.add(lu);
                    } else {
                        result.append(" and " + (wUp ? "wafer" : "UPPER(wafer)") + " = ?");
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
                            result.append("(" + (lUp ? "lot" : "UPPER(lot)") + " = ? and " + (wUp ? "wafer" : "UPPER(wafer)") + " = ?)");
                            result.params.add(lu);
                            result.params.add(wu);
                        } else if (lu != null) {
                            result.append("(" + (lUp ? "lot" : "UPPER(lot)") + " = ?)");
                            result.params.add(lu);
                        } else {
                            result.append("(" + (wUp ? "wafer" : "UPPER(wafer)") + " = ?)");
                            result.params.add(wu);
                        }
                        added++;
                    }
                    result.append(")");
                }
            }
        }
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
        return new MetadataRow(lot, metadataId, idData, endTime, wafer, originalFileName);
    }

    private static final String PREVIEW_ROW_NUMBER =
            "ROW_NUMBER() OVER (PARTITION BY lot, NVL(TRIM(wafer), ' '), NVL(TRIM(original_file_name), ' ') "
                    + "ORDER BY end_time DESC NULLS LAST, id DESC) rn";

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
                                                       java.util.List<String> wafers) {
        String innerSelect = "select lot, id as metadata_id, id_data, end_time, wafer, original_file_name, "
                + PREVIEW_ROW_NUMBER + " from " + viewName;
        SqlWithParams ranked = optimized
                ? buildOptimizedMetadataQuery(innerSelect, start, end, dataType, lots, wafers)
                : buildMetadataQuery(innerSelect, start, end, dataType, dataTypeExt, testPhase, testerType, location, lots, wafers);
        ranked.append(") preview_ranked where rn = 1");

        SqlWithParams outer = new SqlWithParams(
                "select lot, metadata_id, id_data, end_time, wafer, original_file_name from (");
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
                                                        java.util.List<String> wafers) {
        String innerSelect = "select distinct lot, wafer, original_file_name from " + viewName;
        SqlWithParams inner = optimized
                ? buildOptimizedMetadataQuery(innerSelect, start, end, dataType, lots, wafers)
                : buildMetadataQuery(innerSelect, start, end, dataType, dataTypeExt, testPhase, testerType, location, lots, wafers);

        SqlWithParams outer = new SqlWithParams("select count(*) from (");
        outer.sql.append(inner.sql);
        outer.params.addAll(inner.params);
        outer.append(")");
        return outer;
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
