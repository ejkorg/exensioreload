package com.onsemi.cim.apps.exensio.exensioreload.service;

import com.onsemi.cim.apps.exensio.exensioreload.config.ExternalDbConfig;
import com.onsemi.cim.apps.exensio.exensioreload.dto.LotWaferProgress;
import com.onsemi.cim.apps.exensio.exensioreload.dto.SessionAnalyticsResponse;
import com.onsemi.cim.apps.exensio.exensioreload.dto.SessionDailyStatusPoint;
import com.onsemi.cim.apps.exensio.exensioreload.dto.SessionLotWaferDailyPoint;
import com.onsemi.cim.apps.exensio.exensioreload.dto.SessionLotWaferPairTotal;
import com.onsemi.cim.apps.exensio.exensioreload.dto.StageRecordPage;
import com.onsemi.cim.apps.exensio.exensioreload.dto.StageRecordView;
import com.onsemi.cim.apps.exensio.exensioreload.dto.StagingSessionDetail;
import com.onsemi.cim.apps.exensio.exensioreload.dto.StagingSessionSummary;
import com.onsemi.cim.apps.exensio.exensioreload.service.EtlSshTriggerService;
import com.onsemi.cim.apps.exensio.exensioreload.service.TriggerResult;
import com.onsemi.cim.apps.exensio.exensioreload.stage.StageRecord;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.util.StringUtils;

@Service
public class StageSessionService {
    private static final Logger log = LoggerFactory.getLogger(StageSessionService.class);

    private final RefDbService refDbService;
    private final ExternalDbConfig externalDbConfig;
    private final DataSource dataSource;
    private final StagePipelineOrchestrator pipelineOrchestrator;
    private final EtlSshTriggerService etlSshTriggerService;

    public StageSessionService(RefDbService refDbService, ExternalDbConfig externalDbConfig,
                               StagePipelineOrchestrator pipelineOrchestrator,
                               EtlSshTriggerService etlSshTriggerService) {
        this.refDbService = refDbService;
        this.externalDbConfig = externalDbConfig;
        this.dataSource = refDbService.getDataSource();
        this.pipelineOrchestrator = pipelineOrchestrator;
        this.etlSshTriggerService = etlSshTriggerService;
    }

    @PostConstruct
    public void initialize() {
        ensureStagingSessionTable();
    }

    public com.onsemi.cim.apps.exensio.exensioreload.dto.CreateSessionResponse createSession(String username, String site, int senderId, String senderName, String environment) {
        String sessionId = UUID.randomUUID().toString();
        String sql = "INSERT INTO staging_session (id, username, site, sender_id, sender_name, environment, total_files, files_staged, files_enqueued, files_done, files_failed, status, created_at, updated_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, 0, 0, 0, 0, 0, 'STAGING', ?, ?)";
        Instant now = Instant.now();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            ps.setString(2, normalizeUser(username));
            ps.setString(3, site);
            ps.setInt(4, senderId);
            ps.setString(5, normalize(senderName));
            ps.setString(6, normalize(environment));
            ps.setTimestamp(7, Timestamp.from(now));
            ps.setTimestamp(8, Timestamp.from(now));
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to create staging session", ex);
        }

        // Call ETL SSH trigger after session creation
        TriggerResult triggerResult = null;
        try {
            // Prefer site-based key (matches etlservers.yml entries like CEBU-PROD); API may pass real cpConfig instead.
            String senderConfigName = resolveEtlSenderConfigName(site, senderId);
            triggerResult = etlSshTriggerService.execute(
                    sessionId,  // requestId
                    username,   // userId
                    site,       // site
                    null,       // location (optional)
                    senderConfigName  // sender config name
            );

            // Log trigger result for audit
            if (triggerResult != null) {
                log.info("ETL trigger executed for session {}: status={}, message={}",
                        sessionId, triggerResult.getStatus(), triggerResult.getMessage());
            }
        } catch (Exception e) {
            // ETL trigger should never fail the staging session
            log.warn("ETL trigger failed for session {}: {}", sessionId, e.getMessage());
        }

