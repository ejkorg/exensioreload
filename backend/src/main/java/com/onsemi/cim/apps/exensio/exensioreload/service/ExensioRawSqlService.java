package com.onsemi.cim.apps.exensio.exensioreload.service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.onsemi.cim.apps.exensio.exensioreload.config.ExensioProperties;
import com.onsemi.cim.apps.exensio.exensioreload.dto.PreCheckBlock;

/**
 * Unified raw-SQL query service for Exensio, used by both preflight checks and monitoring.
 * 
 * Executes Oracle SQL via Exensio {@code POST /v1/key/raw-sql} endpoint.
 * Returns lot metadata including wafer_key and pg_key for database population.
 */
@Service
public class ExensioRawSqlService {

    private static final Logger log = LoggerFactory.getLogger(ExensioRawSqlService.class);

    private final ExensioProperties exensioProperties;
    private final ExensioAuthService authService;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final int precheckRowLimit;

    public ExensioRawSqlService(
            ExensioProperties exensioProperties,
            ExensioAuthService authService,
            ObjectMapper objectMapper,
            @org.springframework.beans.factory.annotation.Qualifier("exensioHttpClient") HttpClient exensioHttpClient,
            @Value("${exensio.precheck-row-limit:10000}") int precheckRowLimit) {
        this.exensioProperties = exensioProperties;
        this.authService = authService;
        this.objectMapper = objectMapper;
        this.httpClient = exensioHttpClient;
        this.precheckRowLimit = precheckRowLimit;
    }

