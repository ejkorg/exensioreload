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
    /** @deprecated Use {@link #ready()} */
    @Deprecated public long stagedToRefdb() { return ready; }
    /** @deprecated Use {@link #enqueued()} */
    @Deprecated public long queuedForCp() { return enqueued; }
    /** @deprecated Use {@link #failed()} */
    @Deprecated public long cpFailed() { return failed; }
}
