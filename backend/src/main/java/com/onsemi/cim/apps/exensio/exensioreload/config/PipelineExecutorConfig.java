package com.onsemi.cim.apps.exensio.exensioreload.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration for pipeline stage processing thread pool.
 *
 * Provides an ExecutorService bean configured with the thread pool size from application.yml.
 * This pool is used for parallel stage processing within site batches to improve throughput.
 *
 * Requirements: 12.4
 */
@Configuration
public class PipelineExecutorConfig {

    private static final Logger log = LoggerFactory.getLogger(PipelineExecutorConfig.class);

    @Value("${pipeline.thread-pool-size:5}")
    private int threadPoolSize;

    /**
     * Create and configure the thread pool for pipeline stage processing.
     *
     * @return ExecutorService with configured pool size
     */
    @Bean(name = "pipelineExecutor", destroyMethod = "shutdown")
    public ExecutorService pipelineExecutor() {
        log.info("Creating pipeline executor thread pool with size={}", threadPoolSize);
        return Executors.newFixedThreadPool(threadPoolSize, r -> {
            Thread t = new Thread(r, "pipeline-executor-" + Thread.currentThread().getId());
            t.setDaemon(false);
            return t;
        });
    }
}
