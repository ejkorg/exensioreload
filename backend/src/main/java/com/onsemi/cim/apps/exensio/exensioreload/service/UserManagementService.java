package com.onsemi.cim.apps.exensio.exensioreload.service;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.onsemi.cim.apps.exensio.exensioreload.dto.CreateUserRequest;
import com.onsemi.cim.apps.exensio.exensioreload.dto.UpdateUserRequest;
import com.onsemi.cim.apps.exensio.exensioreload.dto.UserDto;
import com.onsemi.cim.apps.exensio.exensioreload.entity.AppUser;
import com.onsemi.cim.apps.exensio.exensioreload.entity.AuditLog;
import com.onsemi.cim.apps.exensio.exensioreload.entity.PasswordHistory;
import com.onsemi.cim.apps.exensio.exensioreload.repository.AppUserRepository;
import com.onsemi.cim.apps.exensio.exensioreload.repository.PasswordHistoryRepository;

@Service
@Transactional
public class UserManagementService {

    private static final Logger logger = LoggerFactory.getLogger(UserManagementService.class);
    
    private final AppUserRepository userRepository;
    private final PasswordHistoryRepository passwordHistoryRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;

    public UserManagementService(AppUserRepository userRepository,
                               PasswordHistoryRepository passwordHistoryRepository,
                               PasswordEncoder passwordEncoder,
                               AuditService auditService) {
        this.userRepository = userRepository;
        this.passwordHistoryRepository = passwordHistoryRepository;
        this.passwordEncoder = passwordEncoder;
        this.auditService = auditService;
    }

    /**
     * Get all users with filtering and pagination
     */
    @Transactional(readOnly = true)
    public Page<UserDto> getUsers(String search, String role, AppUser.UserStatus status, Pageable pageable) {
        Page<AppUser> users = userRepository.findWithFilters(search, role, status != null ? status.name() : null, pageable);
        return users.map(this::convertToDto);
    }

    /**
     * Get user by ID
     */
    @Transactional(readOnly = true)
    public Optional<UserDto> getUserById(Long id) {
        return userRepository.findById(id).map(this::convertToDto);
    }

    /**
     * Create a new user
     */
    public UserDto createUser(CreateUserRequest request) {
        // Validate request
        validateCreateUserRequest(request);
        
        // Check for existing username/email
        if (userRepository.existsByUsernameIgnoreCase(request.getUsername())) {
            throw new IllegalArgumentException("Username already exists");
        }
        if (request.getEmail() != null && userRepository.existsByEmailIgnoreCase(request.getEmail())) {
            throw new IllegalArgumentException("Email already exists");
        }

        // Create user entity
        AppUser user = new AppUser();
        user.setUsername(request.getUsername());
        user.setEmail(request.getEmail());
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        user.setEnabled(request.isEnabled());
        user.setStatus(request.getStatus() != null ? request.getStatus() : AppUser.UserStatus.ACTIVE);
        user.setRoles(new HashSet<>(request.getRoles()));
        
        // Set audit fields
        Long currentUserId = getCurrentUserId();
        user.setCreatedBy(currentUserId);
        user.setUpdatedBy(currentUserId);

        // Save user
        AppUser savedUser = userRepository.save(user);

        // Save password history
        savePasswordHistory(savedUser.getId(), savedUser.getPasswordHash());

        // Log audit event
        auditService.logAction(currentUserId, AuditLog.Actions.USER_CREATED, AuditLog.ResourceTypes.USER, 
                              savedUser.getId().toString(), Map.of(
                                  "username", savedUser.getUsername(),
                                  "email", savedUser.getEmail() != null ? savedUser.getEmail() : "",
                                  "roles", savedUser.getRoles(),
                                  "status", savedUser.getStatus().toString()
                              ));

        logger.info("User created: id={}, username={}, createdBy={}", savedUser.getId(), savedUser.getUsername(), currentUserId);
        
        return convertToDto(savedUser);
    }

