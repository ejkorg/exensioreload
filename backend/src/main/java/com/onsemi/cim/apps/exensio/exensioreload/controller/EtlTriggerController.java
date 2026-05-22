package com.onsemi.cim.apps.exensio.exensioreload.controller;

import com.onsemi.cim.apps.exensio.exensioreload.config.EtlServerConfigLoader;
import com.onsemi.cim.apps.exensio.exensioreload.config.EtlTriggerProperties;
import com.onsemi.cim.apps.exensio.exensioreload.service.EtlSshTriggerService;
import com.onsemi.cim.apps.exensio.exensioreload.service.TriggerResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/etl-trigger")
public class EtlTriggerController {

    private static final Logger logger = LoggerFactory.getLogger(EtlTriggerController.class);

    private final EtlTriggerProperties etlTriggerProperties;
    private final EtlServerConfigLoader configLoader;
    private final EtlSshTriggerService etlSshTriggerService;

    public EtlTriggerController(EtlTriggerProperties etlTriggerProperties,
                                EtlServerConfigLoader configLoader,
                                EtlSshTriggerService etlSshTriggerService) {
        this.etlTriggerProperties = etlTriggerProperties;
        this.configLoader = configLoader;
        this.etlSshTriggerService = etlSshTriggerService;
    }

    /**
     * Execute the ETL SSH trigger for a staging request.
     * <p>
     * This endpoint triggers the ETL SSH process after a staging request completes.
     * It connects to configured ETL servers via SSH, extracts crontab jobs,
     * matches the sender port, and executes the corresponding command.
     * <p>
     * The trigger is non-blocking, single-attempt, and idempotent by requestId.
     * Staging operations never fail due to trigger errors.
     *
     * @param requestId     Unique request identifier
     * @param userId        User who triggered the action
     * @param site          Site name
     * @param location      Location name (optional)
     * @param senderConfigName Elasticsearch sender configuration name
     * @param request       HTTP request for remote IP extraction
     * @return TriggerResult with status and message
     */
    @PostMapping("/execute")
    public TriggerResult executeTrigger(
            @RequestParam String requestId,
            @RequestParam String userId,
            @RequestParam String site,
            @RequestParam(required = false) String location,
            @RequestParam String senderConfigName,
            HttpServletRequest request) {

        logger.info("ETL trigger execution requested: requestId={}, userId={}, site={}, location={}, senderConfigName={}",
                requestId, userId, site, location, senderConfigName);

        try {
            return etlSshTriggerService.execute(requestId, userId, site, location, senderConfigName);
        } catch (Exception e) {
            logger.error("Unexpected error during ETL trigger execution: {}", e.getMessage(), e);
            // Return failure result but don't fail the staging request
            return TriggerResult.failure("Unexpected error: " + e.getMessage());
        }
    }

    /**
     * Health check endpoint for ETL trigger service.
     *
     * @return Status message
     */
    @GetMapping("/health")
    public String health() {
        return "ETL Trigger Service is running";
    }

    /**
     * Reports whether ETL SSH trigger is enabled and how many servers were loaded from etlservers.yml.
     */
    @GetMapping("/status")
    public Map<String, Object> status() {
        configLoader.ensureLoaded();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("enabled", etlTriggerProperties.isEnabled());
        body.put("serversLoaded", configLoader.hasConfigs());
        body.put("serverCount", configLoader.getConfigs().size());
        if (configLoader.getLoadError() != null) {
            body.put("loadError", configLoader.getLoadError());
        }
        return body;
    }
}
