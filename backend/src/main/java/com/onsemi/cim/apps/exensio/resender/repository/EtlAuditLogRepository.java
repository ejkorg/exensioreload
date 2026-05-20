package com.onsemi.cim.apps.exensio.resender.repository;

import com.onsemi.cim.apps.exensio.resender.entity.EtlAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EtlAuditLogRepository extends JpaRepository<EtlAuditLog, Long> {

    List<EtlAuditLog> findAllByOrderByTimestampDesc();
}
