package com.onsemi.cim.apps.exensio.exensioreload.pipeline;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable configuration for a pipeline and its ETL orchestration setup.
 * Loaded primarily from etljobs.yml (with backward-compatible fallback to dbconnections.yml).
 */
public record PipelineConfig(
    String pipelineKey,
    String site,
    String server,
    Integer socketPort,
    String configName,
    Integer senderId,
    Integer rerunPeriodMinutes,
    List<StageDefinition> stages,
    Map<String, StageDefinition> stagesByName
) {
    public PipelineConfig {
        Objects.requireNonNull(pipelineKey, "pipelineKey must not be null");
        Objects.requireNonNull(site, "site must not be null");
        Objects.requireNonNull(stages, "stages must not be null");
        Objects.requireNonNull(stagesByName, "stagesByName must not be null");
        if (server == null || server.isBlank()) {
            server = site;
        }
        if (socketPort == null) {
            socketPort = 60170;
        }
        if (configName == null) {
            configName = "";
        }
        if (rerunPeriodMinutes == null) {
            rerunPeriodMinutes = 0;
        }
    }

    public PipelineConfig(String pipelineKey, String site, String server, Integer socketPort, String configName, Integer rerunPeriodMinutes, List<StageDefinition> stages, Map<String, StageDefinition> stagesByName) {
        this(pipelineKey, site, server, socketPort, configName, null, rerunPeriodMinutes, stages, stagesByName);
    }

    /**
     * Backward-compatible constructor for legacy code and existing tests.
     */
    public PipelineConfig(String site, List<StageDefinition> stages, Map<String, StageDefinition> stagesByName) {
        this(site, site, site, 60170, "", null, 0, stages, stagesByName);
    }

    /**
     * Find a stage by name
     */
    public StageDefinition findStage(String name) {
        return stagesByName.get(name);
    }

    /**
     * Check if pipeline has any stage dependencies
     */
    public boolean hasDependencies() {
        return stages.stream().anyMatch(s -> !s.dependsOn().isEmpty());
    }

    /**
     * Get the first stage in the pipeline
     */
    public StageDefinition getFirstStage() {
        return stages.isEmpty() ? null : stages.get(0);
    }

    /**
     * Get the next stage after a given stage
     */
    public StageDefinition getNextStage(String currentStageName) {
        for (int i = 0; i < stages.size(); i++) {
            if (stages.get(i).name().equals(currentStageName)) {
                return i + 1 < stages.size() ? stages.get(i + 1) : null;
            }
        }
        return null;
    }
}
