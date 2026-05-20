package com.onsemi.cim.apps.exensio.resender.repository;

import com.onsemi.cim.apps.exensio.resender.entity.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {
    
    // Find audit logs by user ID
    Page<AuditLog> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);
    
    // Find audit logs by action
    Page<AuditLog> findByActionOrderByCreatedAtDesc(String action, Pageable pageable);
    
    // Find audit logs by resource type
    Page<AuditLog> findByResourceTypeOrderByCreatedAtDesc(String resourceType, Pageable pageable);
    
    // Find audit logs by date range
    Page<AuditLog> findByCreatedAtBetweenOrderByCreatedAtDesc(Instant startDate, Instant endDate, Pageable pageable);
    
    // Find audit logs by user and action
    Page<AuditLog> findByUserIdAndActionOrderByCreatedAtDesc(Long userId, String action, Pageable pageable);
    
    // Find audit logs by resource type and resource ID
    Page<AuditLog> findByResourceTypeAndResourceIdOrderByCreatedAtDesc(String resourceType, String resourceId, Pageable pageable);
    
    // Complex search query with multiple filters
    @Query("SELECT a FROM AuditLog a WHERE " +
           "(:userId IS NULL OR a.userId = :userId) AND " +
           "(:action IS NULL OR a.action = :action) AND " +
           "(:resourceType IS NULL OR a.resourceType = :resourceType) AND " +
           "(:startDate IS NULL OR a.createdAt >= :startDate) AND " +
           "(:endDate IS NULL OR a.createdAt <= :endDate) " +
           "ORDER BY a.createdAt DESC")
    Page<AuditLog> findWithFilters(@Param("userId") Long userId,
                                  @Param("action") String action,
                                  @Param("resourceType") String resourceType,
                                  @Param("startDate") Instant startDate,
                                  @Param("endDate") Instant endDate,
                                  Pageable pageable);
    
    // Get recent audit logs for a user
    List<AuditLog> findTop10ByUserIdOrderByCreatedAtDesc(Long userId);
    
    // Count audit logs by action in a time period
    @Query("SELECT COUNT(a) FROM AuditLog a WHERE a.action = :action AND a.createdAt >= :since")
    long countByActionSince(@Param("action") String action, @Param("since") Instant since);
    
    // Get audit log statistics
    @Query("SELECT a.action, COUNT(a) FROM AuditLog a WHERE a.createdAt >= :since GROUP BY a.action")
    List<Object[]> getActionStatisticsSince(@Param("since") Instant since);
    
    // Clean up old audit logs (for maintenance)
    void deleteByCreatedAtBefore(Instant cutoffDate);
}
