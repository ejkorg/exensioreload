package com.onsemi.cim.apps.exensio.exensioreload.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.onsemi.cim.apps.exensio.exensioreload.stage.StageRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Result of a batch lot-wafer lookup API call.
 *
 * <p>This class holds the parsed response from the Exensio batch API and provides
 * methods to map the batch response back to individual records.</p>
 *
 * <p>Response shape (from Exensio batch API):
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
public class BatchLookupResult {
    private static final Logger log = LoggerFactory.getLogger(BatchLookupResult.class);

    private final List<LotResult> lots;
    private final boolean success;
    private final String errorMessage;

    /**
     * Represents a single lot result with its wafers.
     */
    public record LotResult(String lotId, long lotKey, List<WaferResult> wafers) {
        public record WaferResult(String waferId, long waferKey, long pgKey, String ppid, Instant endTime, String fileName) {}
    }

    /**
     * Create a successful batch lookup result.
     *
     * @param lots list of lot results
     */
    public BatchLookupResult(List<LotResult> lots) {
        this.lots = lots;
        this.success = true;
        this.errorMessage = null;
    }

    /**
     * Create a failed batch lookup result.
     *
     * @param errorMessage error message describing the failure
     */
    public BatchLookupResult(String errorMessage) {
        this.lots = new ArrayList<>();
        this.success = false;
        this.errorMessage = errorMessage;
    }

    /**
     * Check if the batch lookup was successful.
     *
     * @return true if successful, false otherwise
     */
    public boolean isSuccess() {
        return success;
    }

    /**
     * Get the error message if the lookup failed.
     *
     * @return error message or null if successful
     */
    public String getErrorMessage() {
        return errorMessage;
    }

    /**
     * Get the list of lot results.
     *
     * @return list of lot results
     */
    public List<LotResult> getLots() {
        return lots;
    }

    /**
     * Map the batch response to individual record updates.
     *
     * <p>This method takes the original batch of records and creates a list of
     * RecordUpdate objects based on the batch response. Records that are found
     * in the response are marked as DONE, records that are not found are marked
     * as NOT_FOUND, and records that encounter errors are marked as ERROR.</p>
     *
     * @param originalRecords the original batch of records
     * @return list of record updates
     */
    public List<BatchResult.RecordUpdate> mapToRecordUpdates(List<StageRecord> originalRecords) {
        if (!success) {
            // If the batch API call failed, mark all records as ERROR
            List<BatchResult.RecordUpdate> updates = new ArrayList<>();
            for (StageRecord record : originalRecords) {
                updates.add(new BatchResult.RecordUpdate(
                        record.id(),
                        BatchResult.UpdateType.ERROR,
                        null,
                        null,
                        errorMessage,
                        null,
                        null,
                        null,
                        null
                ));
            }
            return updates;
        }

        // Build lookups:
        // - waferId -> wafer entries (can have multiple entries per wafer_id with different pg/ppid/end_time)
        // - lotId -> all wafer entries under that lot
        Map<String, List<LotResult.WaferResult>> waferLookup = new HashMap<>();
        Map<String, List<LotResult.WaferResult>> lotLookup = new HashMap<>();
        for (LotResult lot : lots) {
            String lotKey = lot.lotId() == null ? null : lot.lotId().toUpperCase();
            if (lotKey != null) {
                lotLookup.computeIfAbsent(lotKey, k -> new ArrayList<>()).addAll(lot.wafers());
            }
            for (LotResult.WaferResult wafer : lot.wafers()) {
                if (wafer.waferId() != null) {
                    waferLookup.computeIfAbsent(wafer.waferId().toUpperCase(), k -> new ArrayList<>()).add(wafer);
                }
            }
        }

        // Map each original record to an update
        List<BatchResult.RecordUpdate> updates = new ArrayList<>();
        for (StageRecord record : originalRecords) {
            String recordWafer = record.wafer() != null ? record.wafer().toUpperCase() : null;
            String recordLot = record.lot() != null ? record.lot().toUpperCase() : null;

            List<LotResult.WaferResult> candidates = null;
            if (recordWafer != null && !recordWafer.isBlank()) {
                candidates = waferLookup.get(recordWafer);
            }
            if ((candidates == null || candidates.isEmpty()) && recordLot != null && !recordLot.isBlank()) {
                candidates = lotLookup.get(recordLot);
            }

            LotResult.WaferResult waferResult = selectBestCandidate(candidates, record.endTime());

            if (waferResult != null) {
                // Wafer found - mark as DONE
                updates.add(new BatchResult.RecordUpdate(
                        record.id(),
                        BatchResult.UpdateType.COMPLETED,
                        waferResult.waferKey(),
                        waferResult.pgKey(),
                        null,
                        record.lot(),
                        waferResult.waferId(),
                        waferResult.fileName(),
                        null
                ));
            } else {
                // Wafer not found in Exensio response
                updates.add(new BatchResult.RecordUpdate(
                        record.id(),
                        BatchResult.UpdateType.NOT_FOUND,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null
                ));
            }
        }

        return updates;
    }

