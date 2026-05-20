package com.onsemi.cim.apps.exensio.resender.service;

import com.onsemi.cim.apps.exensio.resender.config.CrontabJob;
import com.onsemi.cim.apps.exensio.resender.config.EtlServerConfig;
import com.onsemi.cim.apps.exensio.resender.config.EtlServerConfigLoader;
import com.onsemi.cim.apps.exensio.resender.entity.IdempotencyRecord;
import com.onsemi.cim.apps.exensio.resender.repository.IdempotencyRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Main service that orchestrates the ETL SSH trigger process.
 * <p>
 * Requirements: 1.1, 1.2, 1.3, 1.4, 1.5, 1.6, 1.7, 1.8, 1.9, 2.4, 3.1, 3.2, 3.3, 3.4, 4.1, 4.2, 4.3, 4.4, 5.1, 5.2, 5.3, 6.1, 6.2
 */
@Service
public class EtlSshTriggerService {

    private static final Logger logger = LoggerFactory.getLogger(EtlSshTriggerService.class);

    private final EtlServerConfigLoader configLoader;
    private final CrontabExtractor crontabExtractor;
    private final SenderPortExtractor senderPortExtractor;
    private final CrontabJobMatcher jobMatcher;
    private final AuditService auditService;
    private final IdempotencyRepository idempotencyRepository;

    public EtlSshTriggerService(
            EtlServerConfigLoader configLoader,
            CrontabExtractor crontabExtractor,
            SenderPortExtractor senderPortExtractor,
            CrontabJobMatcher jobMatcher,
            AuditService auditService,
            IdempotencyRepository idempotencyRepository) {
        this.configLoader = configLoader;
        this.crontabExtractor = crontabExtractor;
        this.senderPortExtractor = senderPortExtractor;
        this.jobMatcher = jobMatcher;
        this.auditService = auditService;
        this.idempotencyRepository = idempotencyRepository;
    }

    /**
     * Executes the ETL SSH trigger for a staging request.
     * <p>
     * This method:
     * 1. Checks if ETL servers are configured (kill switch)
     * 2. Checks idempotency by requestId
     * 3. Iterates through all configured ETL servers
     * 4. Extracts crontab jobs from each server
     * 5. Extracts sender port from Elasticsearch
     * 6. Matches sender port to crontab job
     * 7. Executes the matched command via SSH (single attempt, no retries)
     * 8. Determines overall status
     * 9. Logs audit entries
     * 10. Stores idempotency record
     *
     * @param requestId     Unique request identifier
     * @param userId        User who triggered the action
     * @param site          Site name
     * @param location      Location name (optional)
     * @param senderConfigName Elasticsearch sender configuration name
     * @return TriggerResult with status and message
     */
    public TriggerResult execute(String requestId, String userId, String site,
                                 String location, String senderConfigName) {
        // Check if ETL servers are configured (kill switch check)
        if (!configLoader.hasConfigs()) {
            logger.info("ETL trigger disabled - no ETL servers configured for requestId: {}", requestId);
            return TriggerResult.notConfigured();
        }

        // Check idempotency
        Optional<IdempotencyRecord> cached = idempotencyRepository.findById(requestId);
        if (cached.isPresent()) {
            IdempotencyRecord record = cached.get();
            logger.info("Returning cached result for requestId: {} (status: {})", requestId, record.getStatus());
            return new TriggerResult(record.getStatus(), record.getMessage());
        }

        // Extract sender port from Elasticsearch
        Optional<Integer> senderPortOpt = senderPortExtractor.extractPort(senderConfigName);
        if (!senderPortOpt.isPresent()) {
            logger.warn("Could not extract sender port from config name: {}", senderConfigName);
        }
        Integer senderPort = senderPortOpt.orElse(null);

        // Get remote IP for audit logging
        String remoteIp = getRemoteIp();

        // Process each ETL server
        List<TriggerResult> results = new ArrayList<>();
        for (EtlServerConfig config : configLoader.getConfigs()) {
            TriggerResult result = processEtlServer(config, senderPort, requestId, userId, site, location, remoteIp);
            results.add(result);
        }

        // Determine overall status
        TriggerResult overallResult = determineOverallStatus(results);

        // Log audit for each server result
        for (int i = 0; i < configLoader.getConfigs().size(); i++) {
            EtlServerConfig config = configLoader.getConfigs().get(i);
            TriggerResult result = results.get(i);

            auditService.logEtlTrigger(
                    requestId, userId, site, location,
                    config.getName(), senderPort,
                    result.getStatus(), result.getMessage(), remoteIp
            );
        }

        // Store idempotency record
        storeIdempotency(requestId, overallResult);

        return overallResult;
    }

