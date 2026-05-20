package com.onsemi.cim.apps.exensio.exensioreload.dto;

import java.util.List;
/**
 * @author fg8n8x
 */
public record SessionAnalyticsResponse(
        String sessionId,
        List<SessionDailyStatusPoint> dailyStatus,
        List<SessionLotWaferPairTotal> topLotWaferPairs,
        List<SessionLotWaferDailyPoint> lotWaferHeatmap
) {
}
