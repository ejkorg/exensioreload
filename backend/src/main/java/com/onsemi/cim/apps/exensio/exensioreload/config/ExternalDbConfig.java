package com.onsemi.cim.apps.exensio.exensioreload.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.onsemi.cim.apps.exensio.exensioreload.entity.ConfigDbConnection;
import com.onsemi.cim.apps.exensio.exensioreload.repository.ConfigDbConnectionRepository;
import com.onsemi.cim.apps.exensio.exensioreload.service.PasswordEncryptionService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class ExternalDbConfig {
    private static final Logger log = LoggerFactory.getLogger(ExternalDbConfig.class);

    private final Environment env;
    private final ObjectMapper jsonMapper = new ObjectMapper();
    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
    private final Map<String, Map<String, Object>> dbConnections;
    private final ConcurrentMap<String, HikariDataSource> dsCache = new ConcurrentHashMap<>();

    /**
     * Admin-maintained connections (config_db_connection table) take priority over the
     * YAML fallback map. Both are optional and wired with required=false so this low-level
     * config bean never fails startup when JPA is unavailable (e.g. unit tests).
     */
    private ConfigDbConnectionRepository connectionRepository;
    private PasswordEncryptionService encryptionService;

    @Autowired(required = false)
    public void setConnectionRepository(ConfigDbConnectionRepository connectionRepository) {
        this.connectionRepository = connectionRepository;
    }

    @Autowired(required = false)
    public void setEncryptionService(PasswordEncryptionService encryptionService) {
        this.encryptionService = encryptionService;
    }

    public ExternalDbConfig(Environment env) throws IOException {
        this.env = env;
        this.dbConnections = loadConnections();
    }

    private Map<String, Map<String, Object>> loadConnections() throws IOException {
        Map<String, Map<String, Object>> loaded = null;

        String externalPath = ConfigUtils.getString(env, "RELOADER_DBCONN_PATH", "reloader.dbconn.path", null);
        if (externalPath != null && !externalPath.isBlank()) {
            try (InputStream is = Files.newInputStream(Paths.get(externalPath))) {
                byte[] raw = is.readAllBytes();
                loaded = tryParse(raw);
            }
        }

        if (loaded == null) {
            String yamlPath = ConfigUtils.getString(env, "exensioreload.dtp-db.yaml.path", "RELOADER_DBCONN_YAML_PATH", null);
            if (yamlPath != null && !yamlPath.isBlank()) {
                try {
                    if (yamlPath.startsWith("classpath:")) {
                        String res = yamlPath.substring("classpath:".length());
                        ClassPathResource r = new ClassPathResource(res.startsWith("/") ? res.substring(1) : res);
                        try (InputStream is = r.getInputStream()) {
                            loaded = yamlMapper.readValue(is, new TypeReference<>() {});
                        }
                    } else {
                        try (InputStream is = Files.newInputStream(Paths.get(yamlPath))) {
                            loaded = yamlMapper.readValue(is, new TypeReference<>() {});
                        }
                    }
                } catch (Exception ignored) {
                    log.warn("Unable to load db connections from configured yaml path: {}", yamlPath);
                }
            }
        }

        if (loaded == null) {
            try {
                ClassPathResource ry = new ClassPathResource("dbconnections.yml");
                if (ry.exists()) {
                    try (InputStream is = ry.getInputStream()) {
                        loaded = yamlMapper.readValue(is, new TypeReference<>() {});
                    }
                }
            } catch (Exception ignored) {
            }
        }

        if (loaded == null) {
            ClassPathResource rj = new ClassPathResource("dbconnections.json");
            if (rj.exists()) {
                try (InputStream is = rj.getInputStream()) {
                    loaded = jsonMapper.readValue(is, new TypeReference<>() {});
                }
            }
        }

        if (loaded == null) {
            log.warn("No dbconnections configuration found; external DB calls will fail until configured");
            return new HashMap<>();
        }

        Map<String, Map<String, Object>> normalized = new HashMap<>();
        for (Map.Entry<String, Map<String, Object>> e : loaded.entrySet()) {
            if (e.getKey() != null && e.getValue() != null) {
                normalized.put(e.getKey().toUpperCase(Locale.ROOT), e.getValue());
            }
        }
        return normalized;
    }

    private Map<String, Map<String, Object>> tryParse(byte[] raw) throws IOException {
        try {
            return jsonMapper.readValue(raw, new TypeReference<>() {});
        } catch (Exception ignored) {
            try {
                return yamlMapper.readValue(raw, new TypeReference<>() {});
            } catch (Exception ex) {
                throw new IOException("Failed to parse external db connections as JSON or YAML", ex);
            }
        }
    }

    public Map<String, Object> listPoolStats() {
        Map<String, Object> out = new HashMap<>();
        for (Map.Entry<String, HikariDataSource> e : dsCache.entrySet()) {
            HikariDataSource ds = e.getValue();
            if (ds == null) continue;
            Map<String, Object> s = new HashMap<>();
            try {
                s.put("active", ds.getHikariPoolMXBean().getActiveConnections());
                s.put("idle", ds.getHikariPoolMXBean().getIdleConnections());
                s.put("threadsAwaiting", ds.getHikariPoolMXBean().getThreadsAwaitingConnection());
            } catch (Exception ex) {
                s.put("error", "unavailable");
            }
            out.put(e.getKey(), s);
        }
        return out;
    }

    public Set<String> getActivePoolKeys() {
        return Collections.unmodifiableSet(dsCache.keySet());
    }

    public Set<String> getConfiguredKeys() {
        Set<String> keys = new HashSet<>(dbConnections.keySet());
        if (connectionRepository != null) {
            try {
                List<ConfigDbConnection> rows = connectionRepository.findAll();
                if (rows != null) {
                    for (ConfigDbConnection row : rows) {
                        if (row != null && row.getConnectionKey() != null) {
                            keys.add(row.getConnectionKey().trim().toUpperCase(Locale.ROOT));
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return Collections.unmodifiableSet(keys);
    }

    public void recreatePool(String resolvedKey) {
        if (resolvedKey == null) return;
        HikariDataSource ds = dsCache.remove(resolvedKey.toUpperCase(Locale.ROOT));
        if (ds != null) {
            try {
                ds.close();
            } catch (Exception ignored) {
            }
        }
    }

    public Map<String, Object> getConfigForSite(String site) {
        return getConfigForSite(site, null);
    }

    /**
     * Resolution order: admin DB (config_db_connection) first, YAML fallback second.
     * DB rows are stored per connection key, so the site lookup tries the
     * SITE-ENV key, then a row whose key equals the site, then a startsWith match —
     * mirroring the YAML key layout.
     */
    public Map<String, Object> getConfigForSite(String site, String environment) {
        if (site == null || site.isBlank()) return null;
        String s = site.trim().toUpperCase(Locale.ROOT);
        String envPart = (environment == null || environment.isBlank()) ? "QA" : environment.trim().toUpperCase(Locale.ROOT);

        Map<String, Object> fromDb = getDbConfigForSite(s, envPart);
        if (fromDb != null) return fromDb;

        if (dbConnections.containsKey(s)) {
            Map<String, Object> direct = dbConnections.get(s);
            if (direct == null) return null;
            Object nested = direct.get(envPart.toLowerCase(Locale.ROOT));
            if (!(nested instanceof Map<?, ?>)) {
                nested = direct.get(envPart.toUpperCase(Locale.ROOT));
            }
            if (nested instanceof Map<?, ?> nestedMap) {
                return (Map<String, Object>) nestedMap;
            }
            return direct;
        }

        String siteEnvKey = s + "-" + envPart;
        if (dbConnections.containsKey(siteEnvKey)) {
            return dbConnections.get(siteEnvKey);
        }

        for (Map.Entry<String, Map<String, Object>> e : dbConnections.entrySet()) {
            if (e.getKey().startsWith(s + "-" + envPart)) {
                return e.getValue();
            }
        }

        return null;
    }

    public Map<String, Object> getConfigByKey(String key) {
        if (key == null || key.isBlank()) return null;
        Map<String, Object> fromDb = getDbConfigByKey(key.trim().toUpperCase(Locale.ROOT));
        if (fromDb != null) return fromDb;
        return dbConnections.get(key.trim().toUpperCase(Locale.ROOT));
    }

    /**
     * Admin-DB lookup for a site+environment, returning the YAML-shaped map
     * ExternalDbConfig consumers expect (host/user/password/dbType/hikari/downloadUrl).
     */
    private Map<String, Object> getDbConfigForSite(String normalizedSite, String envPart) {
        if (connectionRepository == null) return null;
        try {
            String siteEnvKey = normalizedSite + "-" + envPart;
            Optional<ConfigDbConnection> exact = safeFindByKey(siteEnvKey);
            if (exact.isPresent() && matchesEnv(exact.get(), envPart)) {
                return toConfigMap(exact.get());
            }
            Optional<ConfigDbConnection> bySite = safeFindByKey(normalizedSite);
            if (bySite.isPresent() && matchesEnv(bySite.get(), envPart)) {
                return toConfigMap(bySite.get());
            }
            List<ConfigDbConnection> envRows;
            try {
                envRows = connectionRepository.findByEnvironment(envPart);
            } catch (Exception ignored) {
                return null;
            }
            if (envRows != null) {
                for (ConfigDbConnection row : envRows) {
                    if (row != null && row.getConnectionKey() != null
                            && row.getConnectionKey().trim().toUpperCase(Locale.ROOT).startsWith(normalizedSite + "-" + envPart)) {
                        return toConfigMap(row);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private Map<String, Object> getDbConfigByKey(String normalizedKey) {
        if (connectionRepository == null) return null;
        try {
            Optional<ConfigDbConnection> row = safeFindByKey(normalizedKey);
            return row.map(this::toConfigMap).orElse(null);
        } catch (Exception ignored) {
            return null;
        }
    }

    private Optional<ConfigDbConnection> safeFindByKey(String key) {
        try {
            return connectionRepository.findByConnectionKey(key);
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private boolean matchesEnv(ConfigDbConnection row, String envPart) {
        if (row.getEnvironment() == null) return true;
        return row.getEnvironment().trim().equalsIgnoreCase(envPart);
    }

    private Map<String, Object> toConfigMap(ConfigDbConnection row) {
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("host", row.getHost());
        cfg.put("user", row.getUser());
        cfg.put("password", decryptQuietly(row.getEncryptedPassword()));
        cfg.put("dbType", row.getDbType() == null ? "ORACLE" : row.getDbType().name());
        cfg.put("schema", row.getSchema());
        if (row.getDownloadUrl() != null) cfg.put("downloadUrl", row.getDownloadUrl());
        Map<String, Object> hikari = new HashMap<>();
        hikari.put("connectionTimeoutMs", row.getConnectionTimeoutMs());
        hikari.put("maximumPoolSize", row.getMaximumPoolSize());
        hikari.put("minimumIdle", row.getMinimumIdle());
        cfg.put("hikari", hikari);
        return cfg;
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

    public Connection getConnectionByKey(String key, String environment) throws SQLException {
        String envPart = (environment == null || environment.isBlank()) ? "qa" : environment.trim().toLowerCase(Locale.ROOT);
        String keyPart = key == null ? "" : key.trim().toUpperCase(Locale.ROOT);
        String resolvedPoolKey = (keyPart + "-" + envPart).toUpperCase(Locale.ROOT);

        Map<String, Object> cfg = getConfigForSite(keyPart, envPart);
        if (cfg == null) {
            cfg = getConfigByKey(resolvedPoolKey);
        }
        if (cfg == null) {
            cfg = getConfigByKey(keyPart);
        }
        if (cfg == null) {
            throw new SQLException("No DB connection config found for key=" + key + " environment=" + environment);
        }

        Map<String, Object> finalCfg = cfg;
        HikariDataSource ds = dsCache.computeIfAbsent(resolvedPoolKey, k -> buildDataSource(finalCfg, k));
        try {
            return ds.getConnection();
        } catch (Exception ex) {
            dsCache.remove(resolvedPoolKey);
            throw new SQLException("Failed to get pooled connection for " + resolvedPoolKey, ex);
        }
    }

    public Connection getConnection(String site) throws SQLException {
        return getConnection(site, null);
    }

    public Connection getConnection(String site, String environment) throws SQLException {
        return getConnectionByKey(site, environment);
    }

    public String getDownloadUrlTemplate(String site, String environment) {
        Map<String, Object> cfg = getConfigForSite(site, environment);
        if (cfg == null) return null;
        Object v = cfg.get("downloadUrl");
        if (v == null) v = cfg.get("download_url");
        return v == null ? null : String.valueOf(v);
    }

    private HikariDataSource buildDataSource(Map<String, Object> cfg, String poolKey) {
        String host = asString(cfg.get("host"));
        String user = asString(cfg.get("user"));
        String password = asString(cfg.get("password"));
        String dbType = asString(cfg.get("dbType"));

        String jdbcUrl = toJdbcUrl(host, dbType);

        HikariConfig hc = new HikariConfig();
        hc.setPoolName("external-" + poolKey.toLowerCase(Locale.ROOT));
        hc.setJdbcUrl(jdbcUrl);
        hc.setUsername(user);
        hc.setPassword(password);

        if (jdbcUrl.startsWith("jdbc:oracle:")) {
            hc.setDriverClassName("oracle.jdbc.OracleDriver");
        }

        int max = asInt(nested(cfg, "hikari", "maximumPoolSize"), 10);
        int min = asInt(nested(cfg, "hikari", "minimumIdle"), 1);
        long timeout = asLong(nested(cfg, "hikari", "connectionTimeoutMs"), 20000L);
        hc.setMaximumPoolSize(max);
        hc.setMinimumIdle(min);
        hc.setConnectionTimeout(timeout);

        return new HikariDataSource(hc);
    }

    private Object nested(Map<String, Object> cfg, String parentKey, String childKey) {
        Object p = cfg.get(parentKey);
        if (p instanceof Map<?, ?> map) {
            Object v = map.get(childKey);
            if (v == null) v = map.get(childKey.toLowerCase(Locale.ROOT));
            if (v == null) v = map.get(childKey.toUpperCase(Locale.ROOT));
            return v;
        }
        return null;
    }

    private String toJdbcUrl(String host, String dbType) {
        if (host == null || host.isBlank()) {
            return "jdbc:h2:mem:externaldb;DB_CLOSE_DELAY=-1";
        }
        String h = host.trim();
        if (h.startsWith("jdbc:")) return h;
        String type = dbType == null ? "oracle" : dbType.trim().toLowerCase(Locale.ROOT);
        if (!"oracle".equals(type)) {
            return h;
        }
        if (h.contains("/") && !h.startsWith("//")) {
            return "jdbc:oracle:thin:@//" + h;
        }
        return "jdbc:oracle:thin:@" + h;
    }

    private String asString(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private int asInt(Object v, int dflt) {
        if (v == null) return dflt;
        try {
            return Integer.parseInt(String.valueOf(v));
        } catch (Exception ignored) {
            return dflt;
        }
    }

    private long asLong(Object v, long dflt) {
        if (v == null) return dflt;
        try {
            return Long.parseLong(String.valueOf(v));
        } catch (Exception ignored) {
            return dflt;
        }
    }

    @PreDestroy
    public void destroy() {
        for (HikariDataSource ds : dsCache.values()) {
            if (ds != null) {
                try {
                    ds.close();
                } catch (Exception ignored) {
                }
            }
        }
        dsCache.clear();
    }
}
