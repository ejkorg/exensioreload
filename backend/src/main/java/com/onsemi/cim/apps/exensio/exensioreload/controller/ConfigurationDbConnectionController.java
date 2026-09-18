package com.onsemi.cim.apps.exensio.exensioreload.controller;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.onsemi.cim.apps.exensio.exensioreload.entity.ConfigDbConnection;
import com.onsemi.cim.apps.exensio.exensioreload.service.ConfigurationService;

import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;

/**
 * REST API controller for database connection configuration management.
 * Provides CRUD operations for database connection configurations.
 * All administrative endpoints (POST, PUT, DELETE) require ADMIN or SUPER_ADMIN role.
 * Passwords are masked in responses for security.
 * 
 * Pagination: All list endpoints support standard Spring Data pagination parameters:
 * - page: zero-indexed page number (default: 0)
 * - size: page size (default: 20)
 * - sort: comma-separated field names with optional desc/asc (e.g., "connectionKey,asc" or "dbType,desc")
 * 
 * Example: GET /api/configuration/db-connections?environment=PROD&page=0&size=20&sort=connectionKey,asc
 */
@Slf4j
@RestController
@RequestMapping("/api/configuration/db-connections")
public class ConfigurationDbConnectionController {

    private static final String PASSWORD_MASK = "***";

    private final ConfigurationService configurationService;

    public ConfigurationDbConnectionController(ConfigurationService configurationService) {
        this.configurationService = configurationService;
    }

    /**
     * Get paginated database connections for a specific environment with optional filtering.
     *
     * GET /api/configuration/db-connections?environment=PROD&page=0&size=20&sort=connectionKey,asc
     *
     * @param environment the environment to query (e.g., PROD, QA) - REQUIRED
     * @param search optional search filter for connectionKey, host, or schema
     * @param pageable pagination parameters (page, size, sort)
     * @return paginated list of database connections matching the criteria
     */
    @GetMapping
    public ResponseEntity<Page<ConfigDbConnection>> getDbConnections(
            @RequestParam String environment,
            @RequestParam(required = false) String search,
            Pageable pageable) {
        
        log.info("Fetching paginated database connections: environment={}, search={}, page={}, size={}, sort={}",
                environment, search, pageable.getPageNumber(), pageable.getPageSize(), pageable.getSort());
        
        try {
            List<ConfigDbConnection> connections = configurationService.getAllDbConnections(environment);
            
            // Apply search filter
            if (search != null && !search.isBlank()) {
                String searchLower = search.toLowerCase();
                connections = connections.stream()
                        .filter(c -> c.getConnectionKey().toLowerCase().contains(searchLower) ||
                               c.getHost().toLowerCase().contains(searchLower) ||
                               c.getSchema().toLowerCase().contains(searchLower))
                        .collect(Collectors.toList());
            }

            // Mask passwords in response
            connections.forEach(this::maskPassword);

            // Apply pagination
            int pageNumber = pageable.getPageNumber();
            int pageSize = pageable.getPageSize();
            int start = pageNumber * pageSize;
            int end = Math.min(start + pageSize, connections.size());

            List<ConfigDbConnection> pageContent = connections.subList(start, end);
            Page<ConfigDbConnection> page = new PageImpl<>(pageContent, pageable, connections.size());

            log.info("Returned {} database connections (page {}/{}, total {})", 
                    pageContent.size(), pageNumber, page.getTotalPages(), connections.size());
            return ResponseEntity.ok(page);
        } catch (Exception e) {
            log.error("Error fetching paginated database connections", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Get a specific database connection by its key.
     *
     * GET /api/configuration/db-connections/{key}
     *
     * @param key the database connection key
     * @return the database connection configuration
     */
    @GetMapping("/{key}")
    public ResponseEntity<ConfigDbConnection> getDbConnectionByKey(@PathVariable String key) {
        log.info("Fetching database connection: key={}", key);
        
        try {
            return configurationService.getDbConnectionByKey(key)
                    .map(connection -> {
                        maskPassword(connection);
                        return ResponseEntity.ok(connection);
                    })
                    .orElseGet(() -> {
                        log.warn("Database connection not found: key={}", key);
                        return ResponseEntity.notFound().build();
                    });
        } catch (Exception e) {
            log.error("Error fetching database connection by key: {}", key, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Create a new database connection configuration.
     * Requires ADMIN or SUPER_ADMIN role.
     * Password will be encrypted before storage.
     *
     * POST /api/configuration/db-connections
     *
     * @param connection the database connection configuration to create
     * @return the created database connection with 201 status
     */
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @PostMapping
    public ResponseEntity<ConfigDbConnection> createDbConnection(
            @Valid @RequestBody ConfigDbConnection connection) {
        log.info("Creating new database connection: key={}", connection.getConnectionKey());
        
        try {
            ConfigDbConnection created = configurationService.saveDbConnection(connection);
            maskPassword(created);
            log.info("Database connection created successfully: key={}", created.getConnectionKey());
            return ResponseEntity.status(HttpStatus.CREATED).body(created);
        } catch (IllegalArgumentException e) {
            log.warn("Validation error creating database connection: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        } catch (Exception e) {
            log.error("Error creating database connection", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Update an existing database connection configuration.
     * Requires ADMIN or SUPER_ADMIN role.
     *
     * PUT /api/configuration/db-connections/{key}
     *
     * @param key the database connection key to update
     * @param connection the updated database connection configuration
     * @return the updated database connection
     */
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @PutMapping("/{key}")
    public ResponseEntity<ConfigDbConnection> updateDbConnection(
            @PathVariable String key,
            @Valid @RequestBody ConfigDbConnection connection) {
        
        log.info("Updating database connection: key={}", key);
        
        try {
            // Verify the connection exists
            if (configurationService.getDbConnectionByKey(key).isEmpty()) {
                log.warn("Database connection not found for update: key={}", key);
                return ResponseEntity.notFound().build();
            }
            
            // Ensure the key matches
            connection.setConnectionKey(key);
            ConfigDbConnection updated = configurationService.saveDbConnection(connection);
            maskPassword(updated);
            log.info("Database connection updated successfully: key={}", updated.getConnectionKey());
            return ResponseEntity.ok(updated);
        } catch (IllegalArgumentException e) {
            log.warn("Validation error updating database connection: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        } catch (Exception e) {
            log.error("Error updating database connection: {}", key, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Delete a database connection configuration.
     * Requires ADMIN or SUPER_ADMIN role.
     *
     * DELETE /api/configuration/db-connections/{key}
     *
     * @param key the database connection key to delete
     * @return no content response
     */
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @DeleteMapping("/{key}")
    public ResponseEntity<Void> deleteDbConnection(@PathVariable String key) {
        log.info("Deleting database connection: key={}", key);
        
        try {
            // Verify the connection exists
            if (configurationService.getDbConnectionByKey(key).isEmpty()) {
                log.warn("Database connection not found for deletion: key={}", key);
                return ResponseEntity.notFound().build();
            }
            
            configurationService.deleteDbConnection(key);
            log.info("Database connection deleted successfully: key={}", key);
            return ResponseEntity.noContent().build();
        } catch (Exception e) {
            log.error("Error deleting database connection: {}", key, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Mask the password field in a database connection configuration for security.
     *
     * @param connection the connection configuration to mask
     */
    private void maskPassword(ConfigDbConnection connection) {
        if (connection != null && connection.getEncryptedPassword() != null) {
            connection.setEncryptedPassword(PASSWORD_MASK);
        }
    }
}