    /**
     * Processes a single ETL server.
     */
    private TriggerResult processEtlServer(EtlServerConfig config, Integer senderPort,
                                           String requestId, String userId, String site,
                                           String location, String remoteIp) {
        try {
            // Extract crontab jobs from the ETL server
            List<CrontabJob> jobs = crontabExtractor.extract(config);

            // Match sender port to crontab job
            CrontabJob matchedJob = jobMatcher.match(jobs, senderPort);
            if (matchedJob == null) {
                logger.warn("No matching crontab job found for sender port {} on ETL server {}",
                        senderPort, config.getName());
                return TriggerResult.notConfigured();
            }

            // Execute the command via SSH (single attempt, no retries)
            executeSshCommand(config, matchedJob.getCommand());

            logger.info("Successfully executed ETL trigger command on server {}: {}",
                    config.getName(), matchedJob.getCommand());
            return TriggerResult.success();

        } catch (Exception e) {
            logger.error("ETL trigger failed on server {}: {}", config.getName(), e.getMessage(), e);
            return TriggerResult.failure("Error: " + e.getMessage());
        }
    }

    /**
     * Executes a command via SSH on the ETL server.
     * Single attempt only - no retries.
     */
    private void executeSshCommand(EtlServerConfig config, String command) throws Exception {
        // SSH connection setup
        com.jcraft.jsch.JSch jsch = new com.jcraft.jsch.JSch();
        com.jcraft.jsch.Session session = jsch.getSession(config.getUser(), config.getHost(), config.getPort());

        // Set password if provided
        if (config.getPassword() != null && !config.getPassword().isEmpty()) {
            session.setPassword(config.getPassword());
        }

        // Disable strict host key checking (for development/automation)
        java.util.Properties configProperties = new java.util.Properties();
        configProperties.put("StrictHostKeyChecking", "no");
        session.setConfig(configProperties);

        // Set timeout
        session.setTimeout(config.getTimeoutMs());

        // Connect to server
        session.connect();

        try {
            // Execute the command
            com.jcraft.jsch.Channel channel = session.openChannel("exec");
            ((com.jcraft.jsch.ChannelExec) channel).setCommand(command);

            // Get output stream
            try (java.io.InputStream inputStream = channel.getInputStream();
                 java.io.BufferedReader reader = new java.io.BufferedReader(
                         new java.io.InputStreamReader(inputStream, java.nio.charset.StandardCharsets.UTF_8))) {

                channel.connect();

                // Read output (for logging/debugging)
                String line;
                StringBuilder output = new StringBuilder();
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }

                channel.disconnect();

                logger.debug("SSH command executed on {}:{}. Output: {}",
                        config.getHost(), config.getPort(), output.toString().trim());

            }
        } finally {
            session.disconnect();
        }
    }

    /**
     * Determines the overall status based on all server results.
     */
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

        // If any failed, return failure
        if (hasFailure) {
            return TriggerResult.failure("ETL trigger failed on one or more servers");
        }

        // If all not configured, return not configured
        if (!hasSuccess && hasNotConfigured) {
            return TriggerResult.notConfigured();
        }

        // Otherwise return success
        return TriggerResult.success();
    }

    /**
     * Stores the idempotency record.
     */
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
            logger.error("Failed to store idempotency record for requestId {}: {}",
                    requestId, e.getMessage(), e);
            // Don't fail the request if idempotency storage fails
        }
    }

    /**
     * Gets the remote IP address from the current request.
     */
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
