package com.onsemi.cim.apps.exensio.exensioreload.repository;

import com.onsemi.cim.apps.exensio.exensioreload.entity.Alert;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface AlertRepository extends JpaRepository<Alert, Long> {

    Optional<Alert> findByAlertId(String alertId);

    List<Alert> findByStatusOrderByTriggeredAtDesc(String status);

    Page<Alert> findByStatusInOrderByTriggeredAtDesc(List<String> statuses, Pageable pageable);

    @Query("SELECT a FROM Alert a WHERE " +
           "(:status IS NULL OR a.status = :status) AND " +
           "(:severity IS NULL OR a.severity = :severity) AND " +
           "(:site IS NULL OR LOWER(a.site) LIKE LOWER(CONCAT('%', :site, '%'))) AND " +
           "(:senderId IS NULL OR a.senderId = :senderId) AND " +
           "(:since IS NULL OR a.triggeredAt >= :since)")
    Page<Alert> findWithFilters(
            @Param("status") String status,
            @Param("severity") String severity,
            @Param("site") String site,
            @Param("senderId") Integer senderId,
            @Param("since") Instant since,
            Pageable pageable);

    long countByStatus(String status);

    long countByStatusAndSeverity(String status, String severity);

    boolean existsBySenderIdAndAlertTypeAndStatus(Integer senderId, String alertType, String status);
}
