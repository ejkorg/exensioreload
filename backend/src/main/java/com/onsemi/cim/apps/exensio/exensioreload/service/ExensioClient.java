package com.onsemi.cim.apps.exensio.exensioreload.service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
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
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.onsemi.cim.apps.exensio.exensioreload.config.ExensioProperties;
import com.onsemi.cim.apps.exensio.exensioreload.dto.BatchLookupResult;
import com.onsemi.cim.apps.exensio.exensioreload.dto.BatchResult;
import com.onsemi.cim.apps.exensio.exensioreload.stage.StageRecord;

/**
 * HTTP client for the Exensio API.
 *
 * <p>Handles token refresh on 401 automatically (one retry). Uses the same
 * {@link HttpClient} bean as {@code ElasticsearchLogService} — no extra deps.</p>
 */
@Service
public class ExensioClient {

    private static final Logger log = LoggerFactory.getLogger(ExensioClient.class);
    private static final DateTimeFormatter SQL_TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneOffset.UTC);

    private final ExensioProperties props;
    private final ExensioAuthService authService;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    @FunctionalInterface
    private interface RetryableOperation<T> {
        T execute() throws Exception;
    }

    public ExensioClient(ExensioProperties props,
                         ExensioAuthService authService,
                         @org.springframework.beans.factory.annotation.Qualifier("exensioHttpClient") HttpClient exensioHttpClient,
                         ObjectMapper objectMapper) {
        this.props = props;
        this.authService = authService;
        this.httpClient = exensioHttpClient;
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
            token = authService.getToken(props.resolvedDbschema());
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
            authService.invalidateToken(props.resolvedDbschema());
            try {
                token = authService.login(props.resolvedDbschema());
            } catch (ExensioAuthService.ExensioAuthException e) {
                log.error("Exensio re-auth failed (traceId={}): {}", traceId, e.getMessage());
                return new ExensioLotWaferResult.Error("Re-auth failed: " + e.getMessage());
            }
            result = doLotWaferLookup(
                    lot, wafer, targetEndTime, pgcKey, testPhase,
                    filename, metadataId, dataId, token, traceId);
        }

        if (result instanceof ExensioLotWaferResult.Found found) {
            boolean waferBlank = wafer == null || wafer.isBlank();
            int resolvedPgcKey = (pgcKey != null) ? pgcKey : (waferBlank ? 2 : 1);
            result = verifyAndEnrich(found, resolvedPgcKey, traceId);
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

            // Step 1: Try raw-SQL only if preferRawSql is enabled
            if (props.isPreferRawSql()) {
                ExensioLotWaferResult rawSqlResult = doRawSqlLookupSingle(
                        lot, wafer, targetEndTime, resolvedPgcKey, testPhase,
                        filename, metadataId, dataId, token, traceId);
                if (rawSqlResult instanceof ExensioLotWaferResult.Found) {
                    return rawSqlResult;
                }
            }

            // Step 2: Try lot-wafer-lookup endpoint with primary schema
            ExensioLotWaferResult primaryResult = doLotWaferLookupForSchema(
                    lot, wafer, resolvedPgcKey, testPhase, targetEndTime, token, traceId);

            if (primaryResult instanceof ExensioLotWaferResult.Found) {
                return primaryResult;
            }

            // Step 3: If primary schema returned NotFound, try fallback schema (SANDBOX)
            String fallbackSchema = props.resolvedDbschemaFallback();
            if (fallbackSchema != null && !fallbackSchema.isBlank()) {
                log.info("Lot-wafer lookup primary schema returned NotFound — auto-switching to fallback schema {} (traceId={})",
                        fallbackSchema, traceId);
                try {
                    String fallbackToken = authService.login(fallbackSchema);
                    ExensioLotWaferResult fallbackResult = doLotWaferLookupForSchema(
                            lot, wafer, resolvedPgcKey, testPhase, targetEndTime, fallbackToken, traceId);
                    if (fallbackResult instanceof ExensioLotWaferResult.Found) {
                        return fallbackResult;
                    }
                    log.debug("Lot-wafer lookup fallback schema {} also returned NotFound (traceId={})",
                            fallbackSchema, traceId);
                } catch (Exception e) {
                    log.debug("Lot-wafer lookup fallback schema {} failed: {} (traceId={})",
                            fallbackSchema, e.getMessage(), traceId);
                }
            }

            return primaryResult; // Return the result from primary (most likely NotFound or Error)

        } catch (Exception e) {
            log.warn("Exensio lot-wafer-lookup failed (traceId={}) for lot={} wafer={}: {}", traceId, lot, wafer, e.getMessage());
            return new ExensioLotWaferResult.Error(e.getMessage());
        }
    }

    /**
     * Helper: calls the lot-wafer-lookup POST endpoint with a specific schema token.
     */
    private ExensioLotWaferResult doLotWaferLookupForSchema(
            String lot, String wafer, int resolvedPgcKey, String testPhase,
            Instant targetEndTime, String token, String traceId) {

        String url = props.resolvedBaseUrl().replaceAll("/$", "") + "/v1/key/lot-wafer-lookup";
        boolean waferBlank = wafer == null || wafer.isBlank();

        ObjectNode body = objectMapper.createObjectNode();
        body.put("pgc_key", resolvedPgcKey);
        ArrayNode lotIds = body.putArray("lot_ids");
        lotIds.add(lot);
        ArrayNode waferIds = body.putArray("wafer_ids");

        if (!waferBlank) {
            Set<String> uniqueWaferVariants = new LinkedHashSet<>();
            uniqueWaferVariants.add(wafer);
            String cleanWafer = ExensioSqlUtilService.stripWaferPrefix(wafer);
            if (!cleanWafer.isBlank()) {
                uniqueWaferVariants.add(cleanWafer);
            }
            Integer wNum = extractWaferNum(wafer);
            if (wNum != null) {
                uniqueWaferVariants.add(String.valueOf(wNum));
                if (wNum < 10 && wNum >= 0) {
                    uniqueWaferVariants.add(String.format("%02d", wNum));
                }
            }
            // Add composite variants: lot + "_" + wafer, lot + "-" + wafer
            // Exensio Defect (Class 14) and other loaders often store wafer IDs as <Lot>_<Wafer>
            if (lot != null && !lot.isBlank()) {
                uniqueWaferVariants.add(lot + "_" + wafer);
                uniqueWaferVariants.add(lot + "-" + wafer);
                if (!cleanWafer.isBlank()) {
                    uniqueWaferVariants.add(lot + "_" + cleanWafer);
                    uniqueWaferVariants.add(lot + "-" + cleanWafer);
                }
                int dotIdx = lot.indexOf('.');
                int dashIdx = lot.indexOf('-');
                int cut = dotIdx > 0 ? dotIdx : dashIdx;
                if (cut > 0) {
                    String baseLot = lot.substring(0, cut);
                    uniqueWaferVariants.add(baseLot + "_" + wafer);
                    uniqueWaferVariants.add(baseLot + "-" + wafer);
                    if (!cleanWafer.isBlank()) {
                        uniqueWaferVariants.add(baseLot + "_" + cleanWafer);
                        uniqueWaferVariants.add(baseLot + "-" + cleanWafer);
                    }
                }
            }
            for (String v : uniqueWaferVariants) {
                waferIds.add(v);
            }
        }

        ExensioLotWaferResult result = executeLotWaferLookupHttp(url, body, token, wafer, targetEndTime, testPhase, traceId);
        if (result instanceof ExensioLotWaferResult.Found) {
            return result;
        }

        // Attempt 2: If wafer was specified and returned NotFound, retry without wafer_ids filter!
        // The Exensio API matches by lot alone, which avoids wafer-field mismatch issues
        // (especially for Defect data where wafers are indexed by lot or under inspection steps).
        if (!waferBlank && (result instanceof ExensioLotWaferResult.NotFound)) {
            ObjectNode bodyWithoutWafers = body.deepCopy();
            bodyWithoutWafers.remove("wafer_ids");
            if (props.isLogRequestPayloads()) {
                log.info("Exensio lot-wafer-lookup retry without wafer_ids (traceId={}): url={}, body={}",
                        traceId, url, bodyWithoutWafers.toString());
            }
            ExensioLotWaferResult retryResult = executeLotWaferLookupHttp(url, bodyWithoutWafers, token, wafer, targetEndTime, testPhase, traceId);
            if (retryResult instanceof ExensioLotWaferResult.Found) {
                log.info("Lot-wafer lookup matched on retry without wafer_ids filter: lot={}, wafer={} (traceId={})",
                        lot, wafer, traceId);
                return retryResult;
            }
        }

        return result;
    }

    private ExensioLotWaferResult executeLotWaferLookupHttp(
            String url, ObjectNode body, String token, String wafer,
            Instant targetEndTime, String testPhase, String traceId) {

        if (props.isLogRequestPayloads()) {
            log.info("Exensio lot-wafer-lookup request (traceId={}): url={}, body={}", traceId, url, body.toString());
        }

        try {
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
                log.warn("Exensio lot-wafer-lookup returned error (traceId={}): HTTP {}", traceId, response.statusCode());
                return new ExensioLotWaferResult.Error("HTTP " + response.statusCode());
            }

            return parseResponse(response.body(), wafer, targetEndTime, testPhase);

        } catch (Exception e) {
            log.warn("Exensio lot-wafer-lookup request failed (traceId={}): {}", traceId, e.getMessage());
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
        return lotWaferLookupBatch(records, null);
    }

    public BatchLookupResult lotWaferLookupBatch(List<StageRecord> records, String traceId) {
        int maxAttempts = props.getRetryMaxAttempts();
        long baseDelay = props.getRetryBaseDelayMs();
        String token = null;
        boolean needToRefreshToken = true;
        BatchLookupResult lastResult = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            if (needToRefreshToken) {
                try {
                    token = authService.getToken(props.resolvedDbschema());
                } catch (ExensioAuthService.ExensioAuthException e) {
                    // If we can't get a token, we break and return auth error.
                    return new BatchLookupResult("Auth failed: " + e.getMessage());
                }
                needToRefreshToken = false;
            }

            BatchLookupResult result = doLotWaferLookupBatch(records, token, traceId);
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
        return doLotWaferLookupBatch(records, token, null);
    }

    private BatchLookupResult doLotWaferLookupBatch(List<StageRecord> records, String token, String traceId) {
        List<BatchLookupResult.LotResult> mergedLots = new ArrayList<>();
        Set<Long> resolvedRecordIds = new HashSet<>();

        // Step 1: Try raw-SQL batch only if preferRawSql is enabled
        if (props.isPreferRawSql()) {
            BatchLookupResult rawSqlResult = doRawSqlLookupBatch(records, token, traceId);
            if (rawSqlResult.isSuccess()) {
                mergedLots.addAll(rawSqlResult.getLots());
                for (BatchResult.RecordUpdate update : rawSqlResult.mapToRecordUpdates(records, traceId)) {
                    if (update.type() == BatchResult.UpdateType.COMPLETED) {
                        resolvedRecordIds.add(update.recordId());
                    }
                }
            } else {
                log.warn("Raw SQL batch lookup failed (traceId={}), falling back to lot-wafer endpoint: {}", traceId, rawSqlResult.getErrorMessage());
            }
        }

        List<StageRecord> unresolvedRecords = records.stream()
                .filter(r -> !resolvedRecordIds.contains(r.id()))
                .toList();

        if (unresolvedRecords.isEmpty()) {
            return new BatchLookupResult(mergedLots);
        }

        // Step 2: Try lot-wafer-lookup batch endpoint with primary schema
        BatchLookupResult primaryResult = doLotWaferLookupBatchEndpoint(unresolvedRecords, token, true);
        if (primaryResult.isSuccess() && !primaryResult.getLots().isEmpty()) {
            mergedLots.addAll(primaryResult.getLots());
            return new BatchLookupResult(mergedLots);
        }

        // Retry without wafer filter on primary schema if wafer filter yielded empty
        BatchLookupResult primaryLotOnly = doLotWaferLookupBatchEndpoint(unresolvedRecords, token, false);
        if (primaryLotOnly.isSuccess() && !primaryLotOnly.getLots().isEmpty()) {
            log.info("Batch lot-wafer-lookup primary schema matched without wafer filter ({} lots)",
                    primaryLotOnly.getLots().size());
            mergedLots.addAll(primaryLotOnly.getLots());
            return new BatchLookupResult(mergedLots);
        }

        // Step 3: Auto-switch to fallback schema (SANDBOX) if primary returned empty
        String fallbackSchema = props.resolvedDbschemaFallback();
        if (fallbackSchema != null && !fallbackSchema.isBlank()) {
            log.info("Batch lot-wafer-lookup primary schema returned empty — auto-switching to fallback schema {} ({} records)",
                    fallbackSchema, unresolvedRecords.size());
            try {
                String fallbackToken = authService.login(fallbackSchema);
                BatchLookupResult fallbackResult = doLotWaferLookupBatchEndpoint(unresolvedRecords, fallbackToken, true);
                if (fallbackResult.isSuccess() && !fallbackResult.getLots().isEmpty()) {
                    log.debug("Batch lot-wafer-lookup fallback schema {} found results", fallbackSchema);
                    mergedLots.addAll(fallbackResult.getLots());
                    return new BatchLookupResult(mergedLots);
                }
                BatchLookupResult fallbackLotOnly = doLotWaferLookupBatchEndpoint(unresolvedRecords, fallbackToken, false);
                if (fallbackLotOnly.isSuccess() && !fallbackLotOnly.getLots().isEmpty()) {
                    log.debug("Batch lot-wafer-lookup fallback schema {} found results without wafer filter", fallbackSchema);
                    mergedLots.addAll(fallbackLotOnly.getLots());
                    return new BatchLookupResult(mergedLots);
                }
                log.debug("Batch lot-wafer-lookup fallback schema {} also returned empty", fallbackSchema);
            } catch (Exception e) {
                log.debug("Batch lot-wafer-lookup fallback schema {} failed: {}", fallbackSchema, e.getMessage());
            }
        }

        // All batch endpoints exhausted
        if (!mergedLots.isEmpty()) {
            return new BatchLookupResult(mergedLots);
        }
        return primaryResult; // Return primary result (most informative error)
    }

    private BatchLookupResult doLotWaferLookupBatchEndpoint(List<StageRecord> records, String token) {
        return doLotWaferLookupBatchEndpoint(records, token, true);
    }

    private BatchLookupResult doLotWaferLookupBatchEndpoint(List<StageRecord> records, String token, boolean includeWafers) {
        long startTime = System.currentTimeMillis();
        int batchSize = records.size();

        try {
            String url = props.resolvedBaseUrl().replaceAll("/$", "") + "/v1/key/lot-wafer-lookup";

            Set<String> uniqueLots = new HashSet<>();
            for (StageRecord record : records) {
                if (record.lot() != null && !record.lot().isBlank()) {
                    uniqueLots.add(record.lot());
                }
            }

            ObjectNode body = objectMapper.createObjectNode();
            Map<Integer, Long> pgcKeyCounts = new HashMap<>();
            for (StageRecord record : records) {
                int pgcKey = DataTypePgcKeyMapper.resolve(record.dataType());
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

            if (includeWafers) {
                ArrayNode waferIds = body.putArray("wafer_ids");
                Set<String> uniqueWaferVariants = new LinkedHashSet<>();
                for (StageRecord record : records) {
                    String wafer = record.wafer();
                    String lot = record.lot();
                    if (wafer != null && !wafer.isBlank()) {
                        uniqueWaferVariants.add(wafer);
                        String clean = ExensioSqlUtilService.stripWaferPrefix(wafer);
                        if (!clean.isBlank()) {
                            uniqueWaferVariants.add(clean);
                            uniqueWaferVariants.add(ExensioPreCheckService.zeroPadWaferId(clean));
                        }
                        if (lot != null && !lot.isBlank()) {
                            uniqueWaferVariants.add(lot + "_" + wafer);
                            uniqueWaferVariants.add(lot + "-" + wafer);
                            if (!clean.isBlank()) {
                                uniqueWaferVariants.add(lot + "_" + clean);
                                uniqueWaferVariants.add(lot + "-" + clean);
                            }
                            int dot = lot.indexOf('.');
                            int dash = lot.indexOf('-');
                            int cut = dot > 0 ? dot : dash;
                            if (cut > 0) {
                                String baseLot = lot.substring(0, cut);
                                uniqueWaferVariants.add(baseLot + "_" + wafer);
                                uniqueWaferVariants.add(baseLot + "-" + wafer);
                            }
                        }
                    }
                }
                for (String wv : uniqueWaferVariants) {
                    waferIds.add(wv);
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
            long overallStartTime = System.currentTimeMillis();
            Set<String> identifiers = buildIdentifierTokens(filename, metadataId, dataId);
            if (identifiers.isEmpty()) {
                return new ExensioLotWaferResult.NotFound();
            }

            // Step 1: Try primary query with lot_id filter
            long primaryStartTime = System.currentTimeMillis();
            log.info("Primary lot_id query START (traceId={}): lot={}, wafer={}, pgcKey={}, targetEndTime={}", 
                     traceId, lot, wafer, pgcKey, targetEndTime);
            
            String sql = buildSingleRawSql(lot, wafer, pgcKey, identifiers, "PRODUCTION");
            JsonNode rows = executeRawSql(sql, token, traceId);
            if (rows == null || !rows.isArray() || rows.isEmpty()) {
                String fallbackSchema = props.resolvedDbschemaFallback();
                if (fallbackSchema != null && !fallbackSchema.isBlank()) {
                    String fallbackToken = authService.login(fallbackSchema);
                    String sandboxSql = buildSingleRawSql(lot, wafer, pgcKey, identifiers, "SANDBOX");
                    rows = executeRawSql(sandboxSql, fallbackToken, traceId);
                }
            }
            
            long primaryElapsed = System.currentTimeMillis() - primaryStartTime;
            
            if (rows != null && rows.isArray() && !rows.isEmpty()) {
                log.info("Primary lot_id query SUCCESS (traceId={}): {} rows returned in {}ms", 
                         traceId, rows.size(), primaryElapsed);
                
                // Step 3: Primary query returned results, use identifier-based selection
                JsonNode best = selectBestRawRow(rows, targetEndTime, identifiers);
                if (best == null) {
                    log.warn("Primary query returned rows but best row selection failed (traceId={})", traceId);
                    return new ExensioLotWaferResult.NotFound();
                }

                long lotKey = getLong(best, "LOT_KEY");
                long waferKey = getLong(best, "WAFER_KEY");
                long pgKey = getLong(best, "PG_KEY");
                String ppid = getText(best, "PPID");
                String waferId = ExensioSqlUtilService.stripWaferPrefix(getText(best, "WAFER_ID"));
                String lotIdStr = getText(best, "LOT_ID");
                String fileNameStr = getText(best, "FILE_NAME");
                String schema = getText(best, "SCHEMA_NAME");

                if (waferKey <= 0) {
                    return new ExensioLotWaferResult.NotFound();
                }

                long totalElapsed = System.currentTimeMillis() - overallStartTime;
                log.info("Primary query SELECTED RECORD (traceId={}): lotId={}, waferId={}, lotKey={}, waferKey={}, pgKey={}, " +
                         "ppid={}, fileName={}, schema={}, totalElapsed={}ms",
                         traceId, lotIdStr, waferId, lotKey, waferKey, pgKey, ppid, fileNameStr, schema, totalElapsed);

                ExensioLotWaferResult candidate = new ExensioLotWaferResult.Found(lotKey, waferKey, pgKey, ppid, lotIdStr, waferId, fileNameStr, schema);
                return applyPpidCheck(candidate, ppid, testPhase, lot, waferId);
            }

            // Step 2: If primary query returned empty, attempt fallback query without lot_id
            log.info("Primary lot_id query returned empty ({}ms), attempting fallback (traceId={})", primaryElapsed, traceId);
            
            int timeWindowHours = props.getFallbackQueryTimeWindowHours();
            String fallbackSql = buildFallbackRawSql(pgcKey, wafer, identifiers, targetEndTime, timeWindowHours, "PRODUCTION");
            
            long fallbackStartTime = System.currentTimeMillis();
            log.debug("Fallback query execution START (traceId={}): timeWindowHours={}", traceId, timeWindowHours);
            log.debug("Fallback SQL (traceId={}):\n{}", traceId, fallbackSql);
            
            rows = executeRawSql(fallbackSql, token, traceId);
            if (rows == null || !rows.isArray() || rows.isEmpty()) {
                String fallbackSchema = props.resolvedDbschemaFallback();
                if (fallbackSchema != null && !fallbackSchema.isBlank()) {
                    String fallbackToken = authService.login(fallbackSchema);
                    String sandboxFallbackSql = buildFallbackRawSql(pgcKey, wafer, identifiers, targetEndTime, timeWindowHours, "SANDBOX");
                    rows = executeRawSql(sandboxFallbackSql, fallbackToken, traceId);
                }
            }
            
            long fallbackElapsed = System.currentTimeMillis() - fallbackStartTime;
            
            if (rows == null || !rows.isArray() || rows.isEmpty()) {
                log.warn("Fallback query also returned empty ({}ms) (traceId={})", fallbackElapsed, traceId);
                return new ExensioLotWaferResult.NotFound();
            }
            
            log.info("Fallback query SUCCESS (traceId={}): {} rows returned in {}ms", 
                     traceId, rows.size(), fallbackElapsed);
            
            // For fallback results, use timestamp-based selection instead of identifier scoring
            JsonNode best = selectBestRecordByTimestamp(rows, targetEndTime);
            if (best == null) {
                log.warn("Fallback query returned rows but best record selection failed (traceId={})", traceId);
                return new ExensioLotWaferResult.NotFound();
            }
            
            long lotKey = getLong(best, "LOT_KEY");
            long waferKey = getLong(best, "WAFER_KEY");
            long pgKey = getLong(best, "PG_KEY");
            String ppid = getText(best, "PPID");
            String waferId = ExensioSqlUtilService.stripWaferPrefix(getText(best, "WAFER_ID"));
            String lotIdStr = getText(best, "LOT_ID");
            String fileNameStr = getText(best, "FILE_NAME");
            String schema = getText(best, "SCHEMA_NAME");
            String insertTime = getText(best, "INSERT_TIME");
            String endTime = getText(best, "END_TIME");

            if (waferKey <= 0) {
                return new ExensioLotWaferResult.NotFound();
            }

            long totalElapsed = System.currentTimeMillis() - overallStartTime;
            log.info("Fallback query SELECTED RECORD (traceId={}): lotId={}, waferId={}, lotKey={}, waferKey={}, pgKey={}, " +
                     "ppid={}, fileName={}, schema={}, insertTime={}, endTime={}, primaryTime={}ms, fallbackTime={}ms, totalTime={}ms",
                     traceId, lotIdStr, waferId, lotKey, waferKey, pgKey, ppid, fileNameStr, schema, insertTime, endTime, 
                     primaryElapsed, fallbackElapsed, totalElapsed);

            ExensioLotWaferResult candidate = new ExensioLotWaferResult.Found(lotKey, waferKey, pgKey, ppid, lotIdStr, waferId, fileNameStr, schema);
            return applyPpidCheck(candidate, ppid, testPhase, lot, waferId);
        } catch (Exception e) {
            log.warn("Raw SQL lookup failed (traceId={}) for lot={} wafer={}: {}", traceId, lot, wafer, e.getMessage());
            return new ExensioLotWaferResult.Error("Raw SQL error: " + e.getMessage());
        }
    }

    private BatchLookupResult doRawSqlLookupBatch(List<StageRecord> records, String token, String traceId) {
        try {
            // Step 1: Build and execute primary batch query with lot_id filters
            List<String> primaryClauses = new ArrayList<>();
            List<StageRecord> indexedRecords = new ArrayList<>();
            Map<String, StageRecord> lotToRecord = new HashMap<>();
            
            for (StageRecord record : records) {
                if (record.lot() == null || record.lot().isBlank()) {
                    continue;
                }

                boolean waferBlank = isBlankOrNa(record.wafer());
                int pgcKey = ExensioPreCheckService.resolvePgcKey(record.dataType());
                Set<String> identifiers = buildIdentifierTokens(record.filename(), record.metadataId(), record.dataId());

                String cleanLot = record.lot().trim();
                List<String> candidates = getLotCandidates(cleanLot);
                StringBuilder clause = new StringBuilder();
                clause.append("(ol.pgc_key = ").append(pgcKey)
                        .append(" AND ").append(buildLotMatchClause(cleanLot));

                if (!waferBlank) {
                    clause.append(buildWaferMatchClause(record.wafer()));
                }

                if (waferBlank && !identifiers.isEmpty()) {
                    clause.append(" AND (de.file_name IS NULL OR ")
                            .append(buildIdentifierLikeClause("de.file_name", identifiers))
                            .append(")");
                }

                clause.append(")");
                primaryClauses.add(clause.toString());
                indexedRecords.add(record);
                lotToRecord.put(cleanLot.toUpperCase(Locale.ROOT), record);
                for (String cand : candidates) {
                    lotToRecord.putIfAbsent(cand.toUpperCase(Locale.ROOT), record);
                }
                String prefix = extractLotPrefix(cleanLot);
                if (prefix != null && !prefix.isBlank()) {
                    lotToRecord.putIfAbsent(prefix.toUpperCase(Locale.ROOT), record);
                }
            }

            if (primaryClauses.isEmpty()) {
                return new BatchLookupResult(Collections.emptyList());
            }

            // Execute primary query
            String primarySql = buildBatchRawSql(primaryClauses, "PRODUCTION");
            JsonNode primaryRows = executeRawSql(primarySql, token, traceId);
            if (primaryRows == null || !primaryRows.isArray() || primaryRows.isEmpty()) {
                String fallbackSchema = props.resolvedDbschemaFallback();
                if (fallbackSchema != null && !fallbackSchema.isBlank()) {
                    String fallbackToken = authService.login(fallbackSchema);
                    String sandboxPrimarySql = buildBatchRawSql(primaryClauses, "SANDBOX");
                    primaryRows = executeRawSql(sandboxPrimarySql, fallbackToken, traceId);
                }
            }

            // Step 2: Extract lots that were resolved by primary query
            Map<String, List<BatchLookupResult.LotResult.WaferResult>> byLot = new HashMap<>();
            Map<String, Long> lotKeys = new HashMap<>();
            Set<String> resolvedLots = new HashSet<>();

            if (primaryRows != null && primaryRows.isArray() && !primaryRows.isEmpty()) {
                for (JsonNode row : primaryRows) {
                    long waferKey = getLong(row, "WAFER_KEY");
                    if (waferKey <= 0) continue;

                    String lotId = safeUpper(getText(row, "LOT_ID"));
                    if (lotId == null || lotId.isBlank()) continue;

                    resolvedLots.add(lotId);
                    String waferId = ExensioSqlUtilService.stripWaferPrefix(getText(row, "WAFER_ID"));
                    long pgKey = getLong(row, "PG_KEY");
                    String ppid = getText(row, "PPID");
                    Instant endTime = parseInstantSafe(getText(row, "END_TIME"));
                    long lotKey = getLong(row, "LOT_KEY");
                    String fileName = getText(row, "FILE_NAME");
                    String schema = getText(row, "SCHEMA_NAME");

                    byLot.computeIfAbsent(lotId, k -> new ArrayList<>())
                            .add(new BatchLookupResult.LotResult.WaferResult(waferId, waferKey, pgKey, ppid, endTime, fileName, schema));
                    if (lotKey > 0) {
                        lotKeys.putIfAbsent(lotId, lotKey);
                    }
                }
            }

            // Step 3: For unresolved lots, attempt fallback queries
            List<StageRecord> unresolvedRecords = indexedRecords.stream()
                    .filter(r -> {
                        for (String cand : getLotCandidates(r.lot())) {
                            if (resolvedLots.contains(cand.toUpperCase(Locale.ROOT))) {
                                return false;
                            }
                        }
                        String prefix = extractLotPrefix(r.lot());
                        if (prefix != null && !prefix.isBlank()) {
                            String upperPrefix = prefix.toUpperCase(Locale.ROOT);
                            for (String res : resolvedLots) {
                                if (res.startsWith(upperPrefix)) {
                                    return false;
                                }
                            }
                        }
                        return true;
                    })
                    .toList();

            if (!unresolvedRecords.isEmpty()) {
                long fallbackBatchStartTime = System.currentTimeMillis();
                log.info("Primary batch query resolved {}/{} lots, attempting fallback for {} unresolved records (traceId={})",
                        resolvedLots.size(), indexedRecords.size(), unresolvedRecords.size(), traceId);

                int timeWindowHours = props.getFallbackQueryTimeWindowHours();
                
                // Build fallback clauses for unresolved records
                List<String> fallbackClauses = new ArrayList<>();
                for (StageRecord record : unresolvedRecords) {
                    boolean waferBlank = isBlankOrNa(record.wafer());
                    int pgcKey = ExensioPreCheckService.resolvePgcKey(record.dataType());
                    Set<String> identifiers = buildIdentifierTokens(record.filename(), record.metadataId(), record.dataId());

                    StringBuilder clause = new StringBuilder();
                    clause.append("(ol.pgc_key = ").append(pgcKey);

                    // Add wafer matching clause if wafer is provided
                    if (!waferBlank) {
                        clause.append(buildWaferMatchClause(record.wafer()));
                    }

                    // Add file identifier matching if wafer is blank and identifiers are provided
                    if (waferBlank && !identifiers.isEmpty()) {
                        clause.append(" AND (de.file_name IS NULL OR ")
                                .append(buildIdentifierLikeClause("de.file_name", identifiers))
                                .append(")");
                    }

                    clause.append(")");
                    fallbackClauses.add(clause.toString());
                }

                if (!fallbackClauses.isEmpty()) {
                    String fallbackSql = buildBatchRawSql(fallbackClauses, "PRODUCTION");
                    log.debug("Batch fallback SQL execution (traceId={}): {} clauses, timeWindowHours={}", 
                              traceId, fallbackClauses.size(), timeWindowHours);
                    
                    JsonNode fallbackRows = executeRawSql(fallbackSql, token, traceId);
                    if (fallbackRows == null || !fallbackRows.isArray() || fallbackRows.isEmpty()) {
                        String fallbackSchema = props.resolvedDbschemaFallback();
                        if (fallbackSchema != null && !fallbackSchema.isBlank()) {
                            String fallbackToken = authService.login(fallbackSchema);
                            String sandboxFbSql = buildBatchRawSql(fallbackClauses, "SANDBOX");
                            fallbackRows = executeRawSql(sandboxFbSql, fallbackToken, traceId);
                        }
                    }

                    // Process fallback results
                    if (fallbackRows != null && fallbackRows.isArray() && !fallbackRows.isEmpty()) {
                        int fallbackRowCount = 0;
                        for (JsonNode row : fallbackRows) {
                            long waferKey = getLong(row, "WAFER_KEY");
                            if (waferKey <= 0) continue;

                            String lotId = safeUpper(getText(row, "LOT_ID"));
                            if (lotId == null || lotId.isBlank()) continue;

                            // Only add fallback results for lots that weren't resolved by primary query
                            if (!resolvedLots.contains(lotId)) {
                                String waferId = ExensioSqlUtilService.stripWaferPrefix(getText(row, "WAFER_ID"));
                                long pgKey = getLong(row, "PG_KEY");
                                String ppid = getText(row, "PPID");
                                Instant endTime = parseInstantSafe(getText(row, "END_TIME"));
                                long lotKey = getLong(row, "LOT_KEY");
                                String fileName = getText(row, "FILE_NAME");
                                String schema = getText(row, "SCHEMA_NAME");

                                byLot.computeIfAbsent(lotId, k -> new ArrayList<>())
                                        .add(new BatchLookupResult.LotResult.WaferResult(waferId, waferKey, pgKey, ppid, endTime, fileName, schema));
                                if (lotKey > 0) {
                                    lotKeys.putIfAbsent(lotId, lotKey);
                                }
                                fallbackRowCount++;
                            }
                        }
                        long fallbackBatchElapsed = System.currentTimeMillis() - fallbackBatchStartTime;
                        log.info("Fallback batch query SUCCESS (traceId={}): resolved {} additional lots from {} rows in {}ms", 
                                 traceId, fallbackRowCount, fallbackRows.size(), fallbackBatchElapsed);
                    } else {
                        long fallbackBatchElapsed = System.currentTimeMillis() - fallbackBatchStartTime;
                        log.info("Fallback batch query returned empty for {} unresolved records in {}ms (traceId={})", 
                                 unresolvedRecords.size(), fallbackBatchElapsed, traceId);
                    }
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

    public record ExensioLoadError(
            String lotId,
            String waferId,
            String programName,
            String fileName,
            int errorCode,
            String fullErrorMessage,
            String errorTime
    ) {}

    /**
     * Queries Exensio raw-sql endpoint across PRODUCTION and SANDBOX schemas
     * to fetch raw data load errors from DP_LOG, ERROR_MESSAGE, and STRING_HOLDER
     * for specified lot IDs.
     *
     * <p>Convenience overload for callers that only have lot IDs (no filename/dataId context).
     * Records not found via OP_LOG will not receive a filename-based fallback query.
     */
    public Map<String, ExensioLoadError> queryRawDataLoadErrors(List<String> lotIds, String traceId) {
        if (lotIds == null || lotIds.isEmpty()) {
            return Collections.emptyMap();
        }
        // Build a minimal map with no record context so the overload can still run the primary query
        Map<String, StageRecord> lotToRecord = new HashMap<>();
        for (String lot : lotIds) {
            if (lot != null && !lot.isBlank()) {
                lotToRecord.put(lot, null); // null record = no fallback context available
            }
        }
        return queryRawDataLoadErrors(lotToRecord, traceId);
    }

    /**
     * Queries Exensio raw-sql endpoint across PRODUCTION and SANDBOX schemas
     * to fetch raw data load errors from DP_LOG, ERROR_MESSAGE, and STRING_HOLDER.
     *
     * <p>Strategy:
     * <ol>
     *   <li><b>Primary (OP_LOG path)</b>: Queries DP_LOG joined through OP_LOG → LOT.
     *       This finds errors for lots that were at least partially processed.</li>
     *   <li><b>Fallback (RAW_FILE path)</b>: For any lot still unresolved after the primary
     *       query, queries DP_LOG directly joined to RAW_FILE on {@code rawfile_key},
     *       filtering by {@code file_name} or {@code data_id}. This captures early-loader
     *       rejections where the file was rejected before OP_LOG / LOT entries were written.</li>
     * </ol>
     *
     * @param lotToRecord map from lot ID to its {@link StageRecord} (value may be {@code null}
     *                    when filename/dataId context is unavailable — fallback will be skipped
     *                    for those lots)
     * @param traceId     correlation ID for logging
     * @return map from upper-cased lot ID to its most recent load error
     */
    public Map<String, ExensioLoadError> queryRawDataLoadErrors(Map<String, StageRecord> lotToRecord, String traceId) {
        if (lotToRecord == null || lotToRecord.isEmpty()) {
            return Collections.emptyMap();
        }

        List<String> cleanLots = lotToRecord.keySet().stream()
                .filter(l -> l != null && !l.isBlank())
                .distinct()
                .toList();

        if (cleanLots.isEmpty()) {
            return Collections.emptyMap();
        }

        // -----------------------------------------------------------------
        // Build schemas list (PRODUCTION first, then optional SANDBOX)
        // -----------------------------------------------------------------
        String primarySchema = props.resolvedDbschema();
        String fallbackSchema = props.resolvedDbschemaFallback();
        List<String> schemas = new ArrayList<>();
        if (primarySchema != null && !primarySchema.isBlank()) schemas.add(primarySchema);
        if (fallbackSchema != null && !fallbackSchema.isBlank() && !schemas.contains(fallbackSchema)) schemas.add(fallbackSchema);
        if (schemas.isEmpty()) schemas.add("PRODUCTION");

        Map<String, ExensioLoadError> errorMap = new HashMap<>();

        // -----------------------------------------------------------------
        // PRIMARY QUERY: DP_LOG via OP_LOG → LOT
        // -----------------------------------------------------------------
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT l.lot_id, NVL(w.wf_id, '') AS wafer_id, NVL(p.ppid, '') AS program_name, ");
        sql.append("NVL(rf.file_name, '') AS file_name, dl.error_code, ");
        sql.append("COALESCE(sh1.str_value, '') || COALESCE(sh2.str_value, '') || COALESCE(sh3.str_value, '') || COALESCE(sh4.str_value, '') AS full_error_message, ");
        sql.append("TO_CHAR(dl.start_time, 'YYYY-MM-DD\"T\"HH24:MI:SS.FF3\"Z\"') AS error_time ");
        sql.append("FROM op_log ol ");
        sql.append("JOIN lot l ON l.lot_key = ol.lot_key ");
        sql.append("JOIN program p ON p.pg_key = ol.pg_key ");
        sql.append("LEFT JOIN wf_log wl ON wl.lg_key = ol.lg_key ");
        sql.append("LEFT JOIN wafer w ON w.wf_key = wl.wf_key ");
        sql.append("JOIN dp_log dl ON dl.start_time = ol.insert_time ");
        sql.append("LEFT JOIN raw_file rf ON rf.rawfile_key = dl.rawfile_key ");
        sql.append("JOIN error_message em ON em.msg_key = dl.msg_key ");
        sql.append("LEFT JOIN string_holder sh1 ON sh1.str_key = em.str_key1 ");
        sql.append("LEFT JOIN string_holder sh2 ON sh2.str_key = em.str_key2 ");
        sql.append("LEFT JOIN string_holder sh3 ON sh3.str_key = em.str_key3 ");
        sql.append("LEFT JOIN string_holder sh4 ON sh4.str_key = em.str_key4 ");
        sql.append("WHERE dl.error_code != 0 AND l.lot_id IN (");

        List<String> lotLiterals = new ArrayList<>();
        for (String lot : cleanLots) {
            String trimmed = lot.trim();
            lotLiterals.add("'" + escapeSqlLiteral(trimmed.toUpperCase(Locale.ROOT)) + "'");
            String lower = trimmed.toLowerCase(Locale.ROOT);
            if (!lower.equals(trimmed.toUpperCase(Locale.ROOT))) {
                lotLiterals.add("'" + escapeSqlLiteral(lower) + "'");
            }
        }
        sql.append(String.join(", ", lotLiterals));
        sql.append(") ORDER BY l.lot_id, dl.start_time DESC");

        for (String schema : schemas) {
            try {
                String token = authService.getToken(schema);
                JsonNode rows = executeRawSql(sql.toString(), token, traceId);
                if (rows != null && rows.isArray() && !rows.isEmpty()) {
                    for (JsonNode row : rows) {
                        String lotId = safeUpper(getText(row, "LOT_ID"));
                        if (lotId != null && !lotId.isBlank() && !errorMap.containsKey(lotId)) {
                            errorMap.put(lotId, new ExensioLoadError(
                                    lotId,
                                    ExensioSqlUtilService.stripWaferPrefix(getText(row, "WAFER_ID")),
                                    getText(row, "PROGRAM_NAME"),
                                    getText(row, "FILE_NAME"),
                                    getInt(row, "ERROR_CODE", -1),
                                    getText(row, "FULL_ERROR_MESSAGE"),
                                    getText(row, "ERROR_TIME")
                            ));
                        }
                    }
                    if (!errorMap.isEmpty()) {
                        log.info("[ExensioLoadError] Primary query found {} error(s) in schema {} (traceId={})",
                                errorMap.size(), schema, traceId);
                        break;
                    }
                }
            } catch (Exception ex) {
                log.warn("[ExensioLoadError] Primary query failed in schema {} (traceId={}): {}",
                        schema, traceId, ex.getMessage());
            }
        }

        // -----------------------------------------------------------------
        // FALLBACK QUERY: DP_LOG → RAW_FILE (filename / dataId based)
        // Used when early-loader rejection happens before OP_LOG/LOT exists.
        // Only executed for lots that primary query didn't resolve.
        // -----------------------------------------------------------------
        List<StageRecord> unresolvedRecords = new ArrayList<>();
        for (Map.Entry<String, StageRecord> entry : lotToRecord.entrySet()) {
            String lotUpper = entry.getKey() == null ? null : entry.getKey().toUpperCase(Locale.ROOT);
            if (lotUpper != null && !errorMap.containsKey(lotUpper) && entry.getValue() != null) {
                StageRecord rec = entry.getValue();
                boolean hasFileName = rec.filename() != null && !rec.filename().isBlank();
                boolean hasDataId = rec.dataId() != null && !rec.dataId().isBlank();
                if (hasFileName || hasDataId) {
                    unresolvedRecords.add(rec);
                }
            }
        }

        if (!unresolvedRecords.isEmpty()) {
            log.debug("[ExensioLoadError] {} lot(s) unresolved after primary query; attempting RAW_FILE fallback (traceId={})",
                    unresolvedRecords.size(), traceId);
            Map<String, ExensioLoadError> fallbackErrors = queryRawDataLoadErrorsByFile(unresolvedRecords, traceId, schemas);
            if (!fallbackErrors.isEmpty()) {
                log.info("[ExensioLoadError] RAW_FILE fallback found {} error(s) (traceId={})",
                        fallbackErrors.size(), traceId);
                errorMap.putAll(fallbackErrors);
            }
        }

        return errorMap;
    }

    /**
     * Fallback error query: joins DP_LOG directly to RAW_FILE without going through
     * OP_LOG/LOT.  This catches early-loader rejections where Exensio rejects the file
     * before any LOT record is written.
     *
     * <p>The WHERE clause matches on {@code UPPER(rf.file_name) LIKE '%<basename>%'}
     * using the original filename.
     *
     * <p>Results are keyed by upper-cased lot ID derived from the matched StageRecord (since
     * the LOT table may be absent; the lot is taken from our own record metadata).
     */
    private Map<String, ExensioLoadError> queryRawDataLoadErrorsByFile(
            List<StageRecord> records, String traceId, List<String> schemas) {

        Map<String, ExensioLoadError> result = new HashMap<>();
        int timeoutMinutes = props.getTimeoutMinutes();

        for (StageRecord rec : records) {
            String fileNameFilter = rec.filename() != null && !rec.filename().isBlank()
                    ? rec.filename().trim()
                    : (rec.dataId() != null ? rec.dataId().trim() : null);
            if (fileNameFilter == null || fileNameFilter.isBlank()) {
                continue;
            }

            // Match on basename (strip path separators that don't belong in SQL)
            String baseName = fileNameFilter.contains("/")
                    ? fileNameFilter.substring(fileNameFilter.lastIndexOf('/') + 1)
                    : fileNameFilter.contains("\\")
                            ? fileNameFilter.substring(fileNameFilter.lastIndexOf('\\') + 1)
                            : fileNameFilter;

            if (baseName.isBlank()) {
                continue;
            }

            // Use first 35 characters for regex matching to reduce false positives from file reloads
            String filePrefix = baseName.length() > 35 ? baseName.substring(0, 35) : baseName;
            String escapedFilePrefix = escapeSqlLiteral(escapeRegexLiteral(filePrefix.toUpperCase(Locale.ROOT)));

            // Calculate time window: 20 minutes before createdAt to timeoutMinutes after now
            // This ensures we only match the current loading instance, not previous reloads
            Instant windowStart = rec.createdAt().minus(Duration.ofMinutes(20));
            Instant windowEnd = Instant.now().plus(Duration.ofMinutes(timeoutMinutes));
            java.sql.Timestamp sqlWindowStart = java.sql.Timestamp.from(windowStart);
            java.sql.Timestamp sqlWindowEnd = java.sql.Timestamp.from(windowEnd);

            StringBuilder rawDataSql = new StringBuilder();
            rawDataSql.append("SELECT NVL(rf.file_name, '') AS file_name, dl.error_code, ");
            rawDataSql.append("COALESCE(sh1.str_value, '') || COALESCE(sh2.str_value, '') || ");
            rawDataSql.append("COALESCE(sh3.str_value, '') || COALESCE(sh4.str_value, '') AS full_error_message, ");
            rawDataSql.append("TO_CHAR(dl.start_time, 'YYYY-MM-DD\"T\"HH24:MI:SS.FF3\"Z\"') AS error_time ");
            rawDataSql.append("FROM dp_log dl ");
            rawDataSql.append("JOIN raw_file rf ON rf.rawfile_key = dl.rawfile_key ");
            rawDataSql.append("JOIN error_message em ON em.msg_key = dl.msg_key ");
            rawDataSql.append("LEFT JOIN string_holder sh1 ON sh1.str_key = em.str_key1 ");
            rawDataSql.append("LEFT JOIN string_holder sh2 ON sh2.str_key = em.str_key2 ");
            rawDataSql.append("LEFT JOIN string_holder sh3 ON sh3.str_key = em.str_key3 ");
            rawDataSql.append("LEFT JOIN string_holder sh4 ON sh4.str_key = em.str_key4 ");
            rawDataSql.append("WHERE dl.error_code != 0 ");
            rawDataSql.append("AND REGEXP_LIKE(UPPER(rf.file_name), '^").append(escapedFilePrefix).append(".*') ");
            rawDataSql.append("AND dl.insert_time >= TO_TIMESTAMP('").append(sqlWindowStart).append("', 'YYYY-MM-DD HH24:MI:SS.FF') ");
            rawDataSql.append("AND dl.insert_time <= TO_TIMESTAMP('").append(sqlWindowEnd).append("', 'YYYY-MM-DD HH24:MI:SS.FF') ");
            rawDataSql.append("ORDER BY dl.insert_time DESC FETCH FIRST 1 ROW ONLY");

            String lotKey = rec.lot() != null ? rec.lot().toUpperCase(Locale.ROOT) : ("ID_" + rec.id());

            for (String schema : schemas) {
                try {
                    String token = authService.getToken(schema);
                    JsonNode rows = executeRawSql(rawDataSql.toString(), token, traceId);
                    if (rows != null && rows.isArray() && !rows.isEmpty()) {
                        JsonNode row = rows.get(0); // most recent error only
                        result.put(lotKey, new ExensioLoadError(
                                rec.lot() != null ? rec.lot() : "",
                                rec.wafer() != null ? rec.wafer() : "",
                                "", // program name unavailable without OP_LOG
                                getText(row, "FILE_NAME"),
                                getInt(row, "ERROR_CODE", -1),
                                getText(row, "FULL_ERROR_MESSAGE"),
                                getText(row, "ERROR_TIME")
                        ));
                        log.info("[ExensioLoadError] Raw data error query hit for lot={} file={} in schema={} (first 35 chars match, time window={}-{}) (traceId={})",
                                rec.lot(), fileNameFilter, schema, windowStart, windowEnd, traceId);
                        break; // found in this schema, no need to check others
                    }
                } catch (Exception ex) {
                    log.warn("[ExensioLoadError] Raw data error query failed for lot={} in schema={} (traceId={}): {}",
                            rec.lot(), schema, traceId, ex.getMessage());
                }
            }
        }

        return result;
    }

    private List<String> getLotCandidates(String lot) {
        if (lot == null || lot.isBlank()) return Collections.emptyList();
        String cleanLot = lot.trim();
        Set<String> candidates = new LinkedHashSet<>();
        // 1. Always include exact lot as-is (both upper and lower) — no assumptions about delimiters
        candidates.add(cleanLot.toUpperCase(Locale.ROOT));
        candidates.add(cleanLot.toLowerCase(Locale.ROOT));

        // 2. If delimiters exist, handle "sometimes not loaded with delimiters"
        String stripped = cleanLot.replaceAll("[.\\-_]", "");
        if (!stripped.equalsIgnoreCase(cleanLot) && !stripped.isBlank()) {
            candidates.add(stripped.toUpperCase(Locale.ROOT));
            candidates.add(stripped.toLowerCase(Locale.ROOT));
        }

        // 3. Delimiter interchange: replace '.' with '-', '_', etc.
        for (char sep : new char[]{'.', '-', '_'}) {
            if (cleanLot.indexOf(sep) >= 0) {
                for (char targetSep : new char[]{'.', '-', '_'}) {
                    if (sep != targetSep) {
                        String swapped = cleanLot.replace(sep, targetSep);
                        candidates.add(swapped.toUpperCase(Locale.ROOT));
                        candidates.add(swapped.toLowerCase(Locale.ROOT));
                    }
                }
            }
        }

        // 4. If delimiters exist, also handle "loaded as base lot before delimiter"
        for (char sep : new char[]{'.', '-', '_'}) {
            int idx = cleanLot.indexOf(sep);
            if (idx >= 3) {
                String base = cleanLot.substring(0, idx).trim();
                if (!base.isBlank()) {
                    candidates.add(base.toUpperCase(Locale.ROOT));
                    candidates.add(base.toLowerCase(Locale.ROOT));
                }
            }
        }
        return new ArrayList<>(candidates);
    }

    private String extractLotPrefix(String lot) {
        if (lot == null || lot.isBlank()) return null;
        String clean = lot.trim();
        // 1. If delimiters exist, take the base before the first delimiter if length >= 5
        for (char sep : new char[]{'.', '-', '_'}) {
            int idx = clean.indexOf(sep);
            if (idx >= 5) {
                return clean.substring(0, idx).trim();
            }
        }
        // 2. If no delimiter, take first 8 characters (or whole string if >= 5)
        String stripped = clean.replaceAll("[.\\-_]", "");
        if (stripped.length() >= 8) {
            return stripped.substring(0, 8);
        } else if (stripped.length() >= 5) {
            return stripped;
        }
        return null;
    }

    private String buildLotMatchClause(String lot) {
        List<String> candidates = getLotCandidates(lot);
        String inList = candidates.stream()
                .map(c -> "'" + escapeSqlLiteral(c) + "'")
                .collect(Collectors.joining(", "));

        StringBuilder clause = new StringBuilder();
        clause.append("(l.lot_id IN (").append(inList).append(")")
                .append(" OR sl.lot_id IN (").append(inList).append(")");

        String prefix = extractLotPrefix(lot);
        if (prefix != null && !prefix.isBlank()) {
            String escapedPrefix = escapeSqlLiteral(prefix);
            clause.append(" OR REGEXP_LIKE(l.lot_id, '^").append(escapedPrefix).append("', 'i')")
                    .append(" OR REGEXP_LIKE(sl.lot_id, '^").append(escapedPrefix).append("', 'i')");
        }
        clause.append(")");
        return clause.toString();
    }

    private String buildSingleRawSql(String lot, String wafer, int pgcKey, Set<String> identifiers, String schemaLabel) {
        String cleanLot = lot.trim();
        StringBuilder where = new StringBuilder();
        where.append("ol.pgc_key = ").append(pgcKey)
                .append(" AND ").append(buildLotMatchClause(cleanLot));

        if (!isBlankOrNa(wafer)) {
            where.append(buildWaferMatchClause(wafer));
        }

        // Identifier LIKE filtering is intentionally excluded from the JOIN ON clause.
        // A leading-wildcard LIKE on UPPER(NVL(de.file_name,'')) inside a JOIN forces a
        // full df_export table scan for every op_log row, which is the primary cause of
        // raw-sql timeouts. Identifier-based scoring is applied post-query by
        // selectBestRawRow(), so omitting it here has no impact on correctness.
        String dfExportJoin = " LEFT JOIN df_export de ON de.lg_key = ol.lg_key AND (w.wf_key IS NULL OR de.wf_key = w.wf_key)";

        String tsFormat = "'YYYY-MM-DD" + '"' + "T" + '"' + "HH24:MI:SS.FF3" + '"' + "Z" + '"' + "'";
        return "SELECT lot_id, wafer_id, lot_key, wafer_key, pg_key, ppid, file_name, end_time, schema_name FROM (" +
            " SELECT NVL(l.lot_id, NVL(sl.lot_id,'')) AS lot_id, NVL(w.wf_id,'') AS wafer_id," +
                " ol.lot_key AS lot_key, NVL(w.wf_key,0) AS wafer_key," +
                " NVL(ol.pg_key,0) AS pg_key, NVL(p.ppid,'') AS ppid," +
                " NVL(de.file_name,'') AS file_name," +
                " NVL(TO_CHAR(ol.end_time, " + tsFormat + "),'') AS end_time," +
                " '" + escapeSqlLiteral(schemaLabel) + "' AS schema_name" +
                " FROM op_log ol" +
                " JOIN lot l ON l.lot_key = ol.lot_key" +
                " LEFT JOIN lot sl ON sl.lot_key = ol.src_lot" +
                " JOIN program p ON p.pg_key = ol.pg_key" +
                " LEFT JOIN wf_log wfl ON wfl.lg_key = ol.lg_key" +
                " LEFT JOIN wafer w ON w.wf_key = wfl.wf_key" +
                dfExportJoin +
                " WHERE " + where +
                " ORDER BY ol.end_time DESC" +
                ") WHERE ROWNUM <= " + props.getRawSqlRowLimit();
    }

    /**
     * Builds a fallback raw SQL query without the lot_id filter.
     *
     * <p>This query removes the lot_id constraint and instead applies compensating filters:
     * pgc_key matching, time window constraint on INSERT_TIME, wafer matching, and file matching.
     * Used when the primary lot_id query returns empty results.
     *
     * <p>Parameters:
     * <ul>
     *   <li>{@code pgcKey}: Program group category key for filtering</li>
     *   <li>{@code wafer}: Wafer identifier (may be blank or N/A)</li>
     *   <li>{@code identifiers}: File name, metadata ID, data ID tokens for matching</li>
     *   <li>{@code targetEndTime}: Optional target END_TIME for time delta calculation</li>
     *   <li>{@code timeWindowHours}: Number of hours to look back from targetEndTime (or now)</li>
     * </ul>
     *
     * <p>Returns a single-schema query with ROWNUM limit. The schema label is passed as a parameter.
     * Records are ordered by INSERT_TIME DESC, then END_TIME DESC for selection.
     *
     * <p>Requirements: 2.1, 2.2, 4.2</p>
     */
    private String buildFallbackRawSql(int pgcKey, String wafer, Set<String> identifiers,
                                        Instant targetEndTime, int timeWindowHours, String schemaLabel) {
        StringBuilder where = new StringBuilder();
        where.append("ol.pgc_key = ").append(pgcKey);

        // Add wafer matching clause if wafer is provided and not blank/N/A
        if (!isBlankOrNa(wafer)) {
            where.append(buildWaferMatchClause(wafer));
        }

        // Same rationale as buildSingleRawSql: identifier LIKE filters are excluded from
        // the JOIN ON clause to prevent full df_export scans. Post-query record selection
        // handles identifier scoring via selectBestRecordByTimestamp().
        String dfExportJoin = " LEFT JOIN df_export de ON de.lg_key = ol.lg_key AND (w.wf_key IS NULL OR de.wf_key = w.wf_key)";

        String tsFormat = "'YYYY-MM-DD" + '"' + "T" + '"' + "HH24:MI:SS.FF3" + '"' + "Z" + '"' + "'";
        return "SELECT lot_id, wafer_id, lot_key, wafer_key, pg_key, ppid, file_name, end_time, schema_name FROM (" +
            " SELECT NVL(l.lot_id, NVL(sl.lot_id,'')) AS lot_id, NVL(w.wf_id,'') AS wafer_id," +
                " ol.lot_key AS lot_key, NVL(w.wf_key,0) AS wafer_key," +
                " NVL(ol.pg_key,0) AS pg_key, NVL(p.ppid,'') AS ppid," +
                " NVL(de.file_name,'') AS file_name," +
                " NVL(TO_CHAR(ol.end_time, " + tsFormat + "),'') AS end_time," +
                " '" + escapeSqlLiteral(schemaLabel) + "' AS schema_name" +
                " FROM op_log ol" +
                " JOIN lot l ON l.lot_key = ol.lot_key" +
                " LEFT JOIN lot sl ON sl.lot_key = ol.src_lot" +
                " JOIN program p ON p.pg_key = ol.pg_key" +
                " LEFT JOIN wf_log wfl ON wfl.lg_key = ol.lg_key" +
                " LEFT JOIN wafer w ON w.wf_key = wfl.wf_key" +
                dfExportJoin +
                " WHERE " + where +
                " ORDER BY ol.end_time DESC" +
                ") WHERE ROWNUM <= " + props.getRawSqlRowLimit();
    }

    /**
     * Formats an Instant as a SQL-compatible timestamp string in UTC (YYYY-MM-DD HH24:MI:SS.FF).
     */
    private String formatInstantForSql(Instant instant) {
        if (instant == null) return "";
        return SQL_TIMESTAMP_FORMATTER.format(instant);
    }

    private String buildBatchRawSql(List<String> clauses, String schemaLabel) {
        String where = String.join(" OR ", clauses);
        String tsFormat = "'YYYY-MM-DD" + '"' + "T" + '"' + "HH24:MI:SS.FF3" + '"' + "Z" + '"' + "'";
        return "SELECT lot_id, wafer_id, lot_key, wafer_key, pg_key, ppid, file_name, end_time, schema_name FROM (" +
            " SELECT NVL(l.lot_id, NVL(sl.lot_id,'')) AS lot_id, NVL(w.wf_id,'') AS wafer_id," +
                " ol.lot_key AS lot_key, NVL(w.wf_key,0) AS wafer_key," +
                " NVL(ol.pg_key,0) AS pg_key, NVL(p.ppid,'') AS ppid," +
                " NVL(de.file_name,'') AS file_name," +
                " NVL(TO_CHAR(ol.end_time, " + tsFormat + "),'') AS end_time," +
                " '" + escapeSqlLiteral(schemaLabel) + "' AS schema_name" +
                " FROM op_log ol" +
                " JOIN lot l ON l.lot_key = ol.lot_key" +
                " LEFT JOIN lot sl ON sl.lot_key = ol.src_lot" +
                " JOIN program p ON p.pg_key = ol.pg_key" +
                " LEFT JOIN wf_log wfl ON wfl.lg_key = ol.lg_key" +
                " LEFT JOIN wafer w ON w.wf_key = wfl.wf_key" +
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

            boolean isBetter = false;
            if (best == null) {
                isBetter = true;
            } else if (score > bestScore) {
                isBetter = true;
            } else if (score == bestScore) {
                if (targetEndTime != null) {
                    if (delta < bestDelta) {
                        isBetter = true;
                    } else if (delta == bestDelta && getLong(row, "LOT_KEY") > getLong(best, "LOT_KEY")) {
                        isBetter = true;
                    }
                } else {
                    // No targetEndTime: prefer latest END_TIME, then LOT_KEY
                    Instant bestEnd = parseInstantSafe(getText(best, "END_TIME"));
                    int endComp = compareInstants(end, bestEnd);
                    if (endComp > 0) {
                        isBetter = true;
                    } else if (endComp == 0 && getLong(row, "LOT_KEY") > getLong(best, "LOT_KEY")) {
                        isBetter = true;
                    }
                }
            }

            if (isBetter) {
                best = row;
                bestScore = score;
                bestDelta = delta;
            }
        }

        return best;
    }

    /**
     * Enhanced timestamp-based record selection implementing priority logic.
     *
     * <p>Priority order:
     * <ol>
     *   <li>If targetEndTime provided: record with minimum |END_TIME - targetEndTime| delta (in UTC)</li>
     *   <li>If deltas are identical: record with maximum LOT_KEY</li>
     *   <li>If no targetEndTime: record with maximum END_TIME</li>
     *   <li>If END_TIME identical: record with maximum LOT_KEY</li>
     * </ol>
     *
     * <p>All NULL timestamp values are handled gracefully, treating NULL as less-than any actual value.
     *
     * @param rows the array of result rows from the query
     * @param targetEndTime optional target END_TIME for delta matching
     * @return the best matching JsonNode row, or null if no valid rows
     */
    private JsonNode selectBestRecordByTimestamp(JsonNode rows, Instant targetEndTime) {
        if (rows == null || !rows.isArray() || rows.isEmpty()) {
            return null;
        }

        JsonNode bestRow = null;
        int recordsEvaluated = 0;

        if (targetEndTime != null) {
            // Priority 1: Minimize |END_TIME - targetEndTime| delta in UTC
            long bestDelta = Long.MAX_VALUE;

            for (JsonNode row : rows) {
                recordsEvaluated++;
                long waferKey = getLong(row, "WAFER_KEY");
                if (waferKey <= 0) continue;

                Instant endTime = parseInstantSafe(getText(row, "END_TIME"));
                long delta = (endTime == null)
                        ? Long.MAX_VALUE
                        : Math.abs(Duration.between(targetEndTime, endTime).getSeconds());

                boolean isBetter = false;
                if (bestRow == null || delta < bestDelta) {
                    isBetter = true;
                } else if (delta == bestDelta && getLong(row, "LOT_KEY") > getLong(bestRow, "LOT_KEY")) {
                    isBetter = true;
                }

                if (isBetter) {
                    bestRow = row;
                    bestDelta = delta;
                }
            }
        } else {
            // Priority: Order by END_TIME DESC, LOT_KEY DESC
            for (JsonNode row : rows) {
                recordsEvaluated++;
                long waferKey = getLong(row, "WAFER_KEY");
                if (waferKey <= 0) continue;

                if (bestRow == null) {
                    bestRow = row;
                } else {
                    Instant currentEndTime = parseInstantSafe(getText(row, "END_TIME"));
                    Instant bestEndTime = parseInstantSafe(getText(bestRow, "END_TIME"));
                    int endTimeComparison = compareInstants(currentEndTime, bestEndTime);
                    if (endTimeComparison > 0) {
                        bestRow = row;
                    } else if (endTimeComparison == 0) {
                        long currentLotKey = getLong(row, "LOT_KEY");
                        long bestLotKey = getLong(bestRow, "LOT_KEY");
                        if (currentLotKey > bestLotKey) {
                            bestRow = row;
                        }
                    }
                }
            }
        }

        if (bestRow != null) {
            log.debug("Timestamp-based record selection: evaluated {} records, targetEndTime={}", 
                     recordsEvaluated, targetEndTime);
        }

        return bestRow;
    }

    /**
     * Helper method to compare two Instant values for sorting (descending order).
     *
     * <p>NULL values are treated as "less than" non-NULL values.
     *
     * @param a first Instant (may be null)
     * @param b second Instant (may be null)
     * @return positive if a > b, negative if a < b, 0 if equal (considering NULLs)
     */
    private int compareInstants(Instant a, Instant b) {
        if (a == null && b == null) return 0;
        if (a == null) return -1;  // NULL < non-NULL
        if (b == null) return 1;   // non-NULL > NULL
        return a.compareTo(b);
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

        // Prioritize actual filename for matching against df_export.file_name.
        // Internal numeric IDs (metadataId, dataId) do not exist in Exensio df_export.
        String candidateName = (filename != null && !filename.isBlank())
                ? filename.trim()
                : (dataId != null && !dataId.isBlank() && !dataId.matches("^\\d+$") ? dataId.trim() : null);

        if (candidateName != null && !candidateName.isBlank()) {
            // Match on basename (strip path separators if present)
            String baseName = candidateName.contains("/")
                    ? candidateName.substring(candidateName.lastIndexOf('/') + 1)
                    : candidateName.contains("\\")
                            ? candidateName.substring(candidateName.lastIndexOf('\\') + 1)
                            : candidateName;

            int dot = baseName.lastIndexOf('.');
            String noExt = dot > 0 ? baseName.substring(0, dot) : baseName;

            // Strip bracketed suffixes like _{LASERSCRIBE} or {TAG}
            String cleanName = noExt.replaceAll("_?\\{[^}]*\\}", "").replaceAll("[._-]+$", "").trim();
            if (!cleanName.isBlank()) {
                if (cleanName.length() > 35) {
                    ids.add(cleanName.substring(0, 35).replaceAll("[._-]+$", ""));
                    ids.add(cleanName.substring(0, 30).replaceAll("[._-]+$", ""));
                } else if (cleanName.length() >= 30) {
                    ids.add(cleanName);
                    ids.add(cleanName.substring(0, 30).replaceAll("[._-]+$", ""));
                } else {
                    ids.add(cleanName);
                }
            }
        }
        ids.removeIf(v -> v == null || v.isBlank());
        return ids;
    }

    private String buildIdentifierLikeClause(String column, Set<String> identifiers) {
        List<String> parts = new ArrayList<>();
        for (String id : identifiers) {
            parts.add("UPPER(NVL(" + column + ",'')) LIKE '%" + escapeLikeLiteral(id.toUpperCase(Locale.ROOT)) + "%' ESCAPE '\\'");
        }
        if (parts.isEmpty()) {
            return "1=0";
        }
        return "(" + String.join(" OR ", parts) + ")";
    }

    public static Integer extractWaferNum(String rawWafer) {
        if (rawWafer == null || rawWafer.isBlank() || "NA".equalsIgnoreCase(rawWafer.trim())) {
            return null;
        }
        String trimmed = rawWafer.trim();
        // 1. Match trailing digits after delimiter: -11, _05, .01, #02
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("[-_#.](\\d+)$").matcher(trimmed);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (NumberFormatException ignored) {}
        }
        // 2. Strip leading letter prefix(es) (e.g. W01, WF05, WAFER12)
        String cleaned = trimmed.replaceFirst("^[A-Za-z]+[-_#.]*", "");
        try {
            return Integer.parseInt(cleaned);
        } catch (NumberFormatException ignored) {}

        // 3. Fall back to any trailing digits
        m = java.util.regex.Pattern.compile("(\\d+)$").matcher(trimmed);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (NumberFormatException ignored) {}
        }
        return null;
    }

    private StringBuilder buildWaferMatchClause(String rawWafer) {
        StringBuilder wfClause = new StringBuilder();
        Integer waferNum = extractWaferNum(rawWafer);
        if (waferNum != null) {
            String numStr = String.valueOf(waferNum);
            String pad2 = String.format("%02d", waferNum);
            wfClause.append(" AND (w.wf_num = ").append(waferNum)
                    .append(" OR w.wf_num = '").append(numStr).append("'")
                    .append(" OR w.wf_num = '").append(pad2).append("'")
                    .append(" OR w.wf_id = '").append(numStr).append("'")
                    .append(" OR w.wf_id = '").append(pad2).append("'")
                    .append(" OR UPPER(NVL(w.wf_id, '')) LIKE '%").append(pad2).append("%'")
                    .append(" OR REGEXP_LIKE(w.wf_id, '[-_#.]0?").append(numStr).append("$'))");
        } else if (!isBlankOrNa(rawWafer)) {
            String cleanWafer = escapeSqlLiteral(rawWafer.trim());
            wfClause.append(" AND (w.wf_id = '").append(cleanWafer).append("'")
                    .append(" OR UPPER(NVL(w.wf_id, '')) LIKE '%").append(cleanWafer.toUpperCase(Locale.ROOT)).append("%')");
        }
        return wfClause;
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

    private String escapeRegexLiteral(String value) {
        if (value == null) return "";
        return value.replaceAll("([\\\\.^$*+?()\\[\\]{}|])", "\\\\$1");
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

    private int getInt(JsonNode node, String field, int defaultValue) {
        JsonNode v = getFieldNode(node, field);
        if (v == null || v.isNull()) return defaultValue;
        if (v.isNumber()) return v.asInt();
        try {
            return Integer.parseInt(v.asText());
        } catch (Exception e) {
            return defaultValue;
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
            long firstLotKeyWithoutWafers = 0;
            String firstLotIdWithoutWafers = null;
            long firstPgKeyWithoutWafers = 0;
            String firstPpidWithoutWafers = null;

            for (JsonNode lotNode : lots) {
                long lotKey = lotNode.path("lot_key").asLong(0);
                String lotIdStr = lotNode.path("lot_id").asText(null);
                JsonNode wafers = lotNode.path("wafers");

                if (!wafers.isArray() || wafers.isEmpty()) {
                    if (lotKey > 0 && firstLotKeyWithoutWafers == 0) {
                        firstLotKeyWithoutWafers = lotKey;
                        firstLotIdWithoutWafers = lotIdStr;
                        firstPgKeyWithoutWafers = lotNode.path("pg_key").asLong(0);
                        firstPpidWithoutWafers = lotNode.path("ppid").asText(null);
                    }
                    continue;
                }

                for (JsonNode waferNode : wafers) {
                    String rawWafer = waferNode.path("wafer_id").asText(null);
                    String waferId = ExensioSqlUtilService.stripWaferPrefix(rawWafer);

                    // Match by wafer_id if provided; otherwise use end_time proximity / first available.
                    if (targetWaferId != null && !targetWaferId.isBlank()) {
                        boolean matches = targetWaferId.equalsIgnoreCase(waferId)
                                || targetWaferId.equalsIgnoreCase(rawWafer);
                        if (!matches) {
                            String cleanTarget = ExensioSqlUtilService.stripWaferPrefix(targetWaferId);
                            matches = cleanTarget.equalsIgnoreCase(waferId)
                                    || cleanTarget.equalsIgnoreCase(rawWafer);
                        }
                        if (!matches && waferId != null) {
                            try {
                                int tNum = Integer.parseInt(ExensioSqlUtilService.stripWaferPrefix(targetWaferId));
                                int wNum = Integer.parseInt(waferId);
                                matches = (tNum == wNum);
                            } catch (NumberFormatException ignored) {}
                        }
                        if (!matches && rawWafer != null) {
                            String upperRaw = rawWafer.toUpperCase(Locale.ROOT);
                            String upperTarget = targetWaferId.toUpperCase(Locale.ROOT);
                            String cleanTarget = ExensioSqlUtilService.stripWaferPrefix(targetWaferId);
                            matches = upperRaw.endsWith("_" + upperTarget)
                                    || upperRaw.endsWith("-" + upperTarget)
                                    || (!cleanTarget.isBlank() && (upperRaw.endsWith("_" + cleanTarget) || upperRaw.endsWith("-" + cleanTarget)));
                        }
                        if (!matches) {
                            continue;
                        }
                    }

                    if (targetEndTime == null) {
                        long waferKey = waferNode.path("wafer_key").asLong(0);
                        long pgKey = waferNode.path("pg_key").asLong(0);
                        String ppid = waferNode.path("ppid").asText(null);
                        if (waferKey > 0) {
                            ExensioLotWaferResult candidate =
                                    new ExensioLotWaferResult.Found(lotKey, waferKey, pgKey, ppid, lotIdStr, waferId, null, null);
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
                    String finalWaferId = ExensioSqlUtilService.stripWaferPrefix(bestWaferNode.path("wafer_id").asText(null));
                    ExensioLotWaferResult candidate =
                            new ExensioLotWaferResult.Found(bestLotKey, waferKey, pgKey, ppid, bestLotId, finalWaferId, null, null);
                    return applyPpidCheck(candidate, ppid, testPhase, targetWaferId, finalWaferId);
                }
            }

            // Fallback for lot-level data (e.g. DEFECT or PGC 2) where the lot exists in Exensio
            // but wafers array is empty or not broken down into wafer objects by the API:
            if (firstLotKeyWithoutWafers > 0) {
                ExensioLotWaferResult candidate =
                        new ExensioLotWaferResult.Found(firstLotKeyWithoutWafers, 0L, firstPgKeyWithoutWafers,
                                firstPpidWithoutWafers, firstLotIdWithoutWafers, targetWaferId, null, null);
                return applyPpidCheck(candidate, firstPpidWithoutWafers, testPhase, targetWaferId, targetWaferId);
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

    // -------------------------------------------------------------------------
    // Data Verification — Results API and Programs API
    // Modeled after Python lib's exAPI_Results() (line 676) and exAPI_Programs() (line 243)
    // -------------------------------------------------------------------------

    /**
     * Verification result from the Results API or Programs API check.
     *
     * @param verified     true when actual parametric data rows were confirmed
     * @param rowCount     number of data rows found (-1 on error or not checked)
     * @param indexCount   number of test indexes in the program (-1 on error or not checked)
     * @param errorMessage error description if verification failed, null on success
     */
    public record DataVerificationResult(boolean verified, int rowCount, int indexCount, String errorMessage) {
        public static DataVerificationResult success(int rowCount, int indexCount) {
            return new DataVerificationResult(true, rowCount, indexCount, null);
        }
        public static DataVerificationResult notLoaded(int rowCount) {
            return new DataVerificationResult(false, rowCount, -1, "No parametric data rows found");
        }
        public static DataVerificationResult error(String message) {
            return new DataVerificationResult(false, -1, -1, message);
        }
        public static DataVerificationResult skipped() {
            return new DataVerificationResult(true, -1, -1, null);
        }
    }

    /**
     * Verifies that a {@link ExensioLotWaferResult.Found} result has actual parametric
     * data loaded, using the Results API and optionally the Programs API.
     *
     * <p>This is the key missing step identified from the Python lib's workflow:
     * <ol>
     *   <li>lot-wafer-lookup → get keys (current implementation stops here)</li>
     *   <li>Programs API → validate program has valid indexes (optional)</li>
     *   <li>Results API → confirm actual data rows exist (the fix)</li>
     * </ol>
     *
     * <p>When verification is disabled in config, returns the Found result unchanged.
     * When verification succeeds, returns Found with {@code dataVerified=true}.
     * When verification fails (no data rows), returns {@code NotFound} to trigger retry.
     *
     * @param found   the successful lookup result containing pg_key, wafer_key, ppid
     * @param pgcKey  the program group class key for this data type
     * @param traceId correlation ID for logging
     * @return the original Found (with dataVerified=true) or NotFound if data not yet loaded
     */
    public ExensioLotWaferResult verifyAndEnrich(ExensioLotWaferResult.Found found, int pgcKey, String traceId) {
        if (!props.isVerifyDataLoaded()) {
            return found; // Verification disabled — return as-is
        }
        if (found.dataVerified()) {
            return found; // Already verified — return as-is
        }

        String token;
        try {
            // Use the schema from the Found result if available, otherwise use default
            String schema = found.schema() != null && !found.schema().isBlank()
                    ? found.schema()
                    : props.resolvedDbschema();
            token = authService.getToken(schema);
        } catch (ExensioAuthService.ExensioAuthException e) {
            log.warn("Data verification auth failed (traceId={}): {} — accepting Found result as unverified",
                    traceId, e.getMessage());
            return found; // Don't block on auth failure — accept unverified
        }

        // Step 1 (optional): Validate program via Programs API
        if (props.isValidateProgram() && found.ppid() != null && !found.ppid().isBlank()) {
            int indexCount = validateProgram(found.ppid(), pgcKey, token, traceId);
            if (indexCount == 0) {
                log.warn("Program validation failed — PPID '{}' has 0 indexes (traceId={}). " +
                         "Downgrading to NotFound for retry.", found.ppid(), traceId);
                return new ExensioLotWaferResult.NotFound();
            }
            if (indexCount > 0) {
                log.debug("Program validation passed — PPID '{}' has {} indexes (traceId={})",
                         found.ppid(), indexCount, traceId);
            }
            // indexCount == -1 means error — continue to data verification anyway
        }

        // Step 2: Verify actual data rows via Results API
        int rowCount = verifyDataLoaded(found.pgKey(), found.waferKey(), pgcKey, token, traceId);

        if (rowCount >= props.getVerifyMinRows()) {
            log.info("Data verification PASSED: {} rows found for waferKey={}, pgKey={} (traceId={})",
                     rowCount, found.waferKey(), found.pgKey(), traceId);
            return new ExensioLotWaferResult.Found(
                    found.lotKey(), found.waferKey(), found.pgKey(), found.ppid(),
                    found.lotId(), found.waferId(), found.fileName(), found.schema(), true);
        }

        if (rowCount == 0) {
            log.info("Data verification FAILED: 0 rows for waferKey={}, pgKey={} (traceId={}). " +
                     "Keys exist but data not yet loaded — returning NotFound for retry.",
                     found.waferKey(), found.pgKey(), traceId);
            return new ExensioLotWaferResult.NotFound();
        }

        // rowCount == -1 means error — accept the Found result unverified to avoid blocking
        log.warn("Data verification ERROR for waferKey={}, pgKey={} (traceId={}). " +
                 "Accepting Found result as unverified.", found.waferKey(), found.pgKey(), traceId);
        return found;
    }

    /**
     * Calls {@code POST /v1/result/results} to verify that actual parametric data rows
     * exist in Exensio for the given {@code pg_key + wafer_key} combination.
     *
     * <p>Modeled after the Python lib's {@code exAPI_Results()} (line 676) and
     * {@code parse_Results()} (line 951). Request body:
     * <pre>{
     *   "pgc_key": &lt;pgcKey&gt;,
     *   "rework_criteria": "LATEST",
     *   "test_indexes": [1],
     *   "stat_keys": [{ "pg_key": &lt;pgKey&gt;, "wafer_key": &lt;waferKey&gt; }]
     * }</pre>
     *
     * <p>Uses {@code test_indexes: [1]} to request only the first index, minimizing
     * response size since we only need existence confirmation, not the full dataset.
     *
     * @param pgKey    program key from lot-wafer-lookup
     * @param waferKey wafer key from lot-wafer-lookup
     * @param pgcKey   program group class key (1=PROBE, 2=FT, etc.)
     * @param token    bearer token for authentication
     * @param traceId  correlation ID for logging
     * @return number of data rows found, 0 if no data, -1 on error
     */
    public int verifyDataLoaded(long pgKey, long waferKey, int pgcKey, String token, String traceId) {
        String url = props.resolvedBaseUrl().replaceAll("/$", "") + "/v1/result/results";

        try {
            // Build request body — same structure as Python's exAPI_Results
            ObjectNode body = objectMapper.createObjectNode();
            body.put("pgc_key", pgcKey);
            body.put("rework_criteria", "LATEST");

            // Request only index 1 to minimize response size (we only need existence check)
            ArrayNode testIndexes = body.putArray("test_indexes");
            testIndexes.add(1);

            // stat_keys: [{ "pg_key": pgKey, "wafer_key": waferKey }]
            ArrayNode statKeys = body.putArray("stat_keys");
            ObjectNode statKey = objectMapper.createObjectNode();
            statKey.put("pg_key", pgKey);
            statKey.put("wafer_key", waferKey);
            statKeys.add(statKey);

            if (props.isLogRequestPayloads()) {
                log.info("Exensio verify-data request (traceId={}): url={}, body={}", traceId, url, body);
            }

            long startTime = System.currentTimeMillis();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(props.getVerifyTimeoutSeconds()))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            long elapsed = System.currentTimeMillis() - startTime;

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("Exensio verify-data FAILED (HTTP {}, {}ms, traceId={})",
                        response.statusCode(), elapsed, traceId);
                return -1;
            }

            // Parse response — structure from Python's parse_Results:
            // { "results": { "result_sets": [{ "rows": [[...], ...] }] } }
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode resultSets = root.path("results").path("result_sets");
            if (!resultSets.isArray() || resultSets.isEmpty()) {
                log.debug("Exensio verify-data: no result_sets in response ({}ms, traceId={})", elapsed, traceId);
                return 0;
            }

            JsonNode rows = resultSets.get(0).path("rows");
            int rowCount = rows.isArray() ? rows.size() : 0;

            log.debug("Exensio verify-data: {} rows found ({}ms, traceId={})", rowCount, elapsed, traceId);
            return rowCount;

        } catch (Exception e) {
            log.warn("Exensio verify-data failed (traceId={}): {}", traceId, e.getMessage());
            return -1;
        }
    }

    /**
     * Calls {@code POST /v1/key/programs} to validate that the parametric test program
     * (PPID) exists and has valid indexes.
     *
     * <p>Modeled after the Python lib's {@code exAPI_Programs()} (line 243) and
     * {@code parse_Programs()} (line 776). Request body:
     * <pre>{
     *   "pgc_keys": [&lt;pgcKey&gt;],
     *   "ppids": ["&lt;ppid&gt;"]
     * }</pre>
     *
     * @param ppid    parametric program ID from lot-wafer-lookup
     * @param pgcKey  program group class key
     * @param token   bearer token for authentication
     * @param traceId correlation ID for logging
     * @return number of indexes in the program, 0 if program has no indexes, -1 on error
     */
    public int validateProgram(String ppid, int pgcKey, String token, String traceId) {
        String url = props.resolvedBaseUrl().replaceAll("/$", "") + "/v1/key/programs";

        try {
            // Build request body — same structure as Python's exAPI_Programs
            ObjectNode body = objectMapper.createObjectNode();
            ArrayNode pgcKeys = body.putArray("pgc_keys");
            pgcKeys.add(pgcKey);
            ArrayNode ppids = body.putArray("ppids");
            ppids.add(ppid);

            if (props.isLogRequestPayloads()) {
                log.info("Exensio validate-program request (traceId={}): url={}, body={}", traceId, url, body);
            }

            long startTime = System.currentTimeMillis();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(props.getVerifyTimeoutSeconds()))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            long elapsed = System.currentTimeMillis() - startTime;

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("Exensio validate-program FAILED (HTTP {}, {}ms, traceId={})",
                        response.statusCode(), elapsed, traceId);
                return -1;
            }

            // Parse response — structure from Python's parse_Programs:
            // { "programs": [{ "indexes": 5, ... }] }
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode programs = root.path("programs");
            if (!programs.isArray() || programs.isEmpty()) {
                log.debug("Exensio validate-program: no programs in response ({}ms, traceId={})", elapsed, traceId);
                return 0;
            }

            // Python lib checks: numPrograms > 1 → error
            if (programs.size() > 1) {
                log.warn("Exensio validate-program: {} programs found for PPID '{}' (expected 1) (traceId={})",
                        programs.size(), ppid, traceId);
            }

            int indexes = programs.get(0).path("indexes").asInt(0);
            log.debug("Exensio validate-program: PPID '{}' has {} indexes ({}ms, traceId={})",
                     ppid, indexes, elapsed, traceId);
            return indexes;

        } catch (Exception e) {
            log.warn("Exensio validate-program failed (traceId={}): {}", traceId, e.getMessage());
            return -1;
        }
    }

    private Instant parseInstantSafe(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Instant.parse(value);
        } catch (Exception ignored) {
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // Advanced-Dates endpoint
    // -------------------------------------------------------------------------

    /**
     * Parsed result from the {@code POST /api/v1/key/advanced-dates} endpoint.
     *
     * <p>Both instants are in UTC. Either field may be {@code null} when the API
     * response does not include a start or end date (e.g. ABSOLUTE mode with a
     * single fixed point).</p>
     */
    public record AdvancedDatesResult(Instant startDate, Instant endDate) {}

    /**
     * Calls the Exensio {@code POST /api/v1/key/advanced-dates} endpoint to resolve
     * a date window according to Exensio's own calendar logic.
     *
     * <p>Use this instead of hard-coded time offsets when you need to restrict a
     * raw-SQL fallback query to the same date window that the Exensio UI would use.
     * The two most common request shapes are:
     *
     * <ul>
     *   <li><b>ABSOLUTE</b> — fixed ISO-8601 start/end strings.</li>
     *   <li><b>RELATIVE</b> — period-based window (e.g. last N hours / days).</li>
     * </ul>
     *
     * <p>On any error (network, HTTP, parse) the method logs a warning and returns
     * {@code null}. Callers should fall back to their default behaviour in that case.
     *
     * @param requestBody  fully-formed JSON body as defined by the advanced-dates API spec
     * @param schema       Exensio schema to authenticate against (e.g. {@code "PRODUCTION"})
     * @param traceId      correlation ID for logging
     * @return resolved start/end pair, or {@code null} on error
     */
    public AdvancedDatesResult callAdvancedDates(String requestBody, String schema, String traceId) {
        String url = props.resolvedBaseUrl().replaceAll("/$", "") + "/api/v1/key/advanced-dates";
        try {
            String token = authService.getToken(schema);
            log.info("Exensio advanced-dates START: url={}, traceId={}", url, traceId);
            if (props.isLogRequestPayloads()) {
                log.info("Exensio advanced-dates body (traceId={}):\n{}", traceId, requestBody);
            }

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 401) {
                // One retry with a fresh token
                log.debug("Exensio advanced-dates got 401 — refreshing token and retrying (traceId={})", traceId);
                authService.invalidateToken(schema);
                token = authService.login(schema);
                request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(15))
                        .header("Authorization", "Bearer " + token)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                        .build();
                response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            }

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("Exensio advanced-dates FAILED (HTTP {}, traceId={}): {}",
                        response.statusCode(), traceId, response.body());
                return null;
            }

            log.info("Exensio advanced-dates SUCCESS (HTTP {}, traceId={})", response.statusCode(), traceId);
            if (props.isLogRequestPayloads()) {
                log.info("Exensio advanced-dates response (traceId={}):\n{}", traceId, response.body());
            }

            return parseAdvancedDatesResponse(response.body());

        } catch (Exception e) {
            log.warn("Exensio advanced-dates call failed (traceId={}): {}", traceId, e.getMessage());
            return null;
        }
    }

    /**
     * Convenience method: resolves a RELATIVE date window of {@code hours} hours
     * centred on {@code anchorTime} (or "now" when {@code anchorTime} is null) using
     * the Exensio advanced-dates API.
     *
     * <p>This is the recommended replacement for computing
     * {@code Instant.now().minus(Duration.ofHours(hours))} directly, because it
     * delegates the calendar arithmetic to Exensio and therefore stays consistent
     * with the date ranges that the Exensio UI itself would display.
     *
     * <p>Returns {@code null} when the API call fails — callers should fall back to
     * local time arithmetic.
     *
     * @param hours      look-back window length in hours
     * @param anchorTime optional anchor; if null the current wall-clock is used
     * @param schema     Exensio schema to authenticate against
     * @param traceId    correlation ID for logging
     */
    public AdvancedDatesResult resolveQueryDateWindow(int hours, Instant anchorTime, String schema, String traceId) {
        // Build a RELATIVE request: "last N hours" ending at anchorTime (or 'now').
        // Exensio interprets the interval as [anchorTime - hours, anchorTime].
        String body;
        if (anchorTime != null) {
            // ABSOLUTE request with explicit end = anchorTime, start = anchorTime - hours
            String endStr = anchorTime.toString().replace("Z", "+0000"); // ISO-8601 compat
            String startStr = anchorTime.minus(Duration.ofHours(hours)).toString().replace("Z", "+0000");
            body = "{\"date_type\":\"ABSOLUTE\",\"period_range\":{\"unit\":\"HOUR\"," +
                   "\"from\":{\"period\":0,\"year\":0},\"to\":{\"period\":0,\"year\":0}}," +
                   "\"interval\":{\"offset\":0,\"interval\":1,\"position\":\"END\",\"unit\":\"HOUR\"}," +
                   "\"start_date\":\"" + startStr + "\",\"end_date\":\"" + endStr + "\"}";
        } else {
            // RELATIVE request: last N hours ending at 'now'
            body = "{\"date_type\":\"RELATIVE\",\"period_range\":{\"unit\":\"HOUR\"," +
                   "\"from\":{\"period\":" + hours + ",\"year\":0},\"to\":{\"period\":0,\"year\":0}}," +
                   "\"interval\":{\"offset\":0,\"interval\":1,\"position\":\"END\",\"unit\":\"HOUR\"}}";
        }
        return callAdvancedDates(body, schema, traceId);
    }

    /**
     * Parses the JSON response from the advanced-dates endpoint.
     *
     * <p>Expected shape (from API spec):
     * <pre>
     * {
     *   "start_date": "2026-09-14T19:00:00+0000",
     *   "end_date":   "2026-09-15T03:00:00+0000"
     * }
     * </pre>
     */
    private AdvancedDatesResult parseAdvancedDatesResponse(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            Instant start = parseAdvancedDateField(root, "start_date");
            Instant end   = parseAdvancedDateField(root, "end_date");
            return new AdvancedDatesResult(start, end);
        } catch (Exception e) {
            log.warn("Failed to parse advanced-dates response: {} — body snippet: {}",
                    e.getMessage(), body.length() > 200 ? body.substring(0, 200) : body);
            return null;
        }
    }

    /**
     * Parses a single date field from the advanced-dates JSON response.
     * Handles both {@code +0000} and {@code Z} UTC designators.
     */
    private Instant parseAdvancedDateField(JsonNode root, String field) {
        JsonNode node = getFieldNode(root, field);
        if (node == null || node.isNull()) return null;
        String raw = node.asText("").trim();
        if (raw.isEmpty()) return null;
        // Normalise "+0000" → "Z" for standard Instant.parse
        String normalised = raw.replace("+0000", "Z").replace("+00:00", "Z");
        // If no 'T' separator, assume date-only → midnight UTC
        if (!normalised.contains("T")) {
            normalised = normalised + "T00:00:00Z";
        }
        return parseInstantSafe(normalised);
    }
}

