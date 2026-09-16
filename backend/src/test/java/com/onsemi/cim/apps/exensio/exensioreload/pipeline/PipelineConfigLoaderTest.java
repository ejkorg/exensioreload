package com.onsemi.cim.apps.exensio.exensioreload.pipeline;

import com.onsemi.cim.apps.exensio.exensioreload.config.ExternalDbConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("PipelineConfigLoader")
class PipelineConfigLoaderTest {

    private ExternalDbConfig mockExternalDbConfig;
    private PipelineConfigLoader loader;

    @BeforeEach
    void setUp() {
        mockExternalDbConfig = mock(ExternalDbConfig.class);
        loader = new PipelineConfigLoader(mockExternalDbConfig);
        loader.init(); // Loads classpath:etljobs.yml
    }

    @Test
    @DisplayName("should load pipelines from etljobs.yml successfully")
    void testLoadPipelinesFromEtlJobs() {
        List<PipelineConfig> allPipelines = loader.getAllPipelines();
        assertNotNull(allPipelines);
        assertFalse(allPipelines.isEmpty(), "Should have loaded pipelines from etljobs.yml");

        // Verify CEBU-CP-DEFAULT
        Optional<PipelineConfig> cebuOpt = loader.getPipeline("CEBU-CP-DEFAULT");
        assertTrue(cebuOpt.isPresent());
        PipelineConfig cebu = cebuOpt.get();
        assertEquals("CEBU-PROD", cebu.site());
        assertEquals("CEBU-PROD", cebu.server());
        assertEquals(60170, cebu.socketPort());
        assertEquals("CPYQSP", cebu.configName());
        assertEquals(5, cebu.rerunPeriodMinutes());
        assertEquals(3, cebu.stages().size());

        // Verify stage sequence
        assertEquals("cp", cebu.stages().get(0).name());
        assertEquals(StageType.CP, cebu.stages().get(0).type());
        assertEquals("pplog", cebu.stages().get(1).name());
        assertEquals(StageType.PPLOG, cebu.stages().get(1).type());
        assertTrue(cebu.stages().get(1).dependsOn().contains("cp"));
        assertEquals("exensio", cebu.stages().get(2).name());
        assertEquals(StageType.EXENSIO, cebu.stages().get(2).type());
        assertTrue(cebu.stages().get(2).dependsOn().contains("pplog"));
    }

    @Test
    @DisplayName("should find pipelines by site")
    void testGetPipelinesForSite() {
        List<PipelineConfig> cebuList = loader.getPipelinesForSite("CEBU-PROD");
        assertNotNull(cebuList);
        assertFalse(cebuList.isEmpty());
        assertEquals("CEBU-CP-DEFAULT", cebuList.get(0).pipelineKey());

        Optional<PipelineConfig> siteDefault = loader.loadPipelineConfig("CEBU-PROD");
        assertTrue(siteDefault.isPresent());
        assertEquals("CEBU-CP-DEFAULT", siteDefault.get().pipelineKey());
    }

    @Test
    @DisplayName("should fall back to ExternalDbConfig if site not in etljobs.yml")
    void testFallbackToExternalDbConfig() throws Exception {
        String site = "UNKNOWN-SITE";
        Map<String, Object> mockSiteConfig = Map.of(
                "pipeline", Map.of(
                        "stages", List.of(
                                Map.of("name", "cp", "type", "CP"),
                                Map.of("name", "exensio", "type", "EXENSIO", "dependsOn", List.of("cp"))
                        )
                )
        );
        when(mockExternalDbConfig.getConfigForSite(site)).thenReturn(mockSiteConfig);

        Optional<PipelineConfig> fallbackOpt = loader.loadPipelineConfig(site);
        assertTrue(fallbackOpt.isPresent());
        assertEquals(site, fallbackOpt.get().site());
        assertEquals(2, fallbackOpt.get().stages().size());
    }

    @Test
    @DisplayName("should detect circular dependencies in pipeline stages")
    void testCircularDependencyDetection() {
        StageDefinition s1 = new StageDefinition("stage1", StageType.CP, List.of("stage2"), Map.of());
        StageDefinition s2 = new StageDefinition("stage2", StageType.PPLOG, List.of("stage1"), Map.of());

        PipelineConfig circularConfig = new PipelineConfig(
                "CYCLE", "SITE1", "SRV1", 60170, "", 0,
                List.of(s1, s2),
                Map.of("stage1", s1, "stage2", s2)
        );

        assertThrows(PipelineConfigException.class, () -> {
            loader.validatePipelineConfig(circularConfig);
        });
    }
}
