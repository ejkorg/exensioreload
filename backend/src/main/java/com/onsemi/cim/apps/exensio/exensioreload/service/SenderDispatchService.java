package com.onsemi.cim.apps.exensio.exensioreload.service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.onsemi.cim.apps.exensio.exensioreload.config.ExternalDbConfig;
import com.onsemi.cim.apps.exensio.exensioreload.config.RefDbProperties;
import com.onsemi.cim.apps.exensio.exensioreload.pipeline.PipelineConfig;
import com.onsemi.cim.apps.exensio.exensioreload.pipeline.PipelineConfigCache;
import com.onsemi.cim.apps.exensio.exensioreload.pipeline.StageDefinition;
import com.onsemi.cim.apps.exensio.exensioreload.pipeline.StageType;
import com.onsemi.cim.apps.exensio.exensioreload.stage.StageRecord;

import jakarta.annotation.PostConstruct;

@Service
public class SenderDispatchService {
    private static final Logger log = LoggerFactory.getLogger(SenderDispatchService.class);

    private final RefDbService refDbService;
    private final ExternalDbConfig externalDbConfig;
    private final RefDbProperties properties;
    private final StageSessionService stageSessionService;
    private final PipelineConfigCache pipelineConfigCache;

    public SenderDispatchService(RefDbService refDbService, ExternalDbConfig externalDbConfig, RefDbProperties properties, StageSessionService stageSessionService, PipelineConfigCache pipelineConfigCache) {
        this.refDbService = refDbService;
        this.externalDbConfig = externalDbConfig;
        this.properties = properties;
        this.stageSessionService = stageSessionService;
        this.pipelineConfigCache = pipelineConfigCache;
    }

    @PostConstruct
    public void logStartup() {
        log.info("Sender dispatch service initialized with perSend={} intervalMs={}ms", properties.getDispatch().getPerSend(), properties.getDispatch().getIntervalMs());
    }

    @Scheduled(fixedDelayString = "${refdb.dispatch.interval-ms:60000}")
    public void dispatch() {
        try {
            Set<String> sites = refDbService.findSitesWithPending();
            if (sites.isEmpty()) {
                return;
            }
            for (String site : sites) {
                processSite(site);
            }
        } catch (Exception ex) {
            log.error("Dispatch run failed", ex);
        }
    }

    private void processSite(String site) {
        int limit = properties.getDispatch().getPerSend();
        List<StageRecord> batch = refDbService.fetchNextBatchForSite(site, limit);
        if (batch.isEmpty()) {
            return;
        }
        
        // Check if site has pipeline configuration
        Optional<PipelineConfig> pipelineConfig = Optional.empty();
        try {
            pipelineConfig = pipelineConfigCache.getConfig(site);
        } catch (Exception ex) {
            log.warn("Failed to load pipeline config for site {}: {}", site, ex.getMessage());
        }
        
        // If site has pipeline, check if CP is the first stage
        if (pipelineConfig.isPresent()) {
            StageDefinition firstStage = pipelineConfig.get().getFirstStage();
            if (firstStage != null && firstStage.type() != StageType.CP) {
                // Pipeline exists but CP is NOT the first stage
                // Route records directly to the first stage monitoring status
                log.info("Site {} has pipeline with first stage {} (not CP) - routing {} records directly to monitoring", 
                    site, firstStage.type(), batch.size());
                routeToFirstPipelineStage(batch, firstStage);
                return;
            }
            // If first stage IS CP, continue with normal CP dispatch below
            log.debug("Site {} has pipeline with CP as first stage - proceeding with CP dispatch", site);
        }
        
        // Normal CP dispatch for legacy sites or sites with CP as first stage
        Map<Integer, List<StageRecord>> bySender = new HashMap<>();
        for (StageRecord record : batch) {
            bySender.computeIfAbsent(record.senderId(), key -> new ArrayList<>()).add(record);
        }
        for (Map.Entry<Integer, List<StageRecord>> entry : bySender.entrySet()) {
            pushGroup(site, entry.getKey(), entry.getValue());
        }
    }
    
