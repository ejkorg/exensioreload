package com.onsemi.cim.apps.exensio.exensioreload.dto;

import java.util.List;

public record LotVerificationRequest(
        List<String> lots,
        String site,
        String environment,
        String dataType
) {}
