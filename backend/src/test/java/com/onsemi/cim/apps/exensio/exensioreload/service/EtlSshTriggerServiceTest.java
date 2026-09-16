package com.onsemi.cim.apps.exensio.exensioreload.service;

import com.onsemi.cim.apps.exensio.exensioreload.config.CrontabJob;
import com.onsemi.cim.apps.exensio.exensioreload.config.EtlServerConfig;
import com.onsemi.cim.apps.exensio.exensioreload.config.EtlServerConfigLoader;
import com.onsemi.cim.apps.exensio.exensioreload.config.EtlTriggerProperties;
import com.onsemi.cim.apps.exensio.exensioreload.config.ExternalDbConfig;
import com.onsemi.cim.apps.exensio.exensioreload.dto.CrontabDiscoveryResult;
import com.onsemi.cim.apps.exensio.exensioreload.pipeline.PipelineConfig;
import com.onsemi.cim.apps.exensio.exensioreload.pipeline.PipelineConfigLoader;
import com.onsemi.cim.apps.exensio.exensioreload.pipeline.StageDefinition;
import com.onsemi.cim.apps.exensio.exensioreload.pipeline.StageType;
import com.onsemi.cim.apps.exensio.exensioreload.repository.IdempotencyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("EtlSshTriggerService Queue-Aware Tests")
class EtlSshTriggerServiceTest {

    private EtlTriggerProperties properties;
    private EtlServerConfigLoader configLoader;
    private CrontabExtractor extractor;
    private SenderPortExtractor senderPortExtractor;
    private CrontabJobMatcher matcher;
    private AuditService auditService;
    private IdempotencyRepository idempotencyRepo;
    private PipelineConfigLoader pipelineConfigLoader;
    private ExternalDbConfig externalDbConfig;
    private RefDbService refDbService;

    private EtlSshTriggerService service;

    @BeforeEach
    void setUp() {
        properties = new EtlTriggerProperties();
        properties.setEnabled(true);
        properties.setDryRun(true); // dry-run mode prevents real SSH

        configLoader = mock(EtlServerConfigLoader.class);
        extractor = mock(CrontabExtractor.class);
        senderPortExtractor = mock(SenderPortExtractor.class);
        matcher = mock(CrontabJobMatcher.class);
        auditService = mock(AuditService.class);
        idempotencyRepo = mock(IdempotencyRepository.class);
        pipelineConfigLoader = mock(PipelineConfigLoader.class);
        externalDbConfig = mock(ExternalDbConfig.class);
        refDbService = mock(RefDbService.class);

        service = new EtlSshTriggerService(
                properties, configLoader, extractor, senderPortExtractor,
                matcher, auditService, idempotencyRepo, pipelineConfigLoader,
                externalDbConfig, refDbService
        );
    }

    private PipelineConfig createPipeline(String key, String site, Integer senderId, int rerunMins) {
        StageDefinition s = new StageDefinition("cp", StageType.CP, List.of(), Map.of());
        return new PipelineConfig(
                key, site, site, 60170, "CPYQSP", senderId, rerunMins,
                List.of(s), Map.of("cp", s)
        );
    }

    @Test
    @DisplayName("getSenderQueueCount queries external DATAPORT_OWNER.DTP_SENDER_QUEUE_ITEM strictly for senderId")
    void getSenderQueueCount_queriesExternalDatabaseForSenderId() throws Exception {
        PipelineConfig pipeline = createPipeline("CEBU-CP-DEFAULT", "CEBU-PROD", 1, 5);

        Connection mockConn = mock(Connection.class);
        PreparedStatement mockPs = mock(PreparedStatement.class);
        ResultSet mockRs = mock(ResultSet.class);

        when(externalDbConfig.getConnection("CEBU-PROD")).thenReturn(mockConn);
        when(mockConn.prepareStatement(contains("DATAPORT_OWNER.DTP_SENDER_QUEUE_ITEM"))).thenReturn(mockPs);
        when(mockPs.executeQuery()).thenReturn(mockRs);
        when(mockRs.next()).thenReturn(true);
        when(mockRs.getInt(1)).thenReturn(4);

        int count = service.getSenderQueueCount(pipeline);

        assertThat(count).isEqualTo(4);
        verify(mockPs).setInt(1, 1);
        verify(refDbService, never()).countQueuedForSiteAndSender(anyString(), any());
    }

