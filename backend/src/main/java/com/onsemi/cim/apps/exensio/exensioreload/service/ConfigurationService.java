package com.onsemi.cim.apps.exensio.exensioreload.service;

import com.onsemi.cim.apps.exensio.exensioreload.entity.ConfigDbConnection;
import com.onsemi.cim.apps.exensio.exensioreload.entity.ConfigEtlServer;
import com.onsemi.cim.apps.exensio.exensioreload.entity.ConfigPipeline;

import java.util.List;
import java.util.Optional;

/**
 * Service interface for managing ETL configuration.
 * Provides CRUD operations and query methods for pipelines, ETL servers, and database connections.
 * Supports database-first loading with YAML fallback for backward compatibility.
 */
public interface ConfigurationService {

    // ==================== Pipeline Operations ====================
    
    /**
     * Get all pipeline configurations for a specific environment.
     * @param environment the environment (e.g., PROD, QA)
     * @return list of all pipelines for the environment
     */
    List<ConfigPipeline> getAllPipelines(String environment);

    /**
     * Get pipelines for a specific site, with optional historical mode filtering.
     * @param site the site name
     * @param environment the environment
     * @param historicalMode if true, returns only historical pipelines; if false, returns only non-historical; if null, returns all
     * @return list of matching pipelines
     */
    List<ConfigPipeline> getPipelinesBySite(String site, String environment, Boolean historicalMode);

    /**
     * Get a specific pipeline by its key.
     * Uses cache-first, database-second, and YAML-fallback strategy.
     * @param pipelineKey the unique pipeline key
     * @return Optional containing the pipeline if found
     */
    Optional<ConfigPipeline> getPipelineByKey(String pipelineKey);

    /**
     * Save a pipeline configuration (create or update).
     * Validates the configuration and logs the change in audit log.
     * @param pipeline the pipeline to save
     * @return the saved pipeline
     * @throws ValidationException if validation fails
     */
    ConfigPipeline savePipeline(ConfigPipeline pipeline);

    /**
     * Delete a pipeline configuration.
     * @param pipelineKey the pipeline key to delete
     */
    void deletePipeline(String pipelineKey);

    // ==================== ETL Server Operations ====================

    /**
     * Get all ETL server configurations for a specific environment.
     * @param environment the environment (e.g., PROD, QA)
     * @return list of all ETL servers for the environment
     */
    List<ConfigEtlServer> getAllEtlServers(String environment);

    /**
     * Get all historical ETL servers for a specific environment.
     * @param environment the environment
     * @return list of historical servers (isHistoricalSender = true) for the environment
     */
    List<ConfigEtlServer> getHistoricalEtlServers(String environment);

    /**
     * Get a specific ETL server by its key.
     * Uses cache-first, database-second, and YAML-fallback strategy.
     * @param serverKey the unique server key
     * @return Optional containing the server if found
     */
    Optional<ConfigEtlServer> getEtlServerByKey(String serverKey);

    /**
     * Save an ETL server configuration (create or update).
     * Encrypts password and logs the change in audit log.
     * @param server the ETL server to save
     * @return the saved server
     * @throws ValidationException if validation fails
     */
    ConfigEtlServer saveEtlServer(ConfigEtlServer server);

    /**
     * Delete an ETL server configuration.
     * @param serverKey the server key to delete
     */
    void deleteEtlServer(String serverKey);

    // ==================== Database Connection Operations ====================

    /**
     * Get all database connection configurations for a specific environment.
     * @param environment the environment (e.g., PROD, QA)
     * @return list of all database connections for the environment
     */
    List<ConfigDbConnection> getAllDbConnections(String environment);

    /**
     * Get a specific database connection by its key.
     * Uses cache-first, database-second, and YAML-fallback strategy.
     * @param connectionKey the unique connection key
     * @return Optional containing the connection if found
     */
    Optional<ConfigDbConnection> getDbConnectionByKey(String connectionKey);

