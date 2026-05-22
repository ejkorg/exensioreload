package com.onsemi.cim.apps.exensio.exensioreload.service;

import com.onsemi.cim.apps.exensio.exensioreload.config.CpElasticsearchProperties;
import com.onsemi.cim.apps.exensio.exensioreload.config.ExensioProperties;
import com.onsemi.cim.apps.exensio.exensioreload.stage.StageCompletionMonitor;
import org.springframework.stereotype.Component;

/**
 * Capability-based routing for the staging completion pipeline.
 *
 * <p>Priority after CP queue consumption (industry-standard fallback chain):</p>
 * <ol>
 *   <li>Elasticsearch configured → verify CP outcome via ES logs</li>
 *   <li>Else Exensio API configured → verify load via Exensio lot-wafer lookup</li>
 *   <li>Else → treat CP consumption as terminal success</li>
 * </ol>
 */
@Component
public class StagePipelinePolicy {

    private final CpElasticsearchProperties esProperties;
    private final ExensioProperties exensioProperties;

    public StagePipelinePolicy(CpElasticsearchProperties esProperties,
                                 ExensioProperties exensioProperties) {
        this.esProperties = esProperties;
        this.exensioProperties = exensioProperties;
    }

    /**
     * Next monitor once {@code id_metadata|id_data} disappears from the sender queue
     * (CP has consumed the staged payload).
     */
    public StageCompletionMonitor afterCpQueueConsumption() {
        if (esProperties.isConfigured()) {
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
}
