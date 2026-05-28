package com.onsemi.cim.apps.exensio.exensioreload.service;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class IntegrationStatusService {

    public record IntegrationStatus(String status, String message, Instant at) {}

    private final ConcurrentHashMap<String, IntegrationStatus> esStatusByRequest = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, IntegrationStatus> exensioStatusByRequest = new ConcurrentHashMap<>();

    public void updateElasticsearch(String requestId, String status, String message) {
        if (requestId == null || requestId.isBlank()) {
            return;
        }
        esStatusByRequest.put(requestId, new IntegrationStatus(status, message, Instant.now()));
    }

    public void updateExensio(String requestId, String status, String message) {
        if (requestId == null || requestId.isBlank()) {
            return;
        }
        exensioStatusByRequest.put(requestId, new IntegrationStatus(status, message, Instant.now()));
    }

    public Map<String, Object> snapshot(String requestId, boolean esConfigured, boolean exensioConfigured) {
        Map<String, Object> result = new HashMap<>();
        result.put("elasticsearch", toMap(esStatusByRequest.get(requestId), esConfigured));
        result.put("exensio", toMap(exensioStatusByRequest.get(requestId), exensioConfigured));
        return result;
    }

    private Map<String, Object> toMap(IntegrationStatus status, boolean configured) {
        Map<String, Object> out = new HashMap<>();
        out.put("configured", configured);

        if (!configured) {
            out.put("status", "not_configured");
            out.put("message", "Not configured");
            out.put("lastAt", null);
            return out;
        }

        if (status == null) {
            out.put("status", "pending");
            out.put("message", "Waiting for first check");
            out.put("lastAt", null);
            return out;
        }

        out.put("status", status.status());
        out.put("message", status.message());
        out.put("lastAt", status.at() != null ? status.at().toString() : null);
        return out;
    }
}