    /**
     * Save a database connection configuration (create or update).
     * Encrypts password and logs the change in audit log.
     * @param connection the database connection to save
     * @return the saved connection
     * @throws ValidationException if validation fails
     */
    ConfigDbConnection saveDbConnection(ConfigDbConnection connection);

    /**
     * Delete a database connection configuration.
     * @param connectionKey the connection key to delete
     */
    void deleteDbConnection(String connectionKey);

    // ==================== Query Operations for Step 1 UI ====================

    /**
     * Get all available sites for a specific environment.
     * @param environment the environment
     * @return list of site names available in the environment
     */
    List<String> getSitesByEnvironment(String environment);

    /**
     * Get sender options for a specific site and environment.
     * Returns sender candidates (sender ID, port, name) that can be used for data loading.
     * @param site the site name
     * @param environment the environment
     * @return list of available sender options for the site
     */
    List<SenderOption> getSendersBySite(String site, String environment);

    /**
     * Get sender options for a specific site and environment with historical mode support.
     * @param site the site name
     * @param environment the environment
     * @param historicalMode if true, returns only historical senders; if false, returns only non-historical senders
     * @return list of matching sender options
     */
    List<SenderOption> getSendersBySite(String site, String environment, Boolean historicalMode);

    // ==================== Validation ====================

    /**
     * Validate a pipeline configuration.
     * Checks referential integrity, port ranges, and stage dependencies.
     * @param pipeline the pipeline to validate
     * @throws ValidationException if validation fails
     */
    void validatePipeline(ConfigPipeline pipeline);

    /**
     * Validate an ETL server configuration.
     * Checks hostname/IP format, port ranges, and credentials.
     * @param server the ETL server to validate
     * @throws ValidationException if validation fails
     */
    void validateEtlServer(ConfigEtlServer server);

    /**
     * Validate a database connection configuration.
     * Checks connection string format and Hikari pool settings.
     * @param connection the database connection to validate
     * @throws ValidationException if validation fails
     */
    void validateDbConnection(ConfigDbConnection connection);

    // ==================== Cache Management ====================

    /**
     * Invalidate all configuration caches.
     */
    void invalidateCache();

    /**
     * Invalidate the cache entry for a specific pipeline.
     * @param pipelineKey the pipeline key
     */
    void invalidateCacheForPipeline(String pipelineKey);

    /**
     * Invalidate the cache entry for a specific ETL server.
     * @param serverKey the server key
     */
    void invalidateCacheForServer(String serverKey);

    /**
     * Invalidate the cache entry for a specific database connection.
     * @param connectionKey the connection key
     */
    void invalidateCacheForConnection(String connectionKey);

    // ==================== Health Check ====================

    /**
     * Get the configuration source health status.
     * Returns information about whether configurations are loaded from database, YAML, or both.
     * @return health status information
     */
    ConfigurationSourceHealth getHealthStatus();

    /**
     * DTO representing a sender option for Step 1 UI.
     * source: where the candidate came from — "database" (admin config_pipeline),
     * "yaml" (etljobs.yml fallback), "oracle" (confirmed live in the third-party
     * Oracle DTP_SENDER table).
     * verified: true when the senderId was found in DTP_SENDER on the site's
     * Oracle connection. Step 1 auto-selects only verified senders.
     */
    record SenderOption(
        Integer senderId,
        Integer port,
        String name,
        String source,
        Boolean verified
    ) {
        public SenderOption(Integer senderId, Integer port, String name, String source) {
            this(senderId, port, name, source, Boolean.FALSE);
        }
    }

    /**
     * DTO representing the health status of the configuration source.
     */
    record ConfigurationSourceHealth(
        String status,           // "HEALTHY", "DATABASE_ONLY", "YAML_FALLBACK", "UNHEALTHY"
        String source,           // "database", "yaml", or "hybrid"
        Long pipelinesFromDb,
        Long pipelinesFromYaml,
        Long serversFromDb,
        Long serversFromYaml,
        Long connectionsFromDb,
        Long connectionsFromYaml,
        String message
    ) {}
}
