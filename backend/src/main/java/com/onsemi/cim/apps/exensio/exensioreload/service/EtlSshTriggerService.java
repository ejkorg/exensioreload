package com.onsemi.cim.apps.exensio.exensioreload.service;

import com.onsemi.cim.apps.exensio.exensioreload.config.CrontabJob;
import com.onsemi.cim.apps.exensio.exensioreload.config.EtlServerConfig;
import com.onsemi.cim.apps.exensio.exensioreload.config.EtlServerConfigLoader;
import com.onsemi.cim.apps.exensio.exensioreload.config.EtlTriggerProperties;
import com.onsemi.cim.apps.exensio.exensioreload.config.ExternalDbConfig;
import com.onsemi.cim.apps.exensio.exensioreload.dto.CrontabDiscoveryResult;
import com.onsemi.cim.apps.exensio.exensioreload.entity.IdempotencyRecord;
import com.onsemi.cim.apps.exensio.exensioreload.pipeline.PipelineConfig;
import com.onsemi.cim.apps.exensio.exensioreload.pipeline.PipelineConfigLoader;
import com.onsemi.cim.apps.exensio.exensioreload.repository.IdempotencyRepository;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.concurrent.CustomizableThreadFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Service that orchestrates the ETL SSH trigger process, crontab discovery,
 * and queue-aware scheduled reruns within the Pipeline context.
 */
@Service
public class EtlSshTriggerService {

    private static final Logger logger = LoggerFactory.getLogger(EtlSshTriggerService.class);

    private final EtlTriggerProperties etlTriggerProperties;
    private final EtlServerConfigLoader configLoader;
    private final CrontabExtractor crontabExtractor;
    private final SenderPortExtractor senderPortExtractor;
    private final CrontabJobMatcher jobMatcher;
    private final AuditService auditService;
    private final IdempotencyRepository idempotencyRepository;
    private final PipelineConfigLoader pipelineConfigLoader;
    private final ExternalDbConfig externalDbConfig;
    private final RefDbService refDbService;
    private final ScheduledExecutorService rerunExecutor;

    public EtlSshTriggerService(
            EtlTriggerProperties etlTriggerProperties,
            EtlServerConfigLoader configLoader,
            CrontabExtractor crontabExtractor,
            SenderPortExtractor senderPortExtractor,
            CrontabJobMatcher jobMatcher,
            AuditService auditService,
            IdempotencyRepository idempotencyRepository,
            PipelineConfigLoader pipelineConfigLoader,
            @Autowired(required = false) ExternalDbConfig externalDbConfig,
            @Autowired(required = false) RefDbService refDbService) {
        this.etlTriggerProperties = etlTriggerProperties;
        this.configLoader = configLoader;
        this.crontabExtractor = crontabExtractor;
        this.senderPortExtractor = senderPortExtractor;
        this.jobMatcher = jobMatcher;
        this.auditService = auditService;
        this.idempotencyRepository = idempotencyRepository;
        this.pipelineConfigLoader = pipelineConfigLoader;
        this.externalDbConfig = externalDbConfig;
        this.refDbService = refDbService;
        this.rerunExecutor = Executors.newScheduledThreadPool(
                Math.max(2, etlTriggerProperties.getRerunPoolSize()),
                new CustomizableThreadFactory("etl-rerun-")
        );
    }

    @PreDestroy
    public void shutdown() {
        logger.info("Shutting down ETL rerun executor");
        rerunExecutor.shutdown();
    }

    /**
     * Checks how many items remain in the sender queue table for a pipeline,
     * strictly specific to that pipeline's senderId.
     * 1. Queries external site database DTP_SENDER_QUEUE_ITEM WHERE id_sender = ?
     * 2. Falls back to querying local SENDER_STAGE records WHERE sender_id = ? and status IN ('QUEUED_FOR_CP', 'STAGED')
     */
    public int getSenderQueueCount(PipelineConfig pipeline) {
        if (pipeline == null || pipeline.site() == null) {
            return 0;
        }
        return getSenderQueueCount(pipeline.site(), pipeline.senderId());
    }

