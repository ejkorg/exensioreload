package com.onsemi.cim.apps.exensio.resender.repository;

import com.onsemi.cim.apps.exensio.resender.entity.AppUser;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {
    Optional<AppUser> findByUsername(String username);
    Optional<AppUser> findByEmail(String email);
    
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
    @Query("SELECT u FROM AppUser u WHERE " +
           "(:search IS NULL OR LOWER(u.username) LIKE LOWER(CONCAT('%', :search, '%')) OR LOWER(u.email) LIKE LOWER(CONCAT('%', :search, '%'))) AND " +
           "(:role IS NULL OR :role MEMBER OF u.roles) AND " +
           "(:status IS NULL OR u.status = :status)")
    Page<AppUser> findWithFilters(@Param("search") String search,
                                 @Param("role") String role,
                                 @Param("status") AppUser.UserStatus status,
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
