package com.onsemi.cim.apps.exensio.exensioreload.service;

import java.util.HashSet;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.onsemi.cim.apps.exensio.exensioreload.config.SsoProperties;
import com.onsemi.cim.apps.exensio.exensioreload.entity.AppUser;
import com.onsemi.cim.apps.exensio.exensioreload.repository.AppUserRepository;

/**
 * @author fg8n8x
 */

/**
 * Handles Just-In-Time (JIT) user provisioning for SSO logins.
 * Creates a new AppUser on first SSO login, or loads and updates an existing one.
 *
 * <p>Matching priority:
 * <ol>
 *   <li>Match by username extracted from email prefix (e.g. "fg8n8x" from "fg8n8x@onsemi.com")</li>
 *   <li>Match by full email address</li>
 *   <li>JIT-provision a new user if neither match exists</li>
 * </ol>
 *
 * <p>When an existing local user is found, their locally-assigned roles are preserved and
 * take precedence over IdP-derived roles. The email is backfilled if missing.
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
     * Provisions a new user or loads an existing one.
     *
     * <p>Lookup order:
     * <ol>
     *   <li>By {@code idpUsername} from OIDC {@code preferred_username} claim (e.g. "fg8n8x").
     *       This is the AD login name and matches existing local DB usernames.</li>
     *   <li>By full {@code email} address (for users already provisioned via a previous SSO login
     *       where email was stored as the username).</li>
     *   <li>JIT-provision a brand new user if no match is found.</li>
     * </ol>
     *
     * <p>NOTE: Email prefix is NOT used for matching because in AD, username and email
     * are different identifiers (e.g. username=fg8n8x, email=junifferallan.garcia@onsemi.com).
     *
     * <p>When a local user is found, their existing DB roles are preserved and always
     * take precedence over IdP-derived roles.
     *
     * @param email       corporate email from OIDC token (e.g. junifferallan.garcia@onsemi.com)
     * @param idpUsername AD username from preferred_username claim (e.g. fg8n8x); may be null
     * @param idpRoles    roles derived from IdP group claims (only used for new JIT users)
     * @return the persisted {@link AppUser}
     */
    public AppUser provisionOrLoad(String email, String idpUsername, Set<String> idpRoles) {

        // 1. Match by AD username from preferred_username (most reliable)
        if (idpUsername != null && !idpUsername.isBlank()) {
            AppUser byIdpUsername = userRepository.findByUsername(idpUsername).orElse(null);
            if (byIdpUsername != null) {
                logger.info("SSO login: matched local user '{}' via preferred_username claim", byIdpUsername.getUsername());
                return backfillEmailAndSave(byIdpUsername, email);
            }
        }

        // 2. Match by full email, case-insensitive (covers users previously JIT-provisioned with
        //    email as username, and handles case differences between IdP and local DB)
        AppUser byEmail = userRepository.findByEmailIgnoreCase(email).orElse(null);
        if (byEmail != null) {
            logger.debug("SSO login: matched existing user '{}' by email '{}' (case-insensitive)", byEmail.getUsername(), email);
            return backfillEmailAndSave(byEmail, email);
        }

        // 3. JIT-provision: use idpUsername as the username (preferred), fallback to full email
        String newUsername = (idpUsername != null && !idpUsername.isBlank()) ? idpUsername : email;
        return createNewSsoUser(email, newUsername, resolveRoles(idpRoles));
    }

    // --- private helpers ---

    /**
     * Backfills the email on an existing local user if not yet set, preserving all roles.
     */
    private AppUser backfillEmailAndSave(AppUser user, String email) {
        if (user.getEmail() == null || user.getEmail().isBlank()) {
            logger.info("SSO login: backfilling email '{}' for local user '{}'", email, user.getUsername());
            user.setEmail(email);
            return userRepository.save(user);
        }
        return user;
    }

    private Set<String> resolveRoles(Set<String> roles) {
        if (roles == null || roles.isEmpty()) {
            Set<String> defaults = new HashSet<>();
            defaults.add(ssoProperties.getDefaultRole());
            return defaults;
        }
        return new HashSet<>(roles);
    }

    private AppUser createNewSsoUser(String email, String username, Set<String> roles) {
        logger.info("SSO login: JIT provisioning new user '{}' for email '{}'", username, email);

        AppUser user = new AppUser();
        user.setUsername(username);
        user.setEmail(email);
        user.setPasswordHash(SSO_PASSWORD_PLACEHOLDER);
        user.setEnabled(true);
        user.setStatus(AppUser.UserStatus.ACTIVE);
        user.setRoles(roles);

        return userRepository.save(user);
    }
}
