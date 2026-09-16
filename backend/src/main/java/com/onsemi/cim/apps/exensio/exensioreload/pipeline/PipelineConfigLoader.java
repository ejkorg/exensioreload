package com.onsemi.cim.apps.exensio.exensioreload.pipeline;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.onsemi.cim.apps.exensio.exensioreload.config.ExternalDbConfig;

/**
 * Loads and parses pipeline configurations from etljobs.yml (with backward-compatible
 * fallback to dbconnections.yml).
 * 
 * Each pipeline defines:
 * - pipelineKey (unique ID)
 * - site (references dbconnections.yml)
 * - server (references etlservers.yml)
 * - socketPort & configName (for crontab grep)
 * - rerunPeriodMinutes (optional rerun delay)
 * - stages & dependencies
 */
@Component
public class PipelineConfigLoader {

    private static final Logger log = LoggerFactory.getLogger(PipelineConfigLoader.class);
    private static final String ETL_JOBS_FILE = "classpath:etljobs.yml";

    private final ExternalDbConfig externalDbConfig;
    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
    private final Map<String, PipelineConfig> pipelinesByKey = new ConcurrentHashMap<>();
    private final Map<String, List<PipelineConfig>> pipelinesBySite = new ConcurrentHashMap<>();

    public PipelineConfigLoader(ExternalDbConfig externalDbConfig) {
        this.externalDbConfig = externalDbConfig;
    }

    @PostConstruct
    public void init() {
        loadPipelinesFromEtlJobs();
    }

