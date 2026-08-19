package com.onsemi.cim.apps.exensio.exensioreload.controller;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.onsemi.cim.apps.exensio.exensioreload.entity.AppUser;
import com.onsemi.cim.apps.exensio.exensioreload.repository.AppUserRepository;

@RestController
@RequestMapping("/api/admin/users")
public class UserAdminController {
    
    private final AppUserRepository appUserRepository;
    private final PasswordEncoder passwordEncoder;

    public UserAdminController(AppUserRepository appUserRepository, PasswordEncoder passwordEncoder) {
        this.appUserRepository = appUserRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Helper method to create error response maps
     */
    private Map<String, Object> createErrorResponse(String message) {
        Map<String, Object> errorResponse = new HashMap<>();
        errorResponse.put("error", message);
        return errorResponse;
    }

    /**
     * Helper method to create error response maps with timestamp
     */
    private Map<String, Object> createErrorResponseWithTimestamp(String message) {
        Map<String, Object> errorResponse = new HashMap<>();
        errorResponse.put("error", message);
        errorResponse.put("timestamp", Instant.now());
        return errorResponse;
    }

    /**
     * Simple test endpoint to verify controller is working
     */
    @GetMapping("/test")
    public ResponseEntity<Map<String, Object>> test() {
        Map<String, Object> response = new HashMap<>();
        response.put("status", "OK");
        response.put("message", "UserAdminController is working");
        response.put("timestamp", Instant.now());
        response.put("userCount", appUserRepository.count());
        response.put("sampleUsers", appUserRepository.findAll().stream()
            .limit(3)
            .map(user -> {
                Map<String, Object> userMap = new HashMap<>();
                userMap.put("id", user.getId());
                userMap.put("username", user.getUsername());
                userMap.put("roles", user.getRoles());
                userMap.put("status", user.getStatus().toString());
                return userMap;
            })
            .collect(Collectors.toList()));
        return ResponseEntity.ok(response);
    }

    /**
     * Debug endpoint to list all users and their roles
     */
    @GetMapping("/debug-all")
    public ResponseEntity<Map<String, Object>> debugAllUsers() {
        try {
            List<Map<String, Object>> allUsers = appUserRepository.findAll().stream()
                .map(user -> {
                    Map<String, Object> userMap = new HashMap<>();
                    userMap.put("id", user.getId());
                    userMap.put("username", user.getUsername());
                    userMap.put("email", user.getEmail() != null ? user.getEmail() : "null");
                    userMap.put("roles", user.getRoles());
                    userMap.put("status", user.getStatus().toString());
                    userMap.put("enabled", user.isEnabled());
                    userMap.put("lastLoginAt", user.getLastLoginAt() != null ? user.getLastLoginAt().toString() : "null");
                    userMap.put("createdAt", user.getCreatedAt() != null ? user.getCreatedAt().toString() : "null");
                    return userMap;
                })
                .collect(Collectors.toList());

            Map<String, Object> response = new HashMap<>();
            response.put("status", "OK");
            response.put("message", "All users in database");
            response.put("timestamp", Instant.now());
            response.put("userCount", appUserRepository.count());
            response.put("users", allUsers);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", "Failed to fetch users: " + e.getMessage());
            errorResponse.put("timestamp", Instant.now());
            return ResponseEntity.status(500).body(errorResponse);
        }
    }

    /**
     * Get all users with filtering support
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> getUsers(
            @RequestParam(value = "search", required = false) String search,
            @RequestParam(value = "role", required = false) String role,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size,
            @RequestParam(value = "sortBy", defaultValue = "username") String sort,
            @RequestParam(value = "sortDir", defaultValue = "asc") String direction) {
        try {
            // Parse status parameter
            AppUser.UserStatus userStatus = null;
            if (status != null && !status.trim().isEmpty() && !"All Statuses".equals(status)) {
                try {
                    userStatus = AppUser.UserStatus.valueOf(status.toUpperCase());
                } catch (IllegalArgumentException e) {
                    // Invalid status, ignore
                }
            }
            
            // Parse role parameter
            String roleFilter = null;
            if (role != null && !role.trim().isEmpty() && !"All Roles".equals(role)) {
                roleFilter = role.trim();
            }
            
            // Parse search parameter
            String searchFilter = null;
            if (search != null && !search.trim().isEmpty()) {
                searchFilter = search.trim();
            }
            
            // Create pageable with sorting
            Sort.Direction sortDirection = "desc".equalsIgnoreCase(direction) ? Sort.Direction.DESC : Sort.Direction.ASC;
            Pageable pageable = PageRequest.of(page, size, Sort.by(sortDirection, sort));
            
            // Use the repository's filtering method
            Page<AppUser> userPage = appUserRepository.findWithFilters(searchFilter, roleFilter, userStatus != null ? userStatus.name() : null, pageable);
            
            List<Map<String, Object>> userList = userPage.getContent().stream()
                .map(user -> {
                    Map<String, Object> userMap = new HashMap<>();
                    userMap.put("id", user.getId());
                    userMap.put("username", user.getUsername());
                    userMap.put("email", user.getEmail() != null ? user.getEmail() : "");
                    userMap.put("enabled", user.isEnabled());
                    userMap.put("status", user.getStatus().toString());
                    userMap.put("roles", user.getRoles());
                    userMap.put("createdAt", user.getCreatedAt() != null ? user.getCreatedAt().toString() : "");
                    userMap.put("lastLoginAt", user.getLastLoginAt() != null ? user.getLastLoginAt().toString() : null);
                    return userMap;
                })
                .collect(Collectors.toList());
            
            // Return in UserPage format expected by frontend
            Map<String, Object> response = new HashMap<>();
            response.put("content", userList);
            response.put("totalElements", userPage.getTotalElements());
            response.put("totalPages", userPage.getTotalPages());
            response.put("size", userPage.getSize());
            response.put("number", userPage.getNumber());
            response.put("first", userPage.isFirst());
            response.put("last", userPage.isLast());
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("content", List.of());
            errorResponse.put("totalElements", 0);
            errorResponse.put("totalPages", 0);
            errorResponse.put("size", 0);
            errorResponse.put("number", 0);
            errorResponse.put("first", true);
            errorResponse.put("last", true);
            errorResponse.put("error", e.getMessage());
            return ResponseEntity.ok(errorResponse);
        }
    }

    /**
     * Get user by ID
     */
    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getUserById(@PathVariable Long id) {
        try {
            Optional<AppUser> userOpt = appUserRepository.findById(id);
            if (userOpt.isPresent()) {
                AppUser user = userOpt.get();
                Map<String, Object> result = new HashMap<>();
                result.put("id", user.getId());
                result.put("username", user.getUsername());
                result.put("email", user.getEmail() != null ? user.getEmail() : "");
                result.put("enabled", user.isEnabled());
                result.put("status", user.getStatus().toString());
                result.put("roles", user.getRoles());
                result.put("createdAt", user.getCreatedAt() != null ? user.getCreatedAt().toString() : "");
                result.put("lastLoginAt", user.getLastLoginAt() != null ? user.getLastLoginAt().toString() : null);
                return ResponseEntity.ok(result);
            } else {
                return ResponseEntity.notFound().build();
            }
        } catch (Exception e) {
            return ResponseEntity.ok(createErrorResponse(e.getMessage()));
        }
    }

    /**
     * Create a new user
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> createUser(@RequestBody Map<String, Object> request) {
        try {
            // Extract request data
            String username = (String) request.get("username");
            String email = (String) request.get("email");
            String password = (String) request.get("password");
            @SuppressWarnings("unchecked")
            List<String> rolesList = (List<String>) request.get("roles");
            Boolean enabled = (Boolean) request.getOrDefault("enabled", true);
            String statusStr = (String) request.getOrDefault("status", "ACTIVE");

            // Validate required fields
            if (username == null || username.trim().isEmpty()) {
                return ResponseEntity.badRequest().body(createErrorResponse("Username is required"));
            }
            if (password == null || password.length() < 8) {
                return ResponseEntity.badRequest().body(createErrorResponse("Password must be at least 8 characters"));
            }
            if (rolesList == null || rolesList.isEmpty()) {
                return ResponseEntity.badRequest().body(createErrorResponse("At least one role is required"));
            }

            // Check if username already exists
            if (appUserRepository.findByUsername(username).isPresent()) {
                return ResponseEntity.badRequest().body(createErrorResponse("Username already exists"));
            }

            // Check if email already exists (if provided)
            if (email != null && !email.trim().isEmpty() && appUserRepository.findByEmail(email).isPresent()) {
                return ResponseEntity.badRequest().body(createErrorResponse("Email already exists"));
            }

            // Ensure roles have ROLE_ prefix before storing in database
            Set<String> prefixedRoles = rolesList.stream()
                .map(role -> role.startsWith("ROLE_") ? role : "ROLE_" + role)
                .collect(Collectors.toSet());
            
            // Create new user
            AppUser newUser = new AppUser();
            newUser.setUsername(username.trim());
            newUser.setEmail(email != null && !email.trim().isEmpty() ? email.trim() : null);
            newUser.setPasswordHash(passwordEncoder.encode(password));
            newUser.setEnabled(enabled);
            newUser.setStatus(AppUser.UserStatus.valueOf(statusStr));
            newUser.setRoles(prefixedRoles);

            // Save user
            AppUser savedUser = appUserRepository.save(newUser);

            // Return created user
            Map<String, Object> result = new HashMap<>();
            result.put("id", savedUser.getId());
            result.put("username", savedUser.getUsername());
            result.put("email", savedUser.getEmail() != null ? savedUser.getEmail() : "");
            result.put("enabled", savedUser.isEnabled());
            result.put("status", savedUser.getStatus().toString());
            result.put("roles", savedUser.getRoles());
            result.put("createdAt", savedUser.getCreatedAt() != null ? savedUser.getCreatedAt().toString() : "");
            return ResponseEntity.ok(result);

        } catch (Exception e) {
            return ResponseEntity.badRequest().body(createErrorResponse(e.getMessage()));
        }
    }

    /**
     * Update existing user
     */
    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> updateUser(@PathVariable Long id, @RequestBody Map<String, Object> request) {
        try {
            Optional<AppUser> userOpt = appUserRepository.findById(id);
            if (userOpt.isEmpty()) {
                return ResponseEntity.notFound().build();
            }

            AppUser user = userOpt.get();
            
            // Update fields if provided
            if (request.containsKey("username")) {
                String username = (String) request.get("username");
                if (username != null && !username.trim().isEmpty()) {
                    // Check if username already exists for another user
                    Optional<AppUser> existingUser = appUserRepository.findByUsername(username);
                    if (existingUser.isPresent() && !existingUser.get().getId().equals(id)) {
                        return ResponseEntity.badRequest().body(createErrorResponse("Username already exists"));
                    }
                    user.setUsername(username.trim());
                }
            }

            if (request.containsKey("email")) {
                String email = (String) request.get("email");
                if (email != null && !email.trim().isEmpty()) {
                    // Check if email already exists for another user
                    Optional<AppUser> existingUser = appUserRepository.findByEmail(email);
                    if (existingUser.isPresent() && !existingUser.get().getId().equals(id)) {
                        return ResponseEntity.badRequest().body(createErrorResponse("Email already exists"));
                    }
                    user.setEmail(email.trim());
                } else {
                    user.setEmail(null);
                }
            }

            if (request.containsKey("password")) {
                String password = (String) request.get("password");
                if (password != null && !password.isEmpty()) {
                    if (password.length() < 8) {
                        return ResponseEntity.badRequest().body(createErrorResponse("Password must be at least 8 characters"));
                    }
                    user.setPasswordHash(passwordEncoder.encode(password));
                }
            }

            if (request.containsKey("enabled")) {
                Boolean enabled = (Boolean) request.get("enabled");
                if (enabled != null) {
                    user.setEnabled(enabled);
                }
            }

            if (request.containsKey("status")) {
                String statusStr = (String) request.get("status");
                if (statusStr != null) {
                    user.setStatus(AppUser.UserStatus.valueOf(statusStr));
                }
            }

            if (request.containsKey("roles")) {
                @SuppressWarnings("unchecked")
                List<String> rolesList = (List<String>) request.get("roles");
                if (rolesList != null) {
                    // Ensure roles have ROLE_ prefix before storing in database
                    Set<String> prefixedRoles = rolesList.stream()
                        .map(role -> role.startsWith("ROLE_") ? role : "ROLE_" + role)
                        .collect(Collectors.toSet());
                    user.setRoles(prefixedRoles);
                }
            }

            // Save updated user
            AppUser savedUser = appUserRepository.save(user);

            // Return updated user
            Map<String, Object> result = new HashMap<>();
            result.put("id", savedUser.getId());
            result.put("username", savedUser.getUsername());
            result.put("email", savedUser.getEmail() != null ? savedUser.getEmail() : "");
            result.put("enabled", savedUser.isEnabled());
            result.put("status", savedUser.getStatus().toString());
            result.put("roles", savedUser.getRoles());
            result.put("createdAt", savedUser.getCreatedAt() != null ? savedUser.getCreatedAt().toString() : "");
            result.put("updatedAt", savedUser.getUpdatedAt() != null ? savedUser.getUpdatedAt().toString() : "");
            return ResponseEntity.ok(result);

        } catch (Exception e) {
            return ResponseEntity.badRequest().body(createErrorResponse(e.getMessage()));
        }
    }

    /**
     * Delete user
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> deleteUser(@PathVariable Long id) {
        try {
            Optional<AppUser> userOpt = appUserRepository.findById(id);
            if (userOpt.isEmpty()) {
                return ResponseEntity.notFound().build();
            }

            appUserRepository.deleteById(id);
            Map<String, Object> response = new HashMap<>();
            response.put("message", "User deleted successfully");
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            return ResponseEntity.badRequest().body(createErrorResponse(e.getMessage()));
        }
    }

    /**
     * Update user roles
     */
    @PostMapping("/{id}/roles")
    public ResponseEntity<Map<String, Object>> updateUserRoles(@PathVariable Long id, @RequestBody Map<String, Object> request) {
        try {
            Optional<AppUser> userOpt = appUserRepository.findById(id);
            if (userOpt.isEmpty()) {
                return ResponseEntity.notFound().build();
            }

            AppUser user = userOpt.get();
            
            @SuppressWarnings("unchecked")
            List<String> rolesList = (List<String>) request.get("roles");
            if (rolesList == null) {
                return ResponseEntity.badRequest().body(createErrorResponse("Roles list is required"));
            }

            // Ensure roles have ROLE_ prefix before storing in database
            Set<String> prefixedRoles = rolesList.stream()
                .map(role -> role.startsWith("ROLE_") ? role : "ROLE_" + role)
                .collect(Collectors.toSet());

            user.setRoles(prefixedRoles);
            AppUser savedUser = appUserRepository.save(user);

            // Return updated user
            Map<String, Object> result = new HashMap<>();
            result.put("id", savedUser.getId());
            result.put("username", savedUser.getUsername());
            result.put("email", savedUser.getEmail() != null ? savedUser.getEmail() : "");
            result.put("enabled", savedUser.isEnabled());
            result.put("status", savedUser.getStatus().toString());
            result.put("roles", savedUser.getRoles());
            result.put("createdAt", savedUser.getCreatedAt() != null ? savedUser.getCreatedAt().toString() : "");
            result.put("updatedAt", savedUser.getUpdatedAt() != null ? savedUser.getUpdatedAt().toString() : "");
            return ResponseEntity.ok(result);

        } catch (Exception e) {
            return ResponseEntity.badRequest().body(createErrorResponse(e.getMessage()));
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
        Map<String, Object> health = new HashMap<>();
        health.put("status", "OK");
        health.put("timestamp", Instant.now());
        health.put("userCount", appUserRepository.count());
        health.put("availableRoles", List.of("ROLE_USER", "ROLE_ADMIN", "ROLE_SUPER_ADMIN"));
        return ResponseEntity.ok(health);
    }

    /**
     * Get user statistics
     */
    @GetMapping("/statistics")
    public ResponseEntity<Map<String, Object>> getUserStatistics() {
        try {
            long totalUsers = appUserRepository.count();
            long activeUsers = appUserRepository.countByStatus(AppUser.UserStatus.ACTIVE);
            long inactiveUsers = appUserRepository.countByStatus(AppUser.UserStatus.INACTIVE);
            long lockedUsers = appUserRepository.countByStatus(AppUser.UserStatus.LOCKED);
            long superAdmins = appUserRepository.countByRole("ROLE_SUPER_ADMIN");
            
            // Count admins properly - anyone with ROLE_ADMIN or ROLE_SUPER_ADMIN role
            long admins = appUserRepository.findAll().stream()
                .mapToLong(user -> user.isAdmin() ? 1L : 0L)
                .sum();

            Map<String, Object> stats = new HashMap<>();
            stats.put("totalUsers", totalUsers);
            stats.put("activeUsers", activeUsers);
            stats.put("inactiveUsers", inactiveUsers);
            stats.put("lockedUsers", lockedUsers);
            stats.put("superAdmins", superAdmins);
            stats.put("admins", admins);
            return ResponseEntity.ok(stats);
        } catch (Exception e) {
            return ResponseEntity.ok(createErrorResponse(e.getMessage()));
        }
    }

    /**
     * Create a test admin user with any username - for debugging
     */
    @PostMapping("/create-test-admin/{username}")
    public ResponseEntity<Map<String, Object>> createTestAdmin(@PathVariable String username) {
        try {
            if (appUserRepository.findByUsername(username).isPresent()) {
                return ResponseEntity.badRequest().body(createErrorResponse("User already exists: " + username));
            }

            AppUser admin = new AppUser();
            admin.setUsername(username);
            admin.setEmail(username + "@localhost");
            admin.setPasswordHash(passwordEncoder.encode("password"));
            admin.setEnabled(true);
            admin.setStatus(AppUser.UserStatus.ACTIVE);
            admin.setRoles(Set.of("ROLE_USER", "ROLE_ADMIN", "ROLE_SUPER_ADMIN"));
            AppUser savedUser = appUserRepository.save(admin);
            
            Map<String, Object> userInfo = new HashMap<>();
            userInfo.put("id", savedUser.getId());
            userInfo.put("username", savedUser.getUsername());
            userInfo.put("roles", savedUser.getRoles());
            userInfo.put("status", savedUser.getStatus().toString());
            userInfo.put("enabled", savedUser.isEnabled());
            
            Map<String, Object> response = new HashMap<>();
            response.put("message", "Test admin user created successfully");
            response.put("user", userInfo);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(createErrorResponse(e.getMessage()));
        }
    }
    @PostMapping("/seed-admin")
    public ResponseEntity<Map<String, Object>> seedAdmin() {
        try {
            String username = "admin";
            if (appUserRepository.findByUsername(username).isEmpty()) {
                AppUser admin = new AppUser();
                admin.setUsername(username);
                admin.setEmail("admin@localhost");
                admin.setPasswordHash(passwordEncoder.encode("password"));
                admin.setEnabled(true);
                admin.setStatus(AppUser.UserStatus.ACTIVE);
                admin.setRoles(Set.of("ROLE_USER", "ROLE_ADMIN", "ROLE_SUPER_ADMIN"));
                AppUser savedUser = appUserRepository.save(admin);
                
                Map<String, Object> userInfo = new HashMap<>();
                userInfo.put("id", savedUser.getId());
                userInfo.put("username", savedUser.getUsername());
                userInfo.put("roles", savedUser.getRoles());
                userInfo.put("status", savedUser.getStatus().toString());
                
                Map<String, Object> response = new HashMap<>();
                response.put("message", "Admin user created successfully");
                response.put("user", userInfo);
                return ResponseEntity.ok(response);
            } else {
                AppUser existingUser = appUserRepository.findByUsername(username).get();
                Map<String, Object> userInfo = new HashMap<>();
                userInfo.put("id", existingUser.getId());
                userInfo.put("username", existingUser.getUsername());
                userInfo.put("roles", existingUser.getRoles());
                userInfo.put("status", existingUser.getStatus().toString());
                
                Map<String, Object> response = new HashMap<>();
                response.put("message", "Admin user already exists");
                response.put("user", userInfo);
                return ResponseEntity.ok(response);
            }
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(createErrorResponse(e.getMessage()));
        }
    }

    /**
     * Debug endpoint to check authentication context
     */
    @GetMapping("/debug-auth")
    public ResponseEntity<Map<String, Object>> debugAuth() {
        try {
            org.springframework.security.core.Authentication auth = 
                org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
            
            Map<String, Object> authInfo = new HashMap<>();
            authInfo.put("authenticated", auth != null && auth.isAuthenticated());
            authInfo.put("principal", auth != null ? auth.getPrincipal().toString() : "null");
            authInfo.put("authorities", auth != null ? auth.getAuthorities().stream()
                .map(Object::toString)
                .collect(Collectors.toList()) : List.of());
            authInfo.put("name", auth != null ? auth.getName() : "null");
            authInfo.put("details", auth != null && auth.getDetails() != null ? auth.getDetails().toString() : "null");
            
            return ResponseEntity.ok(authInfo);
        } catch (Exception e) {
            return ResponseEntity.ok(createErrorResponse(e.getMessage()));
        }
    }

    /**
     * Test endpoint to verify user has admin access
     */
    @GetMapping("/test-admin-access")
    public ResponseEntity<Map<String, Object>> testAdminAccess() {
        try {
            org.springframework.security.core.Authentication auth = 
                org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
            
            if (auth == null || !auth.isAuthenticated()) {
                return ResponseEntity.status(401).body(createErrorResponse("Not authenticated"));
            }
            
            boolean hasRoleAdmin = auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
            boolean hasRoleSuperAdmin = auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_SUPER_ADMIN".equals(a.getAuthority()));
            
            Map<String, Object> response = new HashMap<>();
            response.put("message", "Admin access test");
            response.put("username", auth.getName());
            response.put("authorities", auth.getAuthorities().stream()
                .map(Object::toString)
                .collect(Collectors.toList()));
            response.put("hasRoleAdmin", hasRoleAdmin);
            response.put("hasRoleSuperAdmin", hasRoleSuperAdmin);
            response.put("hasAdminAccess", hasRoleAdmin || hasRoleSuperAdmin);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(createErrorResponse(e.getMessage()));
        }
    }

    /**
     * Force token refresh for current user to get updated roles
     */
    @PostMapping("/refresh-token")
    public ResponseEntity<Map<String, Object>> forceTokenRefresh() {
        try {
            org.springframework.security.core.Authentication auth = 
                org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
            
            if (auth == null || !auth.isAuthenticated()) {
                return ResponseEntity.status(401).body(createErrorResponse("Not authenticated"));
            }
            
            String username = auth.getName();
            
            // Get fresh roles from database
            java.util.List<String> freshRoles = appUserRepository.findByUsername(username)
                    .map(u -> new java.util.ArrayList<String>(u.getRoles()))
                    .orElse(new java.util.ArrayList<>());
            
            Map<String, Object> response = new HashMap<>();
            response.put("message", "Current user roles from database");
            response.put("username", username);
            response.put("currentAuthorities", auth.getAuthorities().stream()
                .map(Object::toString)
                .collect(Collectors.toList()));
            response.put("freshRolesFromDB", freshRoles);
            response.put("recommendation", "Please logout and login again to get fresh JWT token with updated roles");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(createErrorResponse(e.getMessage()));
        }
    }

    /**
     * Temporary debug endpoint - get users without strict auth (for debugging 401 issues)
     * This endpoint has relaxed security for troubleshooting
     */
    @GetMapping("/debug-list")
    public ResponseEntity<Map<String, Object>> debugGetUsers() {
        try {
            // Get current authentication context
            org.springframework.security.core.Authentication auth = 
                org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
            
            List<AppUser> users = appUserRepository.findAll();
            List<Map<String, Object>> userList = users.stream()
                .map(user -> {
                    Map<String, Object> userMap = new HashMap<>();
                    userMap.put("id", user.getId());
                    userMap.put("username", user.getUsername());
                    userMap.put("roles", user.getRoles());
                    userMap.put("status", user.getStatus().toString());
                    userMap.put("enabled", user.isEnabled());
                    return userMap;
                })
                .collect(Collectors.toList());
            
            Map<String, Object> response = new HashMap<>();
            response.put("message", "Debug user list");
            if (auth != null) {
                Map<String, Object> authInfo = new HashMap<>();
                authInfo.put("username", auth.getName());
                authInfo.put("authorities", auth.getAuthorities().stream().map(Object::toString).collect(Collectors.toList()));
                authInfo.put("authenticated", auth.isAuthenticated());
                response.put("currentAuth", authInfo);
            } else {
                response.put("currentAuth", "No authentication");
            }
            response.put("users", userList);
            response.put("totalUsers", users.size());
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.ok(createErrorResponse(e.getMessage()));
        }
    }

    /**
     * Enable/Disable user
     */
    @PostMapping("/{id}/toggle-enabled")
    public ResponseEntity<Map<String, Object>> toggleUserEnabled(@PathVariable Long id) {
        try {
            Optional<AppUser> userOpt = appUserRepository.findById(id);
            if (userOpt.isEmpty()) {
                return ResponseEntity.notFound().build();
            }

            AppUser user = userOpt.get();
            user.setEnabled(!user.isEnabled()); // Toggle the enabled state
            AppUser savedUser = appUserRepository.save(user);

            Map<String, Object> result = new HashMap<>();
            result.put("id", savedUser.getId());
            result.put("username", savedUser.getUsername());
            result.put("enabled", savedUser.isEnabled());
            result.put("status", savedUser.getStatus().toString());
            result.put("message", savedUser.isEnabled() ? "User enabled" : "User disabled");
            return ResponseEntity.ok(result);

        } catch (Exception e) {
            return ResponseEntity.badRequest().body(createErrorResponse(e.getMessage()));
        }
    }

    /**
     * Fix test123 user - enable them
     */
    @PostMapping("/fix-test123")
    public ResponseEntity<Map<String, Object>> fixTest123User() {
        try {
            Optional<AppUser> userOpt = appUserRepository.findByUsername("test123");
            if (userOpt.isEmpty()) {
                return ResponseEntity.status(404).body(createErrorResponse("test123 user not found"));
            }

            AppUser user = userOpt.get();
            user.setEnabled(true); // Enable the user
            user.setStatus(AppUser.UserStatus.ACTIVE); // Ensure status is ACTIVE
            AppUser savedUser = appUserRepository.save(user);

            Map<String, Object> result = new HashMap<>();
            result.put("id", savedUser.getId());
            result.put("username", savedUser.getUsername());
            result.put("enabled", savedUser.isEnabled());
            result.put("status", savedUser.getStatus().toString());
            result.put("roles", savedUser.getRoles());
            result.put("message", "test123 user has been enabled and set to ACTIVE status");
            return ResponseEntity.ok(result);

        } catch (Exception e) {
            return ResponseEntity.badRequest().body(createErrorResponse(e.getMessage()));
        }
    }

    /**
     * Debug endpoint to check specific user roles
     */
    @GetMapping("/debug-user/{username}")
    public ResponseEntity<Map<String, Object>> debugUser(@PathVariable String username) {
        try {
            Optional<AppUser> userOpt = appUserRepository.findByUsername(username);
            if (userOpt.isEmpty()) {
                return ResponseEntity.status(404).body(createErrorResponse("User not found: " + username));
            }

            AppUser user = userOpt.get();
            Map<String, Object> result = new HashMap<>();
            result.put("id", user.getId());
            result.put("username", user.getUsername());
            result.put("email", user.getEmail() != null ? user.getEmail() : "");
            result.put("enabled", user.isEnabled());
            result.put("status", user.getStatus().toString());
            result.put("roles", user.getRoles());
            result.put("createdAt", user.getCreatedAt() != null ? user.getCreatedAt().toString() : "");
            result.put("hasRoleUser", user.hasRole("USER"));
            result.put("hasRoleAdmin", user.hasRole("ADMIN"));
            result.put("hasRoleSuperAdmin", user.hasRole("SUPER_ADMIN"));
            result.put("isAdmin", user.isAdmin());
            result.put("isSuperAdmin", user.isSuperAdmin());
            return ResponseEntity.ok(result);

        } catch (Exception e) {
            return ResponseEntity.badRequest().body(createErrorResponse(e.getMessage()));
        }
    }
    @GetMapping("/check-auth-source")
    public ResponseEntity<Map<String, Object>> checkAuthSource() {
        try {
            org.springframework.security.core.Authentication auth = 
                org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
            
            if (auth == null) {
                return ResponseEntity.ok(createErrorResponse("No authentication found"));
            }

            String username = auth.getName();
            boolean inDatabase = appUserRepository.findByUsername(username).isPresent();
            
            Map<String, Object> result = new HashMap<>();
            result.put("username", username);
            result.put("authorities", auth.getAuthorities().stream().map(Object::toString).collect(Collectors.toList()));
            result.put("inDatabase", inDatabase);
            result.put("authType", auth.getClass().getSimpleName());
            result.put("principal", auth.getPrincipal().getClass().getSimpleName());
            result.put("message", inDatabase ? "Using database authentication" : "Using in-memory authentication");
            
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.ok(createErrorResponse(e.getMessage()));
        }
    }

    /**
     * Normalize all user roles to use ROLE_ prefix consistently
     */
    @PostMapping("/normalize-roles")
    public ResponseEntity<Map<String, Object>> normalizeAllUserRoles() {
        try {
            List<AppUser> allUsers = appUserRepository.findAll();
            int updatedCount = 0;
            
            for (AppUser user : allUsers) {
                Set<String> currentRoles = user.getRoles();
                Set<String> normalizedRoles = new HashSet<>();
                boolean needsUpdate = false;
                
                for (String role : currentRoles) {
                    String prefixedRole = role.startsWith("ROLE_") ? role : "ROLE_" + role;
                    normalizedRoles.add(prefixedRole);
                    if (!role.equals(prefixedRole)) {
                        needsUpdate = true;
                    }
                }
                
                if (needsUpdate) {
                    user.setRoles(normalizedRoles);
                    appUserRepository.save(user);
                    updatedCount++;
                }
            }
            
            Map<String, Object> response = new HashMap<>();
            response.put("message", "Role normalization completed - all roles now have ROLE_ prefix");
            response.put("totalUsers", allUsers.size());
            response.put("updatedUsers", updatedCount);
            response.put("timestamp", Instant.now());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(createErrorResponse(e.getMessage()));
        }
    }

    /**
     * Fix missing last login times by setting them to creation time for users who have never logged in
     */
    @PostMapping("/fix-last-login-times")
    public ResponseEntity<Map<String, Object>> fixLastLoginTimes() {
        try {
            List<AppUser> allUsers = appUserRepository.findAll();
            int updatedCount = 0;
            
            for (AppUser user : allUsers) {
                if (user.getLastLoginAt() == null && user.getCreatedAt() != null) {
                    // Set last login to creation time for users who have never logged in
                    user.setLastLoginAt(user.getCreatedAt());
                    appUserRepository.save(user);
                    updatedCount++;
                }
            }
            
            Map<String, Object> response = new HashMap<>();
            response.put("message", "Last login times fixed");
            response.put("totalUsers", allUsers.size());
            response.put("updatedUsers", updatedCount);
            response.put("timestamp", Instant.now());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(createErrorResponse(e.getMessage()));
        }
    }

    /**
     * Comprehensive fix for all user data issues
     */
    @PostMapping("/fix-all-user-issues")
    public ResponseEntity<Map<String, Object>> fixAllUserIssues() {
        try {
            List<AppUser> allUsers = appUserRepository.findAll();
            int rolesUpdated = 0;
            int lastLoginUpdated = 0;
            
            for (AppUser user : allUsers) {
                boolean needsSave = false;
                
                // Fix roles - ensure ROLE_ prefix
                Set<String> currentRoles = user.getRoles();
                Set<String> normalizedRoles = new HashSet<>();
                boolean rolesNeedUpdate = false;
                
                for (String role : currentRoles) {
                    String prefixedRole = role.startsWith("ROLE_") ? role : "ROLE_" + role;
                    normalizedRoles.add(prefixedRole);
                    if (!role.equals(prefixedRole)) {
                        rolesNeedUpdate = true;
                    }
                }
                
                if (rolesNeedUpdate) {
                    user.setRoles(normalizedRoles);
                    rolesUpdated++;
                    needsSave = true;
                }
                
                // Fix last login times
                if (user.getLastLoginAt() == null && user.getCreatedAt() != null) {
                    user.setLastLoginAt(user.getCreatedAt());
                    lastLoginUpdated++;
                    needsSave = true;
                }
                
                if (needsSave) {
                    appUserRepository.save(user);
                }
            }
            
            Map<String, Object> response = new HashMap<>();
            response.put("message", "All user issues fixed");
            response.put("totalUsers", allUsers.size());
            response.put("rolesUpdated", rolesUpdated);
            response.put("lastLoginUpdated", lastLoginUpdated);
            response.put("timestamp", Instant.now());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(createErrorResponse(e.getMessage()));
        }
    }

    /**
     * Test filtering functionality
     */
    @GetMapping("/test-filters")
    public ResponseEntity<Map<String, Object>> testFilters(
            @RequestParam(value = "search", required = false) String search,
            @RequestParam(value = "role", required = false) String role,
            @RequestParam(value = "status", required = false) String status) {
        try {
            // Parse status parameter
            AppUser.UserStatus userStatus = null;
            if (status != null && !status.trim().isEmpty() && !"All Statuses".equals(status)) {
                try {
                    userStatus = AppUser.UserStatus.valueOf(status.toUpperCase());
                } catch (IllegalArgumentException e) {
                    // Invalid status, ignore
                }
            }
            
            // Parse role parameter
            String roleFilter = null;
            if (role != null && !role.trim().isEmpty() && !"All Roles".equals(role)) {
                roleFilter = role.trim();
            }
            
            // Parse search parameter
            String searchFilter = null;
            if (search != null && !search.trim().isEmpty()) {
                searchFilter = search.trim();
            }
            
            // Test the filtering without pagination
            Pageable pageable = PageRequest.of(0, 100);
            Page<AppUser> userPage = appUserRepository.findWithFilters(searchFilter, roleFilter, userStatus != null ? userStatus.name() : null, pageable);
            
            List<Map<String, Object>> userList = userPage.getContent().stream()
                .map(user -> {
                    Map<String, Object> userMap = new HashMap<>();
                    userMap.put("id", user.getId());
                    userMap.put("username", user.getUsername());
                    userMap.put("roles", user.getRoles());
                    userMap.put("status", user.getStatus().toString());
                    return userMap;
                })
                .collect(Collectors.toList());
            
            Map<String, Object> filters = new HashMap<>();
            filters.put("search", searchFilter != null ? searchFilter : "null");
            filters.put("role", roleFilter != null ? roleFilter : "null");
            filters.put("status", userStatus != null ? userStatus.toString() : "null");
            
            Map<String, Object> response = new HashMap<>();
            response.put("message", "Filter test results");
            response.put("filters", filters);
            response.put("totalResults", userPage.getTotalElements());
            response.put("users", userList);
            response.put("timestamp", Instant.now());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(createErrorResponse(e.getMessage()));
        }
    }
}
