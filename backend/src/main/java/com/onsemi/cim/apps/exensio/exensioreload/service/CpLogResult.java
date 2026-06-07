package com.onsemi.cim.apps.exensio.exensioreload.service;

import java.time.Instant;

/**
 * Discriminated union representing the outcome of a CP Elasticsearch log lookup.
 *
 * <p>Requirements: 3.1, 4.1</p>
 */
public sealed interface CpLogResult {
    String traceId();

    /**
     * CP successfully enriched the file — an "output path" log entry was found with no errors.
     *
     * @param traceId       the correlation ID for this request
     * @param outputPath   the extracted output folder path from the CP log message
     * @param outputTarget "PRODUCTION", "SANDBOX", or "UNKNOWN"
     * @param logTimestamp timestamp of the matching ES log entry
     */
    record Success(String traceId, String outputPath, String outputTarget, Instant logTimestamp) implements CpLogResult {}

    /**
     * CP reported an error — a log entry with {@code error.type} or {@code error.message} was found.
     *
     * @param traceId       the correlation ID for this request
     * @param errorMessage the error detail (truncated to 500 chars before storage)
     * @param logTimestamp timestamp of the matching ES log entry
     */
    record Failure(String traceId, String errorMessage, Instant logTimestamp) implements CpLogResult {}

    /**
     * No matching ES log entry was found yet — enrichment may still be in progress.
     *
     * @param traceId the correlation ID for this request
     */
    record NotFound(String traceId) implements CpLogResult {}
}
