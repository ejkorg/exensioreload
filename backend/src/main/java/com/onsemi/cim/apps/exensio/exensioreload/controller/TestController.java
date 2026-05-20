package com.onsemi.cim.apps.exensio.exensioreload.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

@RestController
@RequestMapping("/api/test")
public class TestController {

    @GetMapping("/hello")
    public ResponseEntity<Map<String, Object>> hello() {
        Map<String, Object> response = Map.of(
            "message", "Hello from TestController",
            "timestamp", Instant.now().toString(),
            "status", "OK"
        );
        return ResponseEntity.ok(response);
    }

    @GetMapping("/admin-test")
    public ResponseEntity<Map<String, Object>> adminTest() {
        Map<String, Object> response = Map.of(
            "message", "Admin test endpoint working",
            "timestamp", Instant.now().toString(),
            "status", "OK"
        );
        return ResponseEntity.ok(response);
    }
}
