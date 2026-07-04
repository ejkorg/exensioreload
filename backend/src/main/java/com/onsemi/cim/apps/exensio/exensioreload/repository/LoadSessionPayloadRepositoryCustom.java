package com.onsemi.cim.apps.exensio.exensioreload.repository;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import com.onsemi.cim.apps.exensio.exensioreload.entity.LoadSessionPayload;

public interface LoadSessionPayloadRepositoryCustom {
    List<LoadSessionPayload> claimNextBatch(Long sessionId, int batchSize);

    /**
     * Find all distinct non-NULL device values across all payloads.
     * Requirements: 2.5, 7.3
     * @return list of unique device identifiers
     */
    List<String> findDistinctDevices();

    /**
     * Find distinct non-NULL device values for a specific session.
     * Requirements: 2.5, 7.3
     * @param sessionId the session ID to query
     * @return list of unique device identifiers for the session
     */
    List<String> findDistinctDevicesBySessionId(Long sessionId);

    /**
     * Find payloads filtered by device identifiers with pagination.
     * Requirements: 7.2, 8.1
     * @param devices list of device identifiers to filter by
     * @param pageable pagination parameters
     * @return paginated results containing only payloads with matching devices
     */
    Page<LoadSessionPayload> findByDeviceIn(List<String> devices, Pageable pageable);

    /**
     * Find payloads for a session filtered by device identifiers with pagination.
     * Requirements: 3.2, 7.2
     * @param sessionId the session ID to query
     * @param devices list of device identifiers to filter by
     * @param pageable pagination parameters
     * @return paginated results containing only payloads with matching devices in the session
     */
    Page<LoadSessionPayload> findBySessionIdAndDeviceIn(Long sessionId, List<String> devices, Pageable pageable);
}