    /**
     * Executes raw-SQL query via Exensio HTTP endpoint.
     * Returns lot metadata: lot_id, end_time, ppid, wafer_id, wafer_key, pg_key.
     * 
     * @param lotIds       list of lot IDs to query
     * @param waferIds     optional list of wafer IDs (used for wafer-level pgc_keys)
     * @param blocks       optional date range blocks
     * @param dataType     data type string to resolve pgc_key
     * @param environment  Exensio environment/schema
     * @return list of ExensioLotRow results, or null on failure
     */
    public List<ExensioLotRow> queryLotMetadata(
            List<String> lotIds,
            List<String> waferIds,
            List<PreCheckBlock> blocks,
            String dataType,
            String environment) {

        if (lotIds == null || lotIds.isEmpty()) {
            return Collections.emptyList();
        }

        String schema = resolveSchema(environment);
        String sql = buildSql(lotIds, waferIds, blocks, dataType);

        log.debug("[ExensioRawSql] Query: lots={}, wafers={}, pgcKey={}, schema={}",
                lotIds.size(), waferIds != null ? waferIds.size() : 0, 
                ExensioPreCheckService.resolvePgcKey(dataType), schema);

        try {
            String token = authService.getToken(schema);
            String responseBody = callRawSql(sql, token, schema);

            if (responseBody == null) {
                log.warn("[ExensioRawSql] Auth failed after token refresh — returning null");
                return null;
            }

            return parseResponse(responseBody);

        } catch (ExensioAuthService.ExensioAuthException e) {
            log.warn("[ExensioRawSql] Auth error: {}", e.getMessage());
            return null;
        } catch (Exception e) {
            log.warn("[ExensioRawSql] Unexpected error: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Builds the Oracle SQL query with metadata extraction including wafer_key and pg_key.
     */
    private String buildSql(List<String> lotIds, List<String> waferIds, List<PreCheckBlock> blocks, String dataType) {
        int pgcKey = ExensioPreCheckService.resolvePgcKey(dataType);
        boolean isWaferLevel = isWaferLevelClass(pgcKey);

        StringBuilder sb = new StringBuilder();

        sb.append("SELECT lot_id, end_time, ppid, wafer_id, wafer_key, pg_key FROM (\n");
        sb.append("  SELECT\n");
        sb.append("    l.lot_id                                                         AS lot_id,\n");
        sb.append("    NVL(TO_CHAR(ol.end_time,'YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"'), '') AS end_time,\n");
        sb.append("    NVL(p.ppid, '')                                                  AS ppid,\n");
        sb.append("    NVL(w.wf_id, '')                                                 AS wafer_id,\n");
        sb.append("    NVL(w.wf_key, -1)                                                AS wafer_key,\n");
        sb.append("    NVL(p.pg_key, -1)                                                AS pg_key\n");
        sb.append("  FROM op_log ol\n");
        sb.append("  JOIN lot      l   ON l.lot_key  = ol.lot_key\n");
        sb.append("  JOIN program  p   ON p.pg_key   = ol.pg_key\n");
        sb.append("  LEFT JOIN wf_log  wfl ON wfl.lg_key = ol.lg_key\n");
        sb.append("  LEFT JOIN wafer   w   ON w.wf_key   = wfl.wf_key\n");

        sb.append("  WHERE ol.pgc_key = ").append(pgcKey).append("\n");
        sb.append("    AND UPPER(TRIM(l.lot_id)) IN (");
        for (int i = 0; i < lotIds.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append("UPPER(TRIM('").append(escapeSql(lotIds.get(i))).append("'))");
        }
        sb.append(")\n");

        // Wafer-level filtering for Classes 1, 4, 5, 14
        if (isWaferLevel && waferIds != null && !waferIds.isEmpty()) {
            sb.append("    AND UPPER(TRIM(w.wf_id)) IN (");
            for (int i = 0; i < waferIds.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append("UPPER(TRIM('").append(escapeSql(waferIds.get(i))).append("'))");
            }
            sb.append(")\n");
        }

        List<String> dateRangeClauses = buildDateRangeClauses(blocks);
        if (!dateRangeClauses.isEmpty()) {
            sb.append("  AND (\n");
            for (int i = 0; i < dateRangeClauses.size(); i++) {
                if (i > 0) sb.append("    OR ");
                else sb.append("    ");
                sb.append("(").append(dateRangeClauses.get(i)).append(")\n");
            }
            sb.append("  )\n");
        }

        sb.append("  ORDER BY l.lot_id, ol.end_time DESC\n");
        sb.append(") WHERE ROWNUM <= ").append(precheckRowLimit);

        return sb.toString();
    }

    /**
     * Parses the Exensio raw-SQL JSON response.
     */
    private List<ExensioLotRow> parseResponse(String jsonResponse) {
        try {
            JsonNode root = objectMapper.readTree(jsonResponse);
            JsonNode rowsNode = root.path("rows");

            List<ExensioLotRow> rows = new ArrayList<>();
            if (rowsNode.isArray()) {
                for (JsonNode rowNode : rowsNode) {
                    rows.add(new ExensioLotRow(
                            rowNode.path("LOT_ID").asText(""),
                            rowNode.path("END_TIME").asText(""),
                            rowNode.path("PPID").asText(""),
                            rowNode.path("WAFER_ID").asText(""),
                            rowNode.path("WAFER_KEY").asLong(-1L),
                            rowNode.path("PG_KEY").asLong(-1L)
                    ));
                }
            }
            log.debug("[ExensioRawSql] Parsed {} rows from response", rows.size());
            return rows;

        } catch (Exception e) {
            log.warn("[ExensioRawSql] Failed to parse response: {}", e.getMessage());
            return null;
        }
    }

    private String callRawSql(String sql, String token, String schema) throws Exception {
        String result = doCallRawSql(sql, token, schema);
        if (result == null) {
            authService.invalidateToken(schema);
            String refreshedToken = authService.login(schema);
            result = doCallRawSql(sql, refreshedToken, schema);
        }
        return result;
    }

    private String doCallRawSql(String sql, String token, String schema) throws Exception {
        String url = exensioProperties.resolvedBaseUrl().replaceAll("/$", "") + "/v1/key/raw-sql";

        String bodyJson = objectMapper.createObjectNode()
                .put("sql", sql)
                .toString();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(bodyJson))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        log.debug("[ExensioRawSql] raw-sql response: HTTP {}, schema={}", response.statusCode(), schema);

        if (response.statusCode() == 401) {
            return null;
        }

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new RuntimeException("Exensio raw-sql returned HTTP " + response.statusCode()
                    + ": " + response.body());
        }

        return response.body();
    }

    private List<String> buildDateRangeClauses(List<PreCheckBlock> blocks) {
        return ExensioSqlUtilService.buildDateRangeClauses(blocks);
    }

    private static String escapeSql(String value) {
        return ExensioSqlUtilService.escapeSql(value);
    }

    private static boolean isWaferLevelClass(int pgcKey) {
        return ExensioSqlUtilService.isWaferLevelClass(pgcKey);
    }

    private String resolveSchema(String environment) {
        String schema = exensioProperties.getDbschema();
        return (schema != null && !schema.isBlank()) ? schema : "PRODUCTION";
    }

    /**
     * Data class representing a single lot row from raw-sql response.
     */
    public record ExensioLotRow(
            String lotId,
            String endTime,
            String ppid,
            String waferId,
            Long waferKey,
            Long pgKey
    ) {}
}
