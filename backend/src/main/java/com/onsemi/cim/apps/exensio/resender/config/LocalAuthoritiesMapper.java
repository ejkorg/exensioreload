package com.onsemi.cim.apps.exensio.resender.config;

import com.onsemi.cim.apps.exensio.resender.service.ModernAuthService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * Maps an authenticated username to local application authorities using modern JPA-based authentication.
 * This is used after SSO/OIDC authentication to enforce app-specific roles.
 */
public class LocalAuthoritiesMapper {
    private static final Logger log = LoggerFactory.getLogger(LocalAuthoritiesMapper.class);
    private final ModernAuthService modernAuthService;

    public LocalAuthoritiesMapper(ModernAuthService modernAuthService) {
        this.modernAuthService = modernAuthService;
    }

    public Collection<? extends GrantedAuthority> loadAuthorities(String username) {
        try {
            Set<GrantedAuthority> roles = new HashSet<>();
            for (String role : modernAuthService.getUserAuthorities(username)) {
                roles.add(new SimpleGrantedAuthority(role));
            }
            if (roles.isEmpty()) {
                // Always grant basic user if none found
                roles.add(new SimpleGrantedAuthority("ROLE_USER"));
            }
            return roles;
        } catch (Exception e) {
            log.warn("Failed loading local authorities for {}: {}", username, e.getMessage());
            return Set.of(new SimpleGrantedAuthority("ROLE_USER"));
        }
    }
}
