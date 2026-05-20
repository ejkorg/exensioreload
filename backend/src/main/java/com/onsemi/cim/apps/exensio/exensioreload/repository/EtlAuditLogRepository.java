package com.onsemi.cim.apps.exensio.exensioreload.repository;

import com.onsemi.cim.apps.exensio.exensioreload.entity.EtlAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EtlAuditLogRepository extends JpaRepository<EtlAuditLog, Long> {

    List<EtlAuditLog> findAllByOrderByTimestampDesc();
}