    @Test
    @DisplayName("getSenderQueueCount falls back to local SENDER_STAGE by senderId when external DB fails")
    void getSenderQueueCount_fallsBackToLocalSenderStageForSenderId() throws Exception {
        PipelineConfig pipeline = createPipeline("CEBU-CP-DEFAULT", "CEBU-PROD", 1, 5);

        // External DB throws exception
        when(externalDbConfig.getConnection("CEBU-PROD")).thenThrow(new RuntimeException("External DB unreachable"));
        when(refDbService.countQueuedForSiteAndSender("CEBU-PROD", 1)).thenReturn(3);

        int count = service.getSenderQueueCount(pipeline);

        assertThat(count).isEqualTo(3);
        verify(refDbService).countQueuedForSiteAndSender("CEBU-PROD", 1);
    }

    @Test
    @DisplayName("triggerIfQueued should skip trigger when sender queue is empty")
    void triggerIfQueued_skipsWhenQueueEmpty() {
        PipelineConfig pipeline = createPipeline("CEBU-CP-DEFAULT", "CEBU-PROD", 1, 5);
        when(pipelineConfigLoader.getPipeline("CEBU-CP-DEFAULT")).thenReturn(Optional.of(pipeline));
        when(refDbService.countQueuedForSiteAndSender("CEBU-PROD", 1)).thenReturn(0);

        TriggerResult result = service.triggerIfQueued("CEBU-CP-DEFAULT", "test-user");

        assertThat(result.getStatus()).isEqualTo("success");
        assertThat(result.getMessage()).contains("empty (0 items)");
        assertThat(result.getMessage()).contains("skipped");
    }

    @Test
    @DisplayName("triggerIfQueued should execute trigger when items remain in sender queue table")
    void triggerIfQueued_triggersWhenItemsRemain() throws Exception {
        PipelineConfig pipeline = createPipeline("CEBU-CP-DEFAULT", "CEBU-PROD", 1, 5);
        when(pipelineConfigLoader.getPipeline("CEBU-CP-DEFAULT")).thenReturn(Optional.of(pipeline));
        when(refDbService.countQueuedForSiteAndSender("CEBU-PROD", 1)).thenReturn(3);

        EtlServerConfig srv = new EtlServerConfig();
        srv.setName("CEBU-PROD");
        srv.setHost("pbxprdjsrv04.onsemi.com");
        srv.setSshPort(22);
        srv.setSocketPort(60170);
        when(configLoader.getConfigByName("CEBU-PROD")).thenReturn(srv);

        CrontabJob job = new CrontabJob();
        job.setCommand("/opt/cp/bin/cp_loader.sh -p 60170 -c CPYQSP");
        job.setSchedule("*/5 * * * *");
        when(extractor.extract(any())).thenReturn(List.of(job));
        when(matcher.match(any(), eq(60170), eq("CPYQSP"))).thenReturn(job);

        TriggerResult result = service.triggerIfQueued("CEBU-CP-DEFAULT", "test-user");

        assertThat(result.getStatus()).isEqualTo("success");
        assertThat(result.getMessage()).contains("[DRY RUN] Would execute");
        assertThat(result.getCommand()).isEqualTo("/opt/cp/bin/cp_loader.sh -p 60170 -c CPYQSP");
    }

    @Test
    @DisplayName("getQueueStatus returns accurate queue count with senderId")
    void getQueueStatus_returnsQueueDetails() {
        PipelineConfig pipeline = createPipeline("CEBU-CP-DEFAULT", "CEBU-PROD", 1, 5);
        when(pipelineConfigLoader.getPipeline("CEBU-CP-DEFAULT")).thenReturn(Optional.of(pipeline));
        when(refDbService.countQueuedForSiteAndSender("CEBU-PROD", 1)).thenReturn(5);

        Map<String, Object> status = service.getQueueStatus("CEBU-CP-DEFAULT");

        assertThat(status.get("pipelineKey")).isEqualTo("CEBU-CP-DEFAULT");
        assertThat(status.get("site")).isEqualTo("CEBU-PROD");
        assertThat(status.get("senderId")).isEqualTo(1);
        assertThat(status.get("queuedItemCount")).isEqualTo(5);
        assertThat(status.get("hasQueuedItems")).isEqualTo(true);
    }
}
