package com.onsemi.cim.apps.exensio.exensioreload.dto;

/**
 * Result of inspecting/discovering the crontab configuration on an ETL server
 * for a specific pipeline or site.
 */
public record CrontabDiscoveryResult(
    String pipelineKey,
    String site,
    String serverName,
    String host,
    Integer sshPort,
    Integer socketPort,
    String configName,
    String matchedCommand,
    String schedule,
    String rawLine,
    String status,
    String message,
    int totalCrontabJobsFound
) {
    public static CrontabDiscoveryResult success(
            String pipelineKey, String site, String serverName, String host,
            Integer sshPort, Integer socketPort, String configName,
            String matchedCommand, String schedule, String rawLine, int totalJobs) {
        return new CrontabDiscoveryResult(
            pipelineKey, site, serverName, host, sshPort, socketPort, configName,
            matchedCommand, schedule, rawLine, "SUCCESS", "Crontab matched successfully", totalJobs
        );
    }

    public static CrontabDiscoveryResult notFound(
            String pipelineKey, String site, String serverName, String host,
            Integer sshPort, Integer socketPort, String configName, int totalJobs) {
        return new CrontabDiscoveryResult(
            pipelineKey, site, serverName, host, sshPort, socketPort, configName,
            null, null, null, "NOT_FOUND",
            "No crontab line matched socket_port=" + socketPort + " and configName='" + configName + "'",
            totalJobs
        );
    }

    public static CrontabDiscoveryResult failure(
            String pipelineKey, String site, String serverName, String host,
            String message) {
        return new CrontabDiscoveryResult(
            pipelineKey, site, serverName, host, null, null, null,
            null, null, null, "FAILURE", message, 0
        );
    }
}
