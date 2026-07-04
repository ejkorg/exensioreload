package com.onsemi.cim.apps.exensio.exensioreload.repository;

import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.onsemi.cim.apps.exensio.exensioreload.entity.LoadSessionPayload;

@Repository
public class LoadSessionPayloadRepositoryImpl implements LoadSessionPayloadRepositoryCustom {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private jakarta.persistence.EntityManager entityManager;

    @Override
    public List<LoadSessionPayload> claimNextBatch(Long sessionId, int batchSize) {
        final int maxAttempts = 6;
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            List<Long> ids = jdbcTemplate.queryForList(
                    "SELECT id FROM load_session_payload WHERE session_id = ? AND status = 'NEW' ORDER BY id FETCH FIRST ? ROWS ONLY",
                    Long.class, sessionId, batchSize);
            if (ids == null || ids.isEmpty()) return new ArrayList<>();

            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < ids.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append('?');
            }

            Object[] params = ids.toArray();
            String updateSql = "UPDATE load_session_payload SET status = 'STAGED', updated_at = CURRENT_TIMESTAMP WHERE id IN (" + sb.toString() + ") AND status = 'NEW'";
            int updated = jdbcTemplate.update(updateSql, params);
            if (updated == ids.size()) {
                // Load entities by id preserving JPA mapping
                List<LoadSessionPayload> claimed = entityManager.createQuery(
                        "SELECT p FROM LoadSessionPayload p WHERE p.id IN :ids", LoadSessionPayload.class)
                        .setParameter("ids", ids)
                        .getResultList();
                return claimed;
            }

            try { Thread.sleep(8 + attempt * 5L); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
        return new ArrayList<>();
    }

    @Override
    public List<String> findDistinctDevices() {
        // Find all distinct non-NULL device values across all payloads
        // Requirements: 2.5, 7.3
        String sql = "SELECT DISTINCT device FROM load_session_payload WHERE device IS NOT NULL ORDER BY device";
        return jdbcTemplate.queryForList(sql, String.class);
    }

    @Override
    public List<String> findDistinctDevicesBySessionId(Long sessionId) {
        // Find distinct non-NULL device values for a specific session
        // Requirements: 2.5, 7.3
        String sql = "SELECT DISTINCT device FROM load_session_payload WHERE session_id = ? AND device IS NOT NULL ORDER BY device";
        return jdbcTemplate.queryForList(sql, String.class, sessionId);
    }

    @Override
    public Page<LoadSessionPayload> findByDeviceIn(List<String> devices, Pageable pageable) {
        // Find payloads filtered by device identifiers with pagination
        // Requirements: 7.2, 8.1
        if (devices == null || devices.isEmpty()) {
            return new PageImpl<>(new ArrayList<>(), pageable, 0);
        }

        // Build the IN clause with placeholders
        StringBuilder placeholders = new StringBuilder();
        Object[] deviceArray = devices.toArray();
        for (int i = 0; i < devices.size(); i++) {
            if (i > 0) placeholders.append(',');
            placeholders.append('?');
        }

        // Count total records matching the device filter
        String countSql = "SELECT COUNT(*) FROM load_session_payload WHERE device IN (" + placeholders.toString() + ")";
        long total = jdbcTemplate.queryForObject(countSql, Long.class, deviceArray);

        // Query with pagination
        String sql = "SELECT p FROM LoadSessionPayload p WHERE p.device IN :devices ORDER BY p.id DESC";
        List<LoadSessionPayload> content = entityManager.createQuery(sql, LoadSessionPayload.class)
                .setParameter("devices", devices)
                .setFirstResult((int) pageable.getOffset())
                .setMaxResults(pageable.getPageSize())
                .getResultList();

        return new PageImpl<>(content, pageable, total);
    }

    @Override
    public Page<LoadSessionPayload> findBySessionIdAndDeviceIn(Long sessionId, List<String> devices, Pageable pageable) {
        // Find payloads for a session filtered by device identifiers with pagination
        // Requirements: 3.2, 7.2
        if (devices == null || devices.isEmpty()) {
            return new PageImpl<>(new ArrayList<>(), pageable, 0);
        }

        // Build the IN clause with placeholders
        StringBuilder placeholders = new StringBuilder();
        Object[] deviceArray = devices.toArray();
        for (int i = 0; i < devices.size(); i++) {
            if (i > 0) placeholders.append(',');
            placeholders.append('?');
        }

        // Count total records matching the device filter for the session
        String countSql = "SELECT COUNT(*) FROM load_session_payload WHERE session_id = ? AND device IN (" + placeholders.toString() + ")";
        Object[] countParams = new Object[devices.size() + 1];
        countParams[0] = sessionId;
        System.arraycopy(deviceArray, 0, countParams, 1, devices.size());
        long total = jdbcTemplate.queryForObject(countSql, Long.class, countParams);

        // Query with pagination
        String sql = "SELECT p FROM LoadSessionPayload p WHERE p.session.id = :sessionId AND p.device IN :devices ORDER BY p.id DESC";
        List<LoadSessionPayload> content = entityManager.createQuery(sql, LoadSessionPayload.class)
                .setParameter("sessionId", sessionId)
                .setParameter("devices", devices)
                .setFirstResult((int) pageable.getOffset())
                .setMaxResults(pageable.getPageSize())
                .getResultList();

        return new PageImpl<>(content, pageable, total);
    }
}
