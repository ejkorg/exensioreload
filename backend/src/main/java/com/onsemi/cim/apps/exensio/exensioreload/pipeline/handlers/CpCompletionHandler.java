package com.onsemi.cim.apps.exensio.exensioreload.pipeline.handlers;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.onsemi.cim.apps.exensio.exensioreload.config.CpElasticsearchProperties;
import com.onsemi.cim.apps.exensio.exensioreload.pipeline.StageHandler;
import com.onsemi.cim.apps.exensio.exensioreload.pipeline.StageType;
import com.onsemi.cim.apps.exensio.exensioreload.service.CpLogResult;
import com.onsemi.cim.apps.exensio.exensioreload.service.ElasticsearchLogService;
import com.onsemi.cim.apps.exensio.exensioreload.stage.StageRecord;

/**
 * Stage handler that checks CP (Command Processor) enrichment completion via Elasticsearch logs.
 * 
 * Queries the CP Elasticsearch index to determine if CP has successfully processed a file,
 * and returns the appropriate stage result (completed, not found, or error).
 * 
 * Requirements: 3.1, 8.2
 */
@Component
public class CpCompletionHandler implements StageHandler {
    private static final Logger log = LoggerFactory.getLogger(CpCompletionHandler.class);

    private final ElasticsearchLogService elasticsearchLogService;
    private final CpElasticsearchProperties cpProperties;

    public CpCompletionHandler(ElasticsearchLogService elasticsearchLogService,
                               CpElasticsearchProperties cpProperties) {
        this.elasticsearchLogService = elasticsearchLogService;
        this.cpProperties = cpProperties;
    }

    @Override
    public StageType getStageType() {
        return StageType.CP;
    }

    /**
     * Check if CP enrichment has completed for the given record by querying Elasticsearch.
     * 
     * <p>Extracts configuration parameters from stageConfig:</p>
     * <ul>
     *   <li>{@code indexPattern} — ES index pattern (defaults to configured value)</li>
     *   <li>{@code lookbackBufferSeconds} — buffer window for timestamp lookback (defaults to configured value)</li>
     * </ul>
     * 
     * <p>Query flow:</p>
     * <ol>
     *   <li>Extract indexPattern and lookbackBufferSeconds from stageConfig</li>
     *   <li>Calculate searchStart = record.endTime() - lookbackBufferSeconds</li>
     *   <li>Call elasticsearchLogService.findCpLog() with lot, wafer, metadataId, searchStart, site</li>
     *   <li>Map result to StageResult based on CpLogResult type</li>
     * </ol>
     * 
     * Requirements: 3.2, 3.3, 3.4, 7.1, 7.4
     * 
     * @param record the stage record to check (contains lot, wafer, identifiers, timestamps)
     * @param stageConfig configuration map for this stage (indexPattern, lookbackBufferSeconds)
     * @return StageResult.completed() if CP log found, notFound() if no log yet, error() on exception
     */
    @Override
    public StageResult checkCompletion(StageRecord record, Map<String, Object> stageConfig) {
        String traceId = UUID.randomUUID().toString();
        
        try {
            // Extract CP-specific configuration from stage config (Requirement 3.2)
            String indexPattern = extractConfigValue(stageConfig, "indexPattern", 
                cpProperties.getIndexPattern());
            Integer lookbackSeconds = extractIntValue(stageConfig, "lookbackBufferSeconds", 
                cpProperties.getLookbackBufferSeconds());

            // Calculate searchStart = record.endTime - lookbackBufferSeconds (Requirement 3.2)
            Instant searchStart = record.endTime() != null
                ? record.endTime().minusSeconds(lookbackSeconds)
                : record.createdAt().minusSeconds(lookbackSeconds);

            log.debug("CP completion check for record id={}, lot={}, wafer={}, metadataId={}, " +
                    "searchStart={}, site={}, traceId={}", 
                record.id(), record.lot(), record.wafer(), record.metadataId(), 
                searchStart, record.site(), traceId);

            // Query Elasticsearch for CP log (Requirement 3.3, 3.4)
            CpLogResult result = elasticsearchLogService.findCpLog(
                record.metadataId(),      // idFile
                record.dataId(),          // dataId  
                record.lot(),             // lot
                searchStart,              // since (lookback anchor)
                record.site(),            // site
                record.filename()         // filename for optional filtering
            );

            // Map CpLogResult to StageResult (Requirement 3.2, 3.3, 3.4, 7.1, 7.4)
            return mapCpLogResult(result, traceId);

        } catch (Exception e) {
            log.warn("CP completion check failed for record id={}: {} (traceId={})", 
                record.id(), e.getMessage(), traceId, e);
            return StageResult.error(traceId, 
                "CP completion check error: " + e.getMessage());
        }
    }

    /**
     * Map CpLogResult to StageResult based on the result type.
     * 
     * Requirements: 3.2, 3.3, 3.4, 7.1, 7.4
     */
    private StageResult mapCpLogResult(CpLogResult result, String traceId) {
        if (result instanceof CpLogResult.Success success) {
            // CP enrichment succeeded — return completed with metadata
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("cpOutputPath", success.outputPath());
            metadata.put("cpOutputTarget", success.outputTarget());
            metadata.put("cpLogTimestamp", success.logTimestamp());
            
            log.info("CP enrichment success detected: outputPath={}, target={}, traceId={}", 
                success.outputPath(), success.outputTarget(), traceId);
            
            return StageResult.completed(traceId, metadata);
        }

        if (result instanceof CpLogResult.NotFound notFound) {
            // CP log not found yet — no error, just keep waiting
            log.debug("CP log not found yet (traceId={})", traceId);
            return StageResult.notFound(traceId);
        }

        if (result instanceof CpLogResult.Failure failure) {
            // CP reported an error — return error result
            log.warn("CP enrichment error detected: {} (traceId={})", 
                failure.errorMessage(), traceId);
            return StageResult.error(traceId, 
                "CP enrichment error: " + failure.errorMessage());
        }

        // Fallback for unknown result type (should not happen with sealed interface)
        log.warn("Unknown CpLogResult type: {}", result.getClass().getName());
        return StageResult.error(traceId, 
            "Unknown CP log result type: " + result.getClass().getSimpleName());
    }

    /**
     * Extract a string configuration value from the stage config map with fallback to default.
     */
    private String extractConfigValue(Map<String, Object> config, String key, String defaultValue) {
        if (config == null || config.isEmpty()) {
            return defaultValue;
        }
        Object value = config.get(key);
        if (value instanceof String str) {
            return !str.isBlank() ? str : defaultValue;
        }
        return defaultValue;
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
