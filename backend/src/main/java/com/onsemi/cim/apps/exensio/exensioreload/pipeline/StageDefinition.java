package com.onsemi.cim.apps.exensio.exensioreload.pipeline;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable definition of a single stage in a pipeline.
 * Parsed from a stage entry in the pipeline.stages array in dbconnections.yml
 */
public record StageDefinition(
    String name,
    StageType type,
    List<String> dependsOn,
    Map<String, Object> config
) {
    public StageDefinition {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(dependsOn, "dependsOn must not be null");
        Objects.requireNonNull(config, "config must not be null");
    }

    /**
     * Check if this stage has any dependencies
     */
    public boolean hasDependencies() {
        return !dependsOn.isEmpty();
    }

    /**
     * Check if this stage depends on a specific stage
     */
    public boolean dependsOn(String stageName) {
        return dependsOn.contains(stageName);
    }

    /**
     * Get a configuration value by key
     */
    public Object getConfig(String key) {
        return config.get(key);
    }

    /**
     * Get a configuration value by key with a default fallback
     */
    public Object getConfig(String key, Object defaultValue) {
        return config.getOrDefault(key, defaultValue);
    }

    /**
     * Get a configuration value as an Integer
     */
    public Integer getConfigAsInteger(String key, Integer defaultValue) {
        Object value = config.get(key);
        if (value instanceof Integer) {
            return (Integer) value;
        } else if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return defaultValue;
    }

    /**
     * Get a configuration value as a String
     */
    public String getConfigAsString(String key, String defaultValue) {
        Object value = config.get(key);
        if (value instanceof String) {
            return (String) value;
        }
        return defaultValue;
    }
}
