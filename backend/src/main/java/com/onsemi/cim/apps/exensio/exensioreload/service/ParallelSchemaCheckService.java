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
    private final com.onsemi.cim.apps.exensio.exensioreload.config.ExensioProperties exensioProperties;

    @org.springframework.beans.factory.annotation.Autowired
    public ParallelSchemaCheckService(
            ExensioPreCheckService exensioPreCheckService,
            @org.springframework.beans.factory.annotation.Autowired(required = false) com.onsemi.cim.apps.exensio.exensioreload.config.ExensioProperties exensioProperties) {
        this.exensioPreCheckService = exensioPreCheckService;
        this.exensioProperties = exensioProperties;
    }

    public ParallelSchemaCheckService(ExensioPreCheckService exensioPreCheckService) {
        this(exensioPreCheckService, null);
    }

    /**
     * Executes preflight checks in parallel across PRODUCTION and SANDBOX schemas.
     * 
     * Flow (aligned with ExensioLoadMonitor):
     * 1. Create requests for each schema with discovered wafers
     * 2. Execute both in parallel via lot-wafer-lookup endpoint (primary)
     * 3. Consolidate results (both schemas if exists in both, otherwise union)
     * 4. If nothing found and preferRawSql enabled (or as secondary fallback) → try raw-SQL
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

        try {
            ExensioPreCheckService.resolvePgcKey(preCheckRequest.dataType());
        } catch (IllegalArgumentException e) {
            return new ExensioPreCheckResponse(
                    Collections.emptyList(),
                    Collections.emptyList(),
                    Collections.emptyList(),
                    e.getMessage());
        }

        log.info("[ParallelSchemaCheck] Starting parallel check: lots={}, wafers={}, dataType={}",
                lotIds.size(), discoveredWafers != null ? discoveredWafers.size() : 0, preCheckRequest.dataType());

        // If preferRawSql is explicitly configured, run legacy raw-SQL first
        if (exensioProperties != null && exensioProperties.isPreferRawSql()) {
            log.debug("[ParallelSchemaCheck] PreferRawSql=true, running parallel raw-SQL query");
            ExensioPreCheckResponse rawSqlResult = checkLotsParallelRawSql(lotIds, discoveredWafers, preCheckRequest);
            if (rawSqlResult != null && rawSqlResult.lotsFound() != null && !rawSqlResult.lotsFound().isEmpty()) {
                return rawSqlResult;
            }
        }

        // Standard lot-wafer-lookup in parallel across PRODUCTION and SANDBOX (matching ExensioLoadMonitor)
        ExensioPreCheckRequest productionRequest = new ExensioPreCheckRequest(
                "PRODUCTION",
                lotIds,
                discoveredWafers,
                preCheckRequest.blocks(),
                preCheckRequest.dataType(),
                preCheckRequest.enableSnowflakeFallback(),
                preCheckRequest.filenames()
        );

        ExensioPreCheckRequest sandboxRequest = new ExensioPreCheckRequest(
                "SANDBOX",
                lotIds,
                discoveredWafers,
                preCheckRequest.blocks(),
                preCheckRequest.dataType(),
                preCheckRequest.enableSnowflakeFallback(),
                preCheckRequest.filenames()
        );

        CompletableFuture<ExensioPreCheckResponse> productionFuture = CompletableFuture.supplyAsync(
                () -> exensioPreCheckService.checkViaExensioLotWaferLookup(
                        productionRequest, List.of("PRODUCTION"))
        );

        CompletableFuture<ExensioPreCheckResponse> sandboxFuture = CompletableFuture.supplyAsync(
                () -> exensioPreCheckService.checkViaExensioLotWaferLookup(
                        sandboxRequest, List.of("SANDBOX"))
        );

        ExensioPreCheckResponse productionResult = null;
        ExensioPreCheckResponse sandboxResult = null;

        try {
            productionResult = productionFuture.get();
            if (productionResult != null) {
                log.debug("[ParallelSchemaCheck] PRODUCTION lot-wafer result: found={}, notFound={}",
                        productionResult.lotsFound().size(), productionResult.lotsNotFound().size());
            }
        } catch (Exception e) {
            log.warn("[ParallelSchemaCheck] PRODUCTION lot-wafer check failed: {}", e.getMessage());
        }

        try {
            sandboxResult = sandboxFuture.get();
            if (sandboxResult != null) {
                log.debug("[ParallelSchemaCheck] SANDBOX lot-wafer result: found={}, notFound={}",
                        sandboxResult.lotsFound().size(), sandboxResult.lotsNotFound().size());
            }
        } catch (Exception e) {
            log.warn("[ParallelSchemaCheck] SANDBOX lot-wafer check failed: {}", e.getMessage());
        }

        // Consolidate results from both schemas
        ExensioPreCheckResponse consolidated = consolidateResults(productionResult, sandboxResult, lotIds);

        // Fallback: If lot-wafer-lookup found nothing, try raw-SQL as secondary attempt
        if ((consolidated.lotsFound() == null || consolidated.lotsFound().isEmpty())
                && (exensioProperties == null || !exensioProperties.isPreferRawSql())) {
            log.debug("[ParallelSchemaCheck] Lot-wafer-lookup found nothing — attempting raw-SQL fallback");
            try {
                ExensioPreCheckResponse rawSqlResult = checkLotsParallelRawSql(lotIds, discoveredWafers, preCheckRequest);
                if (rawSqlResult != null && rawSqlResult.lotsFound() != null && !rawSqlResult.lotsFound().isEmpty()) {
                    log.info("[ParallelSchemaCheck] Raw-SQL fallback found {} lots", rawSqlResult.lotsFound().size());
                    return rawSqlResult;
                }
            } catch (Exception e) {
                log.debug("[ParallelSchemaCheck] Raw-SQL fallback failed: {}", e.getMessage());
            }
        }

        return consolidated;
    }

    /**
     * Executes legacy raw-SQL preflight checks in parallel across schemas.
     */
    private ExensioPreCheckResponse checkLotsParallelRawSql(
            List<String> lotIds,
            List<String> discoveredWafers,
            ExensioPreCheckRequest preCheckRequest) {

        ExensioPreCheckRequest productionRequest = new ExensioPreCheckRequest(
                "PRODUCTION",
                lotIds,
                discoveredWafers,
                preCheckRequest.blocks(),
                preCheckRequest.dataType(),
                preCheckRequest.enableSnowflakeFallback(),
                preCheckRequest.filenames()
        );

        ExensioPreCheckRequest sandboxRequest = new ExensioPreCheckRequest(
                "SANDBOX",
                lotIds,
                discoveredWafers,
                preCheckRequest.blocks(),
                preCheckRequest.dataType(),
                preCheckRequest.enableSnowflakeFallback(),
                preCheckRequest.filenames()
        );

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
        } catch (Exception e) {
            log.warn("[ParallelSchemaCheck] PRODUCTION raw-sql check failed: {}", e.getMessage());
        }

        try {
            sandboxResult = sandboxFuture.get();
        } catch (Exception e) {
            log.warn("[ParallelSchemaCheck] SANDBOX raw-sql check failed: {}", e.getMessage());
        }

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
