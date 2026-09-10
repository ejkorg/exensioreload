package com.onsemi.cim.apps.exensio.exensioreload.pipeline;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.onsemi.cim.apps.exensio.exensioreload.config.ExternalDbConfig;

/**
 * Loads and parses pipeline configurations from dbconnections.yml.
 * Each site can optionally define a "pipeline" section containing stage definitions and dependencies.
 * 
 * This service is responsible for:
 * - Parsing YAML pipeline configurations per site
 * - Validating dependency graphs for circular dependencies
 * - Verifying all stage references are valid
 * - Building lookup maps for efficient stage access
 */
@Component
public class PipelineConfigLoader {

    private static final Logger log = LoggerFactory.getLogger(PipelineConfigLoader.class);

    private final ExternalDbConfig externalDbConfig;

    public PipelineConfigLoader(ExternalDbConfig externalDbConfig) {
        this.externalDbConfig = externalDbConfig;
    }

    /**
     * Load pipeline configuration for a specific site from dbconnections.yml via ExternalDbConfig
     * 
     * Returns Optional.empty() if the site has no pipeline section defined (indicating legacy behavior)
     * Throws PipelineConfigException if the configuration is invalid
     * 
     * This method integrates with ExternalDbConfig to load the actual dbconnections configuration.
     * 
     * Requirements: 1.1, 1.2, 1.4
     * 
     * @param site the site name (e.g., "CEBU-PROD")
     * @return Optional containing PipelineConfig if pipeline section exists and is valid, Optional.empty() otherwise
     * @throws PipelineConfigException if configuration is invalid
     */
    public Optional<PipelineConfig> loadPipelineConfig(String site) throws PipelineConfigException {
        if (site == null || site.isBlank()) {
            return Optional.empty();
        }

        // Get the site configuration from ExternalDbConfig
        Map<String, Object> siteConfig = externalDbConfig.getConfigForSite(site);
        if (siteConfig == null) {
            log.debug("No configuration found for site: {}", site);
            return Optional.empty();
        }

        // Build a map with just this site for parsing
        Map<String, Object> yamlData = new HashMap<>();
        yamlData.put(site.toUpperCase(Locale.ROOT), siteConfig);

        // Parse using the internal method
        return loadPipelineConfig(site, yamlData);
    }