    /**
     * Update an existing user
     */
    public UserDto updateUser(Long id, UpdateUserRequest request) {
        // Validate request
        validateUpdateUserRequest(request);
        
        AppUser user = userRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        // Prevent self-deletion of Super Admin role
        Long currentUserId = getCurrentUserId();
        if (id.equals(currentUserId) && user.isSuperAdmin() && 
            (request.getRoles() == null || !request.getRoles().contains("SUPER_ADMIN"))) {
            throw new IllegalArgumentException("Cannot remove Super Admin role from your own account");
        }

        // Track changes for audit
        Map<String, Object> changes = new java.util.HashMap<>();
        
        // Update fields
        if (request.getUsername() != null && !request.getUsername().equals(user.getUsername())) {
            if (userRepository.existsByUsernameIgnoreCaseAndIdNot(request.getUsername(), id)) {
                throw new IllegalArgumentException("Username already exists");
            }
            changes.put("username", Map.of("old", user.getUsername(), "new", request.getUsername()));
            user.setUsername(request.getUsername());
        }

        if (request.getEmail() != null && !request.getEmail().equals(user.getEmail())) {
            if (userRepository.existsByEmailIgnoreCaseAndIdNot(request.getEmail(), id)) {
                throw new IllegalArgumentException("Email already exists");
            }
            changes.put("email", Map.of("old", user.getEmail(), "new", request.getEmail()));
            user.setEmail(request.getEmail());
        }

        if (request.getPassword() != null && !request.getPassword().isEmpty()) {
            // Check password history
            if (isPasswordReused(user.getId(), request.getPassword())) {
                throw new IllegalArgumentException("Password has been used recently. Please choose a different password.");
            }
            
            String newPasswordHash = passwordEncoder.encode(request.getPassword());
            user.setPasswordHash(newPasswordHash);
            savePasswordHistory(user.getId(), newPasswordHash);
            changes.put("password", "changed");
        }

        if (request.getStatus() != null && !request.getStatus().equals(user.getStatus())) {
            changes.put("status", Map.of("old", user.getStatus().toString(), "new", request.getStatus().toString()));
            user.setStatus(request.getStatus());
        }

        if (request.getRoles() != null && !request.getRoles().equals(user.getRoles())) {
            changes.put("roles", Map.of("old", user.getRoles(), "new", request.getRoles()));
            user.setRoles(new HashSet<>(request.getRoles()));
        }

        if (request.isEnabled() != user.isEnabled()) {
            changes.put("enabled", Map.of("old", user.isEnabled(), "new", request.isEnabled()));
            user.setEnabled(request.isEnabled());
        }

        // Set audit fields
        user.setUpdatedBy(currentUserId);

        // Save user
        AppUser savedUser = userRepository.save(user);

        // Log audit event
        if (!changes.isEmpty()) {
            auditService.logAction(currentUserId, AuditLog.Actions.USER_UPDATED, AuditLog.ResourceTypes.USER, 
                                  savedUser.getId().toString(), Map.of(
                                      "username", savedUser.getUsername(),
                                      "changes", changes
                                  ));
        }

        logger.info("User updated: id={}, username={}, updatedBy={}", savedUser.getId(), savedUser.getUsername(), currentUserId);
        
        return convertToDto(savedUser);
    }

    /**
     * Delete a user
     */
    public void deleteUser(Long id) {
        AppUser user = userRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        Long currentUserId = getCurrentUserId();
        
        // Prevent self-deletion
        if (id.equals(currentUserId)) {
            throw new IllegalArgumentException("Cannot delete your own account");
        }

        // Prevent deletion of the last Super Admin
        if (user.isSuperAdmin()) {
            long superAdminCount = userRepository.countByRole("SUPER_ADMIN");
            if (superAdminCount <= 1) {
                throw new IllegalArgumentException("Cannot delete the last Super Admin user");
            }
        }

        // Log audit event before deletion
        auditService.logAction(currentUserId, AuditLog.Actions.USER_DELETED, AuditLog.ResourceTypes.USER, 
                              id.toString(), Map.of(
                                  "username", user.getUsername(),
                                  "email", user.getEmail() != null ? user.getEmail() : "",
                                  "roles", user.getRoles()
                              ));

        // Delete user (cascade will handle related records)
        userRepository.delete(user);

        logger.info("User deleted: id={}, username={}, deletedBy={}", id, user.getUsername(), currentUserId);
    }

    /**
     * Update user roles
     */
    public UserDto updateUserRoles(Long id, Set<String> roles) {
        AppUser user = userRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        Long currentUserId = getCurrentUserId();
        
        // Prevent self-removal of Super Admin role
        if (id.equals(currentUserId) && user.isSuperAdmin() && !roles.contains("SUPER_ADMIN")) {
            throw new IllegalArgumentException("Cannot remove Super Admin role from your own account");
        }

        Set<String> oldRoles = new HashSet<>(user.getRoles());
        user.setRoles(new HashSet<>(roles));
        user.setUpdatedBy(currentUserId);

        AppUser savedUser = userRepository.save(user);

        // Log audit event
        auditService.logAction(currentUserId, AuditLog.Actions.ROLE_CHANGED, AuditLog.ResourceTypes.USER, 
                              savedUser.getId().toString(), Map.of(
                                  "username", savedUser.getUsername(),
                                  "oldRoles", oldRoles,
                                  "newRoles", roles
                              ));

        logger.info("User roles updated: id={}, username={}, updatedBy={}", savedUser.getId(), savedUser.getUsername(), currentUserId);
        
        return convertToDto(savedUser);
    }

