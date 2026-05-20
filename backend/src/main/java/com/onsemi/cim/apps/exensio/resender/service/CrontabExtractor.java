package com.onsemi.cim.apps.exensio.resender.service;

import com.onsemi.cim.apps.exensio.resender.config.CrontabJob;
import com.onsemi.cim.apps.exensio.resender.config.EtlServerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Extracts crontab jobs from ETL servers via SSH.
 */
@Service
public class CrontabExtractor {
    private static final Logger logger = LoggerFactory.getLogger(CrontabExtractor.class);

    /**
     * Extracts all uncommented crontab jobs from an ETL server.
     *
     * @param config ETL server configuration
     * @return List of CrontabJob objects
     * @throws Exception if SSH connection or command execution fails
     */
    public List<CrontabJob> extract(EtlServerConfig config) throws Exception {
        List<CrontabJob> jobs = new ArrayList<>();

        try {
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
                // Execute 'crontab -l' command
                com.jcraft.jsch.Channel channel = session.openChannel("exec");
                ((com.jcraft.jsch.ChannelExec) channel).setCommand("crontab -l");

                // Get command output stream
                try (InputStream inputStream = channel.getInputStream();
                     BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {

                    channel.connect();

                    String line;
                    while ((line = reader.readLine()) != null) {
                        // Filter out commented lines and parse uncommented ones
                        CrontabJob job = parseCrontabLine(line);
                        if (job != null) {
                            jobs.add(job);
                        }
                    }

                    channel.disconnect();
                }
            } finally {
                session.disconnect();
            }

        } catch (com.jcraft.jsch.JSchException e) {
            logger.error("SSH connection failed for ETL server {}:{}", config.getHost(), config.getPort(), e);
            throw new Exception("SSH connection failed: " + e.getMessage(), e);
        } catch (IOException e) {
            logger.error("Error reading crontab output for ETL server {}:{}", config.getHost(), config.getPort(), e);
            throw new Exception("Error reading crontab: " + e.getMessage(), e);
        }

        return jobs;
    }

    /**
     * Parses a single crontab line and returns a CrontabJob if it's not a comment.
     *
     * @param line A single line from crontab output
     * @return CrontabJob if line is uncommented, null if it's a comment or empty
     */
    private CrontabJob parseCrontabLine(String line) {
        if (line == null || line.trim().isEmpty()) {
            return null;
        }

        String trimmed = line.trim();

        // Filter out commented lines (lines starting with #)
        if (trimmed.startsWith("#")) {
            return null;
        }

        // Parse schedule and command from uncommented line
        CrontabJob job = new CrontabJob();

        // Crontab format: minute hour day month weekday command
        // Split by whitespace, first 5 fields are schedule, rest is command
        String[] parts = trimmed.split("\\s+", 6);

        if (parts.length >= 6) {
            // First 5 parts are schedule
            job.setSchedule(parts[0] + " " + parts[1] + " " + parts[2] + " " + parts[3] + " " + parts[4]);
            // Remaining part is the command
            job.setCommand(parts[5]);
        } else if (parts.length >= 5) {
            // Minimal valid crontab: 5 schedule fields + minimal command
            job.setSchedule(parts[0] + " " + parts[1] + " " + parts[2] + " " + parts[3] + " " + parts[4]);
            job.setCommand("");
        }

        return job;
    }
}
