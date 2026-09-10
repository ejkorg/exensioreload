package com.onsemi.cim.apps.exensio.exensioreload.pipeline.handlers;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.onsemi.cim.apps.exensio.exensioreload.config.PpLogDbProperties;
import com.onsemi.cim.apps.exensio.exensioreload.pipeline.StageHandler;
import com.onsemi.cim.apps.exensio.exensioreload.pipeline.StageType;
import com.onsemi.cim.apps.exensio.exensioreload.service.RefDbService;
import com.onsemi.cim.apps.exensio.exensioreload.stage.StageRecord;

/**
 * Stage handler that checks PP_LOG (Parametric Program Log) enrichment completion via Oracle database query.
 * 
 * Queries the refdb.pp_log table to determine if PP_LOG has successfully processed a file,
 * and returns the appropriate stage result (completed, not found, or error).
 * 
 * Requirements: 4.1, 8.2
 */
@Component
public class PpLogCompletionHandler implements StageHandler {
    private static final Logger log = LoggerFactory.getLogger(PpLogCompletionHandler.class);

    private final RefDbService refDbService;
    private final PpLogDbProperties ppLogDbProperties;

    public PpLogCompletionHandler(RefDbService refDbService,
                                   PpLogDbProperties ppLogDbProperties) {
        this.refDbService = refDbService;
        this.ppLogDbProperties = ppLogDbProperties;
    }

    @Override
    public StageType getStageType() {
        return StageType.PPLOG;
    }

    /**
     * Check if PP_LOG enrichment has completed for the given record by querying refdb.pp_log.
     * 
     * <p>Extracts configuration parameters from stageConfig:</p>
     * <ul>
     *   <li>{@code lookbackBufferSeconds} — buffer window for timestamp lookback (defaults to 900)</li>
     * </ul>
     * 
     * <p>Query flow:</p>
     * <ol>
     *   <li>Check if PP_LOG is available via ppLogDbProperties.isPpLogAvailable()</li>
     *   <li>Extract lookbackBufferSeconds from stageConfig</li>
     *   <li>Calculate searchStart = record.endTime - lookbackBufferSeconds</li>
     *   <li>Call refDbService.queryPpLog() with lot, enrichmentStartedAt, filename</li>
     *   <li>Map result to StageResult based on whether entry was found</li>
     * </ol>
     * 
     * Requirements: 4.1, 4.2, 4.3, 4.4, 4.5
     * 
     * @param record the stage record to check (contains lot, wafer, identifiers, timestamps)
     * @param stageConfig configuration map for this stage (lookbackBufferSeconds)
     * @return StageResult.completed() if PP_LOG entry found, notFound() if no entry yet, error() on exception
     */
    @Override
    public StageResult checkCompletion(StageRecord record, Map<String, Object> stageConfig) {
        String traceId = UUID.randomUUID().toString();

        try {
            // Early exit if PP_LOG is not configured (Requirement 4.1)
            if (!ppLogDbProperties.isPpLogAvailable()) {
                log.debug("PP_LOG is not available/configured for record id={}, site={}, traceId={}",
                    record.id(), record.site(), traceId);
                return StageResult.error(traceId,
                    "PP_LOG database not configured for this deployment");
            }

            // Extract lookbackBufferSeconds from stageConfig (default 900 seconds) (Requirement 4.2)
            Integer lookbackSeconds = extractIntValue(stageConfig, "lookbackBufferSeconds", 900);

            // Calculate searchStart = record.endTime - lookbackBufferSeconds (Requirement 4.2)
            Instant searchStart = record.endTime() != null
                ? record.endTime().minusSeconds(lookbackSeconds)
                : record.createdAt().minusSeconds(lookbackSeconds);

            // For pp_log query, we use enrichmentStartedAt if available, otherwise searchStart
            Instant enrichmentStartedAt = record.enrichmentStartedAt() != null
                ? record.enrichmentStartedAt()
                : searchStart;

            log.debug("PP_LOG completion check for record id={}, lot={}, wafer={}, " +
                    "enrichmentStartedAt={}, filename={}, site={}, traceId={}",
                record.id(), record.lot(), record.wafer(), enrichmentStartedAt,
                record.filename(), record.site(), traceId);

            // Query refdb.pp_log table for matching record (Requirement 4.2, 4.3)
            RefDbService.PpLogRow ppLogRow = refDbService.queryPpLog(
                record.lot(),
                enrichmentStartedAt,
                record.filename()
            );

            // Map result to StageResult (Requirement 4.1, 4.2, 4.3, 4.4, 4.5)
            if (ppLogRow != null && ppLogRow.outputDirectory() != null && !ppLogRow.outputDirectory().isBlank()) {
                // PP_LOG entry found — enrichment succeeded
                log.info("PP_LOG enrichment success detected for record id={}, lot={}, " +
                        "outputDirectory={}, processCode={}, traceId={}",
                    record.id(), record.lot(), ppLogRow.outputDirectory(), ppLogRow.processCode(), traceId);

                Map<String, Object> metadata = new HashMap<>();
                metadata.put("ppLogOutputDirectory", ppLogRow.outputDirectory());
                metadata.put("ppLogMessage", ppLogRow.logMessage());
                metadata.put("ppLogProcessCode", ppLogRow.processCode());

                return StageResult.completed(traceId, metadata);
            } else {
                // PP_LOG entry not found yet — no error, just keep waiting (Requirement 4.4)
                log.debug("PP_LOG entry not found yet for record id={}, lot={}, traceId={}",
                    record.id(), record.lot(), traceId);
                return StageResult.notFound(traceId);
            }

        } catch (Exception e) {
            // Database query error — return error result (Requirement 4.5)
            log.warn("PP_LOG completion check failed for record id={}: {} (traceId={})",
                record.id(), e.getMessage(), traceId, e);
            return StageResult.error(traceId,
                "PP_LOG query error: " + e.getMessage());
        }
    }

    /**
     * Extract an integer configuration value from the stage config map with fallback to default.
     */
    private Integer extractIntValue(Map<String, Object> config, String key, Integer defaultValue) {
        if (config == null || config.isEmpty()) {
            return defaultValue;
        }
        Object value = config.get(key);
        if (value instanceof Integer i) {
            return i;
        }
        if (value instanceof Number n) {
            return n.intValue();
        }
        return defaultValue;
    }
}
