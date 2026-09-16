package com.onsemi.cim.apps.exensio.exensioreload.service;

import com.onsemi.cim.apps.exensio.exensioreload.config.CrontabJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Matches a crontab job based on socket_port and/or config_name.
 */
@Service
public class CrontabJobMatcher {
    private static final Logger logger = LoggerFactory.getLogger(CrontabJobMatcher.class);

    /**
     * Backward-compatible matching for single port.
     */
    public CrontabJob match(List<CrontabJob> jobs, Integer senderPort) {
        return match(jobs, senderPort, null);
    }

    /**
     * Matches a crontab job matching socketPort and/or configName.
     *
     * @param jobs List of crontab jobs extracted from server
     * @param socketPort The DataPort CP socket_port (e.g. 60170)
     * @param configName The DataPort CP config_name (e.g. "CPYQSP")
     * @return The best matching CrontabJob, or null if none match
     */
    public CrontabJob match(List<CrontabJob> jobs, Integer socketPort, String configName) {
        if (jobs == null || jobs.isEmpty()) {
            logger.debug("No crontab jobs to match");
            return null;
        }

        String normalizedConfig = (configName != null && !configName.isBlank()) ? configName.trim() : null;

        // 1. Try exact match on BOTH port and configName if both provided
        if (socketPort != null && normalizedConfig != null) {
            for (CrontabJob job : jobs) {
                if (job != null && job.getCommand() != null) {
                    if (matchesPort(job.getCommand(), socketPort) && matchesConfig(job.getCommand(), normalizedConfig)) {
                        logger.info("Found crontab job matching BOTH port {} and config '{}': {}",
                                socketPort, normalizedConfig, job.getCommand());
                        return job;
                    }
                }
            }
        }

        // 2. If configName provided, try matching configName
        if (normalizedConfig != null) {
            for (CrontabJob job : jobs) {
                if (job != null && job.getCommand() != null && matchesConfig(job.getCommand(), normalizedConfig)) {
                    logger.info("Found crontab job matching config '{}': {}", normalizedConfig, job.getCommand());
                    return job;
                }
            }
        }

        // 3. If port provided, try matching port with word boundaries
        if (socketPort != null) {
            for (CrontabJob job : jobs) {
                if (job != null && job.getCommand() != null && matchesPort(job.getCommand(), socketPort)) {
                    logger.info("Found crontab job matching port {}: {}", socketPort, job.getCommand());
                    return job;
                }
            }
        }

        logger.debug("No matching crontab job found for port={} and configName='{}'", socketPort, normalizedConfig);
        return null;
    }

    /**
     * Checks whether command contains the port as an isolated numeric token.
     * Prevents 601 matching 60170.
     */
    public boolean matchesPort(String command, int port) {
        if (command == null || command.isBlank()) {
            return false;
        }
        String portStr = String.valueOf(port);
        Pattern pattern = Pattern.compile("(?<![0-9])" + Pattern.quote(portStr) + "(?![0-9])");
        return pattern.matcher(command).find();
    }

    /**
     * Checks whether command contains the config name (case-insensitive).
     */
    public boolean matchesConfig(String command, String configName) {
        if (command == null || configName == null || configName.isBlank()) {
            return false;
        }
        return command.toLowerCase(Locale.ROOT).contains(configName.toLowerCase(Locale.ROOT));
    }
}
