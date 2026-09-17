package com.onsemi.cim.apps.exensio.exensioreload.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.onsemi.cim.apps.exensio.exensioreload.config.YamlConfigLoader;
import com.onsemi.cim.apps.exensio.exensioreload.entity.AuditLog;
import com.onsemi.cim.apps.exensio.exensioreload.entity.ConfigDbConnection;
import com.onsemi.cim.apps.exensio.exensioreload.entity.ConfigEtlServer;
import com.onsemi.cim.apps.exensio.exensioreload.entity.ConfigPipeline;
import com.onsemi.cim.apps.exensio.exensioreload.repository.ConfigDbConnectionRepository;
import com.onsemi.cim.apps.exensio.exensioreload.repository.ConfigEtlServerRepository;
import com.onsemi.cim.apps.exensio.exensioreload.repository.ConfigPipelineRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

/**
 * Service for migrating YAML configuration to database.
 * Supports both initial import and overwriting existing configurations.
 * Provides detailed migration reports and audit logging.
 */
@Service
@Slf4j
public class ConfigMigrationService {

    private final ConfigPipelineRepository pipelineRepo;
    private final ConfigEtlServerRepository serverRepo;
    private final ConfigDbConnectionRepository connectionRepo;
    private final YamlConfigLoader yamlLoader;
    private final PasswordEncryptionService encryptionService;
    private final AuditService auditService;
    private final RoleService roleService;
    private final ObjectMapper objectMapper;

    public ConfigMigrationService(
        ConfigPipelineRepository pipelineRepo,
        ConfigEtlServerRepository serverRepo,
        ConfigDbConnectionRepository connectionRepo,
        YamlConfigLoader yamlLoader,
        PasswordEncryptionService encryptionService,
        AuditService auditService,
        RoleService roleService,
        ObjectMapper objectMapper
    ) {
        this.pipelineRepo = pipelineRepo;
        this.serverRepo = serverRepo;
        this.connectionRepo = connectionRepo;
        this.yamlLoader = yamlLoader;
        this.encryptionService = encryptionService;
        this.auditService = auditService;
        this.roleService = roleService;
        this.objectMapper = objectMapper;
    }

    /**
     * DTO representing a configuration migration report.
     */
    public record MigrationReport(
        long totalPipelines,
        long importedPipelines,
        long skippedPipelines,
        long failedPipelines,
        long totalServers,
        long importedServers,
        long skippedServers,
        long failedServers,
        long totalConnections,
        long importedConnections,
        long skippedConnections,
        long failedConnections,
        List<String> successfulEntries,
        List<String> failedEntries,
        Instant startTime,
        Instant endTime
    ) {
        public long getTotalImported() {
            return importedPipelines + importedServers + importedConnections;
        }

        public long getTotalSkipped() {
            return skippedPipelines + skippedServers + skippedConnections;
        }

        public long getTotalFailed() {
            return failedPipelines + failedServers + failedConnections;
        }
    }

