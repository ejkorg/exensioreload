package com.onsemi.cim.apps.exensio.exensioreload.service;

import com.onsemi.cim.apps.exensio.exensioreload.config.CpElasticsearchProperties;
import com.onsemi.cim.apps.exensio.exensioreload.config.ExensioProperties;
import com.onsemi.cim.apps.exensio.exensioreload.config.PpLogDbProperties;
import com.onsemi.cim.apps.exensio.exensioreload.stage.StageCompletionMonitor;
import org.springframework.stereotype.Component;

/**
 * Capability-based routing for the staging completion pipeline.
 *
 * <p>Priority after CP queue consumption (industry-standard fallback chain):</p>
 * <ol>
 *   <li>Elasticsearch configured OR pp_log available → verify CP outcome via CpLogMonitor</li>
 *   <li>Else Exensio API configured → verify load via Exensio lot-wafer lookup</li>
 *   <li>Else → treat CP consumption as terminal success</li>
 * </ol>
 *
 * <p>Note: {@link StageCompletionMonitor#ELASTICSEARCH} means "hand to {@link CpLogMonitor}",
 * which already queries both ES and pp_log in parallel. Routing to it when only pp_log
 * is available is therefore correct — CpLogMonitor will skip the ES future automatically.</p>
 */
@Component
public class StagePipelinePolicy {

    private final CpElasticsearchProperties esProperties;
    private final ExensioProperties exensioProperties;
    private final PpLogDbProperties ppLogDbProperties;

    public StagePipelinePolicy(CpElasticsearchProperties esProperties,
                                 ExensioProperties exensioProperties,
                                 PpLogDbProperties ppLogDbProperties) {
        this.esProperties = esProperties;
        this.exensioProperties = exensioProperties;
        this.ppLogDbProperties = ppLogDbProperties;
    }

    /**
     * Next monitor once {@code id_metadata|id_data} disappears from the sender queue
     * (CP has consumed the staged payload).
     */
    public StageCompletionMonitor afterCpQueueConsumption() {
        // Route to CpLogMonitor if any enrichment source can resolve the record.
        // CpLogMonitor handles ES-only, pp_log-only, and both-available cases internally.
        if (esProperties.isConfigured() || ppLogDbProperties.isPpLogAvailable()) {
            return StageCompletionMonitor.ELASTICSEARCH;
        }
        if (exensioProperties.isConfigured()) {
            return StageCompletionMonitor.EXENSIO_API;
        }
        return StageCompletionMonitor.NONE;
    }

    /**
     * Next step after Elasticsearch reports a successful CP enrichment.
     */
    public StageCompletionMonitor afterCpEnrichmentSuccess() {
        if (exensioProperties.isConfigured()) {
            return StageCompletionMonitor.EXENSIO_API;
        }
        return StageCompletionMonitor.NONE;
    }

    public boolean isElasticsearchConfigured() {
        return esProperties.isConfigured();
    }

    public boolean isExensioConfigured() {
        return exensioProperties.isConfigured();
    }

    /** Returns true if pp_log queries are available for this deployment. */
    public boolean isPpLogEnabled() {
        return ppLogDbProperties.isPpLogAvailable();
    }
}
