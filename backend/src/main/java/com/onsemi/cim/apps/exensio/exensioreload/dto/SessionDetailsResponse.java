package com.onsemi.cim.apps.exensio.exensioreload.dto;

import java.util.List;
import java.util.Map;

public record SessionDetailsResponse(
    Map<String, Object> session,
    List<SessionActivity> recentActivity,
    Map<String, Object> performance
) {}
