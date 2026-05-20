package com.onsemi.cim.apps.exensio.exensioreload.controller;

import com.onsemi.cim.apps.exensio.exensioreload.entity.AppUser;
import com.onsemi.cim.apps.exensio.exensioreload.repository.AppUserRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/simple/users")
@PreAuthorize("hasRole('ROLE_SUPER_ADMIN') or hasRole('SUPER_ADMIN')")
public class SimpleUserController {
    
    private final AppUserRepository appUserRepository;

    public SimpleUserController(AppUserRepository appUserRepository) {
        this.appUserRepository = appUserRepository;
    }

    @GetMapping("/test")
    public ResponseEntity<Map<String, Object>> test() {
        Map<String, Object> response = Map.of(
            "status", "OK",
            "message", "SimpleUserController is working",
            "timestamp", Instant.now(),
            "userCount", appUserRepository.count()
        );
        return ResponseEntity.ok(response);
    }

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> getUsers() {
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
    }

    @GetMapping("/roles")
    public ResponseEntity<List<String>> getRoles() {
        List<String> roles = List.of("ROLE_USER", "ROLE_ADMIN", "ROLE_SUPER_ADMIN");
        return ResponseEntity.ok(roles);
    }
}
