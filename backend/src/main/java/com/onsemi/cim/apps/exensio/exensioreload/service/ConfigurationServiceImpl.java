package com.onsemi.cim.apps.exensio.exensioreload.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.onsemi.cim.apps.exensio.exensioreload.config.ExternalDbConfig;
import com.onsemi.cim.apps.exensio.exensioreload.config.YamlConfigLoader;
import com.onsemi.cim.apps.exensio.exensioreload.entity.ConfigDbConnection;
import com.onsemi.cim.apps.exensio.exensioreload.entity.ConfigEtlServer;
import com.onsemi.cim.apps.exensio.exensioreload.entity.ConfigPipeline;
import com.onsemi.cim.apps.exensio.exensioreload.entity.ConfigPipelineStage;
import com.onsemi.cim.apps.exensio.exensioreload.repository.ConfigDbConnectionRepository;
import com.onsemi.cim.apps.exensio.exensioreload.repository.ConfigEtlServerRepository;
import com.onsemi.cim.apps.exensio.exensioreload.repository.ConfigPipelineRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Implementation of ConfigurationService with caching and YAML fallback support.
 * Provides database-first configuration loading with YAML fallback for backward compatibility.
 */
@Service
@Slf4j
public class ConfigurationServiceImpl implements ConfigurationService {

    private final ConfigPipelineRepository pipelineRepository;
    private final ConfigEtlServerRepository serverRepository;
    private final ConfigDbConnectionRepository connectionRepository;
    private final YamlConfigLoader yamlConfigLoader;
    private final PasswordEncryptionService encryptionService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final ExternalDbConfig externalDbConfig;

    // Caffeine caches with 1 hour TTL and max 1000 entries
    private Cache<String, ConfigPipeline> pipelineCache;
    private Cache<String, ConfigEtlServer> serverCache;
    private Cache<String, ConfigDbConnection> connectionCache;

    // Track YAML fallback state
    private volatile boolean usingYamlFallback = false;

    @Autowired
    public ConfigurationServiceImpl(ConfigPipelineRepository pipelineRepository,
                                   ConfigEtlServerRepository serverRepository,
                                   ConfigDbConnectionRepository connectionRepository,
                                   YamlConfigLoader yamlConfigLoader,
                                   PasswordEncryptionService encryptionService,
                                   AuditService auditService,
                                   ObjectMapper objectMapper,
                                   @Autowired(required = false) ExternalDbConfig externalDbConfig) {
        this.pipelineRepository = pipelineRepository;
        this.serverRepository = serverRepository;
        this.connectionRepository = connectionRepository;
        this.yamlConfigLoader = yamlConfigLoader;
        this.encryptionService = encryptionService;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.externalDbConfig = externalDbConfig;
        this.initializeCaches();
    }

    /**
     * Initialize all Caffeine caches with appropriate settings.
     */
    private void initializeCaches() {
        pipelineCache = Caffeine.newBuilder()
            .maximumSize(1000)
            .expireAfterWrite(Duration.ofHours(1))
            .build();

        serverCache = Caffeine.newBuilder()
            .maximumSize(500)
            .expireAfterWrite(Duration.ofHours(1))
            .build();

        connectionCache = Caffeine.newBuilder()
            .maximumSize(500)
            .expireAfterWrite(Duration.ofHours(1))
            .build();

        log.debug("Configuration caches initialized with 1-hour TTL");
    }

    // ==================== Pipeline Operations ====================

