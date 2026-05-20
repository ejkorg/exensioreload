package com.onsemi.cim.apps.exensio.resender.controller;

import com.onsemi.cim.apps.exensio.resender.config.ExternalDbConfig;
import com.onsemi.cim.apps.exensio.resender.repository.JdbcExternalMetadataRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/internal")
public class AdminController {

    private final ExternalDbConfig externalDbConfig;
    private final MeterRegistry meterRegistry;
    private final JdbcExternalMetadataRepository metadataRepository;
    private static final Logger logger = LoggerFactory.getLogger(AdminController.class);

    public AdminController(ExternalDbConfig externalDbConfig, ObjectProvider<MeterRegistry> meterRegistryProvider,
                           JdbcExternalMetadataRepository metadataRepository) {
        this.externalDbConfig = externalDbConfig;
        this.meterRegistry = meterRegistryProvider.getIfAvailable();
        this.metadataRepository = metadataRepository;
    }

    @GetMapping("/pools")
    public ResponseEntity<Map<String, Object>> listPools() {
        return ResponseEntity.ok(externalDbConfig.listPoolStats());
    }

    @PostMapping("/pools/recreate")
    public ResponseEntity<String> recreatePool(@RequestParam String key) {
        try {
            externalDbConfig.recreatePool(key);
            return ResponseEntity.ok("recreated");
        } catch (Exception e) {
            return ResponseEntity.status(500).body("error: " + e.getMessage());
        }
    }

    @GetMapping("/metrics")
    public ResponseEntity<Map<String, Object>> metrics(@RequestParam(name = "includeMeters", defaultValue = "false") boolean includeMeters) {
        Set<String> active = externalDbConfig.getActivePoolKeys();
        java.util.Map<String, Object> out = new java.util.HashMap<>();
        out.put("activePoolCount", active.size());
        out.put("activePools", active);
        int meterCount = meterRegistry == null ? 0 : meterRegistry.getMeters().size();
        out.put("meterCount", meterCount);
        if (includeMeters && meterRegistry != null) {
            out.put("meters", meterRegistry.getMeters().stream().map(m -> m.getId().getName()).collect(Collectors.toList()));
        }
        return ResponseEntity.ok(out);
    }

    /**
     * Admin: read current forceAllView flag used by the metadata repository.
     * Intended for operators; access should be restricted in production.
     */
    @PreAuthorize("hasRole('ADMIN') or hasRole('ROLE_ADMIN') or hasRole('ROLE_SUPER_ADMIN')")
    @GetMapping("/metadata/forceAllView")
    public ResponseEntity<Map<String, Object>> getForceAllView() {
        boolean v = false;
        try {
            v = this.metadataRepository != null ? this.metadataRepository.isForceAllMetadataView() : false;
        } catch (Exception ignore) {}
        return ResponseEntity.ok(java.util.Map.of("forceAllView", v));
    }

    /**
     * Admin: set the forceAllView flag at runtime. Note: this affects how
     * metadata queries choose views and may impact performance. This endpoint
     * should be restricted to internal/admin use only.
     */
    @PreAuthorize("hasRole('ADMIN') or hasRole('ROLE_ADMIN') or hasRole('ROLE_SUPER_ADMIN')")
    @PutMapping("/metadata/forceAllView")
    public ResponseEntity<Map<String, Object>> setForceAllView(@RequestParam(name = "value") boolean value) {
        try {
            if (this.metadataRepository != null) {
                boolean before = this.metadataRepository.isForceAllMetadataView();
                // apply change
                this.metadataRepository.setForceAllMetadataView(value);
                // Audit/log who changed it
                try {
                    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
                    String user = auth == null ? "anonymous" : (auth.getName() == null ? "anonymous" : auth.getName());
                    logger.info("[ADMIN AUDIT] user='{}' changed forceAllMetadataView from {} to {}", user, before, value);
                } catch (Exception logEx) {
                    logger.warn("[ADMIN AUDIT] failed to log audit for forceAllMetadataView change: {}", logEx.getMessage());
                }
            }
            return ResponseEntity.ok(java.util.Map.of("forceAllView", value));
        } catch (Exception ex) {
            logger.error("Failed setting forceAllView: {}", ex.getMessage(), ex);
            return ResponseEntity.status(500).body(java.util.Map.of("error", ex.getMessage()));
        }
    }
}
