package com.onsemi.cim.apps.exensio.exensioreload.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Circuit breaker implementation to prevent cascading failures when Exensio API is unavailable.
 *
 * State machine:
 * - CLOSED: Normal operation, requests pass through
 * - OPEN: Circuit is tripped, requests are rejected
 * - HALF_OPEN: Testing if service has recovered
 *
 * Requirements: 4.4, 4.5, 4.6
 */
public class CircuitBreaker {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreaker.class);

    /** Circuit breaker states */
    public enum State {
        CLOSED, OPEN, HALF_OPEN
    }

    private final int failureThreshold;
    private final Duration resetTimeout;
    private final AtomicLong lastFailureTime;
    private final AtomicInteger consecutiveFailures;
    private final Object stateLock = new Object();

    private volatile State state = State.CLOSED;
    private volatile Instant halfOpenStartTime;

    /**
     * Create a new circuit breaker.
     *
     * @param failureThreshold Number of consecutive failures before opening the circuit
     * @param resetTimeoutMs Time in milliseconds to wait before attempting to close the circuit
     */
    public CircuitBreaker(int failureThreshold, long resetTimeoutMs) {
        this.failureThreshold = failureThreshold;
        this.resetTimeout = Duration.ofMillis(resetTimeoutMs);
        this.lastFailureTime = new AtomicLong(0);
        this.consecutiveFailures = new AtomicInteger(0);
        this.halfOpenStartTime = null;
        log.debug("CircuitBreaker initialized: threshold={}, resetTimeout={}",
                failureThreshold, resetTimeoutMs);
    }

    /**
     * Check if the circuit breaker allows the request to proceed.
     *
     * @return true if the request should proceed, false if it should be rejected
     */
    public boolean allowRequest() {
        synchronized (stateLock) {
            switch (state) {
                case CLOSED:
                    return true;

                case OPEN:
                    // Check if reset timeout has elapsed
                    long lastFailure = lastFailureTime.get();
                    if (lastFailure > 0) {
                        Instant lastFailureInstant = Instant.ofEpochMilli(lastFailure);
                        if (lastFailureInstant.plus(resetTimeout).isBefore(Instant.now())) {
                            // Transition to HALF_OPEN
                            state = State.HALF_OPEN;
                            halfOpenStartTime = Instant.now();
                            log.info("Circuit breaker transitioning from OPEN to HALF_OPEN after {} ms timeout",
                                    resetTimeout.toMillis());
                            return true;
                        }
                    }
                    return false;

                case HALF_OPEN:
                    // Allow one request through to test if service recovered
                    return true;

                default:
                    return false;
            }
        }
    }

    /**
     * Record a successful request.
     */
    public void recordSuccess() {
        synchronized (stateLock) {
            switch (state) {
                case CLOSED:
                    // Reset consecutive failures on success
                    consecutiveFailures.set(0);
                    break;

                case OPEN:
                    // Should not happen, but if it does, reset
                    consecutiveFailures.set(0);
                    break;

                case HALF_OPEN:
                    // Success in HALF_OPEN state → close the circuit
                    state = State.CLOSED;
                    long elapsed = Duration.between(halfOpenStartTime, Instant.now()).toMillis();
                    log.info("Circuit breaker transitioning from HALF_OPEN to CLOSED after {} ms (success)",
                            elapsed);
                    consecutiveFailures.set(0);
                    break;
            }
        }
    }

    /**
     * Record a failed request.
     */
    public void recordFailure() {
        long now = System.currentTimeMillis();
        lastFailureTime.set(now);

        synchronized (stateLock) {
            int failures = consecutiveFailures.incrementAndGet();

            switch (state) {
                case CLOSED:
                    if (failures >= failureThreshold) {
                        state = State.OPEN;
                        log.warn("Circuit breaker transitioning from CLOSED to OPEN after {} consecutive failures (threshold: {})",
                                failures, failureThreshold);
                    }
                    break;

                case OPEN:
                    // Already open, just update failure count
                    log.debug("Circuit breaker is OPEN, failure count: {}/{}", failures, failureThreshold);
                    break;

                case HALF_OPEN:
                    // Failure in HALF_OPEN state → open the circuit
                    state = State.OPEN;
                    log.warn("Circuit breaker transitioning from HALF_OPEN to OPEN after failure during recovery test");
                    consecutiveFailures.set(failures);
                    break;
            }
        }
    }

    /**
     * Get the current state of the circuit breaker.
     *
     * @return The current state (CLOSED, OPEN, or HALF_OPEN)
     */
    public State getState() {
        return state;
    }

    /**
     * Get the number of consecutive failures.
     *
     * @return The current failure count
     */
    public int getConsecutiveFailures() {
        return consecutiveFailures.get();
    }

    /**
     * Get the failure threshold.
     *
     * @return The threshold value
     */
    public int getFailureThreshold() {
        return failureThreshold;
    }

    /**
     * Get the reset timeout in milliseconds.
     *
     * @return The reset timeout
     */
    public long getResetTimeoutMs() {
        return resetTimeout.toMillis();
    }

    /**
     * Get the time since the last failure in milliseconds.
     *
     * @return Time since last failure, or -1 if no failure recorded
     */
    public long getTimeSinceLastFailureMs() {
        long lastFailure = lastFailureTime.get();
        if (lastFailure == 0) {
            return -1;
        }
        return Duration.between(Instant.ofEpochMilli(lastFailure), Instant.now()).toMillis();
    }

    /**
     * Reset the circuit breaker to CLOSED state (for manual recovery).
     */
    public void reset() {
        synchronized (stateLock) {
            state = State.CLOSED;
            consecutiveFailures.set(0);
            log.info("Circuit breaker manually reset to CLOSED state");
        }
    }
}
