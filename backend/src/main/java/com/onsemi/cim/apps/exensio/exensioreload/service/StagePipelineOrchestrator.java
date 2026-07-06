package com.onsemi.cim.apps.exensio.exensioreload.service;

import com.onsemi.cim.apps.exensio.exensioreload.stage.StageCompletionMonitor;
import com.onsemi.cim.apps.exensio.exensioreload.stage.StageRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Applies {@link StagePipelinePolicy} decisions to {@link RefDbService} status transitions.
 * Single entry point for post-CP routing (DRY across queue monitor and session refresh).
 */
@Service
public class StagePipelineOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(StagePipelineOrchestrator.class);

    private final StagePipelinePolicy policy;
    private final RefDbService refDbService;

    public StagePipelineOrchestrator(StagePipelinePolicy policy, RefDbService refDbService) {
        this.policy = policy;
        this.refDbService = refDbService;
    }

    /**
     * Called when staged rows are no longer present in {@code DTP_SENDER_QUEUE_ITEM}.
     */
    public void onCpQueueConsumed(List<StageRecord> records, String site, int senderId) {
        if (records == null || records.isEmpty()) {
            return;
        }
        StageCompletionMonitor monitor = policy.afterCpQueueConsumption();
        switch (monitor) {
            case ELASTICSEARCH -> {
                refDbService.markEnrichmentRecords(records);
                log.info("CP consumed {} record(s) for site {} sender {} — awaiting Elasticsearch verification",
                        records.size(), site, senderId);
            }
            case EXENSIO_API -> {
                refDbService.markExensioMonitoringPending(records);
                log.info("CP consumed {} record(s) for site {} sender {} — awaiting Exensio API verification (ES disabled)",
                        records.size(), site, senderId);
            }
            case NONE -> {
                refDbService.markCompletedRecords(records);
                log.info("CP consumed {} record(s) for site {} sender {} — marked DONE (no ES/Exensio monitors)",
                        records.size(), site, senderId);
            }
        }
    }

    /**
     * Called when {@link CpLogMonitor} finds a successful CP log entry.
     */
    public void onCpEnrichmentSuccess(StageRecord record, String outputPath, String outputTarget) {
        if (record == null) {
            return;
        }
        if (policy.afterCpEnrichmentSuccess() == StageCompletionMonitor.EXENSIO_API) {
            refDbService.markExensioMonitoring(record, outputPath, outputTarget);
            log.info("CP ES success for record id={} — awaiting Exensio API verification", record.id());
        } else {
            refDbService.markCompletedFromCp(record, outputPath, outputTarget);
            log.info("CP ES success for record id={} — marked DONE (Exensio monitor disabled)", record.id());
        }
    }
}
