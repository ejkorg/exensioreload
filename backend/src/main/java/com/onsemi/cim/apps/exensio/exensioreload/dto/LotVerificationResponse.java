package com.onsemi.cim.apps.exensio.exensioreload.dto;

import java.util.Map;

public record LotVerificationResponse(
        Map<String, LotVerificationResult> lots,
        String error
) {}