    /**
     * Get user statistics
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getUserStatistics() {
        long totalUsers = userRepository.count();
        long activeUsers = userRepository.countByStatus(AppUser.UserStatus.ACTIVE);
        long inactiveUsers = userRepository.countByStatus(AppUser.UserStatus.INACTIVE);
        long lockedUsers = userRepository.countByStatus(AppUser.UserStatus.LOCKED);
        long superAdmins = userRepository.countByRole("SUPER_ADMIN");
        long admins = userRepository.countByRole("ADMIN");

        return Map.of(
            "totalUsers", totalUsers,
            "activeUsers", activeUsers,
            "inactiveUsers", inactiveUsers,
            "lockedUsers", lockedUsers,
            "superAdmins", superAdmins,
            "admins", admins
        );
    }

    /**
     * Convert AppUser entity to DTO
     */
    private UserDto convertToDto(AppUser user) {
        UserDto dto = new UserDto();
        dto.setId(user.getId());
        dto.setUsername(user.getUsername());
        dto.setEmail(user.getEmail());
        dto.setEnabled(user.isEnabled());
        dto.setStatus(user.getStatus());
        dto.setRoles(user.getRoles());
        dto.setCreatedAt(user.getCreatedAt());
        dto.setUpdatedAt(user.getUpdatedAt());
        dto.setLastLoginAt(user.getLastLoginAt());
        dto.setCreatedBy(user.getCreatedBy());
        dto.setUpdatedBy(user.getUpdatedBy());
        return dto;
    }

    /**
     * Validate create user request
     */
    private void validateCreateUserRequest(CreateUserRequest request) {
        if (request.getUsername() == null || request.getUsername().trim().isEmpty()) {
            throw new IllegalArgumentException("Username is required");
        }
        if (request.getUsername().length() < 3 || request.getUsername().length() > 50) {
            throw new IllegalArgumentException("Username must be between 3 and 50 characters");
        }
        if (request.getPassword() == null || request.getPassword().length() < 8) {
            throw new IllegalArgumentException("Password must be at least 8 characters long");
        }
        if (request.getRoles() == null || request.getRoles().isEmpty()) {
            throw new IllegalArgumentException("At least one role is required");
        }
        if (request.getEmail() != null && !request.getEmail().trim().isEmpty()) {
            if (!isValidEmail(request.getEmail())) {
                throw new IllegalArgumentException("Email must be valid");
            }
        }
    }

    /**
     * Validate update user request
     */
    private void validateUpdateUserRequest(UpdateUserRequest request) {
        if (request.getUsername() != null && !request.getUsername().trim().isEmpty()) {
            if (request.getUsername().length() < 3 || request.getUsername().length() > 50) {
                throw new IllegalArgumentException("Username must be between 3 and 50 characters");
            }
        }
        if (request.getPassword() != null && !request.getPassword().isEmpty()) {
            if (request.getPassword().length() < 8) {
                throw new IllegalArgumentException("Password must be at least 8 characters long");
            }
        }
        if (request.getEmail() != null && !request.getEmail().trim().isEmpty()) {
            if (!isValidEmail(request.getEmail())) {
                throw new IllegalArgumentException("Email must be valid");
            }
        }
    }

    /**
     * Simple email validation
     */
    private boolean isValidEmail(String email) {
        if (email == null || email.trim().isEmpty()) {
            return false;
        }
        String emailRegex = "^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$";
        return email.matches(emailRegex);
    }

    /**
     * Check if password has been used recently
     */
    private boolean isPasswordReused(Long userId, String newPassword) {
        List<String> recentPasswords = passwordHistoryRepository.findLastNPasswordHashesForUser(userId, 5);
        return recentPasswords.stream().anyMatch(hash -> passwordEncoder.matches(newPassword, hash));
    }

    /**
     * Save password to history
     */
    private void savePasswordHistory(Long userId, String passwordHash) {
        PasswordHistory history = new PasswordHistory(userId, passwordHash);
        passwordHistoryRepository.save(history);
        
        // Clean up old password history (keep only last 10)
        passwordHistoryRepository.cleanupOldPasswordsForUser(userId, 10);
    }

    /**
     * Get current user ID from security context
     */
    private Long getCurrentUserId() {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getName() != null) {
                Optional<AppUser> user = userRepository.findByUsername(auth.getName());
                return user.map(AppUser::getId).orElse(null);
            }
        } catch (Exception e) {
            logger.debug("Could not determine current user ID: {}", e.getMessage());
        }
        return null;
    }
}