    /**
     * Route records directly to the first pipeline stage monitoring status.
     * Used when pipeline exists but CP is not the first stage.
     */
    private void routeToFirstPipelineStage(List<StageRecord> records, StageDefinition firstStage) {
        if (records.isEmpty()) {
            return;
        }
        
        String targetStatus = switch (firstStage.type()) {
            case PPLOG -> "PPLOG_MONITORING";
            case EXENSIO -> "EXENSIO_MONITORING";
            case CP -> "CP_MONITORING"; // Shouldn't reach here but handle it
            default -> {
                log.warn("Unknown first stage type {} - defaulting to EXENSIO_MONITORING", firstStage.type());
                yield "EXENSIO_MONITORING";
            }
        };
        
        log.info("Routing {} records to {} status (first pipeline stage: {})", 
            records.size(), targetStatus, firstStage.type());
        
        // Update each record to the target status
        for (StageRecord record : records) {
            refDbService.updateRecordStatus(record.id(), targetStatus);
        }
        
        // Refresh sessions to update UI
        List<String> requestIds = records.stream()
            .map(StageRecord::requestId)
            .distinct()
            .toList();
        stageSessionService.refreshSessions(requestIds);
    }

    private void pushGroup(String site, int senderId, List<StageRecord> records) {
        if (records.isEmpty()) {
            return;
        }
        int maxQueueSize = properties.getDispatch().getMaxQueueSize();
        List<Long> success = new ArrayList<>();
        try (Connection connection = externalDbConfig.getConnection(site)) {
            boolean useSequence = requiresSequence(connection);
            List<StageRecord> toDispatch = records;
            if (maxQueueSize > 0) {
                int existing = safeCountQueue(connection, senderId);
                int available = maxQueueSize - existing;
                if (available <= 0) {
                    log.info("Queue for site {} sender {} already at capacity {} ({} existing)", site, senderId, maxQueueSize, existing);
                    return;
                }
                if (records.size() > available) {
                    log.info("Dispatch for site {} sender {} limited to {} of {} staged records due to queue threshold {}", site, senderId, available, records.size(), maxQueueSize);
                    toDispatch = new ArrayList<>(records.subList(0, available));
                }
            }

            if (toDispatch.isEmpty()) {
                return;
            }

            String insertSql;
            if (useSequence) {
                insertSql = "INSERT INTO DTP_SENDER_QUEUE_ITEM (id, id_metadata, id_data, id_sender, record_created) VALUES (?, ?, ?, ?, ?)";
            } else {
                insertSql = "INSERT INTO DTP_SENDER_QUEUE_ITEM (id_metadata, id_data, id_sender, record_created) VALUES (?, ?, ?, ?)";
            }
            try (PreparedStatement insert = connection.prepareStatement(insertSql)) {
                for (StageRecord record : toDispatch) {
                    try {
                        Timestamp now = Timestamp.from(Instant.now());
                        if (useSequence) {
                            long queueId = nextQueueId(connection);
                            insert.setLong(1, queueId);
                            insert.setString(2, record.metadataId());
                            insert.setString(3, record.dataId());
                            insert.setInt(4, senderId);
                            insert.setTimestamp(5, now);
                        } else {
                            insert.setString(1, record.metadataId());
                            insert.setString(2, record.dataId());
                            insert.setInt(3, senderId);
                            insert.setTimestamp(4, now);
                        }
                        insert.executeUpdate();
                        success.add(record.id());
                    } catch (SQLException ex) {
                        if (isDuplicate(ex)) {
                            log.info("Duplicate detected for {} – marking as processing", record);
                            success.add(record.id());
                        } else {
                            log.error("Failed pushing record {}", record, ex);
                            String errorMsg = ex.getMessage() != null ? ex.getMessage() : "Database push failed";
                            String contextMessage = "[Preprocessing Failure] " + errorMsg;
                            refDbService.markCpFailed(record.id(), contextMessage);
                        }
                    }
                }
            }
        } catch (SQLException ex) {
            log.error("Connection failure pushing site {} sender {}", site, senderId, ex);
            String errorMsg = ex.getMessage() != null ? ex.getMessage() : "Database connection failed";
            String contextMessage = "[Preprocessing Failure] " + errorMsg;
            for (StageRecord record : records) {
                refDbService.markCpFailed(record.id(), contextMessage);
            }
            return;
        }
        if (!success.isEmpty()) {
            List<StageRecord> dispatchedRecords = records.stream()
                    .filter(r -> success.contains(r.id()))
                    .toList();
            if (!dispatchedRecords.isEmpty()) {
                refDbService.markEnqueuedRecords(dispatchedRecords);
                stageSessionService.refreshSessions(dispatchedRecords.stream().map(StageRecord::requestId).toList());
            } else {
                refDbService.markEnqueuedRecords(
                        records.stream().filter(r -> success.contains(r.id())).toList());
            }
        }
    }

