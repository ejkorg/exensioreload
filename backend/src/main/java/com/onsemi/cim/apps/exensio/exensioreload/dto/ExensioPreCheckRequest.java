package com.onsemi.cim.apps.exensio.exensioreload.dto;

import java.util.List;

public record ExensioPreCheckRequest(
        String environment,
        List<String> lotIds,
        List<String> waferIds,  // NEW: optional wafer IDs for wafer-level checking (Class 1, 4, 5, 14)
        List<PreCheckBlock> blocks,
        String dataType  // for PGC_KEY resolution
) {}
