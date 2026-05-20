package com.onsemi.cim.apps.exensio.exensioreload.repository;

import com.onsemi.cim.apps.exensio.exensioreload.entity.LoadSessionPayload;
import java.util.List;

public interface LoadSessionPayloadRepositoryCustom {
    List<LoadSessionPayload> claimNextBatch(Long sessionId, int batchSize);
}
