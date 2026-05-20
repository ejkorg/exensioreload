package com.onsemi.cim.apps.exensio.resender.service;

import com.onsemi.cim.apps.exensio.resender.config.CrontabJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Matches a sender port to a crontab job.
 * <p>
 * Requirements: 1.4, 4.3, 4.4
 */
@Service
public class CrontabJobMatcher {
    private static final Logger logger = LoggerFactory.getLogger(CrontabJobMatcher.class);

    /**
     * Matches a sender port to a crontab job by looking for the port number in the command.
     * <p>
     * Examples:
     * <ul>
     *   <li>Sender port 8080 matches command "java -jar cp.jar --port 8080"</li>
     *   <li>Sender port 9090 matches command "/opt/cp/run.sh 9090"</li>
     * </ul>
     *
     * @param jobs List of crontab jobs to search
     * @param senderPort The sender port number to match
     * @return The matching CrontabJob if found, null otherwise
     */
    public CrontabJob match(List<CrontabJob> jobs, Integer senderPort) {
        if (jobs == null || jobs.isEmpty()) {
            logger.debug("No crontab jobs to match");
            return null;
        }

        if (senderPort == null) {
            logger.debug("Sender port is null, cannot match");
            return null;
        }

        String portPattern = String.valueOf(senderPort);

        for (CrontabJob job : jobs) {
            if (job == null || job.getCommand() == null) {
                continue;
            }

            // Check if the command contains the sender port
            if (job.getCommand().contains(portPattern)) {
                logger.debug("Found matching crontab job for port {}: {}", senderPort, job.getCommand());
                return job;
            }
        }

        logger.debug("No matching crontab job found for port {}", senderPort);
        return null;
    }
}
