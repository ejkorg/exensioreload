package com.onsemi.cim.apps.exensio.exensioreload.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.onsemi.cim.apps.exensio.exensioreload.config.ExensioProperties;
import com.onsemi.cim.apps.exensio.exensioreload.dto.BatchLookupResult;
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
import java.util.List;

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
        String token;
        try {
            token = authService.getToken();
        } catch (ExensioAuthService.ExensioAuthException e) {
            return new ExensioLotWaferResult.Error("Auth failed: " + e.getMessage());
        }

        ExensioLotWaferResult result = doLotWaferLookup(lot, wafer, targetEndTime, pgcKey, testPhase, token);

        // Retry once on 401 with a fresh token
        if (result instanceof ExensioLotWaferResult.Error err && err.message().contains("HTTP 401")) {
            log.debug("Exensio 401 — invalidating token and retrying");
            authService.invalidateToken();
            try {
                token = authService.login();
            } catch (ExensioAuthService.ExensioAuthException e) {
                return new ExensioLotWaferResult.Error("Re-auth failed: " + e.getMessage());
            }
            result = doLotWaferLookup(lot, wafer, targetEndTime, pgcKey, testPhase, token);
        }

        return result;
    }

    // --- private ---

    private ExensioLotWaferResult doLotWaferLookup(String lot, String wafer, Instant targetEndTime,
                                                    Integer pgcKey, String testPhase, String token) {
        try {
            String url = props.resolvedBaseUrl().replaceAll("/$", "") + "/v1/key/lot-wafer-lookup";
            boolean waferBlank = wafer == null || wafer.isBlank();
            // Use the explicit pgcKey when provided; otherwise fall back to wafer-presence logic.
            int resolvedPgcKey = (pgcKey != null) ? pgcKey : (waferBlank ? 2 : 1);

            ObjectNode body = objectMapper.createObjectNode();
            body.put("pgc_key", resolvedPgcKey);
            ArrayNode lotIds = body.putArray("lot_ids");
            lotIds.add(lot);
            ArrayNode waferIds = body.putArray("wafer_ids");
            if (!waferBlank) {
                waferIds.add(wafer);
            }

            if (props.isLogRequestPayloads()) {
                log.info("Exensio lot-wafer-lookup request: url={}, body={}", url, body.toString());
            }

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .header("Connection", "Close")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 401) {
                return new ExensioLotWaferResult.Error("HTTP 401");
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return new ExensioLotWaferResult.Error("HTTP " + response.statusCode());
            }

            return parseResponse(response.body(), wafer, targetEndTime, testPhase);

        } catch (Exception e) {
            log.warn("Exensio lot-wafer-lookup failed for lot={} wafer={}: {}", lot, wafer, e.getMessage());
            return new ExensioLotWaferResult.Error(e.getMessage());
        }
    }

    /**
     * Batch lot-wafer lookup for multiple records.
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
        String token;
        try {
            token = authService.getToken();
        } catch (ExensioAuthService.ExensioAuthException e) {
            return new BatchLookupResult("Auth failed: " + e.getMessage());
        }

        BatchLookupResult result = doLotWaferLookupBatch(records, token);

        // Retry once on 401 with a fresh token
        if (!result.isSuccess() && result.getErrorMessage().contains("HTTP 401")) {
            log.debug("Exensio 401 on batch lookup — invalidating token and retrying");
            authService.invalidateToken();
            try {
                token = authService.login();
            } catch (ExensioAuthService.ExensioAuthException e) {
                return new BatchLookupResult("Re-auth failed: " + e.getMessage());
            }
            result = doLotWaferLookupBatch(records, token);
        }

        return result;
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
        long startTime = System.currentTimeMillis();
        int batchSize = records.size();

        try {
            String url = props.resolvedBaseUrl().replaceAll("/$", "") + "/v1/key/lot-wafer-lookup";

            // Extract unique lot and wafer IDs from the batch
            java.util.Set<String> uniqueLots = new java.util.HashSet<>();
            java.util.Set<String> uniqueWafers = new java.util.HashSet<>();
            for (StageRecord record : records) {
                uniqueLots.add(record.lot());
                uniqueWafers.add(record.wafer());
            }

            // Build request body
            ObjectNode body = objectMapper.createObjectNode();
            // Derive pgc_key per record from its dataType, then use the most common value across the batch.
            // Requirements: 4.3, 4.4, 6.1
            java.util.Map<Integer, Long> pgcKeyCounts = new java.util.HashMap<>();
            for (StageRecord record : records) {
                boolean waferBlank = record.wafer() == null || record.wafer().isBlank();
                int pgcKey = DataTypePgcKeyMapper.resolve(record.dataType(), waferBlank);
                pgcKeyCounts.merge(pgcKey, 1L, Long::sum);
            }
            int batchPgcKey = pgcKeyCounts.entrySet().stream()
                    .max(java.util.Map.Entry.comparingByValue())
                    .map(java.util.Map.Entry::getKey)
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
                log.info("Exensio batch lot-wafer-lookup request: url={}, body={}", url, body.toString());
            }

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .header("Connection", "Close")
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
                // Rate limit - return error to trigger backoff and retry
                return new BatchLookupResult("HTTP 429 (rate limited)");
            }
            if (response.statusCode() >= 500) {
                // Server error - return error to trigger retry
                return new BatchLookupResult("HTTP " + response.statusCode());
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return new BatchLookupResult("HTTP " + response.statusCode());
            }

            // Parse the batch response
            return BatchLookupResult.parse(response.body(), objectMapper);

        } catch (Exception e) {
            long responseTimeMs = System.currentTimeMillis() - startTime;
            log.warn("Exensio batch lot-wafer-lookup failed after {}ms: {}", responseTimeMs, e.getMessage());
            return new BatchLookupResult("Error: " + e.getMessage());
        }
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
            long bestDeltaSeconds = Long.MAX_VALUE;

            for (JsonNode lotNode : lots) {
                long lotKey = lotNode.path("lot_key").asLong(0);
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
                                    new ExensioLotWaferResult.Found(lotKey, waferKey, pgKey, ppid);
                            return applyPpidCheck(candidate, ppid, testPhase, targetWaferId, waferId);
                        }
                        continue;
                    }

                    Instant exEnd = parseInstantSafe(waferNode.path("end_time").asText(null));
                    long delta = exEnd == null ? Long.MAX_VALUE : Math.abs(Duration.between(targetEndTime, exEnd).getSeconds());
                    if (bestWaferNode == null || delta < bestDeltaSeconds) {
                        bestWaferNode = waferNode;
                        bestLotKey = lotKey;
                        bestDeltaSeconds = delta;
                    }
                }
            }

            if (bestWaferNode != null) {
                long waferKey = bestWaferNode.path("wafer_key").asLong(0);
                long pgKey = bestWaferNode.path("pg_key").asLong(0);
                String ppid = bestWaferNode.path("ppid").asText(null);
                if (waferKey > 0) {
                    ExensioLotWaferResult candidate =
                            new ExensioLotWaferResult.Found(bestLotKey, waferKey, pgKey, ppid);
                    return applyPpidCheck(candidate, ppid, testPhase, targetWaferId,
                            bestWaferNode.path("wafer_id").asText(null));
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
