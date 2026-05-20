package com.onsemi.cim.apps.exensio.resender.repository;

import java.time.LocalDateTime;

/**
 * Aggregated metadata preview statistics (count + oldest/newest end_time).
 */
public record MetadataSummary(long total, LocalDateTime oldestEndTime, LocalDateTime latestEndTime) { }
