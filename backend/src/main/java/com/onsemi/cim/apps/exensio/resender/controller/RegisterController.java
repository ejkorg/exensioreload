package com.onsemi.cim.apps.exensio.resender.controller;

import com.onsemi.cim.apps.exensio.resender.entity.AppUser;
import com.onsemi.cim.apps.exensio.resender.repository.AppUserRepository;
import com.onsemi.cim.apps.exensio.resender.dto.RegisterRequest;
import com.onsemi.cim.apps.exensio.resender.service.AuthTokenService;
import com.onsemi.cim.apps.exensio.resender.service.RefDbService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class RegisterController {
    private static final Logger logger = LoggerFactory.getLogger(RegisterController.class);

    private final AppUserRepository repo;
    private final PasswordEncoder encoder;
    private final AuthTokenService tokenService;
    private final RefDbService refDbService;

    public RegisterController(AppUserRepository repo, PasswordEncoder encoder, AuthTokenService tokenService, RefDbService refDbService) {
        this.repo = repo;
        this.encoder = encoder;
        this.tokenService = tokenService;
        this.refDbService = refDbService;
    }

    @PostMapping("/register")
    public ResponseEntity<Map<String, String>> register(@RequestBody RegisterRequest req) {
        logger.info("[RegisterController.register] registration attempt username={}", req.getUsername());
        if (req.getUsername() == null || req.getUsername().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "username required"));
        }
        if (req.getPassword() == null || req.getPassword().length() < 8) {
            return ResponseEntity.badRequest().body(Map.of("error", "password must be at least 8 characters"));
        }
        if (repo.findByUsername(req.getUsername()).isPresent()) {
            return ResponseEntity.status(409).body(Map.of("error", "username already exists"));
        }
        String email = req.getEmail() == null ? null : req.getEmail().trim();
        if (email != null && email.isBlank()) email = null;
        if (email != null && repo.findByEmail(email).isPresent()) {
            return ResponseEntity.status(409).body(Map.of("error", "email already exists"));
        }

        AppUser u = new AppUser();
        u.setUsername(req.getUsername());
        u.setEmail(email);
        u.setPasswordHash(encoder.encode(req.getPassword()));
        u.getRoles().add("ROLE_USER");
        // require verification before login in production; enabled via /api/auth/verify
        u.setEnabled(false);

        try {
            // force immediate persist and flush so failures surface in logs and the row is visible to other connections
            AppUser saved = repo.saveAndFlush(u);
            if (saved.getId() != null) {
                logger.info("User saved to JPA users table id={} username={}", saved.getId(), saved.getUsername());
            } else {
                logger.warn("User saved but id is null (unexpected) username={}", saved.getUsername());
            }
        } catch (DataAccessException dae) {
            logger.error("Failed to save user to JPA users table: {}", dae.getMessage(), dae);
            return ResponseEntity.status(500).body(Map.of("error", "internal error saving user"));
        } catch (Exception ex) {
            logger.error("Unexpected error saving user: {}", ex.getMessage(), ex);
            return ResponseEntity.status(500).body(Map.of("error", "internal error"));
        }

        // User is now managed by modern JPA system - no need for RefDB provisioning
        logger.info("User '{}' registered successfully in modern authentication system", u.getUsername());

        // create verification token - in production we'd send this via email.
        var vt = tokenService.createVerificationToken(u.getUsername());
        Map<String, String> body = new HashMap<>();
        body.put("message", "registered");
        body.put("verificationToken", vt.getToken()); // dev helper
        return ResponseEntity.ok(body);
    }
}
