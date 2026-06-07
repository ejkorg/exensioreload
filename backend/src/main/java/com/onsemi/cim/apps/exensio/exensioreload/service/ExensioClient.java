package com.onsemi.cim.apps.exensio.exensioreload.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.onsemi.cim.apps.exensio.exensioreload.config.ExensioProperties;
import com.onsemi.cim.apps.exensio.exensioreload.dto.BatchLookupResult;
import com.onsemi.cim.apps.exensio.exensioreload.dto.BatchResult;
import com.onsemi.cim.apps.exensio.exensioreload.stage.StageRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

    @FunctionalInterface
    private interface RetryableOperation<T> {
        T execute() throws Exception;
    }

/**
 * HTTP client for the Exensio API.
 *
 * <p>Handles token refresh on 401 automatically (one retry). Uses the same
 * {@link HttpClient} bean as {@code ElasticsearchLogService} — no extra deps.</p>
 */
@Service
public class ExensioClient {

    private static final Logger log = LoggerFactory.getLogger(ExensioClient.class);

    private final ExensioProperties props;
    private final ExensioAuthService authService;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public ExensioClient(ExensioProperties props,
                         ExensioAuthService authService,
                         HttpClient elasticsearchHttpClient,
                         ObjectMapper objectMapper) {
        this.props = props;
        this.authService = authService;
        this.httpClient = elasticsearchHttpClient;
        this.objectMapper = objectMapper;
    }

    /**
     * Calls {@code POST /v1/key/lot-wafer-lookup} with the given lot and wafer IDs.
     *
     * <p>Request body:
     * <pre>{ "pgc_key": 1, "lot_ids": ["&lt;lot&gt;"], "wafer_ids": ["&lt;wafer&gt;"] }</pre>
     *
     * <p>Returns {@link ExensioLotWaferResult.Found} when the response contains at least
     * one wafer entry, {@link ExensioLotWaferResult.NotFound} when the lots array is
     * empty or absent, and {@link ExensioLotWaferResult.Error} on any HTTP or parse failure.
     */
    public ExensioLotWaferResult lotWaferLookup(String lot, String wafer) {
        return lotWaferLookup(lot, wafer, null, null);
    }

    /**
     * Single-record lot/wafer lookup with optional target end-time matching.
     * When wafer is null/blank, this uses pgc_key=2 and matches the best wafer by end_time.
     */
    public ExensioLotWaferResult lotWaferLookup(String lot, String wafer, Instant targetEndTime) {
        return lotWaferLookup(lot, wafer, targetEndTime, null);
    }

    /**
     * Single-record lot/wafer lookup with explicit pgc_key override.
     *
     * <p>When {@code pgcKey} is non-null it is used directly in the request body.
     * When {@code pgcKey} is null the existing wafer-presence fallback applies
     * ({@code pgc_key=1} if wafer is present, {@code pgc_key=2} if wafer is absent).</p>
     *
     * <p>Requirements: 4.1, 4.2, 6.1</p>
     */
    public ExensioLotWaferResult lotWaferLookup(String lot, String wafer, Instant targetEndTime, Integer pgcKey) {
        return lotWaferLookup(lot, wafer, targetEndTime, pgcKey, null);
    }

    /**
     * Single-record lot/wafer lookup with explicit pgc_key override and PPID test-phase validation.
     *
     * <p>When {@code testPhase} is non-blank, a {@link ExensioLotWaferResult.Found} result is
     * only returned when the PPID ends with {@code _<testPhase>} (case-insensitive). A mismatch
     * downgrades the result to {@link ExensioLotWaferResult.NotFound} so the monitor retries.</p>
     *
     * <p>Requirements: 4.1, 4.2, 5.1–5.5, 6.1, 6.2</p>
     */
    public ExensioLotWaferResult lotWaferLookup(String lot, String wafer, Instant targetEndTime,
                                                 Integer pgcKey, String testPhase) {
        return lotWaferLookup(lot, wafer, targetEndTime, pgcKey, testPhase, null, null, null);
    }

