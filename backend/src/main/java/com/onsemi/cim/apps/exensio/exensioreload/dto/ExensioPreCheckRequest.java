package com.onsemi.cim.apps.exensio.exensioreload.dto;

import java.util.List;

public record ExensioPreCheckRequest(
        String environment,
        List<String> lotIds,
        List<PreCheckBlock> blocks,
        String dataType  // NEW: for PGC_KEY resolution
) {}
