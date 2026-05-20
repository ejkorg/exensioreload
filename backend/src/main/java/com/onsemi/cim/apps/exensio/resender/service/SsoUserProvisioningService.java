package com.onsemi.cim.apps.exensio.resender.service;

import com.onsemi.cim.apps.exensio.resender.config.SsoProperties;
import com.onsemi.cim.apps.exensio.resender.entity.AppUser;
import com.onsemi.cim.apps.exensio.resender.repository.AppUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.Set;

/**
 * @author fg8n8x
 */

/**
 * Handles Just-In-Time (JIT) user provisioning for SSO logins.
 * Creates a new AppUser on first SSO login, or loads and updates an existing one.
 */
@Service
@Transactional
public class SsoUserProvisioningService {

    private static final Logger logger = LoggerFactory.getLogger(SsoUserProvisioningService.class);

    private static final String SSO_PASSWORD_PLACEHOLDER = "{noop}SSO_USER_NO_PASSWORD";

    private final AppUserRepository userRepository;
    private final SsoProperties ssoProperties;

    public SsoUserProvisioningService(AppUserRepository userRepository, SsoProperties ssoProperties) {
        this.userRepository = userRepository;
        this.ssoProperties = ssoProperties;
    }

    /**
     * Provisions a new user or loads an existing one based on the corporate email.
     *
     * <p>If no user with the given email exists, a new {@link AppUser} is created with:
     * <ul>
     *   <li>{@code username} = email</li>
     *   <li>{@code passwordHash} = {@value #SSO_PASSWORD_PLACEHOLDER} (prevents local login)</li>
     *   <li>{@code enabled} = true</li>
     *   <li>{@code status} = ACTIVE</li>
     *   <li>{@code roles} = provided roles, or {@code defaultRole} if the set is empty</li>
     * </ul>
     *
     * <p>If a user already exists, their roles are updated if the provided set differs.
     *
     * @param email the corporate email address from the IdP claim (used as username)
     * @param roles the set of local role names derived from IdP group claims
     * @return the persisted {@link AppUser} (never null)
     * @throws RuntimeException if a database error occurs during save
     */
    public AppUser provisionOrLoad(String email, Set<String> roles) {
        Set<String> effectiveRoles = resolveRoles(roles);

        return userRepository.findByEmail(email)
                .map(existing -> updateRolesIfChanged(existing, effectiveRoles))
                .orElseGet(() -> createNewSsoUser(email, effectiveRoles));
    }

    // --- private helpers ---

    private Set<String> resolveRoles(Set<String> roles) {
        if (roles == null || roles.isEmpty()) {
            Set<String> defaults = new HashSet<>();
            defaults.add(ssoProperties.getDefaultRole());
            return defaults;
        }
        return new HashSet<>(roles);
    }

    private AppUser updateRolesIfChanged(AppUser user, Set<String> newRoles) {
        if (!user.getRoles().equals(newRoles)) {
            logger.info("SSO login: updating roles for user '{}' from {} to {}", user.getUsername(), user.getRoles(), newRoles);
            user.setRoles(newRoles);
            return userRepository.save(user);
        }
        logger.debug("SSO login: existing user '{}' loaded, roles unchanged", user.getUsername());
        return user;
    }

    private AppUser createNewSsoUser(String email, Set<String> roles) {
        logger.info("SSO login: JIT provisioning new user for email '{}'", email);

        AppUser user = new AppUser();
        user.setUsername(email);
        user.setEmail(email);
        user.setPasswordHash(SSO_PASSWORD_PLACEHOLDER);
        user.setEnabled(true);
        user.setStatus(AppUser.UserStatus.ACTIVE);
        user.setRoles(roles);

        return userRepository.save(user);
    }
}
