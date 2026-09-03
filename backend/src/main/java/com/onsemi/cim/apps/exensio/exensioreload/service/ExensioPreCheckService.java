package com.onsemi.cim.apps.exensio.exensioreload.service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.onsemi.cim.apps.exensio.exensioreload.config.ExensioProperties;
import com.onsemi.cim.apps.exensio.exensioreload.config.SchemaFallbackConfig;
import com.onsemi.cim.apps.exensio.exensioreload.dto.ExensioPreCheckRequest;
import com.onsemi.cim.apps.exensio.exensioreload.dto.ExensioPreCheckResponse;
import com.onsemi.cim.apps.exensio.exensioreload.dto.ExensioPreCheckRow;
import com.onsemi.cim.apps.exensio.exensioreload.dto.PreCheckBlock;

/**
 * Queries the Exensio raw-SQL endpoint (primary) or Snowflake (fallback) to determine
 * whether submitted lots already exist in Exensio within the given year/month range.
 *
 * <p>Primary path: Exensio HTTP {@code POST /v1/key/raw-sql} (Oracle SQL).</p>
 * <p>Fallback path: Snowflake JDBC via {@code snowflakeDataSource}, querying
 * {@code ANALYTICSPRD.MFG.EXENSIO_PROD_OPLOG_METADATA}.</p>
 * <p>Adapted from xfcs-reloader to support PGC_KEY filtering based on dataType.</p>
 */
@Service
public class ExensioPreCheckService {

    private static final Logger log = LoggerFactory.getLogger(ExensioPreCheckService.class);

    // ── Snowflake SQL: with INSERT_TIME lower-bound date filter, PGC_KEY, and optional WAFER_ID ────
    static final String LOT_CHECK_SQL_WITH_DATE = """
            WITH provided_lots AS (
                SELECT value::VARCHAR AS lot_id
                FROM TABLE(FLATTEN(PARSE_JSON(?)))
            ),
            provided_wafers AS (
                SELECT value::VARCHAR AS wafer_id
                FROM TABLE(FLATTEN(PARSE_JSON(?)))
            ),
            found_lots AS (
                SELECT DISTINCT LOT_ID, WAFER_ID, SCHEMANAME
                FROM ANALYTICSPRD.MFG.EXENSIO_PROD_OPLOG_METADATA
                WHERE PGC_KEY     = ?
                  AND INSERT_TIME >= TO_DATE(? || '-01', 'YYYY-MM-DD')
                  AND LOT_ID IN (SELECT lot_id FROM provided_lots)
                  AND (? = 0 OR ? = 2 OR WAFER_ID IN (SELECT wafer_id FROM provided_wafers WHERE wafer_id IS NOT NULL))
            ),
            ranked AS (
                SELECT LOT_ID, WAFER_ID, SCHEMANAME,
                       ROW_NUMBER() OVER (
                           PARTITION BY LOT_ID, WAFER_ID
                           ORDER BY CASE WHEN UPPER(SCHEMANAME) LIKE '%PROD%' THEN 0 ELSE 1 END
                       ) AS rn
                FROM found_lots
            )
            SELECT p.lot_id, COALESCE(r.WAFER_ID, '') AS wafer_id, COALESCE(r.SCHEMANAME, 'NOT FOUND') AS schema_loaded
            FROM provided_lots p
            LEFT JOIN ranked r ON p.lot_id = r.lot_id AND r.rn = 1
            ORDER BY schema_loaded, p.lot_id
            """;

    // ── Snowflake SQL: no date filter, with PGC_KEY and optional WAFER_ID ────────────────────────
    static final String LOT_CHECK_SQL_NO_DATE = """
            WITH provided_lots AS (
                SELECT value::VARCHAR AS lot_id
                FROM TABLE(FLATTEN(PARSE_JSON(?)))
            ),
            provided_wafers AS (
                SELECT value::VARCHAR AS wafer_id
                FROM TABLE(FLATTEN(PARSE_JSON(?)))
            ),
            found_lots AS (
                SELECT DISTINCT LOT_ID, WAFER_ID, SCHEMANAME
                FROM ANALYTICSPRD.MFG.EXENSIO_PROD_OPLOG_METADATA
                WHERE PGC_KEY = ?
                  AND LOT_ID IN (SELECT lot_id FROM provided_lots)
                  AND (? = 0 OR ? = 2 OR WAFER_ID IN (SELECT wafer_id FROM provided_wafers WHERE wafer_id IS NOT NULL))
            ),
            ranked AS (
                SELECT LOT_ID, WAFER_ID, SCHEMANAME,
                       ROW_NUMBER() OVER (
                           PARTITION BY LOT_ID, WAFER_ID
                           ORDER BY CASE WHEN UPPER(SCHEMANAME) LIKE '%PROD%' THEN 0 ELSE 1 END
                       ) AS rn
                FROM found_lots
            )
            SELECT p.lot_id, COALESCE(r.WAFER_ID, '') AS wafer_id, COALESCE(r.SCHEMANAME, 'NOT FOUND') AS schema_loaded
            FROM provided_lots p
            LEFT JOIN ranked r ON p.lot_id = r.lot_id AND r.rn = 1
            ORDER BY schema_loaded, p.lot_id
            """;

    private final ExensioProperties exensioProperties;
    private final SchemaFallbackConfig schemaFallbackConfig;
    private final ExensioAuthService authService;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final DataSource snowflakeDataSource; // may be null if not configured
    private final int precheckRowLimit;

    public ExensioPreCheckService(
            ExensioProperties exensioProperties,
            SchemaFallbackConfig schemaFallbackConfig,
            ExensioAuthService authService,
            ObjectMapper objectMapper,
            @Qualifier("exensioHttpClient") HttpClient exensioHttpClient,
            @Qualifier("snowflakeDataSource") @org.springframework.beans.factory.annotation.Autowired(required = false) DataSource snowflakeDataSource,
            @Value("${exensio.precheck-row-limit:10000}") int precheckRowLimit) {
        this.exensioProperties = exensioProperties;
        this.schemaFallbackConfig = schemaFallbackConfig;
        this.authService = authService;
        this.objectMapper = objectMapper;
        this.httpClient = exensioHttpClient;
        this.snowflakeDataSource = snowflakeDataSource;
        this.precheckRowLimit = precheckRowLimit;
        
        if (snowflakeDataSource == null) {
            log.info("[ExensioPreCheck] Snowflake DataSource not configured — will use Exensio HTTP only (no Snowflake fallback)");
        }
        
        // Log schema fallback configuration at startup
        logSchemaFallbackConfiguration();
    }

