package com.onsemi.cim.apps.exensio.exensioreload.service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * Service to discover wafer IDs for given lots from the Exensio database.
 * 
 * Used as first step in wafer-level preflight checks:
 * 1. Discover all wafers for the lot(s)
 * 2. Check those wafers in Exensio via preflight (in parallel across schemas)
 * 3. Consolidate results
 */
@Service
public class WaferDiscoveryService {

    private static final Logger log = LoggerFactory.getLogger(WaferDiscoveryService.class);

    private final DataSource exensioDataSource;

    public WaferDiscoveryService(
            @Qualifier("exensioDataSource") DataSource exensioDataSource) {
        this.exensioDataSource = exensioDataSource;
    }

    /**
     * Discovers all wafer IDs for given lot IDs from the Exensio database.
     * 
     * Query returns distinct wafer IDs associated with each lot for the specified PGC_KEY.
     * 
     * @param lotIds list of lot IDs to discover wafers for
     * @param pgcKey the PGC_KEY (1=Probe, 4=Map, 5=PCM, 14=Defect)
     * @return List of wafer IDs found, empty list if none found or on error
     */
    public List<String> discoverWafersForLots(List<String> lotIds, int pgcKey) {
        if (lotIds == null || lotIds.isEmpty()) {
            return Collections.emptyList();
        }

        String sql = buildDiscoveryQuery(lotIds, pgcKey);
        
        log.debug("[WaferDiscovery] Discovering wafers for {} lots, pgcKey={}", lotIds.size(), pgcKey);

        try (Connection conn = exensioDataSource.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                List<String> wafers = new ArrayList<>();
                
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String wafer = rs.getString("WAFER_ID");
                        if (wafer != null && !wafer.isBlank()) {
                            wafers.add(wafer.trim().toUpperCase());
                        }
                    }
                }
                
                // Remove duplicates while preserving order
                List<String> uniqueWafers = wafers.stream()
                        .distinct()
                        .collect(Collectors.toList());
                
                log.info("[WaferDiscovery] Discovered {} unique wafers for {} lots", uniqueWafers.size(), lotIds.size());
                return uniqueWafers;
            }
        } catch (Exception e) {
            log.warn("[WaferDiscovery] Failed to discover wafers: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Builds Oracle SQL query to discover all wafers for given lots.
     * 
     * @param lotIds list of lot IDs
     * @param pgcKey PGC_KEY for device class filtering
     * @return SQL query string
     */
    private String buildDiscoveryQuery(List<String> lotIds, int pgcKey) {
        StringBuilder sb = new StringBuilder();
        
        sb.append("SELECT DISTINCT UPPER(TRIM(w.wf_id)) AS WAFER_ID\n");
        sb.append("FROM op_log ol\n");
        sb.append("JOIN lot l ON l.lot_key = ol.lot_key\n");
        sb.append("LEFT JOIN wf_log wfl ON wfl.lg_key = ol.lg_key\n");
        sb.append("LEFT JOIN wafer w ON w.wf_key = wfl.wf_key\n");
        sb.append("WHERE ol.pgc_key = ").append(pgcKey).append("\n");
        sb.append("  AND UPPER(TRIM(l.lot_id)) IN (");
        
        for (int i = 0; i < lotIds.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append("'").append(escapeSql(lotIds.get(i))).append("'");
        }
        
        sb.append(")\n");
        sb.append("  AND w.wf_id IS NOT NULL\n");
        sb.append("ORDER BY WAFER_ID");
        
        return sb.toString();
    }

    /**
     * Escapes SQL string literal by doubling single quotes.
     */
    private String escapeSql(String value) {
        if (value == null) return "";
        return value.replace("'", "''");
    }
}
