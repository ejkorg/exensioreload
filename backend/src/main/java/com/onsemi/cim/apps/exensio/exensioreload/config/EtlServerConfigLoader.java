package com.onsemi.cim.apps.exensio.exensioreload.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import com.onsemi.cim.apps.exensio.exensioreload.entity.ConfigEtlServer;
import com.onsemi.cim.apps.exensio.exensioreload.repository.ConfigEtlServerRepository;
import com.onsemi.cim.apps.exensio.exensioreload.service.PasswordEncryptionService;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * ETL / DataPort SSH server definitions. Resolution order is admin DB
 * (config_etl_server) first, {@code classpath:etlservers.yml} as fallback.
 */
@Component
public class EtlServerConfigLoader {

    private static final Logger log = LoggerFactory.getLogger(EtlServerConfigLoader.class);
    private static final String CONFIG_FILE = "classpath:etlservers.yml";

    private final List<EtlServerConfig> configs = new ArrayList<>();
    private volatile boolean loaded = false;
    private String loadError;

    /**
     * Admin-maintained servers (config_etl_server table) take priority over the
     * YAML list. Optional so the loader keeps working YAML-only when JPA is
     * unavailable (e.g. unit tests using the no-arg constructor).
     */
    private ConfigEtlServerRepository serverRepository;
    private PasswordEncryptionService encryptionService;

    @Autowired(required = false)
    public void setServerRepository(ConfigEtlServerRepository serverRepository) {
        this.serverRepository = serverRepository;
    }

    @Autowired(required = false)
    public void setEncryptionService(PasswordEncryptionService encryptionService) {
        this.encryptionService = encryptionService;
    }

    @PostConstruct
    public void init() {
        load();
    }

    public void ensureLoaded() {
        if (!loaded) {
            load();
        }
    }

    /**
     * All known servers: admin DB rows first, YAML entries merged in for names
     * the DB does not define.
     */
    public List<EtlServerConfig> getConfigs() {
        ensureLoaded();
        Map<String, EtlServerConfig> merged = new LinkedHashMap<>();
        for (EtlServerConfig cfg : findAllDbServers()) {
            merged.put(cfg.getName().trim().toUpperCase(Locale.ROOT), cfg);
        }
        for (EtlServerConfig cfg : configs) {
            merged.putIfAbsent(cfg.getName().trim().toUpperCase(Locale.ROOT), cfg);
        }
        return List.copyOf(merged.values());
    }

    /**
     * Returns servers whose key matches the staging {@code site} (e.g. site {@code CEBU} → {@code CEBU-PROD}).
     * Searches admin DB rows first, YAML entries second. If nothing matches,
     * returns all loaded servers so callers can still attempt SSH.
     */
    public List<EtlServerConfig> getConfigsForSite(String site) {
        ensureLoaded();
        if (site == null || site.isBlank()) {
            return getConfigs();
        }
        String normalizedSite = site.trim().toUpperCase(Locale.ROOT);
        List<EtlServerConfig> all = getConfigs();
        List<EtlServerConfig> matched = all.stream()
                .filter(c -> serverMatchesSite(c.getName(), normalizedSite))
                .toList();
        if (matched.isEmpty()) {
            log.debug("No etlservers entry matched site '{}'; using all {} configured server(s)",
                    site, all.size());
            return all;
        }
        return matched;
    }

    public EtlServerConfig getConfigByName(String name) {
        ensureLoaded();
        if (name == null || name.isBlank()) {
            return null;
        }
        String normalized = name.trim();
        for (EtlServerConfig c : findAllDbServers()) {
            if (c.getName().equalsIgnoreCase(normalized)) {
                return c;
            }
        }
        for (EtlServerConfig c : configs) {
            if (c.getName().equalsIgnoreCase(normalized)) {
                return c;
            }
        }
        return null;
    }

    public boolean hasConfigs() {
        ensureLoaded();
        return !getConfigs().isEmpty();
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
            config.setSshPort(parseInt(getValue(configMap, "sshPort", null), null));
            config.setPort(parseInt(getValue(configMap, "port", "22"), 22));
            config.setSocketPort(parseInt(getValue(configMap, "socketPort", null), null));
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

    /**
     * Admin DB helpers — guarded so a missing/unavailable repository simply
     * yields "no DB rows" and the loader behaves YAML-only.
     */
    private List<EtlServerConfig> findAllDbServers() {
        if (serverRepository == null) return List.of();
        try {
            List<ConfigEtlServer> rows = serverRepository.findAll();
            if (rows == null || rows.isEmpty()) return List.of();
            List<EtlServerConfig> out = new ArrayList<>();
            for (ConfigEtlServer row : rows) {
                if (row == null || row.getServerKey() == null || row.getHost() == null
                        || row.getHost().isBlank()) {
                    continue;
                }
                EtlServerConfig cfg = new EtlServerConfig();
                cfg.setName(row.getServerKey());
                cfg.setHost(row.getHost());
                cfg.setSshPort(row.getSshPort());
                cfg.setSocketPort(row.getSocketPort());
                cfg.setUser(row.getUser());
                cfg.setPassword(decryptQuietly(row.getEncryptedPassword()));
                cfg.setTimeoutMs(row.getTimeoutMs());
                out.add(cfg);
            }
            return out;
        } catch (Exception ex) {
            log.debug("Admin DB ETL server list failed: {}", ex.getMessage());
            return List.of();
        }
    }

    private String decryptQuietly(String encrypted) {
        if (encrypted == null) return null;
        if (encryptionService == null) return encrypted;
        try {
            return encryptionService.decrypt(encrypted);
        } catch (Exception ignored) {
            return encrypted;
        }
    }

    private String getValue(Map<String, Object> map, String key, String defaultValue) {
        Object value = map.get(key);
        return value != null ? value.toString() : defaultValue;
    }

    private Integer parseInt(String value, Integer defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
