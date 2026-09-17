package com.onsemi.cim.apps.exensio.exensioreload.controller;

import com.onsemi.cim.apps.exensio.exensioreload.service.ConfigMigrationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * REST API endpoints for configuration migration.
 * Only accessible to SUPER_ADMIN users.
 *
 * Endpoints:
 *   POST /api/admin/migrate-config              - Migrate YAML to database (skip existing)
 *   POST /api/admin/migrate-config-overwrite    - Migrate YAML to database (overwrite existing)
 */
@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('SUPER_ADMIN')")
@Slf4j
@Tag(name = "Configuration Migration", description = "Endpoints for migrating YAML configurations to database")
public class ConfigMigrationController {

    private final ConfigMigrationService configMigrationService;

    public ConfigMigrationController(ConfigMigrationService configMigrationService) {
        this.configMigrationService = configMigrationService;
    }

    /**
     * Migrate YAML configuration to database, skipping existing entries.
     *
     * @return migration report with summary and statistics
     */
    @PostMapping("/migrate-config")
    @Operation(
        summary = "Migrate YAML configuration to database",
        description = "Import all pipeline, ETL server, and database connection configurations from YAML files to database. " +
                      "Existing entries in the database are skipped.",
        security = @SecurityRequirement(name = "bearer")
    )
    public ResponseEntity<ConfigMigrationService.MigrationReport> migrateConfig() {
        log.info("Configuration migration endpoint called with overwrite=false");
        try {
            ConfigMigrationService.MigrationReport report = configMigrationService.migrateYamlToDatabase(false);
            return ResponseEntity.ok(report);
        } catch (Exception e) {
            log.error("Configuration migration failed", e);
            return ResponseEntity.status(500).build();
        }
    }

    /**
     * Migrate YAML configuration to database, overwriting existing entries.
     *
     * @return migration report with summary and statistics
     */
    @PostMapping("/migrate-config-overwrite")
    @Operation(
        summary = "Migrate YAML configuration to database with overwrite",
        description = "Import all pipeline, ETL server, and database connection configurations from YAML files to database. " +
                      "Existing entries in the database are updated.",
        security = @SecurityRequirement(name = "bearer")
    )
    public ResponseEntity<ConfigMigrationService.MigrationReport> migrateConfigOverwrite() {
        log.info("Configuration migration endpoint called with overwrite=true");
        try {
            ConfigMigrationService.MigrationReport report = configMigrationService.migrateYamlToDatabase(true);
            return ResponseEntity.ok(report);
        } catch (Exception e) {
            log.error("Configuration migration failed", e);
            return ResponseEntity.status(500).build();
        }
    }
}