    /**
     * Migrate YAML configuration to database.
     *
     * @param overwriteExisting if true, update existing entries; if false, skip them
     * @return migration report with summary and details
     */
    @Transactional
    public MigrationReport migrateYamlToDatabase(boolean overwriteExisting) {
        Instant startTime = Instant.now();
        log.info("Starting YAML to database migration. Overwrite existing: {}", overwriteExisting);

        // Tracking variables
        long pipelinesTotal = 0, pipelinesImported = 0, pipelinesSkipped = 0, pipelinesFailed = 0;
        long serversTotal = 0, serversImported = 0, serversSkipped = 0, serversFailed = 0;
        long connectionsTotal = 0, connectionsImported = 0, connectionsSkipped = 0, connectionsFailed = 0;

        List<String> successfulEntries = new ArrayList<>();
        List<String> failedEntries = new ArrayList<>();

        // Migrate pipelines
        try {
            List<ConfigPipeline> pipelines = yamlLoader.loadAllPipelinesFromYaml();
            pipelinesTotal = pipelines.size();

            for (ConfigPipeline pipeline : pipelines) {
                try {
                    boolean exists = pipelineRepo.existsByPipelineKey(pipeline.getPipelineKey());
                    if (exists && !overwriteExisting) {
                        log.debug("Pipeline '{}' already exists, skipping", pipeline.getPipelineKey());
                        pipelinesSkipped++;
                    } else if (exists && overwriteExisting) {
                        log.debug("Pipeline '{}' already exists, updating", pipeline.getPipelineKey());
                        Optional<ConfigPipeline> existingOpt = pipelineRepo.findByPipelineKey(pipeline.getPipelineKey());
                        if (existingOpt.isPresent()) {
                            pipeline.setId(existingOpt.get().getId());
                            pipeline.setCreatedAt(existingOpt.get().getCreatedAt());
                            pipeline.setCreatedBy(existingOpt.get().getCreatedBy());
                        }
                        pipeline.setUpdatedAt(Instant.now());
                        pipeline.setUpdatedBy(roleService.getCurrentUserId());
                        pipelineRepo.save(pipeline);
                        pipelinesImported++;
                        successfulEntries.add("Pipeline: " + pipeline.getPipelineKey());
                    } else {
                        log.debug("Creating new pipeline '{}'", pipeline.getPipelineKey());
                        pipeline.setCreatedAt(Instant.now());
                        pipeline.setCreatedBy(roleService.getCurrentUserId());
                        pipelineRepo.save(pipeline);
                        pipelinesImported++;
                        successfulEntries.add("Pipeline: " + pipeline.getPipelineKey());
                    }
                } catch (Exception e) {
                    log.error("Failed to migrate pipeline '{}': {}", pipeline.getPipelineKey(), e.getMessage(), e);
                    pipelinesFailed++;
                    failedEntries.add("Pipeline: " + pipeline.getPipelineKey() + " - " + e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("Failed to load pipelines from YAML: {}", e.getMessage(), e);
        }

        // Migrate ETL servers
        try {
            List<ConfigEtlServer> servers = yamlLoader.loadAllServersFromYaml();
            serversTotal = servers.size();

            for (ConfigEtlServer server : servers) {
                try {
                    // Encrypt password
                    String plainPassword = server.getEncryptedPassword();
                    server.setEncryptedPassword(encryptionService.encrypt(plainPassword));

                    // Detect and set historical sender flag
                    boolean isHistorical = server.getServerKey().toUpperCase().contains("HIST");
                    server.setIsHistoricalSender(isHistorical);

                    boolean exists = serverRepo.existsByServerKey(server.getServerKey());
                    if (exists && !overwriteExisting) {
                        log.debug("ETL Server '{}' already exists, skipping", server.getServerKey());
                        serversSkipped++;
                    } else if (exists && overwriteExisting) {
                        log.debug("ETL Server '{}' already exists, updating", server.getServerKey());
                        Optional<ConfigEtlServer> existingOpt = serverRepo.findByServerKey(server.getServerKey());
                        if (existingOpt.isPresent()) {
                            server.setId(existingOpt.get().getId());
                            server.setCreatedAt(existingOpt.get().getCreatedAt());
                            server.setCreatedBy(existingOpt.get().getCreatedBy());
                        }
                        server.setUpdatedAt(Instant.now());
                        server.setUpdatedBy(roleService.getCurrentUserId());
                        serverRepo.save(server);
                        serversImported++;
                        successfulEntries.add("ETL Server: " + server.getServerKey());
                    } else {
                        log.debug("Creating new ETL server '{}'", server.getServerKey());
                        server.setCreatedAt(Instant.now());
                        server.setCreatedBy(roleService.getCurrentUserId());
                        serverRepo.save(server);
                        serversImported++;
                        successfulEntries.add("ETL Server: " + server.getServerKey());
                    }
                } catch (Exception e) {
                    log.error("Failed to migrate ETL server '{}': {}", server.getServerKey(), e.getMessage(), e);
                    serversFailed++;
                    failedEntries.add("ETL Server: " + server.getServerKey() + " - " + e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("Failed to load ETL servers from YAML: {}", e.getMessage(), e);
        }

        // Migrate database connections
        try {
            List<ConfigDbConnection> connections = yamlLoader.loadAllConnectionsFromYaml();
            connectionsTotal = connections.size();

            for (ConfigDbConnection connection : connections) {
                try {
                    // Encrypt password
                    String plainPassword = connection.getEncryptedPassword();
                    connection.setEncryptedPassword(encryptionService.encrypt(plainPassword));

                    boolean exists = connectionRepo.existsByConnectionKey(connection.getConnectionKey());
                    if (exists && !overwriteExisting) {
                        log.debug("DB Connection '{}' already exists, skipping", connection.getConnectionKey());
                        connectionsSkipped++;
                    } else if (exists && overwriteExisting) {
                        log.debug("DB Connection '{}' already exists, updating", connection.getConnectionKey());
                        Optional<ConfigDbConnection> existingOpt = connectionRepo.findByConnectionKey(connection.getConnectionKey());
                        if (existingOpt.isPresent()) {
                            connection.setId(existingOpt.get().getId());
                            connection.setCreatedAt(existingOpt.get().getCreatedAt());
                            connection.setCreatedBy(existingOpt.get().getCreatedBy());
                        }
                        connection.setUpdatedAt(Instant.now());
                        connection.setUpdatedBy(roleService.getCurrentUserId());
                        connectionRepo.save(connection);
                        connectionsImported++;
                        successfulEntries.add("DB Connection: " + connection.getConnectionKey());
                    } else {
                        log.debug("Creating new DB connection '{}'", connection.getConnectionKey());
                        connection.setCreatedAt(Instant.now());
                        connection.setCreatedBy(roleService.getCurrentUserId());
                        connectionRepo.save(connection);
                        connectionsImported++;
                        successfulEntries.add("DB Connection: " + connection.getConnectionKey());
                    }
                } catch (Exception e) {
                    log.error("Failed to migrate DB connection '{}': {}", connection.getConnectionKey(), e.getMessage(), e);
                    connectionsFailed++;
                    failedEntries.add("DB Connection: " + connection.getConnectionKey() + " - " + e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("Failed to load DB connections from YAML: {}", e.getMessage(), e);
        }

        Instant endTime = Instant.now();

        MigrationReport report = new MigrationReport(
            pipelinesTotal, pipelinesImported, pipelinesSkipped, pipelinesFailed,
            serversTotal, serversImported, serversSkipped, serversFailed,
            connectionsTotal, connectionsImported, connectionsSkipped, connectionsFailed,
            successfulEntries, failedEntries, startTime, endTime
        );

        // Log migration summary
        logMigrationSummary(report);

        return report;
    }

    /**
     * Log the migration summary.
     * Creates an audit log entry with detailed statistics and result summary.
     *
     * @param report the migration report with statistics
     */
    private void logMigrationSummary(MigrationReport report) {
        Long duration = report.endTime().toEpochMilli() - report.startTime().toEpochMilli();

        Map<String, Object> details = new HashMap<>();
        details.put("totalPipelines", report.totalPipelines());
        details.put("importedPipelines", report.importedPipelines());
        details.put("skippedPipelines", report.skippedPipelines());
        details.put("failedPipelines", report.failedPipelines());
        details.put("totalServers", report.totalServers());
        details.put("importedServers", report.importedServers());
        details.put("skippedServers", report.skippedServers());
        details.put("failedServers", report.failedServers());
        details.put("totalConnections", report.totalConnections());
        details.put("importedConnections", report.importedConnections());
        details.put("skippedConnections", report.skippedConnections());
        details.put("failedConnections", report.failedConnections());
        details.put("totalImported", report.getTotalImported());
        details.put("totalSkipped", report.getTotalSkipped());
        details.put("totalFailed", report.getTotalFailed());
        details.put("durationMs", duration);

        // Create audit log entry
        auditService.logAction(
            roleService.getCurrentUserId(),
            AuditLog.Actions.CONFIG_MIGRATED,
            AuditLog.ResourceTypes.SYSTEM,
            "YAML_TO_DB_MIGRATION",
            details
        );

        // Log comprehensive summary
        String summary = String.format(
            "YAML to Database Migration Summary:%n" +
                "  Pipelines:   %d total, %d imported, %d skipped, %d failed%n" +
                "  Servers:     %d total, %d imported, %d skipped, %d failed%n" +
                "  Connections: %d total, %d imported, %d skipped, %d failed%n" +
                "  Total Imported: %d%n" +
                "  Total Skipped: %d%n" +
                "  Total Failed: %d%n" +
                "  Duration: %d ms",
            report.totalPipelines(), report.importedPipelines(), report.skippedPipelines(), report.failedPipelines(),
            report.totalServers(), report.importedServers(), report.skippedServers(), report.failedServers(),
            report.totalConnections(), report.importedConnections(), report.skippedConnections(), report.failedConnections(),
            report.getTotalImported(), report.getTotalSkipped(), report.getTotalFailed(),
            duration
        );

        log.info(summary);
    }
}
