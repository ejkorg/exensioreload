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
     * When socketPort is provided, matches ONLY by the unique port across the server's crontab.
     *
     * @param jobs List of crontab jobs extracted from server
     * @param socketPort The DataPort CP port (e.g. 64566)
     * @param configName Fallback config name (used only if socketPort is null)
     * @return The matching CrontabJob, or null if none match
     */
    public CrontabJob match(List<CrontabJob> jobs, Integer socketPort, String configName) {
        if (jobs == null || jobs.isEmpty()) {
            logger.debug("No crontab jobs to match");
            return null;
        }

        // Port is unique! If socketPort is provided, grep/match crontab by port ONLY.
        if (socketPort != null) {
            for (CrontabJob job : jobs) {
                if (job != null) {
                    if (job.getCommand() != null && matchesPort(job.getCommand(), socketPort)) {
                        logger.info("Found crontab job uniquely matching port {}: {}", socketPort, job.getCommand());
                        return job;
                    }
                    if (job.getRawLine() != null && matchesPort(job.getRawLine(), socketPort)) {
                        logger.info("Found crontab job uniquely matching port {} in raw line: {}", socketPort, job.getRawLine());
                        return job;
                    }
                }
            }
            logger.warn("No crontab job found matching port {}", socketPort);
            return null;
        }

        // Fallback: if no port was provided, match by configName
        if (configName != null && !configName.isBlank()) {
            String normalizedConfig = configName.trim();
            for (CrontabJob job : jobs) {
                if (job != null && job.getCommand() != null && matchesConfig(job.getCommand(), normalizedConfig)) {
                    logger.info("Found crontab job matching config '{}': {}", normalizedConfig, job.getCommand());
                    return job;
                }
            }
        }

        logger.debug("No matching crontab job found for port={} and configName='{}'", socketPort, configName);
        return null;
    }

    /**
     * Checks whether command or line contains the port as an isolated numeric token or filename prefix.
     * Prevents 601 matching 60170, while properly matching:
     * - /path/to/64566_CZ2_Defect_Klarf18_Si_Sender.xml
     * - port 64566
     * - 64566.xml
     */
    public boolean matchesPort(String text, int port) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String portStr = String.valueOf(port);
        Pattern pattern = Pattern.compile("(?<![0-9])" + Pattern.quote(portStr) + "(?![0-9])");
        return pattern.matcher(text).find();
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
