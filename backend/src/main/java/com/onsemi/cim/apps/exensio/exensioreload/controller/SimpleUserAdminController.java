package com.onsemi.cim.apps.exensio.exensioreload.controller;

import com.onsemi.cim.apps.exensio.exensioreload.entity.AppUser;
import com.onsemi.cim.apps.exensio.exensioreload.repository.AppUserRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/admin/simple-users")
public class SimpleUserAdminController {
    
    private final AppUserRepository appUserRepository;

    public SimpleUserAdminController(AppUserRepository appUserRepository) {
        this.appUserRepository = appUserRepository;
    }

    /**
     * Simple test endpoint to verify controller is working
     */
    @GetMapping("/test")
    public ResponseEntity<Map<String, Object>> test() {
        Map<String, Object> response = Map.of(
            "status", "OK",
            "message", "SimpleUserAdminController is working",
            "timestamp", Instant.now(),
            "userCount", appUserRepository.count()
        );
        return ResponseEntity.ok(response);
    }

    /**
     * Get all users - simple implementation
     */
    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> getUsers() {
        try {
            List<AppUser> users = appUserRepository.findAll();
            List<Map<String, Object>> userList = users.stream()
                .map(user -> Map.of(
                    "id", user.getId(),
                    "username", user.getUsername(),
                    "email", user.getEmail() != null ? user.getEmail() : "",
                    "enabled", user.isEnabled(),
                    "status", user.getStatus().toString(),
                    "roles", user.getRoles(),
                    "createdAt", user.getCreatedAt() != null ? user.getCreatedAt().toString() : ""
                ))
                .collect(Collectors.toList());
            
            return ResponseEntity.ok(userList);
        } catch (Exception e) {
            return ResponseEntity.ok(List.of(Map.of("error", e.getMessage())));
        }
    }

    /**
     * Get available roles
     */
    @GetMapping("/roles")
    public ResponseEntity<List<String>> getAvailableRoles() {
        List<String> roles = List.of("ROLE_USER", "ROLE_ADMIN", "ROLE_SUPER_ADMIN");
        return ResponseEntity.ok(roles);
    }

    /**
     * Health check endpoint
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> healthCheck() {
        Map<String, Object> health = Map.of(
            "status", "OK",
            "timestamp", Instant.now(),
            "userCount", appUserRepository.count(),
            "availableRoles", List.of("ROLE_USER", "ROLE_ADMIN", "ROLE_SUPER_ADMIN")
        );
        return ResponseEntity.ok(health);
    }
}