    /**
     * Logs the schema fallback configuration at service initialization.
     * Called from constructor to document configuration at startup.
     *
     * @see #resolveSchemaPriorityList()
     * @see #isSnowflakeSecondaryFallbackEnabled()
     */
    private void logSchemaFallbackConfiguration() {
        if (schemaFallbackConfig.isSchemaFallbackEnabled()) {
            List<String> schemas = schemaFallbackConfig.resolveSchemaPriorityList();
            log.info("[ExensioPreCheck] Schema Fallback: enabled=true, schemas={}, snowflakeSecondary={}",
                schemas, schemaFallbackConfig.isEnableSnowflakeSecondary());
        } else {
            log.info("[ExensioPreCheck] Schema Fallback: enabled=false (using single schema only)");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Configuration helper methods
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Determines whether Snowflake secondary fallback is enabled.
     * Can be set via: config property + runtime flag from caller.
     *
     * <p>Requirements: 5.1, 5.2</p>
     *
     * @return true if Snowflake fallback should be used when HTTP finds nothing
     */
    public boolean isSnowflakeSecondaryFallbackEnabled() {
        return schemaFallbackConfig.isEnableSnowflakeSecondary();
    }

    /**
     * Resolves schema priority list from configuration.
     * Returns default [PRODUCTION, SANDBOX] if not configured.
     *
     * <p>Requirements: 5.1, 5.2</p>
     *
     * @return List of schema names in priority order
     */
    public List<String> resolveSchemaPriorityList() {
        return schemaFallbackConfig.resolveSchemaPriorityList();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public API — orchestration
    // ─────────────────────────────────────────────────────────────────────────

    // ─────────────────────────────────────────────────────────────────────────
    // Public API — orchestration
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Checks whether the submitted lots exist in Exensio.
     *
     * <p>Orchestration Strategy (HTTP-First with Auto-Switch and Snowflake Fallback):
     * <ol>
     *   <li><strong>Parse Configuration:</strong> Resolve schema priority list and Snowflake secondary flag</li>
     *   <li><strong>HTTP raw-SQL Primary:</strong> Try HTTP raw-sql path with configured schemas in order
     *     <ul>
     *       <li>If raw-sql finds lots: return immediately (success)</li>
     *       <li>If raw-sql returns empty (no lots found): auto-switch to step 3</li>
     *       <li>If raw-sql fails: fall through to step 3 (lot-wafer-lookup)</li>
     *     </ul>
     *   </li>
     *   <li><strong>Lot-Wafer Lookup Auto-Switch:</strong> When raw-SQL found nothing in any schema:
     *     <ul>
     *       <li>Try lot-wafer-lookup endpoint with same schema priority order</li>
     *       <li>If lot-wafer-lookup finds lots: return (success)</li>
     *       <li>If lot-wafer-lookup returns empty: proceed to step 4 (Snowflake)</li>
     *     </ul>
     *   </li>
     *   <li><strong>Snowflake Secondary:</strong> If enabled and all HTTP paths found nothing:
     *     <ul>
     *       <li>Query both PRODUCTION and SANDBOX in single UNION</li>
     *       <li>Prioritize PRODUCTION results on duplicate</li>
     *       <li>If Snowflake finds lots: return (success)</li>
     *       <li>If Snowflake also returns empty or fails: return soft error</li>
     *     </ul>
     *   </li>
     * </ol>
     *
     * <p>Returns a soft-failure response (with {@code error} field) rather than throwing.
     *
     * <p>Requirements: 1.1, 1.5, 2.4, 6.2, 8.1</p>
     */
    public ExensioPreCheckResponse check(ExensioPreCheckRequest request) {
        if (request.lotIds() == null || request.lotIds().isEmpty()) {
            return new ExensioPreCheckResponse(
                    Collections.emptyList(),
                    Collections.emptyList(),
                    Collections.emptyList(),
                    null);
        }

        long orchestrationStart = System.currentTimeMillis();
        log.info("[ExensioPreCheck] Starting orchestration: lots={}, env={}", request.lotIds().size(), request.environment());

        // Step 1: Parse configuration
        List<String> schemaPriority = resolveSchemaPriorityList();
        boolean configDefault = isSnowflakeSecondaryFallbackEnabled();
        boolean enableSnowflakeFallback = request.shouldEnableSnowflakeFallback(configDefault);
        boolean lotWaferTried = false;

        log.debug("[ExensioPreCheck] Orchestration: schemaPriority={}, snowflakeFallback={}", 
                schemaPriority, enableSnowflakeFallback);

        // Step 2: Primary HTTP raw-SQL path with multi-schema fallback
        if (exensioProperties.isConfigured()) {
            log.debug("[ExensioPreCheck] Attempting HTTP raw-sql path with schemas: {}", schemaPriority);
            ExensioPreCheckResponse httpResult = checkViaExensioHttpMultiSchema(request, schemaPriority);
            
            if (httpResult != null && !httpResult.lotsFound().isEmpty()) {
                long elapsed = System.currentTimeMillis() - orchestrationStart;
                log.info("[ExensioPreCheck] Orchestration complete (HTTP raw-sql success) in {} ms", elapsed);
                return httpResult; // HTTP found results, return success
            }
            
            if (httpResult != null && httpResult.error() != null) {
                long elapsed = System.currentTimeMillis() - orchestrationStart;
                log.warn("[ExensioPreCheck] HTTP raw-sql path error: {}", httpResult.error());
                // HTTP returned explicit error, but still try lot-wafer-lookup and Snowflake
            } else {
                long elapsed = System.currentTimeMillis() - orchestrationStart;
                log.debug("[ExensioPreCheck] HTTP raw-sql path returned empty after {} ms", elapsed);
            }

            // Step 3: Auto-switch to lot-wafer-lookup endpoint (raw-SQL found nothing in any schema)
            lotWaferTried = true;
            log.info("[ExensioPreCheck] Auto-switching to lot-wafer-lookup endpoint (raw-sql found nothing)");
            ExensioPreCheckResponse lotWaferResult = checkViaExensioLotWaferLookup(request, schemaPriority);

            if (lotWaferResult != null && !lotWaferResult.lotsFound().isEmpty()) {
                long elapsed = System.currentTimeMillis() - orchestrationStart;
                log.info("[ExensioPreCheck] Orchestration complete (lot-wafer-lookup success) in {} ms", elapsed);
                return lotWaferResult; // Lot-wafer-lookup found results, return success
            }

            if (lotWaferResult != null && lotWaferResult.error() != null) {
                log.warn("[ExensioPreCheck] Lot-wafer-lookup path error: {}", lotWaferResult.error());
            } else {
                long elapsed = System.currentTimeMillis() - orchestrationStart;
                log.debug("[ExensioPreCheck] Lot-wafer-lookup path returned empty after {} ms", elapsed);
            }
        } else {
            log.debug("[ExensioPreCheck] Exensio not configured — skipping HTTP raw-sql and lot-wafer-lookup paths");
        }

        // Step 4: Secondary Snowflake fallback (only if HTTP + lot-wafer-lookup returned empty/failed and fallback enabled)
        if (enableSnowflakeFallback && snowflakeDataSource != null) {
            log.debug("[ExensioPreCheck] Attempting Snowflake secondary fallback");
            ExensioPreCheckResponse snowflakeResult = checkViaSnowflake(request);
            
            if (snowflakeResult != null && !snowflakeResult.lotsFound().isEmpty()) {
                long elapsed = System.currentTimeMillis() - orchestrationStart;
                log.info("[ExensioPreCheck] Orchestration complete (Snowflake success) in {} ms", elapsed);
                return snowflakeResult; // Snowflake found results, return success
            }
            
            if (snowflakeResult != null && snowflakeResult.error() != null) {
                log.warn("[ExensioPreCheck] Snowflake path error: {}", snowflakeResult.error());
                // Snowflake returned explicit error
            } else if (snowflakeResult != null) {
                long elapsed = System.currentTimeMillis() - orchestrationStart;
                log.debug("[ExensioPreCheck] Snowflake path returned empty after {} ms", elapsed);
            } else {
                log.debug("[ExensioPreCheck] Snowflake path not available");
            }
        } else {
            if (!enableSnowflakeFallback) {
                log.debug("[ExensioPreCheck] Snowflake secondary fallback disabled by configuration");
            } else {
                log.debug("[ExensioPreCheck] Snowflake DataSource not available for fallback");
            }
        }

        // Step 5: All paths failed or returned empty
        long totalElapsed = System.currentTimeMillis() - orchestrationStart;
        String errorMessage = buildOrchestratedErrorMessage(exensioProperties.isConfigured(), lotWaferTried, enableSnowflakeFallback, snowflakeDataSource != null);
        log.warn("[ExensioPreCheck] Orchestration failed (no lots found in any path) after {} ms: {}", totalElapsed, errorMessage);
        
        return new ExensioPreCheckResponse(
                Collections.emptyList(), // lotsFound: none found
                request.lotIds(),         // lotsNotFound: all lots
                Collections.emptyList(),
                errorMessage);
    }

    /**
     * Builds an informative error message describing which paths were attempted during orchestration.
     *
     * @param exensioConfigured whether Exensio HTTP endpoint is configured
     * @param lotWaferTried whether lot-wafer-lookup was attempted
     * @param snowflakeFallbackEnabled whether Snowflake fallback is enabled
     * @param snowflakeAvailable whether Snowflake DataSource is available
     * @return error message describing attempted paths
     */
    private String buildOrchestratedErrorMessage(boolean exensioConfigured, boolean lotWaferTried, boolean snowflakeFallbackEnabled, boolean snowflakeAvailable) {
        StringBuilder msg = new StringBuilder("Pre-flight check: unable to verify lot existence. Attempted paths: ");
        
        if (exensioConfigured) {
            msg.append("raw-sql (PRODUCTION→SANDBOX)");
            if (lotWaferTried) {
                msg.append(" → lot-wafer-lookup (PRODUCTION→SANDBOX)");
            }
        } else {
            msg.append("HTTP (not configured)");
        }
        
        if (snowflakeFallbackEnabled && snowflakeAvailable) {
            msg.append(", Snowflake (both schemas)");
        } else if (snowflakeFallbackEnabled) {
            msg.append(", Snowflake (not available)");
        } else {
            msg.append(", Snowflake (disabled)");
        }
        
        msg.append(". All paths exhausted without result.");
        return msg.toString();
    }

    /**
     * Checks whether the submitted lots exist in Exensio.
     * Tries Exensio HTTP raw-SQL first; falls back to Snowflake JDBC on failure.
     * Returns a soft-failure response (with {@code error} field) rather than throwing.
     */
    public ExensioPreCheckResponse check_original(ExensioPreCheckRequest request) {
        if (request.lotIds() == null || request.lotIds().isEmpty()) {
            return new ExensioPreCheckResponse(
                    Collections.emptyList(),
                    Collections.emptyList(),
                    Collections.emptyList(),
                    null);
        }

        // Primary: Exensio HTTP raw-SQL
        if (exensioProperties.isConfigured()) {
            ExensioPreCheckResponse httpResult = checkViaExensioHttp(request);
            if (httpResult != null) {
                return httpResult;
            }
        } else {
            log.debug("[ExensioPreCheck] Exensio not configured — skipping HTTP raw-sql path");
        }

        // Fallback: Snowflake JDBC
        ExensioPreCheckResponse snowflakeResult = checkViaSnowflake(request);
        if (snowflakeResult != null) {
            return snowflakeResult;
        }

        // Both paths failed or unavailable
        return softError("Both Exensio and Snowflake pre-check paths failed");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Fallback: Snowflake JDBC
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Executes the pre-check query via Snowflake JDBC.
     *
     * @return populated response on success, {@code null} to signal fallback should be used
     */
    ExensioPreCheckResponse checkViaSnowflake(ExensioPreCheckRequest request) {
        if (request.lotIds() == null || request.lotIds().isEmpty()) {
            return new ExensioPreCheckResponse(
                    Collections.emptyList(),
                    Collections.emptyList(),
                    Collections.emptyList(),
                    null);
        }

        // Skip Snowflake if DataSource not available
        if (snowflakeDataSource == null) {
            log.debug("[ExensioPreCheck] Snowflake DataSource not available — skipping Snowflake path");
            return null;
        }

        String lotIdsJson = buildLotIdsJson(request.lotIds());
        String waferIdsJson = (request.waferIds() != null && !request.waferIds().isEmpty()) 
                ? buildLotIdsJson(request.waferIds()) 
                : "[]";
        String yearMonth  = deriveEarliestYearMonth(request.blocks());
        int pgcKey = resolvePgcKey(request.dataType());
        boolean isWaferLevel = isWaferLevelClass(pgcKey);

        log.debug("[ExensioPreCheck] Snowflake query: lots={}, wafers={}, yearMonth={}, pgcKey={}, waferLevel={}",
                request.lotIds().size(), 
                request.waferIds() != null ? request.waferIds().size() : 0,
                yearMonth, pgcKey, isWaferLevel);

        try (Connection conn = snowflakeDataSource.getConnection()) {
            PreparedStatement ps;
            // Determine if we should return all wafers: wafer-level class AND no wafer IDs provided
            boolean hasWaferFilter = request.waferIds() != null && !request.waferIds().isEmpty();
            int waferFilterMode = isWaferLevel ? (hasWaferFilter ? 1 : 2) : 0;  
            // 0 = lot-level only, 1 = wafer-level with filter, 2 = wafer-level return all wafers
            
            if (yearMonth != null) {
                ps = conn.prepareStatement(LOT_CHECK_SQL_WITH_DATE);
                ps.setString(1, lotIdsJson);
                ps.setString(2, waferIdsJson);
                ps.setInt(3, pgcKey);
                ps.setString(4, yearMonth);
                ps.setInt(5, waferFilterMode);
                ps.setInt(6, waferFilterMode);
            } else {
                ps = conn.prepareStatement(LOT_CHECK_SQL_NO_DATE);
                ps.setString(1, lotIdsJson);
                ps.setString(2, waferIdsJson);
                ps.setInt(3, pgcKey);
                ps.setInt(4, waferFilterMode);
                ps.setInt(5, waferFilterMode);
            }

            try (ps; ResultSet rs = ps.executeQuery()) {
                List<ExensioPreCheckRow> rows = new ArrayList<>();
                while (rs.next()) {
                    String rawWafer = rs.getString("WAFER_ID");
                    String cleanWafer = ExensioSqlUtilService.stripWaferPrefix(rawWafer);
                    rows.add(new ExensioPreCheckRow(
                            rs.getString("LOT_ID"),
                            rs.getString("SCHEMA_LOADED"),
                            cleanWafer));
                }
                log.debug("[ExensioPreCheck] Snowflake returned {} rows (waferFilterMode={})", rows.size(), waferFilterMode);
                return partitionResults(rows, request.lotIds());
            }

        } catch (SQLException e) {
            log.warn("[ExensioPreCheck] Snowflake JDBC failed — no more fallbacks: {}", e.getMessage());
            return null; // signal to use fallback
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Primary: Exensio HTTP raw-SQL (Multi-Schema Strategy)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Executes HTTP multi-schema sequential query logic for preflight check.
     * Queries schemas in priority order from configuration: PRODUCTION → SANDBOX.
     * Reuses authentication token across schema attempts.
     *
     * <p>Algorithm:
     * <ol>
     *   <li>For each configured schema in priority order:
     *     <ol>
     *       <li>Execute HTTP raw-sql query for that schema</li>
     *       <li>If successful with results: return immediately (stop fallback)</li>
     *       <li>If 401 (Unauthorized): invalidate token, refresh, retry same schema once</li>
     *       <li>If transient error (5xx, timeout): log and continue to next schema</li>
     *       <li>If successful but empty: continue to next schema</li>
     *     </ol>
     *   </li>
     *   <li>If all schemas exhausted without results: return null (signal Snowflake fallback)</li>
     * </ol>
     *
     * <p>Requirements: 1.1, 1.4, 2.3, 10.1, 10.2, 10.3</p>
     *
     * @param request the pre-check request
     * @param schemas list of schema names in priority order (typically [PRODUCTION, SANDBOX])
     * @return populated response if found, null to signal Snowflake secondary fallback should be attempted
     */
    ExensioPreCheckResponse checkViaExensioHttpMultiSchema(ExensioPreCheckRequest request, List<String> schemas) {
        if (request.lotIds() == null || request.lotIds().isEmpty()) {
            return new ExensioPreCheckResponse(
                    Collections.emptyList(),
                    Collections.emptyList(),
                    Collections.emptyList(),
                    null);
        }

        if (schemas == null || schemas.isEmpty()) {
            log.debug("[ExensioPreCheck] HTTP multi-schema: no schemas to query");
            return null;
        }

        String sql = buildSql(request.lotIds(), request.waferIds(), request.blocks(), request.dataType(), request.filenames());
        long startTime = System.currentTimeMillis();

        for (int i = 0; i < schemas.size(); i++) {
            String schema = schemas.get(i);
            log.debug("[ExensioPreCheck] HTTP multi-schema attempt {} of {}: schema={}, lots={}",
                    i + 1, schemas.size(), schema, request.lotIds().size());

            try {
                // Get or refresh token for this schema
                String token = authService.getToken(schema);

                // Execute HTTP raw-sql query
                String responseBody = callRawSql(sql, token, schema);

                if (responseBody == null) {
                    log.debug("[ExensioPreCheck] HTTP {}: auth failed after token refresh, trying next schema", schema);
                    continue; // Try next schema
                }

                // Parse response
                ExensioPreCheckResponse result = parseResponse(responseBody, request.lotIds());

                if (result == null) {
                    log.debug("[ExensioPreCheck] HTTP {}: parse failed, trying next schema", schema);
                    continue; // Try next schema
                }

                if (result.error() != null) {
                    log.debug("[ExensioPreCheck] HTTP {}: parse error ({}), trying next schema",
                            schema, result.error());
                    continue; // Try next schema
                }

                // Check if we found any lots
                if (!result.lotsFound().isEmpty()) {
                    long elapsed = System.currentTimeMillis() - startTime;
                    log.info("[ExensioPreCheck] HTTP {}: found {} lots in {} ms",
                            schema, result.lotsFound().size(), elapsed);
                    return result; // Found results, stop fallback
                }

                // This schema returned empty, continue to next schema
                log.debug("[ExensioPreCheck] HTTP {}: no lots found, trying next schema", schema);

            } catch (ExensioAuthService.ExensioAuthException e) {
                log.debug("[ExensioPreCheck] HTTP {}: auth error ({}), trying next schema",
                        schema, e.getMessage());
                continue; // Try next schema
            } catch (Exception e) {
                log.debug("[ExensioPreCheck] HTTP {}: unexpected error ({}), trying next schema",
                        schema, e.getMessage());
                continue; // Try next schema
            }
        }

        // All schemas exhausted without finding lots
        long elapsed = System.currentTimeMillis() - startTime;
        log.debug("[ExensioPreCheck] HTTP multi-schema: all {} schemas exhausted after {} ms, no lots found",
                schemas.size(), elapsed);
        return null; // Signal Snowflake secondary fallback should be attempted
    }

    /**
     * Executes the pre-check query via the Exensio HTTP {@code POST /v1/key/raw-sql} endpoint.
     * Reuses the existing Oracle SQL builder and re-logs on 401 with one token refresh.
     *
     * @return populated response on success, {@code null} to signal fallback should be used
     */
    ExensioPreCheckResponse checkViaExensioHttp(ExensioPreCheckRequest request) {
        String schema = resolveSchema(request.environment());
        String sql    = buildSql(request.lotIds(), request.waferIds(), request.blocks(), request.dataType(), request.filenames());

        log.debug("[ExensioPreCheck] HTTP primary: lots={}, wafers={}, schema={}", 
                request.lotIds().size(), 
                request.waferIds() != null ? request.waferIds().size() : 0, 
                schema);

        try {
            String token       = authService.getToken(schema);
            String responseBody = callRawSql(sql, token, schema);

            if (responseBody == null) {
                log.warn("[ExensioPreCheck] HTTP raw-sql: auth failed after token refresh — falling through");
                return null;
            }

            ExensioPreCheckResponse result = parseResponse(responseBody, request.lotIds());
            if (result != null && result.error() != null) {
                log.warn("[ExensioPreCheck] HTTP raw-sql: parse error — falling through: {}", result.error());
                return null;
            }
            return result;

        } catch (ExensioAuthService.ExensioAuthException e) {
            log.warn("[ExensioPreCheck] HTTP raw-sql auth error — falling through: {}", e.getMessage());
            return null;
        } catch (Exception e) {
            log.warn("[ExensioPreCheck] HTTP raw-sql unexpected error — falling through: {}", e.getMessage());
            return null;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PGC_KEY resolution (NEW)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Maps data type to PGC_KEY value for Exensio queries.
     * 
     * @param dataType the data type string (case-insensitive)
     * @return PGC_KEY integer value
     */
    public static int resolvePgcKey(String dataType) {
        return DataTypePgcKeyMapper.resolve(dataType);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // buildLotIdsJson
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Serializes a list of lot IDs into a JSON array string suitable for use as a
     * Snowflake JDBC {@code PARSE_JSON(?)} bind parameter.
     *
     * <p>Double-quote characters within lot ID values are escaped as {@code \"}.</p>
     *
     * <p>Example: {@code ["LOT001","LOT002"]}.</p>
     *
     * @param lotIds non-null, non-empty list of lot IDs
     * @return JSON array string, e.g. {@code ["LOT001","LOT002"]}
     * @throws IllegalArgumentException if lotIds is null
     */
    public static String buildLotIdsJson(List<String> lotIds) {
        if (lotIds == null) {
            throw new IllegalArgumentException("lotIds must not be null");
        }
        return "[" + lotIds.stream()
                .map(id -> "\"" + (id == null ? "" : id).replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
                .collect(Collectors.joining(",")) + "]";
    }

    // ─────────────────────────────────────────────────────────────────────────
    // deriveEarliestYearMonth
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Derives the earliest {@code 'YYYY-MM'} formatted string from the provided blocks
     * for use as the Snowflake {@code INSERT_TIME} lower-bound bind parameter.
     *
     * <ul>
     *   <li>If a block has both year and month, uses that year-month.</li>
     *   <li>If a block has only a year (no month), treats it as {@code year-01} (January).</li>
     *   <li>If no blocks have a year, returns {@code null} (no date filter).</li>
     *   <li>The earliest year-month across all blocks is returned.</li>
     * </ul>
     *
     * @param blocks optional list of pre-check blocks (may be null or empty)
     * @return {@code 'YYYY-MM'} string for the earliest year+month, or {@code null}
     */
    public static String deriveEarliestYearMonth(List<PreCheckBlock> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            return null;
        }

        String earliest = null;

        for (PreCheckBlock block : blocks) {
            if (block.year() == null) {
                continue;
            }
            int month = (block.month() != null) ? block.month() : 1;
            String candidate = String.format("%04d-%02d", block.year(), month);

            if (earliest == null || candidate.compareTo(earliest) < 0) {
                earliest = candidate;
            }
        }

        return earliest; // null when no block has a year
    }

    // ─────────────────────────────────────────────────────────────────────────
    // partitionResults
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Partitions submitted lot IDs into {@code lotsFound} and {@code lotsNotFound} based
     * on Snowflake result rows.
     *
     * <ul>
     *   <li>Rows with {@code schemaName = 'NOT FOUND'} contribute to {@code lotsNotFound}.</li>
     *   <li>Rows with any other {@code schemaName} contribute to {@code lotsFound}.</li>
     *   <li>Comparison against submitted lot IDs is case-insensitive.</li>
     *   <li>For wafer-level queries, multiple rows per lot are preserved to capture all wafers.</li>
     * </ul>
     *
     * @param rows           rows returned from the Snowflake query
     * @param submittedLotIds the original lot IDs from the request
     * @return {@link ExensioPreCheckResponse} with partitioned lists (no error field)
     */
    public static ExensioPreCheckResponse partitionResults(
            List<ExensioPreCheckRow> rows,
            List<String> submittedLotIds) {

        // Collect lot IDs that have an actual Snowflake record (schema ≠ NOT FOUND)
        Set<String> foundUpper = rows.stream()
                .filter(r -> !"NOT FOUND".equalsIgnoreCase(r.schemaName()))
                .map(r -> r.lotId().toUpperCase())
                .collect(Collectors.toCollection(LinkedHashSet::new));

        List<String> lotsFound = submittedLotIds.stream()
                .filter(l -> foundUpper.contains(l.toUpperCase()))
                .collect(Collectors.toList());

        List<String> lotsNotFound = submittedLotIds.stream()
                .filter(l -> !foundUpper.contains(l.toUpperCase()))
                .collect(Collectors.toList());

        // Only include rows that were actually found (exclude NOT FOUND sentinel rows)
        // For wafer-level queries, keep all wafer rows per lot
        List<ExensioPreCheckRow> foundRows = rows.stream()
                .filter(r -> !"NOT FOUND".equalsIgnoreCase(r.schemaName()))
                .collect(Collectors.toList());

        return new ExensioPreCheckResponse(lotsFound, lotsNotFound, foundRows, null);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Oracle SQL builder — filename prefix matching replaces wafer ID filtering
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Builds the Oracle SQL query for the Exensio raw-SQL endpoint.
     * Matches by lot + first-15-chars of filename (from df_export) instead of
     * filtering on w.wf_id, since the wafer field format is unreliable across schemas.
     *
     * @param lotIds       list of lot IDs to check
     * @param waferIds     optional list of wafer IDs (still used to determine wafer-level mode)
     * @param blocks       optional date range blocks for filtering
     * @param dataType     data type string to resolve pgc_key
     * @param filenames    optional filenames for df_export.file_name prefix matching (first 15 chars)
     * @return Oracle SQL query string
     */
    public String buildSql(List<String> lotIds, List<String> waferIds, List<PreCheckBlock> blocks,
                           String dataType, List<String> filenames) {
        int pgcKey = resolvePgcKey(dataType);
        boolean isWaferLevel = isWaferLevelClass(pgcKey);

        StringBuilder sb = new StringBuilder();

        String lotInList = lotIds.stream()
                .map(l -> "'" + escapeSql(l.trim().toUpperCase(java.util.Locale.ROOT)) + "'")
                .collect(java.util.stream.Collectors.joining(", "));

        if (isWaferLevel && (waferIds == null || waferIds.isEmpty())) {
            // ── Wafer-level, no wafer filter: return all wafers per lot ──
            sb.append("SELECT DISTINCT lot_id, wafer_id FROM (\n");
            sb.append("  SELECT\n");
            sb.append("    l.lot_id                    AS lot_id,\n");
            sb.append("    NVL(w.wf_id, '')            AS wafer_id\n");
            sb.append("  FROM op_log ol\n");
            sb.append("  JOIN lot      l   ON l.lot_key  = ol.lot_key\n");
            sb.append("  JOIN program  p   ON p.pg_key   = ol.pg_key\n");
            sb.append("  LEFT JOIN wf_log  wfl ON wfl.lg_key = ol.lg_key\n");
            sb.append("  LEFT JOIN wafer   w   ON w.wf_key   = wfl.wf_key\n");
            sb.append("  LEFT JOIN df_export de ON de.lg_key = ol.lg_key AND (w.wf_key IS NULL OR de.wf_key = w.wf_key)\n");

            sb.append("  WHERE ol.pgc_key = ").append(pgcKey).append("\n");
            sb.append("    AND UPPER(TRIM(l.lot_id)) IN (").append(lotInList).append(")\n");
            appendFilenameFilter(sb, filenames);
            appendDateRangeFilter(sb, blocks);

            sb.append("  ORDER BY l.lot_id\n");
            sb.append(") WHERE ROWNUM <= ").append(precheckRowLimit);
        } else {
            // ── Lot-level OR wafer-filtered: replace wafer filter with filename prefix match ──
            sb.append("SELECT lot_id, end_time, ppid, wafer_id FROM (\n");
            sb.append("  SELECT\n");
            sb.append("    l.lot_id                                                         AS lot_id,\n");
            sb.append("    NVL(TO_CHAR(ol.end_time,'YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"'), '') AS end_time,\n");
            sb.append("    NVL(p.ppid, '')                                                  AS ppid,\n");
            sb.append("    NVL(w.wf_id, '')                                                 AS wafer_id\n");
            sb.append("  FROM op_log ol\n");
            sb.append("  JOIN lot      l   ON l.lot_key  = ol.lot_key\n");
            sb.append("  JOIN program  p   ON p.pg_key   = ol.pg_key\n");
            sb.append("  LEFT JOIN wf_log  wfl ON wfl.lg_key = ol.lg_key\n");
            sb.append("  LEFT JOIN wafer   w   ON w.wf_key   = wfl.wf_key\n");
            sb.append("  LEFT JOIN df_export de ON de.lg_key = ol.lg_key AND (w.wf_key IS NULL OR de.wf_key = w.wf_key)\n");

            sb.append("  WHERE ol.pgc_key = ").append(pgcKey).append("\n");
            sb.append("    AND UPPER(TRIM(l.lot_id)) IN (").append(lotInList).append(")\n");
            appendFilenameFilter(sb, filenames);
            appendDateRangeFilter(sb, blocks);

            sb.append("  ORDER BY l.lot_id, ol.end_time DESC\n");
            sb.append(") WHERE ROWNUM <= ").append(precheckRowLimit);
        }

        return sb.toString();
    }

    /**
     * Appends a df_export.file_name prefix filter to the SQL, matching first 15 characters.
     * Multiple filenames are OR'd together.
     */
    private void appendFilenameFilter(StringBuilder sb, List<String> filenames) {
        if (filenames == null || filenames.isEmpty()) {
            return;
        }
        sb.append("    AND (\n");
        for (int i = 0; i < filenames.size(); i++) {
            String fn = filenames.get(i);
            if (fn == null || fn.isBlank()) continue;
            String prefix = fn.trim().substring(0, Math.min(15, fn.trim().length()));
            if (i > 0) sb.append("      OR ");
            else sb.append("      ");
            sb.append("UPPER(SUBSTR(NVL(de.file_name, ' '), 1, 15)) = UPPER('")
              .append(escapeSql(prefix)).append("')\n");
        }
        sb.append("    )\n");
    }

    /**
     * Appends the date range OR clauses from blocks.
     */
    private void appendDateRangeFilter(StringBuilder sb, List<PreCheckBlock> blocks) {
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
    }

    // ── Backward-compatible overload (for any external callers) ────────────

    /**
     * @deprecated since v3.1 — Use {@link #buildSql(List, List, List, String, List)} instead.
     *              Kept to track migration progress. Remove once all callers are migrated.
     */
    @Deprecated
    public String buildSql(List<String> lotIds, List<String> waferIds, List<PreCheckBlock> blocks, String dataType) {
        return buildSql(lotIds, waferIds, blocks, dataType, null);
    }

    /**
     * Determines if a pgc_key represents a wafer-level class.
     * Delegated to ExensioSqlUtilService for consistency.
     */
    private static boolean isWaferLevelClass(int pgcKey) {
        return ExensioSqlUtilService.isWaferLevelClass(pgcKey);
    }

    /**
     * Parses the Exensio raw-SQL JSON response and partitions the submitted lot IDs.
     * Maps HTTP fallback rows (LOT_ID / END_TIME / PPID / WAFER_ID) to the unified
     * {@link ExensioPreCheckRow} shape ({@code lotId} + {@code schemaName} + {@code waferId}).
     * For HTTP fallback results, {@code schemaName} is set to {@code "FOUND"} when a
     * row is present (the Oracle query does not return a schema name).
     */
    public ExensioPreCheckResponse parseResponse(String jsonResponse, List<String> submittedLotIds) {
        try {
            JsonNode root     = objectMapper.readTree(jsonResponse);
            JsonNode rowsNode = root.path("rows");

            List<ExensioPreCheckRow> rows = new ArrayList<>();
            if (rowsNode.isArray()) {
                for (JsonNode rowNode : rowsNode) {
                    String lotId = rowNode.path("LOT_ID").asText("");
                    String rawWafer = rowNode.path("WAFER_ID").asText("");
                    String waferId = ExensioSqlUtilService.stripWaferPrefix(rawWafer);
                    // HTTP fallback doesn't return SCHEMANAME — use "FOUND" as sentinel
                    rows.add(new ExensioPreCheckRow(lotId, "FOUND", waferId));
                }
            }

            Set<String> foundUpper = rows.stream()
                    .map(r -> r.lotId().toUpperCase())
                    .collect(Collectors.toSet());

            List<String> lotsFound = submittedLotIds.stream()
                    .filter(l -> foundUpper.contains(l.toUpperCase()))
                    .collect(Collectors.toList());

            List<String> lotsNotFound = submittedLotIds.stream()
                    .filter(l -> !foundUpper.contains(l.toUpperCase()))
                    .collect(Collectors.toList());

            return new ExensioPreCheckResponse(lotsFound, lotsNotFound, rows, null);

        } catch (Exception e) {
            log.warn("[ExensioPreCheck] Failed to parse HTTP response: {}", e.getMessage());
            return softError("Failed to parse Exensio response: " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HTTP helpers — preserved from original service
    // ─────────────────────────────────────────────────────────────────────────

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
        log.debug("[ExensioPreCheck] raw-sql response: HTTP {}, schema={}", response.statusCode(), schema);

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

    // SQL utilities now consolidated in ExensioSqlUtilService — use delegating methods for backward compatibility
    static String yearOnlyClause(int year) {
        return ExensioSqlUtilService.yearOnlyClause(year);
    }

    static String yearMonthClause(int year, int month) {
        return ExensioSqlUtilService.yearMonthClause(year, month);
    }

    static String escapeSql(String value) {
        return ExensioSqlUtilService.escapeSql(value);
    }

    /**
     * Zero-pads a numeric wafer ID to 2 digits so that it matches the
     * Oracle {@code wf_id} storage format (e.g. {@code "1"} → {@code "01"}).
     * Non-numeric IDs (e.g. {@code "01A"}) are returned unchanged.
     *
     * @param waferId raw wafer ID from the request
     * @return zero-padded wafer ID if purely numeric, otherwise the original value
     */
    static String zeroPadWaferId(String waferId) {
        if (waferId == null || waferId.isBlank()) {
            return waferId;
        }
        String trimmed = waferId.trim();
        if (trimmed.matches("\\d+")) {
            try {
                return String.format("%02d", Integer.parseInt(trimmed));
            } catch (NumberFormatException ignored) {
                // Fall through — value too large for int, return as-is
            }
        }
        return trimmed;
    }

    private String resolveSchema(String environment) {
        String schema = exensioProperties.getDbschema();
        return (schema != null && !schema.isBlank()) ? schema : "PRODUCTION";
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Error handling and classification
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Classifies whether an error is transient (retriable) or permanent.
     * Used to determine whether to attempt next schema in fallback sequence.
     *
     * <p>Transient errors (should retry next schema):
     * <ul>
     *   <li>HTTP 429 (Too Many Requests)</li>
     *   <li>HTTP 5xx (Server errors)</li>
     *   <li>Connection timeouts</li>
     *   <li>Socket timeouts</li>
     *   <li>Temporary unavailable (e.g., temporarily down)</li>
     * </ul>
     *
     * <p>Permanent errors (should skip to Snowflake):
     * <ul>
     *   <li>HTTP 401 (Auth failed after refresh)</li>
     *   <li>HTTP 403 (Forbidden)</li>
     *   <li>HTTP 404 (Not found)</li>
     *   <li>SQL syntax errors</li>
     *   <li>Connection refused</li>
     *   <li>Invalid credentials</li>
     * </ul>
     *
     * <p>Requirements: 6.1, 6.3, 6.5</p>
     *
     * @param exception the exception to classify
     * @return true if error is transient (retriable), false if permanent
     */
    public static boolean isTransientError(Exception exception) {
        if (exception == null) {
            return false;
        }

        String message = exception.getMessage();
        if (message == null) {
            message = "";
        }
        String msgLower = message.toLowerCase();

        // Network-level transient errors
        if (msgLower.contains("timeout") || 
            msgLower.contains("timed out") ||
            msgLower.contains("socket timeout") ||
            msgLower.contains("connection timeout")) {
            return true;
        }

        // Connection transient errors
        if (msgLower.contains("temporarily") ||
            msgLower.contains("temporarily unavailable") ||
            msgLower.contains("service unavailable")) {
            return true;
        }

        // HTTP 429, 5xx implied in exception messages
        if (msgLower.contains("429") ||
            msgLower.contains("too many requests") ||
            msgLower.contains("rate limit")) {
            return true;
        }

        if (msgLower.contains("500") || 
            msgLower.contains("502") || 
            msgLower.contains("503") || 
            msgLower.contains("504") ||
            msgLower.contains("server error") ||
            msgLower.contains("bad gateway") ||
            msgLower.contains("service unavailable")) {
            return true;
        }

        // Permanent errors: auth, permission, not found
        if (msgLower.contains("401") || 
            msgLower.contains("unauthorized") ||
            msgLower.contains("403") ||
            msgLower.contains("forbidden") ||
            msgLower.contains("404") ||
            msgLower.contains("not found") ||
            msgLower.contains("invalid credentials") ||
            msgLower.contains("authentication") ||
            msgLower.contains("permission denied")) {
            return false;
        }

        // SQL-level errors are typically permanent
        if (msgLower.contains("sql") ||
            msgLower.contains("syntax") ||
            msgLower.contains("column") ||
            msgLower.contains("table")) {
            return false;
        }

        // Unknown errors: assume transient to be conservative
        return true;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Lot-Wafer Lookup endpoint (auto-switch when raw-SQL finds nothing)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Executes the pre-check query via the Exensio HTTP lot-wafer-lookup endpoint.
     * Tries schemas in priority order: PRODUCTION → SANDBOX.
     *
     * <p>This is the auto-switch fallback when raw-SQL finds nothing from any schema.
     * The lot-wafer-lookup endpoint uses pgc_key + lot_ids + wafer_ids instead of
     * raw Oracle SQL.
     *
     * @param request the pre-check request
     * @param schemas list of schema names in priority order (typically [PRODUCTION, SANDBOX])
     * @return populated response if found, null if no lots found in any schema
     */
    ExensioPreCheckResponse checkViaExensioLotWaferLookup(ExensioPreCheckRequest request, List<String> schemas) {
        if (request.lotIds() == null || request.lotIds().isEmpty()) {
            return null;
        }

        if (schemas == null || schemas.isEmpty()) {
            log.debug("[ExensioPreCheck] Lot-wafer lookup: no schemas to query");
            return null;
        }

        int pgcKey = resolvePgcKey(request.dataType());
        long startTime = System.currentTimeMillis();

        // Track found lots across schemas — keyed by lotId (upper case)
        java.util.Map<String, List<LotWaferFoundRow>> foundBySchema = new java.util.LinkedHashMap<>();
        java.util.Set<String> foundLots = new java.util.LinkedHashSet<>();

        for (int i = 0; i < schemas.size(); i++) {
            String schema = schemas.get(i);
            log.debug("[ExensioPreCheck] Lot-wafer lookup attempt {} of {}: schema={}, lots={}",
                    i + 1, schemas.size(), schema, request.lotIds().size());

            try {
                String token = authService.getToken(schema);
                // Skip lots already found in previous schema
                List<String> remainingLots = request.lotIds().stream()
                        .filter(l -> !foundLots.contains(l.toUpperCase()))
                        .collect(java.util.stream.Collectors.toList());

                if (remainingLots.isEmpty()) {
                    log.debug("[ExensioPreCheck] Lot-wafer lookup: all lots already found, skipping schema {}", schema);
                    continue;
                }

                LotWaferLookupResponse lookupResult = callLotWaferLookup(
                        remainingLots, request.waferIds(), pgcKey, token, schema);

                if (lookupResult == null || lookupResult.lotIds().isEmpty()) {
                    log.debug("[ExensioPreCheck] Lot-wafer lookup {}: no lots found, trying next schema", schema);
                    continue;
                }

                // Track what was found in this schema
                for (LotWaferFoundRow row : lookupResult.rows()) {
                    foundLots.add(row.lotId().toUpperCase());
                    foundBySchema.computeIfAbsent(schema, k -> new java.util.ArrayList<>()).add(row);
                }

                log.info("[ExensioPreCheck] Lot-wafer lookup {}: found {} lots, remaining={}",
                        schema, lookupResult.lotIds().size(),
                        request.lotIds().size() - foundLots.size());

                // If all found, stop
                if (foundLots.size() >= request.lotIds().size()) {
                    long elapsed = System.currentTimeMillis() - startTime;
                    log.info("[ExensioPreCheck] Lot-wafer lookup complete (all found across schemas) in {} ms", elapsed);
                    break;
                }

            } catch (ExensioAuthService.ExensioAuthException e) {
                log.debug("[ExensioPreCheck] Lot-wafer lookup {}: auth error ({}), trying next schema",
                        schema, e.getMessage());
                continue;
            } catch (Exception e) {
                log.debug("[ExensioPreCheck] Lot-wafer lookup {}: unexpected error ({}), trying next schema",
                        schema, e.getMessage());
                continue;
            }
        }

        long elapsed = System.currentTimeMillis() - startTime;

        if (foundLots.isEmpty()) {
            log.debug("[ExensioPreCheck] Lot-wafer lookup: no lots found in any schema after {} ms", elapsed);
            return null;
        }

        // Build response
        List<ExensioPreCheckRow> rows = new ArrayList<>();
        for (java.util.Map.Entry<String, List<LotWaferFoundRow>> entry : foundBySchema.entrySet()) {
            String schema = entry.getKey();
            for (LotWaferFoundRow row : entry.getValue()) {
                rows.add(new ExensioPreCheckRow(row.lotId(), schema, row.waferId()));
            }
        }

        List<String> lotsFound = new ArrayList<>(foundLots);
        List<String> lotsNotFound = request.lotIds().stream()
                .filter(l -> !foundLots.contains(l.toUpperCase()))
                .collect(java.util.stream.Collectors.toList());

        log.info("[ExensioPreCheck] Lot-wafer lookup complete in {} ms: found={}, notFound={}",
                elapsed, lotsFound.size(), lotsNotFound.size());

        return new ExensioPreCheckResponse(lotsFound, lotsNotFound, rows, null);
    }

    /**
     * Calls the Exensio lot-wafer-lookup endpoint for a batch of lots.
     *
     * <p>Strategy: first tries with cleaned wafer IDs from discovery (matches
     * {@code w.wf_id} field). If nothing found, retries without wafer IDs at all
     * (API matches by lot alone, which is the most reliable path since the
     * lot-wafer-lookup API may use a different field than {@code wf_id}).</p>
     *
     * @param lotIds   list of lot IDs to check
     * @param waferIds optional list of wafer IDs (may be null or empty)
     * @param pgcKey   the program-class key
     * @param token    Bearer token for the schema
     * @param schema   schema name for logging
     * @return parsed response, or null on failure
     */
    private LotWaferLookupResponse callLotWaferLookup(
            List<String> lotIds, List<String> waferIds, int pgcKey,
            String token, String schema) {

        String url = exensioProperties.resolvedBaseUrl().replaceAll("/$", "") + "/v1/key/lot-wafer-lookup";
        boolean hasWafers = waferIds != null && !waferIds.isEmpty();

        // Attempt 1: try with wafer IDs (matches against w.wf_id field in Exensio)
        if (hasWafers) {
            LotWaferLookupResponse result = doLotWaferLookupCall(lotIds, waferIds, pgcKey, token, url, schema);
            if (result != null && !result.lotIds().isEmpty()) {
                return result;
            }
        }

        // Attempt 2: try without wafer IDs — the API matches by lot alone,
        // which avoids wafer-field mismatch issues entirely
        log.debug("[ExensioPreCheck] Retrying lot-wafer-lookup without wafer filter for schema {} ({} lots)",
                schema, lotIds.size());
        return doLotWaferLookupCall(lotIds, null, pgcKey, token, url, schema);
    }

    /**
     * Executes a single HTTP call to the lot-wafer-lookup endpoint.
     */
    private LotWaferLookupResponse doLotWaferLookupCall(
            List<String> lotIds, List<String> waferIds, int pgcKey,
            String token, String url, String schema) {

        com.fasterxml.jackson.databind.node.ObjectNode body = objectMapper.createObjectNode();
        body.put("pgc_key", pgcKey);

        com.fasterxml.jackson.databind.node.ArrayNode lotIdsNode = body.putArray("lot_ids");
        for (String lot : lotIds) {
            lotIdsNode.add(lot);
        }

        boolean hasWafers = waferIds != null && !waferIds.isEmpty();
        if (hasWafers) {
            com.fasterxml.jackson.databind.node.ArrayNode waferIdsNode = body.putArray("wafer_ids");
            for (String wafer : waferIds) {
                waferIdsNode.add(wafer);
            }
        }

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(60))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            log.debug("[ExensioPreCheck] lot-wafer-lookup response: HTTP {}, schema={}, waferFilter={}",
                    response.statusCode(), schema, hasWafers);

            if (response.statusCode() == 401) {
                authService.invalidateToken(schema);
                return null;
            }

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("[ExensioPreCheck] lot-wafer-lookup returned HTTP {} for schema {}: {}",
                        response.statusCode(), schema, response.body());
                return null;
            }

            return parseLotWaferLookupResponse(response.body());

        } catch (Exception e) {
            log.warn("[ExensioPreCheck] lot-wafer-lookup failed for schema {}: {}", schema, e.getMessage());
            return null;
        }
    }

    /**
     * Parses the lot-wafer-lookup JSON response.
     *
     * <p>Response format:
     * <pre>{ "lots": [ { "lot_key": 123, "lot_id": "LOT001", "wafers": [ { "wafer_id": "01", ... } ] } ] }</pre>
     *
     * @param jsonResponse the raw JSON response
     * @return parsed response with lot IDs and wafer IDs, or null on parse failure
     */
    private LotWaferLookupResponse parseLotWaferLookupResponse(String jsonResponse) {
        try {
            com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(jsonResponse);
            com.fasterxml.jackson.databind.JsonNode lotsNode = root.path("lots");

            List<String> lotIds = new ArrayList<>();
            List<LotWaferFoundRow> rows = new ArrayList<>();

            if (lotsNode.isArray()) {
                for (com.fasterxml.jackson.databind.JsonNode lotNode : lotsNode) {
                    String lotId = lotNode.path("lot_id").asText("");
                    if (lotId.isBlank()) {
                        lotId = lotNode.path("LOT_ID").asText("");
                    }
                    if (lotId.isBlank()) {
                        continue;
                    }

                    lotIds.add(lotId);

                    com.fasterxml.jackson.databind.JsonNode wafersNode = lotNode.path("wafers");
                    if (wafersNode.isArray() && !wafersNode.isEmpty()) {
                        for (com.fasterxml.jackson.databind.JsonNode waferNode : wafersNode) {
                            String rawWafer = waferNode.path("wafer_id").asText("");
                            String waferId = ExensioSqlUtilService.stripWaferPrefix(rawWafer);
                            rows.add(new LotWaferFoundRow(lotId, waferId));
                        }
                    } else {
                        // Lot found but no wafers (e.g., lot-level pgc_key)
                        rows.add(new LotWaferFoundRow(lotId, ""));
                    }
                }
            }

            log.debug("[ExensioPreCheck] Parsed lot-wafer-lookup response: {} lots, {} wafer rows",
                    lotIds.size(), rows.size());

            return new LotWaferLookupResponse(lotIds, rows);

        } catch (Exception e) {
            log.warn("[ExensioPreCheck] Failed to parse lot-wafer-lookup response: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Internal record for a row found via lot-wafer-lookup.
     */
    private record LotWaferFoundRow(String lotId, String waferId) {}

    /**
     * Internal record for lot-wafer-lookup response data.
     */
    private record LotWaferLookupResponse(List<String> lotIds, List<LotWaferFoundRow> rows) {}

    private static ExensioPreCheckResponse softError(String message) {
        return new ExensioPreCheckResponse(
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                message);
    }
}
