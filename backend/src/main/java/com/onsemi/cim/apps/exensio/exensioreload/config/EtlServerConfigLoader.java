package com.onsemi.cim.apps.exensio.exensioreload.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Loads ETL / DataPort SSH server definitions from {@code classpath:etlservers.yml}.
 */
@Component
public class EtlServerConfigLoader {

    private static final Logger log = LoggerFactory.getLogger(EtlServerConfigLoader.class);
    private static final String CONFIG_FILE = "classpath:etlservers.yml";

    private final List<EtlServerConfig> configs = new ArrayList<>();
    private volatile boolean loaded = false;
    private String loadError;

    @PostConstruct
    public void init() {
        load();
    }

    public void ensureLoaded() {
        if (!loaded) {
            load();
        }
    }

    public List<EtlServerConfig> getConfigs() {
        ensureLoaded();
        return List.copyOf(configs);
    }

    /**
     * Returns servers whose YAML key matches the staging {@code site} (e.g. site {@code CEBU} → {@code CEBU-PROD}).
     * If nothing matches, returns all loaded servers so callers can still attempt SSH.
     */
    public List<EtlServerConfig> getConfigsForSite(String site) {
        ensureLoaded();
        if (site == null || site.isBlank()) {
            return getConfigs();
        }
        String normalizedSite = site.trim().toUpperCase(Locale.ROOT);
        List<EtlServerConfig> matched = configs.stream()
                .filter(c -> serverMatchesSite(c.getName(), normalizedSite))
                .toList();
        if (matched.isEmpty()) {
            log.debug("No etlservers.yml entry matched site '{}'; using all {} configured server(s)",
                    site, configs.size());
            return getConfigs();
        }
        return matched;
    }

    public boolean hasConfigs() {
        ensureLoaded();
        return !configs.isEmpty();
    }

    public String getLoadError() {
        return loadError;
    }

    public synchronized void load() {
        if (loaded) {
            return;
        }

        configs.clear();
        loadError = null;

        ResourceLoader resourceLoader = new DefaultResourceLoader();
        Resource resource = resourceLoader.getResource(CONFIG_FILE);

        try {
            if (!resource.exists()) {
                loadError = "Resource not found: " + CONFIG_FILE;
                log.warn("ETL SSH trigger: {} — feature will report not_configured", loadError);
                loaded = true;
                return;
            }

            Map<String, Object> yamlMap = loadYaml(resource);
            parseConfigs(yamlMap);
            loaded = true;
            log.info("ETL SSH trigger: loaded {} server(s) from {}", configs.size(), CONFIG_FILE);
        } catch (Exception e) {
            loadError = e.getMessage();
            loaded = true;
            log.warn("ETL SSH trigger: failed to load {} — {}", CONFIG_FILE, e.getMessage());
        }
    }

    static boolean serverMatchesSite(String serverName, String normalizedSite) {
        if (serverName == null || normalizedSite == null || normalizedSite.isBlank()) {
            return false;
        }
        String server = serverName.trim().toUpperCase(Locale.ROOT);
        return server.equals(normalizedSite)
                || server.startsWith(normalizedSite + "-")
                || server.contains(normalizedSite);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadYaml(Resource resource) throws IOException {
        String content = new String(resource.getInputStream().readAllBytes());
        Map<String, Object> result = new LinkedHashMap<>();

        String[] lines = content.split("\n");
        String currentServer = null;
        Map<String, Object> currentConfig = null;

        for (String line : lines) {
            String trimmed = line.trim();

            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }

            if (!line.startsWith(" ") && !line.startsWith("\t") && trimmed.endsWith(":")) {
                if (currentServer != null && currentConfig != null) {
                    result.put(currentServer, currentConfig);
                }

                currentServer = trimmed.substring(0, trimmed.length() - 1).trim();
                currentConfig = new LinkedHashMap<>();
            } else if (currentConfig != null && trimmed.contains(":")) {
                String[] parts = trimmed.split(":", 2);
                if (parts.length == 2) {
                    String key = parts[0].trim();
                    String value = parts[1].trim();
                    currentConfig.put(key, value);
                }
            }
        }

        if (currentServer != null && currentConfig != null) {
            result.put(currentServer, currentConfig);
        }

        return result;
    }

    private void parseConfigs(Map<String, Object> yamlMap) {
        for (Map.Entry<String, Object> entry : yamlMap.entrySet()) {
            String serverName = entry.getKey();
            if (!(entry.getValue() instanceof Map<?, ?> rawMap)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> configMap = (Map<String, Object>) rawMap;

            EtlServerConfig config = new EtlServerConfig();
            config.setName(serverName);
            config.setHost(getValue(configMap, "host", ""));
            config.setPort(parseInt(getValue(configMap, "port", "22"), 22));
            config.setUser(getValue(configMap, "user", ""));
            config.setPassword(getValue(configMap, "password", ""));
            config.setTimeoutMs(parseInt(getValue(configMap, "timeoutMs", "30000"), 30000));

            if (config.getHost() == null || config.getHost().isBlank()) {
                log.warn("Skipping ETL server '{}' — host is blank", serverName);
                continue;
            }

            configs.add(config);
        }
    }

    private String getValue(Map<String, Object> map, String key, String defaultValue) {
        Object value = map.get(key);
        return value != null ? value.toString() : defaultValue;
    }

    private Integer parseInt(String value, int defaultValue) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
