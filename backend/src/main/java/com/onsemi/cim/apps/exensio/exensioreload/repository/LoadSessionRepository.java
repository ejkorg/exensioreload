package com.onsemi.cim.apps.exensio.exensioreload.repository;

import com.onsemi.cim.apps.exensio.exensioreload.entity.LoadSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface LoadSessionRepository extends JpaRepository<LoadSession, Long> {
	java.util.Optional<LoadSession> findTopByOrderByIdDesc();
}
