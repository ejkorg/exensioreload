package com.onsemi.cim.apps.exensio.exensioreload.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.onsemi.cim.apps.exensio.exensioreload.stage.StageRecord;

/**
 * Background job that silently retries manual verification records against Exensio.
 * 
 * When records timeout and move to COMPLETED_MANUAL_VERIFICATION_REQUIRED, this job
 * periodically checks them against the Exensio API. If data is now found in Exensio,
 * records are automatically promoted to COMPLETED.
 * 
 * This provides a "silent auto-completion" mechanism that reduces manual verification burden
 * while keeping the database and UI synchronized in real-time via SSE events.
 */
@Service
public class ManualVerificationRetryService {
    private static final Logger log = LoggerFactory.getLogger(ManualVerificationRetryService.class);

    private final RefDbService refDbService;
    private final ExensioClient exensioClient;

    public ManualVerificationRetryService(
            RefDbService refDbService,
            ExensioClient exensioClient) {
        this.refDbService = refDbService;
        this.exensioClient = exensioClient;
    }

    /**
     * Silently retry manual verification records against Exensio.
     * Runs every 5 minutes by default (configurable via app.manual-verification.retry-interval-ms).
     * 
     * For each COMPLETED_MANUAL_VERIFICATION_REQUIRED record:
     * 1. Query Exensio API using lotWaferLookup for lot-wafer data
     * 2. If found (ExensioLotWaferResult.Found), mark as COMPLETED and emit SSE event
     * 3. If error detected (via queryRawDataLoadErrors), mark as LOAD_FAILED with details
     * 4. If not found, leave as-is (will retry on next cycle)
     */
    @Scheduled(fixedDelayString = "${app.manual-verification.retry-interval-ms:300000}")
    public void silentRetryManualVerification() {
        try {
            log.debug("Manual verification retry cycle started");
            
            // 1. Fetch all records in COMPLETED_MANUAL_VERIFICATION_REQUIRED state
            List<StageRecord> records;
            try {
                records = refDbService.listRecords(null, null, "COMPLETED_MANUAL_VERIFICATION_REQUIRED", Integer.MAX_VALUE);
            } catch (Exception e) {
                log.warn("Failed to fetch manual verification records — skipping retry cycle: {}", e.getMessage());
                return;
            }

            if (records.isEmpty()) {
                log.debug("No manual verification records — nothing to retry");
                return;
            }

            log.info("Manual verification retry: checking {} record(s) against Exensio", records.size());

            int completedCount = 0;
            int failedCount = 0;
            String traceId = java.util.UUID.randomUUID().toString();

            // 2. Check each record against Exensio
            for (StageRecord record : records) {
                try {
                    String lot = record.lot();
                    String wafer = record.wafer();
                    
                    if (lot == null || lot.isBlank()) {
                        log.debug("Skipping record id={} - no lot ID", record.id());
                        continue;
                    }

                    // Query Exensio silently (no loud logging)
                    ExensioLotWaferResult result = exensioClient.lotWaferLookup(
                            lot, wafer, record.endTime(), null, null, record.filename(), record.metadataId(), record.dataId());
                    
                    if (result instanceof ExensioLotWaferResult.Found found) {
                        // Data found in Exensio - auto-complete this record
                        refDbService.markCompletedFromExensio(
                                record,
                                found.waferKey(),
                                found.pgKey(),
                                found.schema()
                        );
                        
                        completedCount++;
                        log.info("[ManualVerificationRetry] Auto-completed lot={} wafer={} schema={} (id={})",
                                lot, wafer, found.schema(), record.id());
                        
                    } else if (result instanceof ExensioLotWaferResult.Error) {
                        // Error from Exensio - check if it's a load error
                        // Verify it's not just "not found" - check for actual errors
                        Map<String, StageRecord> lotMap = new HashMap<>();
                        lotMap.put(lot, record);
                        Map<String, ExensioClient.ExensioLoadError> loadErrors = 
                                exensioClient.queryRawDataLoadErrors(lotMap, traceId);
                        
                        if (loadErrors != null && !loadErrors.isEmpty()) {
                            String lotKey = lot.toUpperCase();
                            if (loadErrors.containsKey(lotKey)) {
                                ExensioClient.ExensioLoadError loadError = loadErrors.get(lotKey);
                                String errorMsg = String.format("[Exensio Load Error] %s (code: %d)",
                                        loadError.fullErrorMessage(), loadError.errorCode());
                                
                                refDbService.markLoadFailed(record, errorMsg);
                                failedCount++;
                                log.info("[ManualVerificationRetry] Marked as failed lot={} wafer={} (error: {}) (id={})",
                                        lot, wafer, loadError.errorCode(), record.id());
                            }
                        }
                    }
                    
                } catch (Exception e) {
                    log.debug("[ManualVerificationRetry] Silent query failed for record id={}: {}",
                            record.id(), e.getMessage());
                }
            }

            log.info("Manual verification retry cycle complete: {} auto-completed, {} marked failed, {} still pending",
                    completedCount, failedCount, records.size() - completedCount - failedCount);
            
        } catch (Exception e) {
            log.error("Manual verification retry cycle failed", e);
        }
    }
}
