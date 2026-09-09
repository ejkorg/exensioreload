package com.onsemi.cim.apps.exensio.exensioreload.controller;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.onsemi.cim.apps.exensio.exensioreload.repository.LoadSessionPayloadRepository;

/**
 * REST API endpoints for session-related queries.
 * Provides device filtering support for the dashboard.
 */
@RestController
@RequestMapping("/api/sessions")
public class SessionsApiController {

    private static final Logger log = LoggerFactory.getLogger(SessionsApiController.class);

    private final LoadSessionPayloadRepository payloadRepository;

    public SessionsApiController(LoadSessionPayloadRepository payloadRepository) {
        this.payloadRepository = payloadRepository;
    }

    /**
     * Get distinct device identifiers across all session payloads.
     * Used by the dashboard for device filtering.
     *
     * @param sessionId optional session ID to filter devices by session
     * @return list of unique device identifiers
     */
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/devices")
    public ResponseEntity<List<String>> getDistinctDevices(@RequestParam(required = false) Long sessionId) {
        log.debug("Fetching distinct devices, sessionId: {}", sessionId);

        List<String> devices;
        if (sessionId != null) {
            devices = payloadRepository.findDistinctDevicesBySessionId(sessionId);
        } else {
            devices = payloadRepository.findDistinctDevices();
        }

        return ResponseEntity.ok(devices);
    }
}