    /**
     * Helper to execute an operation with exponential backoff for transient failures.
     */
    private <T> T executeWithRetry(String opName, String traceId, RetryableOperation<T> op) throws Exception {
        int maxAttempts = props.getRetryMaxAttempts();
        long baseDelay = props.getRetryBaseDelayMs();
        Exception lastException = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                T result = op.execute();

                // Check if result is a "transient" error result (e.g. HTTP 429 or 5xx)
                if (result instanceof ExensioLotWaferResult.Error err && isTransientError(err.message())) {
                    throw new Exception(err.message());
                }
                if (result instanceof BatchLookupResult blr && !blr.isSuccess() && isTransientError(blr.getErrorMessage())) {
                    throw new Exception(blr.getErrorMessage());
                }

                return result;
            } catch (Exception e) {
                lastException = e;
                if (attempt < maxAttempts && isTransient(e)) {
                    long delay = baseDelay * (long) Math.pow(2, attempt - 1);
                    log.warn("{} failed (attempt {}/{}, traceId={}): {}. Retrying in {}ms...",
                             opName, attempt, maxAttempts, traceId, e.getMessage(), delay);
                    Thread.sleep(delay);
                } else {
                    break;
                }
            }
        }
        throw (lastException != null) ? lastException : new Exception("Operation failed after " + maxAttempts + " attempts");
    }

    private boolean isTransient(Exception e) {
        if (e instanceof java.io.IOException) return true;
        if (e.getMessage() != null && isTransientError(e.getMessage())) return true;
        return false;
    }

    private boolean isTransientError(String msg) {
        if (msg == null) return false;
        return msg.contains("HTTP 429") || msg.contains("HTTP 500") || msg.contains("HTTP 502") ||
               msg.contains("HTTP 503") || msg.contains("HTTP 504") || msg.contains("Timeout");
    }

    /**
     * Single-record lookup with optional filename/metadata identifiers used by raw-SQL matching.
     */
    public ExensioLotWaferResult lotWaferLookup(String lot, String wafer, Instant targetEndTime,
                                                 Integer pgcKey, String testPhase,
                                                 String filename, String metadataId, String dataId) {
        // Generate trace ID for this request
        String traceId = UUID.randomUUID().toString();
        log.info("Exensio lookup START: traceId={}, lot={}, wafer={}", traceId, lot, wafer);

        String token;
        try {
            token = authService.getToken();
        } catch (ExensioAuthService.ExensioAuthException e) {
            log.error("Exensio auth failed (traceId={}): {}", traceId, e.getMessage());
            return new ExensioLotWaferResult.Error("Auth failed: " + e.getMessage());
        }

        ExensioLotWaferResult result = doLotWaferLookup(
                lot, wafer, targetEndTime, pgcKey, testPhase,
                filename, metadataId, dataId, token, traceId);

        // Retry once on 401 with a fresh token
        if (result instanceof ExensioLotWaferResult.Error err && err.message().contains("HTTP 401")) {
            log.debug("Exensio 401 — invalidating token and retrying (traceId={})", traceId);
            authService.invalidateToken();
            try {
                token = authService.login();
            } catch (ExensioAuthService.ExensioAuthException e) {
                log.error("Exensio re-auth failed (traceId={}): {}", traceId, e.getMessage());
                return new ExensioLotWaferResult.Error("Re-auth failed: " + e.getMessage());
            }
            result = doLotWaferLookup(
                    lot, wafer, targetEndTime, pgcKey, testPhase,
                    filename, metadataId, dataId, token, traceId);
        }

        return result;
    }

    // --- private ---

    private ExensioLotWaferResult doLotWaferLookup(String lot, String wafer, Instant targetEndTime,
                                                    Integer pgcKey, String testPhase,
                                                    String filename, String metadataId, String dataId,
                                                    String token, String traceId) {
        try {
            boolean waferBlank = wafer == null || wafer.isBlank();
            // Use the explicit pgcKey when provided; otherwise fall back to wafer-presence logic.
            int resolvedPgcKey = (pgcKey != null) ? pgcKey : (waferBlank ? 2 : 1);

            ExensioLotWaferResult rawSqlResult = doRawSqlLookupSingle(
                    lot, wafer, targetEndTime, resolvedPgcKey, testPhase,
                    filename, metadataId, dataId, token, traceId);
            if (rawSqlResult instanceof ExensioLotWaferResult.Found) {
                return rawSqlResult;
            }

            String url = props.resolvedBaseUrl().replaceAll("/$", "") + "/v1/key/lot-wafer-lookup";

            ObjectNode body = objectMapper.createObjectNode();
            body.put("pgc_key", resolvedPgcKey);
            ArrayNode lotIds = body.putArray("lot_ids");
            lotIds.add(lot);
            ArrayNode waferIds = body.putArray("wafer_ids");
            if (!waferBlank) {
                waferIds.add(wafer);
            }

            if (props.isLogRequestPayloads()) {
                log.info("Exensio lot-wafer-lookup request (traceId={}): url={}, body={}", traceId, url, body.toString());
            }

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 401) {
                return new ExensioLotWaferResult.Error("HTTP 401");
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("Exensio API returned error (traceId={}): HTTP {}", traceId, response.statusCode());
                return new ExensioLotWaferResult.Error("HTTP " + response.statusCode());
            }

            return parseResponse(response.body(), wafer, targetEndTime, testPhase);

        } catch (Exception e) {
            log.warn("Exensio lot-wafer-lookup failed (traceId={}) for lot={} wafer={}: {}", traceId, lot, wafer, e.getMessage());
            return new ExensioLotWaferResult.Error(e.getMessage());
        }
    }

    /**
     * Batch lot-wafer lookup for multiple records with retry and exponential backoff for transient failures.
     *
     * <p>Request body:
     * <pre>{ "pgc_key": 1, "lot_ids": ["&lt;lot1&gt;", "&lt;lot2&gt;", ...], "wafer_ids": ["&lt;wafer1&gt;", "&lt;wafer2&gt;", ...] }</pre>
     *
     * <p>This method extracts unique lot and wafer IDs from the batch, builds the request body,
     * and executes a single HTTP POST request to the Exensio batch API endpoint.</p>
     *
     * <p>On success, returns a {@link BatchLookupResult} with the parsed response. On failure,
     * returns a {@link BatchLookupResult} with an error message.</p>
     *
     * @param records the batch of records to look up
     * @return BatchLookupResult with parsed response or error message
     */
    public BatchLookupResult lotWaferLookupBatch(List<StageRecord> records) {
        int maxAttempts = props.getRetryMaxAttempts();
        long baseDelay = props.getRetryBaseDelayMs();
        String token = null;
        boolean needToRefreshToken = true;
        BatchLookupResult lastResult = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            if (needToRefreshToken) {
                try {
                    token = authService.getToken();
                } catch (ExensioAuthService.ExensioAuthException e) {
                    // If we can't get a token, we break and return auth error.
                    return new BatchLookupResult("Auth failed: " + e.getMessage());
                }
                needToRefreshToken = false;
            }

            BatchLookupResult result = doLotWaferLookupBatch(records, token);
            lastResult = result;

            if (result.isSuccess()) {
                return result;
            }

            String errorMsg = result.getErrorMessage();
            if (errorMsg.contains("HTTP 401")) {
                needToRefreshToken = true; // So we get a new token next time
                // If we have more attempts, we continue immediately (without delay) to try again with new token.
                if (attempt < maxAttempts) {
                    log.debug("Batch lookup attempt {} got 401, refreshing token and retrying...", attempt);
                    continue;
                } else {
                    return result; // Return the 401 error after last attempt
                }
            } else if (isTransientError(errorMsg)) {
                if (attempt < maxAttempts) {
                    long delay = baseDelay * (long) Math.pow(2, attempt - 1);
                    log.warn("Batch lookup attempt {} failed with transient error: {}. Retrying in {}ms...",
                             attempt, errorMsg, delay);
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return new BatchLookupResult("Interrupted: " + e.getMessage());
                    }
                    // Note: we do not refresh token on transient error unless it's 401 (which we handled above)
                    continue;
                } else {
                    return result; // Return the transient error after last attempt
                }
            } else {
                // Non-transient error, return immediately
                return result;
            }
        }

        // If we exit the loop, return the last result (which should be an error)
        return lastResult;
    }



    /**
     * Batch lot-wafer lookup for multiple records (internal implementation).
     *
     * <p>Request body:
     * <pre>{ "pgc_key": 1, "lot_ids": ["&lt;lot1&gt;", "&lt;lot2&gt;", ...], "wafer_ids": ["&lt;wafer1&gt;", "&lt;wafer2&gt;", ...] }</pre>
     *
     * <p>This method extracts unique lot and wafer IDs from the batch, builds the request body,
     * and executes a single HTTP POST request to the Exensio batch API endpoint.</p>
     *
     * <p>On success, returns a {@link BatchLookupResult} with the parsed response. On failure,
     * returns a {@link BatchLookupResult} with an error message.</p>
     *
     * @param records the batch of records to look up
     * @param token the authentication token
     * @return BatchLookupResult with parsed response or error message
     */
    private BatchLookupResult doLotWaferLookupBatch(List<StageRecord> records, String token) {
        List<BatchLookupResult.LotResult> mergedLots = new ArrayList<>();
        Set<Long> resolvedRecordIds = new HashSet<>();

        BatchLookupResult rawSqlResult = doRawSqlLookupBatch(records, token);
        if (rawSqlResult.isSuccess()) {
            mergedLots.addAll(rawSqlResult.getLots());
            for (BatchResult.RecordUpdate update : rawSqlResult.mapToRecordUpdates(records)) {
                if (update.type() == BatchResult.UpdateType.DONE) {
                    resolvedRecordIds.add(update.recordId());
                }
            }
        } else {
            log.warn("Raw SQL batch lookup failed, falling back to lot-wafer endpoint: {}", rawSqlResult.getErrorMessage());
        }

        List<StageRecord> unresolvedRecords = records.stream()
                .filter(r -> !resolvedRecordIds.contains(r.id()))
                .toList();

        if (unresolvedRecords.isEmpty()) {
            return new BatchLookupResult(mergedLots);
        }

        BatchLookupResult fallbackResult = doLotWaferLookupBatchEndpoint(unresolvedRecords, token);
        if (!fallbackResult.isSuccess()) {
            if (!mergedLots.isEmpty()) {
                return new BatchLookupResult(mergedLots);
            }
            return fallbackResult;
        }

        mergedLots.addAll(fallbackResult.getLots());
        return new BatchLookupResult(mergedLots);
    }

    private BatchLookupResult doLotWaferLookupBatchEndpoint(List<StageRecord> records, String token) {
        long startTime = System.currentTimeMillis();
        int batchSize = records.size();

        try {
            String url = props.resolvedBaseUrl().replaceAll("/$", "") + "/v1/key/lot-wafer-lookup";

            Set<String> uniqueLots = new HashSet<>();
            Set<String> uniqueWafers = new HashSet<>();
            for (StageRecord record : records) {
                uniqueLots.add(record.lot());
                uniqueWafers.add(record.wafer());
            }

            ObjectNode body = objectMapper.createObjectNode();
            Map<Integer, Long> pgcKeyCounts = new HashMap<>();
            for (StageRecord record : records) {
                boolean waferBlank = record.wafer() == null || record.wafer().isBlank();
                int pgcKey = DataTypePgcKeyMapper.resolve(record.dataType(), waferBlank);
                pgcKeyCounts.merge(pgcKey, 1L, Long::sum);
            }
            int batchPgcKey = pgcKeyCounts.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .orElse(1);
            body.put("pgc_key", batchPgcKey);
            ArrayNode lotIds = body.putArray("lot_ids");
            for (String lot : uniqueLots) {
                lotIds.add(lot);
            }
            ArrayNode waferIds = body.putArray("wafer_ids");
            for (String wafer : uniqueWafers) {
                if (wafer != null && !wafer.isBlank()) {
                    waferIds.add(wafer);
                }
            }

            if (props.isLogRequestPayloads()) {
                log.info("Exensio batch lot-wafer-lookup request: url={}, body={}", url, body);
            }

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            long responseTimeMs = System.currentTimeMillis() - startTime;

            log.debug("Batch API call completed: batchSize={}, uniqueLots={}, uniqueWafers={}, responseTimeMs={}, statusCode={}",
                    batchSize, uniqueLots.size(), uniqueWafers.size(), responseTimeMs, response.statusCode());

            if (response.statusCode() == 401) {
                return new BatchLookupResult("HTTP 401");
            }
            if (response.statusCode() == 429) {
                return new BatchLookupResult("HTTP 429 (rate limited)");
            }
            if (response.statusCode() >= 500) {
                return new BatchLookupResult("HTTP " + response.statusCode());
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return new BatchLookupResult("HTTP " + response.statusCode());
            }

            return BatchLookupResult.parse(response.body(), objectMapper);

        } catch (Exception e) {
            long responseTimeMs = System.currentTimeMillis() - startTime;
            log.warn("Exensio batch lot-wafer-lookup failed after {}ms: {}", responseTimeMs, e.getMessage());
            return new BatchLookupResult("Error: " + e.getMessage());
        }
    }

    private ExensioLotWaferResult doRawSqlLookupSingle(String lot,
                                                       String wafer,
                                                       Instant targetEndTime,
                                                       int pgcKey,
                                                       String testPhase,
                                                       String filename,
                                                       String metadataId,
                                                       String dataId,
                                                       String token,
                                                       String traceId) {
        try {
            Set<String> identifiers = buildIdentifierTokens(filename, metadataId, dataId);
            if (identifiers.isEmpty()) {
                return new ExensioLotWaferResult.NotFound();
            }

            String sql = buildSingleRawSql(lot, wafer, pgcKey, identifiers);
            JsonNode rows = executeRawSql(sql, token, traceId);
            if (rows == null || !rows.isArray() || rows.isEmpty()) {
                String fallbackSchema = props.resolvedDbschemaFallback();
                if (fallbackSchema != null && !fallbackSchema.isBlank()) {
                    String fallbackToken = authService.loginWithSchema(fallbackSchema);
                    rows = executeRawSql(sql, fallbackToken, traceId);
                }
            }
            if (rows == null || !rows.isArray() || rows.isEmpty()) {
                return new ExensioLotWaferResult.NotFound();
            }

            JsonNode best = selectBestRawRow(rows, targetEndTime, identifiers);
            if (best == null) {
                return new ExensioLotWaferResult.NotFound();
            }

            long lotKey = getLong(best, "LOT_KEY");
            long waferKey = getLong(best, "WAFER_KEY");
            long pgKey = getLong(best, "PG_KEY");
            String ppid = getText(best, "PPID");
            String waferId = getText(best, "WAFER_ID");
            String lotIdStr = getText(best, "LOT_ID");
            String fileNameStr = getText(best, "FILE_NAME");

            if (waferKey <= 0) {
                return new ExensioLotWaferResult.NotFound();
            }

            ExensioLotWaferResult candidate = new ExensioLotWaferResult.Found(lotKey, waferKey, pgKey, ppid, lotIdStr, waferId, fileNameStr);
            return applyPpidCheck(candidate, ppid, testPhase, lot, waferId);
        } catch (Exception e) {
            log.warn("Raw SQL lookup failed (traceId={}) for lot={} wafer={}: {}", traceId, lot, wafer, e.getMessage());
            return new ExensioLotWaferResult.Error("Raw SQL error: " + e.getMessage());
        }
    }

    private BatchLookupResult doRawSqlLookupBatch(List<StageRecord> records, String token, String traceId) {
        try {
            List<String> clauses = new ArrayList<>();
            List<StageRecord> indexedRecords = new ArrayList<>();
            for (StageRecord record : records) {
                Set<String> identifiers = buildIdentifierTokens(record.filename(), record.metadataId(), record.dataId());
                if (identifiers.isEmpty() || record.lot() == null || record.lot().isBlank()) {
                    continue;
                }

                boolean waferBlank = isBlankOrNa(record.wafer());
                int pgcKey = DataTypePgcKeyMapper.resolve(record.dataType(), waferBlank);

                StringBuilder clause = new StringBuilder();
                clause.append("(ol.pgc_key = ").append(pgcKey)
                        .append(" AND UPPER(TRIM(l.lot_id)) = UPPER(TRIM('")
                        .append(escapeSqlLiteral(record.lot()))
                        .append("'))");
                if (!waferBlank) {
                    clause.append(" AND UPPER(TRIM(NVL(w.wf_id,''))) = UPPER(TRIM('")
                            .append(escapeSqlLiteral(record.wafer()))
                            .append("'))");
                }
                clause.append(" AND ").append(buildIdentifierLikeClause("de.file_name", identifiers)).append(")");
                clauses.add(clause.toString());
                indexedRecords.add(record);
            }

            if (clauses.isEmpty()) {
                return new BatchLookupResult(Collections.emptyList());
            }

            String sql = buildBatchRawSql(clauses);
            JsonNode rows = executeRawSql(sql, token, traceId);
            if (rows == null || !rows.isArray() || rows.isEmpty()) {
                String fallbackSchema = props.resolvedDbschemaFallback();
                if (fallbackSchema != null && !fallbackSchema.isBlank()) {
                    String fallbackToken = authService.loginWithSchema(fallbackSchema);
                    rows = executeRawSql(sql, fallbackToken, traceId);
                }
            }
            if (rows == null || !rows.isArray() || rows.isEmpty()) {
                return new BatchLookupResult(Collections.emptyList());
            }

            Map<String, List<BatchLookupResult.LotResult.WaferResult>> byLot = new HashMap<>();
            Map<String, Long> lotKeys = new HashMap<>();

            for (JsonNode row : rows) {
                long waferKey = getLong(row, "WAFER_KEY");
                if (waferKey <= 0) continue;

                String lotId = safeUpper(getText(row, "LOT_ID"));
                if (lotId == null || lotId.isBlank()) continue;

                String waferId = getText(row, "WAFER_ID");
                long pgKey = getLong(row, "PG_KEY");
                String ppid = getText(row, "PPID");
                Instant endTime = parseInstantSafe(getText(row, "END_TIME"));
                long lotKey = getLong(row, "LOT_KEY");
                String fileName = getText(row, "FILE_NAME");

                byLot.computeIfAbsent(lotId, k -> new ArrayList<>())
                        .add(new BatchLookupResult.LotResult.WaferResult(waferId, waferKey, pgKey, ppid, endTime, fileName));
                if (lotKey > 0) {
                    lotKeys.putIfAbsent(lotId, lotKey);
                }
            }

            List<BatchLookupResult.LotResult> lots = new ArrayList<>();
            for (Map.Entry<String, List<BatchLookupResult.LotResult.WaferResult>> e : byLot.entrySet()) {
                lots.add(new BatchLookupResult.LotResult(e.getKey(), lotKeys.getOrDefault(e.getKey(), 0L), e.getValue()));
            }

            return new BatchLookupResult(lots);
        } catch (Exception e) {
            return new BatchLookupResult("Raw SQL batch error: " + e.getMessage());
        }
    }

    private String buildSingleRawSql(String lot, String wafer, int pgcKey, Set<String> identifiers) {
        StringBuilder where = new StringBuilder();
        where.append("ol.pgc_key = ").append(pgcKey)
                .append(" AND UPPER(TRIM(l.lot_id)) = UPPER(TRIM('")
                .append(escapeSqlLiteral(lot))
                .append("'))");

        if (!isBlankOrNa(wafer)) {
            where.append(" AND UPPER(TRIM(NVL(w.wf_id,''))) = UPPER(TRIM('")
                    .append(escapeSqlLiteral(wafer))
                    .append("'))");
        }

        where.append(" AND ").append(buildIdentifierLikeClause("de.file_name", identifiers));

        return "SELECT * FROM (" +
                " SELECT l.lot_id AS lot_id, NVL(w.wf_id,'') AS wafer_id," +
                " ol.lot_key AS lot_key, NVL(w.wf_key,0) AS wafer_key," +
                " NVL(ol.pg_key,0) AS pg_key, NVL(p.ppid,'') AS ppid," +
                " NVL(de.file_name,'') AS file_name," +
                " NVL(TO_CHAR(ol.end_time, 'YYYY-MM-DD" + '"' + "T" + '"' + "HH24:MI:SS" + '"' + "Z" + '"' + "'),'') AS end_time" +
                " FROM op_log ol" +
                " JOIN lot l ON l.lot_key = ol.lot_key" +
                " JOIN program p ON p.pg_key = ol.pg_key" +
                " LEFT JOIN wafer w ON w.wf_key = ol.wf_key" +
                " LEFT JOIN df_export de ON de.lg_key = ol.lg_key AND (w.wf_key IS NULL OR de.wf_key = w.wf_key)" +
                " WHERE " + where +
                " ORDER BY ol.end_time DESC" +
                ") WHERE ROWNUM <= " + props.getRawSqlRowLimit();
    }

    private String buildBatchRawSql(List<String> clauses) {
        String where = String.join(" OR ", clauses);
        return "SELECT * FROM (" +
                " SELECT l.lot_id AS lot_id, NVL(w.wf_id,'') AS wafer_id," +
                " ol.lot_key AS lot_key, NVL(w.wf_key,0) AS wafer_key," +
                " NVL(ol.pg_key,0) AS pg_key, NVL(p.ppid,'') AS ppid," +
                " NVL(de.file_name,'') AS file_name," +
                " NVL(TO_CHAR(ol.end_time, 'YYYY-MM-DD" + '"' + "T" + '"' + "HH24:MI:SS" + '"' + "Z" + '"' + "'),'') AS end_time" +
                " FROM op_log ol" +
                " JOIN lot l ON l.lot_key = ol.lot_key" +
                " JOIN program p ON p.pg_key = ol.pg_key" +
                " LEFT JOIN wafer w ON w.wf_key = ol.wf_key" +
                " LEFT JOIN df_export de ON de.lg_key = ol.lg_key AND (w.wf_key IS NULL OR de.wf_key = w.wf_key)" +
                " WHERE (" + where + ")" +
                " ORDER BY ol.end_time DESC" +
                ") WHERE ROWNUM <= " + props.getRawSqlRowLimit();
    }



    private JsonNode executeRawSql(String sql, String token, String traceId) throws Exception {
        String url = props.resolvedBaseUrl().replaceAll("/$", "") + "/v1/key/raw-sql";
        ObjectNode body = objectMapper.createObjectNode();
        body.put("sql", sql);
        long startTime = System.currentTimeMillis();

        log.info("Exensio raw-sql START: url={}, traceId={}", url, traceId);
        log.info("Exensio raw-sql SQL (traceId={}):\n{}", traceId, sql);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(props.getRawSqlTimeoutSeconds()))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        long elapsed = System.currentTimeMillis() - startTime;

        if (response.statusCode() == 401) {
            log.warn("Exensio raw-sql FAILED (HTTP 401): elapsed={}ms, traceId={}", elapsed, traceId);
            throw new IllegalStateException("HTTP 401");
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            log.warn("Exensio raw-sql FAILED (HTTP {}): elapsed={}ms, traceId={}, response={}",
                response.statusCode(), elapsed, traceId, response.body());
            throw new IllegalStateException("HTTP " + response.statusCode());
        }

        log.info("Exensio raw-sql SUCCESS (HTTP {} in {}ms, traceId={})", response.statusCode(), elapsed, traceId);
        if (props.isLogRequestPayloads()) {
            log.info("Exensio raw-sql response (traceId={}):\n{}", traceId, response.body());
        }

        JsonNode root = objectMapper.readTree(response.body());
        if (root.isArray()) {
            log.info("Exensio raw-sql result: {} rows returned (traceId={})", root.size(), traceId);
            return root;
        }
        if (root.has("rows") && root.get("rows").isArray()) {
            JsonNode rows = root.get("rows");
            log.info("Exensio raw-sql result: {} rows returned (traceId={})", rows.size(), traceId);
            return rows;
        }
        log.info("Exensio raw-sql result: empty response (traceId={})", traceId);
        return objectMapper.createArrayNode();
    }

    private JsonNode selectBestRawRow(JsonNode rows, Instant targetEndTime, Set<String> identifiers) {
        JsonNode best = null;
        long bestDelta = Long.MAX_VALUE;
        int bestScore = -1;

        for (JsonNode row : rows) {
            long waferKey = getLong(row, "WAFER_KEY");
            if (waferKey <= 0) continue;

            String fileName = getText(row, "FILE_NAME");
            int score = identifierMatchScore(fileName, identifiers);
            Instant end = parseInstantSafe(getText(row, "END_TIME"));
            long delta = targetEndTime != null && end != null
                    ? Math.abs(Duration.between(targetEndTime, end).getSeconds())
                    : 0L;

            if (best == null || score > bestScore || (score == bestScore && delta < bestDelta)) {
                best = row;
                bestScore = score;
                bestDelta = delta;
            }
        }

        return best;
    }

    private int identifierMatchScore(String fileName, Set<String> identifiers) {
        if (fileName == null || fileName.isBlank() || identifiers.isEmpty()) return 0;
        String upper = fileName.toUpperCase(Locale.ROOT);
        int score = 0;
        for (String id : identifiers) {
            if (upper.contains(id.toUpperCase(Locale.ROOT))) {
                score++;
            }
        }
        return score;
    }

    private Set<String> buildIdentifierTokens(String filename, String metadataId, String dataId) {
        Set<String> ids = new LinkedHashSet<>();
        if (metadataId != null && !metadataId.isBlank()) {
            ids.add(metadataId.trim());
        }
        if (dataId != null && !dataId.isBlank()) {
            ids.add(dataId.trim());
        }
        if (filename != null && !filename.isBlank()) {
            String name = filename.trim();
            ids.add(name);
            int dot = name.lastIndexOf('.');
            if (dot > 0) {
                ids.add(name.substring(0, dot));
            }
        }
        ids.removeIf(v -> v == null || v.isBlank());
        return ids;
    }

    private String buildIdentifierLikeClause(String column, Set<String> identifiers) {
        List<String> parts = new ArrayList<>();
        for (String id : identifiers) {
            parts.add("UPPER(NVL(" + column + ",'')) LIKE '%" + escapeLikeLiteral(id.toUpperCase(Locale.ROOT)) + "%' ESCAPE '\\\\'");
        }
        if (parts.isEmpty()) {
            return "1=0";
        }
        return "(" + String.join(" OR ", parts) + ")";
    }

    private boolean isBlankOrNa(String value) {
        return value == null || value.isBlank() || "NA".equalsIgnoreCase(value.trim());
    }

    private String escapeSqlLiteral(String value) {
        return value == null ? "" : value.replace("'", "''");
    }

    private String escapeLikeLiteral(String value) {
        if (value == null) return "";
        return value
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_")
                .replace("'", "''");
    }

    private String getText(JsonNode node, String field) {
        JsonNode v = getFieldNode(node, field);
        if (v == null || v.isNull()) return null;
        String text = v.asText();
        return text == null || text.isBlank() ? null : text;
    }

    private long getLong(JsonNode node, String field) {
        JsonNode v = getFieldNode(node, field);
        if (v == null || v.isNull()) return 0L;
        if (v.isNumber()) return v.asLong();
        try {
            return Long.parseLong(v.asText());
        } catch (Exception e) {
            return 0L;
        }
    }

    private JsonNode getFieldNode(JsonNode node, String field) {
        if (node == null || field == null) return null;
        JsonNode direct = node.get(field);
        if (direct != null) return direct;
        JsonNode upper = node.get(field.toUpperCase(Locale.ROOT));
        if (upper != null) return upper;
        return node.get(field.toLowerCase(Locale.ROOT));
    }

    private String safeUpper(String value) {
        return value == null ? null : value.toUpperCase(Locale.ROOT);
    }

    /**
     * Parses the lot-wafer-lookup response, applying PPID suffix validation when a
     * {@code testPhase} is provided.
     *
     * <p>Response shape (from Python reference):
     * <pre>
     * {
     *   "lots": [{
     *     "lot_key": 2776623,
     *     "wafers": [{
     *       "wafer_id": "KG01HK4X_06",
     *       "wafer_key": 4633046,
     *       "pg_key": 12345,
     *       "ppid": "WS::CM8012X_..."
     *     }]
     *   }]
     * }
     * </pre>
     *
     * <p>Requirements: 5.1–5.5, 6.2 — when {@code testPhase} is non-blank and the
     * candidate PPID does not end with {@code _<testPhase>} (case-insensitive), the
     * result is downgraded to {@link ExensioLotWaferResult.NotFound} so the monitor
     * retries on the next cycle.</p>
     */
    private ExensioLotWaferResult parseResponse(String body, String targetWaferId,
                                                 Instant targetEndTime, String testPhase) {
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode lots = root.path("lots");

            if (!lots.isArray() || lots.isEmpty()) {
                return new ExensioLotWaferResult.NotFound();
            }

            JsonNode bestWaferNode = null;
            long bestLotKey = 0;
            String bestLotId = null;
            long bestDeltaSeconds = Long.MAX_VALUE;

            for (JsonNode lotNode : lots) {
                long lotKey = lotNode.path("lot_key").asLong(0);
                String lotIdStr = lotNode.path("lot_id").asText(null);
                JsonNode wafers = lotNode.path("wafers");
                if (!wafers.isArray()) continue;

                for (JsonNode waferNode : wafers) {
                    String waferId = waferNode.path("wafer_id").asText(null);
                    // Match by wafer_id if provided; otherwise use end_time proximity / first available.
                    if (targetWaferId != null && !targetWaferId.isBlank()
                            && !targetWaferId.equalsIgnoreCase(waferId)) {
                        continue;
                    }

                    if (targetEndTime == null) {
                        long waferKey = waferNode.path("wafer_key").asLong(0);
                        long pgKey = waferNode.path("pg_key").asLong(0);
                        String ppid = waferNode.path("ppid").asText(null);
                        if (waferKey > 0) {
                            ExensioLotWaferResult candidate =
                                    new ExensioLotWaferResult.Found(lotKey, waferKey, pgKey, ppid, lotIdStr, waferId, null);
                            return applyPpidCheck(candidate, ppid, testPhase, targetWaferId, waferId);
                        }
                        continue;
                    }

                    Instant exEnd = parseInstantSafe(waferNode.path("end_time").asText(null));
                    long delta = exEnd == null ? Long.MAX_VALUE : Math.abs(Duration.between(targetEndTime, exEnd).getSeconds());
                    if (bestWaferNode == null || delta < bestDeltaSeconds) {
                        bestWaferNode = waferNode;
                        bestLotKey = lotKey;
                        bestLotId = lotIdStr;
                        bestDeltaSeconds = delta;
                    }
                }
            }

            if (bestWaferNode != null) {
                long waferKey = bestWaferNode.path("wafer_key").asLong(0);
                long pgKey = bestWaferNode.path("pg_key").asLong(0);
                String ppid = bestWaferNode.path("ppid").asText(null);
                if (waferKey > 0) {
                    String finalWaferId = bestWaferNode.path("wafer_id").asText(null);
                    ExensioLotWaferResult candidate =
                            new ExensioLotWaferResult.Found(bestLotKey, waferKey, pgKey, ppid, bestLotId, finalWaferId, null);
                    return applyPpidCheck(candidate, ppid, testPhase, targetWaferId, finalWaferId);
                }
            }

            return new ExensioLotWaferResult.NotFound();

        } catch (Exception e) {
            log.warn("Failed to parse Exensio lot-wafer-lookup response: {}", e.getMessage());
            return new ExensioLotWaferResult.Error("Parse error: " + e.getMessage());
        }
    }

    /**
     * Applies the PPID suffix check to a candidate {@link ExensioLotWaferResult.Found} result.
     *
     * <p>When the check fails the result is downgraded to {@link ExensioLotWaferResult.NotFound}
     * and a DEBUG message is logged (Requirements: 5.5).</p>
     */
    private ExensioLotWaferResult applyPpidCheck(ExensioLotWaferResult candidate,
                                                  String ppid, String testPhase,
                                                  String lot, String wafer) {
        if (candidate instanceof ExensioLotWaferResult.Found) {
            if (!ppidMatchesTestPhase(ppid, testPhase)) {
                log.debug("PPID suffix mismatch — downgrading Found to NotFound: " +
                                "lot={}, wafer={}, expectedTestPhase={}, actualPpid={}",
                        lot, wafer, testPhase, ppid);
                return new ExensioLotWaferResult.NotFound();
            }
        }
        return candidate;
    }

    /**
     * Returns {@code true} when the PPID is consistent with the expected test phase.
     *
     * <p>Four cases (Requirements: 5.1–5.4, 6.2):
     * <ol>
     *   <li>testPhase is null or blank → accept (no check needed)</li>
     *   <li>ppid is null or blank → accept (cannot validate, treat as pass)</li>
     *   <li>ppid ends with {@code _<testPhase>} (case-insensitive) → accept</li>
     *   <li>otherwise → reject (caller should downgrade to NotFound)</li>
     * </ol>
     */
    // Feature: exensio-pgc-key-matching, Property 5: PPID suffix validation correctly gates Found results
    boolean ppidMatchesTestPhase(String ppid, String testPhase) {
        // Case 1: no test phase specified — skip check
        if (testPhase == null || testPhase.isBlank()) return true;
        // Case 2: PPID absent — cannot validate, accept
        if (ppid == null || ppid.isBlank()) return true;
        // Case 3 / 4: compare suffix case-insensitively
        return ppid.toUpperCase().endsWith("_" + testPhase.trim().toUpperCase());
    }

    private Instant parseInstantSafe(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Instant.parse(value);
        } catch (Exception ignored) {
            return null;
        }
    }
}
