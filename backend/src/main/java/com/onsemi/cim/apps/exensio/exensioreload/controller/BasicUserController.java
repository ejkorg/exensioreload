package com.onsemi.cim.apps.exensio.exensioreload.controller;

import com.onsemi.cim.apps.exensio.exensioreload.entity.AppUser;
import com.onsemi.cim.apps.exensio.exensioreload.repository.AppUserRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Ultra-simple user controller with minimal dependencies
 * to isolate 404 issues
 */
@RestController
@RequestMapping("/api/basic/users")
public class BasicUserController {
    
    private final AppUserRepository userRepository;

    public BasicUserController(AppUserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @GetMapping("/ping")
    public ResponseEntity<String> ping() {
        return ResponseEntity.ok("BasicUserController is working at " + Instant.now());
    }

    @GetMapping("/count")
    public ResponseEntity<Map<String, Object>> getUserCount() {
        long count = userRepository.count();
        return ResponseEntity.ok(Map.of(
            "userCount", count,
            "timestamp", Instant.now(),
            "status", "OK"
        ));
    }

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> getAllUsers() {
        try {
            List<AppUser> users = userRepository.findAll();
            List<Map<String, Object>> result = users.stream()
                .map(user -> Map.of(
                    "id", user.getId(),
                    "username", user.getUsername(),
                    "enabled", user.isEnabled(),
                    "roles", user.getRoles()
                ))
                .collect(Collectors.toList());
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.ok(List.of(Map.of("error", e.getMessage())));
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getUserById(@PathVariable Long id) {
        try {
            Optional<AppUser> userOpt = userRepository.findById(id);
            if (userOpt.isPresent()) {
                AppUser user = userOpt.get();
                Map<String, Object> result = Map.of(
                    "id", user.getId(),
                    "username", user.getUsername(),
                    "email", user.getEmail() != null ? user.getEmail() : "",
                    "enabled", user.isEnabled(),
                    "status", user.getStatus().toString(),
                    "roles", user.getRoles(),
                    "createdAt", user.getCreatedAt() != null ? user.getCreatedAt().toString() : ""
                );
                return ResponseEntity.ok(result);
            } else {
                return ResponseEntity.notFound().build();
            }
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("error", e.getMessage()));
        }
    }
}