    /**
     * Load pipeline definitions from etljobs.yml.
     */
    public synchronized void loadPipelinesFromEtlJobs() {
        pipelinesByKey.clear();
        pipelinesBySite.clear();

        ResourceLoader resourceLoader = new DefaultResourceLoader();
        Resource resource = resourceLoader.getResource(ETL_JOBS_FILE);
        if (!resource.exists()) {
            log.info("No {} found, pipeline definitions will fall back to dbconnections.yml", ETL_JOBS_FILE);
            return;
        }

        try (InputStream is = resource.getInputStream()) {
            Map<String, Object> root = yamlMapper.readValue(is, new TypeReference<Map<String, Object>>() {});
            if (root == null) {
                return;
            }

            Object pipelinesObj = root.get("pipelines");
            if (pipelinesObj instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> pipeMap = (Map<String, Object>) map;
                        try {
                            PipelineConfig cfg = parsePipelineFromMap(pipeMap);
                            pipelinesByKey.put(cfg.pipelineKey().toUpperCase(Locale.ROOT), cfg);
                            pipelinesBySite.computeIfAbsent(cfg.site().toUpperCase(Locale.ROOT), k -> new ArrayList<>()).add(cfg);
                            log.info("Loaded pipeline '{}' for site '{}' (server={}, socketPort={}, configName={})",
                                cfg.pipelineKey(), cfg.site(), cfg.server(), cfg.socketPort(), cfg.configName());
                        } catch (Exception ex) {
                            log.error("Failed to parse pipeline from etljobs.yml: {}", ex.getMessage(), ex);
                        }
                    }
                }
            }
            log.info("PipelineConfigLoader: loaded {} pipeline(s) across {} site(s) from {}",
                pipelinesByKey.size(), pipelinesBySite.size(), ETL_JOBS_FILE);
        } catch (Exception e) {
            log.warn("Failed to load pipelines from {}: {}", ETL_JOBS_FILE, e.getMessage());
        }
    }

    public List<PipelineConfig> getAllPipelines() {
        return List.copyOf(pipelinesByKey.values());
    }

    public Optional<PipelineConfig> getPipeline(String pipelineKey) {
        if (pipelineKey == null || pipelineKey.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(pipelinesByKey.get(pipelineKey.trim().toUpperCase(Locale.ROOT)));
    }

    public List<PipelineConfig> getPipelinesForSite(String site) {
        if (site == null || site.isBlank()) {
            return Collections.emptyList();
        }
        List<PipelineConfig> list = pipelinesBySite.get(site.trim().toUpperCase(Locale.ROOT));
        return list != null ? List.copyOf(list) : Collections.emptyList();
    }

    /**
     * Load pipeline configuration for a specific site. Checks etljobs.yml first,
     * then falls back to dbconnections.yml.
     */
    public Optional<PipelineConfig> loadPipelineConfig(String site) throws PipelineConfigException {
        if (site == null || site.isBlank()) {
            return Optional.empty();
        }

        String normalizedSite = site.trim().toUpperCase(Locale.ROOT);
        List<PipelineConfig> sitePipelines = pipelinesBySite.get(normalizedSite);
        if (sitePipelines != null && !sitePipelines.isEmpty()) {
            return Optional.of(sitePipelines.get(0));
        }

        // Get the site configuration from ExternalDbConfig (fallback)
        if (externalDbConfig != null) {
            Map<String, Object> siteConfig = externalDbConfig.getConfigForSite(site);
            if (siteConfig != null) {
                Map<String, Object> yamlData = new HashMap<>();
                yamlData.put(normalizedSite, siteConfig);
                return loadPipelineConfig(site, yamlData);
            }
        }

        log.debug("No pipeline configuration found for site: {}", site);
        return Optional.empty();
    }

    /**
     * Parse pipeline definition from etljobs.yml map.
     */
    private PipelineConfig parsePipelineFromMap(Map<String, Object> map) throws PipelineConfigException {
        String site = getString(map, "site", "");
        if (site.isBlank()) {
            throw new PipelineConfigException("Pipeline definition missing 'site'");
        }
        String normalizedSite = site.trim().toUpperCase(Locale.ROOT);
        String pipelineKey = getString(map, "pipelineKey", normalizedSite + "-PIPELINE");
        String server = getString(map, "server", normalizedSite);
        Integer socketPort = getInteger(map, "socketPort", 60170);
        String configName = getString(map, "configName", "");
        Integer senderId = getInteger(map, "senderId", null);
        Integer rerunPeriodMinutes = getInteger(map, "rerunPeriodMinutes", 0);

        Object stagesObj = map.get("stages");
        if (!(stagesObj instanceof List<?> stagesList) || stagesList.isEmpty()) {
            throw new PipelineConfigException("Pipeline '" + pipelineKey + "' must have a non-empty 'stages' array");
        }

        List<StageDefinition> stages = new ArrayList<>();
        Map<String, StageDefinition> stagesByName = new LinkedHashMap<>();

        for (int i = 0; i < stagesList.size(); i++) {
            Object stageObj = stagesList.get(i);
            if (!(stageObj instanceof Map<?, ?>)) {
                throw new PipelineConfigException("Stage at index " + i + " in pipeline '" + pipelineKey + "' must be a map");
            }
            @SuppressWarnings("unchecked")
            StageDefinition stageDef = parseStageDefinition((Map<String, Object>) stageObj, normalizedSite, i);
            if (stagesByName.containsKey(stageDef.name())) {
                throw new PipelineConfigException("Duplicate stage name '" + stageDef.name() + "' in pipeline '" + pipelineKey + "'");
            }
            stages.add(stageDef);
            stagesByName.put(stageDef.name(), stageDef);
        }

        PipelineConfig config = new PipelineConfig(pipelineKey, normalizedSite, server, socketPort, configName, senderId, rerunPeriodMinutes, stages, stagesByName);
        validatePipelineConfig(config);
        return config;
    }

    /**
     * Load pipeline configuration for a specific site from raw YAML data (dbconnections.yml legacy path)
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
            return Optional.empty();
        }

        if (!(pipelineSection instanceof Map<?, ?>)) {
            throw new PipelineConfigException("Pipeline section for site '" + normalizedSite 
                + "' must be a map, got: " + pipelineSection.getClass().getSimpleName());
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> pipelineMap = (Map<String, Object>) pipelineSection;
        
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

        String pipelineKey = getString(pipelineMap, "pipelineKey", normalizedSite + "-LEGACY");
        String server = getString(pipelineMap, "server", normalizedSite);
        Integer socketPort = getInteger(pipelineMap, "socketPort", 60170);
        String configName = getString(pipelineMap, "configName", "");
        Integer rerunPeriodMinutes = getInteger(pipelineMap, "rerunPeriodMinutes", 0);
        Integer senderId = getInteger(pipelineMap, "senderId", null);

        PipelineConfig config = new PipelineConfig(pipelineKey, normalizedSite, server, socketPort, configName, senderId, rerunPeriodMinutes, stages, stagesByName);
        validatePipelineConfig(config);
        
        return Optional.of(config);
    }

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

    public void validatePipelineConfig(PipelineConfig config) throws PipelineConfigException {
        for (StageDefinition stage : config.stages()) {
            for (String depName : stage.dependsOn()) {
                if (config.findStage(depName) == null) {
                    throw new PipelineConfigException("Stage '" + stage.name() + "' in pipeline for site '" 
                        + config.site() + "' depends on non-existent stage '" + depName + "'");
                }
            }
        }
        detectCircularDependencies(config);
    }

    private void detectCircularDependencies(PipelineConfig config) throws PipelineConfigException {
        Map<String, Integer> inDegree = new HashMap<>();
        Map<String, List<String>> dependents = new HashMap<>();

        for (StageDefinition stage : config.stages()) {
            inDegree.put(stage.name(), stage.dependsOn().size());
            dependents.put(stage.name(), new ArrayList<>());
        }

        for (StageDefinition stage : config.stages()) {
            for (String dep : stage.dependsOn()) {
                dependents.get(dep).add(stage.name());
            }
        }

        Queue<String> queue = new java.util.LinkedList<>();
        
        for (String stageName : inDegree.keySet()) {
            if (inDegree.get(stageName) == 0) {
                queue.add(stageName);
            }
        }

        int processedCount = 0;
        while (!queue.isEmpty()) {
            String currentStage = queue.poll();
            processedCount++;

            for (String dependent : dependents.get(currentStage)) {
                inDegree.put(dependent, inDegree.get(dependent) - 1);
                if (inDegree.get(dependent) == 0) {
                    queue.add(dependent);
                }
            }
        }

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

    private List<String> getStageTypeNames() {
        List<String> names = new ArrayList<>();
        for (StageType type : StageType.values()) {
            names.add(type.name());
        }
        return names;
    }

    private String getString(Map<String, Object> map, String key, String defaultValue) {
        Object val = map.get(key);
        return val != null ? val.toString().trim() : defaultValue;
    }

    private Integer getInteger(Map<String, Object> map, String key, Integer defaultValue) {
        Object val = map.get(key);
        if (val instanceof Number num) {
            return num.intValue();
        }
        if (val != null) {
            try {
                return Integer.parseInt(val.toString().trim());
            } catch (NumberFormatException ignored) {}
        }
        return defaultValue;
    }
}
