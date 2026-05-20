package com.onsemi.cim.apps.exensio.resender.service;

import com.onsemi.cim.apps.exensio.resender.entity.AppUser;
import com.onsemi.cim.apps.exensio.resender.repository.AppUserRepository;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
public class RoleService {

    private final AppUserRepository userRepository;

    public RoleService(AppUserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * Check if the current user has a specific permission
     */
    public boolean hasPermission(String permission) {
        AppUser currentUser = getCurrentUser();
        if (currentUser == null) {
            return false;
        }
        return hasPermission(currentUser, permission);
    }

    /**
     * Check if a user has a specific permission
     */
    public boolean hasPermission(AppUser user, String permission) {
        if (user == null || user.getRoles() == null) {
            return false;
        }

        // Super Admin has all permissions
        if (user.isSuperAdmin()) {
            return true;
        }

        // Check role-based permissions
        Set<String> roles = user.getRoles();
        return switch (permission) {
            case Permissions.VIEW_DASHBOARD -> roles.contains("ADMIN") || roles.contains("REGULAR_USER");
            case Permissions.CREATE_RESEND_REQUEST -> roles.contains("ADMIN") || roles.contains("REGULAR_USER");
            case Permissions.MANAGE_USERS -> roles.contains("SUPER_ADMIN");
            case Permissions.DATE_RANGE_OVERRIDE -> roles.contains("SUPER_ADMIN");
            case Permissions.VIEW_AUDIT_LOGS -> roles.contains("SUPER_ADMIN");
            case Permissions.ADMIN_ACCESS -> roles.contains("ADMIN") || roles.contains("SUPER_ADMIN");
            default -> false;
        };
    }

    /**
     * Check if the current user is a Super Admin
     */
    public boolean isSuperAdmin() {
        AppUser currentUser = getCurrentUser();
        return currentUser != null && currentUser.isSuperAdmin();
    }

    /**
     * Check if a user is a Super Admin
     */
    public boolean isSuperAdmin(AppUser user) {
        return user != null && user.isSuperAdmin();
    }

    /**
     * Check if the current user is an Admin (including Super Admin)
     */
    public boolean isAdmin() {
        AppUser currentUser = getCurrentUser();
        return currentUser != null && currentUser.isAdmin();
    }

    /**
     * Check if a user is an Admin (including Super Admin)
     */
    public boolean isAdmin(AppUser user) {
        return user != null && user.isAdmin();
    }

    /**
     * Get all permissions for the current user
     */
    public List<String> getCurrentUserPermissions() {
        AppUser currentUser = getCurrentUser();
        return getUserPermissions(currentUser);
    }

    /**
     * Get all permissions for a user
     */
    public List<String> getUserPermissions(AppUser user) {
        if (user == null) {
            return List.of();
        }

        if (user.isSuperAdmin()) {
            return List.of(
                Permissions.VIEW_DASHBOARD,
                Permissions.CREATE_RESEND_REQUEST,
                Permissions.MANAGE_USERS,
                Permissions.DATE_RANGE_OVERRIDE,
                Permissions.VIEW_AUDIT_LOGS,
                Permissions.ADMIN_ACCESS
            );
        }

        if (user.isAdmin()) {
            return List.of(
                Permissions.VIEW_DASHBOARD,
                Permissions.CREATE_RESEND_REQUEST,
                Permissions.ADMIN_ACCESS
            );
        }

        if (user.hasRole("REGULAR_USER")) {
            return List.of(
                Permissions.VIEW_DASHBOARD,
                Permissions.CREATE_RESEND_REQUEST
            );
        }

        return List.of();
    }

    /**
     * Check if the current user can manage users
     */
    public boolean canManageUsers() {
        return hasPermission(Permissions.MANAGE_USERS);
    }

    /**
     * Check if the current user can override date ranges
     */
    public boolean canOverrideDateRange() {
        return hasPermission(Permissions.DATE_RANGE_OVERRIDE);
    }

    /**
     * Check if the current user can view audit logs
     */
    public boolean canViewAuditLogs() {
        return hasPermission(Permissions.VIEW_AUDIT_LOGS);
    }

    /**
     * Get the current authenticated user
     */
    public AppUser getCurrentUser() {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getName() != null) {
                Optional<AppUser> user = userRepository.findByUsername(auth.getName());
                return user.orElse(null);
            }
        } catch (Exception e) {
            // Log error but don't throw exception
        }
        return null;
    }

    /**
     * Get the current user ID
     */
    public Long getCurrentUserId() {
        AppUser currentUser = getCurrentUser();
        return currentUser != null ? currentUser.getId() : null;
    }

    /**
     * Get the current username
     */
    public String getCurrentUsername() {
        AppUser currentUser = getCurrentUser();
        return currentUser != null ? currentUser.getUsername() : null;
    }

    /**
     * Permission constants
     */
    public static final class Permissions {
        public static final String VIEW_DASHBOARD = "VIEW_DASHBOARD";
        public static final String CREATE_RESEND_REQUEST = "CREATE_RESEND_REQUEST";
        public static final String MANAGE_USERS = "MANAGE_USERS";
        public static final String DATE_RANGE_OVERRIDE = "DATE_RANGE_OVERRIDE";
        public static final String VIEW_AUDIT_LOGS = "VIEW_AUDIT_LOGS";
        public static final String ADMIN_ACCESS = "ADMIN_ACCESS";
    }
}
