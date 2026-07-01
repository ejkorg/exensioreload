package com.onsemi.cim.apps.exensio.exensioreload.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

import com.onsemi.cim.apps.exensio.exensioreload.config.CpElasticsearchProperties;
import com.onsemi.cim.apps.exensio.exensioreload.config.ExensioProperties;
import com.onsemi.cim.apps.exensio.exensioreload.config.PpLogDbProperties;
import com.onsemi.cim.apps.exensio.exensioreload.stage.StageCompletionMonitor;

class StagePipelinePolicyTest {

    // ── existing routing tests (fixed to pass PpLogDbProperties) ─────────────

    @Test
    void afterCpQueueConsumption_noIntegrations_completesImmediately() {
        StagePipelinePolicy policy = policy(false, false, false);
        assertEquals(StageCompletionMonitor.NONE, policy.afterCpQueueConsumption());
    }

    @Test
    void afterCpQueueConsumption_exensioOnly_usesExensioApi() {
        StagePipelinePolicy policy = policy(false, false, true);
        assertEquals(StageCompletionMonitor.EXENSIO_API, policy.afterCpQueueConsumption());
    }

    @Test
    void afterCpQueueConsumption_elasticsearchPreferredOverExensio() {
        StagePipelinePolicy policy = policy(true, false, true);
        assertEquals(StageCompletionMonitor.ELASTICSEARCH, policy.afterCpQueueConsumption());
    }

    @Test
    void afterCpEnrichmentSuccess_exensioDisabled_completesImmediately() {
        StagePipelinePolicy policy = policy(true, false, false);
        assertEquals(StageCompletionMonitor.NONE, policy.afterCpEnrichmentSuccess());
    }

    @Test
    void afterCpEnrichmentSuccess_exensioEnabled_usesExensioApi() {
        StagePipelinePolicy policy = policy(true, false, true);
        assertEquals(StageCompletionMonitor.EXENSIO_API, policy.afterCpEnrichmentSuccess());
    }

    // ── pp_log routing tests (Requirements 3.1, 3.2, 3.3) ───────────────────

    /** Req 3.1: pp_log only (no ES) → route to CpLogMonitor */
    @Test
    void afterCpQueueConsumption_ppLogOnly_routesToCpLogMonitor() {
        StagePipelinePolicy policy = policy(false, true, false);
        assertEquals(StageCompletionMonitor.ELASTICSEARCH, policy.afterCpQueueConsumption());
    }

    /** Req 3.1: pp_log only, with Exensio also enabled → CpLogMonitor still wins */
    @Test
    void afterCpQueueConsumption_ppLogOnlyWithExensio_routesToCpLogMonitor() {
        StagePipelinePolicy policy = policy(false, true, true);
        assertEquals(StageCompletionMonitor.ELASTICSEARCH, policy.afterCpQueueConsumption());
    }

    /** Req 3.2: no ES, no pp_log, Exensio enabled → EXENSIO_API */
    @Test
    void afterCpQueueConsumption_neitherEsNorPpLog_exensioEnabled_usesExensioApi() {
        StagePipelinePolicy policy = policy(false, false, true);
        assertEquals(StageCompletionMonitor.EXENSIO_API, policy.afterCpQueueConsumption());
    }

    /** Req 3.3: nothing configured → NONE */
    @Test
    void afterCpQueueConsumption_nothingConfigured_returnsNone() {
        StagePipelinePolicy policy = policy(false, false, false);
        assertEquals(StageCompletionMonitor.NONE, policy.afterCpQueueConsumption());
    }

    /** Property 2: when both ES and pp_log are unavailable, must NOT route to CpLogMonitor */
    @Test
    void afterCpQueueConsumption_neitherEsNorPpLog_neverRoutesToCpLogMonitor() {
        // with Exensio
        assertNotEquals(StageCompletionMonitor.ELASTICSEARCH,
                policy(false, false, true).afterCpQueueConsumption());
        // without Exensio
        assertNotEquals(StageCompletionMonitor.ELASTICSEARCH,
                policy(false, false, false).afterCpQueueConsumption());
    }

    /** Req 3.4: isPpLogEnabled() delegates correctly */
    @Test
    void isPpLogEnabled_reflectsPpLogAvailability() {
        assertEquals(true,  policy(false, true,  false).isPpLogEnabled());
        assertEquals(false, policy(false, false, false).isPpLogEnabled());
    }

    // ── helper ───────────────────────────────────────────────────────────────

    /**
     * @param es      whether cp.elasticsearch.url is set
     * @param ppLog   whether refdb.pplog is enabled+configured
     * @param exensio whether exensio is enabled+configured
     */
    private static StagePipelinePolicy policy(boolean es, boolean ppLog, boolean exensio) {
        CpElasticsearchProperties esProps = new CpElasticsearchProperties();
        if (es) {
            esProps.setUrl("https://es.example:9200");
        }

        PpLogDbProperties ppLogProps = new PpLogDbProperties();
        if (ppLog) {
            ppLogProps.setHost("pplog-db.example.com");
            ppLogProps.setEnabled(true);
        } else {
            ppLogProps.setEnabled(false);
        }

        ExensioProperties exProps = new ExensioProperties(esProps);
        exProps.setEnabled(exensio);
        if (exensio) {
            exProps.setQaUrl("https://exensio.example");
        }

        return new StagePipelinePolicy(esProps, exProps, ppLogProps);
    }
}