    /**
     * DB-first list: DB rows win; when the DB query succeeds but returns nothing
     * (fresh install, no admin setups yet), merge in the YAML definitions so the
     * Step 1 dropdowns still work.
     */
    @Override
    @Transactional(readOnly = true)
    public List<ConfigPipeline> getAllPipelines(String environment) {
        try {
            Map<String, ConfigPipeline> merged = new LinkedHashMap<>();
            List<ConfigPipeline> dbRows = (environment == null || environment.isEmpty())
                ? pipelineRepository.findAll()
                : pipelineRepository.findByEnvironment(environment);
            if (dbRows != null) {
                for (ConfigPipeline p : dbRows) {
                    if (p != null && p.getPipelineKey() != null) merged.put(p.getPipelineKey(), p);
                }
            }
            for (ConfigPipeline p : yamlConfigLoader.loadAllPipelinesFromYaml()) {
                if (environment != null && !environment.isEmpty() && !environment.equals(p.getEnvironment())) continue;
                merged.putIfAbsent(p.getPipelineKey(), p);
            }
            if (merged.size() > (dbRows == null ? 0 : dbRows.size())) {
                usingYamlFallback = true;
            }
            return new ArrayList<>(merged.values());
        } catch (Exception e) {
            log.warn("Error loading pipelines from database for environment {}, falling back to YAML", environment, e);
            usingYamlFallback = true;
            return yamlConfigLoader.loadAllPipelinesFromYaml().stream()
                .filter(p -> environment == null || environment.isEmpty() || environment.equals(p.getEnvironment()))
                .collect(Collectors.toList());
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<ConfigPipeline> getPipelinesBySite(String site, String environment, Boolean historicalMode) {
        try {
            Map<String, ConfigPipeline> merged = new LinkedHashMap<>();

            if (historicalMode == null) {
                // Return all pipelines for the site and environment
                for (ConfigPipeline p : pipelineRepository.findBySiteAndEnvironmentAndHistoricalModeEnabled(site, environment, true)) {
                    if (p != null && p.getPipelineKey() != null) merged.put(p.getPipelineKey(), p);
                }
                for (ConfigPipeline p : pipelineRepository.findBySiteAndEnvironmentAndHistoricalModeEnabled(site, environment, false)) {
                    if (p != null && p.getPipelineKey() != null) merged.put(p.getPipelineKey(), p);
                }
            } else {
                // Return pipelines filtered by historical mode
                for (ConfigPipeline p : pipelineRepository.findBySiteAndEnvironmentAndHistoricalModeEnabled(site, environment, historicalMode)) {
                    if (p != null && p.getPipelineKey() != null) merged.put(p.getPipelineKey(), p);
                }
            }

            for (ConfigPipeline p : yamlConfigLoader.loadAllPipelinesFromYaml()) {
                if (!site.equals(p.getSite()) || !environment.equals(p.getEnvironment())) continue;
                if (historicalMode != null && !historicalMode.equals(p.getHistoricalModeEnabled())) continue;
                merged.putIfAbsent(p.getPipelineKey(), p);
            }
            return new ArrayList<>(merged.values());
        } catch (Exception e) {
            log.warn("Error loading pipelines for site {} from database, falling back to YAML", site, e);
            usingYamlFallback = true;
            return yamlConfigLoader.loadAllPipelinesFromYaml().stream()
                .filter(p -> site.equals(p.getSite()) && environment.equals(p.getEnvironment()))
                .filter(p -> historicalMode == null || historicalMode.equals(p.getHistoricalModeEnabled()))
                .collect(Collectors.toList());
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ConfigPipeline> getPipelineByKey(String pipelineKey) {
        // Try cache first
        ConfigPipeline cached = pipelineCache.getIfPresent(pipelineKey);
        if (cached != null) {
            log.debug("Pipeline '{}' retrieved from cache", pipelineKey);
            return Optional.of(cached);
        }

        try {
            // Try database
            Optional<ConfigPipeline> dbResult = pipelineRepository.findByPipelineKey(pipelineKey);
            if (dbResult.isPresent()) {
                pipelineCache.put(pipelineKey, dbResult.get());
                log.debug("Pipeline '{}' retrieved from database", pipelineKey);
                return dbResult;
            }
        } catch (Exception e) {
            log.warn("Error loading pipeline '{}' from database, attempting YAML fallback", pipelineKey, e);
        }

        // Fall back to YAML
        log.warn("Pipeline '{}' not found in database, attempting to load from YAML", pipelineKey);
        usingYamlFallback = true;
        Optional<ConfigPipeline> yamlResult = yamlConfigLoader.loadPipelineFromYaml(pipelineKey);
        yamlResult.ifPresent(p -> pipelineCache.put(pipelineKey, p));
        return yamlResult;
    }

    @Override
    @Transactional
    public ConfigPipeline savePipeline(ConfigPipeline pipeline) {
        // Set audit fields
        Long currentUserId = getCurrentUserId();
        Instant now = Instant.now();

        // Check if this is an update or create
        Optional<ConfigPipeline> existing = pipelineRepository.findByPipelineKey(pipeline.getPipelineKey());

        if (existing.isEmpty()) {
            // Create new
            pipeline.setCreatedAt(now);
            pipeline.setCreatedBy(currentUserId);
        } else {
            // Update existing
            pipeline.setCreatedAt(existing.get().getCreatedAt());
            pipeline.setCreatedBy(existing.get().getCreatedBy());
        }

        // Validate before save
        validatePipeline(pipeline);

        // Update modification fields
        pipeline.setUpdatedAt(now);
        pipeline.setUpdatedBy(currentUserId);

        // Save
        ConfigPipeline saved = pipelineRepository.save(pipeline);

        // Invalidate cache
        invalidateCacheForPipeline(saved.getPipelineKey());

        // Audit log
        String action = existing.isEmpty() ? "PIPELINE_CREATED" : "PIPELINE_UPDATED";
        try {
            Map<String, Object> details = new HashMap<>();
            if (existing.isPresent()) {
                details.put("before", convertToMap(existing.get()));
            }
            details.put("after", convertToMap(saved));
            auditService.logAction(currentUserId, action, "PIPELINE", saved.getPipelineKey(), details);
        } catch (Exception e) {
            log.error("Failed to audit pipeline save: {}", e.getMessage());
        }

        log.info("Pipeline '{}' saved successfully", saved.getPipelineKey());
        return saved;
    }

    @Override
    @Transactional
    public void deletePipeline(String pipelineKey) {
        Optional<ConfigPipeline> existing = pipelineRepository.findByPipelineKey(pipelineKey);
        if (existing.isEmpty()) {
            log.warn("Attempted to delete non-existent pipeline: {}", pipelineKey);
            return;
        }

        ConfigPipeline pipeline = existing.get();
        pipelineRepository.delete(pipeline);
        invalidateCacheForPipeline(pipelineKey);

        // Audit log
        Long currentUserId = getCurrentUserId();
        try {
            Map<String, Object> details = new HashMap<>();
            details.put("deleted", convertToMap(pipeline));
            auditService.logAction(currentUserId, "PIPELINE_DELETED", "PIPELINE", pipelineKey, details);
        } catch (Exception e) {
            log.error("Failed to audit pipeline delete: {}", e.getMessage());
        }

        log.info("Pipeline '{}' deleted successfully", pipelineKey);
    }

    // ==================== ETL Server Operations ====================

    @Override
    @Transactional(readOnly = true)
    public List<ConfigEtlServer> getAllEtlServers(String environment) {
        try {
            Map<String, ConfigEtlServer> merged = new LinkedHashMap<>();
            List<ConfigEtlServer> dbRows = (environment == null || environment.isEmpty())
                ? serverRepository.findAll()
                : serverRepository.findByEnvironment(environment);
            if (dbRows != null) {
                for (ConfigEtlServer s : dbRows) {
                    if (s != null && s.getServerKey() != null) merged.put(s.getServerKey(), s);
                }
            }
            for (ConfigEtlServer s : yamlConfigLoader.loadAllServersFromYaml()) {
                if (environment != null && !environment.isEmpty() && !environment.equals(s.getEnvironment())) continue;
                merged.putIfAbsent(s.getServerKey(), s);
            }
            if (merged.size() > (dbRows == null ? 0 : dbRows.size())) {
                usingYamlFallback = true;
            }
            return new ArrayList<>(merged.values());
        } catch (Exception e) {
            log.warn("Error loading ETL servers from database for environment {}, falling back to YAML", environment, e);
            usingYamlFallback = true;
            return yamlConfigLoader.loadAllServersFromYaml().stream()
                .filter(s -> environment == null || environment.isEmpty() || environment.equals(s.getEnvironment()))
                .collect(Collectors.toList());
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<ConfigEtlServer> getHistoricalEtlServers(String environment) {
        try {
            Map<String, ConfigEtlServer> merged = new LinkedHashMap<>();
            List<ConfigEtlServer> dbRows = serverRepository.findByEnvironmentAndIsHistoricalSenderTrue(environment);
            if (dbRows != null) {
                for (ConfigEtlServer s : dbRows) {
                    if (s != null && s.getServerKey() != null) merged.put(s.getServerKey(), s);
                }
            }
            for (ConfigEtlServer s : yamlConfigLoader.loadAllServersFromYaml()) {
                if (!environment.equals(s.getEnvironment()) || !Boolean.TRUE.equals(s.getIsHistoricalSender())) continue;
                merged.putIfAbsent(s.getServerKey(), s);
            }
            return new ArrayList<>(merged.values());
        } catch (Exception e) {
            log.warn("Error loading historical ETL servers from database, falling back to YAML", e);
            usingYamlFallback = true;
            return yamlConfigLoader.loadAllServersFromYaml().stream()
                .filter(s -> environment.equals(s.getEnvironment()) && Boolean.TRUE.equals(s.getIsHistoricalSender()))
                .collect(Collectors.toList());
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ConfigEtlServer> getEtlServerByKey(String serverKey) {
        // Try cache first
        ConfigEtlServer cached = serverCache.getIfPresent(serverKey);
        if (cached != null) {
            log.debug("ETL server '{}' retrieved from cache", serverKey);
            return Optional.of(cached);
        }

        try {
            // Try database
            Optional<ConfigEtlServer> dbResult = serverRepository.findByServerKey(serverKey);
            if (dbResult.isPresent()) {
                serverCache.put(serverKey, dbResult.get());
                log.debug("ETL server '{}' retrieved from database", serverKey);
                return dbResult;
            }
        } catch (Exception e) {
            log.warn("Error loading ETL server '{}' from database, attempting YAML fallback", serverKey, e);
        }

        // Fall back to YAML
        log.warn("ETL server '{}' not found in database, attempting to load from YAML", serverKey);
        usingYamlFallback = true;
        Optional<ConfigEtlServer> yamlResult = yamlConfigLoader.loadServerFromYaml(serverKey);
        yamlResult.ifPresent(s -> serverCache.put(serverKey, s));
        return yamlResult;
    }

    @Override
    @Transactional
    public ConfigEtlServer saveEtlServer(ConfigEtlServer server) {
        // Encrypt password
        if (server.getEncryptedPassword() != null && !server.getEncryptedPassword().isEmpty()) {
            // Check if already encrypted (encrypted passwords are longer)
            if (server.getEncryptedPassword().length() < 100) {
                server.setEncryptedPassword(encryptionService.encrypt(server.getEncryptedPassword()));
            }
        }

        // Set audit fields
        Long currentUserId = getCurrentUserId();
        Instant now = Instant.now();

        // Check if this is an update or create
        Optional<ConfigEtlServer> existing = serverRepository.findByServerKey(server.getServerKey());

        if (existing.isEmpty()) {
            server.setCreatedAt(now);
            server.setCreatedBy(currentUserId);
        } else {
            server.setCreatedAt(existing.get().getCreatedAt());
            server.setCreatedBy(existing.get().getCreatedBy());
        }

        // Validate before save
        validateEtlServer(server);

        // Update modification fields
        server.setUpdatedAt(now);
        server.setUpdatedBy(currentUserId);

        // Save
        ConfigEtlServer saved = serverRepository.save(server);

        // Invalidate cache
        invalidateCacheForServer(saved.getServerKey());

        // Audit log (mask password in details)
        String action = existing.isEmpty() ? "ETL_SERVER_CREATED" : "ETL_SERVER_UPDATED";
        try {
            Map<String, Object> details = new HashMap<>();
            if (existing.isPresent()) {
                Map<String, Object> beforeMap = convertToMap(existing.get());
                beforeMap.put("encryptedPassword", "***");
                details.put("before", beforeMap);
            }
            Map<String, Object> afterMap = convertToMap(saved);
            afterMap.put("encryptedPassword", "***");
            details.put("after", afterMap);
            auditService.logAction(currentUserId, action, "ETL_SERVER", saved.getServerKey(), details);
        } catch (Exception e) {
            log.error("Failed to audit ETL server save: {}", e.getMessage());
        }

        log.info("ETL server '{}' saved successfully", saved.getServerKey());
        return saved;
    }

    @Override
    @Transactional
    public void deleteEtlServer(String serverKey) {
        Optional<ConfigEtlServer> existing = serverRepository.findByServerKey(serverKey);
        if (existing.isEmpty()) {
            log.warn("Attempted to delete non-existent ETL server: {}", serverKey);
            return;
        }

        ConfigEtlServer server = existing.get();
        serverRepository.delete(server);
        invalidateCacheForServer(serverKey);

        // Audit log
        Long currentUserId = getCurrentUserId();
        try {
            Map<String, Object> details = new HashMap<>();
            Map<String, Object> serverMap = convertToMap(server);
            serverMap.put("encryptedPassword", "***");
            details.put("deleted", serverMap);
            auditService.logAction(currentUserId, "ETL_SERVER_DELETED", "ETL_SERVER", serverKey, details);
        } catch (Exception e) {
            log.error("Failed to audit ETL server delete: {}", e.getMessage());
        }

        log.info("ETL server '{}' deleted successfully", serverKey);
    }

    // ==================== Database Connection Operations ====================

    @Override
    @Transactional(readOnly = true)
    public List<ConfigDbConnection> getAllDbConnections(String environment) {
        try {
            Map<String, ConfigDbConnection> merged = new LinkedHashMap<>();
            List<ConfigDbConnection> dbRows = (environment == null || environment.isEmpty())
                ? connectionRepository.findAll()
                : connectionRepository.findByEnvironment(environment);
            if (dbRows != null) {
                for (ConfigDbConnection c : dbRows) {
                    if (c != null && c.getConnectionKey() != null) merged.put(c.getConnectionKey(), c);
                }
            }
            for (ConfigDbConnection c : yamlConfigLoader.loadAllConnectionsFromYaml()) {
                if (environment != null && !environment.isEmpty() && !environment.equals(c.getEnvironment())) continue;
                merged.putIfAbsent(c.getConnectionKey(), c);
            }
            if (merged.size() > (dbRows == null ? 0 : dbRows.size())) {
                usingYamlFallback = true;
            }
            return new ArrayList<>(merged.values());
        } catch (Exception e) {
            log.warn("Error loading DB connections from database for environment {}, falling back to YAML", environment, e);
            usingYamlFallback = true;
            return yamlConfigLoader.loadAllConnectionsFromYaml().stream()
                .filter(c -> environment == null || environment.isEmpty() || environment.equals(c.getEnvironment()))
                .collect(Collectors.toList());
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ConfigDbConnection> getDbConnectionByKey(String connectionKey) {
        // Try cache first
        ConfigDbConnection cached = connectionCache.getIfPresent(connectionKey);
        if (cached != null) {
            log.debug("DB connection '{}' retrieved from cache", connectionKey);
            return Optional.of(cached);
        }

        try {
            // Try database
            Optional<ConfigDbConnection> dbResult = connectionRepository.findByConnectionKey(connectionKey);
            if (dbResult.isPresent()) {
                connectionCache.put(connectionKey, dbResult.get());
                log.debug("DB connection '{}' retrieved from database", connectionKey);
                return dbResult;
            }
        } catch (Exception e) {
            log.warn("Error loading DB connection '{}' from database, attempting YAML fallback", connectionKey, e);
        }

        // Fall back to YAML
        log.warn("DB connection '{}' not found in database, attempting to load from YAML", connectionKey);
        usingYamlFallback = true;
        Optional<ConfigDbConnection> yamlResult = yamlConfigLoader.loadConnectionFromYaml(connectionKey);
        yamlResult.ifPresent(c -> connectionCache.put(connectionKey, c));
        return yamlResult;
    }

    @Override
    @Transactional
    public ConfigDbConnection saveDbConnection(ConfigDbConnection connection) {
        // Encrypt password
        if (connection.getEncryptedPassword() != null && !connection.getEncryptedPassword().isEmpty()) {
            // Check if already encrypted (encrypted passwords are longer)
            if (connection.getEncryptedPassword().length() < 100) {
                connection.setEncryptedPassword(encryptionService.encrypt(connection.getEncryptedPassword()));
            }
        }

        // Set audit fields
        Long currentUserId = getCurrentUserId();
        Instant now = Instant.now();

        // Check if this is an update or create
        Optional<ConfigDbConnection> existing = connectionRepository.findByConnectionKey(connection.getConnectionKey());

        if (existing.isEmpty()) {
            connection.setCreatedAt(now);
            connection.setCreatedBy(currentUserId);
        } else {
            connection.setCreatedAt(existing.get().getCreatedAt());
            connection.setCreatedBy(existing.get().getCreatedBy());
        }

        // Validate before save
        validateDbConnection(connection);

        // Update modification fields
        connection.setUpdatedAt(now);
        connection.setUpdatedBy(currentUserId);

        // Save
        ConfigDbConnection saved = connectionRepository.save(connection);

        // Invalidate cache
        invalidateCacheForConnection(saved.getConnectionKey());

        // Audit log (mask password in details)
        String action = existing.isEmpty() ? "DB_CONNECTION_CREATED" : "DB_CONNECTION_UPDATED";
        try {
            Map<String, Object> details = new HashMap<>();
            if (existing.isPresent()) {
                Map<String, Object> beforeMap = convertToMap(existing.get());
                beforeMap.put("encryptedPassword", "***");
                details.put("before", beforeMap);
            }
            Map<String, Object> afterMap = convertToMap(saved);
            afterMap.put("encryptedPassword", "***");
            details.put("after", afterMap);
            auditService.logAction(currentUserId, action, "DB_CONNECTION", saved.getConnectionKey(), details);
        } catch (Exception e) {
            log.error("Failed to audit DB connection save: {}", e.getMessage());
        }

        log.info("DB connection '{}' saved successfully", saved.getConnectionKey());
        return saved;
    }

    @Override
    @Transactional
    public void deleteDbConnection(String connectionKey) {
        Optional<ConfigDbConnection> existing = connectionRepository.findByConnectionKey(connectionKey);
        if (existing.isEmpty()) {
            log.warn("Attempted to delete non-existent DB connection: {}", connectionKey);
            return;
        }

        ConfigDbConnection connection = existing.get();
        connectionRepository.delete(connection);
        invalidateCacheForConnection(connectionKey);

        // Audit log
        Long currentUserId = getCurrentUserId();
        try {
            Map<String, Object> details = new HashMap<>();
            Map<String, Object> connMap = convertToMap(connection);
            connMap.put("encryptedPassword", "***");
            details.put("deleted", connMap);
            auditService.logAction(currentUserId, "DB_CONNECTION_DELETED", "DB_CONNECTION", connectionKey, details);
        } catch (Exception e) {
            log.error("Failed to audit DB connection delete: {}", e.getMessage());
        }

        log.info("DB connection '{}' deleted successfully", connectionKey);
    }

    // ==================== Query Operations for Step 1 UI ====================

    @Override
    @Transactional(readOnly = true)
    public List<String> getSitesByEnvironment(String environment) {
        return getAllPipelines(environment).stream()
            .map(ConfigPipeline::getSite)
            .distinct()
            .sorted()
            .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<SenderOption> getSendersBySite(String site, String environment) {
        return getSendersBySite(site, environment, null);
    }

    /**
     * Step 1 sender candidates, DB-first. Admin config_pipeline rows win; YAML rows
     * fill gaps. Each candidate senderId is then checked against the site's
     * third-party Oracle DTP_SENDER table — verified candidates carry
     * source="oracle"+verified=true and are the only ones Step 1 auto-selects,
     * so the data-flow pipeline always points at a sender the Oracle side knows.
     */
    @Override
    @Transactional(readOnly = true)
    public List<SenderOption> getSendersBySite(String site, String environment, Boolean historicalMode) {
        List<SenderOption> senders = new ArrayList<>();

        // Get pipelines for the site with optional historical filtering
        // (getPipelinesBySite already merges admin DB rows first, YAML fallback second)
        List<ConfigPipeline> pipelines = getPipelinesBySite(site, environment, historicalMode);

        // Extract unique senders from pipelines, remembering whether the winning
        // row came from the admin DB or the YAML fallback
        Set<String> dbKeys = dbPipelineKeys();
        Set<Integer> seenSenders = new HashSet<>();
        for (ConfigPipeline pipeline : pipelines) {
            if (pipeline.getSenderId() == null || seenSenders.contains(pipeline.getSenderId())) {
                continue;
            }
            String src = dbKeys.contains(pipeline.getPipelineKey()) ? "database" : "yaml";
            senders.add(new SenderOption(
                pipeline.getSenderId(),
                pipeline.getSocketPort(),
                pipeline.getConfigName() != null ? pipeline.getConfigName() : "Pipeline-" + pipeline.getPipelineKey(),
                src
            ));
            seenSenders.add(pipeline.getSenderId());
        }

        // Confirm each candidate against Oracle DTP_SENDER on the site connection
        List<SenderOption> confirmed = new ArrayList<>();
        Map<Integer, OracleSender> oracleSenders = loadOracleSenders(site, environment);
        for (SenderOption candidate : senders) {
            OracleSender oracle = oracleSenders.get(candidate.senderId());
            if (oracle != null) {
                confirmed.add(new SenderOption(
                    candidate.senderId(),
                    oracle.port() != null ? oracle.port() : candidate.port(),
                    oracle.name() != null && !oracle.name().isBlank() ? oracle.name() : candidate.name(),
                    "oracle",
                    Boolean.TRUE
                ));
            } else {
                log.warn("Sender {} for site {} env {} not found in Oracle DTP_SENDER — offered unverified (source={})",
                    candidate.senderId(), site, environment, candidate.source());
                confirmed.add(candidate);
            }
        }

        // Sort by sender ID for consistency
        confirmed.sort((a, b) -> a.senderId().compareTo(b.senderId()));

        return confirmed;
    }

    private Set<String> dbPipelineKeys() {
        try {
            List<ConfigPipeline> rows = pipelineRepository.findAll();
            if (rows == null) return Set.of();
            Set<String> keys = new HashSet<>();
            for (ConfigPipeline row : rows) {
                if (row != null && row.getPipelineKey() != null) keys.add(row.getPipelineKey());
            }
            return keys;
        } catch (Exception e) {
            return Set.of();
        }
    }

    /**
     * All sender ids (with live port/name) visible in the site's third-party
     * Oracle DTP_* schema. Empty map = Oracle unreachable or no site connection,
     * in which case every candidate stays unverified and Step 1 will not auto-select.
     */
    private Map<Integer, OracleSender> loadOracleSenders(String site, String environment) {
        Map<Integer, OracleSender> out = new HashMap<>();
        if (externalDbConfig == null || site == null || site.isBlank()) {
            return out;
        }
        String[] tables = {"DATAPORT_OWNER.DTP_SENDER", "DTP_SENDER"};
        try (java.sql.Connection conn = externalDbConfig.getConnection(site, environment)) {
            if (conn == null) return out;
            for (String table : tables) {
                try (java.sql.PreparedStatement ps = conn.prepareStatement(
                        "SELECT id, name, port FROM " + table);
                     java.sql.ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        int id = rs.getInt("id");
                        if (rs.wasNull()) continue;
                        String name = null;
                        try {
                            name = rs.getString("name");
                        } catch (Exception ignored) {
                        }
                        Integer port = null;
                        try {
                            int p = rs.getInt("port");
                            if (!rs.wasNull() && p > 0) port = p;
                        } catch (Exception ignored) {
                        }
                        out.putIfAbsent(id, new OracleSender(name, port));
                    }
                    if (!out.isEmpty()) return out;
                } catch (Exception queryEx) {
                    log.debug("Oracle sender scan missed table {} for site {}: {}", table, site, queryEx.getMessage());
                }
            }
        } catch (Exception ex) {
            log.debug("Oracle sender verification unavailable for site {} env {}: {}", site, environment, ex.getMessage());
        }
        return out;
    }

    private record OracleSender(String name, Integer port) {}

    // ==================== Validation ====================

    @Override
    public void validatePipeline(ConfigPipeline pipeline) {
        // Check site exists in connections — admin DB first, YAML fallback second
        // (a fresh install with no admin setups yet validates against the YAML files)
        if (!connectionExists(pipeline.getSite())) {
            throw new ValidationException("Site '" + pipeline.getSite() + "' not found in database connections");
        }

        // Check server exists — admin DB first, YAML fallback second
        if (!serverExists(pipeline.getServer())) {
            throw new ValidationException("Server '" + pipeline.getServer() + "' not found in ETL servers");
        }

        // Validate socket port range
        if (pipeline.getSocketPort() == null || pipeline.getSocketPort() < 1 || pipeline.getSocketPort() > 65535) {
            throw new ValidationException("Socket port must be between 1 and 65535, got " + pipeline.getSocketPort());
        }

        // Validate stage dependencies
        if (pipeline.getStages() != null && !pipeline.getStages().isEmpty()) {
            validateStageDependencies(pipeline.getStages());
        }

        // Validate historical mode distinct sender ID
        if (Boolean.TRUE.equals(pipeline.getHistoricalModeEnabled())) {
            validateDistinctSenderForHistoricalPipeline(pipeline);
        }
    }

    /**
     * Admin DB first, YAML fallback: true when the site/server key is known in
     * either source. Repository exceptions also fall through to YAML so a DB
     * outage never blocks validation of YAML-defined setups.
     */
    private boolean connectionExists(String site) {
        if (site == null || site.isBlank()) return false;
        try {
            if (connectionRepository.existsByConnectionKey(site)) return true;
        } catch (Exception e) {
            log.debug("DB connection existence check failed for '{}', trying YAML: {}", site, e.getMessage());
        }
        try {
            return !yamlConfigLoader.loadAllConnectionsFromYaml().stream()
                .filter(c -> site.equalsIgnoreCase(c.getConnectionKey()))
                .toList().isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    private boolean serverExists(String server) {
        if (server == null || server.isBlank()) return false;
        try {
            if (serverRepository.existsByServerKey(server)) return true;
        } catch (Exception e) {
            log.debug("ETL server existence check failed for '{}', trying YAML: {}", server, e.getMessage());
        }
        try {
            return !yamlConfigLoader.loadAllServersFromYaml().stream()
                .filter(s -> server.equalsIgnoreCase(s.getServerKey()))
                .toList().isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    private void validateStageDependencies(List<ConfigPipelineStage> stages) {
        Set<String> stageNames = stages.stream()
            .map(ConfigPipelineStage::getName)
            .collect(Collectors.toSet());

        for (ConfigPipelineStage stage : stages) {
            if (stage.getDependsOn() != null) {
                for (String dependency : stage.getDependsOn()) {
                    if (!stageNames.contains(dependency)) {
                        throw new ValidationException(
                            "Stage '" + stage.getName() + "' depends on non-existent stage '" + dependency + "'"
                        );
                    }
                }
            }
        }
    }

    private void validateDistinctSenderForHistoricalPipeline(ConfigPipeline pipeline) {
        // Get non-historical pipelines for the same site
        List<ConfigPipeline> nonHistoricalPipelines = pipelineRepository
            .findBySiteAndEnvironmentAndHistoricalModeEnabled(pipeline.getSite(), pipeline.getEnvironment(), false);

        for (ConfigPipeline other : nonHistoricalPipelines) {
            if (pipeline.getSenderId().equals(other.getSenderId())) {
                throw new ValidationException(
                    "Historical pipeline has same sender ID as non-historical pipeline for site '" + 
                    pipeline.getSite() + "'. Sender IDs must be distinct."
                );
            }
        }
    }

    @Override
    public void validateEtlServer(ConfigEtlServer server) {
        // Validate host is not empty
        if (server.getHost() == null || server.getHost().trim().isEmpty()) {
            throw new ValidationException("ETL server host cannot be empty");
        }

        // Validate SSH port range
        if (server.getSshPort() == null || server.getSshPort() < 1 || server.getSshPort() > 65535) {
            throw new ValidationException("SSH port must be between 1 and 65535, got " + server.getSshPort());
        }

        // Validate socket port range
        if (server.getSocketPort() == null || server.getSocketPort() < 1 || server.getSocketPort() > 65535) {
            throw new ValidationException("Socket port must be between 1 and 65535, got " + server.getSocketPort());
        }

        // Validate user is not empty
        if (server.getUser() == null || server.getUser().trim().isEmpty()) {
            throw new ValidationException("ETL server user cannot be empty");
        }

        // Validate password is not empty
        if (server.getEncryptedPassword() == null || server.getEncryptedPassword().trim().isEmpty()) {
            throw new ValidationException("ETL server password cannot be empty");
        }
    }

    @Override
    public void validateDbConnection(ConfigDbConnection connection) {
        // Validate connection key is not empty
        if (connection.getConnectionKey() == null || connection.getConnectionKey().trim().isEmpty()) {
            throw new ValidationException("DB connection key cannot be empty");
        }

        // Validate database type
        if (connection.getDbType() == null) {
            throw new ValidationException("Database type must be specified (ORACLE or POSTGRESQL)");
        }

        // Validate host is not empty
        if (connection.getHost() == null || connection.getHost().trim().isEmpty()) {
            throw new ValidationException("Database host cannot be empty");
        }

        // Validate schema is not empty
        if (connection.getSchema() == null || connection.getSchema().trim().isEmpty()) {
            throw new ValidationException("Database schema cannot be empty");
        }

        // Validate user is not empty
        if (connection.getUser() == null || connection.getUser().trim().isEmpty()) {
            throw new ValidationException("Database user cannot be empty");
        }

        // Validate password is not empty
        if (connection.getEncryptedPassword() == null || connection.getEncryptedPassword().trim().isEmpty()) {
            throw new ValidationException("Database password cannot be empty");
        }

        // Validate Hikari pool settings
        if (connection.getConnectionTimeoutMs() == null || connection.getConnectionTimeoutMs() <= 0) {
            throw new ValidationException("Connection timeout must be positive integer");
        }

        if (connection.getMaximumPoolSize() == null || connection.getMaximumPoolSize() <= 0) {
            throw new ValidationException("Maximum pool size must be positive integer");
        }

        if (connection.getMinimumIdle() == null || connection.getMinimumIdle() <= 0) {
            throw new ValidationException("Minimum idle must be positive integer");
        }

        if (connection.getMaximumPoolSize() < connection.getMinimumIdle()) {
            throw new ValidationException("Maximum pool size must be >= minimum idle");
        }
    }

    // ==================== Cache Management ====================

    @Override
    public void invalidateCache() {
        pipelineCache.invalidateAll();
        serverCache.invalidateAll();
        connectionCache.invalidateAll();
        log.debug("All configuration caches invalidated");
    }

    @Override
    public void invalidateCacheForPipeline(String pipelineKey) {
        pipelineCache.invalidate(pipelineKey);
        log.debug("Pipeline cache invalidated for key: {}", pipelineKey);
    }

    @Override
    public void invalidateCacheForServer(String serverKey) {
        serverCache.invalidate(serverKey);
        log.debug("ETL server cache invalidated for key: {}", serverKey);
    }

    @Override
    public void invalidateCacheForConnection(String connectionKey) {
        connectionCache.invalidate(connectionKey);
        log.debug("DB connection cache invalidated for key: {}", connectionKey);
    }

    // ==================== Health Check ====================

    @Override
    @Transactional(readOnly = true)
    public ConfigurationSourceHealth getHealthStatus() {
        try {
            long pipelinesDb = pipelineRepository.count();
            long serversDb = serverRepository.count();
            long connectionsDb = connectionRepository.count();

            long pipelinesYaml = (long) yamlConfigLoader.loadAllPipelinesFromYaml().size();
            long serversYaml = (long) yamlConfigLoader.loadAllServersFromYaml().size();
            long connectionsYaml = (long) yamlConfigLoader.loadAllConnectionsFromYaml().size();

            String status;
            String source;
            String message;

            if (pipelinesDb + serversDb + connectionsDb > 0) {
                status = "HEALTHY";
                if (pipelinesYaml + serversYaml + connectionsYaml > 0) {
                    source = "hybrid";
                    message = "Loading from both database and YAML";
                } else {
                    source = "database";
                    message = "Loading from database only";
                }
            } else if (pipelinesYaml + serversYaml + connectionsYaml > 0) {
                status = usingYamlFallback ? "YAML_FALLBACK" : "DATABASE_ONLY";
                source = "yaml";
                message = "Falling back to YAML configuration";
            } else {
                status = "UNHEALTHY";
                source = "none";
                message = "No configuration available from database or YAML";
            }

            return new ConfigurationSourceHealth(
                status, source, pipelinesDb, pipelinesYaml,
                serversDb, serversYaml, connectionsDb, connectionsYaml, message
            );
        } catch (Exception e) {
            log.error("Error getting configuration health status", e);
            return new ConfigurationSourceHealth(
                "UNHEALTHY", "unknown", 0L, 0L, 0L, 0L, 0L, 0L,
                "Error checking configuration status: " + e.getMessage()
            );
        }
    }

    // ==================== Helper Methods ====================

    private Long getCurrentUserId() {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getPrincipal() instanceof org.springframework.security.core.userdetails.UserDetails) {
                // Get user ID from principal if available, otherwise use username
                Object principal = auth.getPrincipal();
                // This assumes the user details has been extended with ID information
                return 0L; // Placeholder - implement based on your UserDetails structure
            }
        } catch (Exception e) {
            log.debug("Could not determine current user ID", e);
        }
        return null;
    }

    private Map<String, Object> convertToMap(Object entity) {
        try {
            return objectMapper.convertValue(entity, java.util.Map.class);
        } catch (Exception e) {
            log.warn("Failed to convert entity to map: {}", e.getMessage());
            return new HashMap<>();
        }
    }

    /**
     * Custom exception for validation errors.
     */
    public static class ValidationException extends RuntimeException {
        public ValidationException(String message) {
            super(message);
        }

        public ValidationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
