package com.onsemi.cim.apps.exensio.exensioreload.controller;

import com.onsemi.cim.apps.exensio.exensioreload.service.ConfigurationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST API controller for configuration query endpoints used by Step 1 UI.
 * Provides read-only query methods for populating dropdown selections
 * and health status checks.
 */
@Slf4j
@RestController
@RequestMapping("/api/configuration")
public class ConfigurationQueryController {

    private final ConfigurationService configurationService;

    public ConfigurationQueryController(ConfigurationService configurationService) {
        this.configurationService = configurationService;
    }

    /**
     * Get all available sites for a specific environment.
     * Used by Step 1 UI to populate site dropdown.
     *
     * GET /api/configuration/sites?environment=PROD
     *
     * @param environment the environment to query (e.g., PROD, QA)
     * @return list of site names available in the environment, sorted alphabetically
     */
    @GetMapping("/sites")
    public ResponseEntity<List<String>> getSitesByEnvironment(
            @RequestParam String environment) {
        
        log.info("Fetching sites: environment={}", environment);
        
        try {
            List<String> sites = configurationService.getSitesByEnvironment(environment);
            return ResponseEntity.ok(sites.stream().sorted().toList());
        } catch (Exception e) {
            log.error("Error fetching sites for environment: {}", environment, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Get sender options for a specific site and environment.
     * Used by Step 1 UI to populate sender dropdown.
     *
     * GET /api/configuration/senders?site=CEBU&environment=PROD&historicalMode=false
     *
     * @param site the site name
     * @param environment the environment (e.g., PROD, QA)
     * @param historicalMode optional filter: true for historical senders only, false for non-historical only, null for all
     * @return list of available sender options for the site
     */
    @GetMapping("/senders")
    public ResponseEntity<List<ConfigurationService.SenderOption>> getSendersBySite(
            @RequestParam String site,
            @RequestParam String environment,
            @RequestParam(required = false) Boolean historicalMode) {
        
        log.info("Fetching senders: site={}, environment={}, historicalMode={}", site, environment, historicalMode);
        
        try {
            List<ConfigurationService.SenderOption> senders;
            if (historicalMode != null) {
                senders = configurationService.getSendersBySite(site, environment, historicalMode);
            } else {
                senders = configurationService.getSendersBySite(site, environment);
            }
            return ResponseEntity.ok(senders);
        } catch (Exception e) {
            log.error("Error fetching senders for site: {}, environment: {}", site, environment, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Get the health status of the configuration source.
     * Indicates whether configurations are loaded from database, YAML, or both.
     *
     * GET /api/configuration/health
     *
     * @return health status information
     */
    @GetMapping("/health")
    public ResponseEntity<ConfigurationService.ConfigurationSourceHealth> getConfigurationHealth() {
        log.info("Fetching configuration health status");
        
        try {
            ConfigurationService.ConfigurationSourceHealth health = configurationService.getHealthStatus();
            return ResponseEntity.ok(health);
        } catch (Exception e) {
            log.error("Error fetching configuration health status", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
}
