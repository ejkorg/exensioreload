package com.onsemi.cim.apps.exensio.exensioreload.service;

/**
 * Result of an ETL trigger attempt.
 */
public class TriggerResult {
    private final String status;  // "success", "failure", "not_configured"
    private final String message;
    private final String command;
    private final Integer rerunMinutes;

    public TriggerResult(String status, String message) {
        this(status, message, null, null);
    }

    public TriggerResult(String status, String message, String command, Integer rerunMinutes) {
        this.status = status;
        this.message = message;
        this.command = command;
        this.rerunMinutes = rerunMinutes;
    }

    public String getStatus() {
        return status;
    }

    public String getMessage() {
        return message;
    }

    public String getCommand() {
        return command;
    }

    public Integer getRerunMinutes() {
        return rerunMinutes;
    }

    public static TriggerResult success() {
        return new TriggerResult("success", "ETL trigger executed successfully");
    }

    public static TriggerResult success(String message, String command, Integer rerunMinutes) {
        return new TriggerResult("success", message, command, rerunMinutes);
    }

    public static TriggerResult failure(String message) {
        return new TriggerResult("failure", message);
    }

    public static TriggerResult notConfigured() {
        return new TriggerResult("not_configured", "ETL servers not configured");
    }
}
