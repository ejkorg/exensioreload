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
        return lotWaferLookup(lot, wafer, null);
    }

    /**
     * Single-record lot/wafer lookup with optional target end-time matching.
     * When wafer is null/blank, this uses pgc_key=2 and matches the best wafer by end_time.
     */
    public ExensioLotWaferResult lotWaferLookup(String lot, String wafer, Instant targetEndTime) {
        String token;
        try {
            token = authService.getToken();
        } catch (ExensioAuthService.ExensioAuthException e) {
            return new ExensioLotWaferResult.Error("Auth failed: " + e.getMessage());
        }

        ExensioLotWaferResult result = doLotWaferLookup(lot, wafer, targetEndTime, token);

        // Retry once on 401 with a fresh token
        if (result instanceof ExensioLotWaferResult.Error err && err.message().contains("HTTP 401")) {
            log.debug("Exensio 401 — invalidating token and retrying");
            authService.invalidateToken();
            try {
                token = authService.login();
            } catch (ExensioAuthService.ExensioAuthException e) {
                return new ExensioLotWaferResult.Error("Re-auth failed: " + e.getMessage());
            }
            result = doLotWaferLookup(lot, wafer, targetEndTime, token);
        }

        return result;
    }

    // --- private ---

    private ExensioLotWaferResult doLotWaferLookup(String lot, String wafer, Instant targetEndTime, String token) {
        try {
            String url = props.resolvedBaseUrl().replaceAll("/$", "") + "/v1/key/lot-wafer-lookup";
            boolean waferBlank = wafer == null || wafer.isBlank();
            int pgcKey = waferBlank ? 2 : 1;

            ObjectNode body = objectMapper.createObjectNode();
            body.put("pgc_key", pgcKey);
            ArrayNode lotIds = body.putArray("lot_ids");
            lotIds.add(lot);
            ArrayNode waferIds = body.putArray("wafer_ids");
            if (!waferBlank) {
                waferIds.add(wafer);
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

            return parseResponse(response.body(), wafer, targetEndTime);

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
            // Dynamic pgc strategy:
            // - If all records in batch have blank wafer, use pgc_key=2 (lot-level lookup)
            // - Otherwise use pgc_key=1 (wafer-level lookup)
            boolean allWafersBlank = records.stream().allMatch(r -> r.wafer() == null || r.wafer().isBlank());
            body.put("pgc_key", allWafersBlank ? 2 : 1);
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
     * Parses the lot-wafer-lookup response.
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
     */
    private ExensioLotWaferResult parseResponse(String body, String targetWaferId, Instant targetEndTime) {
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
                            return new ExensioLotWaferResult.Found(lotKey, waferKey, pgKey, ppid);
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
                    return new ExensioLotWaferResult.Found(bestLotKey, waferKey, pgKey, ppid);
                }
            }

            return new ExensioLotWaferResult.NotFound();

        } catch (Exception e) {
            log.warn("Failed to parse Exensio lot-wafer-lookup response: {}", e.getMessage());
            return new ExensioLotWaferResult.Error("Parse error: " + e.getMessage());
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
}
