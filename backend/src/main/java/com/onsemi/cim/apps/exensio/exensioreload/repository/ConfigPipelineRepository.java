package com.onsemi.cim.apps.exensio.exensioreload.repository;

import com.onsemi.cim.apps.exensio.exensioreload.entity.ConfigPipeline;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ConfigPipelineRepository extends JpaRepository<ConfigPipeline, Long> {
    
    /**
     * Find a pipeline by its pipeline key
     * @param pipelineKey the unique pipeline key
     * @return Optional containing the pipeline if found
     */
    Optional<ConfigPipeline> findByPipelineKey(String pipelineKey);
    
    /**
     * Find all pipelines by environment
     * @param environment the environment (e.g., PROD, QA)
     * @return list of pipelines for the given environment
     */
    List<ConfigPipeline> findByEnvironment(String environment);
    
    /**
     * Find pipelines by site, environment, and historical mode
     * @param site the site name
     * @param environment the environment
     * @param historicalModeEnabled whether to filter for historical mode enabled
     * @return list of matching pipelines
     */
    List<ConfigPipeline> findBySiteAndEnvironmentAndHistoricalModeEnabled(String site, String environment, Boolean historicalModeEnabled);
    
    /**
     * Check if a pipeline with the given key exists
     * @param pipelineKey the pipeline key
     * @return true if exists, false otherwise
     */
    boolean existsByPipelineKey(String pipelineKey);
    
}
