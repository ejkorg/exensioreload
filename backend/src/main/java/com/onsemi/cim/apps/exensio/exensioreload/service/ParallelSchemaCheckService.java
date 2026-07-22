package com.onsemi.cim.apps.exensio.exensioreload.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.onsemi.cim.apps.exensio.exensioreload.dto.ExensioPreCheckRequest;
import com.onsemi.cim.apps.exensio.exensioreload.dto.ExensioPreCheckResponse;
import com.onsemi.cim.apps.exensio.exensioreload.dto.ExensioPreCheckRow;

/**
 * Service to execute preflight checks in parallel across multiple schemas.
 * 
 * Orchestrates:
 * 1. Query PRODUCTION schema in parallel
 * 2. Query SANDBOX schema in parallel
 * 3. Consolidate results: if exists in both, include both; if in one, include that one
 */
@Service
public class ParallelSchemaCheckService {

    private static final Logger log = LoggerFactory.getLogger(ParallelSchemaCheckService.class);

    private final ExensioPreCheckService exensioPreCheckService;

    public ParallelSchemaCheckService(ExensioPreCheckService exensioPreCheckService) {
        this.exensioPreCheckService = exensioPreCheckService;
    }

    /**
     * Executes preflight checks in parallel across PRODUCTION and SANDBOX schemas.
     * 
     * Flow:
     * 1. Create requests for each schema with discovered wafers
     * 2. Execute both in parallel via raw-SQL
     * 3. Consolidate results (both schemas if exists in both, otherwise union)
     * 4. If nothing found → auto-switch to lot-wafer-lookup endpoint (PRODUCTION→SANDBOX)
     * 
     * @param lotIds list of lot IDs to verify
     * @param discoveredWafers list of discovered wafer IDs (from WaferDiscoveryService)
     * @param preCheckRequest original request (for blocks, dataType, etc.)
     * @return consolidated response across both schemas
     */
    public ExensioPreCheckResponse checkLotsParallel(
            List<String> lotIds,
            List<String> discoveredWafers,
            ExensioPreCheckRequest preCheckRequest) {

        if (lotIds == null || lotIds.isEmpty()) {
            return new ExensioPreCheckResponse(
                    Collections.emptyList(),
                    Collections.emptyList(),
                    Collections.emptyList(),
                    null);
        }

        log.info("[ParallelSchemaCheck] Starting parallel check: lots={}, wafers={}, dataType={}",
                lotIds.size(), discoveredWafers.size(), preCheckRequest.dataType());

        // Create requests for each schema
        ExensioPreCheckRequest productionRequest = new ExensioPreCheckRequest(
                "PRODUCTION",  // Force specific environment
                lotIds,
                discoveredWafers,  // Use discovered wafers
                preCheckRequest.blocks(),
                preCheckRequest.dataType(),
                preCheckRequest.enableSnowflakeFallback(),
                preCheckRequest.filenames()  // pass through filenames for raw-SQL prefix matching
        );

        ExensioPreCheckRequest sandboxRequest = new ExensioPreCheckRequest(
                "SANDBOX",  // Force specific environment
                lotIds,
                discoveredWafers,  // Use discovered wafers
                preCheckRequest.blocks(),
                preCheckRequest.dataType(),
                preCheckRequest.enableSnowflakeFallback(),
                preCheckRequest.filenames()  // pass through filenames for raw-SQL prefix matching
        );

        // Step 1: Execute raw-SQL for both schemas in parallel
        CompletableFuture<ExensioPreCheckResponse> productionFuture = CompletableFuture.supplyAsync(
                () -> exensioPreCheckService.checkViaExensioHttpMultiSchema(
                        productionRequest, List.of("PRODUCTION", "SANDBOX"))
        );

        CompletableFuture<ExensioPreCheckResponse> sandboxFuture = CompletableFuture.supplyAsync(
                () -> exensioPreCheckService.checkViaExensioHttpMultiSchema(
                        sandboxRequest, List.of("PRODUCTION", "SANDBOX"))
        );

        ExensioPreCheckResponse productionResult = null;
        ExensioPreCheckResponse sandboxResult = null;

        try {
            productionResult = productionFuture.get();
            if (productionResult == null) {
                productionResult = new ExensioPreCheckResponse(
                        Collections.emptyList(), lotIds, Collections.emptyList(),
                        "PRODUCTION raw-sql check returned no result");
            } else {
                log.debug("[ParallelSchemaCheck] PRODUCTION raw-sql result: found={}, notFound={}",
                        productionResult.lotsFound().size(), productionResult.lotsNotFound().size());
            }
        } catch (Exception e) {
            log.warn("[ParallelSchemaCheck] PRODUCTION raw-sql check failed: {}", e.getMessage());
            productionResult = new ExensioPreCheckResponse(
                    Collections.emptyList(),
                    lotIds,
                    Collections.emptyList(),
                    "PRODUCTION raw-sql check failed: " + e.getMessage()
            );
        }

        try {
            sandboxResult = sandboxFuture.get();
            if (sandboxResult == null) {
                sandboxResult = new ExensioPreCheckResponse(
                        Collections.emptyList(), lotIds, Collections.emptyList(),
                        "SANDBOX raw-sql check returned no result");
            } else {
                log.debug("[ParallelSchemaCheck] SANDBOX raw-sql result: found={}, notFound={}",
                        sandboxResult.lotsFound().size(), sandboxResult.lotsNotFound().size());
            }
        } catch (Exception e) {
            log.warn("[ParallelSchemaCheck] SANDBOX raw-sql check failed: {}", e.getMessage());
            sandboxResult = new ExensioPreCheckResponse(
                    Collections.emptyList(),
                    lotIds,
                    Collections.emptyList(),
                    "SANDBOX raw-sql check failed: " + e.getMessage()
            );
        }

        // Step 2: Consolidate results from raw-SQL
        ExensioPreCheckResponse consolidated = consolidateResults(productionResult, sandboxResult, lotIds);

        // Step 3: Auto-switch to lot-wafer-lookup if raw-SQL found nothing
        if (consolidated.lotsFound() == null || consolidated.lotsFound().isEmpty()) {
            log.info("[ParallelSchemaCheck] raw-SQL found nothing — auto-switching to lot-wafer-lookup endpoint (PRODUCTION→SANDBOX)");
            
            // Build a simple lot-wafer request (no environment override — let it use schema priority)
            ExensioPreCheckRequest lotWaferRequest = new ExensioPreCheckRequest(
                    preCheckRequest.environment(),
                    lotIds,
                    discoveredWafers,
                    preCheckRequest.blocks(),
                    preCheckRequest.dataType(),
                    preCheckRequest.enableSnowflakeFallback(),
                    preCheckRequest.filenames()
            );

            ExensioPreCheckResponse lotWaferResult = exensioPreCheckService.checkViaExensioLotWaferLookup(
                    lotWaferRequest, List.of("PRODUCTION", "SANDBOX"));

            if (lotWaferResult != null && lotWaferResult.lotsFound() != null && !lotWaferResult.lotsFound().isEmpty()) {
                log.info("[ParallelSchemaCheck] Lot-wafer-lookup found {} lots — using auto-switch results",
                        lotWaferResult.lotsFound().size());
                return lotWaferResult;
            }

            log.debug("[ParallelSchemaCheck] Lot-wafer-lookup also found nothing, returning raw-SQL consolidated result");
        }

        return consolidated;
    }