    /**
     * Checks queue depth for a site and specific senderId.
     */
    public int getSenderQueueCount(String site, Integer senderId) {
        if (site == null || site.isBlank()) {
            return 0;
        }

        // 1. Query external site database (DATAPORT_OWNER.DTP_SENDER_QUEUE_ITEM) strictly for this senderId
        if (externalDbConfig != null) {
            try (Connection conn = externalDbConfig.getConnection(site)) {
                String[] tableCandidates = new String[]{"DATAPORT_OWNER.DTP_SENDER_QUEUE_ITEM", "DTP_SENDER_QUEUE_ITEM"};
                for (String table : tableCandidates) {
                    try {
                        String sql = (senderId != null)
                                ? "SELECT COUNT(1) FROM " + table + " WHERE id_sender = ?"
                                : "SELECT COUNT(1) FROM " + table;
                        try (PreparedStatement ps = conn.prepareStatement(sql)) {
                            if (senderId != null) {
                                ps.setInt(1, senderId);
                            }
                            try (ResultSet rs = ps.executeQuery()) {
                                if (rs.next()) {
                                    int count = rs.getInt(1);
                                    logger.debug("{} count for site {} senderId {}: {}", table, site, senderId, count);
                                    return count;
                                }
                            }
                        }
                    } catch (Exception queryEx) {
                        logger.debug("Could not query {} on site {} (senderId={}): {}", table, site, senderId, queryEx.getMessage());
                    }
                }
            } catch (Exception ex) {
                logger.debug("Could not connect to external DB on site {} (senderId={}): {}", site, senderId, ex.getMessage());
            }
        }

        // 2. Fallback: query local staging table (SENDER_STAGE) in QUEUED_FOR_CP / STAGED status strictly for this senderId
        if (refDbService != null) {
            try {
                int localCount = refDbService.countQueuedForSiteAndSender(site, senderId);
                logger.debug("Local SENDER_STAGE queued count for site {} senderId {}: {}", site, senderId, localCount);
                return localCount;
            } catch (Exception ex) {
                logger.debug("Could not query local queued count for site {} (senderId={}): {}", site, senderId, ex.getMessage());
            }
        }

        return 0;
    }

    /**
     * Gets queue status summary for a pipeline or site.
     */
    public Map<String, Object> getQueueStatus(String identifier) {
        Optional<PipelineConfig> pipelineOpt = pipelineConfigLoader.getPipeline(identifier);
        if (pipelineOpt.isEmpty()) {
            pipelineOpt = pipelineConfigLoader.loadPipelineConfig(identifier);
        }
        Map<String, Object> status = new LinkedHashMap<>();
        if (pipelineOpt.isPresent()) {
            PipelineConfig p = pipelineOpt.get();
            int count = getSenderQueueCount(p);
            status.put("pipelineKey", p.pipelineKey());
            status.put("site", p.site());
            status.put("server", p.server());
            status.put("senderId", p.senderId());
            status.put("socketPort", p.socketPort());
            status.put("configName", p.configName());
            status.put("queuedItemCount", count);
            status.put("hasQueuedItems", count > 0);
        } else {
            status.put("error", "Pipeline not found: " + identifier);
            status.put("queuedItemCount", 0);
            status.put("hasQueuedItems", false);
        }
        return status;
    }

    /**
     * Inspects and discovers the crontab command on the ETL server for a pipeline without executing it.
     */
    public CrontabDiscoveryResult discoverCrontabSetup(PipelineConfig pipeline) {
        if (pipeline == null) {
            return CrontabDiscoveryResult.failure(null, null, null, null, "Pipeline is null");
        }

        configLoader.ensureLoaded();
        EtlServerConfig serverConfig = resolveServerConfig(pipeline.server(), pipeline.site());
        if (serverConfig == null) {
            return CrontabDiscoveryResult.failure(
                    pipeline.pipelineKey(), pipeline.site(), pipeline.server(), null,
                    "No matching ETL server configured for '" + pipeline.server() + "'"
            );
        }

        try {
            List<CrontabJob> jobs = crontabExtractor.extract(serverConfig);
            CrontabJob matchedJob = jobMatcher.match(jobs, pipeline.socketPort(), pipeline.configName());

            if (matchedJob == null) {
                return CrontabDiscoveryResult.notFound(
                        pipeline.pipelineKey(), pipeline.site(), serverConfig.getName(), serverConfig.getHost(),
                        serverConfig.getSshPort(), pipeline.socketPort(), pipeline.configName(), jobs.size()
                );
            }

            return CrontabDiscoveryResult.success(
                    pipeline.pipelineKey(), pipeline.site(), serverConfig.getName(), serverConfig.getHost(),
                    serverConfig.getSshPort(), pipeline.socketPort(), pipeline.configName(),
                    matchedJob.getCommand(), matchedJob.getSchedule(), matchedJob.getRawLine(), jobs.size()
            );
        } catch (Exception e) {
            logger.error("Crontab discovery failed for pipeline '{}' on server '{}': {}",
                    pipeline.pipelineKey(), serverConfig.getName(), e.getMessage(), e);
            return CrontabDiscoveryResult.failure(
                    pipeline.pipelineKey(), pipeline.site(), serverConfig.getName(), serverConfig.getHost(),
                    "SSH crontab extraction failed: " + e.getMessage()
            );
        }
    }

