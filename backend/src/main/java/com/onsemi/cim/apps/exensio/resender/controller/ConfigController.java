package com.onsemi.cim.apps.exensio.resender.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/config")
public class ConfigController {
    @Value("${app.preview.max-rows-cap:20000}")
    private int previewMaxRowsCap;

    @Value("${app.preview.fetch-cap:20000}")
    private int previewFetchCap;

    @Value("${app.stage.page-size-cap:20000}")
    private int stagePageSizeCap;

    @Value("${app.stage.max-rows-cap:100000}")
    private int stageMaxRowsCap;

    @Value("${app.stage.default-max-rows:20000}")
    private int stageDefaultMaxRows;

    @GetMapping("/limits")
    public ResponseEntity<Map<String, Object>> getLimits() {
        return ResponseEntity.ok(Map.of(
                "previewMaxRowsCap", previewMaxRowsCap,
                "previewFetchCap", previewFetchCap,
                "stagePageSizeCap", stagePageSizeCap,
                "stageMaxRowsCap", stageMaxRowsCap,
                "stageDefaultMaxRows", stageDefaultMaxRows
        ));
    }
}
