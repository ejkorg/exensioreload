package com.onsemi.cim.apps.exensio.exensioreload.service;

import com.onsemi.cim.apps.exensio.exensioreload.config.CpElasticsearchProperties;
import com.onsemi.cim.apps.exensio.exensioreload.config.ExensioProperties;
import com.onsemi.cim.apps.exensio.exensioreload.stage.StageCompletionMonitor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StagePipelinePolicyTest {

    @Test
    void afterCpQueueConsumption_noIntegrations_completesImmediately() {
        StagePipelinePolicy policy = policy(false, false);
        assertEquals(StageCompletionMonitor.NONE, policy.afterCpQueueConsumption());
    }

    @Test
    void afterCpQueueConsumption_exensioOnly_usesExensioApi() {
        StagePipelinePolicy policy = policy(false, true);
        assertEquals(StageCompletionMonitor.EXENSIO_API, policy.afterCpQueueConsumption());
    }

    @Test
    void afterCpQueueConsumption_elasticsearchPreferredOverExensio() {
        StagePipelinePolicy policy = policy(true, true);
        assertEquals(StageCompletionMonitor.ELASTICSEARCH, policy.afterCpQueueConsumption());
    }

    @Test
    void afterCpEnrichmentSuccess_exensioDisabled_completesImmediately() {
        StagePipelinePolicy policy = policy(true, false);
        assertEquals(StageCompletionMonitor.NONE, policy.afterCpEnrichmentSuccess());
    }

    @Test
    void afterCpEnrichmentSuccess_exensioEnabled_usesExensioApi() {
        StagePipelinePolicy policy = policy(true, true);
        assertEquals(StageCompletionMonitor.EXENSIO_API, policy.afterCpEnrichmentSuccess());
    }

    private static StagePipelinePolicy policy(boolean es, boolean exensio) {
        CpElasticsearchProperties esProps = new CpElasticsearchProperties();
        if (es) {
            esProps.setUrl("https://es.example:9200");
        }
        ExensioProperties exProps = new ExensioProperties();
        exProps.setEnabled(exensio);
        if (exensio) {
            exProps.setQaBaseUrl("https://exensio.example");
        }
        return new StagePipelinePolicy(esProps, exProps);
    }
}
