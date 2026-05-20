package com.onsemi.cim.apps.exensio.exensioreload.repository;

import com.onsemi.cim.apps.exensio.exensioreload.entity.UserSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface UserSessionRepository extends JpaRepository<UserSession, Long> {
    
    // Find session by token
    Optional<UserSession> findBySessionToken(String sessionToken);
    
    // Find active sessions for a user
    List<UserSession> findByUserIdAndIsActiveTrueOrderByLastAccessedAtDesc(Long userId);
    
    // Find all sessions for a user (active and inactive)
    List<UserSession> findByUserIdOrderByLastAccessedAtDesc(Long userId);
    
    // Find expired sessions
    List<UserSession> findByExpiresAtBeforeAndIsActiveTrue(Instant now);
    
    // Count active sessions for a user
    long countByUserIdAndIsActiveTrue(Long userId);
    
    // Deactivate all sessions for a user
    @Modifying
    @Query("UPDATE UserSession s SET s.isActive = false WHERE s.userId = :userId")
    void deactivateAllSessionsForUser(@Param("userId") Long userId);
    
    // Deactivate a specific session
    @Modifying
    @Query("UPDATE UserSession s SET s.isActive = false WHERE s.sessionToken = :token")
    void deactivateSession(@Param("token") String token);
    
    // Clean up expired sessions
    @Modifying
    @Query("UPDATE UserSession s SET s.isActive = false WHERE s.expiresAt < :now AND s.isActive = true")
    int deactivateExpiredSessions(@Param("now") Instant now);
    
    // Delete old inactive sessions
    void deleteByIsActiveFalseAndLastAccessedAtBefore(Instant cutoffDate);
    
    // Find sessions by IP address (for security monitoring)
    List<UserSession> findByIpAddressAndIsActiveTrueOrderByLastAccessedAtDesc(String ipAddress);
    
    // Update last accessed time
    @Modifying
    @Query("UPDATE UserSession s SET s.lastAccessedAt = :now WHERE s.sessionToken = :token")
    void updateLastAccessedTime(@Param("token") String token, @Param("now") Instant now);
}
