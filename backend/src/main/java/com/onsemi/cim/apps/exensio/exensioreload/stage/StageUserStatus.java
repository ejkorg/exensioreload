package com.onsemi.cim.apps.exensio.exensioreload.stage;

import java.time.Instant;

public record StageUserStatus(
        String username,
        long total,
        long ready,
        long enqueued,
        long failed,
        long completed,
        Instant lastRequestedAt
) {
}
