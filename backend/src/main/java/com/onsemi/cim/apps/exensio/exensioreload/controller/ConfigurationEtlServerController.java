package com.onsemi.cim.apps.exensio.exensioreload.controller;

import com.onsemi.cim.apps.exensio.exensioreload.entity.ConfigEtlServer;
import com.onsemi.cim.apps.exensio.exensioreload.service.ConfigurationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.util.List;

/**
 * REST API controller for ETL server configuration management.
 * Provides CRUD operations for ETL server configurations with SSH connectivity details.
 * All administrative endpoints (POST, PUT, DELETE) require ADMIN or SUPER_ADMIN role.
 * Passwords are masked in responses for security.
 */
@Slf4j
@RestController
@RequestMapping("/api/configuration/etl-servers")
public class ConfigurationEtlServerController {

    private static final String PASSWORD_MASK = "***";

    private final ConfigurationService configurationService;

    public ConfigurationEtlServerController(ConfigurationService configurationService) {
        this.configurationService = configurationService;
    }

    /**
     * Get all ETL servers for a specific environment.
     * Optionally filter by historical-only flag.
     *
     * GET /api/configuration/etl-servers?environment=PROD&historicalOnly=false
     *
     * @param environment the environment to query (e.g., PROD, QA)
     * @param historicalOnly if true, returns only historical servers; default false
     * @return list of ETL servers matching the criteria
     */
    @GetMapping
    public ResponseEntity<List<ConfigEtlServer>> getEtlServers(
            @RequestParam String environment,
            @RequestParam(required = false, defaultValue = "false") Boolean historicalOnly) {
        
        log.info("Fetching ETL servers: environment={}, historicalOnly={}", environment, historicalOnly);
        
        try {
            List<ConfigEtlServer> servers;
            if (historicalOnly != null && historicalOnly) {
                servers = configurationService.getHistoricalEtlServers(environment);
            } else {
                servers = configurationService.getAllEtlServers(environment);
            }
            
            // Mask passwords in response
            servers.forEach(this::maskPassword);
            return ResponseEntity.ok(servers);
        } catch (Exception e) {
            log.error("Error fetching ETL servers", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Get a specific ETL server by its key.
     *
     * GET /api/configuration/etl-servers/{key}
     *
     * @param key the ETL server key
     * @return the ETL server configuration
     */
    @GetMapping("/{key}")
    public ResponseEntity<ConfigEtlServer> getEtlServerByKey(@PathVariable String key) {
        log.info("Fetching ETL server: key={}", key);
        
        try {
            return configurationService.getEtlServerByKey(key)
                    .map(server -> {
                        maskPassword(server);
                        return ResponseEntity.ok(server);
                    })
                    .orElseGet(() -> {
                        log.warn("ETL server not found: key={}", key);
                        return ResponseEntity.notFound().build();
                    });
        } catch (Exception e) {
            log.error("Error fetching ETL server by key: {}", key, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Create a new ETL server configuration.
     * Requires ADMIN or SUPER_ADMIN role.
     * Password will be encrypted before storage.
     *
     * POST /api/configuration/etl-servers
     *
     * @param server the ETL server configuration to create
     * @return the created ETL server with 201 status
     */
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @PostMapping
    public ResponseEntity<ConfigEtlServer> createEtlServer(@Valid @RequestBody ConfigEtlServer server) {
        log.info("Creating new ETL server: key={}", server.getServerKey());
        
        try {
            ConfigEtlServer created = configurationService.saveEtlServer(server);
            maskPassword(created);
            log.info("ETL server created successfully: key={}", created.getServerKey());
            return ResponseEntity.status(HttpStatus.CREATED).body(created);
        } catch (IllegalArgumentException e) {
            log.warn("Validation error creating ETL server: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        } catch (Exception e) {
            log.error("Error creating ETL server", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Update an existing ETL server configuration.
     * Requires ADMIN or SUPER_ADMIN role.
     *
     * PUT /api/configuration/etl-servers/{key}
     *
     * @param key the ETL server key to update
     * @param server the updated ETL server configuration
     * @return the updated ETL server
     */
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @PutMapping("/{key}")
    public ResponseEntity<ConfigEtlServer> updateEtlServer(
            @PathVariable String key,
            @Valid @RequestBody ConfigEtlServer server) {
        
        log.info("Updating ETL server: key={}", key);
        
        try {
            // Verify the server exists
            if (configurationService.getEtlServerByKey(key).isEmpty()) {
                log.warn("ETL server not found for update: key={}", key);
                return ResponseEntity.notFound().build();
            }
            
            // Ensure the key matches
            server.setServerKey(key);
            ConfigEtlServer updated = configurationService.saveEtlServer(server);
            maskPassword(updated);
            log.info("ETL server updated successfully: key={}", updated.getServerKey());
            return ResponseEntity.ok(updated);
        } catch (IllegalArgumentException e) {
            log.warn("Validation error updating ETL server: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        } catch (Exception e) {
            log.error("Error updating ETL server: {}", key, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Delete an ETL server configuration.
     * Requires ADMIN or SUPER_ADMIN role.
     *
     * DELETE /api/configuration/etl-servers/{key}
     *
     * @param key the ETL server key to delete
     * @return no content response
     */
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @DeleteMapping("/{key}")
    public ResponseEntity<Void> deleteEtlServer(@PathVariable String key) {
        log.info("Deleting ETL server: key={}", key);
        
        try {
            // Verify the server exists
            if (configurationService.getEtlServerByKey(key).isEmpty()) {
                log.warn("ETL server not found for deletion: key={}", key);
                return ResponseEntity.notFound().build();
            }
            
            configurationService.deleteEtlServer(key);
            log.info("ETL server deleted successfully: key={}", key);
            return ResponseEntity.noContent().build();
        } catch (Exception e) {
            log.error("Error deleting ETL server: {}", key, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Mask the password field in a server configuration for security.
     *
     * @param server the server configuration to mask
     */
    private void maskPassword(ConfigEtlServer server) {
        if (server != null && server.getEncryptedPassword() != null) {
            server.setEncryptedPassword(PASSWORD_MASK);
        }
    }
}
