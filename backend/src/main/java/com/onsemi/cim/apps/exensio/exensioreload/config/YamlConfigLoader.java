package com.onsemi.cim.apps.exensio.exensioreload.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.onsemi.cim.apps.exensio.exensioreload.entity.*;
import java.io.IOException;
import java.util.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

/**
 * Loads configuration from YAML files for fallback when database is unavailable.
 * Supports parsing etljobs.yml, etlservers.yml, and dbconnections.yml.
 */
@Component
@Slf4j
public class YamlConfigLoader {

    @Value("${config.yaml.etljobs:classpath:etljobs.yml}")
    private Resource etlJobsYaml;

    @Value("${config.yaml.etlservers:classpath:etlservers.yml}")
    private Resource etlServersYaml;

    @Value("${config.yaml.dbconnections:classpath:dbconnections.yml}")
    private Resource dbConnectionsYaml;

    private final ObjectMapper yamlMapper;

    public YamlConfigLoader() {
        this.yamlMapper = new ObjectMapper(new YAMLFactory());
        this.yamlMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    // ========================================================================
    // PIPELINE YAML LOADING
    // ========================================================================

    /**
     * Load a single pipeline from YAML by key.
     *
     * @param pipelineKey the pipeline key to load
     * @return Optional containing the pipeline if found
     */
    public Optional<ConfigPipeline> loadPipelineFromYaml(String pipelineKey) {
        try {
            JsonNode root = yamlMapper.readTree(etlJobsYaml.getInputStream());
            JsonNode pipelines = root.get("pipelines");

            if (pipelines != null && pipelines.isArray()) {
                for (JsonNode node : pipelines) {
                    String key = node.get("pipelineKey").asText();
                    if (key.equals(pipelineKey)) {
                        return Optional.of(parsePipelineNode(node));
                    }
                }
            }
        } catch (IOException e) {
            log.error("Failed to load pipeline '{}' from YAML", pipelineKey, e);
        }
        return Optional.empty();
    }

    /**
     * Load all pipelines from YAML.
     *
     * @return list of all pipelines found in YAML
     */
    public List<ConfigPipeline> loadAllPipelinesFromYaml() {
        List<ConfigPipeline> pipelines = new ArrayList<>();
        try {
            JsonNode root = yamlMapper.readTree(etlJobsYaml.getInputStream());
            JsonNode pipelinesNode = root.get("pipelines");

            if (pipelinesNode != null && pipelinesNode.isArray()) {
                for (JsonNode node : pipelinesNode) {
                    pipelines.add(parsePipelineNode(node));
                }
            }
        } catch (IOException e) {
            log.error("Failed to load all pipelines from YAML", e);
        }
        return pipelines;
    }

    /**
     * Parse a single pipeline node from YAML.
     * Automatically detects historical pipelines and infers environment from site name.
     *
     * @param node the YAML node to parse
     * @return parsed ConfigPipeline entity
     */
    private ConfigPipeline parsePipelineNode(JsonNode node) {
        ConfigPipeline pipeline = new ConfigPipeline();
        pipeline.setPipelineKey(node.get("pipelineKey").asText());
        pipeline.setSite(node.get("site").asText());
        pipeline.setServer(node.get("server").asText());

        // Handle socketPort which may be integer or string in YAML
        JsonNode socketPortNode = node.get("socketPort");
        Integer socketPort = socketPortNode.isNumber() ? socketPortNode.asInt() : Integer.parseInt(socketPortNode.asText());
        pipeline.setSocketPort(socketPort);

        pipeline.setConfigName(node.has("configName") ? node.get("configName").asText() : "");
        pipeline.setSenderId(node.get("senderId").asInt());
        pipeline.setRerunPeriodMinutes(node.get("rerunPeriodMinutes").asInt());

        // Infer environment from site name (e.g., "CEBU-PROD" -> "PROD")
        String environment = inferEnvironment(pipeline.getSite());
        pipeline.setEnvironment(environment);

        // Detect historical pipeline
        String pipelineKey = pipeline.getPipelineKey();
        Boolean isHistorical = pipelineKey.toUpperCase().contains("HIST");
        pipeline.setHistoricalModeEnabled(isHistorical);

        pipeline.setEnabled(true);

        // Parse stages
        List<ConfigPipelineStage> stages = new ArrayList<>();
        JsonNode stagesNode = node.get("stages");
        if (stagesNode != null && stagesNode.isArray()) {
            int order = 0;
            for (JsonNode stageNode : stagesNode) {
                ConfigPipelineStage stage = new ConfigPipelineStage();
                stage.setPipeline(pipeline);
                stage.setName(stageNode.get("name").asText());
                
                String stageTypeStr = stageNode.get("type").asText().toUpperCase();
                stage.setType(StageType.valueOf(stageTypeStr));
                
                Integer timeoutMinutes = stageNode.has("timeoutMinutes") 
                    ? stageNode.get("timeoutMinutes").asInt() 
                    : 30; // Default to 30 if not specified
                stage.setTimeoutMinutes(timeoutMinutes);
                
                stage.setExecutionOrder(order++);

                // Parse dependsOn
                Set<String> dependsOn = new HashSet<>();
                if (stageNode.has("dependsOn") && stageNode.get("dependsOn").isArray()) {
                    for (JsonNode dep : stageNode.get("dependsOn")) {
                        dependsOn.add(dep.asText());
                    }
                }
                stage.setDependsOn(dependsOn);

                stages.add(stage);
            }
        }
        pipeline.setStages(stages);

        return pipeline;
    }

    // ========================================================================
    // ETL SERVER YAML LOADING
    // ========================================================================

    /**
     * Load a single ETL server from YAML by key.
     *
     * @param serverKey the server key to load
     * @return Optional containing the server if found
     */
    public Optional<ConfigEtlServer> loadServerFromYaml(String serverKey) {
        try {
            JsonNode root = yamlMapper.readTree(etlServersYaml.getInputStream());
            JsonNode serverNode = root.get(serverKey);

            if (serverNode != null) {
                return Optional.of(parseServerNode(serverKey, serverNode));
            }
        } catch (IOException e) {
            log.error("Failed to load ETL server '{}' from YAML", serverKey, e);
        }
        return Optional.empty();
    }

    /**
     * Load all ETL servers from YAML.
     *
     * @return list of all ETL servers found in YAML
     */
    public List<ConfigEtlServer> loadAllServersFromYaml() {
        List<ConfigEtlServer> servers = new ArrayList<>();
        try {
            JsonNode root = yamlMapper.readTree(etlServersYaml.getInputStream());
            root.fields().forEachRemaining(entry -> {
                String serverKey = entry.getKey();
                JsonNode serverNode = entry.getValue();
                // Skip commented or non-object entries
                if (serverNode.isObject()) {
                    servers.add(parseServerNode(serverKey, serverNode));
                }
            });
        } catch (IOException e) {
            log.error("Failed to load all ETL servers from YAML", e);
        }
        return servers;
    }

    /**
     * Parse a single ETL server node from YAML.
     * Automatically detects historical senders based on server key.
     *
     * @param serverKey the server key
     * @param node the YAML node to parse
     * @return parsed ConfigEtlServer entity
     */
    private ConfigEtlServer parseServerNode(String serverKey, JsonNode node) {
        ConfigEtlServer server = new ConfigEtlServer();
        server.setServerKey(serverKey);
        server.setHost(node.get("host").asText());
        server.setSshPort(node.get("sshPort").asInt());
        server.setSocketPort(node.get("socketPort").asInt());
        server.setUser(node.get("user").asText());
        
        // Password will be encrypted when saved to database
        server.setEncryptedPassword(node.get("password").asText());
        
        server.setTimeoutMs(node.get("timeoutMs").asInt());

        // Infer environment from server key (e.g., "CEBU-PROD" -> "PROD")
        String environment = inferEnvironment(serverKey);
        server.setEnvironment(environment);

        // Detect historical sender
        Boolean isHistorical = serverKey.toUpperCase().contains("HIST");
        server.setIsHistoricalSender(isHistorical);

        server.setEnabled(true);

        return server;
    }

    // ========================================================================
    // DATABASE CONNECTION YAML LOADING
    // ========================================================================

    /**
     * Load a single database connection from YAML by key.
     *
     * @param connectionKey the connection key to load
     * @return Optional containing the connection if found
     */
    public Optional<ConfigDbConnection> loadConnectionFromYaml(String connectionKey) {
        try {
            JsonNode root = yamlMapper.readTree(dbConnectionsYaml.getInputStream());
            JsonNode connNode = root.get(connectionKey);

            if (connNode != null && connNode.isObject()) {
                return Optional.of(parseConnectionNode(connectionKey, connNode));
            }
        } catch (IOException e) {
            log.error("Failed to load database connection '{}' from YAML", connectionKey, e);
        }
        return Optional.empty();
    }

    /**
     * Load all database connections from YAML.
     *
     * @return list of all database connections found in YAML
     */
    public List<ConfigDbConnection> loadAllConnectionsFromYaml() {
        List<ConfigDbConnection> connections = new ArrayList<>();
        try {
            JsonNode root = yamlMapper.readTree(dbConnectionsYaml.getInputStream());
            root.fields().forEachRemaining(entry -> {
                String connKey = entry.getKey();
                JsonNode connNode = entry.getValue();
                // Skip commented or non-object entries, and skip pipeline examples
                if (connNode.isObject() && !connKey.equals("EXAMPLE")) {
                    // Skip comment entries and lines with colons that aren't connection configs
                    if (!connKey.contains("#") && connNode.has("dbType")) {
                        connections.add(parseConnectionNode(connKey, connNode));
                    }
                }
            });
        } catch (IOException e) {
            log.error("Failed to load all database connections from YAML", e);
        }
        return connections;
    }

    /**
     * Parse a single database connection node from YAML.
     * Parses Hikari connection pool settings from the hikari section.
     *
     * @param connectionKey the connection key
     * @param node the YAML node to parse
     * @return parsed ConfigDbConnection entity
     */
    private ConfigDbConnection parseConnectionNode(String connectionKey, JsonNode node) {
        ConfigDbConnection connection = new ConfigDbConnection();
        connection.setConnectionKey(connectionKey);

        // Parse dbType
        String dbTypeStr = node.get("dbType").asText().toUpperCase();
        connection.setDbType(DbType.valueOf(dbTypeStr));

        connection.setSchema(node.get("schema").asText());
        connection.setHost(node.get("host").asText());
        connection.setUser(node.get("user").asText());
        
        // Password will be encrypted when saved to database
        connection.setEncryptedPassword(node.get("password").asText());

        // Parse Hikari settings
        JsonNode hikariNode = node.get("hikari");
        if (hikariNode != null) {
            Integer connectionTimeoutMs = hikariNode.has("connectionTimeoutMs")
                ? hikariNode.get("connectionTimeoutMs").asInt()
                : 20000; // Default
            connection.setConnectionTimeoutMs(connectionTimeoutMs);

            Integer maximumPoolSize = hikariNode.has("maximumPoolSize")
                ? hikariNode.get("maximumPoolSize").asInt()
                : 10; // Default
            connection.setMaximumPoolSize(maximumPoolSize);

            Integer minimumIdle = hikariNode.has("minimumIdle")
                ? hikariNode.get("minimumIdle").asInt()
                : 1; // Default
            connection.setMinimumIdle(minimumIdle);
        } else {
            // Use defaults if hikari section not present
            connection.setConnectionTimeoutMs(20000);
            connection.setMaximumPoolSize(10);
            connection.setMinimumIdle(1);
        }

        // Parse downloadUrl if present
        if (node.has("downloadUrl")) {
            connection.setDownloadUrl(node.get("downloadUrl").asText());
        }

        // Infer environment from connection key (e.g., "CEBU-PROD" -> "PROD")
        String environment = inferEnvironment(connectionKey);
        connection.setEnvironment(environment);

        connection.setEnabled(true);

        return connection;
    }

    // ========================================================================
    // UTILITY METHODS
    // ========================================================================

    /**
     * Infer environment from a name string.
     * Looks for "-PROD" or "-QA" suffix.
     * Defaults to "PROD" if neither is found.
     *
     * @param name the name to infer from
     * @return "PROD" or "QA"
     */
    private String inferEnvironment(String name) {
        if (name.endsWith("-PROD")) {
            return "PROD";
        } else if (name.endsWith("-QA")) {
            return "QA";
        }
        return "PROD"; // default
    }
}