    private LotResult.WaferResult selectBestCandidate(List<LotResult.WaferResult> candidates, Instant targetEndTime) {
        if (candidates == null || candidates.isEmpty()) return null;
        if (targetEndTime == null) return candidates.get(0);

        LotResult.WaferResult best = null;
        long bestDelta = Long.MAX_VALUE;
        for (LotResult.WaferResult c : candidates) {
            if (c == null) continue;
            if (c.endTime() == null) {
                if (best == null) best = c;
                continue;
            }
            long delta = Math.abs(Duration.between(targetEndTime, c.endTime()).getSeconds());
            if (best == null || delta < bestDelta) {
                best = c;
                bestDelta = delta;
            }
        }
        return best;
    }

    /**
     * Find a specific wafer result by wafer ID.
     *
     * @param lotResult the lot result to search
     * @param waferId the wafer ID to find
     * @return the wafer result or null if not found
     */
    private BatchLookupResult.LotResult.WaferResult findWaferResult(
            BatchLookupResult.LotResult lotResult, String waferId) {
        for (BatchLookupResult.LotResult.WaferResult wafer : lotResult.wafers()) {
            if (wafer.waferId().equalsIgnoreCase(waferId)) {
                return wafer;
            }
        }
        return null;
    }

    /**
     * Parse a JSON response string into a BatchLookupResult.
     *
     * @param jsonResponse the JSON response from the Exensio batch API
     * @param objectMapper the Jackson ObjectMapper
     * @return parsed BatchLookupResult
     */
    public static BatchLookupResult parse(String jsonResponse, ObjectMapper objectMapper) {
        try {
            JsonNode root = objectMapper.readTree(jsonResponse);
            JsonNode lotsNode = root.path("lots");

            if (!lotsNode.isArray()) {
                return new BatchLookupResult("Response missing 'lots' array");
            }

            List<LotResult> lotResults = new ArrayList<>();
            for (JsonNode lotNode : lotsNode) {
                String lotId = lotNode.path("lot_id").asText(null);
                long lotKey = lotNode.path("lot_key").asLong(0);
                JsonNode wafersNode = lotNode.path("wafers");

                if (!wafersNode.isArray()) {
                    log.warn("Lot missing 'wafers' array, skipping");
                    continue;
                }

                List<LotResult.WaferResult> waferResults = new ArrayList<>();
                for (JsonNode waferNode : wafersNode) {
                    String waferId = waferNode.path("wafer_id").asText(null);
                    long waferKey = waferNode.path("wafer_key").asLong(0);
                    long pgKey = waferNode.path("pg_key").asLong(0);
                    String ppid = waferNode.path("ppid").asText(null);
                    Instant endTime = parseInstantSafe(waferNode.path("end_time").asText(null));
                    String fileName = waferNode.path("file_name").asText(null);

                    if (waferId != null && waferKey > 0) {
                        waferResults.add(new LotResult.WaferResult(waferId, waferKey, pgKey, ppid, endTime, fileName));
                    }
                }

                lotResults.add(new LotResult(lotId, lotKey, waferResults));
            }

            return new BatchLookupResult(lotResults);

        } catch (Exception e) {
            log.warn("Failed to parse batch lookup response: {}", e.getMessage());
            return new BatchLookupResult("Parse error: " + e.getMessage());
        }
    }

    /**
     * Build a batch request body for the Exensio batch API.
     *
     * @param records the batch of records to include
     * @param objectMapper the Jackson ObjectMapper
     * @return ObjectNode containing the request body
     */
    public static ObjectNode buildRequestBody(List<StageRecord> records, ObjectMapper objectMapper) {
        ObjectNode body = objectMapper.createObjectNode();
        boolean allWafersBlank = records.stream().allMatch(r -> r.wafer() == null || r.wafer().isBlank());
        body.put("pgc_key", allWafersBlank ? 2 : 1);

        // Extract unique lot IDs
        ArrayNode lotIds = body.putArray("lot_ids");
        for (StageRecord record : records) {
            if (!lotIds.toString().contains(record.lot())) {
                lotIds.add(record.lot());
            }
        }

        // Extract unique wafer IDs
        ArrayNode waferIds = body.putArray("wafer_ids");
        for (StageRecord record : records) {
            if (record.wafer() != null && !record.wafer().isBlank() && !waferIds.toString().contains(record.wafer())) {
                waferIds.add(record.wafer());
            }
        }

        return body;
    }

    private static Instant parseInstantSafe(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Instant.parse(value);
        } catch (Exception e) {
            return null;
        }
    }
}