    private boolean isDuplicate(SQLException ex) {
        return ex.getErrorCode() == 1 || (ex.getMessage() != null && ex.getMessage().toUpperCase().contains("UNIQUE"));
    }

    private int safeCountQueue(Connection connection, int senderId) {
        String sql = "SELECT COUNT(1) FROM DTP_SENDER_QUEUE_ITEM WHERE id_sender = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, senderId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        } catch (SQLException ex) {
            log.warn("Failed counting queue for sender {}: {}", senderId, ex.getMessage());
        }
        return 0;
    }

    private long nextQueueId(Connection connection) throws SQLException {
        String productName = connection.getMetaData().getDatabaseProductName();
        boolean oracle = productName != null && productName.toLowerCase(java.util.Locale.ROOT).contains("oracle");
        String sql = oracle ? "SELECT DTP_SENDER_QUEUE_ITEM_SEQ.NEXTVAL FROM dual" : "SELECT NEXT VALUE FOR DTP_SENDER_QUEUE_ITEM_SEQ";
        try (PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                return rs.getLong(1);
            }
        }
        throw new SQLException("Unable to fetch next value from DTP_SENDER_QUEUE_ITEM_SEQ");
    }

    private boolean requiresSequence(Connection connection) {
        try {
            String productName = connection.getMetaData().getDatabaseProductName();
            if (productName == null) {
                return false;
            }
            return productName.toLowerCase(java.util.Locale.ROOT).contains("oracle");
        } catch (SQLException ex) {
            log.warn("Failed resolving database product name; assuming identity inserts are supported: {}", ex.getMessage());
            return false;
        }
    }

    public int dispatchSender(String site, int senderId) {
        return dispatchSender(site, senderId, null);
    }

    public int dispatchSender(String site, int senderId, Integer limitOverride) {
        int configuredPerSend = properties.getDispatch().getPerSend();
        int defaultBatchSize = configuredPerSend > 0 ? configuredPerSend : 200;
        int remaining = (limitOverride != null && limitOverride > 0) ? limitOverride : Integer.MAX_VALUE;
        int processed = 0;
        
        // Check if site has pipeline configuration
        Optional<PipelineConfig> pipelineConfig = Optional.empty();
        try {
            pipelineConfig = pipelineConfigCache.getConfig(site);
        } catch (Exception ex) {
            log.warn("Failed to load pipeline config for site {}: {}", site, ex.getMessage());
        }
        
        // Check if pipeline has non-CP first stage
        boolean shouldRouteToPipeline = false;
        StageDefinition firstStage = null;
        if (pipelineConfig.isPresent()) {
            firstStage = pipelineConfig.get().getFirstStage();
            shouldRouteToPipeline = firstStage != null && firstStage.type() != StageType.CP;
        }

        while (true) {
            if (remaining <= 0) {
                break;
            }
            int requestedBatch = Math.min(defaultBatchSize, remaining);
            if (requestedBatch <= 0) {
                break;
            }
            List<StageRecord> batch = refDbService.fetchNextBatchForSender(site, senderId, requestedBatch);
            if (batch.isEmpty()) {
                break;
            }
            
            if (shouldRouteToPipeline) {
                // Route to first pipeline stage instead of CP dispatch
                routeToFirstPipelineStage(batch, firstStage);
            } else {
                // Normal CP dispatch
                pushGroup(site, senderId, batch);
            }
            
            processed += batch.size();
            remaining -= batch.size();
            if (batch.size() < requestedBatch) {
                break;
            }
        }
        return processed;
    }
}
