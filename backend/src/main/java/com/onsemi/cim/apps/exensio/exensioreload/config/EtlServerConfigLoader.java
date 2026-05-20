package com.onsemi.cim.apps.exensio.exensioreload.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Configuration
public class EtlServerConfigLoader {
    private static final String CONFIG_FILE = "etlservers.yml";

    private List<EtlServerConfig> configs = new ArrayList<>();
    private boolean loaded = false;

    public List<EtlServerConfig> getConfigs() {
        return configs;
    }

    public boolean hasConfigs() {
        return loaded && !configs.isEmpty();
    }

    public void load() {
        if (loaded) {
            return;
        }

        ResourceLoader resourceLoader = new DefaultResourceLoader();
        Resource resource = resourceLoader.getResource(CONFIG_FILE);

        try {
            if (resource.exists()) {
                Map<String, Object> yamlMap = loadYaml(resource);
                parseConfigs(yamlMap);
                loaded = true;
            }
        } catch (Exception e) {
            // Log warning but don't fail - continue with empty config
            loaded = true;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadYaml(Resource resource) throws IOException {
        // Simple YAML parser for the etlservers.yml format
        // Format: SERVER_NAME: { field: value, ... }
        String content = new String(resource.getInputStream().readAllBytes());
        Map<String, Object> result = new LinkedHashMap<>();

        String[] lines = content.split("\n");
        String currentServer = null;
        Map<String, Object> currentConfig = null;

        for (String line : lines) {
            String trimmed = line.trim();

            // Skip empty lines and comments
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }

            // Check if this is a server name (no leading spaces, ends with :)
            if (!line.startsWith(" ") && !line.startsWith("\t") && trimmed.endsWith(":")) {
                // Save previous server if exists
                if (currentServer != null && currentConfig != null) {
                    result.put(currentServer, currentConfig);
                }

                currentServer = trimmed.substring(0, trimmed.length() - 1).trim();
                currentConfig = new LinkedHashMap<>();
            } else if (currentConfig != null && trimmed.contains(":")) {
                // This is a config field
                String[] parts = trimmed.split(":", 2);
                if (parts.length == 2) {
                    String key = parts[0].trim();
                    String value = parts[1].trim();
                    currentConfig.put(key, value);
                }
            }
        }

        // Save last server if exists
        if (currentServer != null && currentConfig != null) {
            result.put(currentServer, currentConfig);
        }

        return result;
    }

    private void parseConfigs(Map<String, Object> yamlMap) {
        for (Map.Entry<String, Object> entry : yamlMap.entrySet()) {
            String serverName = entry.getKey();
            Map<String, Object> configMap = (Map<String, Object>) entry.getValue();

            EtlServerConfig config = new EtlServerConfig();
            config.setName(serverName);
            config.setHost(getValue(configMap, "host", ""));
            config.setPort(parseInt(getValue(configMap, "port", "22")));
            config.setUser(getValue(configMap, "user", ""));
            config.setPassword(getValue(configMap, "password", ""));
            config.setTimeoutMs(parseInt(getValue(configMap, "timeoutMs", "30000")));

            configs.add(config);
        }
    }

    private String getValue(Map<String, Object> map, String key, String defaultValue) {
        Object value = map.get(key);
        return value != null ? value.toString() : defaultValue;
    }

    private Integer parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 22; // default port
        }
    }
}
