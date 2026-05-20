package com.onsemi.cim.apps.exensio.exensioreload.stage;

import java.util.List;

public record StageStatus(
        String site,
        int senderId,
        String senderName,
        long total,
        long ready,
        long enqueued,
        long failed,
        long completed,
        List<StageUserStatus> users
) {
    public StageStatus {
        users = users == null ? List.of() : List.copyOf(users);
    }
}
