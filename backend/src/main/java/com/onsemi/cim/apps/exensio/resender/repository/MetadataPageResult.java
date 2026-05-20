package com.onsemi.cim.apps.exensio.resender.repository;

import java.util.List;


/**
 * Result holder for optimized paginated metadata queries.
 * Combines the paginated rows with the total count to avoid multiple DB round-trips.

 * @author fg8n8x
 */
public record MetadataPageResult(List<MetadataRow> rows, long total) {}
