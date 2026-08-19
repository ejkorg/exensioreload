package com.onsemi.cim.apps.exensio.exensioreload.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.onsemi.cim.apps.exensio.exensioreload.entity.AppUser;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {
    Optional<AppUser> findByUsername(String username);
    Optional<AppUser> findByEmail(String email);
    List<AppUser> findByEmailIgnoreCase(String email);
    
    // Enhanced queries for Super Admin functionality
    
    // Find users by status
    List<AppUser> findByStatus(AppUser.UserStatus status);
    
    // Find users by role
    @Query("SELECT u FROM AppUser u JOIN u.roles r WHERE r = :role")
    List<AppUser> findByRole(@Param("role") String role);
    
    // Find Super Admins
    @Query("SELECT u FROM AppUser u JOIN u.roles r WHERE r = 'SUPER_ADMIN'")
    List<AppUser> findSuperAdmins();
    
    // Search users with pagination and filtering
    @Query(value = "SELECT u.* FROM users u WHERE " +
           "(:search IS NULL OR LOWER(u.username) LIKE LOWER(CONCAT('%', CAST(:search AS VARCHAR), '%')) OR (u.email IS NOT NULL AND LOWER(u.email) LIKE LOWER(CONCAT('%', CAST(:search AS VARCHAR), '%')))) AND " +
           "(:role IS NULL OR EXISTS (SELECT 1 FROM user_roles ur WHERE ur.user_id = u.id AND ur.role = CAST(:role AS VARCHAR))) AND " +
           "(:status IS NULL OR u.status = CAST(:status AS VARCHAR))",
           countQuery = "SELECT COUNT(u.id) FROM users u WHERE " +
           "(:search IS NULL OR LOWER(u.username) LIKE LOWER(CONCAT('%', CAST(:search AS VARCHAR), '%')) OR (u.email IS NOT NULL AND LOWER(u.email) LIKE LOWER(CONCAT('%', CAST(:search AS VARCHAR), '%')))) AND " +
           "(:role IS NULL OR EXISTS (SELECT 1 FROM user_roles ur WHERE ur.user_id = u.id AND ur.role = CAST(:role AS VARCHAR))) AND " +
           "(:status IS NULL OR u.status = CAST(:status AS VARCHAR))",
           nativeQuery = true)
    Page<AppUser> findWithFilters(@Param("search") String search,
                                 @Param("role") String role,
                                 @Param("status") String status,
                                 Pageable pageable);
    
    // Find users created by a specific user
    List<AppUser> findByCreatedBy(Long createdBy);
    
    // Find users updated by a specific user
    List<AppUser> findByUpdatedBy(Long updatedBy);
    
    // Find users who haven't logged in since a certain date
    List<AppUser> findByLastLoginAtBeforeOrLastLoginAtIsNull(Instant cutoffDate);
    
    // Count users by status
    long countByStatus(AppUser.UserStatus status);
    
    // Count users by role
    @Query("SELECT COUNT(u) FROM AppUser u JOIN u.roles r WHERE r = :role")
    long countByRole(@Param("role") String role);
    
    // Find recently created users
    List<AppUser> findByCreatedAtAfterOrderByCreatedAtDesc(Instant since);
    
    // Check if username exists (case-insensitive)
    @Query("SELECT COUNT(u) > 0 FROM AppUser u WHERE LOWER(u.username) = LOWER(:username)")
    boolean existsByUsernameIgnoreCase(@Param("username") String username);
    
    // Check if email exists (case-insensitive)
    @Query("SELECT COUNT(u) > 0 FROM AppUser u WHERE LOWER(u.email) = LOWER(:email)")
    boolean existsByEmailIgnoreCase(@Param("email") String email);
    
    // Additional methods needed by UserManagementService
    @Query("SELECT COUNT(u) > 0 FROM AppUser u WHERE LOWER(u.username) = LOWER(:username) AND u.id != :id")
    boolean existsByUsernameIgnoreCaseAndIdNot(@Param("username") String username, @Param("id") Long id);
    
    @Query("SELECT COUNT(u) > 0 FROM AppUser u WHERE LOWER(u.email) = LOWER(:email) AND u.id != :id")
    boolean existsByEmailIgnoreCaseAndIdNot(@Param("email") String email, @Param("id") Long id);
}
