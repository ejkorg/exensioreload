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
     * 2. Execute both in parallel
     * 3. Consolidate results (both schemas if exists in both, otherwise union)
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
                preCheckRequest.enableSnowflakeFallback()
        );

        ExensioPreCheckRequest sandboxRequest = new ExensioPreCheckRequest(
                "SANDBOX",  // Force specific environment
                lotIds,
                discoveredWafers,  // Use discovered wafers
                preCheckRequest.blocks(),
                preCheckRequest.dataType(),
                preCheckRequest.enableSnowflakeFallback()
        );

        // Execute both schemas in parallel
        CompletableFuture<ExensioPreCheckResponse> productionFuture = CompletableFuture.supplyAsync(
                () -> exensioPreCheckService.check(productionRequest)
        );

        CompletableFuture<ExensioPreCheckResponse> sandboxFuture = CompletableFuture.supplyAsync(
                () -> exensioPreCheckService.check(sandboxRequest)
        );

        // Wait for both to complete
        ExensioPreCheckResponse productionResult = null;
        ExensioPreCheckResponse sandboxResult = null;

        try {
            productionResult = productionFuture.get();
            log.debug("[ParallelSchemaCheck] PRODUCTION result: found={}, notFound={}",
                    productionResult.lotsFound().size(), productionResult.lotsNotFound().size());
        } catch (Exception e) {
            log.warn("[ParallelSchemaCheck] PRODUCTION check failed: {}", e.getMessage());
            productionResult = new ExensioPreCheckResponse(
                    Collections.emptyList(),
                    lotIds,
                    Collections.emptyList(),
                    "PRODUCTION check failed: " + e.getMessage()
            );
        }

        try {
            sandboxResult = sandboxFuture.get();
            log.debug("[ParallelSchemaCheck] SANDBOX result: found={}, notFound={}",
                    sandboxResult.lotsFound().size(), sandboxResult.lotsNotFound().size());
        } catch (Exception e) {
            log.warn("[ParallelSchemaCheck] SANDBOX check failed: {}", e.getMessage());
            sandboxResult = new ExensioPreCheckResponse(
                    Collections.emptyList(),
                    lotIds,
                    Collections.emptyList(),
                    "SANDBOX check failed: " + e.getMessage()
            );
        }

        // Consolidate results
        return consolidateResults(productionResult, sandboxResult, lotIds);
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