    /**
     * Discovers crontab setup by site or pipelineKey.
     */
    public CrontabDiscoveryResult discoverCrontabBySiteOrKey(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            return CrontabDiscoveryResult.failure(null, null, null, null, "Identifier cannot be empty");
        }

        Optional<PipelineConfig> pipelineOpt = pipelineConfigLoader.getPipeline(identifier);
        if (pipelineOpt.isEmpty()) {
            pipelineOpt = pipelineConfigLoader.loadPipelineConfig(identifier);
        }

        if (pipelineOpt.isPresent()) {
            return discoverCrontabSetup(pipelineOpt.get());
        }

        // Fallback: search server directly by site
        configLoader.ensureLoaded();
        List<EtlServerConfig> servers = configLoader.getConfigsForSite(identifier);
        if (servers.isEmpty()) {
            return CrontabDiscoveryResult.failure(null, identifier, null, null, "No server or pipeline found for " + identifier);
        }

        EtlServerConfig server = servers.get(0);
        try {
            List<CrontabJob> jobs = crontabExtractor.extract(server);
            CrontabJob matched = jobMatcher.match(jobs, server.getSocketPort(), null);
            if (matched != null) {
                return CrontabDiscoveryResult.success(
                        identifier, identifier, server.getName(), server.getHost(),
                        server.getSshPort(), server.getSocketPort(), "",
                        matched.getCommand(), matched.getSchedule(), matched.getRawLine(), jobs.size()
                );
            }
            return CrontabDiscoveryResult.notFound(
                    identifier, identifier, server.getName(), server.getHost(),
                    server.getSshPort(), server.getSocketPort(), "", jobs.size()
            );
        } catch (Exception e) {
            return CrontabDiscoveryResult.failure(identifier, identifier, server.getName(), server.getHost(), e.getMessage());
        }
    }

    /**
     * Checks if there are queued items in the sender queue table for this pipeline/site,
     * and only triggers the cronjob if queued items exist (does not blindly trigger).
     */
    public TriggerResult triggerIfQueued(String identifier, String userId) {
        Optional<PipelineConfig> pipelineOpt = pipelineConfigLoader.getPipeline(identifier);
        if (pipelineOpt.isEmpty()) {
            pipelineOpt = pipelineConfigLoader.loadPipelineConfig(identifier);
        }

        if (pipelineOpt.isEmpty()) {
            return TriggerResult.failure("Pipeline '" + identifier + "' not found");
        }

        PipelineConfig pipeline = pipelineOpt.get();
        int queuedCount = getSenderQueueCount(pipeline);
        if (queuedCount == 0) {
            String msg = "Sender queue table for pipeline '" + pipeline.pipelineKey() + "' is empty (0 items). Cronjob run skipped.";
            logger.info(msg);
            return TriggerResult.success(msg, null, 0);
        }

        logger.info("Pipeline '{}' has {} queued item(s) in sender queue table. Triggering cronjob...",
                pipeline.pipelineKey(), queuedCount);
        return triggerPipeline(pipeline, "queue-trigger-" + System.currentTimeMillis(), userId, null);
    }

    /**
     * Triggers crontab execution for a specific pipeline with queue-aware rerun scheduling.
     */
    public TriggerResult triggerPipeline(PipelineConfig pipeline, String requestId, String userId,
                                        Integer rerunPeriodMinutesOverride) {
        if (!etlTriggerProperties.isEnabled()) {
            logger.debug("ETL SSH trigger disabled (etl.trigger.enabled=false) for requestId: {}", requestId);
            return TriggerResult.notConfigured();
        }

        configLoader.ensureLoaded();
        if (pipeline == null) {
            return TriggerResult.failure("Pipeline configuration is null");
        }

        // Idempotency check
        if (requestId != null) {
            Optional<IdempotencyRecord> cached = idempotencyRepository.findById(requestId);
            if (cached.isPresent()) {
                IdempotencyRecord record = cached.get();
                logger.info("Returning cached result for requestId: {} (status: {})", requestId, record.getStatus());
                return new TriggerResult(record.getStatus(), record.getMessage());
            }
        }

        EtlServerConfig serverConfig = resolveServerConfig(pipeline.server(), pipeline.site());
        if (serverConfig == null) {
            String msg = "No ETL server configured for pipeline " + pipeline.pipelineKey() + " (server=" + pipeline.server() + ")";
            logger.warn(msg);
            return TriggerResult.notConfigured();
        }

        String remoteIp = getRemoteIp();
        CrontabDiscoveryResult discovery = discoverCrontabSetup(pipeline);

        if (!"SUCCESS".equalsIgnoreCase(discovery.status()) || discovery.matchedCommand() == null) {
            String msg = "Crontab matching failed: " + discovery.message();
            logger.warn("Cannot trigger pipeline '{}': {}", pipeline.pipelineKey(), msg);
            auditService.logEtlTrigger(requestId, userId, pipeline.site(), null, serverConfig.getName(),
                    pipeline.socketPort(), "failure", msg, remoteIp);
            return TriggerResult.failure(msg);
        }

        String commandToRun = discovery.matchedCommand();
        Integer rerunMinutes = rerunPeriodMinutesOverride != null ? rerunPeriodMinutesOverride : pipeline.rerunPeriodMinutes();

        if (etlTriggerProperties.isDryRun()) {
            String msg = "[DRY RUN] Would execute on " + serverConfig.getName() + ": " + commandToRun
                    + (rerunMinutes != null && rerunMinutes > 0 ? " (queue-aware rerun in " + rerunMinutes + "m)" : "");
            logger.info(msg);
            TriggerResult res = TriggerResult.success(msg, commandToRun, rerunMinutes);
            if (requestId != null) {
                storeIdempotency(requestId, res);
            }
            return res;
        }

        try {
            // Execute the crontab command via SSH
            logger.info("Executing ETL trigger command on server {} ({}:{}): {}",
                    serverConfig.getName(), serverConfig.getHost(), serverConfig.getSshPort(), commandToRun);
            executeSshCommand(serverConfig, commandToRun);

            // Schedule queue-aware rerun if configured
            if (rerunMinutes != null && rerunMinutes > 0) {
                scheduleRerun(pipeline, serverConfig, commandToRun, requestId, userId, rerunMinutes, 1);
            }

            TriggerResult result = TriggerResult.success(
                    "ETL trigger executed on " + serverConfig.getName() + ": " + commandToRun,
                    commandToRun, rerunMinutes
            );

            auditService.logEtlTrigger(
                    requestId, userId, pipeline.site(), null,
                    serverConfig.getName(), pipeline.socketPort(),
                    result.getStatus(), result.getMessage(), remoteIp
            );

            if (requestId != null) {
                storeIdempotency(requestId, result);
            }

            return result;

        } catch (Exception e) {
            logger.error("ETL trigger execution failed on server {}: {}", serverConfig.getName(), e.getMessage(), e);
            TriggerResult failure = TriggerResult.failure("SSH execution error: " + e.getMessage());
            auditService.logEtlTrigger(
                    requestId, userId, pipeline.site(), null,
                    serverConfig.getName(), pipeline.socketPort(),
                    "failure", failure.getMessage(), remoteIp
            );
            return failure;
        }
    }

    /**
     * Legacy execute method for existing controllers and session creation.
     */
    public TriggerResult execute(String requestId, String userId, String site,
                                 String location, String senderConfigName) {
        if (!etlTriggerProperties.isEnabled()) {
            logger.debug("ETL SSH trigger disabled (etl.trigger.enabled=false) for requestId: {}", requestId);
            return TriggerResult.notConfigured();
        }

        configLoader.ensureLoaded();
        if (!configLoader.hasConfigs()) {
            logger.info("ETL SSH trigger skipped - no servers loaded from etlservers.yml for requestId: {}", requestId);
            return TriggerResult.notConfigured();
        }

        // Check if there is a configured pipeline for this site
        if (site != null && !site.isBlank()) {
            List<PipelineConfig> pipelines = pipelineConfigLoader.getPipelinesForSite(site);
            if (!pipelines.isEmpty()) {
                PipelineConfig pipeline = pipelines.get(0);
                return triggerPipeline(pipeline, requestId, userId, null);
            }
        }

        // Fallback: multi-server matching
        if (requestId != null) {
            Optional<IdempotencyRecord> cached = idempotencyRepository.findById(requestId);
            if (cached.isPresent()) {
                IdempotencyRecord record = cached.get();
                return new TriggerResult(record.getStatus(), record.getMessage());
            }
        }

        Optional<Integer> senderPortOpt = senderPortExtractor.extractPort(senderConfigName);
        Integer senderPort = senderPortOpt.orElse(null);
        String remoteIp = getRemoteIp();

        List<EtlServerConfig> targetServers = configLoader.getConfigsForSite(site);
        List<TriggerResult> results = new ArrayList<>();

        for (EtlServerConfig config : targetServers) {
            TriggerResult result = processEtlServer(config, senderPort, requestId, userId, site, location, remoteIp);
            results.add(result);
        }

        TriggerResult overallResult = determineOverallStatus(results);

        for (int i = 0; i < targetServers.size(); i++) {
            EtlServerConfig config = targetServers.get(i);
            TriggerResult result = results.get(i);
            auditService.logEtlTrigger(
                    requestId, userId, site, location,
                    config.getName(), senderPort,
                    result.getStatus(), result.getMessage(), remoteIp
            );
        }

        if (requestId != null) {
            storeIdempotency(requestId, overallResult);
        }

        return overallResult;
    }

    /**
     * Queue-aware scheduled rerun: only executes if items remain in the sender queue table.
     * If the queue is empty, the rerun is skipped and not blindly fired.
     */
    private void scheduleRerun(PipelineConfig pipeline, EtlServerConfig serverConfig, String command,
                               String requestId, String userId, int delayMinutes, int iteration) {
        logger.info("Scheduling queue-aware rerun #{} for pipeline '{}' on server '{}' in {} minute(s)",
                iteration, pipeline.pipelineKey(), serverConfig.getName(), delayMinutes);

        rerunExecutor.schedule(() -> {
            try {
                // Check if there are still queued items in the sender queue table for this pipeline
                int queuedCount = getSenderQueueCount(pipeline);
                if (queuedCount == 0) {
                    logger.info("Sender queue table for pipeline '{}' (site={}) is EMPTY (0 items). Skipping scheduled cronjob rerun.",
                            pipeline.pipelineKey(), pipeline.site());
                    auditService.logEtlTrigger(
                            requestId + "-rerun-" + iteration, userId, pipeline.site(), null,
                            serverConfig.getName(), pipeline.socketPort(), "skipped",
                            "Sender queue is empty (0 items). Skipping scheduled cronjob run.", "system-queue-check"
                    );
                    return;
                }

                logger.info("Sender queue table for pipeline '{}' (site={}) has {} queued item(s). Executing cronjob run (iteration #{})!",
                        pipeline.pipelineKey(), pipeline.site(), queuedCount, iteration);

                executeSshCommand(serverConfig, command);

                auditService.logEtlTrigger(
                        requestId + "-rerun-" + iteration, userId, pipeline.site(), null,
                        serverConfig.getName(), pipeline.socketPort(), "success",
                        "Executed cronjob (" + queuedCount + " items remaining in sender queue): " + command, "system-queue-rerun"
                );

                // If still queued and within max iterations, schedule next check
                if (iteration < etlTriggerProperties.getMaxRerunIterations()) {
                    scheduleRerun(pipeline, serverConfig, command, requestId, userId, delayMinutes, iteration + 1);
                } else {
                    logger.warn("Reached maximum rerun iterations ({}) for pipeline '{}' with {} items still queued",
                            etlTriggerProperties.getMaxRerunIterations(), pipeline.pipelineKey(), queuedCount);
                }

            } catch (Exception ex) {
                logger.error("Failed executing scheduled rerun for pipeline '{}' on server {}: {}",
                        pipeline.pipelineKey(), serverConfig.getName(), ex.getMessage(), ex);
                auditService.logEtlTrigger(
                        requestId + "-rerun-" + iteration, userId, pipeline.site(), null,
                        serverConfig.getName(), pipeline.socketPort(), "failure",
                        "Rerun failed: " + ex.getMessage(), "system-queue-rerun"
                );
            }
        }, delayMinutes, TimeUnit.MINUTES);
    }

    private TriggerResult processEtlServer(EtlServerConfig config, Integer senderPort,
                                           String requestId, String userId, String site,
                                           String location, String remoteIp) {
        try {
            List<CrontabJob> jobs = crontabExtractor.extract(config);
            Integer portForMatch = senderPort != null ? senderPort : config.getSocketPort();
            CrontabJob matchedJob = jobMatcher.match(jobs, portForMatch);
            if (matchedJob == null) {
                logger.warn("No matching crontab job found for port {} on ETL server {}", portForMatch, config.getName());
                return TriggerResult.notConfigured();
            }

            if (etlTriggerProperties.isDryRun()) {
                logger.info("[DRY RUN] Would execute on {}: {}", config.getName(), matchedJob.getCommand());
                return TriggerResult.success();
            }

            executeSshCommand(config, matchedJob.getCommand());
            logger.info("Successfully executed ETL trigger command on server {}: {}", config.getName(), matchedJob.getCommand());
            return TriggerResult.success();

        } catch (Exception e) {
            logger.error("ETL trigger failed on server {}: {}", config.getName(), e.getMessage(), e);
            return TriggerResult.failure("Error: " + e.getMessage());
        }
    }

    private void executeSshCommand(EtlServerConfig config, String command) throws Exception {
        com.jcraft.jsch.JSch jsch = new com.jcraft.jsch.JSch();
        com.jcraft.jsch.Session session = jsch.getSession(config.getUser(), config.getHost(), config.getSshPort());

        if (config.getPassword() != null && !config.getPassword().isEmpty()) {
            session.setPassword(config.getPassword());
        }

        java.util.Properties configProperties = new java.util.Properties();
        configProperties.put("StrictHostKeyChecking", "no");
        session.setConfig(configProperties);
        session.setTimeout(config.getTimeoutMs());
        session.connect();

        try {
            com.jcraft.jsch.Channel channel = session.openChannel("exec");
            ((com.jcraft.jsch.ChannelExec) channel).setCommand(command);

            try (java.io.InputStream inputStream = channel.getInputStream();
                 java.io.BufferedReader reader = new java.io.BufferedReader(
                         new java.io.InputStreamReader(inputStream, java.nio.charset.StandardCharsets.UTF_8))) {

                channel.connect();

                String line;
                StringBuilder output = new StringBuilder();
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }

                channel.disconnect();
                logger.debug("SSH command executed on {}:{}. Output: {}",
                        config.getHost(), config.getSshPort(), output.toString().trim());
            }
        } finally {
            session.disconnect();
        }
    }

    private EtlServerConfig resolveServerConfig(String serverKey, String site) {
        if (serverKey != null && !serverKey.isBlank()) {
            EtlServerConfig cfg = configLoader.getConfigByName(serverKey);
            if (cfg != null) {
                return cfg;
            }
        }
        List<EtlServerConfig> matches = configLoader.getConfigsForSite(site);
        return matches.isEmpty() ? null : matches.get(0);
    }

    private TriggerResult determineOverallStatus(List<TriggerResult> results) {
        if (results == null || results.isEmpty()) {
            return TriggerResult.notConfigured();
        }

        boolean hasSuccess = false;
        boolean hasFailure = false;
        boolean hasNotConfigured = false;

        for (TriggerResult result : results) {
            if ("success".equals(result.getStatus())) {
                hasSuccess = true;
            } else if ("failure".equals(result.getStatus())) {
                hasFailure = true;
            } else if ("not_configured".equals(result.getStatus())) {
                hasNotConfigured = true;
            }
        }

        if (hasFailure) {
            return TriggerResult.failure("ETL trigger failed on one or more servers");
        }
        if (!hasSuccess && hasNotConfigured) {
            return TriggerResult.notConfigured();
        }
        return TriggerResult.success();
    }

    private void storeIdempotency(String requestId, TriggerResult result) {
        try {
            IdempotencyRecord record = new IdempotencyRecord();
            record.setRequestId(requestId);
            record.setStatus(result.getStatus());
            record.setMessage(result.getMessage());
            record.setCreatedAt(Instant.now());
            idempotencyRepository.save(record);
            logger.info("Stored idempotency record for requestId: {}", requestId);
        } catch (Exception e) {
            logger.error("Failed to store idempotency record for requestId {}: {}", requestId, e.getMessage(), e);
        }
    }

    private String getRemoteIp() {
        try {
            ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attributes != null) {
                HttpServletRequest request = attributes.getRequest();
                String xForwardedFor = request.getHeader("X-Forwarded-For");
                if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
                    return xForwardedFor.split(",")[0].trim();
                }
                String xRealIp = request.getHeader("X-Real-IP");
                if (xRealIp != null && !xRealIp.isEmpty()) {
                    return xRealIp;
                }
                return request.getRemoteAddr();
            }
        } catch (Exception e) {
            logger.debug("Could not determine IP address: {}", e.getMessage());
        }
        return "unknown";
    }
}
