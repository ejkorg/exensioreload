package com.onsemi.cim.apps.exensio.resender.service;

/**
 * Result of an ETL trigger attempt.
 */
public class TriggerResult {
    private final String status;  // "success", "failure", "not_configured"
    private final String message;

    public TriggerResult(String status, String message) {
        this.status = status;
        this.message = message;
    }

    public String getStatus() {
        return status;
    }

    public String getMessage() {
        return message;
    }

    public static TriggerResult success() {
        return new TriggerResult("success", "ETL trigger executed successfully");
    }

    public static TriggerResult failure(String message) {
        return new TriggerResult("failure", message);
    }

    public static TriggerResult notConfigured() {
        return new TriggerResult("not_configured", "ETL servers not configured");
    }
}
