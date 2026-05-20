package com.onsemi.cim.apps.exensio.resender.repository;

import com.onsemi.cim.apps.exensio.resender.entity.LoadSessionPayload;
import java.util.List;

public interface LoadSessionPayloadRepositoryCustom {
    List<LoadSessionPayload> claimNextBatch(Long sessionId, int batchSize);
}
