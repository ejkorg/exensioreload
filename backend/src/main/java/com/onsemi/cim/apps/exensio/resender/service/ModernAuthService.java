package com.onsemi.cim.apps.exensio.resender.service;

import com.onsemi.cim.apps.exensio.resender.entity.AppUser;
import com.onsemi.cim.apps.exensio.resender.repository.AppUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.Set;

/**
 * Modern authentication service that replaces legacy RefDbService authentication methods.
 * Uses JPA entities and repositories for user management.
 */
@Service
public class ModernAuthService {
    
    private static final Logger log = LoggerFactory.getLogger(ModernAuthService.class);
    
    private final AppUserRepository userRepository;
    
    public ModernAuthService(AppUserRepository userRepository) {
        this.userRepository = userRepository;
    }
    
    /**
     * Get user authorities using modern JPA-based approach.
     * Replaces RefDbService.getUserAuthorities()
     */
    public Set<String> getUserAuthorities(String username) {
        Set<String> roles = new HashSet<>();
        if (username == null || username.isBlank()) {
            return roles;
        }
        
        try {
            AppUser user = userRepository.findByUsername(username).orElse(null);
            if (user != null) {
                roles.addAll(user.getRoles());
            } else {
                log.debug("User '{}' not found in modern authentication system", username);
                // Default role for unknown users
                roles.add("ROLE_USER");
            }
        } catch (Exception ex) {
            log.warn("Failed to get user authorities for '{}': {}", username, ex.getMessage());
            // Default role on error
            roles.add("ROLE_USER");
        }
        
        return roles;
    }
    
    /**
     * Check if user exists in the modern system
     */
    public boolean userExists(String username) {
        if (username == null || username.isBlank()) {
            return false;
        }
        return userRepository.findByUsername(username).isPresent();
    }
    
    /**
     * Get user by username
     */
    public AppUser getUser(String username) {
        if (username == null || username.isBlank()) {
            return null;
        }
        return userRepository.findByUsername(username).orElse(null);
    }
}
