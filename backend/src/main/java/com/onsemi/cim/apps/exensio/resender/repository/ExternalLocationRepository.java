package com.onsemi.cim.apps.exensio.resender.repository;

import com.onsemi.cim.apps.exensio.resender.entity.ExternalLocation;
import com.onsemi.cim.apps.exensio.resender.entity.ExternalEnvironment;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ExternalLocationRepository extends JpaRepository<ExternalLocation, Long> {
    List<ExternalLocation> findByEnvironment(ExternalEnvironment environment);
}
