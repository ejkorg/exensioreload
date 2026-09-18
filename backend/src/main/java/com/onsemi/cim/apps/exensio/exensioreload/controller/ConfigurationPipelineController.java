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

import com.onsemi.cim.apps.exensio.exensioreload.entity.ConfigPipeline;
import com.onsemi.cim.apps.exensio.exensioreload.service.ConfigurationService;

import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;

/**
 * REST API controller for pipeline configuration management.
 * Provides CRUD operations for ETL pipeline configurations.
 * All administrative endpoints (POST, PUT, DELETE) require ADMIN or SUPER_ADMIN role.
 * 
 * Pagination: All list endpoints support standard Spring Data pagination parameters:
 * - page: zero-indexed page number (default: 0)
 * - size: page size (default: 20)
 * - sort: comma-separated field names with optional desc/asc (e.g., "pipelineKey,asc" or "environment,desc")
 * 
 * Example: GET /api/configuration/pipelines?environment=PROD&page=0&size=20&sort=pipelineKey,asc
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
     * Get paginated pipelines for a specific environment with optional filtering.
     *
     * GET /api/configuration/pipelines?environment=PROD&page=0&size=20&sort=pipelineKey,asc
     *
     * @param environment the environment to query (e.g., PROD, QA) - REQUIRED
     * @param site optional site filter
     * @param historicalMode optional filter: true for historical only, false for non-historical only
     * @param search optional search filter for pipelineKey or site
     * @param pageable pagination parameters (page, size, sort)
     * @return paginated list of pipelines matching the criteria
     */
    @GetMapping
    public ResponseEntity<Page<ConfigPipeline>> getPipelines(
            @RequestParam String environment,
            @RequestParam(required = false) String site,
            @RequestParam(required = false) Boolean historicalMode,
            @RequestParam(required = false) String search,
            Pageable pageable) {
        
        log.info("Fetching paginated pipelines: environment={}, site={}, historicalMode={}, search={}, page={}, size={}, sort={}",
                environment, site, historicalMode, search, pageable.getPageNumber(), pageable.getPageSize(), pageable.getSort());
        
        try {
            List<ConfigPipeline> pipelines;
            if (site != null) {
                pipelines = configurationService.getPipelinesBySite(site, environment, historicalMode);
            } else {
                List<ConfigPipeline> allPipelines = configurationService.getAllPipelines(environment);
                if (historicalMode != null) {
                    pipelines = allPipelines.stream()
                            .filter(p -> historicalMode ? p.getHistoricalModeEnabled() : !p.getHistoricalModeEnabled())
                            .collect(Collectors.toList());
                } else {
                    pipelines = allPipelines;
                }
            }

            // Apply search filter
            if (search != null && !search.isBlank()) {
                String searchLower = search.toLowerCase();
                pipelines = pipelines.stream()
                        .filter(p -> p.getPipelineKey().toLowerCase().contains(searchLower) ||
                               (p.getSite() != null && p.getSite().toLowerCase().contains(searchLower)))
                        .collect(Collectors.toList());
            }

            // Apply sorting and pagination
            int pageNumber = pageable.getPageNumber();
            int pageSize = pageable.getPageSize();
            int start = pageNumber * pageSize;
            int end = Math.min(start + pageSize, pipelines.size());

            List<ConfigPipeline> pageContent = pipelines.subList(start, end);
            Page<ConfigPipeline> page = new PageImpl<>(pageContent, pageable, pipelines.size());

            log.info("Returned {} pipelines (page {}/{}, total {})", 
                    pageContent.size(), pageNumber, page.getTotalPages(), pipelines.size());
            return ResponseEntity.ok(page);
        } catch (Exception e) {
            log.error("Error fetching paginated pipelines", e);
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
