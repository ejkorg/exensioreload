package com.onsemi.cim.apps.exensio.exensioreload.repository;

import com.onsemi.cim.apps.exensio.exensioreload.entity.ConfigEtlServer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ConfigEtlServerRepository extends JpaRepository<ConfigEtlServer, Long> {
    
    /**
     * Find an ETL server by its server key
     * @param serverKey the unique server key
     * @return Optional containing the server if found
     */
    Optional<ConfigEtlServer> findByServerKey(String serverKey);
    
    /**
     * Find all ETL servers by environment
     * @param environment the environment (e.g., PROD, QA)
     * @return list of servers for the given environment
     */
    List<ConfigEtlServer> findByEnvironment(String environment);
    
    /**
     * Find all historical ETL servers in a given environment
     * @param environment the environment
     * @param isHistoricalSender whether the server is a historical sender
     * @return list of historical servers for the given environment
     */
    List<ConfigEtlServer> findByEnvironmentAndIsHistoricalSenderTrue(String environment);
    
    /**
     * Check if an ETL server with the given key exists
     * @param serverKey the server key
     * @return true if exists, false otherwise
     */
    boolean existsByServerKey(String serverKey);
    
}