        return com.onsemi.cim.apps.exensio.exensioreload.dto.CreateSessionResponse.fromTrigger(sessionId, triggerResult);
    }

    public void refreshCounters(String sessionId) {
        StagingSessionDetail detail = getSessionRaw(sessionId);
        if (detail == null) {
            return;
        }
        refreshCounters(sessionId, detail.status());
    }

    public void refreshCounters(String sessionId, String currentStatus) {
        StatusCounts counts = loadCounts(sessionId);
        String nextStatus = resolveStatus(currentStatus, counts);
        Instant now = Instant.now();
        boolean terminal = isTerminal(nextStatus);
        String updateSql = "UPDATE staging_session SET total_files = ?, files_staged = ?, files_enqueued = ?, files_done = ?, files_failed = ?, status = ?, updated_at = ?, completed_at = ?, last_checked_at = ? WHERE id = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(updateSql)) {
            ps.setLong(1, counts.total());
            ps.setLong(2, counts.staged());
            ps.setLong(3, counts.enqueued());
            ps.setLong(4, counts.done());
            ps.setLong(5, counts.failed());
            ps.setString(6, nextStatus);
            ps.setTimestamp(7, Timestamp.from(now));
            if (terminal) {
                ps.setTimestamp(8, Timestamp.from(now));
            } else {
                ps.setNull(8, java.sql.Types.TIMESTAMP);
            }
            ps.setTimestamp(9, Timestamp.from(now));
            ps.setString(10, sessionId);
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed refreshing staging session counters", ex);
        }
    }

    public StagingSessionDetail getSession(String sessionId, String username) {
        StagingSessionDetail detail = getOwnedSession(sessionId, username);
        if (detail == null) {
            return null;
        }
        refreshCounters(sessionId, detail.status());
        return getOwnedSession(sessionId, username);
    }

    public List<StagingSessionSummary> getUserSessions(String username, int page, int size,
                                                       String q,
                                                       Integer senderId,
                                                       String sessionId,
                                                       String site,
                                                       String status) {
        int resolvedPage = Math.max(page, 0);
        int resolvedSize = size <= 0 ? 20 : Math.min(size, 200);
        int offset = resolvedPage * resolvedSize;

        StringBuilder sql = new StringBuilder("SELECT id, username, site, sender_id, sender_name, environment, status, total_files, files_staged, files_enqueued, files_done, files_failed, created_at, updated_at, completed_at ")
                .append("FROM staging_session WHERE LOWER(username) = ?");
        List<Object> params = new ArrayList<>();
        params.add(normalizeUser(username));
        appendSessionFilters(sql, params, q, senderId, sessionId, site, status, null);
        sql.append(" ORDER BY created_at DESC OFFSET ? ROWS FETCH NEXT ? ROWS ONLY");
        params.add(offset);
        params.add(resolvedSize);

        List<StagingSessionSummary> items = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql.toString())) {
            bindSessionParams(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    items.add(toSummary(rs));
                }
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed loading user staging sessions", ex);
        }
        return items;
    }

    public List<StagingSessionSummary> getAllSessions(int page, int size,
                                                      String q,
                                                      Integer senderId,
                                                      String username,
                                                      String sessionId,
                                                      String site,
                                                      String status) {
        int resolvedPage = Math.max(page, 0);
        int resolvedSize = size <= 0 ? 20 : Math.min(size, 200);
        int offset = resolvedPage * resolvedSize;

        StringBuilder sql = new StringBuilder("SELECT id, username, site, sender_id, sender_name, environment, status, total_files, files_staged, files_enqueued, files_done, files_failed, created_at, updated_at, completed_at ")
                .append("FROM staging_session WHERE 1=1");
        List<Object> params = new ArrayList<>();
        appendSessionFilters(sql, params, q, senderId, sessionId, site, status, username);
        sql.append(" ORDER BY created_at DESC OFFSET ? ROWS FETCH NEXT ? ROWS ONLY");
        params.add(offset);
        params.add(resolvedSize);

        List<StagingSessionSummary> items = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql.toString())) {
            bindSessionParams(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    items.add(toSummary(rs));
                }
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed loading staging sessions", ex);
        }
        return items;
    }

    public long countUserSessions(String username,
                                  String q,
                                  Integer senderId,
                                  String sessionId,
                                  String site,
                                  String status) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(1) FROM staging_session WHERE LOWER(username) = ?");
        List<Object> params = new ArrayList<>();
        params.add(normalizeUser(username));
        appendSessionFilters(sql, params, q, senderId, sessionId, site, status, null);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql.toString())) {
            bindSessionParams(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed counting user sessions", ex);
        }
        return 0;
    }

    public long countAllSessions(String q,
                                 Integer senderId,
                                 String username,
                                 String sessionId,
                                 String site,
                                 String status) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(1) FROM staging_session WHERE 1=1");
        List<Object> params = new ArrayList<>();
        appendSessionFilters(sql, params, q, senderId, sessionId, site, status, username);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql.toString())) {
            bindSessionParams(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed counting staging sessions", ex);
        }
        return 0;
    }

    private void appendSessionFilters(StringBuilder sql,
                                      List<Object> params,
                                      String q,
                                      Integer senderId,
                                      String sessionId,
                                      String site,
                                      String status,
                                      String username) {
        if (senderId != null && senderId > 0) {
            sql.append(" AND sender_id = ?");
            params.add(senderId);
        }
        if (StringUtils.hasText(sessionId)) {
            sql.append(" AND LOWER(id) LIKE ?");
            params.add("%" + sessionId.trim().toLowerCase(Locale.ROOT) + "%");
        }
        if (StringUtils.hasText(site)) {
            sql.append(" AND LOWER(site) LIKE ?");
            params.add("%" + site.trim().toLowerCase(Locale.ROOT) + "%");
        }
        if (StringUtils.hasText(status)) {
            sql.append(" AND LOWER(status) = ?");
            params.add(status.trim().toLowerCase(Locale.ROOT));
        }
        if (StringUtils.hasText(username)) {
            sql.append(" AND LOWER(username) LIKE ?");
            params.add("%" + username.trim().toLowerCase(Locale.ROOT) + "%");
        }
        if (StringUtils.hasText(q)) {
            String needle = "%" + q.trim().toLowerCase(Locale.ROOT) + "%";
            sql.append(" AND (")
                    .append("LOWER(id) LIKE ? OR LOWER(username) LIKE ? OR LOWER(site) LIKE ? OR LOWER(COALESCE(sender_name, '')) LIKE ? OR LOWER(status) LIKE ?")
                    .append(")");
            params.add(needle);
            params.add(needle);
            params.add(needle);
            params.add(needle);
            params.add(needle);
        }
    }

    private void bindSessionParams(PreparedStatement ps, List<Object> params) throws SQLException {
        int i = 1;
        for (Object value : params) {
            if (value instanceof Integer intVal) {
                ps.setInt(i++, intVal);
            } else {
                ps.setString(i++, value == null ? null : value.toString());
            }
        }
    }

    public StagingSessionDetail getSession(String sessionId, String username, boolean isAdmin) {
        StagingSessionDetail detail = isAdmin ? getSessionRaw(sessionId) : getOwnedSession(sessionId, username);
        if (detail == null) {
            return null;
        }
        refreshCounters(sessionId, detail.status());
        return isAdmin ? getSessionRaw(sessionId) : getOwnedSession(sessionId, username);
    }

    public StageRecordPage getSessionFiles(String sessionId,
                                           String username,
                                           String statusFilter,
                                           String search,
                                           int page,
                                           int size,
                                           com.onsemi.cim.apps.exensio.exensioreload.controller.StageRecordMapper mapper) {
        StagingSessionDetail session = getOwnedSession(sessionId, username);
        if (session == null) {
            return new StageRecordPage(List.of(), 0, 0, 0);
        }
        int resolvedPage = Math.max(page, 0);
        int resolvedSize = size <= 0 ? 100 : Math.min(size, 1000);
        int offset = resolvedPage * resolvedSize;

        List<StageRecord> rows = (search != null && !search.isBlank())
                ? refDbService.listRecords(session.site(), session.senderId(), statusFilter, search, offset, resolvedSize, "updated_at", "desc", sessionId)
                : refDbService.listRecords(session.site(), session.senderId(), statusFilter, offset, resolvedSize, "updated_at", "desc", sessionId);

        long total = refDbService.countRecords(session.site(), session.senderId(), statusFilter, search, sessionId);
        List<StageRecordView> views = rows.stream().map(mapper::toView).toList();
        tempDebugSessionFileFieldCoverage(sessionId, session.site(), session.senderId(), statusFilter, search, resolvedPage, resolvedSize, rows, views);
        return new StageRecordPage(views, total, resolvedPage, resolvedSize);
    }

    public StageRecordPage getSessionFiles(String sessionId,
                                           String username,
                                           boolean isAdmin,
                                           String statusFilter,
                                           String search,
                                           int page,
                                           int size,
                                           com.onsemi.cim.apps.exensio.exensioreload.controller.StageRecordMapper mapper) {
        StagingSessionDetail session = isAdmin ? getSessionRaw(sessionId) : getOwnedSession(sessionId, username);
        if (session == null) {
            return new StageRecordPage(List.of(), 0, 0, 0);
        }
        int resolvedPage = Math.max(page, 0);
        int resolvedSize = size <= 0 ? 100 : Math.min(size, 1000);
        int offset = resolvedPage * resolvedSize;

        List<StageRecord> rows = (search != null && !search.isBlank())
                ? refDbService.listRecords(session.site(), session.senderId(), statusFilter, search, offset, resolvedSize, "updated_at", "desc", sessionId)
                : refDbService.listRecords(session.site(), session.senderId(), statusFilter, offset, resolvedSize, "updated_at", "desc", sessionId);

        long total = refDbService.countRecords(session.site(), session.senderId(), statusFilter, search, sessionId);
        List<StageRecordView> views = rows.stream().map(mapper::toView).toList();
        tempDebugSessionFileFieldCoverage(sessionId, session.site(), session.senderId(), statusFilter, search, resolvedPage, resolvedSize, rows, views);
        return new StageRecordPage(views, total, resolvedPage, resolvedSize);
    }

    /**
     * TEMP DEBUG: Helps verify where lot/wafer/filename values are lost for monitoring APIs.
     * Remove after Stage-All monitoring field investigation is complete.
     */
    private void tempDebugSessionFileFieldCoverage(String sessionId,
                                                   String site,
                                                   int senderId,
                                                   String statusFilter,
                                                   String search,
                                                   int page,
                                                   int size,
                                                   List<StageRecord> rows,
                                                   List<StageRecordView> views) {
        if (rows == null || rows.isEmpty()) {
            if (log.isDebugEnabled()) {
                log.debug("[TEMP_DEBUG_STAGE_ALL] sessionFiles empty sessionId={} site={} senderId={} status={} q={} page={} size={}",
                        sessionId, site, senderId, statusFilter, search, page, size);
            }
            return;
        }

        int rowMissingLot = 0;
        int rowMissingWafer = 0;
        int rowMissingFilename = 0;

        for (StageRecord row : rows) {
            if (isBlank(row.lot())) rowMissingLot++;
            if (isBlank(row.wafer())) rowMissingWafer++;
            if (isBlank(row.filename())) rowMissingFilename++;
        }

        int viewMissingLot = 0;
        int viewMissingWafer = 0;
        int viewMissingFilename = 0;

        for (StageRecordView view : views) {
            if (isBlank(view.lot()) || "-".equals(view.lot())) viewMissingLot++;
            if (isBlank(view.wafer()) || "-".equals(view.wafer())) viewMissingWafer++;
            if (isBlank(view.filename()) || "unknown".equalsIgnoreCase(view.filename())) viewMissingFilename++;
        }

        StageRecord sampleRow = rows.get(0);
        StageRecordView sampleView = views.isEmpty() ? null : views.get(0);

        log.info("[TEMP_DEBUG_STAGE_ALL] sessionId={} site={} senderId={} status={} q={} page={} size={} rows={} missing(row lot/wafer/filename)={}/{}/{} missing(view lot/wafer/filename)={}/{}/{} sampleRow[id={},metadataId={},dataId={},lot={},wafer={},filename={}] sampleView[id={},metadataId={},dataId={},lot={},wafer={},filename={}]",
                sessionId,
                site,
                senderId,
                statusFilter,
                search,
                page,
                size,
                rows.size(),
                rowMissingLot,
                rowMissingWafer,
                rowMissingFilename,
                viewMissingLot,
                viewMissingWafer,
                viewMissingFilename,
                sampleRow.id(),
                sampleRow.metadataId(),
                sampleRow.dataId(),
                sampleRow.lot(),
                sampleRow.wafer(),
                sampleRow.filename(),
                sampleView != null ? sampleView.id() : null,
                sampleView != null ? sampleView.metadataId() : null,
                sampleView != null ? sampleView.dataId() : null,
                sampleView != null ? sampleView.lot() : null,
                sampleView != null ? sampleView.wafer() : null,
                sampleView != null ? sampleView.filename() : null
        );
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public List<LotWaferProgress> getSessionLotWaferProgress(String sessionId, String username) {
        StagingSessionDetail session = getOwnedSession(sessionId, username);
        if (session == null) {
            return List.of();
        }

        String table = refDbService.getStagingTable();
        String sql = "SELECT COALESCE(lot, '-'), COALESCE(wafer, '-'), COUNT(*), " +
                "SUM(CASE WHEN status = 'DONE' THEN 1 ELSE 0 END), " +
                "SUM(CASE WHEN status = 'FAILED' THEN 1 ELSE 0 END) " +
                "FROM " + table + " WHERE request_id = ? GROUP BY lot, wafer ORDER BY lot, wafer";

        List<LotWaferProgress> items = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long total = rs.getLong(3);
                    long done = rs.getLong(4);
                    long failed = rs.getLong(5);
                    String status = done + failed >= total ? (failed > 0 ? "PARTIALLY_FAILED" : "COMPLETED") : (done > 0 ? "IN_PROGRESS" : "STAGED");
                    items.add(new LotWaferProgress(rs.getString(1), rs.getString(2), total, done, failed, status));
                }
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed loading lot/wafer progress", ex);
        }
        return items;
    }

    public List<LotWaferProgress> getSessionLotWaferProgress(String sessionId, String username, boolean isAdmin) {
        StagingSessionDetail session = isAdmin ? getSessionRaw(sessionId) : getOwnedSession(sessionId, username);
        if (session == null) {
            return List.of();
        }

        String table = refDbService.getStagingTable();
        String sql = "SELECT COALESCE(lot, '-'), COALESCE(wafer, '-'), COUNT(*), " +
                "SUM(CASE WHEN status = 'DONE' THEN 1 ELSE 0 END), " +
                "SUM(CASE WHEN status = 'FAILED' THEN 1 ELSE 0 END) " +
                "FROM " + table + " WHERE request_id = ? GROUP BY lot, wafer ORDER BY lot, wafer";

        List<LotWaferProgress> items = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long total = rs.getLong(3);
                    long done = rs.getLong(4);
                    long failed = rs.getLong(5);
                    String status = done + failed >= total ? (failed > 0 ? "PARTIALLY_FAILED" : "COMPLETED") : (done > 0 ? "IN_PROGRESS" : "STAGED");
                    items.add(new LotWaferProgress(rs.getString(1), rs.getString(2), total, done, failed, status));
                }
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed loading lot/wafer progress", ex);
        }
        return items;
    }

    public StagingSessionDetail refreshExternalStatus(String sessionId, String username) {
        StagingSessionDetail session = getOwnedSession(sessionId, username);
        if (session == null) {
            return null;
        }

        int offset = 0;
        int pageSize = 500;
        List<StageRecord> completedNow = new ArrayList<>();
        while (true) {
            List<StageRecord> enrichmentRecords = refDbService.listRecords(session.site(), session.senderId(), "ENRICHMENT", offset, pageSize, null, null, sessionId);
            if (enrichmentRecords.isEmpty()) {
                break;
            }
            Set<String> queueKeys = fetchQueueKeys(session.site(), session.senderId());
            for (StageRecord row : enrichmentRecords) {
                String key = buildKey(row.metadataId(), row.dataId());
                if (!queueKeys.contains(key)) {
                    completedNow.add(row);
                }
            }
            if (enrichmentRecords.size() < pageSize) {
                break;
            }
            offset += pageSize;
        }

        if (!completedNow.isEmpty()) {
            pipelineOrchestrator.onCpQueueConsumed(completedNow, session.site(), session.senderId());
        }
        refreshCounters(sessionId, session.status());
        return getOwnedSession(sessionId, username);
    }

    public StagingSessionDetail refreshExternalStatus(String sessionId, String username, boolean isAdmin) {
        StagingSessionDetail session = isAdmin ? getSessionRaw(sessionId) : getOwnedSession(sessionId, username);
        if (session == null) {
            return null;
        }

        int offset = 0;
        int pageSize = 500;
        List<StageRecord> completedNow = new ArrayList<>();
        while (true) {
            List<StageRecord> enrichmentRecords = refDbService.listRecords(session.site(), session.senderId(), "ENRICHMENT", offset, pageSize, null, null, sessionId);
            if (enrichmentRecords.isEmpty()) {
                break;
            }
            Set<String> queueKeys = fetchQueueKeys(session.site(), session.senderId());
            for (StageRecord row : enrichmentRecords) {
                String key = buildKey(row.metadataId(), row.dataId());
                if (!queueKeys.contains(key)) {
                    completedNow.add(row);
                }
            }
            if (enrichmentRecords.size() < pageSize) {
                break;
            }
            offset += pageSize;
        }

        if (!completedNow.isEmpty()) {
            pipelineOrchestrator.onCpQueueConsumed(completedNow, session.site(), session.senderId());
        }
        refreshCounters(sessionId, session.status());
        return isAdmin ? getSessionRaw(sessionId) : getOwnedSession(sessionId, username);
    }

    public void cancelSession(String sessionId, String username) {
        StagingSessionDetail session = getOwnedSession(sessionId, username);
        if (session == null) {
            return;
        }
        String table = refDbService.getStagingTable();
        String stageSql = "UPDATE " + table + " SET status = 'FAILED', error_message = 'Cancelled by user', updated_at = CURRENT_TIMESTAMP WHERE request_id = ? AND status = 'NEW'";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(stageSql)) {
            ps.setString(1, sessionId);
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed cancelling staging session rows", ex);
        }

        String updateSql = "UPDATE staging_session SET status = 'CANCELLED', updated_at = ?, completed_at = ? WHERE id = ?";
        Instant now = Instant.now();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(updateSql)) {
            ps.setTimestamp(1, Timestamp.from(now));
            ps.setTimestamp(2, Timestamp.from(now));
            ps.setString(3, sessionId);
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed cancelling session", ex);
        }
    }

    public void cancelSession(String sessionId, String username, boolean isAdmin) {
        StagingSessionDetail session = isAdmin ? getSessionRaw(sessionId) : getOwnedSession(sessionId, username);
        if (session == null) {
            return;
        }
        String table = refDbService.getStagingTable();
        String stageSql = "UPDATE " + table + " SET status = 'FAILED', error_message = 'Cancelled by user', updated_at = CURRENT_TIMESTAMP WHERE request_id = ? AND status = 'NEW'";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(stageSql)) {
            ps.setString(1, sessionId);
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed cancelling staging session rows", ex);
        }

        String updateSql = "UPDATE staging_session SET status = 'CANCELLED', updated_at = ?, completed_at = ? WHERE id = ?";
        Instant now = Instant.now();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(updateSql)) {
            ps.setTimestamp(1, Timestamp.from(now));
            ps.setTimestamp(2, Timestamp.from(now));
            ps.setString(3, sessionId);
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed cancelling session", ex);
        }
    }

    public SessionAnalyticsResponse getSessionAnalytics(String sessionId,
                                                        String username,
                                                        boolean isAdmin,
                                                        int topPairsLimit,
                                                        String startDate,
                                                        String endDate) {
        StagingSessionDetail session = isAdmin ? getSessionRaw(sessionId) : getOwnedSession(sessionId, username);
        if (session == null) {
            return null;
        }

        int resolvedTopPairs = topPairsLimit <= 0 ? 10 : Math.min(topPairsLimit, 50);
        String table = refDbService.getStagingTable();
        Instant start = parseDateStart(startDate);
        Instant end = parseDateEnd(endDate);

        List<SessionDailyStatusPoint> dailyStatus = loadDailyStatus(table, sessionId, start, end);
        List<SessionLotWaferPairTotal> topPairs = loadTopLotWaferPairs(table, sessionId, resolvedTopPairs, start, end);
        List<SessionLotWaferDailyPoint> heatmap = loadLotWaferDailyHeatmap(table, sessionId, topPairs, start, end);

        return new SessionAnalyticsResponse(sessionId, dailyStatus, topPairs, heatmap);
    }

    public void refreshSessions(Collection<String> sessionIds) {
        if (sessionIds == null || sessionIds.isEmpty()) {
            return;
        }
        Set<String> unique = new HashSet<>();
        for (String sessionId : sessionIds) {
            if (sessionId != null && !sessionId.isBlank()) {
                unique.add(sessionId.trim());
            }
        }
        unique.forEach(this::refreshCounters);
    }

    /**
     * Calculate throughput and ETA metrics for a session
     *
     * @param sessionId Session ID
     * @return SessionMetrics with throughput, ETA, and success rate
     */
    public SessionMetrics calculateMetrics(String sessionId) {
        String table = refDbService.getStagingTable();

        // Query completion timestamps from last 5 minutes
        Instant fiveMinutesAgo = Instant.now().minus(5, ChronoUnit.MINUTES);
        String sql = "SELECT COUNT(*) FROM " + table +
                " WHERE request_id = ? AND status IN ('DONE', 'FAILED') AND updated_at >= ?";

        long completedInLast5Min = 0;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            ps.setTimestamp(2, Timestamp.from(fiveMinutesAgo));
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    completedInLast5Min = rs.getLong(1);
                }
            }
        } catch (SQLException ex) {
            log.warn("Failed to calculate throughput for session {}: {}", sessionId, ex.getMessage());
        }

        // Calculate throughput (files per minute)
        double throughput = completedInLast5Min / 5.0;

        // Get current session state
        StatusCounts counts = loadCounts(sessionId);
        long remaining = counts.staged() + counts.enqueued();

        // Calculate ETA (minutes remaining)
        int eta = 0;
        if (throughput > 0 && remaining > 0) {
            eta = (int) Math.ceil(remaining / throughput);
        }

        // Calculate success rate
        double successRate = 0.0;
        long terminal = counts.done() + counts.failed();
        if (terminal > 0) {
            successRate = (counts.done() * 100.0) / terminal;
        }

        return new SessionMetrics(throughput, eta, successRate);
    }

    private Set<String> fetchQueueKeys(String site, int senderId) {
        Set<String> keys = new HashSet<>();
        Connection connection = null;
        try {
            connection = externalDbConfig.getConnection(site);
            if (connection == null) {
                return keys;
            }
            String sql = "SELECT id_metadata, id_data FROM DTP_SENDER_QUEUE_ITEM WHERE id_sender = ?";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setInt(1, senderId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        keys.add(buildKey(rs.getString(1), rs.getString(2)));
                    }
                }
            }
        } catch (SQLException ex) {
            log.warn("Failed to read external queue for {} sender {}: {}", site, senderId, ex.getMessage());
        } finally {
            if (connection != null) {
                try {
                    connection.close();
                } catch (SQLException ignored) {
                }
            }
        }
        return keys;
    }

    private String buildKey(String metadataId, String dataId) {
        String left = metadataId == null ? "" : metadataId.trim();
        String right = dataId == null ? "" : dataId.trim();
        return left + "|" + right;
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isBlank() ? null : trimmed;
    }

    private String normalizeUser(String username) {
        if (username == null || username.isBlank()) {
            return "system";
        }
        return username.trim().toLowerCase(Locale.ROOT);
    }

    private StatusCounts loadCounts(String sessionId) {
        String table = refDbService.getStagingTable();
        String sql = "SELECT COUNT(*), " +
                "SUM(CASE WHEN status = 'NEW' THEN 1 ELSE 0 END), " +
                "SUM(CASE WHEN status IN ('ENQUEUED','ENRICHMENT','EXENSIO_LOADING') THEN 1 ELSE 0 END), " +
                "SUM(CASE WHEN status = 'DONE' THEN 1 ELSE 0 END), " +
                "SUM(CASE WHEN status = 'FAILED' THEN 1 ELSE 0 END) " +
                "FROM " + table + " WHERE request_id = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new StatusCounts(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5));
                }
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to load staging session counts", ex);
        }
        return new StatusCounts(0, 0, 0, 0, 0);
    }

    private List<SessionDailyStatusPoint> loadDailyStatus(String table, String sessionId, Instant start, Instant end) {
        String timestampExpr = "COALESCE(end_time, processed_at, updated_at, created_at)";
        StringBuilder sql = new StringBuilder("SELECT CAST(")
                .append(timestampExpr)
                .append(" AS DATE) AS day_bucket, ")
                .append("SUM(CASE WHEN UPPER(status) = 'DONE' THEN 1 ELSE 0 END) AS done_count, ")
                .append("SUM(CASE WHEN UPPER(status) IN ('ENQUEUED','ENRICHMENT','EXENSIO_LOADING') THEN 1 ELSE 0 END) AS enqueued_count, ")
                .append("SUM(CASE WHEN UPPER(status) = 'FAILED' AND UPPER(COALESCE(error_message, '')) NOT LIKE 'CANCELLED BY USER%' THEN 1 ELSE 0 END) AS failed_count, ")
                .append("SUM(CASE WHEN UPPER(status) = 'FAILED' AND UPPER(COALESCE(error_message, '')) LIKE 'CANCELLED BY USER%' THEN 1 ELSE 0 END) AS cancelled_count, ")
                .append("SUM(CASE WHEN UPPER(status) = 'NEW' THEN 1 ELSE 0 END) AS new_count, ")
                .append("COUNT(*) AS total_count ")
                .append("FROM ").append(table).append(" WHERE request_id = ? ");
        List<Object> params = new ArrayList<>();
        params.add(sessionId);
        if (start != null) {
            sql.append(" AND ").append(timestampExpr).append(" >= ?");
            params.add(Timestamp.from(start));
        }
        if (end != null) {
            sql.append(" AND ").append(timestampExpr).append(" < ?");
            params.add(Timestamp.from(end));
        }
        sql.append(" GROUP BY CAST(").append(timestampExpr).append(" AS DATE) ORDER BY day_bucket");

        List<SessionDailyStatusPoint> points = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql.toString())) {
            bindParams(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String day = formatDayBucket(rs.getObject("day_bucket"));
                    points.add(new SessionDailyStatusPoint(
                            day,
                            rs.getLong("total_count"),
                            rs.getLong("done_count"),
                            rs.getLong("enqueued_count"),
                            rs.getLong("failed_count"),
                            rs.getLong("cancelled_count"),
                            rs.getLong("new_count")
                    ));
                }
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed loading session daily status analytics", ex);
        }
        return points;
    }

    private List<SessionLotWaferPairTotal> loadTopLotWaferPairs(String table,
                                                                String sessionId,
                                                                int topPairsLimit,
                                                                Instant start,
                                                                Instant end) {
        String timestampExpr = "COALESCE(end_time, processed_at, updated_at, created_at)";
        StringBuilder sql = new StringBuilder("SELECT COALESCE(lot, '-') AS lot_key, COALESCE(wafer, '-') AS wafer_key, COUNT(*) AS pair_total ")
                .append("FROM ").append(table).append(" WHERE request_id = ?");
        List<Object> params = new ArrayList<>();
        params.add(sessionId);
        if (start != null) {
            sql.append(" AND ").append(timestampExpr).append(" >= ?");
            params.add(Timestamp.from(start));
        }
        if (end != null) {
            sql.append(" AND ").append(timestampExpr).append(" < ?");
            params.add(Timestamp.from(end));
        }
        sql.append(" GROUP BY COALESCE(lot, '-'), COALESCE(wafer, '-') ORDER BY pair_total DESC, lot_key, wafer_key");

        List<SessionLotWaferPairTotal> pairs = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql.toString())) {
            bindParams(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next() && pairs.size() < topPairsLimit) {
                    pairs.add(new SessionLotWaferPairTotal(
                            rs.getString("lot_key"),
                            rs.getString("wafer_key"),
                            rs.getLong("pair_total")
                    ));
                }
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed loading session lot/wafer pairs", ex);
        }
        return pairs;
    }

    private List<SessionLotWaferDailyPoint> loadLotWaferDailyHeatmap(String table,
                                                                     String sessionId,
                                                                     List<SessionLotWaferPairTotal> topPairs,
                                                                     Instant start,
                                                                     Instant end) {
        if (topPairs == null || topPairs.isEmpty()) {
            return List.of();
        }

        Map<String, SessionLotWaferPairTotal> selected = new HashMap<>();
        for (SessionLotWaferPairTotal pair : topPairs) {
            selected.put(buildKey(pair.lot(), pair.wafer()), pair);
        }

        String timestampExpr = "COALESCE(end_time, processed_at, updated_at, created_at)";
        StringBuilder sql = new StringBuilder("SELECT CAST(")
                .append(timestampExpr)
                .append(" AS DATE) AS day_bucket, COALESCE(lot, '-') AS lot_key, COALESCE(wafer, '-') AS wafer_key, COUNT(*) AS point_total ")
                .append("FROM ").append(table).append(" WHERE request_id = ?");
        List<Object> params = new ArrayList<>();
        params.add(sessionId);
        if (start != null) {
            sql.append(" AND ").append(timestampExpr).append(" >= ?");
            params.add(Timestamp.from(start));
        }
        if (end != null) {
            sql.append(" AND ").append(timestampExpr).append(" < ?");
            params.add(Timestamp.from(end));
        }
        sql.append(" GROUP BY CAST(").append(timestampExpr).append(" AS DATE), COALESCE(lot, '-'), COALESCE(wafer, '-')")
                .append(" ORDER BY day_bucket, lot_key, wafer_key");

        Map<String, SessionLotWaferDailyPoint> compact = new LinkedHashMap<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql.toString())) {
            bindParams(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String lot = rs.getString("lot_key");
                    String wafer = rs.getString("wafer_key");
                    if (!selected.containsKey(buildKey(lot, wafer))) {
                        continue;
                    }
                    String day = formatDayBucket(rs.getObject("day_bucket"));
                    String pointKey = day + "|" + lot + "|" + wafer;
                    compact.put(pointKey, new SessionLotWaferDailyPoint(day, lot, wafer, rs.getLong("point_total")));
                }
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed loading session lot/wafer daily heatmap", ex);
        }
        return new ArrayList<>(compact.values());
    }

    private void bindParams(PreparedStatement ps, List<Object> params) throws SQLException {
        int idx = 1;
        for (Object param : params) {
            if (param instanceof Timestamp ts) {
                ps.setTimestamp(idx++, ts);
            } else {
                ps.setString(idx++, param == null ? null : param.toString());
            }
        }
    }

    private Instant parseDateStart(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        try {
            return Instant.parse(trimmed);
        } catch (Exception ignored) {
        }
        try {
            return LocalDate.parse(trimmed).atStartOfDay(ZoneOffset.UTC).toInstant();
        } catch (Exception ignored) {
        }
        return null;
    }

    private Instant parseDateEnd(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        try {
            return Instant.parse(trimmed);
        } catch (Exception ignored) {
        }
        try {
            return LocalDate.parse(trimmed).plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        } catch (Exception ignored) {
        }
        return null;
    }

    private String resolveStatus(String currentStatus, StatusCounts counts) {
        if ("CANCELLED".equalsIgnoreCase(currentStatus)) {
            return "CANCELLED";
        }
        long terminal = counts.done() + counts.failed();
        if (counts.total() > 0 && terminal >= counts.total()) {
            return counts.failed() > 0 ? "PARTIALLY_FAILED" : "COMPLETED";
        }
        if (counts.enqueued() > 0) {
            return counts.done() > 0 ? "MONITORING" : "DISPATCHING";
        }
        if (counts.staged() > 0) {
            return "STAGING";
        }
        return currentStatus == null || currentStatus.isBlank() ? "STAGING" : currentStatus;
    }

    private boolean isTerminal(String status) {
        return "COMPLETED".equalsIgnoreCase(status)
                || "PARTIALLY_FAILED".equalsIgnoreCase(status)
                || "CANCELLED".equalsIgnoreCase(status);
    }

    private StagingSessionDetail getOwnedSession(String sessionId, String username) {
        StagingSessionDetail session = getSessionRaw(sessionId);
        if (session == null) {
            return null;
        }
        if (normalizeUser(username).equals(session.username().toLowerCase(Locale.ROOT))) {
            return session;
        }
        return null;
    }

    private StagingSessionDetail getSessionRaw(String sessionId) {
        String sql = "SELECT id, username, site, sender_id, sender_name, environment, status, total_files, files_staged, files_enqueued, files_done, files_failed, created_at, updated_at, completed_at, last_checked_at FROM staging_session WHERE id = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return toDetail(rs);
                }
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed loading staging session", ex);
        }
        return null;
    }

    private StagingSessionSummary toSummary(ResultSet rs) throws SQLException {
        long total = rs.getLong("total_files");
        long done = rs.getLong("files_done");
        long failed = rs.getLong("files_failed");
        double progress = total > 0 ? ((done + failed) * 100.0) / total : 0.0;
        return new StagingSessionSummary(
                rs.getString("id"),
                rs.getString("username"),
                rs.getString("site"),
                rs.getInt("sender_id"),
                rs.getString("sender_name"),
                rs.getString("environment"),
                rs.getString("status"),
                total,
                rs.getLong("files_staged"),
                rs.getLong("files_enqueued"),
                done,
                failed,
                toIso(rs.getTimestamp("created_at")),
                toIso(rs.getTimestamp("updated_at")),
                toIso(rs.getTimestamp("completed_at")),
                progress
        );
    }

    private StagingSessionDetail toDetail(ResultSet rs) throws SQLException {
        long total = rs.getLong("total_files");
        long done = rs.getLong("files_done");
        long failed = rs.getLong("files_failed");
        double progress = total > 0 ? ((done + failed) * 100.0) / total : 0.0;

        String sessionId = rs.getString("id");
        SessionMetrics metrics = calculateMetrics(sessionId);

        return createStagingSessionDetail(
                sessionId,
                rs.getString("username"),
                rs.getString("site"),
                rs.getInt("sender_id"),
                rs.getString("sender_name"),
                rs.getString("environment"),
                rs.getString("status"),
                total,
                rs.getLong("files_staged"),
                rs.getLong("files_enqueued"),
                done,
                failed,
                toIso(rs.getTimestamp("created_at")),
                toIso(rs.getTimestamp("updated_at")),
                toIso(rs.getTimestamp("completed_at")),
                toIso(rs.getTimestamp("last_checked_at")),
                progress,
                metrics
        );
    }

    private StagingSessionDetail createStagingSessionDetail(String sessionId,
                                                            String username,
                                                            String site,
                                                            int senderId,
                                                            String senderName,
                                                            String environment,
                                                            String status,
                                                            long total,
                                                            long filesStaged,
                                                            long filesEnqueued,
                                                            long done,
                                                            long failed,
                                                            String createdAt,
                                                            String updatedAt,
                                                            String completedAt,
                                                            String lastCheckedAt,
                                                            double progress,
                                                            SessionMetrics metrics) {
        try {
            var ctor20 = StagingSessionDetail.class.getConstructor(
                    String.class, String.class, String.class, int.class, String.class, String.class, String.class,
                    long.class, long.class, long.class, long.class, long.class,
                    String.class, String.class, String.class, String.class,
                    double.class, double.class, int.class, double.class);
            return ctor20.newInstance(
                    sessionId, username, site, senderId, senderName, environment, status,
                    total, filesStaged, filesEnqueued, done, failed,
                    createdAt, updatedAt, completedAt, lastCheckedAt,
                    progress, metrics.throughput(), metrics.eta(), metrics.successRate());
        } catch (NoSuchMethodException ignored) {
            // Backward compatibility for older DTO shape without throughput/eta/successRate
            try {
                var ctor17 = StagingSessionDetail.class.getConstructor(
                        String.class, String.class, String.class, int.class, String.class, String.class, String.class,
                        long.class, long.class, long.class, long.class, long.class,
                        String.class, String.class, String.class, String.class,
                        double.class);
                return ctor17.newInstance(
                        sessionId, username, site, senderId, senderName, environment, status,
                        total, filesStaged, filesEnqueued, done, failed,
                        createdAt, updatedAt, completedAt, lastCheckedAt,
                        progress);
            } catch (ReflectiveOperationException ex) {
                throw new IllegalStateException("Failed to construct StagingSessionDetail (17-arg)", ex);
            }
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Failed to construct StagingSessionDetail (20-arg)", ex);
        }
    }

    private String toIso(Timestamp ts) {
        if (ts == null) {
            return null;
        }
        return ts.toLocalDateTime().toInstant(ZoneOffset.UTC).toString();
    }

    /** Normalize JDBC day bucket values to YYYY-MM-DD (UTC wall-clock date). */
    private String formatDayBucket(Object value) {
        if (value == null) {
            return "unknown";
        }
        if (value instanceof Date date) {
            return date.toLocalDate().toString();
        }
        if (value instanceof Timestamp ts) {
            return ts.toLocalDateTime().toLocalDate().toString();
        }
        if (value instanceof java.time.LocalDate localDate) {
            return localDate.toString();
        }
        String raw = String.valueOf(value).trim();
        if (raw.length() >= 10 && raw.charAt(4) == '-' && raw.charAt(7) == '-') {
            return raw.substring(0, 10);
        }
        return raw;
    }

    private void ensureStagingSessionTable() {
        try (Connection connection = dataSource.getConnection()) {
            if (tableExists(connection, "STAGING_SESSION")) {
                return;
            }
            String create = "CREATE TABLE staging_session (" +
                    "id VARCHAR(36) PRIMARY KEY, " +
                    "username VARCHAR(120) NOT NULL, " +
                    "site VARCHAR(60) NOT NULL, " +
                    "sender_id INTEGER NOT NULL, " +
                    "sender_name VARCHAR(200), " +
                    "environment VARCHAR(20), " +
                    "total_files INTEGER DEFAULT 0 NOT NULL, " +
                    "files_staged INTEGER DEFAULT 0 NOT NULL, " +
                    "files_enqueued INTEGER DEFAULT 0 NOT NULL, " +
                    "files_done INTEGER DEFAULT 0 NOT NULL, " +
                    "files_failed INTEGER DEFAULT 0 NOT NULL, " +
                    "status VARCHAR(30) DEFAULT 'STAGING' NOT NULL, " +
                    "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL, " +
                    "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL, " +
                    "completed_at TIMESTAMP NULL, " +
                    "last_checked_at TIMESTAMP NULL)";
            try (Statement statement = connection.createStatement()) {
                statement.execute(create);
                statement.execute("CREATE INDEX idx_staging_session_user ON staging_session(username)");
                statement.execute("CREATE INDEX idx_staging_session_status ON staging_session(status)");
            }
            log.info("Created staging_session table via runtime bootstrap");
        } catch (Exception ex) {
            log.warn("Unable to ensure staging_session table at runtime: {}", ex.getMessage());
        }
    }

    /**
     * Builds a sender config hint for ETL SSH port extraction.
     * YAML keys use {@code SITE-PROD}; crontab lines often embed the SSH/sender port (e.g. 60170).
     */
    private String resolveEtlSenderConfigName(String site, int senderId) {
        if (site != null && !site.isBlank()) {
            String trimmed = site.trim().toUpperCase(java.util.Locale.ROOT);
            if (!trimmed.endsWith("-PROD") && !trimmed.endsWith("-QA")) {
                return trimmed + "-PROD";
            }
            return trimmed;
        }
        return "sender-" + senderId;
    }

    private boolean tableExists(Connection connection, String tableName) {
        try (ResultSet rs = connection.getMetaData().getTables(null, null, tableName, null)) {
            return rs.next();
        } catch (SQLException ex) {
            return false;
        }
    }

    private record StatusCounts(long total, long staged, long enqueued, long done, long failed) {
    }

    private record SessionMetrics(double throughput, int eta, double successRate) {
    }
}
