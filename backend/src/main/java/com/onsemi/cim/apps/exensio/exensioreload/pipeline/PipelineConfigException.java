package com.onsemi.cim.apps.exensio.exensioreload.pipeline;

/**
 * Exception thrown when pipeline configuration is invalid.
 * Used for configuration parsing errors, validation failures, and circular dependencies.
 */
public class PipelineConfigException extends RuntimeException {

    public PipelineConfigException(String message) {
        super(message);
    }

    public PipelineConfigException(String message, Throwable cause) {
        super(message, cause);
    }

    public PipelineConfigException(Throwable cause) {
        super(cause);
    }
}
