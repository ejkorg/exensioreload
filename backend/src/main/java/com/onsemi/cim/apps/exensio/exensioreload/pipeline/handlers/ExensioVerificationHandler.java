package com.onsemi.cim.apps.exensio.exensioreload.pipeline.handlers;

import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.onsemi.cim.apps.exensio.exensioreload.pipeline.StageHandler;
import com.onsemi.cim.apps.exensio.exensioreload.pipeline.StageType;
import com.onsemi.cim.apps.exensio.exensioreload.stage.StageRecord;

/**
 * Stage handler that delegates Exensio verification to the ExensioLoadMonitor scheduled service.
 * 
 * <p>This is a delegator/no-op handler that returns NOT_FOUND for all checks. The actual Exensio
 * verification is performed asynchronously by the ExensioLoadMonitor scheduled service which polls
 * for Exensio API verification results on a separate schedule.</p>
 * 
 * <p>The handler's role is to:</p>
 * <ul>
 *   <li>Identify when a record reaches the Exensio stage in the pipeline</li>
 *   <li>Signal to the orchestrator that Exensio verification is delegated (by returning NOT_FOUND)</li>
 *   <li>Allow ExensioLoadMonitor to handle verification independently</li>
 * </ul>
 * 
 * <p>Requirements: 8.2, 10.3</p>
 */
@Component
public class ExensioVerificationHandler implements StageHandler {
    private static final Logger log = LoggerFactory.getLogger(ExensioVerificationHandler.class);

    @Override
    public StageType getStageType() {
        return StageType.EXENSIO;
    }

    /**
     * Delegate Exensio verification to the ExensioLoadMonitor scheduled service.
     * 
     * <p>This handler always returns NOT_FOUND to indicate that Exensio verification is not
     * performed synchronously by the orchestrator. Instead, the ExensioLoadMonitor scheduled
     * service handles Exensio API verification asynchronously on its own schedule.</p>
     * 
     * <p>Flow:</p>
     * <ol>
     *   <li>Log that Exensio verification is delegated to ExensioLoadMonitor</li>
     *   <li>Return NOT_FOUND to keep record in EXENSIO_MONITORING status</li>
     *   <li>ExensioLoadMonitor will independently check Exensio API and update record when complete</li>
     *   <li>Once ExensioLoadMonitor updates the record, next pipeline check will see completion</li>
     * </ol>
     * 
     * <p>Requirements: 8.2, 10.3</p>
     * 
     * @param record the stage record to check (contains lot, wafer, identifiers)
     * @param stageConfig configuration map for this stage (unused - all Exensio config in ExensioLoadMonitor)
     * @return StageResult.notFound() to indicate verification is delegated to ExensioLoadMonitor
     */
    @Override
    public StageResult checkCompletion(StageRecord record, Map<String, Object> stageConfig) {
        String traceId = UUID.randomUUID().toString();

        log.debug("Exensio verification delegated to ExensioLoadMonitor for record id={}, " +
                "lot={}, wafer={}, site={}, traceId={}",
            record.id(), record.lot(), record.wafer(), record.site(), traceId);

        // Return NOT_FOUND to signal that verification is in progress via ExensioLoadMonitor
        // The orchestrator will keep the record in EXENSIO_MONITORING status and retry on next poll
        return StageResult.notFound(traceId);
    }
}
