package com.onsemi.cim.apps.exensio.resender.dto;

import java.util.List;

public record DiscoveryPreviewResponse(List<DiscoveryPreviewRow> items,
									   long total,
									   // How many rows are returned in this response (items.size())
									   int returned,
									   int page,
									   int size,
									   String debugSql,
									   // True when the server limited returned rows due to cap
									   boolean capped,
									   // True when the caller requested to bypass the preview cap
									   boolean bypass,
									   // Optional human-readable message for the client UI
									   String message) {}