    /**
     * Load pipeline configuration for a specific site from raw YAML data
     * 
     * Returns Optional.empty() if the site has no pipeline section defined (indicating legacy behavior)
     * Throws PipelineConfigException if the configuration is invalid
     * 
     * Requirements: 1.1, 1.2, 1.4
     * 
     * @param site the site name (e.g., "CEBU-PROD")
     * @param yamlData the raw YAML map loaded from dbconnections.yml
     * @return Optional containing PipelineConfig if pipeline section exists and is valid, Optional.empty() otherwise
     * @throws PipelineConfigException if configuration is invalid
     */
    private Optional<PipelineConfig> loadPipelineConfig(String site, Map<String, Object> yamlData) 
            throws PipelineConfigException {
        
        if (site == null || site.isBlank() || yamlData == null) {
            return Optional.empty();
        }

        String normalizedSite = site.trim().toUpperCase(Locale.ROOT);
        Object siteEntry = yamlData.get(normalizedSite);
        
        if (!(siteEntry instanceof Map<?, ?>)) {
            return Optional.empty();
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> siteConfig = (Map<String, Object>) siteEntry;
        
        Object pipelineSection = siteConfig.get("pipeline");
        if (pipelineSection == null) {
            // No pipeline section = legacy behavior
            return Optional.empty();
        }

        if (!(pipelineSection instanceof Map<?, ?>)) {
            throw new PipelineConfigException("Pipeline section for site '" + normalizedSite 
                + "' must be a map, got: " + pipelineSection.getClass().getSimpleName());
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> pipelineMap = (Map<String, Object>) pipelineSection;
        
        // Parse stages array
        Object stagesObj = pipelineMap.get("stages");
        if (stagesObj == null) {
            throw new PipelineConfigException("Pipeline for site '" + normalizedSite 
                + "' must have a 'stages' array");
        }

        if (!(stagesObj instanceof List<?>)) {
            throw new PipelineConfigException("Pipeline 'stages' for site '" + normalizedSite 
                + "' must be an array, got: " + stagesObj.getClass().getSimpleName());
        }

        List<?> stagesList = (List<?>) stagesObj;
        List<StageDefinition> stages = new ArrayList<>();
        Map<String, StageDefinition> stagesByName = new LinkedHashMap<>();

        // Parse each stage definition
        for (int i = 0; i < stagesList.size(); i++) {
            Object stageObj = stagesList.get(i);
            if (!(stageObj instanceof Map<?, ?>)) {
                throw new PipelineConfigException("Stage at index " + i + " for site '" + normalizedSite 
                    + "' must be a map, got: " + (stageObj == null ? "null" : stageObj.getClass().getSimpleName()));
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> stageMap = (Map<String, Object>) stageObj;
            StageDefinition stageDef = parseStageDefinition(stageMap, normalizedSite, i);
            
            if (stagesByName.containsKey(stageDef.name())) {
                throw new PipelineConfigException("Duplicate stage name '" + stageDef.name() 
                    + "' in pipeline for site '" + normalizedSite + "'");
            }
            
            stages.add(stageDef);
            stagesByName.put(stageDef.name(), stageDef);
        }

        if (stages.isEmpty()) {
            throw new PipelineConfigException("Pipeline for site '" + normalizedSite 
                + "' must have at least one stage");
        }

        PipelineConfig config = new PipelineConfig(normalizedSite, stages, stagesByName);
        
        // Validate the configuration
        validatePipelineConfig(config);
        
        return Optional.of(config);
    }

    /**
     * Parse a single stage definition from YAML
     * 
     * Requirements: 1.1, 1.2, 1.4
     */
    private StageDefinition parseStageDefinition(Map<String, Object> stageMap, String site, int index) 
            throws PipelineConfigException {
        
        Object nameObj = stageMap.get("name");
        if (nameObj == null || !(nameObj instanceof String) || ((String) nameObj).isBlank()) {
            throw new PipelineConfigException("Stage at index " + index + " for site '" + site 
                + "' must have a 'name' (non-empty string)");
        }
        String name = ((String) nameObj).trim();

        Object typeObj = stageMap.get("type");
        if (typeObj == null || !(typeObj instanceof String) || ((String) typeObj).isBlank()) {
            throw new PipelineConfigException("Stage '" + name + "' for site '" + site 
                + "' must have a 'type' (non-empty string)");
        }
        
        StageType type;
        try {
            type = StageType.valueOf(((String) typeObj).trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new PipelineConfigException("Stage '" + name + "' for site '" + site 
                + "' has invalid type '" + typeObj + "'. Valid types: " 
                + String.join(", ", getStageTypeNames()), e);
        }

        // Parse dependsOn array (optional)
        List<String> dependsOn = new ArrayList<>();
        Object dependsOnObj = stageMap.get("dependsOn");
        if (dependsOnObj != null) {
            if (!(dependsOnObj instanceof List<?>)) {
                throw new PipelineConfigException("Stage '" + name + "' for site '" + site 
                    + "' dependsOn must be an array, got: " + dependsOnObj.getClass().getSimpleName());
            }
            List<?> depList = (List<?>) dependsOnObj;
            for (int i = 0; i < depList.size(); i++) {
                Object dep = depList.get(i);
                if (!(dep instanceof String) || ((String) dep).isBlank()) {
                    throw new PipelineConfigException("Stage '" + name + "' for site '" + site 
                        + "' dependsOn[" + i + "] must be a non-empty string");
                }
                dependsOn.add(((String) dep).trim());
            }
        }

        // Parse config map (optional)
        Map<String, Object> config = new HashMap<>();
        Object configObj = stageMap.get("config");
        if (configObj != null) {
            if (!(configObj instanceof Map<?, ?>)) {
                throw new PipelineConfigException("Stage '" + name + "' for site '" + site 
                    + "' config must be a map, got: " + configObj.getClass().getSimpleName());
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> configMap = (Map<String, Object>) configObj;
            config.putAll(configMap);
        }

        return new StageDefinition(name, type, Collections.unmodifiableList(dependsOn), 
            Collections.unmodifiableMap(config));
    }

    /**
     * Validate pipeline configuration for circular dependencies and invalid stage references.
     * 
     * Uses topological sort to detect circular dependencies.
     * Throws PipelineConfigException if validation fails.
     * 
     * Requirements: 1.5
     */
    public void validatePipelineConfig(PipelineConfig config) throws PipelineConfigException {
        // Check all stage references exist
        for (StageDefinition stage : config.stages()) {
            for (String depName : stage.dependsOn()) {
                if (config.findStage(depName) == null) {
                    throw new PipelineConfigException("Stage '" + stage.name() + "' in pipeline for site '" 
                        + config.site() + "' depends on non-existent stage '" + depName + "'");
                }
            }
        }

        // Check for circular dependencies using topological sort
        detectCircularDependencies(config);
    }

    /**
     * Detect circular dependencies using Kahn's algorithm for topological sorting.
     * 
     * If the algorithm cannot process all nodes, a circular dependency exists.
     * 
     * Requirements: 1.5
     */
    private void detectCircularDependencies(PipelineConfig config) throws PipelineConfigException {
        Map<String, Integer> inDegree = new HashMap<>();
        Map<String, List<String>> dependents = new HashMap<>();

        // Initialize
        for (StageDefinition stage : config.stages()) {
            inDegree.put(stage.name(), stage.dependsOn().size());
            dependents.put(stage.name(), new ArrayList<>());
        }

        // Build dependents map (reverse of dependencies)
        for (StageDefinition stage : config.stages()) {
            for (String dep : stage.dependsOn()) {
                dependents.get(dep).add(stage.name());
            }
        }

        // Kahn's algorithm
        Queue<String> queue = new java.util.LinkedList<>();
        
        // Find all nodes with no incoming edges
        for (String stageName : inDegree.keySet()) {
            if (inDegree.get(stageName) == 0) {
                queue.add(stageName);
            }
        }

        int processedCount = 0;
        while (!queue.isEmpty()) {
            String currentStage = queue.poll();
            processedCount++;

            // For each dependent of current stage
            for (String dependent : dependents.get(currentStage)) {
                inDegree.put(dependent, inDegree.get(dependent) - 1);
                if (inDegree.get(dependent) == 0) {
                    queue.add(dependent);
                }
            }
        }

        // If we couldn't process all stages, there's a cycle
        if (processedCount < config.stages().size()) {
            List<String> cycleStages = new ArrayList<>();
            for (String stageName : inDegree.keySet()) {
                if (inDegree.get(stageName) > 0) {
                    cycleStages.add(stageName);
                }
            }
            throw new PipelineConfigException("Circular dependency detected in pipeline for site '" 
                + config.site() + "'. Stages involved: " + String.join(", ", cycleStages));
        }
    }

    /**
     * Get all valid stage type names for error messages
     */
    private List<String> getStageTypeNames() {
        List<String> names = new ArrayList<>();
        for (StageType type : StageType.values()) {
            names.add(type.name());
        }
        return names;
    }
}
