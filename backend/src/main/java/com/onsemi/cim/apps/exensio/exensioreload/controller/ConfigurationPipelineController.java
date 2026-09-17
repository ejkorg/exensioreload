package com.onsemi.cim.apps.exensio.exensioreload.controller;

import com.onsemi.cim.apps.exensio.exensioreload.entity.ConfigPipeline;
import com.onsemi.cim.apps.exensio.exensioreload.service.ConfigurationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.util.List;

/**
 * REST API controller for pipeline configuration management.
 * Provides CRUD operations for ETL pipeline configurations.
 * All administrative endpoints (POST, PUT, DELETE) require ADMIN or SUPER_ADMIN role.
 */
@Slf4j
@RestController
@RequestMapping("/api/configuration/pipelines")
public class ConfigurationPipelineController {

    private final ConfigurationService configurationService;

    public ConfigurationPipelineController(ConfigurationService configurationService) {
        this.configurationService = configurationService;
    }

    /**
     * Get all pipelines for a specific environment.
     * Optionally filter by historical mode.
     *
     * GET /api/configuration/pipelines?environment=PROD&historicalMode=false
     *
     * @param environment the environment to query (e.g., PROD, QA)
     * @param site optional site filter
     * @param historicalMode optional filter: true for historical only, false for non-historical only, null for all
     * @return list of pipelines matching the criteria
     */
    @GetMapping
    public ResponseEntity<List<ConfigPipeline>> getPipelines(
            @RequestParam String environment,
            @RequestParam(required = false) String site,
            @RequestParam(required = false) Boolean historicalMode) {
        
        log.info("Fetching pipelines: environment={}, site={}, historicalMode={}", environment, site, historicalMode);
        
        try {
            List<ConfigPipeline> pipelines;
            if (site != null) {
                pipelines = configurationService.getPipelinesBySite(site, environment, historicalMode);
            } else {
                List<ConfigPipeline> allPipelines = configurationService.getAllPipelines(environment);
                if (historicalMode != null) {
                    pipelines = allPipelines.stream()
                            .filter(p -> historicalMode ? p.getHistoricalModeEnabled() : !p.getHistoricalModeEnabled())
                            .toList();
                } else {
                    pipelines = allPipelines;
                }
            }
            return ResponseEntity.ok(pipelines);
        } catch (Exception e) {
            log.error("Error fetching pipelines", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Get a specific pipeline by its key.
     *
     * GET /api/configuration/pipelines/{key}
     *
     * @param key the pipeline key
     * @return the pipeline configuration
     */
    @GetMapping("/{key}")
    public ResponseEntity<ConfigPipeline> getPipelineByKey(@PathVariable String key) {
        log.info("Fetching pipeline: key={}", key);
        
        try {
            return configurationService.getPipelineByKey(key)
                    .map(ResponseEntity::ok)
                    .orElseGet(() -> {
                        log.warn("Pipeline not found: key={}", key);
                        return ResponseEntity.notFound().build();
                    });
        } catch (Exception e) {
            log.error("Error fetching pipeline by key: {}", key, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Create a new pipeline configuration.
     * Requires ADMIN or SUPER_ADMIN role.
     *
     * POST /api/configuration/pipelines
     *
     * @param pipeline the pipeline configuration to create
     * @return the created pipeline with 201 status
     */
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @PostMapping
    public ResponseEntity<ConfigPipeline> createPipeline(@Valid @RequestBody ConfigPipeline pipeline) {
        log.info("Creating new pipeline: key={}", pipeline.getPipelineKey());
        
        try {
            ConfigPipeline created = configurationService.savePipeline(pipeline);
            log.info("Pipeline created successfully: key={}", created.getPipelineKey());
            return ResponseEntity.status(HttpStatus.CREATED).body(created);
        } catch (IllegalArgumentException e) {
            log.warn("Validation error creating pipeline: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        } catch (Exception e) {
            log.error("Error creating pipeline", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Update an existing pipeline configuration.
     * Requires ADMIN or SUPER_ADMIN role.
     *
     * PUT /api/configuration/pipelines/{key}
     *
     * @param key the pipeline key to update
     * @param pipeline the updated pipeline configuration
     * @return the updated pipeline
     */
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @PutMapping("/{key}")
    public ResponseEntity<ConfigPipeline> updatePipeline(
            @PathVariable String key,
            @Valid @RequestBody ConfigPipeline pipeline) {
        
        log.info("Updating pipeline: key={}", key);
        
        try {
            // Verify the pipeline exists
            if (configurationService.getPipelineByKey(key).isEmpty()) {
                log.warn("Pipeline not found for update: key={}", key);
                return ResponseEntity.notFound().build();
            }
            
            // Ensure the key matches
            pipeline.setPipelineKey(key);
            ConfigPipeline updated = configurationService.savePipeline(pipeline);
            log.info("Pipeline updated successfully: key={}", updated.getPipelineKey());
            return ResponseEntity.ok(updated);
        } catch (IllegalArgumentException e) {
            log.warn("Validation error updating pipeline: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        } catch (Exception e) {
            log.error("Error updating pipeline: {}", key, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Delete a pipeline configuration.
     * Requires ADMIN or SUPER_ADMIN role.
     *
     * DELETE /api/configuration/pipelines/{key}
     *
     * @param key the pipeline key to delete
     * @return no content response
     */
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @DeleteMapping("/{key}")
    public ResponseEntity<Void> deletePipeline(@PathVariable String key) {
        log.info("Deleting pipeline: key={}", key);
        
        try {
            // Verify the pipeline exists
            if (configurationService.getPipelineByKey(key).isEmpty()) {
                log.warn("Pipeline not found for deletion: key={}", key);
                return ResponseEntity.notFound().build();
            }
            
            configurationService.deletePipeline(key);
            log.info("Pipeline deleted successfully: key={}", key);
            return ResponseEntity.noContent().build();
        } catch (Exception e) {
            log.error("Error deleting pipeline: {}", key, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
}
