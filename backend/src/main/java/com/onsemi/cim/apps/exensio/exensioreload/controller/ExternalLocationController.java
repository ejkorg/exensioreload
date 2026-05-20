package com.onsemi.cim.apps.exensio.exensioreload.controller;

import com.onsemi.cim.apps.exensio.exensioreload.config.ExternalDbConfig;
import com.onsemi.cim.apps.exensio.exensioreload.entity.ExternalEnvironment;
import com.onsemi.cim.apps.exensio.exensioreload.entity.ExternalLocation;
import com.onsemi.cim.apps.exensio.exensioreload.service.ExternalLocationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@RestController
@RequestMapping("/api/environments")
public class ExternalLocationController {
    private final ExternalLocationService service;
    private final org.springframework.core.env.Environment env;
    private final ExternalDbConfig externalDbConfig;

    public ExternalLocationController(ExternalLocationService service, org.springframework.core.env.Environment env, ExternalDbConfig externalDbConfig) {
        this.service = service;
        this.env = env;
        this.externalDbConfig = externalDbConfig;
    }

    @GetMapping
    public List<ExternalEnvironment> listEnvironments() {
        return service.listEnvironments();
    }

    @GetMapping("/{envName}/locations")
    public List<ExternalLocation> listLocations(@PathVariable String envName) {
        return service.listLocationsForEnvironment(envName);
    }

    /**
     * Returns all configured sites from dbconnections.yml.
     * This matches the old backend's /api/sites endpoint.
     */
    @GetMapping("/sites")
    public List<String> listAllSites() {
        List<String> keys = new ArrayList<>(externalDbConfig.getConfiguredKeys());
        Collections.sort(keys);
        return keys;
    }

    @org.springframework.security.access.prepost.PreAuthorize("hasRole('ADMIN') or hasRole('ROLE_ADMIN') or hasRole('ROLE_SUPER_ADMIN')")
    @PostMapping("/import")
    public ResponseEntity<String> importCsv(@RequestParam("file") MultipartFile file) throws IOException {
        String expected = com.onsemi.cim.apps.exensio.exensioreload.config.ConfigUtils.getString(env, "external.import-token", "EXTERNAL_IMPORT_TOKEN", null);
        if (expected != null && !expected.isBlank()) {
            String provided = null;
            // check header
            // The header will be extracted by Spring if declared, but we read from request attribute here instead for simplicity.
            // Note: in production you may want to integrate with Spring Security instead.
            provided = ((org.springframework.web.context.request.ServletRequestAttributes)org.springframework.web.context.request.RequestContextHolder.currentRequestAttributes()).getRequest().getHeader("X-Admin-Token");
            if (provided == null || !provided.equals(expected)) {
                return ResponseEntity.status(403).body("forbidden");
            }
        }
        service.importCsv(file.getInputStream());
        return ResponseEntity.ok("imported");
    }
}
