package com.onsemi.cim.apps.exensio.exensioreload.repository;

import com.onsemi.cim.apps.exensio.exensioreload.entity.ConfigDbConnection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ConfigDbConnectionRepository extends JpaRepository<ConfigDbConnection, Long> {
    
    /**
     * Find a database connection by its connection key
     * @param connectionKey the unique connection key
     * @return Optional containing the connection if found
     */
    Optional<ConfigDbConnection> findByConnectionKey(String connectionKey);
    
    /**
     * Find all database connections by environment
     * @param environment the environment (e.g., PROD, QA)
     * @return list of connections for the given environment
     */
    List<ConfigDbConnection> findByEnvironment(String environment);
    
    /**
     * Check if a database connection with the given key exists
     * @param connectionKey the connection key
     * @return true if exists, false otherwise
     */
    boolean existsByConnectionKey(String connectionKey);
    
}
