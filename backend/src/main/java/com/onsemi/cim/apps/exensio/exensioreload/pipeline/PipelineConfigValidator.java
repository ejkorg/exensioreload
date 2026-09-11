package com.onsemi.cim.apps.exensio.exensioreload.pipeline;

import java.util.HashSet;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.onsemi.cim.apps.exensio.exensioreload.config.ExternalDbConfig;

import jakarta.annotation.PostConstruct;

/**
 * Validates pipeline configurations at application startup.
 *
 * Loads all site pipelines from dbconnections.yml and validates:
 * - Configuration structure (stages array, stage names, types)
 * - Dependency references (all dependsOn stages exist)
 * - Circular dependencies
 *
 * Fails fast with clear error message if any config is invalid.
 * Logs summary of loaded pipelines.
 *
 * Requirements: 1.5, 8.1
 */
@Component
public class PipelineConfigValidator {

    private static final Logger log = LoggerFactory.getLogger(PipelineConfigValidator.class);

    private final PipelineConfigLoader configLoader;
    private final ExternalDbConfig externalDbConfig;

    public PipelineConfigValidator(PipelineConfigLoader configLoader,
                                   ExternalDbConfig externalDbConfig) {
        this.configLoader = configLoader;
        this.externalDbConfig = externalDbConfig;
    }

    /**
     * Validate all pipeline configurations at application startup.
     *
     * Annotated with @PostConstruct to run after all beans are initialized.
     * Fails fast if any configuration is invalid.
     *
     * Requirements: 1.5, 8.1
     */
    @PostConstruct
    public void validateConfigurations() {
        Set<String> sites = externalDbConfig.getConfiguredKeys();
        if (sites == null || sites.isEmpty()) {
            log.info("No pipeline configurations found (dbconnections.yml not loaded or empty)");
            return;
        }

        log.info("Validating pipeline configurations for {} site(s)...", sites.size());

        Set<String> stageTypesUsed = new HashSet<>();
        int validConfigs = 0;

        for (String site : sites) {
            try {
                var configOpt = configLoader.loadPipelineConfig(site);
                if (configOpt.isPresent()) {
                    PipelineConfigLoader.PipelineConfig config = configOpt.get();

                    configLoader.validatePipelineConfig(config);

                    for (var stage : config.stages()) {
                        stageTypesUsed.add(stage.type().toString());
                    }

                    validConfigs++;
                    log.debug("Pipeline configuration for site '{}' validated: {} stages",
                            site, config.stages().size());
                }
            } catch (Exception e) {
                log.error("VALIDATION FAILED for site '{}': {}", site, e.getMessage(), e);
                throw new IllegalStateException(
                        "Invalid pipeline configuration for site '" + site + "': " + e.getMessage(), e);
            }
        }

        if (validConfigs > 0) {
            log.info("Pipeline validation complete: {} site(s) configured, stage types: {}",
                    validConfigs, stageTypesUsed);
        } else {
            log.info("No pipeline configurations to validate (all sites use legacy behavior)");
        }
    }
}
