package com.onsemi.cim.apps.exensio.exensioreload.controller;

import com.onsemi.cim.apps.exensio.exensioreload.config.EtlServerConfigLoader;
import com.onsemi.cim.apps.exensio.exensioreload.config.EtlTriggerProperties;
import com.onsemi.cim.apps.exensio.exensioreload.dto.CrontabDiscoveryResult;
import com.onsemi.cim.apps.exensio.exensioreload.pipeline.PipelineConfig;
import com.onsemi.cim.apps.exensio.exensioreload.pipeline.PipelineConfigLoader;
import com.onsemi.cim.apps.exensio.exensioreload.service.EtlSshTriggerService;
import com.onsemi.cim.apps.exensio.exensioreload.service.TriggerResult;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/etl-trigger")
public class EtlTriggerController {

    private static final Logger logger = LoggerFactory.getLogger(EtlTriggerController.class);

    private final EtlTriggerProperties etlTriggerProperties;
    private final EtlServerConfigLoader configLoader;
    private final EtlSshTriggerService etlSshTriggerService;
    private final PipelineConfigLoader pipelineConfigLoader;

    public EtlTriggerController(EtlTriggerProperties etlTriggerProperties,
                                EtlServerConfigLoader configLoader,
                                EtlSshTriggerService etlSshTriggerService,
                                PipelineConfigLoader pipelineConfigLoader) {
        this.etlTriggerProperties = etlTriggerProperties;
        this.configLoader = configLoader;
        this.etlSshTriggerService = etlSshTriggerService;
        this.pipelineConfigLoader = pipelineConfigLoader;
    }

    /**
     * Execute the ETL SSH trigger for a staging request.
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
            return TriggerResult.failure("Unexpected error: " + e.getMessage());
        }
    }

    /**
     * Discovers and returns the crontab setup on the ETL server for a site or pipelineKey
     * without running the command.
     */
    @GetMapping("/discover")
    public ResponseEntity<CrontabDiscoveryResult> discoverCrontab(@RequestParam String identifier) {
        CrontabDiscoveryResult result = etlSshTriggerService.discoverCrontabBySiteOrKey(identifier);
        return ResponseEntity.ok(result);
    }

    /**
     * Returns all configured pipelines loaded from etljobs.yml.
     */
    @GetMapping("/pipelines")
    public ResponseEntity<List<Map<String, Object>>> getPipelines() {
        List<PipelineConfig> pipelines = pipelineConfigLoader.getAllPipelines();
        List<Map<String, Object>> response = new ArrayList<>();

        for (PipelineConfig p : pipelines) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("pipelineKey", p.pipelineKey());
            map.put("site", p.site());
            map.put("server", p.server());
            map.put("socketPort", p.socketPort());
            map.put("configName", p.configName());
            map.put("rerunPeriodMinutes", p.rerunPeriodMinutes());
            map.put("stageCount", p.stages().size());
            map.put("stageNames", p.stages().stream().map(s -> s.name()).toList());
            response.add(map);
        }

        return ResponseEntity.ok(response);
    }

    /**
     * Triggers a specific pipeline by pipelineKey with optional rerunPeriodMinutes, senderId, or port override.
     */
    @PostMapping("/trigger-pipeline")
    public ResponseEntity<TriggerResult> triggerPipeline(
            @RequestParam String pipelineKey,
            @RequestParam(required = false) String requestId,
            @RequestParam(required = false, defaultValue = "manual") String userId,
            @RequestParam(required = false) Integer rerunPeriodMinutes,
            @RequestParam(required = false) Integer senderId,
            @RequestParam(required = false) Integer port) {

        Optional<PipelineConfig> pipelineOpt = pipelineConfigLoader.getPipeline(pipelineKey);
        if (pipelineOpt.isEmpty()) {
            pipelineOpt = pipelineConfigLoader.loadPipelineConfig(pipelineKey);
        }

        if (pipelineOpt.isEmpty()) {
            return ResponseEntity.badRequest().body(TriggerResult.failure("Pipeline '" + pipelineKey + "' not found"));
        }

        TriggerResult result = etlSshTriggerService.triggerPipeline(
                pipelineOpt.get(), requestId, userId, rerunPeriodMinutes, senderId, port
        );
        return ResponseEntity.ok(result);
    }

    /**
     * Checks the sender queue table for a pipeline or site.
     * If senderId or port is provided (from the stepper Step 1 user selection), it overrides the etljobs.yml value.
     */
    @GetMapping("/queue-status")
    public ResponseEntity<Map<String, Object>> getQueueStatus(
            @RequestParam String identifier,
            @RequestParam(required = false) Integer senderId,
            @RequestParam(required = false) Integer port) {
        Map<String, Object> status = etlSshTriggerService.getQueueStatus(identifier, senderId, port);
        return ResponseEntity.ok(status);
    }

    /**
     * Triggers the cronjob for a pipeline ONLY if there are queued items remaining in the sender queue table.
     * Does not blindly trigger if queue is empty.
     * If senderId or port is provided (from the stepper Step 1 user selection), it overrides the etljobs.yml value.
     */
    @PostMapping("/trigger-if-queued")
    public ResponseEntity<TriggerResult> triggerIfQueued(
            @RequestParam String identifier,
            @RequestParam(required = false, defaultValue = "queue-monitor") String userId,
            @RequestParam(required = false) Integer senderId,
            @RequestParam(required = false) Integer port) {
        TriggerResult result = etlSshTriggerService.triggerIfQueued(identifier, userId, senderId, port);
        return ResponseEntity.ok(result);
    }

    /**
     * Discovers crontab setup on the ETL server by grepping ONLY for the unique port.
     */
    @GetMapping("/discover-by-port")
    public ResponseEntity<CrontabDiscoveryResult> discoverCrontabByPort(
            @RequestParam int port,
            @RequestParam(required = false) String site,
            @RequestParam(required = false) String server) {
        CrontabDiscoveryResult result = etlSshTriggerService.discoverCrontabByPort(port, site, server);
        return ResponseEntity.ok(result);
    }

    /**
     * Triggers crontab setup on the ETL server by grepping ONLY for the unique port.
     */
    @PostMapping("/trigger-by-port")
    public ResponseEntity<TriggerResult> triggerByPort(
            @RequestParam int port,
            @RequestParam(required = false) String site,
            @RequestParam(required = false) String server,
            @RequestParam(required = false) String requestId,
            @RequestParam(required = false, defaultValue = "manual") String userId) {
        TriggerResult result = etlSshTriggerService.triggerByPort(port, site, server, requestId, userId);
        return ResponseEntity.ok(result);
    }

    /**
     * Health check endpoint for ETL trigger service.
     */
    @GetMapping("/health")
    public String health() {
        return "ETL Trigger Service is running";
    }

    /**
     * Reports whether ETL SSH trigger is enabled, dry-run mode, and loaded servers count.
     */
    @GetMapping("/status")
    public Map<String, Object> status() {
        configLoader.ensureLoaded();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("enabled", etlTriggerProperties.isEnabled());
        body.put("dryRun", etlTriggerProperties.isDryRun());
        body.put("rerunPoolSize", etlTriggerProperties.getRerunPoolSize());
        body.put("serversLoaded", configLoader.hasConfigs());
        body.put("serverCount", configLoader.getConfigs().size());
        body.put("pipelineCount", pipelineConfigLoader.getAllPipelines().size());
        if (configLoader.getLoadError() != null) {
            body.put("loadError", configLoader.getLoadError());
        }
        return body;
    }
}
