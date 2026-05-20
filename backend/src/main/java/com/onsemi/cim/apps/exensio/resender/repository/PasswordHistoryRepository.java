package com.onsemi.cim.apps.exensio.resender.repository;

import com.onsemi.cim.apps.exensio.resender.entity.PasswordHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface PasswordHistoryRepository extends JpaRepository<PasswordHistory, Long> {
    
    // Find password history for a user, ordered by most recent first
    List<PasswordHistory> findByUserIdOrderByCreatedAtDesc(Long userId);
    
    // Find the last N passwords for a user
    @Query("SELECT ph FROM PasswordHistory ph WHERE ph.userId = :userId ORDER BY ph.createdAt DESC")
    List<PasswordHistory> findLastNPasswordsForUser(@Param("userId") Long userId);
    
    // Check if a password hash exists in user's history
    boolean existsByUserIdAndPasswordHash(Long userId, String passwordHash);
    
    // Get the last N password hashes for a user (for password reuse checking)
    @Query(value = "SELECT password_hash FROM password_history WHERE user_id = :userId ORDER BY created_at DESC LIMIT :limit", 
           nativeQuery = true)
    List<String> findLastNPasswordHashesForUser(@Param("userId") Long userId, @Param("limit") int limit);
    
    // Clean up old password history entries (keep only last N entries per user)
    @Query(value = "DELETE FROM password_history WHERE user_id = :userId AND id NOT IN " +
           "(SELECT * FROM (SELECT id FROM password_history WHERE user_id = :userId ORDER BY created_at DESC LIMIT :keepCount) AS temp)", 
           nativeQuery = true)
    void cleanupOldPasswordsForUser(@Param("userId") Long userId, @Param("keepCount") int keepCount);
}
