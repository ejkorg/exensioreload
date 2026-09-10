package com.onsemi.cim.apps.exensio.exensioreload.pipeline;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable configuration for a pipeline at a specific site.
 * Parsed from the "pipeline" section in dbconnections.yml
 */
public record PipelineConfig(
    String site,
    List<StageDefinition> stages,
    Map<String, StageDefinition> stagesByName
) {
    public PipelineConfig {
        Objects.requireNonNull(site, "site must not be null");
        Objects.requireNonNull(stages, "stages must not be null");
        Objects.requireNonNull(stagesByName, "stagesByName must not be null");
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