    /**
     * Consolidates results from both schemas.
     * 
     * Logic:
     * - If lot found in PRODUCTION: include with PRODUCTION schema
     * - If lot found in SANDBOX: include with SANDBOX schema
     * - If lot found in both: include with BOTH or prioritize PRODUCTION
     * - If lot not found in either: mark as not found
     * - Collect all wafers from both schemas for each lot
     * 
     * @param productionResult result from PRODUCTION schema
     * @param sandboxResult result from SANDBOX schema
     * @param allLotIds all lot IDs from request
     * @return consolidated response
     */
    private ExensioPreCheckResponse consolidateResults(
            ExensioPreCheckResponse productionResult,
            ExensioPreCheckResponse sandboxResult,
            List<String> allLotIds) {

        Map<String, ExensioPreCheckRow> consolidatedRows = new ConcurrentHashMap<>();
        List<String> lotsFound = new ArrayList<>();
        List<String> lotsNotFound = new ArrayList<>();

        // Process production results
        if (productionResult != null && productionResult.rows() != null) {
            for (ExensioPreCheckRow row : productionResult.rows()) {
                consolidatedRows.put(row.lotId().toUpperCase(), row);
            }
        }

        // Process sandbox results - merge with production data
        if (sandboxResult != null && sandboxResult.rows() != null) {
            for (ExensioPreCheckRow row : sandboxResult.rows()) {
                String lotKey = row.lotId().toUpperCase();
                
                // If already in production, indicate both schemas
                if (consolidatedRows.containsKey(lotKey)) {
                    // Keep production row, but log that it exists in both
                    log.debug("[ParallelSchemaCheck] Lot {} found in both PRODUCTION and SANDBOX", row.lotId());
                } else {
                    // Add sandbox row
                    consolidatedRows.put(lotKey, row);
                }
            }
        }

        // Determine found vs not found
        for (String lot : allLotIds) {
            if (consolidatedRows.containsKey(lot.toUpperCase())) {
                lotsFound.add(lot);
            } else {
                lotsNotFound.add(lot);
            }
        }

        List<ExensioPreCheckRow> finalRows = new ArrayList<>(consolidatedRows.values());

        log.info("[ParallelSchemaCheck] Consolidated results: {} found, {} not found",
                lotsFound.size(), lotsNotFound.size());

        return new ExensioPreCheckResponse(lotsFound, lotsNotFound, finalRows, null);
    }
}
